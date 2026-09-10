package com.studyagent.client.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.studyagent.client.core.common.AppLogger

interface SecureTokenStorage {
    fun saveToken(profileId: String, token: String)
    fun getToken(profileId: String): String?
    fun removeToken(profileId: String)
    fun clearAll()
}

class AndroidSecureTokenStorage(
    private val context: Context
) : SecureTokenStorage {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            AppLogger.w("SecureTokenStorage", "Fallback to private SharedPreferences: ${e.message}")
            context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    override fun saveToken(profileId: String, token: String) {
        prefs.edit().putString(KEY_PREFIX + profileId, token).apply()
    }

    override fun getToken(profileId: String): String? {
        return prefs.getString(KEY_PREFIX + profileId, null)
    }

    override fun removeToken(profileId: String) {
        prefs.edit().remove(KEY_PREFIX + profileId).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PREFIX = "token_profile_"
    }
}
