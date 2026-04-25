package com.nestegg.btlogger.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nestegg.btlogger.storage.BtEvent
import com.nestegg.btlogger.storage.EventStore
import com.nestegg.btlogger.storage.EventType

/**
 * Statically-registered receiver for ACL connect/disconnect.
 * Fires at the link-layer level — earlier than A2DP, earlier than anything MileIQ sees.
 */
class BluetoothEventReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT requested at runtime in MainActivity
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        val type = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> EventType.CONNECTED
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> EventType.DISCONNECTED
            else -> return
        }

        val event = BtEvent(
            utcTimestamp = System.currentTimeMillis(),
            eventType = type,
            deviceName = runCatching { device.name }.getOrNull(),
            deviceMac = device.address,
        )

        Log.i(TAG, "$type ${event.deviceMac} (${event.deviceName})")
        EventStore(context).append(event)
    }

    companion object {
        private const val TAG = "BtEventReceiver"
    }
}
