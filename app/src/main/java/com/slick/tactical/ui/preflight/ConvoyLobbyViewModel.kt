package com.slick.tactical.ui.preflight

import androidx.lifecycle.ViewModel
import com.slick.tactical.engine.mesh.ConvoyMeshManager
import com.slick.tactical.engine.mesh.ConvoyRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

data class ConvoyLobbyUiState(
    val convoyCode: String = "",
    val joinCode: String = "",
    val isAdvertising: Boolean = false,
    val isDiscovering: Boolean = false,
    val connectedCount: Int = 0,
    val role: ConvoyRole = ConvoyRole.PACK,
    val error: String? = null,
)

/**
 * ViewModel for the Convoy Lobby screen.
 *
 * Create Convoy: generates a short convoy code, calls [ConvoyMeshManager.startAdvertising].
 * Join Convoy: accepts code from text input or QR scan, calls [ConvoyMeshManager.startDiscovery].
 */
@HiltViewModel
class ConvoyLobbyViewModel @Inject constructor(
    private val meshManager: ConvoyMeshManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ConvoyLobbyUiState())
    val state: StateFlow<ConvoyLobbyUiState> = _state.asStateFlow()

    /**
     * Creates a new convoy as Lead rider.
     * Generates a 6-character code (uppercase alpha-numeric, easy to share verbally).
     */
    fun createConvoy() {
        val code = generateConvoyCode()
        meshManager.startAdvertising(code)
            .onSuccess {
                _state.value = _state.value.copy(
                    convoyCode = code,
                    isAdvertising = true,
                    role = ConvoyRole.LEADER,
                    error = null,
                )
                Timber.i("Convoy created: code=%s", code)
            }
            .onFailure { e ->
                _state.value = _state.value.copy(error = "Failed to start convoy: ${e.localizedMessage}")
                Timber.e(e, "createConvoy failed")
            }
    }

    /**
     * Joins an existing convoy as a Follower.
     * Accepts the 6-character code entered by the user or scanned from a QR code.
     */
    fun joinConvoy(code: String = _state.value.joinCode) {
        val trimmed = code.trim().uppercase()
        if (trimmed.length < 4) {
            _state.value = _state.value.copy(error = "Enter at least 4 characters of the convoy code")
            return
        }
        meshManager.startDiscovery(trimmed)
            .onSuccess {
                _state.value = _state.value.copy(
                    joinCode = trimmed,
                    isDiscovering = true,
                    role = ConvoyRole.PACK,
                    error = null,
                )
                Timber.i("Joining convoy: code=%s", trimmed)
            }
            .onFailure { e ->
                _state.value = _state.value.copy(error = "Failed to join convoy: ${e.localizedMessage}")
                Timber.e(e, "joinConvoy failed")
            }
    }

    fun onJoinCodeChanged(code: String) {
        _state.value = _state.value.copy(joinCode = code.uppercase(), error = null)
    }

    /** Called when QR scan produces a code */
    fun onQrCodeScanned(rawValue: String) {
        val code = rawValue.trim().uppercase()
        _state.value = _state.value.copy(joinCode = code)
        joinConvoy(code)
    }

    fun leaveConvoy() {
        meshManager.stopMesh()
        _state.value = ConvoyLobbyUiState()
    }

    private fun generateConvoyCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  // Omit O, 0, I, 1 -- confusing verbally
        return (1..6).map { chars.random() }.joinToString("")
    }
}
