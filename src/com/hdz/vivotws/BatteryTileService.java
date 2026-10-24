package com.hdz.vivotws;

import androidx.wear.tiles.ColorBuilders;
import androidx.wear.tiles.DimensionBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
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

/** 一行磁贴: 三个电量圆环(左/右/盒), 环内百分比, 环下标签 */
public class BatteryTileService extends TileService {

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

    /** 单个圆环: 灰色轨道 + 绿色进度 + 中心百分比 + 环下标签 */
    private LayoutElementBuilders.LayoutElement ring(String label, int pct) {
        boolean known = pct >= 0 && pct <= 100;
        float deg = known ? pct * 3.6f : 0f;
        String num = known ? String.valueOf(pct) : "--";

        LayoutElementBuilders.Arc arc = new LayoutElementBuilders.Arc.Builder()
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
                .build();

        return new LayoutElementBuilders.Column.Builder()
                .addContent(new LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.dp(54f))
                        .setHeight(DimensionBuilders.dp(54f))
                        .addContent(arc)
                        .build())
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText(label)
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(9f))
                                .setColor(ColorBuilders.argb(0xFF8E8E93))
                                .build())
                        .build())
                .build();
    }

    @Override
    public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            RequestBuilders.ResourcesRequest requestParams) {
        return done(new ResourceBuilders.Resources.Builder().setVersion("1").build());
    }

    @Override
    public ListenableFuture<TileBuilders.Tile> onTileRequest(
            RequestBuilders.TileRequest requestParams) {
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        int l = cfg.getInt("tile_batt_l", -1);
        int r = cfg.getInt("tile_batt_r", -1);
        int c = cfg.getInt("tile_batt_c", -1);

        LayoutElementBuilders.Layout layout =
                new LayoutElementBuilders.Layout.Builder()
                        .setRoot(new LayoutElementBuilders.Box.Builder()
                                .setWidth(DimensionBuilders.expand())
                                .setHeight(DimensionBuilders.expand())
                                .setHorizontalAlignment(
                                        LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                                .setVerticalAlignment(
                                        LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                                .addContent(new LayoutElementBuilders.Row.Builder()
                                        .addContent(ring("左", l))
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setWidth(DimensionBuilders.dp(6f))
                                                .build())
                                        .addContent(ring("右", r))
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setWidth(DimensionBuilders.dp(6f))
                                                .build())
                                        .addContent(ring("盒", c))
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
