package com.example.eikokiosk.lockscreen

import androidx.lifecycle.ViewModel
import com.example.eikokiosk.auth.PinManager
import com.example.eikokiosk.auth.PinResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for the Lock Screen.
 * Manages PIN entry state and verification logic.
 */
class LockScreenViewModel(
    private val pinManager: PinManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockScreenUiState())
    val uiState: StateFlow<LockScreenUiState> = _uiState.asStateFlow()

    private val _adminGestureState = MutableStateFlow(AdminGestureState())
    val adminGestureState: StateFlow<AdminGestureState> = _adminGestureState.asStateFlow()

    /**
     * Appends a digit to the current PIN entry.
     */
    fun onDigitPressed(digit: Char) {
        val current = _uiState.value
        if (current.enteredPin.length >= 6) return
        if (current.isLockedOut) return

        val newPin = current.enteredPin + digit
        _uiState.value = current.copy(
            enteredPin = newPin,
            errorMessage = null
        )
    }

    /**
     * Removes the last digit from the current PIN entry.
     */
    fun onBackspacePressed() {
        val current = _uiState.value
        if (current.enteredPin.isEmpty()) return

        _uiState.value = current.copy(
            enteredPin = current.enteredPin.dropLast(1),
            errorMessage = null
        )
    }

    /**
     * Clears the entire PIN entry.
     */
    fun onClearPressed() {
        _uiState.value = _uiState.value.copy(enteredPin = "", errorMessage = null)
    }

    /**
     * Attempts to verify the entered User PIN.
     */
    fun onSubmitUserPin(): PinResult {
        val pin = _uiState.value.enteredPin
        if (pin.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter your PIN")
            return PinResult.Incorrect(attemptsRemaining = -1)
        }

        val result = pinManager.verifyUserPin(pin)
        when (result) {
            is PinResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    enteredPin = "",
                    errorMessage = null,
                    isUnlocked = true
                )
            }
            is PinResult.Incorrect -> {
                _uiState.value = _uiState.value.copy(
                    enteredPin = "",
                    errorMessage = "Incorrect PIN. ${result.attemptsRemaining} attempts remaining."
                )
            }
            is PinResult.LockedOut -> {
                _uiState.value = _uiState.value.copy(
                    enteredPin = "",
                    errorMessage = "Too many attempts. Try again in ${result.remainingSeconds}s.",
                    isLockedOut = true
                )
            }
            is PinResult.NoPinSet -> {
                // No User PIN set — unlock directly (first-time use)
                _uiState.value = _uiState.value.copy(isUnlocked = true)
            }
        }
        return result
    }

    /**
     * Verifies the Master PIN for admin access.
     */
    fun verifyMasterPin(pin: String): Boolean {
        return pinManager.verifyMasterPin(pin)
    }

    /**
     * Records a tap for the hidden admin gesture.
     * Requires a 3-second long press followed by 5 rapid taps.
     */
    fun onAdminGestureTap() {
        val current = _adminGestureState.value
        val now = System.currentTimeMillis()

        // Reset if too much time passed since last tap
        if (now - current.lastTapTime > 2000) {
            _adminGestureState.value = AdminGestureState(tapCount = 1, lastTapTime = now)
            return
        }

        val newCount = current.tapCount + 1
        _adminGestureState.value = current.copy(tapCount = newCount, lastTapTime = now)

        if (newCount >= 5) {
            _adminGestureState.value = AdminGestureState(showMasterPinDialog = true)
        }
    }

    /**
     * Dismisses the master PIN dialog.
     */
    fun dismissMasterPinDialog() {
        _adminGestureState.value = AdminGestureState()
    }
}

data class LockScreenUiState(
    val enteredPin: String = "",
    val errorMessage: String? = null,
    val isUnlocked: Boolean = false,
    val isLockedOut: Boolean = false
)

data class AdminGestureState(
    val tapCount: Int = 0,
    val lastTapTime: Long = 0L,
    val showMasterPinDialog: Boolean = false
)
