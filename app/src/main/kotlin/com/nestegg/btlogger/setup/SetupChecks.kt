package com.nestegg.btlogger.setup

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.core.content.ContextCompat

fun readSetupStatus(context: Context): SetupStatus {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    val bluetoothConnectGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) == PackageManager.PERMISSION_GRANTED
    return SetupStatus(
        bluetoothConnectGranted = bluetoothConnectGranted,
        batteryExempt = batteryExempt,
    )
}

fun isBluetoothAdapterEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter?.isEnabled == true
}

// VALIDATED means Android confirmed real internet (captive-portal probe passed), not merely "connected".
fun isActiveNetworkValidated(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
