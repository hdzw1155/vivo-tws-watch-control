package com.hdz.vivotws;

import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ColorBuilders;
import androidx.wear.tiles.DimensionBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.ModifiersBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.ResourceBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.tiles.TimelineBuilders;

import com.google.common.util.concurrent.ListenableFuture;

import android.content.SharedPreferences;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** 整页磁贴: 已连接/未连接 + 耳机名 + 电量圆环 + 降噪圆钮 + 打开 App */
public class TwsTileService extends TileService {

    private static final long REFRESH_MS = 2 * 60 * 1000;

    private static final class DoneFuture implements ListenableFuture<Object> {
        private final Object value;

        DoneFuture(Object value) {
            this.value = value;
        }

        @Override public void addListener(Runnable listener, Executor executor) {
            listener.run();
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override public boolean isCancelled() {
            return false;
        }

        @Override public boolean isDone() {
            return true;
        }

        @Override public Object get() {
            return value;
        }

        @Override public Object get(long timeout, TimeUnit unit) {
            return value;
        }
    }

    private static <T> ListenableFuture<T> done(T value) {
        return (ListenableFuture<T>) (ListenableFuture<?>) new DoneFuture(value);
    }

    /** 圆环 */
    private LayoutElementBuilders.LayoutElement ring(int pct) {
        boolean known = pct >= 0 && pct <= 100;
        float deg = known ? pct * 3.6f : 0f;
        String num = known ? String.valueOf(pct) : "--";

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(56f))
                .setHeight(DimensionBuilders.dp(56f))
                .addContent(new LayoutElementBuilders.Arc.Builder()
                        .addContent(new LayoutElementBuilders.ArcLine.Builder()
                                .setLength(DimensionBuilders.degrees(360f))
                                .setThickness(DimensionBuilders.dp(5f))
                                .setColor(ColorBuilders.argb(0xFF3A3A3C))
                                .build())
                        .addContent(new LayoutElementBuilders.ArcLine.Builder()
                                .setLength(DimensionBuilders.degrees(deg))
                                .setThickness(DimensionBuilders.dp(5f))
                                .setColor(ColorBuilders.argb(known ? 0xFF34C759 : 0xFF3A3A3C))
                                .build())
                        .build())
                .build();
    }

    /** 圆形档位钮(顺序: 关闭/通透/降噪 由调用方排) */
    private LayoutElementBuilders.LayoutElement modeCircle(
            String resId, int target, boolean active) {
        int bg = active ? 0xFF3478F6 : 0xFF2A2A2C;

        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(36f))
                .setHeight(DimensionBuilders.dp(36f))
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(bg))
                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                        .setRadius(DimensionBuilders.dp(18f))
                                        .build())
                                .build())
                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                .setStart(DimensionBuilders.dp(9f))
                                .setEnd(DimensionBuilders.dp(9f))
                                .setTop(DimensionBuilders.dp(9f))
                                .setBottom(DimensionBuilders.dp(9f))
                                .build())
                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                .setId("tap")
                                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                                .setClassName("com.hdz.vivotws.ModeCyclerActivity")
                                                .setPackageName("com.hdz.vivotws")
                                                .addKeyToExtraMapping("target_mode",
                                                        new ActionBuilders.AndroidIntExtra.Builder()
                                                                .setValue(target)
                                                                .build())
                                                .build())
                                        .build())
                                .build())
                        .build())
                .addContent(new LayoutElementBuilders.Image.Builder()
                        .setResourceId(resId)
                        .setWidth(DimensionBuilders.dp(18f))
                        .setHeight(DimensionBuilders.dp(18f))
                        .build())
                .build();
    }

    @Override
    public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            RequestBuilders.ResourcesRequest requestParams) {
        ResourceBuilders.Resources res = new ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .addIdToImageMapping("ic_off",
                        new ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_off).build())
                        .build())
                .addIdToImageMapping("ic_trans",
                        new ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_trans).build())
                        .build())
                .addIdToImageMapping("ic_anc",
                        new ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(new ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_anc).build())
                        .build())
                .build();
        return done(res);
    }

    @Override
    public ListenableFuture<TileBuilders.Tile> onTileRequest(
            RequestBuilders.TileRequest requestParams) {
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        int l = cfg.getInt("tile_batt_l", -1);
        int r = cfg.getInt("tile_batt_r", -1);
        int c = cfg.getInt("tile_batt_c", -1);
        int mode = cfg.getInt("tile_mode", 1);
        String status = TileState.liveStatus(this);
        String name = cfg.getString("tile_name", "vivo TWS Air 3 Pro");

        LayoutElementBuilders.Layout layout =
                new LayoutElementBuilders.Layout.Builder()
                        .setRoot(new LayoutElementBuilders.Box.Builder()
                                .setWidth(DimensionBuilders.expand())
                                .setHeight(DimensionBuilders.expand())
                                .setHorizontalAlignment(
                                        LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                .setVerticalAlignment(
                                        LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                                .addContent(new LayoutElementBuilders.Column.Builder()
                                        .setHorizontalAlignment(
                                                LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                        .addContent(new LayoutElementBuilders.Text.Builder()
                                                .setText(status)
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(15f))
                                                        .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                                        .setWeight(700)
                                                        .build())
                                                .build())
                                        .addContent(new LayoutElementBuilders.Text.Builder()
                                                .setText(name)
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(10f))
                                                        .setColor(ColorBuilders.argb(0xFF8E8E93))
                                                        .build())
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(8f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Row.Builder()
                                                .addContent(ring(l))
                                                .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                        .setWidth(DimensionBuilders.dp(6f))
                                                        .build())
                                                .addContent(ring(r))
                                                .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                        .setWidth(DimensionBuilders.dp(6f))
                                                        .build())
                                                .addContent(ring(c))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(10f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Row.Builder()
                                                .addContent(modeCircle("ic_off", 1, mode == 1))
                                                .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                        .setWidth(DimensionBuilders.dp(10f))
                                                        .build())
                                                .addContent(modeCircle("ic_trans", 2, mode == 2))
                                                .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                        .setWidth(DimensionBuilders.dp(10f))
                                                        .build())
                                                .addContent(modeCircle("ic_anc", 0, mode == 0))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(8f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Box.Builder()
                                                .setWidth(DimensionBuilders.wrap())
                                                .setHeight(DimensionBuilders.wrap())
                                                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                                                .setId("tap")
                                                                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                                                                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                                                                .setClassName("com.hdz.vivotws.MainActivity")
                                                                                .setPackageName("com.hdz.vivotws")
                                                                                .build())
                                                                        .build())
                                                                .build())
                                                        .setBackground(new ModifiersBuilders.Background.Builder()
                                                                .setColor(ColorBuilders.argb(0xFF2A2A2C))
                                                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                                                        .setRadius(DimensionBuilders.dp(16f))
                                                                        .build())
                                                                .build())
                                                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                                                .setStart(DimensionBuilders.dp(16f))
                                                                .setEnd(DimensionBuilders.dp(16f))
                                                                .setTop(DimensionBuilders.dp(6f))
                                                                .setBottom(DimensionBuilders.dp(6f))
                                                                .build())
                                                        .build())
                                                .addContent(new LayoutElementBuilders.Text.Builder()
                                                        .setText("打开控制")
                                                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                                .setSize(DimensionBuilders.sp(11f))
                                                                .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                                                .build())
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build();

        return done(new TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setFreshnessIntervalMillis(REFRESH_MS)
                .setTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(layout)
                                .build())
                        .build())
                .build());
    }
}
