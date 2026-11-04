package com.orbit.wellness.ui.profile

import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.WearableObservationRepository
import com.orbit.wellness.data.biometrics.BiometricRepository
import com.orbit.wellness.data.local.WearableObservation
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
    val observations: List<WearableObservation> = emptyList(),
    val syncNotes: List<String> = emptyList(),
)

class HealthConnectViewModel(
    private val biometricRepository: BiometricRepository,
    private val wearableObservations: WearableObservationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HealthConnectUiState())
    val uiState: StateFlow<HealthConnectUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refreshAvailability()
        viewModelScope.launch {
            wearableObservations.observeRecent().collect { observations ->
                _uiState.update { it.copy(observations = observations) }
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
                val result = biometricRepository.sync()
                _uiState.update {
                    it.copy(
                        hasPermissions = biometricRepository.hasReadPermissions(),
                        syncNotes = result.notes,
                    )
                }
                when {
                    result.error != null -> _messages.trySend(result.error)
                    result.notes.isNotEmpty() -> {
                        _messages.trySend("Synced with notes — see details below.")
                    }
                    else -> _messages.trySend("Synced wearable observations.")
                }
            } catch (e: Exception) {
                _messages.trySend(e.message ?: "Sync failed.")
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
        private val wearableObservations: WearableObservationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HealthConnectViewModel(
                biometricRepository,
                wearableObservations,
            ) as T
        }
    }
}
