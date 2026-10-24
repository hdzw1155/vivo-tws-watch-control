package com.hdz.vivotws;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.wear.tiles.TileService;

/**
 * 磁贴降噪切换的后台服务: 跳板 Activity 秒退,这里无界面地连耳机切模式,
 * 完成或 8 秒超时就自杀。耳机不在时最多静默等 8 秒,不再冻结界面。
 */
public class ModeCyclerService extends Service implements GaiaClient.Listener {

    private static final String[] NAMES = {"降噪开", "降噪关", "通透"};

    private GaiaClient client;
    private int nextMode;
    private boolean done = false;
    private final Handler h = new Handler();
    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            stopSelf();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 没授权就点磁贴: 安静退出,别崩
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        int last = cfg.getInt("tile_mode", GaiaClient.MODE_OFF);
        // 磁贴传来的指定档位(点谁切谁,0..2); 没传或越界就循环下一档
        int wanted = intent != null ? intent.getIntExtra("target_mode", -1) : -1;
        nextMode = (wanted >= 0 && wanted <= 2) ? wanted : (last + 1) % 3;

        BluetoothAdapter adapter =
                ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        BluetoothDevice target = adapter != null ? MainActivity.findTarget(adapter) : null;
        if (target == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        client = new GaiaClient(this);
        client.connect(target);
        h.postDelayed(timeout, 8000);
        return START_NOT_STICKY;
    }

    private void complete() {
        if (done) return;
        done = true;
        stopSelf();
    }

    @Override
    public void onConnected(String deviceName) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putLong("tile_last_ok", System.currentTimeMillis()).apply();
        if (client != null) client.setNoiseMode(nextMode);
    }

    @Override
    public void onNoiseMode(int mode) {
        // 耳机确认了新模式,记下来让磁贴和主界面显示正确
        if (mode < 0 || mode > 2 || mode != nextMode) return;
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putInt("tile_mode", mode)
                .putString("tile_mode_name", NAMES[mode])
                .putLong("tile_last_ok", System.currentTimeMillis())
                .apply();
        try {
            TileService.getUpdater(this).requestUpdate(TwsTileService.class);
        } catch (Exception ignored) {
        }
        complete();
    }

    @Override
    public void onBattery(int left, int right, int box, int chargeFlags) {
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
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (client != null) {
            client.disconnect();
            client = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
