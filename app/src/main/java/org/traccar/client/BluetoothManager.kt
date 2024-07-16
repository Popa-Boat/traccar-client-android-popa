package org.traccar.client
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.util.*

class BluetoothManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null

    fun isBluetoothSupported() = bluetoothAdapter != null

    fun isBluetoothEnabled() = bluetoothAdapter?.isEnabled ?: false

    fun startDiscovery() {
        Log.d("BluetoothManager", "Starting discovery...")

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // Permission not granted, handle accordingly
            return
        }
        bluetoothAdapter?.startDiscovery()
        Log.d("BluetoothManager", "Discovery started")

    }

    fun connectToDevice(device: BluetoothDevice, uuid: UUID, callback: ConnectionCallback) {
        Log.d("BluetoothManager", "Attempting to connect to device: ${device.name}")

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        bluetoothAdapter?.cancelDiscovery()
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
            bluetoothSocket?.connect()
            callback.onConnectionEstablished() // Invoke callback here
        } catch (e: IOException) {
            // Handle connection failure
        }
        Log.d("BluetoothManager", "Connection attempt finished")

    }

    fun disconnect() {
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            // Handle disconnection failure
        }
    }

    fun sendData(data: String) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission not granted
            return
        }
        try {
            val outputStream = bluetoothSocket?.outputStream
            outputStream?.write(data.toByteArray())
        } catch (e: IOException) {
            // Handle send data failure
        }
    }

    fun receiveData(): String? {
        Log.d("BluetoothManager", "Receiving data...")

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("BluetoothManager", "Data received")

            // Permission not granted
            return null
        }
        return try {
            val inputStream = bluetoothSocket?.inputStream;
            val buffer = ByteArray(1024)
            val bytes = inputStream?.read(buffer)
            String(buffer, 0, bytes ?: 0)
        } catch (e: IOException) {
            // Handle receive data failure
            null
        }
    }

    fun getDeviceByName(deviceName: String): BluetoothDevice? {
        Log.d("BluetoothManager", "Searching for device by name: $deviceName")
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("BluetoothManager", "Device search completed")
            // Permission not granted
            return null
        }
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        pairedDevices?.forEach { device ->
            if (device.name == deviceName) {
                return device
            }
        }
        return null
    }
    interface ConnectionCallback {
        fun onConnectionEstablished()
    }
}
class DataListener(private val bluetoothManager: BluetoothManager, private val onDataReceived: (String) -> Unit) : Thread() {
    var running = true

    override fun run() {
        while (running) {
            bluetoothManager.receiveData()?.let { data ->
                if (data.isNotEmpty()) {
                    onDataReceived(data)
                }
            }
            try {
                sleep(100) // Adjust based on your needs
            } catch (e: InterruptedException) {
                running = false
            }
        }
    }

    fun stopListening() {
        running = false
    }
}