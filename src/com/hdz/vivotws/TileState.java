package com.hdz.vivotws;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.util.List;

/** 磁贴共用的状态探测: 通过系统 GATT 连接列表判断耳机是否真的挂在手表上 */
final class TileState {

    private TileState() {
    }

    /** 耳机是否已连接到手表系统(不依赖 App 是否在前台) */
    static boolean earbudsLinked(Context ctx) {
        BluetoothManager bm =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return false;
        List<BluetoothDevice> connected = bm.getConnectedDevices(BluetoothProfile.GATT);
        return findEarbudIn(connected);
    }

    /** 实时状态文字(磁贴直接显示用) */
    static String liveStatus(Context ctx) {
        return earbudsLinked(ctx) ? "已连接" : "未连接";
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
