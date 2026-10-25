package com.orbit.wellness.ui.profile

import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.biometrics.BiometricRepository
import com.orbit.wellness.data.local.BiometricSnapshot
import com.orbit.wellness.data.local.StateVectorSnapshot
import com.orbit.wellness.domain.state.StateVector
import com.orbit.wellness.domain.topology.TopologyHarness
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HealthConnectUiState(
    val sdkAvailable: Boolean = false,
    val sdkStatusCode: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val hasPermissions: Boolean = false,
    val isBusy: Boolean = false,
    val biometric: BiometricSnapshot? = null,
    val stateVector: StateVectorSnapshot? = null,
    val axisRows: List<Pair<String, Float>> = emptyList(),
    /** Last sync diagnostics (empty HC, Fitbit not writing, HR fallback, etc.). */
    val syncNotes: List<String> = emptyList(),
)

class HealthConnectViewModel(
    private val biometricRepository: BiometricRepository,
    private val topologyHarness: TopologyHarness? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthConnectUiState())
    val uiState: StateFlow<HealthConnectUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refreshAvailability()
        viewModelScope.launch {
            biometricRepository.observeLatestBiometric().collect { snapshot ->
                _uiState.update { it.copy(biometric = snapshot) }
            }
        }
        viewModelScope.launch {
            biometricRepository.observeLatestStateVector().collect { snapshot ->
                _uiState.update {
                    it.copy(
                        stateVector = snapshot,
                        axisRows = snapshot?.let { s ->
                            StateVector.fromSnapshot(s).labeledPairs()
                        } ?: emptyList(),
                    )
                }
            }
        }
    }

    fun refreshAvailability() {
        viewModelScope.launch {
            val available = biometricRepository.isHealthConnectAvailable()
            val status = biometricRepository.sdkStatus()
            val granted = if (available) {
                biometricRepository.hasReadPermissions()
            } else {
                false
            }
            _uiState.update {
                it.copy(
                    sdkAvailable = available,
                    sdkStatusCode = status,
                    hasPermissions = granted,
                )
            }
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        viewModelScope.launch {
            val ok = biometricRepository.hasReadPermissions()
            _uiState.update { it.copy(hasPermissions = ok) }
            if (ok) {
                syncNow()
            } else {
                _messages.trySend("Health Connect permissions were not granted.")
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val result = biometricRepository.syncAndEncode()
                _uiState.update {
                    it.copy(
                        hasPermissions = biometricRepository.hasReadPermissions(),
                        axisRows = result.stateVector.labeledPairs(),
                        syncNotes = result.notes,
                    )
                }
                when {
                    result.biometricError != null && result.biometric == null -> {
                        _messages.trySend(result.biometricError)
                    }
                    result.biometricError != null -> {
                        _messages.trySend(result.biometricError)
                    }
                    result.notes.isNotEmpty() -> {
                        _messages.trySend("Synced with notes — see details below.")
                    }
                    else -> {
                        _messages.trySend("Synced Health Connect and encoded state vector.")
                    }
                }
            } catch (e: Exception) {
                _messages.trySend(e.message ?: "Sync failed.")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun encodeOnly() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val vector = biometricRepository.encodeOnly()
                _uiState.update { it.copy(axisRows = vector.labeledPairs()) }
                _messages.trySend("Encoded state vector from Room (no HC pull).")
            } catch (e: Exception) {
                _messages.trySend(e.message ?: "Encode failed.")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun seedSyntheticTopology() {
        val harness = topologyHarness ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val summary = harness.seedAndEvaluate()
                _messages.trySend(summary)
            } catch (e: Exception) {
                _messages.trySend(e.message ?: "Synthetic seed failed.")
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun sdkStatusLabel(): String = when (_uiState.value.sdkStatusCode) {
        HealthConnectClient.SDK_AVAILABLE -> "Available"
        HealthConnectClient.SDK_UNAVAILABLE -> "Unavailable"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Update required"
        else -> "Unknown (${_uiState.value.sdkStatusCode})"
    }

    class Factory(
        private val biometricRepository: BiometricRepository,
        private val topologyHarness: TopologyHarness? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HealthConnectViewModel(biometricRepository, topologyHarness) as T
        }
    }
}
