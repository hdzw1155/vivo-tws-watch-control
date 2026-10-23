#!/usr/bin/env node
// 从 btsnoop_hci.log 提取 vivo GAIA 私有帧。用法: node parse_btsnoop.js btsnoop_hci.log
// 原理: 不剥协议栈,直接在 HCI ACL 数据里扫帧签名 FF <verb> 00 <len> <vendor2B> <cmd2B> [payload]
const GAIA_CMDS = {
  0x0300: "握手", 0x8300: "握手应答",
  0x0207: "查电量", 0x8207: "电量上报",
  0x0230: "查降噪", 0x8230: "降噪上报", 0x0130: "设降噪", 0x8130: "降噪确认",
  0x0139: "设空间音频★", 0x0239: "查空间音频★",
  0x8139: "空间音频确认★", 0x8239: "空间音频上报★",
  0x014C: "设多连接", 0x0103: "设佩戴检测", 0x8203: "佩戴检测上报",
  0x0118: "设音效EQ", 0x0218: "查音效EQ", 0x8118: "EQ确认", 0x8218: "EQ上报",
};

function* gaiaFrames(buf) {
  let i = 0;
  while (i + 8 <= buf.length) {
    if (buf[i] !== 0xff || buf[i + 2] !== 0x00) { i++; continue; }
    const verb = buf[i + 1];
    if (verb !== 0x03 && verb !== 0x04) { i++; continue; }
    const len = buf[i + 3];
    const vendor = (buf[i + 4] << 8) | buf[i + 5];
    const cmd = (buf[i + 6] << 8) | buf[i + 7];
    if (vendor !== 0x001b && vendor !== 0x000a) { i++; continue; }
    if (i + 8 + len > buf.length) { i++; continue; }
    if ((cmd >= 0x0100 && cmd <= 0x0600) || (cmd >= 0x8100 && cmd <= 0x8600)) {
      yield { cmd, payload: buf.slice(i + 8, i + 8 + len), verb };
      i += 8 + len;
    } else i++;
  }
}

function main() {
  const file = process.argv[2];
  if (!file) { console.log("用法: node parse_btsnoop.js btsnoop_hci.log"); return; }
  const buf = require("fs").readFileSync(file);
  if (buf.slice(0, 8).toString("latin1") !== "btsnoop\x00") {
    console.log("[X] 不是 btsnoop 格式:", buf.slice(0, 8).toString("latin1")); return;
  }
  let off = 16, idx = 0, hits = 0;
  while (off + 24 <= buf.length) {
    const incl = buf.readUInt32BE(off + 4);
    const flags = buf.readUInt32BE(off + 8);
    const data = buf.slice(off + 24, off + 24 + incl);
    off += 24 + incl;
    const isTx = (flags & 1) === 0;
    for (const { cmd, payload, verb } of gaiaFrames(data)) {
      hits++;
      const name = GAIA_CMDS[cmd] || "未知0x" + cmd.toString(16).padStart(4, "0");
      const star = (cmd >= 0x0139 && cmd <= 0x0239) || (cmd >= 0x8139 && cmd <= 0x8239) ? "★" : " ";
      const dir = isTx ? "手机→耳机" : "耳机→手机";
      console.log(`${star} #${idx} ${dir} verb=${verb.toString(16).padStart(2, "0")} cmd=${cmd.toString(16).padStart(4, "0")}(${name}) len=${payload.length} payload=${payload.toString("hex").toUpperCase().match(/../g)?.join(" ")}`);
    }
    idx++;
  }
  console.log(`\n共 ${hits} 帧。重点看 ★ 行: 官方App开关空间音频时的 设空间音频★ 载荷就是答案。`);
}
main();
