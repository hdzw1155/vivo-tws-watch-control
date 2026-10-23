package com.hdz.vivotws;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/** vivo 耳机 App 风格: 卡片式深色布局 + 三选一圆形模式按钮 + 三段电量条 */
public class MainActivity extends Activity implements GaiaClient.Listener {

    private static final int REQ_PERM = 1;

    private static final int COL_SEL = Color.rgb(0x34, 0x78, 0xF6);   // vivo 蓝
    private static final int COL_LABEL_GRAY = Color.rgb(0x8E, 0x8E, 0x93);
    private static final int COL_ICON_GRAY = Color.rgb(0x9A, 0x9A, 0x9E);
    private static final int COL_GREEN = Color.rgb(0x34, 0xC7, 0x59); // 电量绿
    private static final int COL_RED = Color.rgb(0xFA, 0x51, 0x51);   // 低电量红
    private static final int COL_TRACK = Color.rgb(0x3A, 0x3A, 0x3C); // 电量槽灰

    private final Handler ui = new Handler(Looper.getMainLooper());
    private GaiaClient client;
    private TextView status;
    private TextView sub;
    private ImageView imgOff;
    private ImageView imgTrans;
    private ImageView imgAnc;
    private TextView lblOff;
    private TextView lblTrans;
    private TextView lblAnc;
    private TextView pctL;
    private TextView pctR;
    private TextView pctC;
    private ProgressBar barL;
    private ProgressBar barR;
    private ProgressBar barC;
    private int lastMode = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        sub = findViewById(R.id.sub);
        imgOff = findViewById(R.id.img_off);
        imgTrans = findViewById(R.id.img_trans);
        imgAnc = findViewById(R.id.img_anc);
        lblOff = findViewById(R.id.lbl_off);
        lblTrans = findViewById(R.id.lbl_trans);
        lblAnc = findViewById(R.id.lbl_anc);
        pctL = findViewById(R.id.pct_l);
        pctR = findViewById(R.id.pct_r);
        pctC = findViewById(R.id.pct_c);
        barL = findViewById(R.id.bar_l);
        barR = findViewById(R.id.bar_r);
        barC = findViewById(R.id.bar_c);
        View refresh = findViewById(R.id.btn_refresh);
        findViewById(R.id.mode_off).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(GaiaClient.MODE_OFF); }
        });
        findViewById(R.id.mode_trans).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(GaiaClient.MODE_TRANS); }
        });
        findViewById(R.id.mode_anc).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(GaiaClient.MODE_ANC); }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (client != null && client.isConnected()) {
                    client.queryBattery();
                    client.queryNoise();
                } else {
                    tryConnect();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryConnect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 手表应用切后台就断开,省电
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    private boolean hasPerm() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 在已配对设备里挑目标: 先精确匹配 vivo/iQOO 家族命名,再兜底任意耳机 */
    static BluetoothDevice findTarget(BluetoothAdapter adapter) {
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return null;
        for (BluetoothDevice d : bonded) {
            String n = d.getName();
            if (n == null) continue;
            String ln = n.toLowerCase();
            if (ln.contains("vivo") || ln.contains("iqoo")
                    || ln.contains("tws") || ln.contains("air")) {
                return d;
            }
        }
        for (BluetoothDevice d : bonded) {
            ParcelUuid[] uuids = d.getUuids();
            if (uuids == null) continue;
            for (ParcelUuid u : uuids) {
                if (u.getUuid().equals(GaiaClient.A2DP_SINK_UUID)) return d;
            }
        }
        return null;
    }

    private void tryConnect() {
        if (!hasPerm()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
            return;
        }
        if (client != null && client.isConnected()) return;

        BluetoothAdapter adapter =
                ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status.setText("请先打开手表蓝牙");
            return;
        }
        BluetoothDevice target = findTarget(adapter);
        if (target == null) {
            status.setText("未找到已配对的 vivo 耳机");
            sub.setText("请先在 手表设置-蓝牙 配对");
            return;
        }
        status.setText(R.string.status_connecting);
        sub.setTextColor(COL_LABEL_GRAY);
        sub.setText(target.getName());
        client = new GaiaClient(this);
        client.connect(target);
    }

    private void setMode(int mode) {
        if (client == null || !client.isConnected()) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show();
            tryConnect();
            return;
        }
        client.setNoiseMode(mode);
    }

    private void applyMode(ImageView img, TextView lbl, boolean sel) {
        img.setBackgroundResource(sel ? R.drawable.circle_sel : R.drawable.circle_norm);
        img.setImageTintList(ColorStateList.valueOf(sel ? Color.WHITE : COL_ICON_GRAY));
        lbl.setTextColor(sel ? COL_SEL : COL_LABEL_GRAY);
    }

    private void renderMode() {
        ui.post(new Runnable() {
            @Override public void run() {
                applyMode(imgOff, lblOff, lastMode == GaiaClient.MODE_OFF);
                applyMode(imgTrans, lblTrans, lastMode == GaiaClient.MODE_TRANS);
                applyMode(imgAnc, lblAnc, lastMode == GaiaClient.MODE_ANC);
            }
        });
    }

    /** 电量未知时耳机上报 0xFF,显示 -- */
    private void setBatt(final TextView pct, final ProgressBar bar,
                         final int v, final boolean charging) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (v >= 0 && v <= 100) {
                    pct.setText(charging ? (v + "%⚡") : (v + "%"));
                    bar.setProgress(v);
                    bar.setProgressTintList(
                            ColorStateList.valueOf(v <= 20 ? COL_RED : COL_GREEN));
                } else {
                    pct.setText("--");
                    bar.setProgress(0);
                }
                bar.setProgressBackgroundTintList(ColorStateList.valueOf(COL_TRACK));
            }
        });
    }

    // ---- GaiaClient.Listener (后台线程回调) ----

    @Override
    public void onConnected(final String deviceName) {
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(Color.WHITE);
                status.setText(deviceName);
                sub.setTextColor(COL_GREEN);
                sub.setText("已连接");
            }
        });
    }

    @Override
    public void onBattery(final int left, final int right, final int box, final int chargeFlags) {
        setBatt(pctL, barL, left, (chargeFlags & 0x01) != 0);
        setBatt(pctR, barR, right, (chargeFlags & 0x02) != 0);
        setBatt(pctC, barC, box, (chargeFlags & 0x04) != 0);
    }

    @Override
    public void onNoiseMode(int mode) {
        lastMode = mode;
        renderMode();
    }

    @Override
    public void onDisconnected(String reason) {
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(COL_LABEL_GRAY);
                status.setText(R.string.status_idle);
                sub.setText("");
                lastMode = -1;
                renderMode();
            }
        });
    }

    @Override
    public void onError(final String msg) {
        ui.post(new Runnable() {
            @Override public void run() {
                status.setTextColor(COL_LABEL_GRAY);
                status.setText(msg);
                sub.setText("");
            }
        });
    }
}
