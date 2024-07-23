package org.traccar.client

object GlobalData {
    @Volatile var requiredByte: Byte = 0
    @Volatile var requiredBytes: Int = 0

}