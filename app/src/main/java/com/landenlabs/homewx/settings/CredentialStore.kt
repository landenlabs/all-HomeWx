package com.landenlabs.homewx.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.landenlabs.homewx.BuildConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Govee, Wyze and WxData credentials entered in Settings > Accounts & Feeds. Kept in their own prefs
 * file, separate from [AppSettings], so [com.landenlabs.homewx.data.BackupManager]'s settings
 * export never carries them, and each value is encrypted with an Android Keystore key.
 *
 * A value never saved here falls back to its [BuildConfig] default: that is empty in release
 * builds and seeded from local.properties in debug builds. Clearing a value removes it, so a
 * debug build goes back to its local.properties default.
 */
object CredentialStore {

    private const val PREFS_NAME = "homewx_credentials"
    private const val KEY_GOVEE_API_KEY = "govee_api_key"
    private const val KEY_WYZE_USER = "wyze_user"
    private const val KEY_WYZE_PASSWORD = "wyze_password"
    private const val KEY_WXDATA_API_KEY = "wxdata_api_key"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "homewx_credentials_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    fun getGoveeApiKey(context: Context): String = get(context, KEY_GOVEE_API_KEY, BuildConfig.GOVEE_API_KEY)

    fun setGoveeApiKey(context: Context, value: String) = set(context, KEY_GOVEE_API_KEY, value)

    fun getWyzeUser(context: Context): String = get(context, KEY_WYZE_USER, BuildConfig.WYZE_USER)

    fun getWyzePassword(context: Context): String = get(context, KEY_WYZE_PASSWORD, BuildConfig.WYZE_PWD)

    fun setWyzeLogin(context: Context, user: String, password: String) {
        set(context, KEY_WYZE_USER, user)
        set(context, KEY_WYZE_PASSWORD, password)
    }

    /** WxData weather source key; the BuildConfig field is still named SUN_API_KEY after the
     *  SDK's config constant ([com.wsi.wxdata.WxData.OVER_SUN]). */
    fun getWxDataApiKey(context: Context): String = get(context, KEY_WXDATA_API_KEY, BuildConfig.SUN_API_KEY)

    fun setWxDataApiKey(context: Context, value: String) = set(context, KEY_WXDATA_API_KEY, value)

    fun hasWxData(context: Context): Boolean = getWxDataApiKey(context).isNotBlank()

    fun isWxDataDefault(context: Context): Boolean = isDefault(context, KEY_WXDATA_API_KEY, BuildConfig.SUN_API_KEY)

    fun hasGovee(context: Context): Boolean = getGoveeApiKey(context).isNotBlank()

    fun hasWyze(context: Context): Boolean =
        getWyzeUser(context).isNotBlank() && getWyzePassword(context).isNotBlank()

    /** True when the Govee key shown is the build's local.properties default rather than one
     *  the user typed - lets the dialog say so instead of looking like a saved credential. */
    fun isGoveeDefault(context: Context): Boolean = isDefault(context, KEY_GOVEE_API_KEY, BuildConfig.GOVEE_API_KEY)

    fun isWyzeDefault(context: Context): Boolean =
        isDefault(context, KEY_WYZE_USER, BuildConfig.WYZE_USER) &&
            isDefault(context, KEY_WYZE_PASSWORD, BuildConfig.WYZE_PWD)

    private fun isDefault(context: Context, key: String, default: String): Boolean =
        default.isNotBlank() && !prefs(context).contains(key)

    private fun get(context: Context, key: String, default: String): String {
        val stored = prefs(context).getString(key, null) ?: return default
        // A value that can't be decrypted (keystore wiped, e.g. after a restore onto new hardware)
        // is treated as not set rather than crashing.
        return decrypt(stored) ?: default
    }

    private fun set(context: Context, key: String, value: String) {
        prefs(context).edit {
            if (value.isBlank()) remove(key) else putString(key, encrypt(value.trim()))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /** Stored as base64(iv || ciphertext); the IV is generated fresh by the cipher on each call. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val ivLength = 12
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, bytes, 0, ivLength))
        }
        String(cipher.doFinal(bytes, ivLength, bytes.size - ivLength))
    } catch (e: Exception) {
        null
    }
}
