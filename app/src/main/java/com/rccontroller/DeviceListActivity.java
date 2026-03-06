package com.rccontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DeviceListActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_ADDRESS = "device_address";
    private static final int REQUEST_BT_PERMISSIONS = 10;

    private BluetoothAdapter bluetoothAdapter;
    private ArrayAdapter<String> deviceAdapter;
    private List<String> deviceAddresses = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_list);

        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = btManager != null ? btManager.getAdapter() : null;

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        ListView listView = findViewById(R.id.device_list);
        listView.setAdapter(deviceAdapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String address = deviceAddresses.get(position);
            Intent result = new Intent();
            result.putExtra(EXTRA_DEVICE_ADDRESS, address);
            setResult(RESULT_OK, result);
            finish();
        });

        if (hasRequiredPermissions()) {
            populatePairedDevices();
        } else {
            requestPermissions();
        }
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestPermissions() {
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
                populatePairedDevices();
            } else {
                Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void populatePairedDevices() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        deviceAdapter.clear();
        deviceAddresses.clear();

        if (pairedDevices.isEmpty()) {
            deviceAdapter.add("No paired devices found.\nPair HC-06 in system settings first.");
        } else {
            for (BluetoothDevice device : pairedDevices) {
                String name = device.getName() != null ? device.getName() : "Unknown";
                deviceAdapter.add(name + "\n" + device.getAddress());
                deviceAddresses.add(device.getAddress());
            }
        }
        deviceAdapter.notifyDataSetChanged();
    }
}
