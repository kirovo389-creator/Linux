package com.kirovo.linuxai.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("linux_ai_config", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) { prefs.edit().putString("base_url", value.trim().trimEnd('/')).apply() }

    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(value) { prefs.edit().putString("model", value).apply() }

    var apiKey: String
        get() = decrypt(prefs.getString("api_key_enc", null))
        set(value) { prefs.edit().putString("api_key_enc", encrypt(value)).apply() }

    var savedModels: List<String>
        get() = try {
            val array = JSONArray(prefs.getString("models", "[]"))
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) { emptyList() }
        set(value) {
            val array = JSONArray()
            value.forEach(array::put)
            prefs.edit().putString("models", array.toString()).apply()
        }

    fun isReady() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    companion object { private const val KEY_ALIAS = "linux_ai_api_key_v1" }
}
