package com.nestegg.btlogger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* TODO: react to grant/deny */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                        Text(
                            text = "Bluetooth Logger",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = "Logs ACL connect/disconnect events to a CSV in Google Drive.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = { requestNeededPermissions() },
                            modifier = Modifier.padding(top = 24.dp),
                        ) { Text("Grant permissions") }
                        Button(
                            onClick = { /* TODO: launch Google Sign-In + Drive scope */ },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Sign in to Google Drive") }
                    }
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val needed = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
