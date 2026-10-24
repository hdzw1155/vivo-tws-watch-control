package com.hdz.vivotws;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.SharedPreferences;
import androidx.wear.tiles.TileService;
import android.os.Bundle;
import android.os.Handler;

/**
 * 无界面切换器: 磁贴上点"切换"跳到这里, 连一次耳机把降噪切到下一档,
 * 完成即退出, 全程无 UI。8 秒超时兜底。
 */
public class ModeCyclerActivity extends Activity implements GaiaClient.Listener {

    private static final String[] NAMES = {"降噪开", "降噪关", "通透"};

    private final Handler h = new Handler();
    private GaiaClient client;
    private int nextMode;
    private boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        int last = cfg.getInt("tile_mode", GaiaClient.MODE_OFF);
        // 磁贴上传来的指定档位(点谁切谁); 没传就循环下一档
        int wanted = getIntent().getIntExtra("target_mode", -1);
        nextMode = wanted >= 0 ? wanted : (last + 1) % 3;

        BluetoothAdapter adapter =
                ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        BluetoothDevice target = adapter != null ? MainActivity.findTarget(adapter) : null;
        if (target == null) {
            finish();
            return;
        }
        client = new GaiaClient(this);
        client.connect(target);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                finish();
            }
        }, 8000);
    }

    private void complete() {
        if (done) return;
        done = true;
        finish();
    }

    @Override
    public void onConnected(String deviceName) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putLong("tile_last_ok", System.currentTimeMillis()).apply();
        if (client != null) client.setNoiseMode(nextMode);
    }

    @Override
    public void onNoiseMode(int mode) {
        // 耳机确认了新模式, 记下来让磁贴和主界面下次显示正确
        if (mode != nextMode) return;
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        cfg.edit()
                .putInt("tile_mode", mode)
                .putString("tile_mode_name", NAMES[mode])
                .putLong("tile_last_ok", System.currentTimeMillis())
                .apply();
        // 让系统立刻重画磁贴, 不等刷新周期
        try {
            TileService.getUpdater(this).requestUpdate(TwsTileService.class);
        } catch (Exception ignored) {
        }
        complete();
    }

    @Override
    public void onBattery(int left, int right, int box, int chargeFlags) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("tile_batt",
                        "左 " + (left >= 0 && left <= 100 ? left + "%" : "--") + "  "
                                + "右 " + (right >= 0 && right <= 100 ? right + "%" : "--") + "  "
                                + "盒 " + (box >= 0 && box <= 100 ? box + "%" : "--"))
                .apply();
    }

    @Override
    public void onDeepX(Integer mode) {
    }

    @Override
    public void onSpatial(Boolean state) {
    }

    @Override
    public void onError(String msg) {
        complete();
    }

    @Override
    public void onDisconnected(String reason) {
        complete();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (client != null) {
            client.disconnect();
            client = null;
        }
        super.onDestroy();
    }
}
