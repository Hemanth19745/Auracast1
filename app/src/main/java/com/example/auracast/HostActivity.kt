package com.example.auracast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.auracast.ui.theme.*
import java.util.UUID

data class AudioFile(
    val uri: Uri,
    val name: String,
    val artist: String,
    val duration: Int,
    val thumbnail: Bitmap? = null
)

class HostActivity : ComponentActivity() {

    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }
    private val bluetoothLeAdvertiser by lazy {
        bluetoothAdapter?.bluetoothLeAdvertiser
    }

    private val connectedClients = mutableStateListOf<BluetoothDevice>()
    private var mediaPlayer: MediaPlayer? = null
    private val audioFiles = mutableStateListOf<AudioFile>()
    private var currentSongName: String = ""

    private var gattServer: BluetoothGattServer? = null
    private val serviceUuid = UUID.fromString("00001856-0000-1000-8000-00805F9B34FB")
    private val syncCharUuid = UUID.fromString("00002A3D-0000-1000-8000-00805F9B34FB")
    private var syncCharacteristic: BluetoothGattCharacteristic? = null
    
    private var currentPositionState = mutableIntStateOf(0)
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            if (mediaPlayer?.isPlaying == true) {
                currentPositionState.intValue = mediaPlayer?.currentPosition ?: 0
                notifyPlaybackSync()
            }
            syncHandler.postDelayed(this, 1000)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == intent?.action) {
                val temp = connectedClients.toList()
                connectedClients.clear()
                connectedClients.addAll(temp)
            }
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    if (connectedClients.none { it.address == device.address }) {
                        connectedClients.add(device)
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    connectedClients.removeAll { it.address == device.address }
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this@HostActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return 
            }
            if (characteristic?.uuid == syncCharUuid) {
                val isPlaying = mediaPlayer?.isPlaying ?: false
                val pos = mediaPlayer?.currentPosition ?: 0
                val payload = "$isPlaying|$pos|$currentSongName"
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload.toByteArray())
                } catch (e: SecurityException) {
                    Log.e("HostActivity", "SecurityException on sending response", e)
                }
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this@HostActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (e: SecurityException) {
                    Log.e("HostActivity", "SecurityException on descriptor write", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        syncCharacteristic = BluetoothGattCharacteristic(
            syncCharUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        
        syncCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        
        service.addCharacteristic(syncCharacteristic)
        gattServer?.addService(service)
        syncHandler.post(syncRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun notifyPlaybackSync() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val isPlaying = mediaPlayer?.isPlaying ?: false
        val pos = mediaPlayer?.currentPosition ?: 0
        val payload = "$isPlaying|$pos|$currentSongName"
        syncCharacteristic?.let { char ->
            char.value = payload.toByteArray()
            connectedClients.forEach { device ->
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    try {
                        gattServer?.notifyCharacteristicChanged(device, char, false)
                    } catch (e: Exception) {
                        Log.e("HostActivity", "Failed to notify ${device.address}")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startGattServer()
        
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)

        setContent {
            AuracastTheme {
                HostScreen(
                    isBluetoothEnabled = bluetoothAdapter?.isEnabled ?: false,
                    onEnableBluetooth = {
                        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        bluetoothEnableLauncher.launch(enableBtIntent)
                    },
                    connectedClients = connectedClients,
                    audioFiles = audioFiles,
                    onAudioFilesRequested = { fetchAudioFiles() },
                    startAdvertising = ::startAdvertising,
                    stopAdvertising = ::stopAdvertising,
                    playAudio = { audio -> playAudio(audio) },
                    togglePlayback = ::togglePlayback,
                    currentPosition = currentPositionState.intValue,
                    onSeek = { pos -> mediaPlayer?.seekTo(pos); currentPositionState.intValue = pos; notifyPlaybackSync() }
                )
            }
        }
    }

    private fun fetchAudioFiles() {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION
        )

        contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            audioFiles.clear()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val duration = cursor.getInt(durationColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                
                var thumbnail: Bitmap? = null
                try {
                    thumbnail = contentResolver.loadThumbnail(contentUri, Size(300, 300), null)
                } catch (e: Exception) {}
                
                audioFiles.add(AudioFile(contentUri, name, artist, duration, thumbnail))
            }
        }
    }

    private fun playAudio(audio: AudioFile) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            currentSongName = audio.name
            mediaPlayer = MediaPlayer.create(this, audio.uri).apply {
                start()
                setOnCompletionListener { notifyPlaybackSync() }
            }
            currentPositionState.intValue = 0
            notifyPlaybackSync()
        } catch (e: Exception) {
            Log.e("HostActivity", "Error playing audio: ${e.message}")
        }
    }

    private fun togglePlayback(): Boolean {
        return try {
            val isPlaying: Boolean
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                isPlaying = false
            } else {
                mediaPlayer?.start()
                isPlaying = true
            }
            notifyPlaybackSync()
            isPlaying
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(bluetoothReceiver) } catch (e: Exception) {}
        syncHandler.removeCallbacks(syncRunnable)
        gattServer?.close()
        mediaPlayer?.release()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeTxPowerLevel(true)
            .build()

        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e("HostActivity", "Failed to start advertising: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {}
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("HostActivity", "Advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e("HostActivity", "Advertising failed: $errorCode")
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun HostScreen(
    isBluetoothEnabled: Boolean,
    onEnableBluetooth: () -> Unit,
    connectedClients: List<BluetoothDevice>,
    audioFiles: List<AudioFile>,
    onAudioFilesRequested: () -> Unit,
    startAdvertising: () -> Unit,
    stopAdvertising: () -> Unit,
    playAudio: (AudioFile) -> Unit,
    togglePlayback: () -> Boolean,
    currentPosition: Int,
    onSeek: (Int) -> Unit
) {
    var isBroadcasting by remember { mutableStateOf(false) }
    var currentSongIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var showBluetoothDialog by remember { mutableStateOf(!isBluetoothEnabled) }

    val liquidColors = listOf(iOSLiquidCyan, iOSLiquidPurple, iOSLiquidPink, Color(0xFF5856D6))
    var colorSet by remember { mutableStateOf(liquidColors.shuffled().take(4)) }
    val animatedColors = colorSet.map { animateColorAsState(it, tween(2000), label = "") }

    if (showBluetoothDialog) {
        AlertDialog(
            onDismissRequest = { /* Don't dismiss without action */ },
            title = { Text("Bluetooth Required") },
            text = { Text("Auracast requires Bluetooth to broadcast audio to nearby devices. Please enable it to continue.") },
            confirmButton = {
                Button(onClick = { 
                    onEnableBluetooth()
                    showBluetoothDialog = false
                }) { Text("Enable Bluetooth") }
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) onAudioFilesRequested()
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
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
                    start = Offset(0f, 0f),
                    end = Offset(canvasWidth, canvasHeight)
                )
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Surface(
                color = GlassWhite,
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier.fillMaxWidth().weight(1.3f),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Card(modifier = Modifier.size(160.dp).clip(RoundedCornerShape(24.dp)), elevation = CardDefaults.cardElevation(12.dp)) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (currentSongIndex != -1 && audioFiles[currentSongIndex].thumbnail != null) {
                                Image(bitmap = audioFiles[currentSongIndex].thumbnail!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (currentSongIndex != -1) audioFiles[currentSongIndex].name else "No Song Selected",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth()
                    )
                    Text(
                        text = if (currentSongIndex != -1) audioFiles[currentSongIndex].artist else "Unknown Artist",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    val totalDuration = if (currentSongIndex != -1) audioFiles[currentSongIndex].duration else 0
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Slider(
                            value = currentPosition.toFloat(),
                            onValueChange = { onSeek(it.toInt()) },
                            valueRange = 0f..totalDuration.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(currentPosition), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            Text(formatTime(totalDuration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        IconButton(
                            modifier = Modifier.size(48.dp),
                            onClick = { if (currentSongIndex > 0) { currentSongIndex--; playAudio(audioFiles[currentSongIndex]); isPlaying = true } }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        FilledIconButton(
                            onClick = { if (currentSongIndex != -1) isPlaying = togglePlayback() else if (audioFiles.isNotEmpty()) { currentSongIndex = 0; playAudio(audioFiles[0]); isPlaying = true } },
                            modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White)
                        ) {
                            Icon(imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = animatedColors[0].value, modifier = Modifier.size(32.dp))
                        }
                        IconButton(
                            modifier = Modifier.size(48.dp),
                            onClick = { if (currentSongIndex < audioFiles.size - 1) { currentSongIndex++; playAudio(audioFiles[currentSongIndex]); isPlaying = true } }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = GlassWhite,
                shape = RoundedCornerShape(25.dp),
                modifier = Modifier.fillMaxWidth().height(120.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
            ) {
                LazyRow(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(audioFiles) { index, audio ->
                        Column(modifier = Modifier.width(80.dp).clickable { currentSongIndex = index; playAudio(audio); isPlaying = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(if (currentSongIndex == index) Color.White.copy(alpha = 0.3f) else Color.Transparent)) {
                                if (audio.thumbnail != null) { Image(bitmap = audio.thumbnail.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                else { Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center)) }
                            }
                            Text(audio.name, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                onClick = { if (isBroadcasting) stopAdvertising() else startAdvertising(); isBroadcasting = !isBroadcasting },
                color = if (isBroadcasting) iOSLiquidPink else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (isBroadcasting) "STOP AURACAST" else "START AURACAST", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = GlassWhite,
                shape = RoundedCornerShape(25.dp),
                modifier = Modifier.fillMaxWidth().weight(0.7f),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("CONNECTED CLIENTS (${connectedClients.size})", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.2f))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(connectedClients) { device ->
                            val isPaired = device.bondState == BluetoothDevice.BOND_BONDED
                            ListItem(
                                headlineContent = { Text(device.name ?: "Client Device", color = Color.White, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text(device.address, color = Color.White.copy(alpha = 0.7f)) },
                                trailingContent = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (isPaired) "PAIRED" else "CONNECTING", color = if (isPaired) Color.Green else Color.Yellow, style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.Default.Link, tint = if (isPaired) Color.Green else Color.Gray, contentDescription = null)
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
