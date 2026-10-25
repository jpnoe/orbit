package com.orbit.wellness.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.CurrentMoodStore
import com.orbit.wellness.data.MoodRepository
import com.orbit.wellness.data.SleepRepository
import com.orbit.wellness.data.biometrics.BiometricRepository
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
    val moodScore: Float = 5f,
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
    private val sleepRepository: SleepRepository,
    private val currentMoodStore: CurrentMoodStore,
    private val biometricRepository: BiometricRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CheckInUiState(moodScore = currentMoodStore.committedMood.value),
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

    /** Snap slider to last logged mood (call on tab enter / leave without save). */
    fun syncFromCommitted() {
        _uiState.update { it.copy(moodScore = currentMoodStore.committedMood.value) }
    }

    fun onMoodChanged(score: Float) {
        _uiState.update { it.copy(moodScore = score.coerceIn(0f, 10f)) }
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

    fun saveMood() {
        val current = _uiState.value
        if (current.isSaving) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val score = current.moodScore.roundToInt().coerceIn(0, 10)
            val recordedAt = current.loggedAtMillis
            moodRepository.logMood(
                moodScore = score,
                note = current.moodNote,
                emotion = current.selectedEmotion,
                recordedAt = recordedAt,
            )
            currentMoodStore.commit(score.toFloat(), recordedAt)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    moodNote = "",
                    selectedEmotion = "",
                    moodScore = score.toFloat(),
                    loggedAtMillis = System.currentTimeMillis(),
                )
            }
            _savedFeedback.trySend(Unit)
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
        private val sleepRepository: SleepRepository,
        private val currentMoodStore: CurrentMoodStore,
        private val biometricRepository: BiometricRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CheckInViewModel(
                moodRepository,
                sleepRepository,
                currentMoodStore,
                biometricRepository,
            ) as T
        }
    }
}
