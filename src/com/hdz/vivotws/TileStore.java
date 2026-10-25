package com.hdz.vivotws;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.wear.tiles.TileService;

/** 磁贴数据契约: 全 App 只有这里知道磁贴读哪些键。
 *  磁贴展示的是"最后一次成功通讯时的快照"(电量/档位/时间),写完记得 push()。 */
final class TileStore {

    private TileStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    /** 每次与耳机真实通讯成功(连上/切档/收到电量)都叫一声,磁贴的"上一次连接"读它 */
    static void noteOk(Context ctx) {
        prefs(ctx).edit().putLong("tile_last_ok", System.currentTimeMillis()).apply();
    }

    static void saveBattery(Context ctx, int l, int r, int c) {
        prefs(ctx).edit()
                .putInt("tile_batt_l", l)
                .putInt("tile_batt_r", r)
                .putInt("tile_batt_c", c)
                .apply();
    }

    static void saveMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt("tile_mode", mode).apply();
    }

    static void saveName(Context ctx, String name) {
        prefs(ctx).edit()
                .putString("tile_name", name == null ? "vivo 耳机" : name)
                .apply();
    }

    /** 数据变了就叫系统立刻重画磁贴,不等刷新周期 */
    static void push(Context ctx) {
        try {
            TileService.getUpdater(ctx).requestUpdate(TwsTileService.class);
        } catch (Exception ignored) {
        }
    }
}
