package com.orbit.wellness.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.orbit.wellness.data.MoodRepository
import com.orbit.wellness.data.local.MoodSample
import com.orbit.wellness.util.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

enum class TrendMode {
    INDIVIDUAL,
    DAILY_AVERAGE,
}

data class TrendPoint(
    val xLabel: String,
    val moodScore: Int?,
)

data class TrendsUiState(
    val mode: TrendMode = TrendMode.INDIVIDUAL,
    val points: List<TrendPoint> = emptyList(),
    val hasEnoughData: Boolean = false,
)

class TrendsViewModel(
    private val repository: MoodRepository,
) : ViewModel() {

    private val _mode = MutableStateFlow(TrendMode.INDIVIDUAL)

    val uiState: StateFlow<TrendsUiState> = combine(
        repository.observeLastSevenDays(),
        repository.observeDailyAveragesLastSevenDays(),
        _mode,
    ) { samples, averagesByDate, mode ->
        when (mode) {
            TrendMode.INDIVIDUAL -> mapIndividualUiState(samples, mode)
            TrendMode.DAILY_AVERAGE -> mapDailyAverageUiState(averagesByDate, mode)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrendsUiState(),
    )

    fun setMode(mode: TrendMode) {
        _mode.update { mode }
    }

    private fun mapIndividualUiState(
        samples: List<MoodSample>,
        mode: TrendMode,
    ): TrendsUiState {
        val points = samples.sortedBy { it.recordedAt }.map { sample ->
            TrendPoint(
                xLabel = DateUtils.shortDateTimeLabel(sample.recordedAt),
                moodScore = sample.moodScore,
            )
        }
        return TrendsUiState(
            mode = mode,
            points = points,
            hasEnoughData = samples.isNotEmpty(),
        )
    }

    private fun mapDailyAverageUiState(
        averagesByDate: Map<String, Int>,
        mode: TrendMode,
    ): TrendsUiState {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(6)

        val points = (0..6).map { offset ->
            val date = startDate.plusDays(offset.toLong())
            val localDate = DateUtils.formatLocalDate(date)
            TrendPoint(
                xLabel = DateUtils.dayLabel(date),
                moodScore = averagesByDate[localDate],
            )
        }

        val scoredDays = points.count { it.moodScore != null }
        return TrendsUiState(
            mode = mode,
            points = points,
            hasEnoughData = scoredDays >= 1,
        )
    }

    class Factory(
        private val repository: MoodRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TrendsViewModel(repository) as T
        }
    }
}
