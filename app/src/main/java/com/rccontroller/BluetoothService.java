package com.rccontroller;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Manages a Bluetooth Classic (SPP) connection to the HC-06 module.
 * HC-06 uses the standard SPP UUID.
 */
public class BluetoothService {

    private static final String TAG = "BluetoothService";
    // Standard SPP UUID used by HC-06
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface ConnectionCallback {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String message);
    }

    public interface MessageCallback {
        void onMessageReceived(String message);
    }

    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private ConnectThread connectThread;
    private ReadThread readThread;
    private volatile boolean isConnected = false;
    private ConnectionCallback callback;
    private MessageCallback messageCallback;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public BluetoothService(BluetoothAdapter adapter) {
        this.adapter = adapter;
    }

    public void setCallback(ConnectionCallback callback) {
        this.callback = callback;
    }

    public void setMessageCallback(MessageCallback messageCallback) {
        this.messageCallback = messageCallback;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void connect(BluetoothDevice device) {
        disconnect();
        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    public void disconnect() {
        isConnected = false;
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (readThread != null) {
            readThread.cancel();
            readThread = null;
        }
        closeSocket();
    }

    private void closeSocket() {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        } catch (IOException ignored) {}
        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException ignored) {}
    }

    /**
     * Send a formatted control packet to the HC-06.
     *
     * Format: "V:+0.0000,D:L,T:0.0000\n"
     *   V = velocity   [-1.0000 .. +1.0000]  (+ = forward, - = reverse)
     *   D = direction  [L | R | N]           (N = neutral / straight)
     *   T = turn mag   [0.0000 .. 1.0000]
     *
     * @param velocity   -1.0 to +1.0
     * @param turnValue  0.0 to 1.0 (magnitude)
     * @param turnLeft   true = left, false = right (ignored if turnValue ~0)
     */
    public void sendControl(float velocity, float turnValue, boolean turnLeft) {
        if (!isConnected || outputStream == null) return;

        velocity = Math.max(-1f, Math.min(1f, velocity));
        turnValue = Math.max(0f, Math.min(1f, Math.abs(turnValue)));

        String dirChar;
        if (turnValue < 0.0001f) {
            dirChar = "N";
        } else {
            dirChar = turnLeft ? "L" : "R";
        }

        // Sign prefix for velocity
        String velStr = String.format("%+.4f", velocity);
        String turnStr = String.format("%.4f", turnValue);
        String packet = "V:" + velStr + ",D:" + dirChar + ",T:" + turnStr + "\n";

        try {
            outputStream.write(packet.getBytes());
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Send failed: " + e.getMessage());
            isConnected = false;
            mainHandler.post(() -> {
                if (callback != null) callback.onDisconnected();
            });
        }
    }

    // ─── ConnectThread ────────────────────────────────────────────────────────

    private class ConnectThread extends Thread {
        private final BluetoothDevice device;
        private BluetoothSocket tmpSocket;
        private volatile boolean cancelled = false;

        ConnectThread(BluetoothDevice device) {
            this.device = device;
            setName("BT-ConnectThread");
            try {
                tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket create failed", e);
            }
        }

        @Override
        public void run() {
            if (tmpSocket == null) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Failed to create socket");
                });
                return;
            }

            // Cancel discovery to speed up connection
            if (adapter.isDiscovering()) adapter.cancelDiscovery();

            try {
                tmpSocket.connect();
            } catch (IOException connectException) {
                if (cancelled) return;
                Log.e(TAG, "Connect failed", connectException);
                try { tmpSocket.close(); } catch (IOException ignored) {}
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Connection failed: " + connectException.getMessage());
                });
                return;
            }

            if (cancelled) {
                try { tmpSocket.close(); } catch (IOException ignored) {}
                return;
            }

            // Success
            socket = tmpSocket;
            try {
                outputStream = socket.getOutputStream();
            } catch (IOException e) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Could not open stream");
                });
                return;
            }

            isConnected = true;
            final String name = device.getName() != null ? device.getName() : device.getAddress();
            mainHandler.post(() -> {
                if (callback != null) callback.onConnected(name);
            });

            // Start reading incoming data
            readThread = new ReadThread();
            readThread.start();
        }

        void cancel() {
            cancelled = true;
            try {
                if (tmpSocket != null) tmpSocket.close();
            } catch (IOException ignored) {}
        }
    }

    // ─── ReadThread ───────────────────────────────────────────────────────────

    private class ReadThread extends Thread {
        private volatile boolean cancelled = false;

        ReadThread() {
            setName("BT-ReadThread");
        }

        @Override
        public void run() {
            InputStream inputStream;
            try {
                inputStream = socket.getInputStream();
            } catch (IOException e) {
                Log.e(TAG, "Could not open input stream", e);
                return;
            }

            StringBuilder buffer = new StringBuilder();
            byte[] chunk = new byte[256];

            while (!cancelled && isConnected) {
                try {
                    int bytesRead = inputStream.read(chunk);
                    if (bytesRead > 0) {
                        buffer.append(new String(chunk, 0, bytesRead));
                        // Dispatch complete lines
                        int newlineIdx;
                        while ((newlineIdx = buffer.indexOf("\n")) >= 0) {
                            String line = buffer.substring(0, newlineIdx).trim();
                            buffer.delete(0, newlineIdx + 1);
                            if (!line.isEmpty()) {
                                final String msg = line;
                                mainHandler.post(() -> {
                                    if (messageCallback != null) messageCallback.onMessageReceived(msg);
                                });
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!cancelled) {
                        Log.e(TAG, "Read failed: " + e.getMessage());
                        isConnected = false;
                        mainHandler.post(() -> {
                            if (callback != null) callback.onDisconnected();
                        });
                    }
                    break;
                }
            }
        }

        void cancel() {
            cancelled = true;
        }
    }
}