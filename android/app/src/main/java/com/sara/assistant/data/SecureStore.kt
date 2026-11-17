package com.sara.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest key/value store for the Gemini API key and every subagent's API key.
 *
 * Uses EncryptedSharedPreferences as requested. Heads up: the Jetpack `security-crypto`
 * API is deprecated upstream (no drop-in replacement has shipped yet), so this class may
 * emit a deprecation warning. It still works on API 28+ and is the right tool today.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun read(key: String): String? = prefs.getString(key, null)

    fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val FILE_NAME = "sara_secure_store"
    }
}
