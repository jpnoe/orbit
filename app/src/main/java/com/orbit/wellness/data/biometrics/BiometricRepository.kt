package com.orbit.wellness.data.biometrics

import com.orbit.wellness.data.local.BiometricSnapshot
import com.orbit.wellness.data.local.BiometricSnapshotDao
import com.orbit.wellness.data.local.StateVectorSnapshot
import com.orbit.wellness.data.local.StateVectorSnapshotDao
import com.orbit.wellness.domain.state.StateEncoder
import com.orbit.wellness.domain.state.StateVector
import com.orbit.wellness.domain.topology.TopologyRepository
import android.util.Log
import kotlinx.coroutines.flow.Flow

/**
 * Syncs Health Connect → Room biometric snapshot, then runs [StateEncoder] into Room.
 */
class BiometricRepository(
    private val reader: HealthConnectReader,
    private val biometricSnapshotDao: BiometricSnapshotDao,
    private val stateVectorSnapshotDao: StateVectorSnapshotDao,
    private val stateEncoder: StateEncoder,
    private val topologyRepository: TopologyRepository,
) {
    fun observeLatestBiometric(): Flow<BiometricSnapshot?> =
        biometricSnapshotDao.observeLatest()

    fun observeLatestStateVector(): Flow<StateVectorSnapshot?> =
        stateVectorSnapshotDao.observeLatest()

    suspend fun getLatestBiometric(): BiometricSnapshot? = biometricSnapshotDao.getLatest()

    suspend fun getLatestStateVector(): StateVector? =
        stateVectorSnapshotDao.getLatest()?.let(StateVector::fromSnapshot)

    fun isHealthConnectAvailable(): Boolean = reader.isAvailable()

    fun sdkStatus(): Int = reader.sdkStatus()

    suspend fun hasReadPermissions(): Boolean = reader.hasAllReadPermissions()

    /**
     * Pull HC metrics into Room, then encode + persist the 10-axis state vector.
     * Encoding still runs (manual axes only) if HC is unavailable or denied.
     */
    suspend fun syncAndEncode(): SyncResult {
        var biometric: BiometricSnapshot? = null
        var biometricError: String? = null
        var notes: List<String> = emptyList()

        when {
            !reader.isAvailable() -> {
                biometricError = "Health Connect is not available on this device."
            }
            !reader.hasAllReadPermissions() -> {
                biometricError = "Health Connect permissions are not granted (or need re-grant for heart rate)."
            }
            else -> {
                try {
                    val read = reader.readSnapshot()
                    if (read != null) {
                        biometricSnapshotDao.insert(read.snapshot)
                        biometric = read.snapshot
                        notes = read.notes
                        if (!read.hasWearableMetrics) {
                            biometricError = read.notes.lastOrNull()
                                ?: "Synced, but Health Connect returned no steps / heart rate."
                        }
                    } else {
                        biometricError = "No biometric data returned from Health Connect."
                    }
                } catch (e: Exception) {
                    biometricError = e.message ?: "Health Connect sync failed."
                }
            }
        }

        val snapshot = stateEncoder.encodeAndPersist()
        ingestTopology(snapshot)
        return SyncResult(
            biometric = biometric ?: biometricSnapshotDao.getLatest(),
            stateVector = StateVector.fromSnapshot(snapshot),
            biometricError = biometricError,
            notes = notes,
        )
    }

    /**
     * Latest overnight sleep from Health Connect for Mood-tab prefill (not persisted).
     */
    suspend fun suggestSleepFromHealthConnect(): SleepSessionSuggestion? =
        reader.readLatestSleepSession()

    /** Encode from existing Room data without a new HC pull. */
    suspend fun encodeOnly(): StateVector {
        val snapshot = stateEncoder.encodeAndPersist()
        ingestTopology(snapshot)
        return StateVector.fromSnapshot(snapshot)
    }

    private suspend fun ingestTopology(snapshot: StateVectorSnapshot) {
        try {
            topologyRepository.ingest(snapshot)
        } catch (e: Exception) {
            Log.w("OrbitTopology", "ingest failed; encode still persisted", e)
        }
    }

    data class SyncResult(
        val biometric: BiometricSnapshot?,
        val stateVector: StateVector,
        val biometricError: String?,
        val notes: List<String> = emptyList(),
    )
}
