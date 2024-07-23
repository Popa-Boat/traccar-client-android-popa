package org.traccar.client;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import androidx.fragment.app.Fragment;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BluetoothTimedConnection extends Fragment implements ServiceConnection, SerialListener {

    private String deviceAddress = BluetoothDeviceManager.getLastDeviceAddress();
    private SerialService service;
    private ScheduledExecutorService scheduler;
    private StringBuilder accumulatedData = new StringBuilder();
    private final String startSequence = "01410005";
    private final int requiredLength = 234; // The length of the structure in characters

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeConnection();
    }

    public void onServiceConnected(ComponentName name, IBinder service) {
        this.service = ((SerialService.SerialBinder) service).getService();
        this.service.attach(this);
        // Add any additional setup after connecting to the service
    }

    public void onServiceDisconnected(ComponentName name) {
        this.service.detach();
        this.service = null;
        // Handle the service being unexpectedly disconnected if needed
    }
    @Override
    public void onSerialConnect() {
        Log.d("BluetoothConnection", "Serial connection established");
        // Update connection status or perform other actions upon successful connection
    }
    private void initializeConnection() {
        if (deviceAddress != null) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(this::scheduledConnectAndFetchData, 0, 1, TimeUnit.MINUTES);
        } else {
            Log.d("BluetoothConnection", "Device address is null, scheduler not started");
        }
    }

    private void scheduledConnectAndFetchData() {
        String currentAddress = BluetoothDeviceManager.getLastDeviceAddress();
        if (!deviceAddress.equals(currentAddress)) {
            deviceAddress = currentAddress;
        }
        connect();
        new Handler().postDelayed(this::disconnect, 30000); // Adjust the delay as needed
    }

    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        service.disconnect();
    }

    private void receive(ArrayDeque<byte[]> datas) {
        for (byte[] data : datas) {
            String hexString = bytesToHex(data).toUpperCase();
            accumulatedData.append(hexString);
            byte requiredByte = 0;
            int requiredBytes = 0;
            // Check if accumulated data starts with the start sequence
            if (accumulatedData.indexOf(startSequence) == 0) {
                // Check if the accumulated data has reached the required length
                if (accumulatedData.length() >= 128) { // Each byte is 2 characters in hex string
                    String hexValue = accumulatedData.substring(126, 128); // Get the hex representation of the 64th byte
                    int intValue = Integer.parseInt(hexValue, 16);
                    requiredByte = (byte) intValue;
                }
                if (accumulatedData.length() >= 64) { // Each byte is 2 characters in hex string
                    String hexValue = accumulatedData.substring(60, 64); // Get the hex representation of the 64th byte
                     requiredBytes = Integer.parseInt(hexValue, 16);
                }
                if (accumulatedData.length() >= requiredLength) {
                    // Here, you can check for the exact match with your structure
                    // If it matches, process the data
                    processAccumulatedData(accumulatedData.toString(), requiredByte, requiredBytes);

                    // Clear the StringBuilder for the next data
                    accumulatedData.setLength(0);
                    disconnect();
                }
            } else {
                // If the start sequence is not found, reset the accumulated data
                accumulatedData.setLength(0);
            }
        }
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
    private void processAccumulatedData(String data, byte requiredByte, int requiredBytes) {
        Log.d("Debug", "Accumulated Data: " + data);
        Log.d("Debug", "Required byte: " + requiredByte);
        Log.d("Debug", "Required bytes: " + requiredBytes);
        GlobalData.INSTANCE.setRequiredByte(requiredByte);
        GlobalData.INSTANCE.setRequiredBytes(requiredBytes);

    }


    @Override
    public void onSerialConnectError(Exception e) {
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        disconnect();
    }

}
