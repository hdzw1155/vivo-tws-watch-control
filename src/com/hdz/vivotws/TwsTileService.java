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

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** 整页磁贴: 已连接/未连接 + 耳机名 + 三个电量环(左/右/盒) + 降噪圆钮 + 打开控制 */
public class TwsTileService extends TileService {

    private static final long REFRESH_MS = 15 * 1000; // 可见时勤刷,断开状态别装傻
    private static final String[] EARS = {"左", "右", "盒"};

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

    /** 运行时画一个纯电量环(不含文字): 深色底 + 灰轨道 + 绿进度, 文字交给系统渲染 */
    private byte[] renderRing(String label, int pct) {
        boolean known = pct >= 0 && pct <= 100;
        Bitmap bmp = Bitmap.createBitmap(232, 232, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF1C1C1E);
        c.drawCircle(116, 116, 106, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(16f);
        p.setColor(0xFF3A3A3C);
        c.drawCircle(116, 116, 100, p);

        if (known) {
            p.setColor(0xFF30D158);
            c.drawArc(18, 18, 214, 214, -90, 360f * pct / 100f, false, p);
        }

        // 文字必须用 FILL: 上一步画弧后画笔停在 STROKE 模式, 直接写字会变成描边轮廓(糊成一坨的根因)
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(known ? 50f : 54f);
        p.setFakeBoldText(true);
        c.drawText(label, 116, 100, p);
        p.setTextSize(58f);
        c.drawText(known ? pct + "%" : "--", 116, 164, p);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    /** 电量环图片元素 */
    private LayoutElementBuilders.LayoutElement ringImage(String resId, int dp) {
        return new LayoutElementBuilders.Image.Builder()
                .setResourceId(resId)
                .setWidth(DimensionBuilders.dp((float) dp))
                .setHeight(DimensionBuilders.dp((float) dp))
                .build();
    }

    /** 圆形降噪按钮: 蓝底=当前档, 深底=可切, 按钮下带名字 */
    private LayoutElementBuilders.LayoutElement modeButton(
            String resId, String label, int target, boolean active) {
        int bg = active ? 0xFF3478F6 : 0xFF262628;

        return new LayoutElementBuilders.Column.Builder()
                .addContent(new LayoutElementBuilders.Box.Builder()
                        .setWidth(DimensionBuilders.dp(38f))
                        .setHeight(DimensionBuilders.dp(38f))
                        .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                .setClickable(new ModifiersBuilders.Clickable.Builder()
                                        .setId("mode_" + target)
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
                                .setBackground(new ModifiersBuilders.Background.Builder()
                                        .setColor(ColorBuilders.argb(bg))
                                        .setCorner(new ModifiersBuilders.Corner.Builder()
                                                .setRadius(DimensionBuilders.dp(19f))
                                                .build())
                                        .build())
                                .build())
                        .addContent(new LayoutElementBuilders.Image.Builder()
                                .setResourceId(resId)
                                .setWidth(DimensionBuilders.dp(16f))
                                .setHeight(DimensionBuilders.dp(16f))
                                .build())
                        .build())
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText(label)
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(10f))
                                .setColor(ColorBuilders.argb(active ? 0xFF3478F6 : 0xFF8E8E93))
                                .build())
                        .build())
                .build();
    }

    /** 磁贴数据快照: {是否连着, 左, 右, 盒, 模式}; 断开时电量一律 -1(显示 --) */
    private static int[] snapshot(Context ctx) {
        SharedPreferences cfg = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        boolean on = TileState.liveStatus(ctx).equals("已连接");
        return new int[]{
                on ? 1 : 0,
                on ? cfg.getInt("tile_batt_l", -1) : -1,
                on ? cfg.getInt("tile_batt_r", -1) : -1,
                on ? cfg.getInt("tile_batt_c", -1) : -1,
                cfg.getInt("tile_mode", 1),
        };
    }

    /** 资源版本号: 随数据变化而变化, 否则系统拿缓存的旧电量环图永远不重画 */
    private static String resVersion(int[] s) {
        return s[0] + "_" + s[1] + "_" + s[2] + "_" + s[3];
    }

    @Override
    public ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(
            RequestBuilders.ResourcesRequest requestParams) {
        int[] s = snapshot(this);
        int l = s[1], r = s[2], c = s[3];

        ResourceBuilders.Resources res = new ResourceBuilders.Resources.Builder()
                .setVersion(resVersion(s))
                .addIdToImageMapping("ring_l",
                        new ResourceBuilders.ImageResource.Builder()
                                .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                                        .setData(renderRing("左", l))
                                        .setWidthPx(232)
                                        .setHeightPx(232)
                                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                                        .build())
                                .build())
                .addIdToImageMapping("ring_r",
                        new ResourceBuilders.ImageResource.Builder()
                                .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                                        .setData(renderRing("右", r))
                                        .setWidthPx(232)
                                        .setHeightPx(232)
                                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                                        .build())
                                .build())
                .addIdToImageMapping("ring_c",
                        new ResourceBuilders.ImageResource.Builder()
                                .setInlineResource(new ResourceBuilders.InlineImageResource.Builder()
                                        .setData(renderRing("盒", c))
                                        .setWidthPx(232)
                                        .setHeightPx(232)
                                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                                        .build())
                                .build())
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
        int[] s = snapshot(this);
        int mode = s[4];
        String status = TileState.liveStatus(this);
        // 诊断: 系统来要磁贴时,把我们算出的状态打到日志,验证"没重画"还是"算错"
        android.util.Log.d("TileSvc", "onTileRequest status=" + status
                + " conn=" + s[0] + " batt=" + s[1] + "/" + s[2] + "/" + s[3]);
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
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
                                                        .setSize(DimensionBuilders.sp(12f))
                                                        .setColor(ColorBuilders.argb(0xFF9BE29B))
                                                        .build())
                                                .build())
                                        .addContent(new LayoutElementBuilders.Text.Builder()
                                                .setText(name)
                                                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                        .setSize(DimensionBuilders.sp(14f))
                                                        .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                                        .setWeight(700)
                                                        .build())
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(6f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Row.Builder()
                                                .addContent(ringImage("ring_l", 56))
                                                .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                        .setWidth(DimensionBuilders.dp(5f))
                                                        .build())
                                                .addContent(ringImage("ring_r", 56))
                                                .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                        .setWidth(DimensionBuilders.dp(5f))
                                                        .build())
                                                .addContent(ringImage("ring_c", 56))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(6f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Row.Builder()
                                                .addContent(modeButton("ic_off", "关闭", 1, mode == 1))
                                                .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                        .setWidth(DimensionBuilders.dp(14f))
                                                        .build())
                                                .addContent(modeButton("ic_trans", "通透", 2, mode == 2))
                                                .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                        .setWidth(DimensionBuilders.dp(14f))
                                                        .build())
                                                .addContent(modeButton("ic_anc", "降噪", 0, mode == 0))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Spacer.Builder()
                                                .setHeight(DimensionBuilders.dp(6f))
                                                .build())
                                        .addContent(new LayoutElementBuilders.Box.Builder()
                                                .setWidth(DimensionBuilders.wrap())
                                                .setHeight(DimensionBuilders.wrap())
                                                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                                        .setClickable(new ModifiersBuilders.Clickable.Builder()
                                                                .setId("open")
                                                                .setOnClick(new ActionBuilders.LaunchAction.Builder()
                                                                        .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                                                                .setClassName("com.hdz.vivotws.MainActivity")
                                                                                .setPackageName("com.hdz.vivotws")
                                                                                .build())
                                                                        .build())
                                                                .build())
                                                        .setBackground(new ModifiersBuilders.Background.Builder()
                                                                .setColor(ColorBuilders.argb(0xFF262628))
                                                                .setCorner(new ModifiersBuilders.Corner.Builder()
                                                                        .setRadius(DimensionBuilders.dp(17f))
                                                                        .build())
                                                                .build())
                                                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                                                .setStart(DimensionBuilders.dp(18f))
                                                                .setEnd(DimensionBuilders.dp(18f))
                                                                .setTop(DimensionBuilders.dp(7f))
                                                                .setBottom(DimensionBuilders.dp(7f))
                                                                .build())
                                                        .build())
                                                .addContent(new LayoutElementBuilders.Text.Builder()
                                                        .setText("打开控制")
                                                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                                                .setSize(DimensionBuilders.sp(12f))
                                                                .setColor(ColorBuilders.argb(0xFFFFFFFF))
                                                                .build())
                                                        .build())
                                                .build())
                                        .build())
                                .build())
                        .build();

        long now = System.currentTimeMillis();
        return done(new TileBuilders.Tile.Builder()
                .setResourcesVersion(resVersion(s))
                .setFreshnessIntervalMillis(REFRESH_MS)
                .setTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(layout)
                                .build())
                        .build())
                .build());
    }
}
