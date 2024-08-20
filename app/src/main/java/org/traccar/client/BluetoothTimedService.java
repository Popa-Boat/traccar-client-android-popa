package org.traccar.client;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BluetoothTimedService extends Service implements SerialListener, ServiceConnection {

    private static final String TAG = "BluetoothTimedService";
    private static final String START_SEQUENCE = ":";
    private static final int REQUIRED_LENGTH = 235;
    private static final long SCHEDULE_DELAY_MINUTES = 1;

    private String deviceAddress;
    private SerialService serialService;
    private ScheduledExecutorService scheduler;
    private StringBuilder accumulatedData = new StringBuilder();
    private PowerManager.WakeLock wakeLock;
    private boolean bound = false;
    private static final int CONNECTION_TIMEOUT_MS = 30000; // 10 seconds timeout
    private boolean dataProcessedThisSchedule = false;
    private ScheduledExecutorService timeoutScheduler;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.v(TAG, "onCreate called");
        acquireWakeLock();
        Intent intent = new Intent(this, SerialService.class);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(bondStateReceiver, filter);
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
        unregisterReceiver(bondStateReceiver);
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
        wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private void scheduledConnectAndFetchData() {
        try {
            Log.v(TAG, "scheduledConnectAndFetchData called");
            dataProcessedThisSchedule = false;
            String currentAddress = BluetoothDeviceManager.getLastDeviceAddress();
            if (!deviceAddress.equals(currentAddress)) {
                deviceAddress = currentAddress;
                Log.v(TAG, "Device address updated to: " + deviceAddress);
            }
            connect();
            scheduleDisconnectTimeout();
        } catch (Exception e) {
            Log.e(TAG, "Error in scheduledConnectAndFetchData: " + e.getMessage(), e);
            disconnect();
        }
    }

    private void connect() {
        Log.v(TAG, "Attempting to connect...");
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            Log.v(TAG, "Creating SerialSocket...");
            SerialSocket socket = new SerialSocket(getApplicationContext(), device);
            Log.v(TAG, "Connecting to service...");
            if (serialService != null) {
                serialService.connect(socket);
                Log.v(TAG, "Connection request sent to SerialService");
            } else {
                Log.e(TAG, "SerialService is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error connecting to device: " + e.getMessage(), e);
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        Log.v(TAG, "Disconnecting from device: " + deviceAddress);
        if (serialService != null) {
            serialService.disconnect();
        }
        if (timeoutScheduler != null && !timeoutScheduler.isShutdown()) {
            timeoutScheduler.shutdownNow();
        }
        accumulatedData.setLength(0);  // Clear the buffer on disconnect
    }

    private void scheduleDisconnectTimeout() {
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        timeoutScheduler.schedule(this::disconnectIfNotProcessed, CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void disconnectIfNotProcessed() {
        if (!dataProcessedThisSchedule) {
            Log.v(TAG, "Disconnecting due to timeout or no data processed");
            disconnect();
        }
    }

    private void receive(ArrayDeque<byte[]> datas) {
        for (byte[] data : datas) {
            String asciiString = new String(data);
            Log.v(TAG, "Received data: " + asciiString);
            accumulatedData.append(asciiString);
        }
        processAccumulatedData();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.v(TAG, "SerialService connected");
        serialService = ((SerialService.SerialBinder) binder).getService();
        serialService.attach(this);
        bound = true;
        initializeConnection();
    }

    private void initializeConnection() {
        Log.v(TAG, "initializeConnection called");
        deviceAddress = BluetoothDeviceManager.getLastDeviceAddress();
        if (deviceAddress != null && !deviceAddress.isEmpty()) {
            Log.v(TAG, "Initializing connection with device: " + deviceAddress);
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
            }
            scheduler.scheduleWithFixedDelay(this::scheduledConnectAndFetchData, 0, SCHEDULE_DELAY_MINUTES, TimeUnit.MINUTES);
        } else {
            Log.w(TAG, "Device address is null or empty, scheduler not started");
        }
    }


    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.v(TAG, "SerialService disconnected");
        serialService = null;
        bound = false;
    }


    private void processAccumulatedData() {
        while (accumulatedData.length() >= REQUIRED_LENGTH) {
            int startIndex = accumulatedData.indexOf(START_SEQUENCE);
            if (startIndex >= 0 && accumulatedData.length() >= startIndex + REQUIRED_LENGTH) {
                String validMessage = accumulatedData.substring(startIndex, startIndex + REQUIRED_LENGTH);
                processValidMessage(validMessage);
                dataProcessedThisSchedule = true;
                accumulatedData.delete(0, startIndex + REQUIRED_LENGTH);
            } else if (startIndex == -1) {
                accumulatedData.setLength(0);
                break;
            } else {
                accumulatedData.delete(0, startIndex);
                break;
            }
        }
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
        String asciiString = new String(data);
        Log.v(TAG, "Data received: " + asciiString);
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

       private void processValidMessage(String message) {
        byte requiredByte = getRequiredByte(message);
        int requiredBytes = getRequiredBytes(message);
        Log.v(TAG, "Valid message: " + message);
        Log.v(TAG, "Required byte: " + requiredByte);
        Log.v(TAG, "Required bytes: " + requiredBytes);
        GlobalData.INSTANCE.setRequiredByte(requiredByte);
        GlobalData.INSTANCE.setRequiredBytes(requiredBytes);

        // Check if the processed data is not an error
        if (!isErrorData(requiredByte, requiredBytes)) {
            disconnect();
        }
    }
    private boolean isErrorData(byte requiredByte, int requiredBytes) {
        // Implement your logic to determine if the data is an error
        // For example, return true if requiredBytes is 0 or any other condition
        return requiredBytes == 0;
    }
    private byte getRequiredByte(String message) {
        try {
            //String requiredBytesString = message.substring(155,157); //V2
            String requiredBytesString = message.substring(127, 129); //V1
            if (requiredBytesString.matches("[0-9A-Fa-f]+")) { // Check if the substring is a valid hexadecimal number
                return (byte) Integer.parseInt(requiredBytesString, 16); // Parse as a hexadecimal number and cast to byte
            } else {
                Log.e(TAG, "Invalid number format in message: " + requiredBytesString);
                return 0; // or handle the error appropriately
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing required bytes from message: " + message, e);
            return 0; // or handle the error appropriately
        }
    }
    private int getRequiredBytes(String message) {
        try {
            String requiredBytesString = message.substring(97, 101);
            if (requiredBytesString.matches("[0-9A-Fa-f]+")) { // Check if the substring is a valid hexadecimal number
                return Integer.parseInt(requiredBytesString, 16); // Parse as a hexadecimal number
            } else {
                Log.e(TAG, "Invalid number format in message: " + requiredBytesString);
                return 0; // or handle the error appropriately
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing required bytes from message: " + message, e);
            return 0; // or handle the error appropriately
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        Log.e(TAG, "Serial IO error: " + e.getMessage());
        disconnect();
    }
    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (device.getAddress().equals(deviceAddress)) {
                    Log.v(TAG, "Bond state changed for device " + deviceAddress + ": " + bondState);
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        connect();
                    }
                }
            }
        }
    };
}