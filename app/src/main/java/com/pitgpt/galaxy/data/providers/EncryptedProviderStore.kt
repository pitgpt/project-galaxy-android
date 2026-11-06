package com.pitgpt.galaxy.data.providers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String
)

data class ProviderSettings(
    val providers: List<AiProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val textModel: String? = null,
    val voiceModel: String? = null
)

/** Device-local encrypted provider settings. The ciphertext is excluded from Android backup. */
class EncryptedProviderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("galaxy_provider_vault", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): ProviderSettings {
        val encoded = prefs.getString(DATA_KEY, null) ?: return ProviderSettings()
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
            val root = JSONObject(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).toString(Charsets.UTF_8))
            val providers = buildList {
                val array = root.optJSONArray("providers") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(AiProviderConfig(item.getString("id"), item.getString("name"),
                        item.getString("baseUrl"), item.getString("apiKey"), item.getString("model")))
                }
            }
            ProviderSettings(providers, root.optString("activeProviderId").takeIf { it.isNotBlank() },
                root.optString("textModel").takeIf { it.isNotBlank() },
                root.optString("voiceModel").takeIf { it.isNotBlank() })
        }.getOrElse {
            // Never return partial or plaintext data if the ciphertext/key is invalid.
            prefs.edit().remove(DATA_KEY).commit()
            ProviderSettings()
        }
    }

    @Synchronized
    fun save(settings: ProviderSettings) {
        val root = JSONObject().put("providers", JSONArray().apply {
            settings.providers.forEach { provider ->
                put(JSONObject().put("id", provider.id).put("name", provider.name)
                    .put("baseUrl", provider.baseUrl).put("apiKey", provider.apiKey).put("model", provider.model))
            }
        }).put("activeProviderId", settings.activeProviderId ?: "")
            .put("textModel", settings.textModel ?: "").put("voiceModel", settings.voiceModel ?: "")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    private companion object {
        const val DATA_KEY = "encrypted_provider_settings_v1"
        const val KEY_ALIAS = "galaxy_provider_vault_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
