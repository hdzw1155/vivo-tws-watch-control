package com.hdz.vivotws;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * vivo TWS (Air3 Pro 实机验证) GAIA-over-RFCOMM 客户端。
 * 帧格式: FF | VERB | 00 | LEN | VENDOR(2,大端) | CMD(2,大端) | PAYLOAD(LEN)
 * 协议来源: HyperEars / TWS-Pods-PC 公开逆向文档。
 */
public class GaiaClient {

    public static final UUID GAIA_UUID =
            UUID.fromString("00000837-d102-11e1-9b23-00025b00a5a5");

    /** A2DP Sink, 用于兜底识别任意已配对耳机 */
    public static final UUID A2DP_SINK_UUID =
            UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb");

    public static final int VENDOR_QUALCOMM = 0x000A;
    public static final int VENDOR_VIVO = 0x001B;

    public static final int CMD_HANDSHAKE = 0x0300;
    public static final int CMD_NOISE_SET = 0x0130;
    public static final int CMD_NOISE_QUERY = 0x0230;
    public static final int CMD_BATT_QUERY = 0x0207;

    public static final int MODE_ANC = 0x00;
    public static final int MODE_OFF = 0x01;
    public static final int MODE_TRANS = 0x02;

    /** 机型线格式差异,参数来自 HyperEars VivoTwsProtocol.WireConfig */
    public static final int CFG_AIR3_PRO = 0;  // v3, 设置载荷 mode 04 00 (实机抓包)
    public static final int CFG_FAMILY_V4 = 1; // v4, mode 03 01 (家族默认画像)
    public static final int CFG_TWS_3E = 2;    // v3, mode 03 (TWS 3e 文档)

    /** 按蓝牙设备名猜机型线格式,未知名字用家族默认 */
    public static int cfgForName(String name) {
        if (name == null) return CFG_FAMILY_V4;
        String n = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (n.contains("air3")) return CFG_AIR3_PRO;
        if (n.contains("3e")) return CFG_TWS_3E;
        return CFG_FAMILY_V4;
    }

    public static final int RESP_NOISE_SET = 0x8130;
    public static final int RESP_NOISE_QUERY = 0x8230;
    public static final int RESP_BATT = 0x8207;

    public interface Listener {
        void onConnected(String deviceName);

        void onDisconnected(String reason);

        void onError(String msg);

        void onBattery(int left, int right, int box, int chargeFlags);

        void onNoiseMode(int mode);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean closing = false;
    private int cfg = CFG_FAMILY_V4;

    public GaiaClient(Listener listener) {
        listeners.add(listener);
    }

    /** 支持多订阅者(页面 + 弹窗服务),回调发生在后台线程 */
    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fireConnected(String name) {
        for (Listener l : listeners) l.onConnected(name);
    }

    private void fireDisconnected(String reason) {
        for (Listener l : listeners) l.onDisconnected(reason);
    }

    private void fireError(String msg) {
        for (Listener l : listeners) l.onError(msg);
    }

    private void fireBattery(int l, int r, int c, int f) {
        for (Listener li : listeners) li.onBattery(l, r, c, f);
    }

    private void fireMode(int mode) {
        for (Listener l : listeners) l.onNoiseMode(mode);
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    /** 注意: Listener 回调发生在后台线程,UI 侧需自行切线程。 */
    public void connect(final BluetoothDevice device) {
        closing = false;
        cfg = cfgForName(device.getName());
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    try {
                        socket = device.createRfcommSocketToServiceRecord(GAIA_UUID);
                        socket.connect();
                    } catch (IOException e) {
                        try {
                            if (socket != null) socket.close();
                        } catch (IOException ignored) {
                        }
                        // SDP 找不到服务时退回固定 channel 13
                        socket = fallbackChannel(device, 13);
                        socket.connect();
                    }
                    out = socket.getOutputStream();
                } catch (Exception e) {
                    safeClose();
                    fireError("连接失败: " + e.getMessage()
                            + "\n(耳机未与手表配对? 或耳机已占满两个连接)");
                    return;
                }
                fireConnected(device.getName());
                sendHandshake();
                queryBattery();
                queryNoise();
                startReader();
            }
        }, "gaia-connect").start();
    }

    private static BluetoothSocket fallbackChannel(BluetoothDevice device, int channel)
            throws Exception {
        // createRfcommSocket(int) 是隐藏 API,反射调用
        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
        return (BluetoothSocket) m.invoke(device, channel);
    }

    public void disconnect() {
        closing = true;
        safeClose();
    }

    private void safeClose() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- 发送 ----

    private synchronized void writeFrame(int verb, int vendor, int cmd, byte[] payload) {
        if (out == null) return;
        int len = payload == null ? 0 : payload.length;
        byte[] f = new byte[8 + len];
        f[0] = (byte) 0xFF;
        f[1] = (byte) verb;
        f[2] = 0x00;
        f[3] = (byte) len;
        f[4] = (byte) (vendor >> 8);
        f[5] = (byte) vendor;
        f[6] = (byte) (cmd >> 8);
        f[7] = (byte) cmd;
        if (payload != null && len > 0) System.arraycopy(payload, 0, f, 8, len);
        try {
            out.write(f);
            out.flush();
        } catch (IOException e) {
            fireError("发送失败: " + e.getMessage());
        }
    }

    public void sendHandshake() {
        writeFrame(0x04, VENDOR_QUALCOMM, CMD_HANDSHAKE, null);
    }

    public void queryBattery() {
        writeFrame(0x04, VENDOR_VIVO, CMD_BATT_QUERY, null);
    }

    public void queryNoise() {
        byte[] payload = cfg == CFG_FAMILY_V4 ? new byte[]{0x00} : new byte[0];
        writeFrame(cfg == CFG_FAMILY_V4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_NOISE_QUERY, payload);
    }

    public void setNoiseMode(int mode) {
        byte[] suffix;
        if (cfg == CFG_AIR3_PRO) suffix = new byte[]{0x04, 0x00};
        else if (cfg == CFG_TWS_3E) suffix = new byte[]{0x03};
        else suffix = new byte[]{0x03, 0x01};
        byte[] payload = new byte[1 + suffix.length];
        payload[0] = (byte) mode;
        System.arraycopy(suffix, 0, payload, 1, suffix.length);
        writeFrame(cfg == CFG_FAMILY_V4 ? 0x04 : 0x03, VENDOR_VIVO, CMD_NOISE_SET, payload);
    }

    // ---- 接收 ----

    private void startReader() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = socket.getInputStream();
                    byte[] head = new byte[8];
                    while (!closing) {
                        if (!readFully(in, head, 8)) break;
                        if ((head[0] & 0xFF) != 0xFF) {
                            continue; // 流错位,丢弃继续同步
                        }
                        int len = head[3] & 0xFF;
                        int vendor = ((head[4] & 0xFF) << 8) | (head[5] & 0xFF);
                        int cmd = ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
                        byte[] payload = new byte[len];
                        if (len > 0 && !readFully(in, payload, len)) break;

                        if (vendor == VENDOR_VIVO && cmd == RESP_BATT && payload.length >= 5) {
                            fireBattery(payload[1] & 0xFF, payload[2] & 0xFF,
                                    payload[3] & 0xFF, payload[4] & 0xFF);
                        } else if (vendor == VENDOR_VIVO
                                && (cmd == RESP_NOISE_QUERY || cmd == RESP_NOISE_SET)
                                && payload.length >= 2) {
                            fireMode(payload[1] & 0xFF);
                        }
                    }
                    if (!closing) fireDisconnected("连接已关闭");
                } catch (IOException e) {
                    if (!closing) fireDisconnected("连接断开: " + e.getMessage());
                }
            }
        }, "gaia-reader").start();
    }

    private static boolean readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) return false;
            off += r;
        }
        return true;
    }
}
