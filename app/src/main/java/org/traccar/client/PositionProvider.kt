/*
 * Copyright 2013 - 2022 Anton Tananaev (anton@traccar.org)
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

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.Location
import android.os.BatteryManager
import androidx.preference.PreferenceManager
import android.util.Log
import kotlin.math.abs

abstract class PositionProvider(
    protected val context: Context,
    protected val listener: PositionListener,
) {

    interface PositionListener {
        fun onPositionUpdate(position: Position)
        fun onPositionError(error: Throwable)
    }

    protected var preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    protected var deviceId = preferences.getString(MainFragment.KEY_DEVICE, "undefined")!!
    protected var interval = preferences.getString(MainFragment.KEY_INTERVAL, "600")!!.toLong() * 1000
    protected var distance: Double = preferences.getString(MainFragment.KEY_DISTANCE, "0")!!.toInt().toDouble()
    protected var angle: Double = preferences.getString(MainFragment.KEY_ANGLE, "0")!!.toInt().toDouble()
    private var lastLocation: Location? = null

    abstract fun startUpdates()
    abstract fun stopUpdates()
    abstract fun requestSingleLocation()

    protected fun processLocation(location: Location?) {
        val lastLocation = this.lastLocation
        if (location != null &&
            (lastLocation == null || location.time - lastLocation.time >= interval || distance > 0
                    && location.distanceTo(lastLocation) >= distance || angle > 0
                    && abs(location.bearing - lastLocation.bearing) >= angle)
        ) {
            Log.i(TAG, "location new")
            this.lastLocation = location
            listener.onPositionUpdate(Position(deviceId, location, getBatteryStatus(context)))
        } else {
            Log.i(TAG, if (location != null) "location ignored" else "location nil")
        }
    }

    protected fun getBatteryStatus(context: Context): BatteryStatus {
        //val bluetoothValue = TerminalFragment.//BluetoothActivity.getBluetoothBatValue() // Replace this with your method to get value from Bluetooth device
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val receivedText = TextDataManager.getTextData()
        if (batteryIntent != null) {
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return BatteryStatus(
//                level = receivedText * 100.0 / scale, // Use the Bluetooth value instead of battery level
                level = 100 * 100.0 / scale, // Use the Bluetooth value instead of battery level
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }
        return BatteryStatus()
    }

    companion object {
        private val TAG = PositionProvider::class.java.simpleName
        const val MINIMUM_INTERVAL: Long = 1000
    }

}
object TextDataManager {
    private var textData: String = ""
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun setTextData(newTextData: String) {
        textData = newTextData
        notifyListeners()
    }

    @Synchronized
    fun getTextData(): String {
        return textData
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it(textData) }
    }
}