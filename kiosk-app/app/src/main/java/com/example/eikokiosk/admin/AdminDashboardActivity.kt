package com.example.eikokiosk.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eikokiosk.auth.PinManager
import com.example.eikokiosk.data.LocalDatabase
import com.example.eikokiosk.data.entity.ApprovedAppEntity
import com.example.eikokiosk.device.DeviceIdentityManager
import com.example.eikokiosk.device.KioskModeManager
import com.example.eikokiosk.theme.EikoKioskTheme

/**
 * Admin Dashboard — accessible only via Master PIN.
 *
 * Features:
 * - Set/change User PIN
 * - View approved apps and sync status
 * - View device UUID and metadata
 * - Exit/re-enter kiosk mode for maintenance
 */
class AdminDashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pinManager = PinManager(applicationContext)
        val kioskManager = KioskModeManager(applicationContext)
        val identityManager = DeviceIdentityManager(applicationContext)
        val db = LocalDatabase.getInstance(applicationContext)

        setContent {
            EikoKioskTheme {
                AdminDashboardScreen(
                    pinManager = pinManager,
                    kioskManager = kioskManager,
                    identityManager = identityManager,
                    db = db,
                    activity = this,
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    pinManager: PinManager,
    kioskManager: KioskModeManager,
    identityManager: DeviceIdentityManager,
    db: LocalDatabase,
    activity: ComponentActivity,
    onBack: () -> Unit
) {
    val accentColor = Color(0xFF4FC3F7)
    val bgColor = Color(0xFF0D1B2A)
    val cardColor = Color(0xFF1B2838)

    var newPin by remember { mutableStateOf("") }
    var pinMessage by remember { mutableStateOf<String?>(null) }
    var kioskModeActive by remember { mutableStateOf(kioskManager.isDeviceOwner()) }
    val approvedApps by db.approvedAppDao().getActiveApps().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Admin Dashboard", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bgColor,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = bgColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device Info Section
            item {
                SectionTitle("Device Information")
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        InfoRow("UUID", identityManager.getDeviceId())
                        InfoRow("Model", identityManager.getDeviceModel())
                        InfoRow("Android", identityManager.getAndroidVersion())
                        InfoRow("Kiosk Version", identityManager.getKioskAppVersion())
                        InfoRow("Device Owner", if (kioskManager.isDeviceOwner()) "✅ Yes" else "❌ No")
                    }
                }
            }

            // PIN Management Section
            item {
                SectionTitle("User PIN Management")
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (pinManager.isUserPinSet()) "User PIN is set" else "No User PIN configured",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = newPin,
                            onValueChange = {
                                if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                                    newPin = it
                                    pinMessage = null
                                }
                            },
                            label = { Text("New User PIN (4-6 digits)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                focusedLabelColor = accentColor,
                                unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (newPin.length in 4..6) {
                                    pinManager.setUserPin(newPin)
                                    pinMessage = "User PIN updated ✓"
                                    newPin = ""
                                } else {
                                    pinMessage = "PIN must be 4-6 digits"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Set User PIN", color = Color(0xFF0D1B2A))
                        }
                        if (pinMessage != null) {
                            Text(
                                pinMessage!!,
                                color = if (pinMessage!!.contains("✓")) Color(0xFF81C784) else Color(0xFFFF6B6B),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Approved Apps Section
            item {
                SectionTitle("Approved Apps (${approvedApps.size})")
            }

            if (approvedApps.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No approved apps synced yet.\nApps will appear after the next Firebase sync.",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            } else {
                items(approvedApps) { app ->
                    ApprovedAppCard(app, cardColor)
                }
            }

            // Kiosk Mode Controls
            item {
                SectionTitle("Kiosk Mode")
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (kioskModeActive) {
                            Button(
                                onClick = {
                                    kioskManager.exitKioskMode(activity)
                                    kioskModeActive = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.LockOpen, "Exit Kiosk", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Exit Kiosk Mode (Maintenance)", color = Color.White)
                            }
                        } else {
                            Button(
                                onClick = {
                                    kioskManager.enterKioskMode(activity)
                                    kioskModeActive = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81C784)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Lock, "Enter Kiosk", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Re-enter Kiosk Mode", color = Color(0xFF0D1B2A))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = Color(0xFF4FC3F7),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ApprovedAppCard(app: ApprovedAppEntity, cardColor: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Apps,
                contentDescription = null,
                tint = Color(0xFF4FC3F7),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.appName, color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    "${app.packageName} • v${app.versionName} (${app.versionCode})",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }
            if (app.isActive) {
                Icon(Icons.Default.CheckCircle, "Active", tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
            }
        }
    }
}
