package com.hdz.vivotws;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.List;

/** 磁贴共用的状态探测: 判断耳机是否真的挂在手表系统上 */
final class TileState {

    private TileState() {
    }

    /** 实时状态: App 的断连回调已写"未连接"时直接信它(蓝牙 ACL 常滞后,反射会撒谎),
     *  否则查真实 ACL/GATT,再不行用 20 秒内的成功记录兜底 */
    static String liveStatus(Context ctx) {
        BluetoothManager bm =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return "未连接";
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return "蓝牙关";

        SharedPreferences cfg = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        String mac = cfg.getString("tile_mac", null);
        if (mac != null) {
            try {
                BluetoothDevice d = adapter.getRemoteDevice(mac);
                Method m = d.getClass().getMethod("isConnected");
                Object r = m.invoke(d);
                if (r instanceof Boolean && (Boolean) r) return "已连接";
            } catch (Exception ignored) {
                // 反射失败走下面的 GATT 查询
            }
        }
        List<BluetoothDevice> connected = bm.getConnectedDevices(BluetoothProfile.GATT);
        if (findEarbudIn(connected)) return "已连接";

        // 蓝牙层已无连接: 若 App 的断连回调也报了断开(且超过 20 秒没有成功记录),别再装连着
        long lastOk = cfg.getLong("tile_last_ok", 0);
        if ("未连接".equals(cfg.getString("tile_status", null))
                && System.currentTimeMillis() - lastOk > 20 * 1000L) return "未连接";
        // 刚断开不到 20 秒, 给耳机一点回连时间, 显示保持乐观
        if (System.currentTimeMillis() - lastOk < 20 * 1000L) return "已连接";
        return "未连接";
    }

    /** 在已连接 GATT 设备里找 vivo/iQOO 耳机 */
    static boolean findEarbudIn(List<BluetoothDevice> connected) {
        if (connected == null) return false;
        for (BluetoothDevice d : connected) {
            String n = d.getName();
            if (n == null) continue;
            String ln = n.toLowerCase();
            if (ln.contains("vivo") || ln.contains("iqoo")
                    || ln.contains("tws") || ln.contains("air")) return true;
        }
        return false;
    }
}
