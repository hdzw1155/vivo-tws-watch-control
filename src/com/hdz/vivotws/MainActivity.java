package com.hdz.vivotws;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/** vivo 耳机 App 风格: 卡片式深色布局 + 三选一圆形模式按钮 + 三段电量条 */
public class MainActivity extends Activity implements GaiaClient.Listener {

    private static final int REQ_PERM = 1;

    private static final int COL_SEL = Color.rgb(0x34, 0x78, 0xF6);   // vivo 蓝
    private static final int COL_LABEL_GRAY = Color.rgb(0x8E, 0x8E, 0x93);
    private static final int COL_ICON_GRAY = Color.rgb(0x9A, 0x9A, 0x9E);
    private static final int COL_GREEN = Color.rgb(0x34, 0xC7, 0x59); // 电量绿
    private static final int COL_RED = Color.rgb(0xFA, 0x51, 0x51);   // 低电量红
    private static final int COL_TRACK = Color.rgb(0x3A, 0x3A, 0x3C); // 电量槽灰

    private final Handler ui = new Handler(Looper.getMainLooper());
    private GaiaClient client;
    private TextView status;
    private TextView sub;
    private ImageView imgOff;
    private ImageView imgTrans;
    private ImageView imgAnc;
    private TextView lblOff;
    private TextView lblTrans;
    private TextView lblAnc;
    private TextView pctL;
    private TextView pctR;
    private TextView pctC;
    private ProgressBar barL;
    private ProgressBar barR;
    private ProgressBar barC;
    private int lastMode = -1;
    private long connectStartAt = 0;
    private Runnable refresher;
    private Runnable reconnectTask;
    private ScrollView scrollHost;
    // 表冠滚动泵: 事件增量累进 pending,泵每 16ms 逐帧逼近目标;
    // 若每个事件各起一段 smooth 动画,动画反复重启就会一顿一顿
    private float crownPendingPx;
    private boolean crownPumping;
    private float crownTickAcc;
    private static final float CROWN_DETENT_DP = 36f; // 转满一格滚这么多,震一下(与滚动条滑块移动感一致)
    private final Runnable crownPump = new Runnable() {
        @Override public void run() {
            if (scrollHost == null || isFinishing() || isDestroyed()
                    || crownPendingPx == 0f) {
                crownPumping = false;
                return;
            }
            float move = crownPendingPx * 0.35f; // 指数缓动:越接近目标越慢
            if (Math.abs(move) < 1f) move = Math.signum(crownPendingPx);
            int y = scrollHost.getScrollY();
            int max = Math.max(0,
                    scrollHost.getChildAt(0).getHeight() - scrollHost.getHeight());
            if ((y <= 0 && move < 0) || (y >= max && move > 0)) {
                crownPendingPx = 0; // 已顶到头,别空转
                crownPumping = false;
                return;
            }
            scrollHost.scrollBy(0, (int) move);
            crownPendingPx -= move;
            ui.postDelayed(this, 16);
        }
    };
    private Switch swSpatial;
    private boolean spatialGuard;
    private long spatialSetAt = 0;
    // DeepX 音效: 真实模式值 0/1/2/3/5(0x04 被官方跳过,悠扬听书=5),官方协议文档实锤
    private static final String[] DEEPX_NAMES =
            {"标准", "清澈人声", "超重低音", "清亮高音", "悠扬听书"};
    private static final int[] DEEPX_MODES = {0, 1, 2, 3, 5};

    /** DeepX 模式值 → 显示名(查表,未知值返回 未知) */
    private static String deepxNameFor(int mode) {
        for (int i = 0; i < DEEPX_MODES.length; i++) {
            if (DEEPX_MODES[i] == mode) return DEEPX_NAMES[i];
        }
        return "未知";
    }


    private Button btnDeepx;
    private int deepxMode = -1;   // 当前档位(数组下标)
    private long deepxSetAt = 0;
    private View devSection;
    private int devTaps = 0;
    private long lastTapAt = 0;
    private boolean disclaimerOk = false;
    private boolean disclaimerDialogUp = false;

    /** 进程是否活着主界面(磁贴切换服务收工时看它决定要不要带走空壳进程) */
    static volatile boolean resumed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        sub = findViewById(R.id.sub);
        imgOff = findViewById(R.id.img_off);
        imgTrans = findViewById(R.id.img_trans);
        imgAnc = findViewById(R.id.img_anc);
        lblOff = findViewById(R.id.lbl_off);
        lblTrans = findViewById(R.id.lbl_trans);
        lblAnc = findViewById(R.id.lbl_anc);
        pctL = findViewById(R.id.pct_l);
        pctR = findViewById(R.id.pct_r);
        pctC = findViewById(R.id.pct_c);
        barL = findViewById(R.id.bar_l);
        barR = findViewById(R.id.bar_r);
        barC = findViewById(R.id.bar_c);
        View refresh = findViewById(R.id.btn_refresh);
        swSpatial = findViewById(R.id.switch_spatial);
        btnDeepx = findViewById(R.id.btn_deepx);
        devSection = findViewById(R.id.dev_section);
        scrollHost = findViewById(R.id.scroll);

        // 开发者设置: 默认关闭,连点入口 5 下本会话内显示(不持久化)
        final TextView devHint = findViewById(R.id.dev_hint);
        devHint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long now = SystemClock.elapsedRealtime();
                devTaps = (now - lastTapAt > 2000) ? 1 : devTaps + 1;
                lastTapAt = now;
                if (devTaps >= 5 && devSection.getVisibility() != View.VISIBLE) {
                    devSection.setVisibility(View.VISIBLE);
                    Toast.makeText(MainActivity.this, R.string.dev_unlocked_toast,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        swSpatial.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (spatialGuard) return;
                spatialSetAt = SystemClock.elapsedRealtime();
                // 首次启用弹个别化风险确认,记住后不再打扰(履行个别警示义务)
                if (isChecked && !getSharedPreferences("cfg", MODE_PRIVATE)
                        .getBoolean("spatial_ack", false)) {
                    spatialGuard = true;
                    swSpatial.setChecked(false);
                    spatialGuard = false;
                    showSpatialDialog();
                    return;
                }
                Toast.makeText(MainActivity.this, R.string.spatial_toast, Toast.LENGTH_SHORT).show();
                if (client != null && client.isConnected()) {
                    client.setSpatial(isChecked);
                } else {
                    Toast.makeText(MainActivity.this, "未连接", Toast.LENGTH_SHORT).show();
                }
            }
        });
        // DeepX 五档循环按钮(真实值 0/1/2/3/5); 只切音效不伤耳,不加确认弹窗
        btnDeepx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (client == null || !client.isConnected()) {
                    Toast.makeText(MainActivity.this, "未连接", Toast.LENGTH_SHORT).show();
                    return;
                }
                deepxMode = (deepxMode + 1) % DEEPX_NAMES.length;
                deepxSetAt = SystemClock.elapsedRealtime();
                btnDeepx.setText(DEEPX_NAMES[deepxMode]);
                client.setDeepX(DEEPX_MODES[deepxMode]);
            }
        });

        findViewById(R.id.mode_off).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(GaiaClient.MODE_OFF); }
        });
        findViewById(R.id.mode_trans).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(GaiaClient.MODE_TRANS); }
        });
        findViewById(R.id.mode_anc).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(GaiaClient.MODE_ANC); }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (client != null && client.isConnected()) {
                    client.queryBattery();
                    client.queryNoise();
                } else {
                    tryConnect();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        tryConnect();
        // 阶梯轮询省电: 连上先快查两轮拿首屏,之后转 30 秒慢轮询;
        // 空间音频/DeepX 状态几乎不变,降到每 4 轮(约 2 分钟)问一次
        ui.removeCallbacks(refresher);
        refresher = new Runnable() {
            int tick = 0;

            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                tick++;
                if (client != null && client.isConnected()) {
                    client.queryBattery();
                    client.queryNoise();
                    if (tick == 1 || tick % 4 == 0) {
                        client.querySpatial();
                        client.queryDeepX();
                    }
                } else if (client == null
                        || SystemClock.elapsedRealtime() - connectStartAt > 12000) {
                    // 没有进行中的连接尝试才重连,避免打断慢连接
                    tryConnect();
                }
                // 前 2 轮 5 秒(首屏),之后 30 秒(省电)
                ui.postDelayed(this, tick <= 2 ? 5000L : 30000L);
            }
        };
        ui.postDelayed(refresher, 5000);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        // 表冠/表圈归页面滚动: ScrollView 不认 rotary 事件源,事件会漏到 Activity 这里,
        // 自己接住滚——焦点落在按钮上也不会被抢去切功能
        float delta = e.getAxisValue(MotionEvent.AXIS_SCROLL);
        if (scrollHost != null && delta != 0f) {
            float px = -delta * 55f * getResources().getDisplayMetrics().density;
            crownPendingPx += px;
            // 旋转刻度震动: 攒满一格滚一次钟表滴答,和系统列表的表冠手感一致
            crownTickAcc += px;
            float detent = CROWN_DETENT_DP * getResources().getDisplayMetrics().density;
            while (Math.abs(crownTickAcc) >= detent) {
                crownTickAcc -= Math.signum(crownTickAcc) * detent;
                scrollHost.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            if (!crownPumping) {
                crownPumping = true;
                ui.post(crownPump);
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // 收掉滚动泵,别在后台空转
        ui.removeCallbacks(crownPump);
        crownPumping = false;
        crownPendingPx = 0;
        crownTickAcc = 0;
        ui.removeCallbacks(refresher);
        if (reconnectTask != null) {
            ui.removeCallbacks(reconnectTask);
            reconnectTask = null;
        }
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    /** 首次启用空间音频的个别化风险确认框,确认后记住(spatial_ack) */
    private void showSpatialDialog() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.spatial_dialog_title)
                .setMessage(R.string.spatial_dialog_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_ok, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                                .putBoolean("spatial_ack", true).apply();
                        spatialSetAt = SystemClock.elapsedRealtime();
                        if (client != null && client.isConnected()) {
                            client.setSpatial(true);
                        }
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** 全局免责确认框: 同意后记录"同意时的版本号",
     *  升级新版本或卸载重装会重新弹出,同一版本连续使用不再打扰 */
    private void showDisclaimerDialog() {
        if (disclaimerDialogUp) return;
        disclaimerDialogUp = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.global_disclaimer_title)
                .setMessage(R.string.global_disclaimer_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_agree, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                                .putBoolean("disclaimer_ack", true).apply();
                        disclaimerOk = true;
                        disclaimerDialogUp = false;
                        tryConnect();
                    }
                })
                .setNegativeButton(R.string.dialog_refuse, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        disclaimerDialogUp = false;
                        finish();
                    }
                })
                .show();
    }

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 在已配对设备里挑目标: 先精确匹配 vivo/iQOO 家族命名,再兜底任意耳机 */
    static BluetoothDevice findTarget(BluetoothAdapter adapter) {
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return null;
        for (BluetoothDevice d : bonded) {
            String n = d.getName();
            if (n == null) continue;
            String ln = n.toLowerCase();
            if (ln.contains("vivo") || ln.contains("iqoo")
                    || ln.contains("tws") || ln.contains("air")) {
                return d;
            }
        }
        for (BluetoothDevice d : bonded) {
            ParcelUuid[] uuids = d.getUuids();
            if (uuids == null) continue;
            for (ParcelUuid u : uuids) {
                if (u.getUuid().equals(GaiaClient.A2DP_SINK_UUID)) return d;
            }
        }
        return null;
    }

    private void tryConnect() {
        // 全局免责确认: 首次安装弹一次,同意后一直用不再弹(用户明确要求)
        if (!disclaimerOk && !getSharedPreferences("cfg", MODE_PRIVATE)
                .getBoolean("disclaimer_ack", false)) {
            showDisclaimerDialog();
            return;
        }
        if (!hasPerm()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
            return;
        }
        if (client != null && client.isConnected()) return;
        // 灭掉残留的旧连接(含还在握手的线程),防止新旧两个客户端打架
        if (client != null) {
            client.disconnect();
            client = null;
        }

        BluetoothAdapter adapter =
                ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            clearData();
            status.setText("请先打开手表蓝牙");
            sub.setText("");
            return;
        }
        BluetoothDevice target = findTarget(adapter);
        if (target == null) {
            clearData();
            status.setText("未找到已配对的 vivo 耳机");
            sub.setText("请先在 手表设置-蓝牙 配对");
            return;
        }
        status.setText(R.string.status_connecting);
        sub.setTextColor(COL_LABEL_GRAY);
        sub.setText(target.getName());
        client = new GaiaClient(this);
        connectStartAt = SystemClock.elapsedRealtime();
        client.connect(target);
    }

    private void setMode(int mode) {
        if (client == null || !client.isConnected()) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show();
            tryConnect();
            return;
        }
        client.setNoiseMode(mode);
    }

    private void applyMode(ImageView img, TextView lbl, boolean sel) {
        img.setBackgroundResource(sel ? R.drawable.circle_sel : R.drawable.circle_norm);
        img.setImageTintList(ColorStateList.valueOf(sel ? Color.WHITE : COL_ICON_GRAY));
        lbl.setTextColor(sel ? COL_SEL : COL_LABEL_GRAY);
    }

    private void renderMode() {
        ui.post(new Runnable() {
            @Override public void run() {
                applyMode(imgOff, lblOff, lastMode == GaiaClient.MODE_OFF);
                applyMode(imgTrans, lblTrans, lastMode == GaiaClient.MODE_TRANS);
                applyMode(imgAnc, lblAnc, lastMode == GaiaClient.MODE_ANC);
            }
        });
    }

    /** 电量未知时耳机上报 0xFF,显示 -- */
    private void setBatt(final TextView pct, final ProgressBar bar,
                         final int v, final boolean charging) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (v >= 0 && v <= 100) {
                    pct.setText(charging ? (v + "%⚡") : (v + "%"));
                    bar.setProgress(v);
                    bar.setProgressTintList(
                            ColorStateList.valueOf(v <= 20 ? COL_RED : COL_GREEN));
                } else {
                    pct.setText("--");
                    bar.setProgress(0);
                }
                bar.setProgressBackgroundTintList(ColorStateList.valueOf(COL_TRACK));
            }
        });
    }

    // ---- GaiaClient.Listener (后台线程回调) ----

    @Override
    public void onConnected(final String deviceName) {
        TileStore.saveName(this, deviceName);
        TileStore.noteOk(this);
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(Color.WHITE);
                status.setText("已连接");
                sub.setTextColor(COL_LABEL_GRAY);
                sub.setText(deviceName);
            }
        });
    }

    @Override
    public void onBattery(final int left, final int right, final int box, final int chargeFlags) {
        setBatt(pctL, barL, left, (chargeFlags & 0x01) != 0);
        setBatt(pctR, barR, right, (chargeFlags & 0x02) != 0);
        setBatt(pctC, barC, box, (chargeFlags & 0x04) != 0);
        // 给系统磁贴留快照
        TileStore.saveBattery(this, left, right, box);
        TileStore.noteOk(this);
        TileStore.push(this);
    }

    @Override
    public void onNoiseMode(int mode) {
        lastMode = mode;
        renderMode();
        // 给降噪切换磁贴留快照
        if (mode >= 0 && mode < 3) TileStore.saveMode(this, mode);
        TileStore.push(this);
    }

    @Override
    public void onSpatial(final Boolean state) {
        if (state == null) return;
        ui.post(new Runnable() {
            @Override public void run() {
                // 手动设置后 4 秒内不回写,避免查询结果盖掉用户刚点的状态
                if (SystemClock.elapsedRealtime() - spatialSetAt < 4000) return;
                spatialGuard = true;
                swSpatial.setChecked(state);
                spatialGuard = false;
            }
        });
    }

    @Override
    public void onDeepX(final Integer mode) {
        if (mode == null) return;
        ui.post(new Runnable() {
            @Override public void run() {
                // 手动设置后 4 秒内不回写,避免查询结果盖掉用户刚点的状态
                if (SystemClock.elapsedRealtime() - deepxSetAt < 4000) return;
                String name = deepxNameFor(mode);
                btnDeepx.setText(name);
                if (!name.equals("未知")) {
                    // 反查下标,让下次点击从当前档继续循环
                    for (int i = 0; i < DEEPX_MODES.length; i++) {
                        if (DEEPX_MODES[i] == mode) { deepxMode = i; break; }
                    }
                }
            }
        });
    }

    /** 未连接时清空电量显示与模式选中态 */
    private void clearData() {
        setBatt(pctL, barL, -1, false);
        setBatt(pctR, barR, -1, false);
        setBatt(pctC, barC, -1, false);
        lastMode = -1;
        renderMode();
        deepxMode = -1;
        ui.post(new Runnable() {
            @Override public void run() {
                spatialGuard = true;
                swSpatial.setChecked(false);
                spatialGuard = false;
                btnDeepx.setText("--");
            }
        });
    }

    @Override
    public void onDisconnected(String reason) {
        clearData();
        // 断开也主动推一次磁贴,别等刷新周期(周期已放宽到5分钟)
        TileStore.push(this);
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(COL_LABEL_GRAY);
                status.setText(R.string.status_idle);
                sub.setText("");
            }
        });
        // 耳机开关机/走远断连后,3 秒后自动试着连回来;只在界面真在前台时才重连
        // (onPause 清完旧任务后这里才投新任务,不拦 resumed 会在后台偷偷连耳机占槽位)
        if (!isFinishing() && !isDestroyed() && resumed) {
            if (reconnectTask != null) ui.removeCallbacks(reconnectTask);
            reconnectTask = new Runnable() {
                @Override public void run() {
                    reconnectTask = null;
                    if (!isFinishing() && !isDestroyed()
                            && (client == null || !client.isConnected())) {
                        tryConnect();
                    }
                }
            };
            ui.postDelayed(reconnectTask, 3000);
        }
    }

    @Override
    public void onError(final String msg) {
        clearData();
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(COL_LABEL_GRAY);
                status.setText(msg);
                sub.setText("");
            }
        });
    }
}
