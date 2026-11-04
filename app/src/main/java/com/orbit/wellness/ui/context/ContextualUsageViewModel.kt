package com.orbit.wellness.ui.context

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.SubstanceRepository
import com.orbit.wellness.data.local.CheckInContextWindow
import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContextualUsageUiState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val title: String = "",
    val detail: String? = null,
    val options: List<SubstanceOption> = emptyList(),
    val lines: List<ContextualUsageLine> = emptyList(),
    val isSaving: Boolean = false,
    val addIntent: SubstanceIntent? = null,
    val newOptionName: String = "",
)

class ContextualUsageViewModel(
    private val checkInId: String,
    private val checkInRepository: CheckInRepository,
    private val substanceRepository: SubstanceRepository,
    private val pending: PendingCheckInSession? = null,
) : ViewModel() {

    private val selection = ContextualUsageSelection()
    private val _uiState = MutableStateFlow(ContextualUsageUiState())
    val uiState: StateFlow<ContextualUsageUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _finished = Channel<Unit>(Channel.BUFFERED)
    val finished = _finished.receiveAsFlow()

    init {
        if (checkInId.isBlank()) {
            _uiState.value = ContextualUsageUiState(loaded = true, missing = true)
        } else if (pending != null && checkInId == PendingCheckInSession.ID) {
            viewModelScope.launch {
                combine(pending.usage, substanceRepository.observeOptions()) { selections, options ->
                    selections to options
                }.collect { (selections, options) ->
                    if (selections != null) {
                        selection.onSavedItems(pending.usageItems())
                    }
                    selection.onOptions(options)
                    publish(pending.window, options)
                }
            }
        } else {
            viewModelScope.launch {
                combine(
                    checkInRepository.observeContextWindow(checkInId),
                    checkInRepository.observeUsageItems(checkInId),
                    substanceRepository.observeOptions(),
                ) { window, items, options ->
                    Triple(window, items, options)
                }.collect { (window, items, options) ->
                    selection.onSavedItems(items)
                    selection.onOptions(options)
                    if (window == null) {
                        _uiState.update {
                            it.copy(loaded = true, missing = true, options = options, lines = selection.lines)
                        }
                    } else {
                        publish(window, options)
                    }
                }
            }
        }
    }

    fun toggle(option: SubstanceOption) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        selection.toggle(option)
        publishLines()
    }

    fun onAmountChanged(key: String, amount: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        selection.onAmountChanged(key, amount)
        publishLines()
    }

    fun remove(key: String) {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        selection.remove(key)
        publishLines()
    }

    fun openAddDialog(intent: SubstanceIntent) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(addIntent = intent, newOptionName = "") }
    }

    fun dismissAddDialog() {
        _uiState.update { it.copy(addIntent = null, newOptionName = "") }
    }

    fun onNewOptionNameChanged(name: String) {
        _uiState.update { it.copy(newOptionName = name) }
    }

    fun confirmAddOption() {
        val intent = _uiState.value.addIntent ?: return
        val name = _uiState.value.newOptionName.trim()
        if (name.isEmpty() || _uiState.value.isSaving) return
        viewModelScope.launch {
            substanceRepository.addOption(name, intent)
            selection.selectAdded(name, intent)
            _uiState.update {
                it.copy(
                    addIntent = null,
                    newOptionName = "",
                    lines = selection.lines,
                )
            }
        }
    }

    fun saveUsage() {
        if (_uiState.value.isSaving || _uiState.value.missing) return
        commit(selection.commitForSave())
    }

    private fun commit(commit: UsageReviewCommit) {
        if (pending != null && commit is UsageReviewCommit.Replace) {
            pending.stageUsage(commit.selections)
            _finished.trySend(Unit)
            return
        }
        _uiState.update { it.copy(isSaving = true, lines = selection.lines) }
        viewModelScope.launch {
            try {
                commitUsageReview(checkInRepository, checkInId, commit)
                _uiState.update { it.copy(isSaving = false) }
                _finished.trySend(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isSaving = false, lines = selection.lines) }
                _messages.trySend(error.message ?: "Could not save usage.")
            }
        }
    }

    private fun publish(window: CheckInContextWindow, options: List<SubstanceOption>) {
        _uiState.update { state ->
            state.copy(
                loaded = true,
                missing = false,
                title = headingTitle(window),
                detail = headingDetail(window),
                options = options,
                lines = selection.lines,
            )
        }
    }

    private fun publishLines() {
        _uiState.update { it.copy(lines = selection.lines) }
    }

    private fun headingTitle(window: CheckInContextWindow): String {
        return ContextOverviewCopy.heading(
            window.startBasis,
            window.windowStartAt,
            java.time.ZoneId.systemDefault(),
        ).first
    }

    private fun headingDetail(window: CheckInContextWindow): String? {
        return ContextOverviewCopy.heading(
            window.startBasis,
            window.windowStartAt,
            java.time.ZoneId.systemDefault(),
        ).second
    }

    class Factory(
        private val checkInId: String,
        private val checkInRepository: CheckInRepository,
        private val substanceRepository: SubstanceRepository,
        private val pending: PendingCheckInSession? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContextualUsageViewModel(
                checkInId,
                checkInRepository,
                substanceRepository,
                pending,
            ) as T
        }
    }
}
