package com.hdz.vivotws;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 磁贴状态行: 只陈述"上一次连接"的时间事实。
 *  旧版实时探测(反射/GATT/乐观窗)在磁贴上反复误报"已连接",2026-10-25 用户拍板撤掉,不装了。 */
final class TileState {

    private TileState() {
    }

    /** 上次成功通讯(tile_last_ok)的时间: 今天只报时分,隔天带月日,从没连过显示 -- */
    static String lastSeenText(Context ctx) {
        SharedPreferences cfg = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        long t = cfg.getLong("tile_last_ok", 0);
        if (t <= 0) return "上一次连接 --";
        Date when = new Date(t);
        SimpleDateFormat keyFmt = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        boolean sameDay = keyFmt.format(when).equals(keyFmt.format(new Date()));
        String time = android.text.format.DateFormat.getTimeFormat(ctx).format(when);
        if (sameDay) return "上一次连接 " + time;
        String day = new SimpleDateFormat("MM-dd", Locale.getDefault()).format(when);
        return "上一次连接 " + day + " " + time;
    }
}
