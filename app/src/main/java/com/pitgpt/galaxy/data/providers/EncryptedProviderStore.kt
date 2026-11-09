package com.pitgpt.galaxy.data.providers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiWorkflowTemplate
import com.pitgpt.galaxy.data.providers.comfyui.ComfyUiNodeInput
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
) {
    /** Keep user-controlled endpoint metadata and credentials out of accidental logs. */
    override fun toString(): String =
        "AiProviderConfig(idConfigured=${id.isNotBlank()}, nameConfigured=${name.isNotBlank()}, " +
            "baseUrlConfigured=${baseUrl.isNotBlank()}, apiKeyConfigured=${apiKey.isNotBlank()}, " +
            "modelConfigured=${model.isNotBlank()})"
}

data class ProviderSettings(
    val providers: List<AiProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val textModel: String? = null,
    val voiceModel: String? = null,
    val comfyUi: ComfyUiSavedSettings? = null,
    val comfyUiWorkflow: ComfyUiWorkflowTemplate? = null
) {
    /** Avoid leaking user-controlled model identifiers through generated data-class output. */
    override fun toString(): String =
        "ProviderSettings(providerCount=${providers.size}, activeProviderConfigured=${!activeProviderId.isNullOrBlank()}, " +
            "textModelConfigured=${!textModel.isNullOrBlank()}, voiceModelConfigured=${!voiceModel.isNullOrBlank()}, " +
            "comfyUiConfigured=${comfyUi != null}, workflowConfigured=${comfyUiWorkflow != null})"
}

/**
 * Raised when previously saved encrypted settings cannot be read.
 *
 * The original ciphertext is deliberately retained so a transient Keystore problem or
 * recoverable key state does not silently destroy provider credentials.
 */
class ProviderSettingsLoadException(cause: Throwable) :
    IllegalStateException("Saved provider settings could not be decrypted or parsed.", cause)

/** Device-local encrypted provider settings. The ciphertext is excluded from Android backup. */
class EncryptedProviderStore internal constructor(
    context: Context,
    prefsName: String = "galaxy_provider_vault",
    private val keyAlias: String = KEY_ALIAS
) : ProviderSettingsRepository {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): ProviderSettings {
        val encoded = prefs.getString(DATA_KEY, null) ?: return ProviderSettings()
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_BYTES) { "Encrypted provider settings are truncated." }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // Never create a replacement key while reading: it cannot decrypt old data.
            cipher.init(Cipher.DECRYPT_MODE, existingKey(),
                GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
            val root = JSONObject(
                cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)).toString(Charsets.UTF_8)
            )
            val providers = buildList {
                val array = root.optJSONArray("providers") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(AiProviderConfig(
                        item.getString("id"),
                        item.getString("name"),
                        item.getString("baseUrl"),
                        item.getString("apiKey"),
                        item.getString("model")
                    ))
                }
            }
            val comfyUiJson = root.optJSONObject("comfyUi")
            val comfyUi = comfyUiJson?.let { value ->
                ComfyUiSavedSettings(
                    lanBaseUrl = value.optString("lanBaseUrl").takeIf { it.isNotBlank() && it != "null" },
                    lanBearerToken = value.optString("lanBearerToken").takeIf { it.isNotBlank() && it != "null" },
                    vpnBaseUrl = value.optString("vpnBaseUrl").takeIf { it.isNotBlank() && it != "null" },
                    vpnBearerToken = value.optString("vpnBearerToken").takeIf { it.isNotBlank() && it != "null" },
                    preference = com.pitgpt.galaxy.data.providers.comfyui.ComfyUiRoutePreference.valueOf(
                        value.optString("preference").ifBlank { "AUTO" }
                    )
                )
            }
            val workflowJson = root.optJSONObject("comfyUiWorkflow")
            val workflow = workflowJson?.let { value ->
                fun node(name: String): ComfyUiNodeInput? {
                    val item = value.optJSONObject(name) ?: return null
                    return ComfyUiNodeInput(
                        nodeId = item.getString("nodeId"),
                        inputName = item.getString("inputName")
                    )
                }
                ComfyUiWorkflowTemplate(
                    id = value.getString("id"),
                    workflowJson = value.getString("workflowJson"),
                    positivePrompt = requireNotNull(node("positivePrompt")) {
                        "Workflow positive-prompt mapping is missing"
                    },
                    negativePrompt = node("negativePrompt"),
                    width = node("width"),
                    height = node("height"),
                    seed = node("seed")
                )
            }.also { it?.validateMappings() }
            ProviderSettings(
                providers,
                root.optString("activeProviderId").takeIf { it.isNotBlank() },
                root.optString("textModel").takeIf { it.isNotBlank() },
                root.optString("voiceModel").takeIf { it.isNotBlank() },
                comfyUi,
                workflow
            )
        } catch (error: Exception) {
            // Preserve the ciphertext. Recovery or destructive reset must be an explicit user action.
            throw ProviderSettingsLoadException(error)
        }
    }

    @Synchronized
    override fun save(settings: ProviderSettings) {
        settings.comfyUiWorkflow?.validateMappings()
        val comfyUi = settings.comfyUi?.let { value ->
            JSONObject()
                .put("lanBaseUrl", value.lanBaseUrl ?: JSONObject.NULL)
                .put("lanBearerToken", value.lanBearerToken ?: JSONObject.NULL)
                .put("vpnBaseUrl", value.vpnBaseUrl ?: JSONObject.NULL)
                .put("vpnBearerToken", value.vpnBearerToken ?: JSONObject.NULL)
                .put("preference", value.preference.name)
        } ?: JSONObject.NULL
        val workflow = settings.comfyUiWorkflow?.let { value ->
            fun node(input: ComfyUiNodeInput?): Any = input?.let {
                JSONObject().put("nodeId", it.nodeId).put("inputName", it.inputName)
            } ?: JSONObject.NULL
            JSONObject()
                .put("id", value.id)
                .put("workflowJson", value.workflowJson)
                .put("positivePrompt", node(value.positivePrompt))
                .put("negativePrompt", node(value.negativePrompt))
                .put("width", node(value.width))
                .put("height", node(value.height))
                .put("seed", node(value.seed))
        } ?: JSONObject.NULL
        val root = JSONObject().put("providers", JSONArray().apply {
            settings.providers.forEach { provider ->
                put(JSONObject().put("id", provider.id).put("name", provider.name)
                    .put("baseUrl", provider.baseUrl).put("apiKey", provider.apiKey).put("model", provider.model))
            }
        }).put("activeProviderId", settings.activeProviderId ?: "")
            .put("textModel", settings.textModel ?: "").put("voiceModel", settings.voiceModel ?: "")
            .put("comfyUi", comfyUi).put("comfyUiWorkflow", workflow)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(root.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }

    /** Removes unreadable saved data only after an explicit user confirmation in the UI. */
    @Synchronized
    override fun clear() {
        // The user has explicitly confirmed a destructive reset. Remove the ciphertext first,
        // then retire even an invalidated Keystore key so a fresh key can be generated.
        check(prefs.edit().remove(DATA_KEY).commit()) { "Could not clear provider settings." }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
    }

    private fun existingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(keyAlias, null) as? SecretKey
            ?: throw IllegalStateException("The encryption key for saved provider settings is missing.")
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliasExists = store.containsAlias(keyAlias)
        requireProviderKeyCreationAllowed(
            hasStoredCiphertext = prefs.contains(DATA_KEY),
            keyAliasExists = aliasExists
        )
        if (aliasExists) {
            return store.getKey(keyAlias, null) as? SecretKey
                ?: throw IllegalStateException("The provider encryption key exists but is unavailable.")
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias,
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

/**
 * Prevents a save path from replacing encrypted settings when their original Keystore key is gone.
 * A missing key with existing ciphertext is a locked-data state, not a first-run state.
 */
internal fun requireProviderKeyCreationAllowed(hasStoredCiphertext: Boolean, keyAliasExists: Boolean) {
    check(keyAliasExists || !hasStoredCiphertext) {
        "Saved provider settings exist but their encryption key is missing; refusing to overwrite them."
    }
}
