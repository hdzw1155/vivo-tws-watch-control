package com.hdz.vivotws;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

/**
 * 磁贴降噪切换的后台服务: 跳板 Activity 秒退,这里无界面地连耳机切模式,
 * 完成或 8 秒超时就自杀。耳机不在时最多静默等 8 秒,不再冻结界面。
 */
public class ModeCyclerService extends Service implements GaiaClient.Listener {

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
        // 上次记下的档位; 从没连过时按"关闭"起算, 保持老版本的点按顺序不变
        int last = TileStore.read(this).mode;
        if (last < GaiaClient.MODE_ANC || last > GaiaClient.MODE_TRANS) {
            last = GaiaClient.MODE_OFF;
        }
        // 磁贴传来的指定档位(点谁切谁, 0..2); 没传或越界就循环下一档
        int wanted = intent != null ? intent.getIntExtra("target_mode", -1) : -1;
        nextMode = (wanted >= 0 && wanted <= 2) ? wanted : (last + 1) % 3;

        // 磁贴连点第二下会再进这里: 灭掉上一把没收工的连接,别让两个客户端抢耳机
        done = false;
        if (client != null) {
            client.disconnect();
            client = null;
        }

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
        TileStore.noteOk(this);
        if (client != null) client.setNoiseMode(nextMode);
    }

    @Override
    public void onNoiseMode(int mode) {
        // 耳机确认了新模式,记下来让磁贴和主界面显示正确
        if (mode < 0 || mode > 2 || mode != nextMode) return;
        boolean changed = TileStore.saveMode(this, mode);
        TileStore.noteOk(this);
        if (changed) {
            TileStore.push(this);
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
