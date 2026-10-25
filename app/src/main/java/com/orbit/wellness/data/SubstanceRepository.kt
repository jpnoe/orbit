package com.orbit.wellness.data

import com.orbit.wellness.data.local.SubstanceIntent
import com.orbit.wellness.data.local.SubstanceOption
import com.orbit.wellness.data.local.SubstanceOptionDao
import com.orbit.wellness.data.local.SubstanceUseEvent
import com.orbit.wellness.data.local.SubstanceUseEventDao
import kotlinx.coroutines.flow.Flow

class SubstanceRepository(
    private val eventDao: SubstanceUseEventDao,
    private val optionDao: SubstanceOptionDao,
) {
    fun observeEvents(): Flow<List<SubstanceUseEvent>> = eventDao.observeAll()

    fun observeOptions(): Flow<List<SubstanceOption>> = optionDao.observeAll()

    fun observeOptions(intent: SubstanceIntent): Flow<List<SubstanceOption>> {
        return optionDao.observeByIntent(intent.name)
    }

    suspend fun logUse(
        name: String,
        amount: String,
        moodScore: Int,
        intent: SubstanceIntent,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        eventDao.insert(
            SubstanceUseEvent(
                timestamp = timestamp,
                name = name.trim(),
                amount = amount.trim(),
                moodScore = moodScore.coerceIn(0, 10),
                intent = intent.name,
            ),
        )
    }

    suspend fun addOption(
        name: String,
        intent: SubstanceIntent,
        defaultAmount: String = "",
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val nextOrder = optionDao.maxSortOrder(intent.name) + 1
        optionDao.insert(
            SubstanceOption(
                name = trimmed,
                intent = intent.name,
                defaultAmount = defaultAmount.trim(),
                sortOrder = nextOrder,
                isCustom = true,
            ),
        )
    }

    suspend fun deleteOption(id: String) {
        optionDao.deleteById(id)
    }

    suspend fun updateUse(
        event: SubstanceUseEvent,
        name: String,
        amount: String,
        moodScore: Int,
        intent: SubstanceIntent,
    ) {
        eventDao.update(
            event.copy(
                name = name.trim(),
                amount = amount.trim(),
                moodScore = moodScore.coerceIn(0, 10),
                intent = intent.name,
            ),
        )
    }
}
