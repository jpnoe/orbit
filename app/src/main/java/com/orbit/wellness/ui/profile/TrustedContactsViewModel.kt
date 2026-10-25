package com.orbit.wellness.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.local.StatusRequest
import com.orbit.wellness.data.local.TrustedContact
import com.orbit.wellness.data.sharing.SharingRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrustedContactsUiState(
    val contacts: List<TrustedContact> = emptyList(),
    val editingContact: TrustedContact? = null,
    val showAddDialog: Boolean = false,
    val newName: String = "",
    val newPhone: String = "",
    val pendingRequest: StatusRequest? = null,
    val isSaving: Boolean = false,
    val lastInboundAudit: String? = null,
)

class TrustedContactsViewModel(
    private val sharingRepository: SharingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrustedContactsUiState())
    val uiState: StateFlow<TrustedContactsUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            sharingRepository.observeContacts().collect { contacts ->
                _uiState.update { state ->
                    val editing = state.editingContact?.id?.let { id ->
                        contacts.firstOrNull { it.id == id }
                    }
                    state.copy(contacts = contacts, editingContact = editing)
                }
            }
        }
        viewModelScope.launch {
            sharingRepository.observeLastInboundAudit().collect { summary ->
                _uiState.update { it.copy(lastInboundAudit = summary) }
            }
        }
    }

    fun openAddDialog() {
        _uiState.update {
            it.copy(showAddDialog = true, newName = "", newPhone = "")
        }
    }

    fun dismissAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, newName = "", newPhone = "") }
    }

    fun onNewNameChanged(value: String) {
        _uiState.update { it.copy(newName = value) }
    }

    fun onNewPhoneChanged(value: String) {
        _uiState.update { it.copy(newPhone = value) }
    }

    fun addContact() {
        val current = _uiState.value
        if (current.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            sharingRepository.addContact(current.newName, current.newPhone)
                .onSuccess { contact ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            showAddDialog = false,
                            newName = "",
                            newPhone = "",
                            editingContact = contact,
                        )
                    }
                    _messages.trySend("Contact added. Set permissions before enabling auto-approve.")
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSaving = false) }
                    _messages.trySend(error.message ?: "Could not add contact.")
                }
        }
    }

    fun openContact(contact: TrustedContact) {
        _uiState.update { it.copy(editingContact = contact) }
    }

    fun dismissEditor() {
        _uiState.update { it.copy(editingContact = null) }
    }

    fun updateEditing(transform: (TrustedContact) -> TrustedContact) {
        val current = _uiState.value.editingContact ?: return
        val updated = transform(current)
        _uiState.update { it.copy(editingContact = updated) }
        viewModelScope.launch {
            sharingRepository.updateContact(updated)
        }
    }

    fun deleteEditing() {
        val current = _uiState.value.editingContact ?: return
        viewModelScope.launch {
            sharingRepository.deleteContact(current.id)
            _uiState.update { it.copy(editingContact = null) }
            _messages.trySend("Contact removed.")
        }
    }

    fun loadPendingRequest(requestId: String) {
        viewModelScope.launch {
            val request = sharingRepository.getStatusRequest(requestId)
            if (request != null && !request.resolved) {
                _uiState.update { it.copy(pendingRequest = request) }
            }
        }
    }

    fun dismissPendingRequest() {
        _uiState.update { it.copy(pendingRequest = null) }
    }

    fun approvePendingRequest() {
        val request = _uiState.value.pendingRequest ?: return
        viewModelScope.launch {
            sharingRepository.approveRequest(request.id)
                .onSuccess {
                    _uiState.update { it.copy(pendingRequest = null) }
                    _messages.trySend("Status sent.")
                }
                .onFailure { error ->
                    _messages.trySend(friendlySmsError(error))
                }
        }
    }

    fun denyPendingRequest() {
        val request = _uiState.value.pendingRequest ?: return
        viewModelScope.launch {
            sharingRepository.denyRequest(request.id)
            _uiState.update { it.copy(pendingRequest = null) }
            _messages.trySend("Request denied.")
        }
    }

    fun sendUpdate(contact: TrustedContact) {
        viewModelScope.launch {
            _uiState.update { it.copy(editingContact = null) }
            sharingRepository.sendUpdate(
                contact = contact,
                onNeedsManualApproval = { request ->
                    _uiState.update { it.copy(pendingRequest = request) }
                },
            ).onSuccess {
                val auto = contact.autoApprove
                _messages.trySend(
                    if (auto) {
                        "Status update sent."
                    } else {
                        "Status update ready — review the Send/Deny prompt."
                    },
                )
            }.onFailure { error ->
                _messages.trySend(friendlySmsError(error))
            }
        }
    }

    private fun friendlySmsError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("getGroupIdLevel1", ignoreCase = true) ->
                "Could not send SMS on this phone (SIM / dual-SIM issue). Try again after this update."
            raw.isBlank() -> "Could not send SMS."
            else -> "Could not send SMS: $raw"
        }
    }

    class Factory(
        private val sharingRepository: SharingRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TrustedContactsViewModel(sharingRepository) as T
        }
    }
}
