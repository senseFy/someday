package saien.someday.app.android

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.decodeSelfHostedSessionCredentials
import saien.someday.domain.settings.encodeForSecureStorage
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credential writes use synchronous commit because authority adoption must not
 * report success before its encrypted credential is durable.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
class AndroidSelfHostedSessionCredentialStore(
    context: Context,
) : SelfHostedSessionCredentialStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(): SelfHostedSessionCredentials? =
        decrypt(ivKey, ciphertextKey)?.let(::decodeSelfHostedSessionCredentials)

    override fun save(credentials: SelfHostedSessionCredentials) {
        encrypt(ivKey, ciphertextKey, credentials.encodeForSecureStorage())
    }

    override fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
        decrypt(authorityIvKey(authorityBindingId), authorityCiphertextKey(authorityBindingId))
            ?.let(::decodeSelfHostedSessionCredentials)
            ?.takeIf { saien.someday.domain.settings.selfHostedV2AuthorityBindingId(it.endpoint) == authorityBindingId }

    override fun saveForAuthority(authorityBindingId: String, credentials: SelfHostedSessionCredentials) {
        require(saien.someday.domain.settings.selfHostedV2AuthorityBindingId(credentials.endpoint) == authorityBindingId)
        encrypt(
            authorityIvKey(authorityBindingId),
            authorityCiphertextKey(authorityBindingId),
            credentials.encodeForSecureStorage(),
        )
    }

    override fun clearAuthority(authorityBindingId: String) {
        preferences.edit()
            .remove(authorityIvKey(authorityBindingId))
            .remove(authorityCiphertextKey(authorityBindingId))
            .commit()
    }

    override fun clear() {
        val editor = preferences.edit().remove(ivKey).remove(ciphertextKey)
        preferences.all.keys.filter { it.startsWith(authorityPrefix) }.forEach(editor::remove)
        editor.commit()
    }

    private fun decrypt(ivPreferenceKey: String, ciphertextPreferenceKey: String): String? {
        val encodedIv = preferences.getString(ivPreferenceKey, null) ?: return null
        val encodedCiphertext = preferences.getString(ciphertextPreferenceKey, null) ?: return null
        val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
        val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(gcmTagBits, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun encrypt(ivPreferenceKey: String, ciphertextPreferenceKey: String, encoded: String) {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(encoded.encodeToByteArray())
        val saved = preferences.edit()
            .putString(ivPreferenceKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ciphertextPreferenceKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        require(saved) { "Android credential preferences rejected the self-hosted session." }
    }

    private fun authorityKey(authorityBindingId: String): String =
        Base64.encodeToString(authorityBindingId.encodeToByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun authorityIvKey(authorityBindingId: String): String = "$authorityPrefix${authorityKey(authorityBindingId)}.iv"

    private fun authorityCiphertextKey(authorityBindingId: String): String =
        "$authorityPrefix${authorityKey(authorityBindingId)}.ciphertext"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(androidKeyStore).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, androidKeyStore)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val preferencesName = "someday_selfhosted_session"
        const val ivKey = "session_iv"
        const val ciphertextKey = "session_ciphertext"
        const val authorityPrefix = "authority."
        const val androidKeyStore = "AndroidKeyStore"
        const val keyAlias = "someday_selfhosted_session_key"
        const val transformation = "AES/GCM/NoPadding"
        const val gcmTagBits = 128
    }
}
