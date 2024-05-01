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

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.*
import com.google.android.material.snackbar.Snackbar
import android.view.View
import android.widget.TextView
class BluetoothActivity : AppCompatActivity() {

    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Replace with your UUID
    private val REQUEST_BLUETOOTH_SCAN_PERMISSION = 1
    private val REQUEST_DISCOVERABLE = 2
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var messageTextView: TextView

    private fun showMessage(message: String) {
        messageTextView.text = message
        messageTextView.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        messageTextView = findViewById(R.id.messageTextView)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.battery, menu)
        return super.onCreateOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.connect) {
            // Check if the BLUETOOTH_SCAN permission is granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                // Permission is granted, cancel Bluetooth discovery
                bluetoothAdapter.cancelDiscovery()
                // Proceed with showing paired devices list
                showPairedDevicesList()
            } else {
                // Permission is not granted, request the permission from the user
                requestBluetoothScanPermission()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun requestBluetoothScanPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_BLUETOOTH_SCAN_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_SCAN_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, cancel Bluetooth discovery and proceed with showing paired devices list
                bluetoothAdapter.cancelDiscovery()
                showPairedDevicesList()
            } else {
                // Permission denied, show a message
                showMessage("Permission denied, cannot initiate Bluetooth discovery")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DISCOVERABLE) {
            if (resultCode == Activity.RESULT_OK) {
                // If discovery is enabled, show paired devices list
                showPairedDevicesList()
            } else {
                // If discovery is canceled or failed, show an error message
                showMessage("Bluetooth discovery failed or canceled")
            }
        }
    }

    private fun showPairedDevicesList() {
        // Check if the BLUETOOTH permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
            == PackageManager.PERMISSION_GRANTED) {
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
            val deviceNames = pairedDevices?.map { it.name }?.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select a device")
                .setItems(deviceNames) { _, which ->
                    // Connect to the selected device
                    pairedDevices?.elementAtOrNull(which)?.let { device ->
                        ConnectThread(device).start()
                    }
                }
                .show()
        } else {
            // Permission is not granted, request the permission from the user
            requestBluetoothPermission()
        }
    }

    private fun requestBluetoothPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH),
            REQUEST_BLUETOOTH_PERMISSION
        )
    }


    private inner class ConnectThread(device: BluetoothDevice) : Thread() {

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(MY_UUID)
        }

        override fun run() {
            // Check if the BLUETOOTH permission is granted
            if (ContextCompat.checkSelfPermission(this@BluetoothActivity, Manifest.permission.BLUETOOTH)
                == PackageManager.PERMISSION_GRANTED) {
                // Permission is granted, proceed with Bluetooth connection
                // Cancel discovery because it otherwise slows down the connection.
                bluetoothAdapter.cancelDiscovery()

                mmSocket?.let { socket ->
                    try {
                        // Connect to the remote device through the socket. This call blocks
                        // until it succeeds or throws an exception.
                        socket.connect()

                        // The connection attempt succeeded. Perform work associated with
                        // the connection in a separate thread.
                        manageMyConnectedSocket()
                    } catch (e: IOException) {
                        Log.e(TAG, "Could not connect to the Bluetooth host", e)
                    }
                }
            } else {
                // Permission is not granted, request the permission from the user
                requestBluetoothPermission()
            }
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }

    private fun manageMyConnectedSocket() {
        // Implement your logic for managing the connected socket here
    }

    companion object {
        private const val TAG = "BluetoothActivity"
        private const val REQUEST_BLUETOOTH_PERMISSION = 3
    }
}
