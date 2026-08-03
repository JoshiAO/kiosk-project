package com.example.eikokiosk.launcher

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.example.eikokiosk.auth.ActivationManager
import com.example.eikokiosk.auth.ActivationManager.ActivationState
import com.example.eikokiosk.data.LocalDatabase
import com.example.eikokiosk.data.entity.ApprovedAppEntity
import com.example.eikokiosk.device.DeviceIdentityManager
import com.example.eikokiosk.device.KioskDeviceAdminReceiver
import com.example.eikokiosk.device.KioskModeManager
import com.example.eikokiosk.installer.SilentInstaller
import com.example.eikokiosk.sync.DeviceStatusWorker
import com.example.eikokiosk.sync.FirebaseSyncWorker
import com.example.eikokiosk.theme.EikoKioskTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = LocalDatabase.getInstance(applicationContext)
        val kioskManager = KioskModeManager(applicationContext)
        val identityManager = DeviceIdentityManager(applicationContext)
        val activationManager = ActivationManager(applicationContext)

        // Grant READ_PHONE_STATE to ourselves if we are Device Owner
        if (kioskManager.isDeviceOwner()) {
            try {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = KioskDeviceAdminReceiver.getComponentName(applicationContext)
                dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.READ_PHONE_STATE,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            EikoKioskTheme {
                MainAppFlow(
                    db = db,
                    identityManager = identityManager,
                    kioskManager = kioskManager,
                    activationManager = activationManager
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing — this IS the home screen
    }
}

enum class AppState {
    CHECKING_ACTIVATION,
    ACTIVATION_LOCKED,
    LOGIN,
    KIOSK
}

@Composable
fun MainAppFlow(
    db: LocalDatabase,
    identityManager: DeviceIdentityManager,
    kioskManager: KioskModeManager,
    activationManager: ActivationManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appState by remember { mutableStateOf(AppState.CHECKING_ACTIVATION) }
    
    LaunchedEffect(Unit) {
        val state = activationManager.getActivationState()
        if (state == ActivationState.LOCKED_DOWN) {
            // Force online check
            val success = activationManager.checkActivationOnline()
            if (success) {
                appState = checkAuthNextState()
            } else {
                appState = AppState.ACTIVATION_LOCKED
            }
        } else if (state == ActivationState.REQUIRES_CHECK) {
            // Silent check in background, but allow in anyway
            scope.launch { activationManager.checkActivationOnline() }
            appState = checkAuthNextState()
        } else {
            // Activated
            appState = checkAuthNextState()
        }
    }

    when (appState) {
        AppState.CHECKING_ACTIVATION -> {
            LoadingScreen("Verifying license activation...")
        }
        AppState.ACTIVATION_LOCKED -> {
            ActivationLockedScreen(
                onRetry = { activationCode ->
                    appState = AppState.CHECKING_ACTIVATION
                    scope.launch {
                        val success = activationManager.checkActivationOnline(activationCode)
                        if (success) {
                            appState = checkAuthNextState()
                        } else {
                            appState = AppState.ACTIVATION_LOCKED
                        }
                    }
                },
                onExitKiosk = {
                    kioskManager.exitKioskMode(context as ComponentActivity)
                    context.finish()
                }
            )
        }
        AppState.LOGIN -> {
            LoginScreen(onLoginSuccess = {
                appState = AppState.KIOSK
            })
        }
        AppState.KIOSK -> {
            // Start workers and kiosk mode only when fully authorized and logged in
            LaunchedEffect(Unit) {
                if (kioskManager.isDeviceOwner()) {
                    kioskManager.enterKioskMode(context as ComponentActivity)
                }
                DeviceStatusWorker.enqueueHeartbeat(context)
                FirebaseSyncWorker.enqueue(context)
                DeviceStatusWorker.sendEvent(context, "APP_START", "Kiosk Started")
            }
            LauncherScreen(db = db, identityManager = identityManager)
        }
    }
}

private fun checkAuthNextState(): AppState {
    return if (FirebaseAuth.getInstance().currentUser != null) {
        AppState.KIOSK
    } else {
        AppState.LOGIN
    }
}

@Composable
fun LoadingScreen(message: String) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1B2A)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFF00D2FF))
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = Color.White)
        }
    }
}

@Composable
fun ActivationLockedScreen(onRetry: (String) -> Unit, onExitKiosk: () -> Unit) {
    var activationCode by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1B2A)), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(400.dp).padding(32.dp).background(Color(0xFF1B2838), RoundedCornerShape(16.dp)).padding(32.dp)
        ) {
            Text("Device Locked", color = Color.Red, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "This device's license activation has expired or failed validation.\n" +
                "Please enter your Activation Code to verify.",
                color = Color.White, textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = activationCode,
                onValueChange = { activationCode = it },
                label = { Text("Activation Code", color = Color.Gray) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00D2FF)
                )
            )
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { onRetry(activationCode) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF)),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = activationCode.isNotBlank()
            ) {
                Text("Activate Device", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onExitKiosk, 
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Exit Kiosk Mode", color = Color.White)
            }
        }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1B2A)), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(400.dp)
                .background(Color(0xFF1B2838), RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Text("Device Authentication", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Please login with the device's credentials.", color = Color.Gray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email", color = Color.Gray) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00D2FF)
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password", color = Color.Gray) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00D2FF)
                )
            )

            errorMessage?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Text(it, color = Color.Red, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    if (email.isBlank() || password.isBlank()) {
                        errorMessage = "Please enter email and password"
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
                            withContext(Dispatchers.Main) {
                                isLoading = false
                                onLoginSuccess()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                isLoading = false
                                errorMessage = e.message ?: "Authentication failed"
                            }
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                } else {
                    Text("Login Device", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==========================================
// THE REST IS THE KIOSK LAUNCHER ITSELF
// ==========================================

data class LauncherAppItem(
    val appName: String,
    val packageName: String,
    val icon: Drawable?,
    val remoteConfig: String?
)

@Composable
fun LauncherScreen(
    db: LocalDatabase,
    identityManager: DeviceIdentityManager
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    val approvedApps by db.approvedAppDao().getActiveApps().collectAsState(initial = emptyList())
    
    val wallpaperUrl = remember { mutableStateOf<String?>(null) }
    val deviceName = remember { mutableStateOf<String?>(null) }
    val firestore = FirebaseFirestore.getInstance()
    
    var showSettingsDialog by remember { mutableStateOf(false) }
    var authRequestPending by remember { mutableStateOf(false) }
    var advancedUnlocked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val deviceId = identityManager.getDeviceId()
        firestore.collection("devices").document(deviceId).addSnapshotListener { snapshot, _ ->
            if (snapshot != null && snapshot.exists()) {
                wallpaperUrl.value = snapshot.getString("wallpaperUrl")
                deviceName.value = snapshot.getString("deviceName")
                authRequestPending = snapshot.getBoolean("authRequestPending") ?: false
                advancedUnlocked = snapshot.getBoolean("advancedUnlocked") ?: false
            }
        }

        firestore.collection("approved_apps").addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            if (snapshot != null) {
                scope.launch(Dispatchers.IO) {
                    val remoteApps = snapshot.documents.mapNotNull { doc ->
                        try {
                            ApprovedAppEntity(
                                id = doc.id,
                                appName = doc.getString("appName") ?: return@mapNotNull null,
                                packageName = doc.getString("packageName") ?: return@mapNotNull null,
                                versionCode = (doc.getLong("versionCode") ?: 0).toInt(),
                                versionName = doc.getString("versionName") ?: "0.0.0",
                                apkUrl = doc.getString("apkUrl") ?: "",
                                remoteConfig = doc.get("remoteConfig")?.toString(),
                                isActive = doc.getBoolean("isActive") ?: true,
                                updatedAt = doc.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
                            )
                        } catch (e: Exception) { null }
                    }

                    val localApps = db.approvedAppDao().getActiveAppsList()
                    val localMap = localApps.associateBy { it.id }

                    db.approvedAppDao().insertAll(remoteApps)
                    db.approvedAppDao().deleteRemovedApps(remoteApps.map { it.id })

                    for (app in remoteApps) {
                        if (!app.isActive) continue
                        val local = localMap[app.id]
                        if (local == null || local.versionCode < app.versionCode) {
                            if (app.apkUrl.isNotEmpty()) {
                                SilentInstaller(context).downloadAndInstall(app.packageName, app.versionCode, app.apkUrl)
                            }
                        }
                    }
                    
                    val allowedPackages = remoteApps.map { it.packageName }.toMutableList()
                    val prefs = context.getSharedPreferences("KioskSystemApps", Context.MODE_PRIVATE)
                    val enabledApps = prefs.getStringSet("enabledApps", setOf()) ?: emptySet()
                    allowedPackages.addAll(enabledApps)
                    // Keep essential settings packages
                    allowedPackages.addAll(listOf(
                        "com.android.settings", "com.samsung.android.settings"
                    ))
                    KioskModeManager(context).setAllowedLockTaskPackages(allowedPackages)
                }
            }
        }
    }

    val launcherApps = remember(approvedApps) {
        approvedApps.mapNotNull { app ->
            try {
                pm.getPackageInfo(app.packageName, 0)
                val appInfo = pm.getApplicationInfo(app.packageName, 0)
                LauncherAppItem(
                    appName = app.appName,
                    packageName = app.packageName,
                    icon = pm.getApplicationIcon(appInfo),
                    remoteConfig = app.remoteConfig
                )
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (wallpaperUrl.value != null && wallpaperUrl.value!!.isNotEmpty()) {
            AsyncImage(
                model = wallpaperUrl.value,
                contentDescription = "Wallpaper",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        } else {
            val gradientBrush = Brush.verticalGradient(
                colors = listOf(Color(0xFF0D1B2A), Color(0xFF1B2838), Color(0xFF0D1B2A))
            )
            Box(modifier = Modifier.fillMaxSize().background(gradientBrush))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            CustomStatusBar()

            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(deviceName.value?.takeIf { it.isNotBlank() } ?: "Eiko Kiosk", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(identityManager.getDeviceModel(), color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    if (launcherApps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No apps available", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp)
                                Text("Apps will appear once synced from Firebase", color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp)
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            items(launcherApps) { app ->
                                AppGridItem(app = app, onClick = {
                                    val intent = pm.getLaunchIntentForPackage(app.packageName)
                                    if (intent != null) {
                                        app.remoteConfig?.let { config -> intent.putExtra("kiosk_remote_config", config) }
                                        context.startActivity(intent)
                                    }
                                })
                            }
                        }
                    }
                }
            }

            // Bottom App Tray
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(vertical = 12.dp, horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .clickable { showSettingsDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    UtilityMenu()
                }
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            onDismiss = { showSettingsDialog = false },
            firestore = firestore,
            deviceId = identityManager.getDeviceId(),
            authRequestPending = authRequestPending,
            advancedUnlocked = advancedUnlocked
        )
    }
}

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    firestore: FirebaseFirestore,
    deviceId: String,
    authRequestPending: Boolean,
    advancedUnlocked: Boolean
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    
    val prefs = context.getSharedPreferences("KioskSystemApps", Context.MODE_PRIVATE)
    val pm = context.packageManager
    val allApps = remember {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).map { it.activityInfo }.sortedBy { it.loadLabel(pm).toString() }
    }
    
    // Load enabled packages from SharedPreferences
    val enabledApps = remember { 
        val set = prefs.getStringSet("enabledApps", setOf()) ?: setOf()
        mutableStateListOf(*set.toTypedArray())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Device Settings", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { selectedTab = 0 },
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 0) Color(0xFF00D2FF) else Color.DarkGray)
                    ) { Text("Basic", color = Color.White) }
                    Button(
                        onClick = { selectedTab = 1 },
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == 1) Color(0xFF00D2FF) else Color.DarkGray)
                    ) { Text("Advanced", color = Color.White) }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(top = 16.dp)) {
                if (selectedTab == 0) {
                    Button(
                        onClick = { com.example.eikokiosk.sync.FirebaseSyncWorker.syncNow(context) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF).copy(alpha = 0.8f))
                    ) { Text("Sync Apps from Cloud", color = Color.Black, fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = { context.startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI)) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                    ) { Text("Wi-Fi Settings", color = Color.White) }
                    
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                    ) { Text("Bluetooth Settings", color = Color.White) }
                } else {
                    if (advancedUnlocked) {
                        Text("Select Utility Apps to display in the menu:", color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                        
                        val scrollState = androidx.compose.foundation.rememberScrollState()
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp).verticalScroll(scrollState)
                        ) {
                            allApps.forEach { appInfo ->
                                val isChecked = enabledApps.contains(appInfo.packageName)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Checkbox(
                                        checked = isChecked, 
                                        onCheckedChange = { checked -> 
                                            if (checked) enabledApps.add(appInfo.packageName) else enabledApps.remove(appInfo.packageName)
                                            prefs.edit().putStringSet("enabledApps", enabledApps.toSet()).apply()
                                            
                                            // Instantly update LockTask packages so they aren't blocked from launching
                                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                                val localDb = LocalDatabase.getInstance(context)
                                                val remoteAppPkgs = localDb.approvedAppDao().getActiveAppsList().map { it.packageName }
                                                val allAllowed = remoteAppPkgs + enabledApps.toSet() + listOf("com.android.settings", "com.samsung.android.settings")
                                                KioskModeManager(context).setAllowedLockTaskPackages(allAllowed.toList())
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(appInfo.loadLabel(pm).toString(), color = Color.White)
                                }
                            }
                        }

                        Button(
                            onClick = {
                                KioskModeManager(context).exitKioskMode(context as androidx.activity.ComponentActivity)
                                (context as androidx.activity.ComponentActivity).finish()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Text("Exit Kiosk", color = Color.White)
                        }

                        Button(
                            onClick = {
                                FirebaseAuth.getInstance().signOut()
                                ActivationManager(context).forceClearActivation()
                                (context as ComponentActivity).recreate()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Logout & Reset License", color = Color.White)
                        }
                    } else if (authRequestPending) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(color = Color(0xFF00D2FF), modifier = Modifier.padding(16.dp))
                            Text("Waiting for Admin approval from Dashboard...", color = Color.White, textAlign = TextAlign.Center)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text("Advanced settings require Admin authorization.", color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
                            Button(
                                onClick = {
                                    firestore.collection("devices").document(deviceId)
                                        .set(mapOf("authRequestPending" to true), SetOptions.merge())
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                            ) { Text("Request Access", color = Color.Black) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF00D2FF)) }
        },
        containerColor = Color(0xFF1B2838)
    )
}

@Composable
fun UtilityMenu() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    
    val prefs = context.getSharedPreferences("KioskSystemApps", Context.MODE_PRIVATE)
    val pm = context.packageManager
    
    // Dynamic list of packages
    var currentEnabledApps by remember { mutableStateOf<Set<String>>(emptySet()) }

    Box {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .clickable { 
                    currentEnabledApps = prefs.getStringSet("enabledApps", setOf()) ?: emptySet()
                    expanded = true 
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Apps", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (expanded) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { expanded = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable { expanded = false },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .fillMaxHeight(0.6f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1B2838).copy(alpha = 0.95f))
                        .padding(24.dp)
                        .clickable(enabled = false) {} // block clicks from closing modal
                ) {
                    if (currentEnabledApps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No utility apps enabled", color = Color.Gray, fontSize = 18.sp)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            items(currentEnabledApps.toList()) { pkg ->
                                val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                                if (appInfo != null) {
                                    val label = pm.getApplicationLabel(appInfo).toString()
                                    val icon = pm.getApplicationIcon(appInfo)
                                    val bitmap = remember(icon) { icon.toBitmap(96, 96).asImageBitmap() }
                                    
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                expanded = false
                                                try {
                                                    val intent = pm.getLaunchIntentForPackage(pkg)
                                                    if (intent != null) {
                                                        context.startActivity(intent)
                                                    }
                                                } catch (e: Exception) {}
                                            }
                                            .padding(8.dp)
                                    ) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = label,
                                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = label,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomStatusBar() {
    val time = remember { mutableStateOf(getCurrentTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            time.value = getCurrentTime()
            kotlinx.coroutines.delay(1000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time.value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("4G", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AppGridItem(app: LauncherAppItem, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        app.icon?.let { drawable ->
            val bitmap = remember(drawable) { drawable.toBitmap(96, 96) }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.appName,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = app.appName,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
    }
}

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
