package com.orbit.wellness.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedContactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contact: TrustedContact)

    @Update
    suspend fun update(contact: TrustedContact)

    @Query("DELETE FROM trusted_contacts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM trusted_contacts ORDER BY contactName ASC")
    fun observeAll(): Flow<List<TrustedContact>>

    @Query("SELECT * FROM trusted_contacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TrustedContact?

    @Query(
        """
        SELECT * FROM trusted_contacts
        WHERE enabled = 1 AND phoneE164 = :phoneE164
        LIMIT 1
        """,
    )
    suspend fun findEnabledByPhone(phoneE164: String): TrustedContact?

    @Query("SELECT * FROM trusted_contacts WHERE enabled = 1")
    suspend fun getAllEnabled(): List<TrustedContact>

    @Query("SELECT * FROM trusted_contacts")
    suspend fun getAll(): List<TrustedContact>
}
