package com.orbit.wellness.data.local

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom

private val Context.passphraseDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "orbit_passphrase_store",
)

class DatabaseKeyManager(private val context: Context) {

    private val aead: Aead = run {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(AndroidKeystoreKmsClient.PREFIX + MASTER_KEY_ALIAS)
            .build()
            .keysetHandle
        keysetHandle.getPrimitive(Aead::class.java)
    }

    fun getPassphrase(): ByteArray = runBlocking {
        getPassphraseSuspend()
    }

    private suspend fun getPassphraseSuspend(): ByteArray {
        val encoded = context.passphraseDataStore.data
            .map { preferences -> preferences[PASSPHRASE_KEY] }
            .first()

        if (encoded != null) {
            return aead.decrypt(Base64.decode(encoded, Base64.NO_WRAP), null)
        }

        val passphrase = ByteArray(PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(passphrase)
        val encrypted = aead.encrypt(passphrase, null)
        context.passphraseDataStore.edit { preferences ->
            preferences[PASSPHRASE_KEY] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }
        return passphrase
    }

    private companion object {
        const val KEYSET_NAME = "orbit_tink_keyset"
        const val KEYSET_PREF_NAME = "orbit_tink_keyset_prefs"
        const val MASTER_KEY_ALIAS = "orbit_master_key"
        const val PASSPHRASE_LENGTH = 32
        val PASSPHRASE_KEY = stringPreferencesKey("encrypted_db_passphrase")
    }
}
