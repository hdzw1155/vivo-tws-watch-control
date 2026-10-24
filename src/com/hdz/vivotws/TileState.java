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

    /** 实时状态文字: 优先用 MAC+反射查 ACL 连接(最准), 退化到 GATT 列表 */
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
        return findEarbudIn(connected) ? "已连接" : "未连接";
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
