package com.example.auracast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.auracast.ui.theme.*
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.isImmediateUpdateAllowed

class MainActivity : ComponentActivity() {
    private lateinit var appUpdateManager: AppUpdateManager
    private val updateActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "Update failed or cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForUpdates(silent = true)
        
        setContent {
            AuracastTheme {
                MainScreen(onCheckUpdate = { checkForUpdates(silent = false) })
            }
        }
    }

    private fun checkForUpdates(silent: Boolean) {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                info.isImmediateUpdateAllowed
            ) {
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    updateActivityResultLauncher,
                    com.google.android.play.core.appupdate.AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE)
                )
            } else if (!silent) {
                Toast.makeText(this, "App is up to date", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun MainScreen(onCheckUpdate: () -> Unit) {
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(iOSLiquidPurple, iOSLiquidCyan)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = GlassWhite,
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "AURACAST",
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Advanced Audio Sharing",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            GlassButton(
                text = "HOST BROADCAST",
                onClick = { context.startActivity(Intent(context, HostActivity::class.java)) }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            GlassButton(
                text = "CLIENT RECEIVER",
                onClick = { context.startActivity(Intent(context, ClientActivity::class.java)) }
            )

            Spacer(modifier = Modifier.height(48.dp))
            
            TextButton(onClick = onCheckUpdate) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("CHECK FOR UPDATES", color = Color.White)
            }
        }
    }
}

@Composable
fun GlassButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = GlassWhite,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassWhiteBorder)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
