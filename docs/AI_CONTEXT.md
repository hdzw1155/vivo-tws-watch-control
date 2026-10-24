# AI 开发经验注入包 — vivo TWS Air3 Pro × Wear OS 手表控制器

> 这份文档是给 AI 看的。如果你想让你的 AI 接着开发这个项目（或者做类似的事），
> 把整个文件复制粘贴给它就行。人类读者也能看，就是写得比较密。
> 出处：[hdzw1155/vivo-tws-watch-control](https://github.com/hdzw1155/vivo-tws-watch-control)。
> 参考或转载本文档请附上本仓库链接（详见 README 授权声明）。

## 0. 项目是什么

在 Wear OS 手表（实测三星 Galaxy Watch7，SM-L300，Android 16 / Wear OS 7）上，
不经手机直连 vivo TWS Air3 Pro 耳机，做降噪切换和电量显示。
纯 Java（无 lambda、无第三方依赖、Java 8 语法级），免 Gradle 手工构建，
两个 Java 文件 + 一套 XML 资源就是全部。

## 1. 30 秒上手

```bat
:: Windows, 一次性环境(约300MB)
scripts\setup-env.bat
:: 构建(免 Gradle, aapt2/javac/d8 手工链)
scripts\build.bat
:: 产物: vivo-tws-watch-debug.apk
adb install -r vivo-tws-watch-debug.apk
:: 手表需先在 设置-蓝牙 里与耳机配对(RFCOMM 安全通道要求已配对)
```

前提：手表开开发者选项 → ADB/无线调试。无线调试端口每次重开会变，
`adb connect` 失败先怀疑端口过期，让用户重看屏幕报新端口。

## 2. 蓝牙协议档案（vivo 私有 GAIA，全部来自社区逆向+本项目真机探测）

### 传输层
- 经典蓝牙 **RFCOMM**（不是 BLE GATT；iOS 官方 App 才走 GATT 且是裸 payload 无帧头）
- SDP 服务 UUID：`00000837-d102-11e1-9b23-00025b00a5a5`（高通 GAIA）
- SDP 发现失败时反射调用隐藏 API `createRfcommSocket(13)` 退回固定 channel 13
- Android 侧：`device.createRfcommSocketToServiceRecord(UUID)`，需要 BLUETOOTH_CONNECT 运行时权限

### 帧格式（8 字节头）
```
FF | VERB | 00 | LEN | VENDOR(2B 大端) | CMD(2B 大端) | PAYLOAD(LEN 字节)
```
- VERB：控制帧 v3（0x03），电量/握手 v4（0x04）；**用错 verb 耳机直接沉默**（不报错，表现为超时）
- VENDOR：握手用高通通用 0x000A，vivo 命令空间 0x001B
- 例：握手 `FF 04 00 00 00 0A 03 00`；查电量 `FF 04 00 00 00 1B 02 07`

### 命令总表（状态以 Air3 Pro 2026-07 固件为准）

| 命令 | SET | QUERY | ACK | 上报 | 状态 |
|---|---|---|---|---|---|
| 握手 | 0x0300 | - | - | 0x8300 | ✅ 实测 |
| 电量 | - | 0x0207(恒v4) | - | 0x8207 | ✅ 实测 |
| 降噪 | 0x0130 | 0x0230 | 0x8130 | 0x8230 | ✅ 实测 |
| 空间音频开关 | 0x0139 | 0x0239 | 0x8139 | 0x8239 | ⚠ 半验证（见 §4） |
| DeepX 音效 | 0x0118 | 0x0218 | 0x8118 | 0x8218 | ✅ 实测 |
| 空间音效预设 | 0x013E | 0x023E | - | - | ❌ 无应答，弃 |
| 佩戴检测开关 | 0x0103 | 0x0203 | 0x8103 | 0x8203 | 文档实测(3e) |
| 多连接 | 0x014C | 0x0249 | 0x814C | 0x8249 | TWS5 验证 |

### payload 语义（已验证部分）
- 电量 0x8207：`[00, 左, 右, 盒, 充电标志]`，**0xFF=未上报**（耳机没戴/盒没开时），
  充电标志 bit0/1/2 = 左/右/盒在充电
- 降噪 SET：`[mode, 尾缀]`，mode 00=降噪 01=关 02=通透（全机型统一）；
  尾缀按机型：Air3 Pro=`04 00`，TWS 3e=`03`，家族默认(=TWS4)=`03 01`
- DeepX SET：`[mode]` 单字节，**真实值 0/1/2/3/5（0x04 被官方跳过！）**：
  0=标准 1=清澈人声 2=超重低音 3=清亮高音 5=悠扬听书
- 空间音频 SET：`[01]`开 `[00]`关；上报 `[00, 状态, 01]`，状态随摘戴自动变
- 降噪上报 0x8230：`[00, mode, 效果, 效果]`

### 机型判定
按蓝牙设备名归一化（去非字母数字）匹配：含 `air3` → Air3 配置；含 `3e`/`tws3` → 3e 配置；
其余 → 家族默认 v4。识别不到 vivo/iQOO 命名时兜底挑任意带 A2DP Sink UUID 的已配对设备。

## 3. 未知指令探测方法论（本项目最值钱的经验）

1. **先让日志说话**：读线程里对所有未知 vivo 帧打 `Log.d` 原始 hex（本项目 GaiaClient 的
   unknown 分支）。手表连着 `adb logcat -s GaiaClient` 就是免费的协议分析仪。
2. **探针轮换**：payload 格式未知时，写一个带 `probeIdx` 的 SET 函数，每次调用轮换一种
   候选（verb×payload 组合），发送前打标记日志。点 N 次按钮=N 种候选全试完。
   候选集参考：`[v]`、`[v,00]`、`[00,v]`、`[v,尾缀同降噪]`，verb 3/4 都试。
3. **官方 App 交叉对照**：手表链路连着时，用户在手机官方 App 里改设置，耳机会主动推
   上报帧——手表的 logcat 能直接看到官方改动的结果（本项目空间音频的 state 字节就是这么读懂的）。
4. **判定标准**：发 SET 后看三样——有没有 ACK（命令号对不对）、上报有没有变（payload 对不对）、
   用户耳朵有没有感觉（功能真的生效没）。三者可以不一致，都记录下来。
5. **沉默≠不支持**：也可能是 verb 用错、前置条件没开（比如预设可能要求总开关先开）、
   或该型号固件没实现。换 verb 再试，还不行就真弃。

### 失败档案（别再踩）
- 0x013E 空间音效预设：5 种候选全沉默（含空间音频已开时），Air3 Pro 上不存在此路径
- 0x010C set_anc_mode：只回 ACK 不切模式（前人已证伪，切模式必须 0x0130）
- 0x0218 曾被误标"空间音效"，实锤是 DeepX/EQ 预设
- 空间音频 0x0139：状态字节会随摘戴自动翻转，SET 能改状态但作者听不出效果——
  待官方 App 抓 HCI 日志（btsnoop）对照后才能定性

## 4. Wear OS 工程经验

- **连接策略**：手表只当遥控器，不开音频通道。App 在前台才连，onPause 断开省电；
  耳机双设备位=手机+手表正好占满，用户想连第三台时要先断手表
- **断线自愈**：耳机开关机后 socket 半死不活（isConnected 可能还是 true）。做法：
  reader 线程退出时真正 close socket；UI 层 5 秒周期自检 + onDisconnected 后 3 秒重连；
  tryConnect 前先 disconnect 掉残留 client（否则新旧两个客户端打架，表现为"反复开关耳机后不刷新"）
- **弹窗结论**（做过三轮，最终放弃）：Wear OS 不支持 FullScreenIntent 全屏通知（官方文档明说）；
  直拉全屏 Activity 需要 SYSTEM_ALERT_WINDOW 授权（adb `appops set ... allow`），
  且弹完会把应用任务栈带到前台、Samsung 后台限制偶尔拦；高优先级通知 peek 卡在
  三星表上只进通知流不浮层。（三版代码曾存 popup-dev 分支，后已删）
- **重连任务必须可取消**（踩过的坑）：onDisconnected 里 postDelayed 的重连任务要存字段，
  onPause 时 removeCallbacks——否则断连后 3 秒内切后台，会在后台重连并一直占着耳机
  的 RFCOMM 连接位（耗电+顶掉第三台设备）
- **旧连接回调守卫**：所有 fire 回调（电量/模式/错误等）开头检查 `if (closing) return;`
  ——旧 client 被新 client 顶掉后，残留读线程的最后一帧不该再上 UI
- **多监听者**：GaiaClient 用 CopyOnWriteArrayList 支持多 Listener（页面+后台服务共用连接）
- **UI**：432×432 圆屏，卡片式深色布局，ScrollView 记得 `scrollbars="none"`（默认滚动条
  在圆屏边缘像残影）。396/450 尺寸用 `wm size` 模拟验证过，自适应权重布局无裁切
- **开关类 UI**：未知/异步状态用循环 Button 比 Switch 稳（Switch 有回写抖动问题，
  需要 guard 标志 + 4 秒抑制窗口）

## 4.5 系统磁贴（Tiles）实战——本项目坑最密的一块

依赖 androidx.wear.tiles **1.1.0**（经典 Java 写法，新系统完全兼容，磁贴由系统渲染）。
依赖链极小：tiles + tiles-proto + annotation + annotation-experimental + listenablefuture。
注意 listenablefuture 只在 **Maven Central**（repo1.maven.org），Google Maven 上是 404。

**黑屏磁贴的根因（排查了一天）**：库 jar 必须作为 **d8 的输入**（位置参数）一起打进 dex；
`--lib` 只用于解析引用、不会打包。库不在包里 → 系统加载磁贴类时 ClassNotFound → 全黑。

tiles 1.1 的 API 和新版教程差异（全踩过）：
- Builder 用 `new Box.Builder()`，没有静态工厂 `Box.Builder()`
- 没有 `ArcAnchor`/`ArcLength`（1.2 才有）；环内居中文字做不了 → 用**运行时 Canvas 画 PNG**
  经 `InlineImageResource` 塞进资源（`setData/setWidthPx/setHeightPx`，format 用
  `IMAGE_FORMAT_UNDEFINED` 让渲染器自动识别）
- 图片映射要包两层：`ImageResource.Builder().setAndroidResourceByResId(AndroidImageResourceByResId...)`
- `Clickable` 必须 `setId(...)`，否则部分渲染器点击无效
- 磁贴按钮的跳转目标 Activity 必须 `android:exported="true"`（点击由系统发起，未导出=静默无反应）

**Canvas 画字五轮才修好的坑**：画完弧线后 Paint 停在 STROKE 模式，直接 drawText 出来的
全是描边轮廓（一团白色乱码）。写字前必须 `p.setStyle(Paint.Style.FILL)` 复位。

**磁贴数据流**：磁贴进程读不到耳机的实时状态，用两条路：
1. App/无界面切换器把快照写进 SharedPreferences（tile_batt_l/r/c、tile_mode 等），
   磁贴 onTileRequest 时读；
2. 连接状态用 TileState 实时查（记住耳机 MAC → 反射 `BluetoothDevice.isConnected()` →
   GATT 已连接列表 → 30 分钟内成功通讯过的 last_ok 时间戳兜底——半入耳双链路空闲时
   系统查询会漏判，单一手段都会误报"未连接"）
数据变化后用 `TileService.getUpdater(ctx).requestUpdate(XxxTile.class)` 立即重画，
不用等 freshnessIntervalMillis（设的 2 分钟）。

**磁贴点击直切模式**：跳转一个透明无 UI 的 exported Activity（`Theme.Translucent.NoTitleBar`
+ excludeFromRecents + noHistory），onConnected 后 setNoiseMode、收到确认即 finish，
8 秒超时兜底。磁贴按钮用 `addKeyToExtraMapping("target_mode", AndroidIntExtra)` 指定
目标档位——点谁切谁，不是循环。

**尺寸事实**：公开 Tiles API 的磁贴在表盘上永远占满一个槽位；One UI 8 的"半尺寸卡片"
是三星私有框架，第三方做不了。内容往竖版 Column 里排，间距 6~8dp 收紧即可。

## 5. 免 Gradle 构建的坑（Windows）

1. **`.bat` 必须纯 ASCII**（最重要的一条，血泪教训）：`chcp 65001` + 中文注释会让 cmd
   解析器随机错位——文件内容一变（几个字节）解析位置就漂移，后面的步骤跳着执行、
   构建时好时坏，而且表现为"成功"（装的是旧包）。build.bat 全英文注释、不写 chcp，
   一劳永逸。中文输出提示也别写在 bat 里
2. `.bat` 必须 CRLF 换行；sed 改完记得转回（sed 会洗成 LF）
3. javac 的 `@响应文件` 会吃反斜杠和中文路径 → 改用 `for /r` 枚举源文件、逐个加引号拼命令行
4. Android 34 的 platform 包**没有 core-lambda-stubs.jar** → 源码别用 lambda（本项目全匿名内部类）
5. `-source 11` 不让用 `-bootclasspath` → 用 `-source 8 -target 8`
6. d8 前先 `jar cf` 打包 classes 再喂给它；classes.dex 用 PowerShell 的 ZipFile API 塞进 APK
7. 构建前 `rmdir build` 失败要显式报错退出（目录被占用时旧产物会让"构建成功"是假的）
8. **验证构建真的成功**：改资源后从产物里抽文件对比（`tar -xf apk res/...`），
   别信 "[OK]" 输出——见第 1 条，解析错位时会打印假 OK

## 6. 法律合规结构（为什么 App 里到处是弹窗）

- MIT LICENSE（纯标准文本，GitHub 才能识别徽标）+ NOTICE（中文善意说明）+ README 授权声明（分层：文档强制署名/代码鼓励/禁商业售卖/厂商绿色通道）
- 首启全局免责弹窗**按版本号记忆**（升级/重装必弹）——堵"侧载用户没读过 README"的漏洞
- 实验功能另有首次确认框（个别化警示义务）+ 操作处常驻警告 + 摘耳机安全提示
- 人身伤害不可依格式条款免责（民法典 506 条），所以听力警告写得很重

## 7. 待办与线索

- 当前版本 0.3.0（versionCode 13）；免责弹窗按 versionCode 记忆，改版本号会重新弹
- DeepX 的 3=清亮高音、5=悠扬听书 未实测（0/1/2 已验证）；想验证照 §3 方法点两下按钮看日志
- 空间音频想定性：需要手机开"蓝牙 HCI 信息收集"抓官方 App 的 btsnoop 对照
  （vivo 手机 dumpstate 被封，bugreport 拿不到日志，需 root 或换机型）
- 仓库 scripts/parse_btsnoop.js：写好的 btsnoop GAIA 帧扫描器，抓到日志直接 `node` 跑
- 更多机型：TWS-Pods-PC 仓库有 huawei/ xiaomi/ moondrop/ 同族文档可挖
  （多品牌续作见本地姊妹项目 tws-control-for-wear，注意其华为/小米协议层未实测）
- 图标：res/mipmap/ic_launcher.png，用户偏好小尺寸原图（80×80 即可，别上高清大图）

## 8. 给你的 AI 的引导提示词（复制即用）

```text
我要基于开源项目 hdzw1155/vivo-tws-watch-control 继续开发（Wear OS 手表控制
vivo TWS 耳机）。请先读取仓库里的 docs/AI_CONTEXT.md，那是前人整理的完整开发
经验（协议格式/命令表/探测方法论/构建坑），全部按它说的来：
1. 修改代码前先读 GaiaClient.java 和 MainActivity.java，保持纯 Java 匿名内部类
   风格（无 lambda），改动后用 scripts\build.bat 验证编译（注意 .bat 要 CRLF）。
2. 涉及未知蓝牙指令时，用文档 §3 的探针法，不要瞎猜 payload。
3. 手表通过 adb 无线调试连接（端口会变，连不上让用户重新报端口），实测通过才
   算完成，不要只编译过就交付。
4. 发版遵守仓库 README 的授权声明，实验功能必须带摘耳机警告。
我现在要做的改动是：【在这里写你的需求】
```
