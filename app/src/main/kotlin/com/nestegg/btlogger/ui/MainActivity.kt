package com.nestegg.btlogger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.nestegg.btlogger.setup.SetupIssue
import com.nestegg.btlogger.setup.readSetupStatus
import com.nestegg.btlogger.storage.BtEvent
import com.nestegg.btlogger.storage.EventStore
import com.nestegg.btlogger.storage.EventType
import com.nestegg.btlogger.sync.DriveSyncWorker
import com.nestegg.btlogger.sync.SyncState
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val refreshTick: MutableIntState = mutableIntStateOf(0)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.w(TAG, "Permissions denied: $denied")
            val permanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
            if (permanentlyDenied) openAppSettings()
        }
        refreshTick.intValue++
    }

    private val signIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Sign-in cancelled (resultCode=${result.resultCode})")
            return@registerForActivityResult
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
            val email = account.email
            if (email == null) {
                Log.w(TAG, "Sign-in succeeded but no email on account")
                return@registerForActivityResult
            }
            SyncState.from(this).accountName = email
            Log.i(TAG, "Signed in as $email")
            refreshTick.intValue++
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed: statusCode=${e.statusCode}", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    StatusScreen(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                        refreshTick = refreshTick.intValue,
                        onGrantPermissions = ::requestNeededPermissions,
                        onFixBattery = ::requestBatteryExemption,
                        onSignIn = ::launchSignIn,
                        onSignOut = ::signOut,
                        onSyncNow = ::triggerSyncNow,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    private fun launchSignIn() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        signIn.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }

    private fun signOut() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        GoogleSignIn.getClient(this, options).signOut()
            .addOnCompleteListener {
                SyncState.from(this).accountName = null
                Log.i(TAG, "Signed out")
                refreshTick.intValue++
            }
    }

    private fun triggerSyncNow() {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>().build()
        WorkManager.getInstance(this).enqueue(request)
        Log.i(TAG, "Manual sync enqueued")
    }

    private fun requestNeededPermissions() {
        val needed = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    companion object {
        private const val TAG = "BtLoggerUi"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}

@Composable
private fun StatusScreen(
    modifier: Modifier,
    refreshTick: Int,
    onGrantPermissions: () -> Unit,
    onFixBattery: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val context = LocalContext.current
    val syncState = remember { SyncState.from(context) }
    val store = remember { EventStore(context) }

    val account = remember(refreshTick) { syncState.accountName }
    val lastSync = remember(refreshTick) { syncState.lastSyncMillis }
    val eventCount = remember(refreshTick) { store.totalEvents() }
    val recent = remember(refreshTick) { store.recentConnections(RECENT_LIMIT) }
    val lastHeartbeat = remember(refreshTick) { store.lastHeartbeat() }
    val setup = remember(refreshTick) { readSetupStatus(context) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupWarningBanner(
            issues = setup.issues,
            onFixBattery = onFixBattery,
            onFixPermission = onGrantPermissions,
        )
        Text("Bluetooth Logger", style = MaterialTheme.typography.headlineMedium)
        Text("Logs ACL connect/disconnect events to a CSV in Google Drive.")

        Spacer(Modifier.height(8.dp))

        Text("Signed in: ${account ?: "—"}")
        Text("Events captured: $eventCount")
        Text("Last sync: ${formatLastSync(lastSync)}")
        Text("Last alive check: ${formatHeartbeat(lastHeartbeat)}")

        Spacer(Modifier.height(8.dp))

        Button(onClick = onGrantPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("Grant permissions")
        }
        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(if (account == null) "Sign in to Google Drive" else "Switch Google account")
        }
        Button(
            onClick = onSyncNow,
            modifier = Modifier.fillMaxWidth(),
            enabled = account != null,
        ) { Text("Sync now") }
        if (account != null) {
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text("Recent events", style = MaterialTheme.typography.titleMedium)

        if (recent.isEmpty()) {
            Text("None yet — pair a Bluetooth device and toggle it.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(recent) { event -> RecentEventRow(event) }
            }
        }
    }
}

@Composable
private fun SetupWarningBanner(
    issues: List<SetupIssue>,
    onFixBattery: () -> Unit,
    onFixPermission: () -> Unit,
) {
    if (issues.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Setup needs attention", style = MaterialTheme.typography.titleMedium)
            issues.forEach { issue ->
                when (issue) {
                    SetupIssue.NOT_BATTERY_EXEMPT -> {
                        Text("Battery optimisation is on — Android may stop the app capturing connections.")
                        Button(onClick = onFixBattery, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow background activity")
                        }
                    }
                    SetupIssue.MISSING_BLUETOOTH_CONNECT -> {
                        Text("Bluetooth permission is missing — connections won't be recorded.")
                        Button(onClick = onFixPermission, modifier = Modifier.fillMaxWidth()) {
                            Text("Grant Bluetooth permission")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentEventRow(event: BtEvent) {
    val time = recentEventTimeFormatter.format(Date(event.utcTimestamp))
    val verb = if (event.eventType == EventType.CONNECTED) "connected" else "disconnected"
    val name = event.deviceName ?: event.deviceMac
    Text("$time  $verb  $name", style = MaterialTheme.typography.bodySmall)
}

private const val RECENT_LIMIT = 10

private val recentEventTimeFormatter: DateFormat =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

private val timestampFormatter: DateFormat =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

private fun formatLastSync(millis: Long): String =
    if (millis == 0L) "never"
    else timestampFormatter.format(Date(millis))

private fun formatHeartbeat(event: BtEvent?): String {
    if (event == null) return "never"
    val time = timestampFormatter.format(Date(event.utcTimestamp))
    return "$time — ${event.deviceName ?: "OK"}"
}
