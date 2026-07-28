package com.blink.dtn.ble

import java.util.UUID

/** Shared BLE service / characteristic UUIDs for TukTuk mesh. */
object BleMeshUuids {
    val SERVICE: UUID = UUID.fromString("0000b111-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC: UUID = UUID.fromString("0000b112-0000-1000-8000-00805f9b34fb")
}
