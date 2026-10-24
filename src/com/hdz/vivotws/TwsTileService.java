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
import androidx.wear.tiles.TypeBuilders;

import com.google.common.util.concurrent.ListenableFuture;

import android.content.SharedPreferences;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 系统磁贴: 把"连接状态 + 三段电量"浓缩在一张卡里,
 * 底部胶囊按钮点开 App 操作降噪。数据取自主界面写入的本地快照,
 * 每 5 分钟自动刷新一次。
 */
public class TwsTileService extends TileService {

    private static final long REFRESH_MS = 5 * 60 * 1000;

    /** tiles 1.1 依赖的 guava 只有接口没有工具类,自带一个"已完成"实现 */
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

        @SuppressWarnings("unchecked")
        <T> ListenableFuture<T> asFuture() {
            return (ListenableFuture<T>) this;
        }
    }

    @Override
    public ListenableFuture<TileBuilders.Tile> onTileRequest(
            RequestBuilders.TileRequest requestParams) {
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        String status = cfg.getString("tile_status", "未连接");
        String name = cfg.getString("tile_name", "vivo TWS Air 3 Pro");
        String batt = cfg.getString("tile_batt", "左 --  右 --  盒 --");

        // 胶囊按钮: 点击打开主界面操作
        ActionBuilders.Action openApp =
                new ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                .setClassName("com.hdz.vivotws.MainActivity")
                                .setPackageName("com.hdz.vivotws")
                                .build())
                        .build();

        ModifiersBuilders.Padding pillPad = new ModifiersBuilders.Padding.Builder()
                .setStart(DimensionBuilders.dp(24f))
                .setEnd(DimensionBuilders.dp(24f))
                .setTop(DimensionBuilders.dp(10f))
                .setBottom(DimensionBuilders.dp(10f))
                .build();

        LayoutElementBuilders.LayoutElement pill =
                new LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.wrap())
                        .setHeight(DimensionBuilders.wrap())
                        .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                .setClickable(new ModifiersBuilders.Clickable.Builder()
                                        .setOnClick(openApp)
                                        .build())
                                .setBackground(new ModifiersBuilders.Background.Builder()
                                        .setColor(ColorBuilders.argb(0xFF2A2A2C))
                                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                                .setRadius(DimensionBuilders.dp(24f))
                                                .build())
                                        .build())
                                .setPadding(pillPad)
                                .build())
                        .addContent(new LayoutElementBuilders.Text.Builder()
                                .setText("打开控制")
                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                        .setSize(DimensionBuilders.sp(13f))
                                        .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                        .setWeight(700)
                                        .build())
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
                                                .setText(status)
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(16f))
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
                                                .setHeight(DimensionBuilders.dp(14f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Text.Builder()
                                                .setText(batt)
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(12f))
                                                        .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                                        .build())
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(18f))
                                                .build())
                                        .addContent(pill)
                                        .build())
                                .build())
                        .build();

        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setFreshnessIntervalMillis(REFRESH_MS)
                .setTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(layout)
                                .build())
                        .build())
                .build();
        return new DoneFuture(tile).asFuture();
    }

    @Override
    public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            RequestBuilders.ResourcesRequest requestParams) {
        ResourceBuilders.Resources res = new ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .build();
        return new DoneFuture(res).asFuture();
    }
}
