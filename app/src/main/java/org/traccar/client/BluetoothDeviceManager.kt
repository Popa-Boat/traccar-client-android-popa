package org.traccar.client

object BluetoothDeviceManager {
    private var lastDeviceAddress: String? = null

    fun setLastDeviceAddress(address: String) {
        lastDeviceAddress = address
    }

    @JvmStatic
    fun getLastDeviceAddress(): String? = lastDeviceAddress

}