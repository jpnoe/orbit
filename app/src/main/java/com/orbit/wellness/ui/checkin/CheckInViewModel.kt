package com.orbit.wellness.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CheckInDrafts
import com.orbit.wellness.data.CheckInRepository
import com.orbit.wellness.data.CurrentMoodStore
import com.orbit.wellness.data.MoodRepository
import com.orbit.wellness.data.SleepRepository
import com.orbit.wellness.data.biometrics.BiometricRepository
import com.orbit.wellness.data.local.CheckInAxis
import com.orbit.wellness.data.local.CheckInRecord
import com.orbit.wellness.data.local.DefaultCheckInAxes
import com.orbit.wellness.data.local.EmotionOption
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.round

data class CheckInUiState(
    val enabledAxes: List<CheckInAxis> = emptyList(),
    val axesLoaded: Boolean = false,
    /**
     * Explicit axis values keyed by stable axis id.
     * Absent and null both mean the axis was not set.
     */
    val axisDrafts: Map<String, Int?> = emptyMap(),
    /** Visual slider anchor for mood. This is not an observation. */
    val moodAnchor: Float = 5f,
    val moodNote: String = "",
    val emotionOptions: List<EmotionOption> = emptyList(),
    val selectedEmotion: String = "",
    val showAddEmotionDialog: Boolean = false,
    val newEmotionName: String = "",
    val isSaving: Boolean = false,
    val todaySampleCount: Int = 0,
    val todaySamples: List<MoodSample> = emptyList(),
    val loggedAtMillis: Long = System.currentTimeMillis(),
    val hasLoggedSleepToday: Boolean = false,
    val sleepLoaded: Boolean = false,
    val bedtimeMillis: Long = defaultBedtimeMillis(),
    val wakeTimeMillis: Long = defaultWakeTimeMillis(),
    val sleepQuality: Float = 5f,
    val isSavingSleep: Boolean = false,
    /** True when bedtime/wake were seeded from Health Connect and not yet saved. */
    val sleepPrefillFromHealthConnect: Boolean = false,
)

private fun defaultBedtimeMillis(): Long {
    return DateUtils.atTimeMillis(LocalDate.now().minusDays(1), 23, 0)
}

private fun defaultWakeTimeMillis(): Long {
    return DateUtils.atTimeMillis(LocalDate.now(), 7, 0)
}

class CheckInViewModel(
    private val moodRepository: MoodRepository,
    private val checkInRepository: CheckInRepository,
    private val sleepRepository: SleepRepository,
    private val currentMoodStore: CurrentMoodStore,
    private val biometricRepository: BiometricRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CheckInUiState(moodAnchor = currentMoodStore.committedMood.value),
    )
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _savedFeedback = Channel<Unit>(Channel.BUFFERED)
    val savedFeedback = _savedFeedback.receiveAsFlow()

    private val _sleepSavedFeedback = Channel<Unit>(Channel.BUFFERED)
    val sleepSavedFeedback = _sleepSavedFeedback.receiveAsFlow()

    private var appliedSleepPrefill = false
    private var sleepTimesTouchedByUser = false

    init {
        viewModelScope.launch {
            checkInRepository.observeEnabledAxes().collect { axes ->
                _uiState.update { state ->
                    state.copy(
                        enabledAxes = axes,
                        axesLoaded = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            moodRepository.observeEmotionOptions().collect { options ->
                _uiState.update { it.copy(emotionOptions = options) }
            }
        }
        viewModelScope.launch {
            moodRepository.observeTodaySamples().collect { samples ->
                val checkIns = samples.filter { it.source == MoodSample.SOURCE_CHECK_IN }
                _uiState.update {
                    it.copy(
                        todaySampleCount = checkIns.size,
                        todaySamples = checkIns,
                    )
                }
            }
        }
        viewModelScope.launch {
            sleepRepository.observeToday().collect { sleep ->
                if (sleep != null) {
                    appliedSleepPrefill = true
                    _uiState.update {
                        it.copy(
                            hasLoggedSleepToday = true,
                            sleepLoaded = true,
                            bedtimeMillis = sleep.bedtime,
                            wakeTimeMillis = sleep.wakeTime,
                            sleepQuality = sleep.sleepQuality.toFloat(),
                            sleepPrefillFromHealthConnect = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            hasLoggedSleepToday = false,
                            sleepLoaded = true,
                        )
                    }
                    maybePrefillSleepFromHealthConnect()
                }
            }
        }
    }

    private suspend fun maybePrefillSleepFromHealthConnect() {
        if (appliedSleepPrefill || sleepTimesTouchedByUser) return
        if (_uiState.value.hasLoggedSleepToday) return
        val suggestion = biometricRepository.suggestSleepFromHealthConnect() ?: return
        if (suggestion.wakeTimeMillis <= suggestion.bedtimeMillis) return
        appliedSleepPrefill = true
        _uiState.update {
            it.copy(
                bedtimeMillis = suggestion.bedtimeMillis,
                wakeTimeMillis = suggestion.wakeTimeMillis,
                sleepPrefillFromHealthConnect = true,
            )
        }
    }

    /** Move the mood visual anchor. Does not mark mood as set. */
    fun syncFromCommitted() {
        _uiState.update { it.copy(moodAnchor = currentMoodStore.committedMood.value) }
    }

    fun onAxisChanged(axisId: String, score: Float) {
        val value = score.roundToInt().coerceIn(0, 10)
        _uiState.update { state ->
            state.copy(axisDrafts = state.axisDrafts + (axisId to value))
        }
    }

    fun clearAxis(axisId: String) {
        _uiState.update { state ->
            state.copy(axisDrafts = state.axisDrafts + (axisId to null))
        }
    }

    fun onMoodNoteChanged(note: String) {
        _uiState.update { it.copy(moodNote = note) }
    }

    fun onLoggedAtChanged(millis: Long) {
        _uiState.update { it.copy(loggedAtMillis = millis) }
    }

    fun onEmotionSelected(emotion: String) {
        _uiState.update {
            it.copy(selectedEmotion = if (it.selectedEmotion == emotion) "" else emotion)
        }
    }

    fun openAddEmotionDialog() {
        _uiState.update { it.copy(showAddEmotionDialog = true, newEmotionName = "") }
    }

    fun dismissAddEmotionDialog() {
        _uiState.update { it.copy(showAddEmotionDialog = false) }
    }

    fun onNewEmotionNameChanged(name: String) {
        _uiState.update { it.copy(newEmotionName = name) }
    }

    fun confirmAddEmotion() {
        val current = _uiState.value
        val name = current.newEmotionName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            moodRepository.addCustomEmotion(name)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            showAddEmotionDialog = false,
                            newEmotionName = "",
                            selectedEmotion = name,
                        )
                    }
                }
                .onFailure { error ->
                    _messages.trySend(error.message ?: "Could not add emotion.")
                }
        }
    }

    fun onBedtimeChanged(millis: Long) {
        sleepTimesTouchedByUser = true
        _uiState.update {
            it.copy(bedtimeMillis = millis, sleepPrefillFromHealthConnect = false)
        }
    }

    fun onWakeTimeChanged(millis: Long) {
        sleepTimesTouchedByUser = true
        _uiState.update {
            it.copy(wakeTimeMillis = millis, sleepPrefillFromHealthConnect = false)
        }
    }

    fun onSleepQualityChanged(score: Float) {
        _uiState.update { it.copy(sleepQuality = score) }
    }

    fun saveSleep() {
        val current = _uiState.value
        if (current.isSavingSleep) return
        if (current.wakeTimeMillis <= current.bedtimeMillis) {
            _messages.trySend("Wake time must be after bedtime.")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingSleep = true) }
            sleepRepository.logSleep(
                bedtime = current.bedtimeMillis,
                wakeTime = current.wakeTimeMillis,
                sleepQuality = current.sleepQuality.roundToInt().coerceIn(0, 10),
            )
            _uiState.update {
                it.copy(
                    isSavingSleep = false,
                    sleepPrefillFromHealthConnect = false,
                )
            }
            _sleepSavedFeedback.trySend(Unit)
        }
    }

    fun saveCheckIn() {
        val current = _uiState.value
        if (current.isSaving) return

        val enabledIds = current.enabledAxes.map { it.id }.toSet()
        val inputs = CheckInDrafts.toAxisInputs(current.axisDrafts)
            .filter { it.axisId in enabledIds }
        if (inputs.isEmpty()) {
            _messages.trySend("Set at least one scale before saving.")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val recordedAt = current.loggedAtMillis
                checkInRepository.saveCheckIn(
                    axisValues = inputs,
                    trigger = CheckInRecord.TRIGGER_MANUAL,
                    note = current.moodNote,
                    recordedAt = recordedAt,
                )
                val mood = inputs.firstOrNull { it.axisId == DefaultCheckInAxes.MOOD }
                if (mood != null) {
                    moodRepository.logMood(
                        moodScore = mood.value,
                        note = current.moodNote,
                        emotion = current.selectedEmotion,
                        recordedAt = recordedAt,
                    )
                    currentMoodStore.commit(mood.value.toFloat(), recordedAt)
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        axisDrafts = emptyMap(),
                        moodNote = "",
                        selectedEmotion = "",
                        loggedAtMillis = System.currentTimeMillis(),
                        moodAnchor = currentMoodStore.committedMood.value,
                    )
                }
                _savedFeedback.trySend(Unit)
            } catch (error: IllegalArgumentException) {
                _uiState.update { it.copy(isSaving = false) }
                _messages.trySend(error.message ?: "Could not save check-in.")
            }
        }
    }

    fun updateMood(sample: MoodSample, newScore: Int, newNote: String) {
        viewModelScope.launch {
            moodRepository.updateMood(sample, newScore, newNote)
        }
    }

    private fun Float.roundToInt(): Int = round(this).toInt()

    class Factory(
        private val moodRepository: MoodRepository,
        private val checkInRepository: CheckInRepository,
        private val sleepRepository: SleepRepository,
        private val currentMoodStore: CurrentMoodStore,
        private val biometricRepository: BiometricRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CheckInViewModel(
                moodRepository,
                checkInRepository,
                sleepRepository,
                currentMoodStore,
                biometricRepository,
            ) as T
        }
    }
}
