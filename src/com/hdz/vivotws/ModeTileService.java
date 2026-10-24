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

/** 极简磁贴: 显示当前降噪状态, 点击"切换"按钮循环 降噪开→降噪关→通透 (经无界面切换器发指令) */
public class ModeTileService extends TileService {

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
        String mode = cfg.getString("tile_mode_name", "--");

        ActionBuilders.Action toggle =
                new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setClassName("com.hdz.vivotws.ModeCyclerActivity")
                                .setPackageName("com.hdz.vivotws")
                                .build())
                        .build();

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
                                                .setText("降噪")
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(11f))
                                                        .setColor(ColorBuilders.argb(0xFF8E8E93))
                                                        .build())
                                                .build())
                                        .addContent(new LayoutElementBuilders.Text.Builder()
                                                .setText(mode)
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(20f))
                                                        .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                                        .setWeight(700)
                                                        .build())
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(16f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Box.Builder()
                                                .setWidth(DimensionBuilders.wrap())
                                                .setHeight(DimensionBuilders.wrap())
                                                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                                                .setOnClick(toggle)
                                                                .build())
                                                        .setBackground(new ModifiersBuilders.Background.Builder()
                                                                .setColor(ColorBuilders.argb(0xFF2A2A2C))
                                                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                                                        .setRadius(DimensionBuilders.dp(24f))
                                                                        .build())
                                                                .build())
                                                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                                                .setStart(DimensionBuilders.dp(24f))
                                                                .setEnd(DimensionBuilders.dp(24f))
                                                                .setTop(DimensionBuilders.dp(10f))
                                                                .setBottom(DimensionBuilders.dp(10f))
                                                                .build())
                                                        .build())
                                                .addContent(new LayoutElementBuilders.Text.Builder()
                                                        .setText("切换")
                                                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                                .setSize(DimensionBuilders.sp(13f))
                                                                .setColor(ColorBuilders.argb(0xFF3478F6))
                                                                .setWeight(700)
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
