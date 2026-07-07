package com.blink.dtn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blink.dtn.ble.BleMeshManager
import com.blink.dtn.db.BLinkDatabase
import com.blink.dtn.ui.BLinkViewModel
import com.blink.dtn.ui.BLinkViewModelFactory
import com.blink.dtn.ui.MainScreen
import com.blink.dtn.ui.theme.BLinkTheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        com.blink.dtn.utils.AppForegroundState.isForeground = true
    }

    override fun onPause() {
        super.onPause()
        com.blink.dtn.utils.AppForegroundState.isForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Retrieve or generate unique ID and Nickname from SharedPreferences
        val prefs = getSharedPreferences("blink_prefs", Context.MODE_PRIVATE)
        var myNodeId = prefs.getString("node_id", null)
        if (myNodeId == null) {
            myNodeId = UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString("node_id", myNodeId).apply()
        }
        
        var myNick = prefs.getString("nick", null)
        if (myNick == null) {
            myNick = "User-$myNodeId"
            prefs.edit().putString("nick", myNick).apply()
        }
        
        val dao = BLinkDatabase.getDatabase(this).bLinkDao()
        val conversationDao = BLinkDatabase.getDatabase(this).conversationDao()
        val bleManager = BleMeshManager.getInstance(this, dao, myNodeId)
        val factory = BLinkViewModelFactory(application, dao, conversationDao, bleManager, myNodeId, myNick)

        setContent {
            BLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BLinkApp(bleManager, factory)
                }
            }
        }
    }
}

@Composable
fun BLinkApp(bleManager: BleMeshManager, factory: BLinkViewModelFactory) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }
    
    val mandatoryBlePermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    val optionalNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else emptyList()

    val allRequired = (mandatoryBlePermissions + optionalNotificationPermission).toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val mandatoryGranted = mandatoryBlePermissions.all { permissionsMap[it] == true }
        if (mandatoryGranted) {
            permissionsGranted = true
            val serviceIntent = Intent(context, com.blink.dtn.service.BLinkMeshService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    LaunchedEffect(Unit) {
        val alreadyGranted = mandatoryBlePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) {
            permissionsGranted = true
            val serviceIntent = Intent(context, com.blink.dtn.service.BLinkMeshService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            permissionLauncher.launch(allRequired)
        }
    }

    if (permissionsGranted) {
        val viewModel: BLinkViewModel = viewModel(factory = factory)
        MainScreen(viewModel)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Bluetooth & Location permissions are required for Mesh.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(allRequired) }) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}
