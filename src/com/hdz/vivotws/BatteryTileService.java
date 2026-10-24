package com.hdz.vivotws;

import androidx.wear.tiles.ColorBuilders;
import androidx.wear.tiles.DimensionBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.ModifiersBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.ResourceBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.tiles.TimelineBuilders;
import androidx.wear.tiles.TypeBuilders;

import com.google.common.util.concurrent.ListenableFuture;

import android.content.SharedPreferences;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** 极简磁贴: 只显示左右耳和充电盒电量, 每 5 分钟刷新 */
public class BatteryTileService extends TileService {

    private static final long REFRESH_MS = 5 * 60 * 1000;

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

    @SuppressWarnings("unchecked")
    private static <T> ListenableFuture<T> done(T value) {
        return (ListenableFuture<T>) (ListenableFuture<?>) new DoneFuture(value);
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
        String batt = cfg.getString("tile_batt", "左 --  右 --  盒 --");
        String status = cfg.getString("tile_status", "未连接");

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
                                        .setWidth(DimensionBuilders.expand())
                                        .addContent(new LayoutElementBuilders.Text.Builder()
                                                .setText(status)
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(12f))
                                                        .setColor(ColorBuilders.argb(0xFF8E8E93))
                                                        .build())
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(10f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Text.Builder()
                                                .setText(batt)
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(15f))
                                                        .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                                        .setWeight(700)
                                                        .build())
                                                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                                                .setStart(DimensionBuilders.dp(6f))
                                                                .setEnd(DimensionBuilders.dp(6f))
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
