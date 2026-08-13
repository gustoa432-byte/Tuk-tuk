package com.blink.dtn.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Bring the mesh back after reboot / package replace. Missing runtime
 * permissions fail quietly — the next UI open will ask for them.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        if (!hasBleRuntimePermissions(context)) {
            Log.i(TAG, "Boot: BLE permissions missing — wait for UI")
            return
        }
        runCatching {
            val service = Intent(context, BLinkMeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
            Log.i(TAG, "Boot: mesh service requested ($action)")
        }.onFailure {
            Log.w(TAG, "Boot: could not start mesh: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        fun hasBleRuntimePermissions(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val scan = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
                val connect = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                val advertise = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_ADVERTISE
                ) == PackageManager.PERMISSION_GRANTED
                scan && connect && advertise
            } else {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
