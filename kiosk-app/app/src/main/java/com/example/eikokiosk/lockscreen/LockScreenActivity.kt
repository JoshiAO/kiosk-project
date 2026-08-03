package com.example.eikokiosk.lockscreen

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.eikokiosk.admin.AdminDashboardActivity
import com.example.eikokiosk.auth.PinManager
import com.example.eikokiosk.auth.PinResult
import com.example.eikokiosk.launcher.LauncherActivity
import com.example.eikokiosk.theme.EikoKioskTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * Full-screen custom lock screen activity.
 *
 * - Shows a PIN pad for User PIN entry
 * - Displays the time and date
 * - Contains a hidden gesture (5 rapid taps on the clock) for Admin access
 * - Navigates to LauncherActivity on successful PIN entry
 * - Navigates to AdminDashboardActivity on successful Master PIN entry
 */
class LockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val pinManager = PinManager(applicationContext)
        val viewModel = LockScreenViewModel(pinManager)

        // Start the lock screen foreground service
        val serviceIntent = Intent(this, LockScreenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            EikoKioskTheme {
                LockScreen(
                    viewModel = viewModel,
                    onUnlocked = { navigateToLauncher() },
                    onAdminAccess = { navigateToAdmin() }
                )
            }
        }
    }

    private fun navigateToLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun navigateToAdmin() {
        val intent = Intent(this, AdminDashboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // Prevent back press from dismissing the lock screen
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing — lock screen cannot be dismissed with back button
    }
}

// ==================== Compose UI ====================

@Composable
fun LockScreen(
    viewModel: LockScreenViewModel,
    onUnlocked: () -> Unit,
    onAdminAccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val adminGesture by viewModel.adminGestureState.collectAsState()

    // Navigate on unlock
    LaunchedEffect(uiState.isUnlocked) {
        if (uiState.isUnlocked) onUnlocked()
    }

    val gradientBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0D1B2A),
            Color(0xFF1B2838),
            Color(0xFF0D1B2A)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBrush),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Clock (hidden admin gesture target)
            ClockDisplay(
                onTap = { viewModel.onAdminGestureTap() }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // PIN dots
            PinDots(pinLength = uiState.enteredPin.length)

            Spacer(modifier = Modifier.height(8.dp))

            // Error message
            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage!!,
                    color = Color(0xFFFF6B6B),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // PIN pad
            PinPad(
                onDigit = { viewModel.onDigitPressed(it) },
                onBackspace = { viewModel.onBackspacePressed() },
                onSubmit = { viewModel.onSubmitUserPin() },
                isDisabled = uiState.isLockedOut
            )
        }
    }

    // Master PIN dialog
    if (adminGesture.showMasterPinDialog) {
        MasterPinDialog(
            onVerify = { pin ->
                val isValid = viewModel.verifyMasterPin(pin)
                if (isValid) {
                    viewModel.dismissMasterPinDialog()
                    onAdminAccess()
                }
                isValid
            },
            onDismiss = { viewModel.dismissMasterPinDialog() }
        )
    }
}

@Composable
private fun ClockDisplay(onTap: () -> Unit) {
    val time = remember { mutableStateOf(getCurrentTime()) }
    val date = remember { mutableStateOf(getCurrentDate()) }

    // Update time every second
    LaunchedEffect(Unit) {
        while (true) {
            time.value = getCurrentTime()
            date.value = getCurrentDate()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onTap
        )
    ) {
        Text(
            text = time.value,
            color = Color.White,
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp
        )
        Text(
            text = date.value,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp
        )
    }
}

@Composable
private fun PinDots(pinLength: Int, maxLength: Int = 6) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(maxLength) { index ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < pinLength) Color(0xFF4FC3F7)
                        else Color.White.copy(alpha = 0.2f)
                    )
            )
        }
    }
}

@Composable
private fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> PinResult,
    isDisabled: Boolean
) {
    val digits = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf(' ', '0', '⌫')
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        digits.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                row.forEach { key ->
                    when (key) {
                        ' ' -> {
                            // Submit / Enter
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4FC3F7).copy(alpha = 0.15f))
                                    .clickable(enabled = !isDisabled) { onSubmit() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("OK", color = Color(0xFF4FC3F7), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        '⌫' -> {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .clickable(enabled = !isDisabled) { onBackspace() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Backspace",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .clickable(enabled = !isDisabled) { onDigit(key) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key.toString(),
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Light
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MasterPinDialog(
    onVerify: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1B2838),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Admin Access",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Enter Master PIN",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 8 && it.all { c -> c.isDigit() }) {
                            pin = it
                            error = null
                        }
                    },
                    label = { Text("Master PIN") },
                    isError = error != null,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF4FC3F7),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedLabelColor = Color(0xFF4FC3F7),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                    )
                )

                if (error != null) {
                    Text(error!!, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                    Button(
                        onClick = {
                            if (!onVerify(pin)) {
                                error = "Incorrect Master PIN"
                                pin = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4FC3F7)
                        )
                    ) {
                        Text("Verify", color = Color(0xFF0D1B2A))
                    }
                }
            }
        }
    }
}

private fun getCurrentTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

private fun getCurrentDate(): String {
    return SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
}
