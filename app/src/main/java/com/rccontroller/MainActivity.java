package com.rccontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_DEVICE_PICK = 2;
    private static final int REQUEST_BT_PERMISSIONS = 3;

    // UI
    private JoystickView joystickLeft;   // Drive / throttle (forward/reverse)
    private JoystickView joystickRight;  // Steering (left/right)
    private Button btnConnect;
    private CheckBox cbImu;
    private TextView tvStatus;
    private TextView tvDataOut;
    private TextView tvRxLog;
    private ScrollView scrollRx;
    private TextView tvRxClear;
    private SeekBar seekbarTxRate;
    private TextView tvTxRate;
    private View statusBar;

    // TX rate steps: index -> Hz, ms
    private static final int[]    TX_HZ = { 1, 2, 4, 5, 10, 20, 50, 100 };
    private static final long[]   TX_MS = { 1000, 500, 250, 200, 100, 50, 20, 10 };

    private static final int MAX_RX_LINES = 100;
    private int rxLineCount = 0;
    private StringBuilder rxBuffer = new StringBuilder();

    // BT
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothService bluetoothService;

    // IMU
    private ImuController imuController;
    private boolean imuActive = false;

    // Transmission loop
    private Handler txHandler = new Handler(Looper.getMainLooper());
    private long txIntervalMs = 50; // default 20 Hz
    private Runnable txRunnable;

    // Current values
    private float velocity = 0f;
    private float steerX = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while driving
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        joystickLeft  = findViewById(R.id.joystick_left);
        joystickRight = findViewById(R.id.joystick_right);
        btnConnect    = findViewById(R.id.btn_connect);
        cbImu         = findViewById(R.id.cb_imu);
        tvStatus      = findViewById(R.id.tv_status);
        tvDataOut     = findViewById(R.id.tv_data_out);
        tvRxLog       = findViewById(R.id.tv_rx_log);
        scrollRx      = findViewById(R.id.scroll_rx);
        tvRxClear     = findViewById(R.id.tv_rx_clear);
        seekbarTxRate = findViewById(R.id.seekbar_tx_rate);
        tvTxRate      = findViewById(R.id.tv_tx_rate);
        statusBar     = findViewById(R.id.status_bar);

        tvRxClear.setOnClickListener(v -> {
            rxBuffer.setLength(0);
            rxLineCount = 0;
            tvRxLog.setText("");
        });

        seekbarTxRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                txIntervalMs = TX_MS[progress];
                tvTxRate.setText(TX_HZ[progress] + " Hz");
                // Restart loop at new rate if already running
                if (bluetoothService.isConnected()) {
                    startTxLoop();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        // Set initial label to match default progress=3 (5 Hz... wait, index 3 = 5Hz)
        // Default seekbar progress=3 maps to TX_HZ[3]=5Hz per the table
        // But we want default 20Hz (index 5). Update seekbar default progress to 5.
        seekbarTxRate.setProgress(5); // 20 Hz

        joystickLeft.setLabel("DRIVE");
        joystickRight.setLabel("STEER");
        joystickLeft.setAxisMode(JoystickView.AxisMode.VERTICAL_ONLY);
        joystickRight.setAxisMode(JoystickView.AxisMode.HORIZONTAL_ONLY);

        // Left joystick: Y-axis = velocity
        joystickLeft.setListener((x, y) -> velocity = y);

        // Right joystick: X-axis = steering
        joystickRight.setListener((x, y) -> {
            if (!imuActive) steerX = x;
        });

        // BT setup
        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = btManager != null ? btManager.getAdapter() : null;
        bluetoothService = new BluetoothService(bluetoothAdapter);

        bluetoothService.setCallback(new BluetoothService.ConnectionCallback() {
            @Override
            public void onConnected(String deviceName) {
                runOnUiThread(() -> {
                    tvStatus.setText("Connected: " + deviceName);
                    statusBar.setBackgroundColor(0xFF2E7D32); // dark green
                    btnConnect.setText("Disconnect");
                    startTxLoop();
                });
            }
            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    tvStatus.setText("Disconnected");
                    statusBar.setBackgroundColor(0xFFB71C1C); // dark red
                    btnConnect.setText("Connect HC-06");
                    stopTxLoop();
                });
            }
            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    tvStatus.setText("Error: " + message);
                    statusBar.setBackgroundColor(0xFFE65100); // orange
                    btnConnect.setText("Connect HC-06");
                    stopTxLoop();
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });

        bluetoothService.setMessageCallback(message -> appendRxLine(message));

        // IMU setup
        imuController = new ImuController(this);
        imuController.setListener(steer -> {
            if (imuActive) {
                steerX = steer;
                // Also visually update the right joystick thumb
                joystickRight.setValues(steer, 0f);
            }
        });

        // IMU checkbox
        cbImu.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                if (!imuController.isAvailable()) {
                    Toast.makeText(this, "IMU sensor not available on this device", Toast.LENGTH_SHORT).show();
                    cbImu.setChecked(false);
                    return;
                }
                imuActive = true;
                imuController.start();
                joystickRight.setAlpha(0.4f);
                joystickRight.setEnabled(false);
            } else {
                imuActive = false;
                imuController.stop();
                joystickRight.setAlpha(1f);
                joystickRight.setEnabled(true);
                steerX = 0f;
                joystickRight.setValues(0f, 0f);
            }
        });

        // Connect button
        btnConnect.setOnClickListener(v -> {
            if (bluetoothService.isConnected()) {
                bluetoothService.disconnect();
            } else {
                initiateConnection();
            }
        });

        setStatusDisconnected();
    }

    // ─── Transmission Loop ────────────────────────────────────────────────────

    private void startTxLoop() {
        stopTxLoop();
        txRunnable = new Runnable() {
            @Override
            public void run() {
                sendCurrentValues();
                txHandler.postDelayed(this, txIntervalMs);
            }
        };
        txHandler.post(txRunnable);
    }

    private void stopTxLoop() {
        if (txRunnable != null) {
            txHandler.removeCallbacks(txRunnable);
            txRunnable = null;
        }
    }

    private void sendCurrentValues() {
        float vel = velocity;
        float turn = steerX; // -1..+1

        float turnMag = Math.abs(turn);
        boolean turnLeft = turn < 0f;

        bluetoothService.sendControl(vel, turnMag, turnLeft);

        // Display the formatted packet in the UI
        String dirChar = turnMag < 0.0001f ? "N" : (turnLeft ? "L" : "R");
        String velStr  = String.format("%+.4f", vel);
        String turnStr = String.format("%.4f", turnMag);
        String packet  = "V:" + velStr + ",D:" + dirChar + ",T:" + turnStr;
        tvDataOut.setText(packet);
    }

    // ─── Bluetooth Connection Flow ────────────────────────────────────────────

    private void initiateConnection() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissions();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBt = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBt, REQUEST_ENABLE_BT);
            return;
        }
        launchDevicePicker();
    }

    private void launchDevicePicker() {
        Intent intent = new Intent(this, DeviceListActivity.class);
        startActivityForResult(intent, REQUEST_DEVICE_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            launchDevicePicker();
        } else if (requestCode == REQUEST_DEVICE_PICK && resultCode == RESULT_OK && data != null) {
            String address = data.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
            if (address != null && bluetoothAdapter != null) {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                tvStatus.setText("Connecting…");
                statusBar.setBackgroundColor(0xFFF57F17); // amber
                bluetoothService.connect(device);
            }
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    }, REQUEST_BT_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.BLUETOOTH_ADMIN,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, REQUEST_BT_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initiateConnection();
            } else {
                Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        if (imuActive) imuController.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        imuController.stop();
        // Also zero out controls on pause (safety)
        velocity = 0f;
        steerX = 0f;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTxLoop();
        imuController.stop();
        bluetoothService.disconnect();
    }

    private void appendRxLine(String line) {
        // Trim oldest lines if over limit
        if (rxLineCount >= MAX_RX_LINES) {
            String text = rxBuffer.toString();
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                rxBuffer.delete(0, firstNewline + 1);
                rxLineCount--;
            }
        }
        rxBuffer.append(line).append('\n');
        rxLineCount++;
        tvRxLog.setText(rxBuffer.toString());
        // Auto-scroll to bottom
        scrollRx.post(() -> scrollRx.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void setStatusDisconnected() {
        tvStatus.setText("Not connected");
        statusBar.setBackgroundColor(0xFFB71C1C);
    }
}