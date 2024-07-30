package org.traccar.client;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BluetoothTimedService extends Service implements SerialListener, ServiceConnection {

    private static final String TAG = "BluetoothTimedService";
    private static final String START_SEQUENCE = "01410005";
    private static final int REQUIRED_LENGTH = 234;
    private static final int DISCONNECT_DELAY_MS = 30000;
    private static final long SCHEDULE_DELAY_MINUTES = 1;

    private String deviceAddress;
    private SerialService serialService;
    private ScheduledExecutorService scheduler;
    private StringBuilder accumulatedData = new StringBuilder();
    private PowerManager.WakeLock wakeLock;
    private boolean bound = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.v(TAG, "onCreate called");
        acquireWakeLock();
        Intent intent = new Intent(this, SerialService.class);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand called");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.v(TAG, "onDestroy called");
        super.onDestroy();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (bound) {
            unbindService(this);
            bound = false;
        }
        disconnect();
        releaseWakeLock();
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "BluetoothTimedService::WakeLock");
        wakeLock.acquire(10*60*1000L /*10 minutes*/);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void initializeConnection() {
        Log.v(TAG, "initializeConnection called");
        deviceAddress = BluetoothDeviceManager.getLastDeviceAddress();
        if (deviceAddress != null && !deviceAddress.isEmpty()) {
            Log.v(TAG, "Initializing connection with device: " + deviceAddress);
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(this::scheduledConnectAndFetchData, 0, SCHEDULE_DELAY_MINUTES, TimeUnit.MINUTES);
        } else {
            Log.w(TAG, "Device address is null or empty, scheduler not started");
        }
    }

    private void scheduledConnectAndFetchData() {
        try {
            Log.v(TAG, "scheduledConnectAndFetchData called");
            String currentAddress = BluetoothDeviceManager.getLastDeviceAddress();
            if (!deviceAddress.equals(currentAddress)) {
                deviceAddress = currentAddress;
                Log.v(TAG, "Device address updated to: " + deviceAddress);
            }
            Log.v(TAG, "Connecting to device ID: " + deviceAddress);
            connect();
            scheduler.schedule(this::disconnect, DISCONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Error in scheduledConnectAndFetchData: " + e.getMessage());
        }
    }

    private void connect() {
        Log.v(TAG, "Attempting to connect...");
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Device doesn't support Bluetooth");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            return;
        }
        try {
            Log.v(TAG, "Getting remote device with address: " + deviceAddress);
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            Log.v(TAG, "Creating SerialSocket...");
            SerialSocket socket = new SerialSocket(getApplicationContext(), device);
            Log.v(TAG, "Connecting to service...");
            if (serialService != null) {
                serialService.connect(socket);
            } else {
                Log.e(TAG, "SerialService is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to device: " + e.getMessage(), e);
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        if (serialService != null) {
            serialService.disconnect();
        }
    }
    private synchronized void receive(ArrayDeque<byte[]> datas) {
        for (byte[] data : datas) {
            String hexString = bytesToHex(data).toUpperCase();
            accumulatedData.append(hexString);
            processAccumulatedData();
        }
    }
    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.v(TAG, "SerialService connected");
        serialService = ((SerialService.SerialBinder) binder).getService();
        serialService.attach(this);
        bound = true;
        initializeConnection();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.v(TAG, "SerialService disconnected");
        serialService = null;
        bound = false;
    }


    private void processAccumulatedData() {
        if (accumulatedData.indexOf(START_SEQUENCE) == 0 && accumulatedData.length() >= REQUIRED_LENGTH) {
            byte requiredByte = getRequiredByte();
            int requiredBytes = getRequiredBytes();
            processAndClearData(requiredByte, requiredBytes);
            disconnect();
        } else if (accumulatedData.indexOf(START_SEQUENCE) != 0) {
            accumulatedData.setLength(0);
        }
    }

    private byte getRequiredByte() {
        if (accumulatedData.length() >= 128) {
            String hexValue = accumulatedData.substring(126, 128);
            return (byte) Integer.parseInt(hexValue, 16);
        }
        return 0;
    }

    private int getRequiredBytes() {
        if (accumulatedData.length() >= 64) {
            String hexValue = accumulatedData.substring(60, 64);
            return Integer.parseInt(hexValue, 16);
        }
        return 0;
    }

    private void processAndClearData(byte requiredByte, int requiredBytes) {
        Log.v(TAG, "Accumulated Data: " + accumulatedData.toString());
        Log.v(TAG, "Required byte: " + requiredByte);
        Log.v(TAG, "Required bytes: " + requiredBytes);
        GlobalData.INSTANCE.setRequiredByte(requiredByte);
        GlobalData.INSTANCE.setRequiredBytes(requiredBytes);
        accumulatedData.setLength(0);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    @Override
    public void onSerialConnect() {
        Log.v(TAG, "Serial connection established");
    }

    @Override
    public void onSerialConnectError(Exception e) {
        Log.e(TAG, "Serial connection error: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        Log.e(TAG, "Serial IO error: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

}