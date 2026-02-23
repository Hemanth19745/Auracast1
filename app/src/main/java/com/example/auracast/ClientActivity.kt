package com.example.auracast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.auracast.ui.theme.*
import java.util.UUID

// Re-defining AudioFile for Client side synchronization
data class ClientAudioFile(
    val uri: Uri,
    val name: String
)

data class DiscoveredHost(
    val device: BluetoothDevice,
    val isAuracast: Boolean,
    val rssi: Int,
    val scanResult: ScanResult? = null
)

class ClientActivity : ComponentActivity() {

    private val tag = "AuracastClient"
    private val serviceUuid = UUID.fromString("00001856-0000-1000-8000-00805F9B34FB")
    private val syncCharUuid = UUID.fromString("00002A3D-0000-1000-8000-00805F9B34FB")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }

    private var activeGatt: BluetoothGatt? = null
    private val discoveredHosts = mutableStateListOf<DiscoveredHost>()
    
    private var mediaPlayer: MediaPlayer? = null
    private var currentSongUri: Uri? = null
    private val localAudioFiles = mutableStateListOf<ClientAudioFile>()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == intent?.action) {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (bondState == BluetoothDevice.BOND_BONDED) {
                    device?.let { 
                        Toast.makeText(this@ClientActivity, "Paired with ${it.name}", Toast.LENGTH_SHORT).show()
                        connectToGatt(it)
                    }
                }
            }
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth is required for scanning", Toast.LENGTH_SHORT).show()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this@ClientActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(tag, "Connected to Host. Discovering services...")
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    Log.e(tag, "SecurityException on discoverServices", e)
                }
                runOnUiThread { Toast.makeText(this@ClientActivity, "Connected to Host", Toast.LENGTH_SHORT).show() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(tag, "Disconnected from Host.")
                runOnUiThread { Toast.makeText(this@ClientActivity, "Disconnected", Toast.LENGTH_SHORT).show() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this@ClientActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(serviceUuid)
                val characteristic = service?.getCharacteristic(syncCharUuid)
                if (characteristic != null) {
                    try {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(cccdUuid)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                            Log.d(tag, "Sync Notifications Enabled")
                        }
                    } catch (e: SecurityException) {
                        Log.e(tag, "SecurityException on enabling notifications", e)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value?.let { String(it) } ?: ""
            handleSyncUpdate(data)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleSyncUpdate(String(value))
        }
    }

    private fun handleSyncUpdate(data: String) {
        val parts = data.split("|")
        if (parts.size >= 3) {
            val isPlaying = parts[0].toBoolean()
            val position = parts[1].toInt()
            val songName = parts[2]
            
            runOnUiThread {
                performSync(isPlaying, position, songName)
            }
        }
    }

    private fun performSync(isPlaying: Boolean, position: Int, songName: String) {
        val targetSong = localAudioFiles.find { it.name == songName }
        if (targetSong != null) {
            if (mediaPlayer == null || currentSongUri != targetSong.uri) {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer.create(this, targetSong.uri)
                currentSongUri = targetSong.uri
            }

            mediaPlayer?.let { mp ->
                if (isPlaying) {
                    if (!mp.isPlaying) {
                        mp.seekTo(position)
                        mp.start()
                    } else if (Math.abs(mp.currentPosition - position) > 800) {
                        mp.seekTo(position)
                    }
                } else {
                    if (mp.isPlaying) mp.pause()
                    mp.seekTo(position)
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val device = scanResult.device
                val serviceUuids = scanResult.scanRecord?.serviceUuids ?: emptyList()
                val isAuracast = serviceUuids.contains(ParcelUuid(serviceUuid))
                
                if (discoveredHosts.none { it.device.address == device.address }) {
                    discoveredHosts.add(DiscoveredHost(device, isAuracast, scanResult.rssi, scanResult))
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "Scan failed with error: $errorCode")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> 
        if (results.values.all { it }) {
            fetchLocalFiles()
        } else {
            Toast.makeText(this, "Permissions required for scanning", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        permissionLauncher.launch(permissions.toTypedArray())

        setContent {
            AuracastTheme {
                ClientScreen(
                    isBluetoothEnabled = bluetoothAdapter?.isEnabled ?: false,
                    onEnableBluetooth = {
                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        bluetoothEnableLauncher.launch(enableBtIntent)
                    },
                    discoveredHosts = discoveredHosts,
                    startScan = ::startScan,
                    stopScan = ::stopScan,
                    onConnect = { handleJoin(it) }
                )
            }
        }
    }

    private fun fetchLocalFiles() {
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            localAudioFiles.clear()
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                localAudioFiles.add(ClientAudioFile(uri, cursor.getString(nameCol)))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleJoin(host: DiscoveredHost) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant Bluetooth Connect permission", Toast.LENGTH_SHORT).show()
            return
        }

        if (host.device.bondState == BluetoothDevice.BOND_BONDED) {
            connectToGatt(host.device)
        } else {
            Toast.makeText(this, "Pairing with host...", Toast.LENGTH_SHORT).show()
            try {
                host.device.createBond()
            } catch (e: SecurityException) {
                Log.e(tag, "SecurityException on createBond", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToGatt(device: BluetoothDevice) {
        activeGatt?.disconnect()
        activeGatt?.close()
        try {
            activeGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException on connectGatt", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant Bluetooth Scan permission", Toast.LENGTH_SHORT).show()
            return
        }

        discoveredHosts.clear()
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        
        try {
            scanner?.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {}
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(bluetoothReceiver) } catch (e: Exception) {}
        activeGatt?.close()
        mediaPlayer?.release()
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ClientScreen(
    isBluetoothEnabled: Boolean,
    onEnableBluetooth: () -> Unit,
    discoveredHosts: List<DiscoveredHost>,
    startScan: () -> Unit,
    stopScan: () -> Unit,
    onConnect: (DiscoveredHost) -> Unit
) {
    var isScanning by remember { mutableStateOf(false) }
    var showBluetoothDialog by remember { mutableStateOf(!isBluetoothEnabled) }
    
    val liquidColors = listOf(iOSLiquidCyan, iOSLiquidPurple, iOSLiquidPink, Color(0xFF5856D6))
    var colorSet by remember { mutableStateOf(liquidColors.shuffled().take(4)) }
    val animatedColors = colorSet.map { animateColorAsState(it, tween(2000), label = "") }

    val infiniteTransition = rememberInfiniteTransition(label = "")
    val offsetAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = ""
    )

    if (showBluetoothDialog) {
        AlertDialog(
            onDismissRequest = { /* Don't dismiss */ },
            title = { Text("Bluetooth Required") },
            text = { Text("Auracast requires Bluetooth to scan for and connect to nearby hosts. Please enable it to continue.") },
            confirmButton = {
                Button(onClick = { 
                    onEnableBluetooth()
                    showBluetoothDialog = false
                }) { Text("Enable Bluetooth") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().blur(80.dp)) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to animatedColors[0].value,
                    0.3f to animatedColors[1].value,
                    0.7f to animatedColors[2].value,
                    1.0f to animatedColors[3].value,
                    start = Offset(offsetAnim, offsetAnim),
                    end = Offset(canvasWidth - offsetAnim, canvasHeight - offsetAnim)
                )
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(40.dp))

            Surface(
                color = GlassWhite, 
                shape = RoundedCornerShape(25.dp), 
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("AURACAST CLIENT", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text("Scanning for hosts...", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Surface(
                color = GlassWhite,
                shape = RoundedCornerShape(25.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { if (isScanning) stopScan() else startScan(); isScanning = !isScanning },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isScanning) iOSLiquidPink else Color.White.copy(alpha = 0.3f)
                        )
                    ) {
                        Icon(
                            imageVector = if (isScanning) Icons.Default.BluetoothSearching else Icons.Default.Search,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isScanning) "STOP SCANNING" else "SCAN FOR HOSTS", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            Text(
                text = "AVAILABLE HOSTS (${discoveredHosts.size})", 
                style = MaterialTheme.typography.labelMedium, 
                color = Color.White, 
                modifier = Modifier.align(Alignment.Start).padding(start = 8.dp, bottom = 8.dp)
            )

            Surface(
                color = GlassWhite, 
                shape = RoundedCornerShape(25.dp), 
                modifier = Modifier.fillMaxWidth().weight(2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
            ) {
                if (discoveredHosts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = if (isScanning) "Searching for hosts..." else "No hosts found", color = Color.White.copy(alpha = 0.6f))
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(discoveredHosts) { host ->
                            Surface(
                                color = if (host.isAuracast) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(15.dp),
                                modifier = Modifier.fillMaxWidth().clickable { onConnect(host) }
                            ) {
                                ListItem(
                                    headlineContent = { Text(host.device.name ?: "Unknown Device", fontWeight = FontWeight.Bold, color = Color.White) },
                                    supportingContent = { Text(host.device.address, color = Color.White.copy(alpha = 0.6f)) },
                                    trailingContent = { 
                                        if (host.isAuracast) {
                                            Surface(color = iOSSystemBlue, shape = RoundedCornerShape(8.dp)) {
                                                Text(text = "JOIN", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Black)
                                            }
                                        } else {
                                            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color.White.copy(alpha = 0.3f))
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
