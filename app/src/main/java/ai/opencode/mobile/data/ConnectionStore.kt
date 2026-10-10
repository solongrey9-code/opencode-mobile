package ai.opencode.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    fun load(): ConnectionProfile? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        return ConnectionProfile(
            baseUrl = baseUrl,
            bridgeBaseUrl = preferences.getString(KEY_BRIDGE_BASE_URL, "").orEmpty(),
            bridgeToken = decrypt(preferences.getString(KEY_BRIDGE_TOKEN, null)).orEmpty(),
            directory = preferences.getString(KEY_DIRECTORY, "").orEmpty(),
            username = preferences.getString(KEY_USERNAME, "opencode").orEmpty().ifBlank { "opencode" },
            password = decrypt(preferences.getString(KEY_PASSWORD, null)).orEmpty(),
        )
    }

    fun save(profile: ConnectionProfile) {
        val encryptedPassword = encrypt(profile.password)
        val encryptedBridgeToken = encrypt(profile.bridgeToken)
        preferences.edit {
            putString(KEY_BASE_URL, profile.baseUrl)
            putString(KEY_BRIDGE_BASE_URL, profile.bridgeBaseUrl)
            putString(KEY_BRIDGE_TOKEN, encryptedBridgeToken)
            putString(KEY_DIRECTORY, profile.directory)
            putString(KEY_USERNAME, profile.username)
            putString(KEY_PASSWORD, encryptedPassword)
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(combined)
        encrypted.copyInto(combined, destinationOffset = cipher.iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrEmpty()) return ""
        return runCatching {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            require(combined.size > IV_SIZE)
            val iv = combined.copyOfRange(0, IV_SIZE)
            val encrypted = combined.copyOfRange(IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opencode-mobile-connection"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_BASE_URL = "base_url"
        const val KEY_BRIDGE_BASE_URL = "bridge_base_url"
        const val KEY_BRIDGE_TOKEN = "bridge_token"
        const val KEY_DIRECTORY = "directory"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
