/*
 * Copyright 2024 Rimas Bacevičius
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client
import android.view.Menu
import android.view.MenuItem
import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class BluetoothActivity : AppCompatActivity() {

    private val bluetoothViewModel: BluetoothViewModel by viewModels {
        BluetoothViewModelFactory(applicationContext)
    }
    private val receiver = object : BroadcastReceiver() {
override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
        BluetoothDevice.ACTION_FOUND -> {
            Log.d("BluetoothActivity", "Device found")
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
            bluetoothViewModel.addDiscoveredDevice(device)
        }
        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
            Log.d("BluetoothActivity", "Discovery finished")
            // No additional action needed here for this scenario
        }
    }
}
}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth)
        checkAndRequestPermissions()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN), 0)
        }

        bluetoothViewModel.deviceDataStream.observe(this) { dataStream ->
            findViewById<TextView>(R.id.data_stream_text_view).text = dataStream
        }
        // Observe deviceNames LiveData to show dialog when the list is updated
        bluetoothViewModel.deviceNames.observe(this) { deviceNames ->
            if (deviceNames.isNotEmpty()) {
                showBluetoothDevicesDialog()
            }
        }

        // Other initialization code
    }

private fun showBluetoothDevicesDialog() {
    val devicesArray = bluetoothViewModel.deviceNames.value?.toTypedArray() ?: arrayOf()
    val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, devicesArray)
    AlertDialog.Builder(this)
        .setTitle("Select Bluetooth Device")
        .setAdapter(adapter) { dialog, which ->
            val deviceName = devicesArray[which]
            bluetoothViewModel.connectToDevice(deviceName)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.battery, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.connect -> {
                bluetoothViewModel.startDeviceDiscovery()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    private fun checkAndRequestPermissions() {
    val requiredPermissions = mutableListOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val missingPermissions = requiredPermissions.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (missingPermissions.isNotEmpty()) {
        ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 0)
    }
}
    override fun onDestroy() {
        super.onDestroy()
        // Unregister BroadcastReceiver
        unregisterReceiver(receiver)
    }
}
