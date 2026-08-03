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
import androidx.compose.material.icons.filled.Check
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
                
                val logRequestPending = snapshot.getBoolean("logRequestPending") ?: false
                if (logRequestPending) {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val process = Runtime.getRuntime().exec("logcat -d -v time -t 100 SilentInstaller:I KioskModeManager:I FirebaseSyncWorker:I *:S")
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                            val logs = mutableListOf<String>()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                logs.add(line ?: "")
                            }
                            firestore.collection("devices").document(deviceId).update(
                                mapOf(
                                    "activityLog" to logs.reversed(),
                                    "logRequestPending" to false
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("LauncherActivity", "Failed to fetch remote logs", e)
                            firestore.collection("devices").document(deviceId).update("logRequestPending", false)
                        }
                    }
                }
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
                                apkUrl = doc.getString("downloadUrl") ?: "",
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
                        val isActuallyInstalled = try {
                            context.packageManager.getPackageInfo(app.packageName, 0)
                            true
                        } catch (_: Exception) { false }

                        if (local == null || local.versionCode < app.versionCode || !isActuallyInstalled) {
                            if (app.apkUrl.isNotEmpty()) {
                                SilentInstaller(context).downloadAndInstall(app.packageName, app.versionCode, app.apkUrl)
                            }
                        }
                    }
                    
                    val allowedPackages = remoteApps.map { it.packageName }.toMutableList()
                    val prefs = context.getSharedPreferences("KioskSystemApps", Context.MODE_PRIVATE)
                    val enabledApps = prefs.getStringSet("enabledApps", setOf()) ?: emptySet()
                    val manualHomeApps = prefs.getStringSet("manualHomeApps", setOf()) ?: emptySet()
                    allowedPackages.addAll(enabledApps)
                    allowedPackages.addAll(manualHomeApps)
                    // Keep essential settings and play store packages
                    allowedPackages.addAll(listOf(
                        "com.android.settings", "com.samsung.android.settings",
                        "com.android.vending", "com.google.android.gms"
                    ))
                    KioskModeManager(context).setAllowedLockTaskPackages(allowedPackages)
                }
            }
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    
    DisposableEffect(lifecycleOwner, approvedApps) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                approvedApps.forEach { app ->
                    if (app.packageName != context.packageName) {
                        try { am.killBackgroundProcesses(app.packageName) } catch (e: Exception) {}
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var installTick by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                installTick++
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val prefs = context.getSharedPreferences("KioskSystemApps", Context.MODE_PRIVATE)
    val manualHomeApps = remember(showSettingsDialog, installTick) {
        prefs.getStringSet("manualHomeApps", setOf()) ?: emptySet()
    }

    val launcherApps = remember(approvedApps, installTick, manualHomeApps) {
        val apps = mutableListOf<LauncherAppItem>()
        
        // Add remote approved apps
        apps.addAll(approvedApps.mapNotNull { app ->
            try {
                pm.getPackageInfo(app.packageName, 0)
                val appInfo = pm.getApplicationInfo(app.packageName, 0)
                LauncherAppItem(
                    appName = app.appName,
                    packageName = app.packageName,
                    icon = pm.getApplicationIcon(appInfo),
                    remoteConfig = app.remoteConfig
                )
            } catch (_: PackageManager.NameNotFoundException) { null }
        })

        // Add manual home apps
        apps.addAll(manualHomeApps.mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                LauncherAppItem(
                    appName = appInfo.loadLabel(pm).toString(),
                    packageName = pkg,
                    icon = pm.getApplicationIcon(appInfo),
                    remoteConfig = null
                )
            } catch (_: PackageManager.NameNotFoundException) { null }
        })
        
        apps.distinctBy { it.packageName }
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
    var showUtilityAppPicker by remember { mutableStateOf(false) }
    var showHomeAppPicker by remember { mutableStateOf(false) }
    
    val prefs = context.getSharedPreferences("KioskSystemApps", Context.MODE_PRIVATE)
    val pm = context.packageManager
    
    // Load enabled packages from SharedPreferences
    val enabledApps = remember { 
        val set = prefs.getStringSet("enabledApps", setOf()) ?: setOf()
        mutableStateListOf(*set.toTypedArray())
    }

    // Load manual home apps from SharedPreferences
    val manualHomeApps = remember {
        val set = prefs.getStringSet("manualHomeApps", setOf()) ?: setOf()
        mutableStateListOf(*set.toTypedArray())
    }

    // Activity log from logcat
    val activityLog = remember { mutableStateListOf<String>() }
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && advancedUnlocked) {
            withContext(Dispatchers.IO) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "SilentInstaller:I", "FirebaseSyncWorker:I", "KioskModeManager:I"))
                    val lines = process.inputStream.bufferedReader().readLines()
                        .filter { it.contains("SilentInstaller") || it.contains("FirebaseSyncWorker") || it.contains("KioskModeManager") }
                        .takeLast(50)
                    withContext(Dispatchers.Main) {
                        activityLog.clear()
                        activityLog.addAll(lines)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // Show Utility App Picker as separate Dialog
    if (showUtilityAppPicker) {
        val allApps = remember {
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).map { it.activityInfo }.sortedBy { it.loadLabel(pm).toString() }
        }
        
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showUtilityAppPicker = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable { showUtilityAppPicker = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.8f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1B2838))
                        .clickable(enabled = false) {}
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Select Utility Apps", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("${enabledApps.size} selected", color = Color(0xFF00D2FF), fontSize = 14.sp)
                    }
                    
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(allApps.size) { index ->
                            val appInfo = allApps[index]
                            val isSelected = enabledApps.contains(appInfo.packageName)
                            val label = appInfo.loadLabel(pm).toString()
                            val icon = appInfo.loadIcon(pm)
                            val bitmap = remember(icon) { icon.toBitmap(96, 96).asImageBitmap() }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF00D2FF).copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        if (isSelected) enabledApps.remove(appInfo.packageName)
                                        else enabledApps.add(appInfo.packageName)
                                        prefs.edit().putStringSet("enabledApps", enabledApps.toSet()).apply()
                                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                            val localDb = LocalDatabase.getInstance(context)
                                            val remoteAppPkgs = localDb.approvedAppDao().getActiveAppsList().map { it.packageName }
                                            val allAllowed = remoteAppPkgs + enabledApps.toSet() + manualHomeApps.toSet() + listOf("com.android.settings", "com.samsung.android.settings", "com.android.vending", "com.google.android.gms")
                                            KioskModeManager(context).setAllowedLockTaskPackages(allAllowed.toList())
                                        }
                                    }
                                    .padding(8.dp)
                            ) {
                                Box {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = label,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                    )
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .align(Alignment.TopEnd)
                                                .clip(CircleShape)
                                                .background(Color(0xFF00D2FF)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("✓", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = label,
                                    color = if (isSelected) Color(0xFF00D2FF) else Color.White.copy(alpha = 0.7f),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { showUtilityAppPicker = false },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                    ) { Text("Done", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Device Settings", color = Color.White, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Basic" to 0, "Advanced" to 1).forEach { (label, index) ->
                        Button(
                            onClick = { selectedTab = index },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedTab == index) Color(0xFF00D2FF) else Color.White.copy(alpha = 0.08f)
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text(label, color = if (selectedTab == index) Color.Black else Color.White, fontSize = 13.sp) }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp).padding(top = 16.dp)) {
                if (selectedTab == 0) {
                    val clockPrefs = context.getSharedPreferences("KioskSettings", Context.MODE_PRIVATE)
                    var use24h by remember { mutableStateOf(clockPrefs.getBoolean("use24h", true)) }

                    // Sync Button
                    Button(
                        onClick = { 
                            android.widget.Toast.makeText(context, "Syncing apps from cloud...", android.widget.Toast.LENGTH_SHORT).show()
                            com.example.eikokiosk.sync.FirebaseSyncWorker.syncNow(context) 
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("⟳  Sync Apps from Cloud", color = Color.Black, fontWeight = FontWeight.Bold) }

                    // Clock Format
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Clock Format", color = Color.White, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("24h" to true, "12h" to false).forEach { (label, is24) ->
                                Button(
                                    onClick = { use24h = is24; clockPrefs.edit().putBoolean("use24h", is24).apply() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (use24h == is24) Color(0xFF00D2FF) else Color.White.copy(alpha = 0.1f)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Text(label, color = if (use24h == is24) Color.Black else Color.White, fontSize = 12.sp) }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // System Settings
                    listOf(
                        "Wi-Fi Settings" to { context.startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI)) },
                        "Bluetooth Settings" to { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    ).forEach { (label, action) ->
                        Button(
                            onClick = action,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.06f)),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(label, color = Color.White) }
                    }
                } else {
                    if (advancedUnlocked) {
                        val scrollState = androidx.compose.foundation.rememberScrollState()
                        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState)) {
                            // Home Screen Apps Button
                            Button(
                                onClick = { showHomeAppPicker = true },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.06f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Manage Home Screen Apps", color = Color.White)
                                    Text("${manualHomeApps.size} apps", color = Color(0xFF00D2FF), fontSize = 12.sp)
                                }
                            }

                            // Utility Apps Button
                            Button(
                                onClick = { showUtilityAppPicker = true },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.06f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Manage Utility Apps", color = Color.White)
                                    Text("${enabledApps.size} apps", color = Color(0xFF00D2FF), fontSize = 12.sp)
                                }
                            }

                            // Activity Log
                            Text("Activity Log", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .padding(8.dp)
                            ) {
                                val logScrollState = androidx.compose.foundation.rememberScrollState()
                                Column(modifier = Modifier.fillMaxSize().verticalScroll(logScrollState)) {
                                    if (activityLog.isEmpty()) {
                                        Text("No activity logs available", color = Color.Gray, fontSize = 11.sp)
                                    } else {
                                        activityLog.forEach { line ->
                                            Text(
                                                text = line.substringAfter("I ").substringAfter("E ").substringAfter("W "),
                                                color = when {
                                                    line.contains("FAILED") || line.contains("Error") -> Color.Red.copy(alpha = 0.9f)
                                                    line.contains("SUCCESS") || line.contains("CONFIRMED") -> Color(0xFF4CAF50)
                                                    else -> Color.White.copy(alpha = 0.6f)
                                                },
                                                fontSize = 10.sp,
                                                lineHeight = 14.sp,
                                                modifier = Modifier.padding(bottom = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Export Log Button
                            Button(
                                onClick = {
                                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val logDir = java.io.File(context.getExternalFilesDir(null), "logs")
                                            logDir.mkdirs()
                                            val file = java.io.File(logDir, "kiosk_log_${System.currentTimeMillis()}.txt")
                                            file.writeText(activityLog.joinToString("\n"))
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "Log exported to: ${file.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.06f)),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Export Log as TXT", color = Color.White) }

                            // Danger Zone
                            Text("Danger Zone", color = Color.Red.copy(alpha = 0.7f), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

                            Button(
                                onClick = {
                                    KioskModeManager(context).exitKioskMode(context as ComponentActivity)
                                    (context as ComponentActivity).finish()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.06f)),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Exit Kiosk", color = Color.White) }

                            Button(
                                onClick = {
                                    FirebaseAuth.getInstance().signOut()
                                    ActivationManager(context).forceClearActivation()
                                    (context as ComponentActivity).recreate()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Logout & Reset License", color = Color.Red) }
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF)),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Request Access", color = Color.Black) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF00D2FF)) }
        },
        containerColor = Color(0xFF1B2838),
        shape = RoundedCornerShape(20.dp)
    )

    // Show Home App Picker as separate Dialog
    if (showHomeAppPicker) {
        val allApps = remember {
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0).map { it.activityInfo }.sortedBy { it.loadLabel(pm).toString() }
        }
        
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showHomeAppPicker = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable { showHomeAppPicker = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.8f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1B2838))
                        .clickable(enabled = false) {}
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Select Home Screen Apps", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("${manualHomeApps.size} selected", color = Color(0xFF00D2FF), fontSize = 14.sp)
                    }
                    
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(allApps.size) { index ->
                            val appInfo = allApps[index]
                            val isSelected = manualHomeApps.contains(appInfo.packageName)
                            val label = appInfo.loadLabel(pm).toString()
                            val icon = appInfo.loadIcon(pm)
                            val bitmap = remember(icon) { icon.toBitmap(96, 96).asImageBitmap() }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF00D2FF).copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        if (isSelected) manualHomeApps.remove(appInfo.packageName)
                                        else manualHomeApps.add(appInfo.packageName)
                                        prefs.edit().putStringSet("manualHomeApps", manualHomeApps.toSet()).apply()
                                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                            val localDb = LocalDatabase.getInstance(context)
                                            val remoteAppPkgs = localDb.approvedAppDao().getActiveAppsList().map { it.packageName }
                                            val allAllowed = remoteAppPkgs + enabledApps.toSet() + manualHomeApps.toSet() + listOf("com.android.settings", "com.samsung.android.settings", "com.android.vending", "com.google.android.gms")
                                            KioskModeManager(context).setAllowedLockTaskPackages(allAllowed.toList())
                                        }
                                    }
                                    .padding(8.dp)
                            ) {
                                Box {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = label,
                                        modifier = Modifier.size(64.dp)
                                    )
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(24.dp)
                                                .background(Color(0xFF00D2FF), CircleShape)
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.padding(4.dp))
                                        }
                                    }
                                }
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                    
                    Button(
                        onClick = { showHomeAppPicker = false },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF)),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Done", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
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
    val context = LocalContext.current
    val clockPrefs = context.getSharedPreferences("KioskSettings", Context.MODE_PRIVATE)
    val use24h = remember { mutableStateOf(clockPrefs.getBoolean("use24h", true)) }
    val time = remember { mutableStateOf("") }
    val batteryLevel = remember { mutableStateOf(-1) }
    val isCharging = remember { mutableStateOf(false) }
    val networkType = remember { mutableStateOf("No Signal") }

    LaunchedEffect(Unit) {
        while (true) {
            // Clock
            val fmt = if (clockPrefs.getBoolean("use24h", true)) "HH:mm" else "hh:mm a"
            time.value = SimpleDateFormat(fmt, Locale.getDefault()).format(Date())
            use24h.value = clockPrefs.getBoolean("use24h", true)

            // Battery
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryIntent?.let {
                val level = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                batteryLevel.value = (level * 100 / scale)
                val status = it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                isCharging.value = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            }

            // Network
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            networkType.value = when {
                nc == null -> "Offline"
                nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Online"
            }

            kotlinx.coroutines.delay(2000)
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(networkType.value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (batteryLevel.value >= 0) {
                val batteryColor = when {
                    isCharging.value -> Color(0xFF4CAF50)
                    batteryLevel.value <= 15 -> Color.Red
                    batteryLevel.value <= 30 -> Color(0xFFFF9800)
                    else -> Color.White
                }
                Text(
                    text = if (isCharging.value) "⚡${batteryLevel.value}%" else "${batteryLevel.value}%",
                    color = batteryColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
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

