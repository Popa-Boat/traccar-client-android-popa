package org.traccar.client
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.traccar.client.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import android.Manifest

class BluetoothViewModel(private val context: Context) : ViewModel() {
    private val bluetoothManager = BluetoothManager(context)

    private val _deviceNames = MutableLiveData<List<String>>()
    val deviceNames: LiveData<List<String>> = _deviceNames
    private val _deviceDataStream = MutableLiveData<String>()
    val deviceDataStream: LiveData<String> = _deviceDataStream

    fun updateDeviceNames(names: List<String>) {
        _deviceNames.value = names
    }
    fun receiveData(data: String) {
        _deviceDataStream.postValue(data)
    }
    // Add other LiveData and update methods as needed
    fun startDeviceDiscovery() {
        bluetoothManager.startDiscovery()
    }
    fun connectToDevice(deviceName: String) {
        val uuid = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val device = bluetoothManager.getDeviceByName(deviceName)
        device?.let {
            bluetoothManager.connectToDevice(it, uuid, object : BluetoothManager.ConnectionCallback {
                override fun onConnectionEstablished() {
                    val dataListener = DataListener(bluetoothManager) { data ->
                        receiveData(data)
                    }
                    dataListener.start()
                }
            })
        }
    }
    fun addDiscoveredDevice(device: BluetoothDevice) {
        // Check for necessary permissions before adding the device
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

            val deviceName = device.name ?: device.address
            synchronized(this) {
                val updatedList = _deviceNames.value?.toMutableList() ?: mutableListOf()
                if (!updatedList.contains(deviceName)) {
                    updatedList.add(deviceName)
                    _deviceNames.postValue(updatedList)
                }
            }
        } else {
            // Handle the case where permissions are not granted
            Log.d("BluetoothViewModel", "Permission for Bluetooth scan not granted")
        }
    }
}
class BluetoothViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BluetoothViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BluetoothViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}