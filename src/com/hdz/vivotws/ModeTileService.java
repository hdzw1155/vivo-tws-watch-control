package com.hdz.vivotws;

import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ColorBuilders;
import androidx.wear.tiles.DimensionBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.ModifiersBuilders;
import androidx.wear.tiles.ResourceBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.tiles.TimelineBuilders;

import com.google.common.util.concurrent.ListenableFuture;

import android.content.SharedPreferences;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** 一行磁贴: 三个圆形图标按钮(关闭/通透/降噪), 点谁切谁, 当前档变蓝 */
public class ModeTileService extends TileService {

    private static final long REFRESH_MS = 60 * 1000;

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

    /** 单个圆形按钮: 蓝底白图标=当前档, 深底灰图标=可切 */
    private LayoutElementBuilders.LayoutElement modeCircle(
            String resId, String label, int target, boolean active) {
        int bg = active ? 0xFF3478F6 : 0xFF2A2A2C;
        int iconTint = active ? 0xFFFFFFFF : 0xFF9A9A9E;
        int labelColor = active ? 0xFF3478F6 : 0xFF8E8E93;

        return new LayoutElementBuilders.Column.Builder()
                .addContent(new LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.dp(38f))
                        .setHeight(DimensionBuilders.dp(38f))
                        .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                .setBackground(new ModifiersBuilders.Background.Builder()
                                        .setColor(ColorBuilders.argb(bg))
                                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                                .setRadius(DimensionBuilders.dp(19f))
                                                .build())
                                        .build())
                                .setPadding(new ModifiersBuilders.Padding.Builder()
                                        .setStart(DimensionBuilders.dp(10f))
                                        .setEnd(DimensionBuilders.dp(10f))
                                        .setTop(DimensionBuilders.dp(10f))
                                        .setBottom(DimensionBuilders.dp(10f))
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
                        .build())
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText(label)
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(9f))
                                .setColor(ColorBuilders.argb(labelColor))
                                .build())
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
        int mode = cfg.getInt("tile_mode", 1);

        // 顺序按用户要求: 最左 关闭, 中间 通透, 最右 降噪
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
                                        .addContent(modeCircle("ic_off", "关闭",
                                                1, mode == 1))
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setWidth(DimensionBuilders.dp(10f))
                                                .build())
                                        .addContent(modeCircle("ic_trans", "通透",
                                                2, mode == 2))
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setWidth(DimensionBuilders.dp(10f))
                                                .build())
                                        .addContent(modeCircle("ic_anc", "降噪",
                                                0, mode == 0))
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
