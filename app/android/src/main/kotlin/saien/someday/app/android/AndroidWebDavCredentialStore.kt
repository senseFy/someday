package saien.someday.app.android

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import saien.someday.domain.settings.WebDavAuthorityCredentials
import saien.someday.domain.settings.WebDavCredentialStore
import saien.someday.domain.settings.decodeWebDavAuthorityCredentials
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
class AndroidWebDavCredentialStore(
    context: Context,
) : WebDavCredentialStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(): String? = decrypt(ivKey, ciphertextKey)

    override fun save(secret: String) {
        require(secret.isNotBlank()) { "WebDAV credential must not be blank." }
        encrypt(ivKey, ciphertextKey, secret)
    }

    override fun loadForAuthority(authorityBindingId: String): WebDavAuthorityCredentials? =
        decrypt(authorityIvKey(authorityBindingId), authorityCiphertextKey(authorityBindingId))
            ?.let(::decodeWebDavAuthorityCredentials)
            ?.takeIf { it.authorityBindingId == authorityBindingId }

    override fun saveForAuthority(credentials: WebDavAuthorityCredentials) {
        encrypt(
            authorityIvKey(credentials.authorityBindingId),
            authorityCiphertextKey(credentials.authorityBindingId),
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

    private fun encrypt(ivPreferenceKey: String, ciphertextPreferenceKey: String, value: String) {
        require(value.isNotBlank()) { "WebDAV secure payload must not be blank." }
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        val saved = preferences.edit()
            .putString(ivPreferenceKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ciphertextPreferenceKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        require(saved) { "Android credential preferences rejected the WebDAV credential." }
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
        const val preferencesName = "someday_webdav_credentials"
        const val ivKey = "credential_iv"
        const val ciphertextKey = "credential_ciphertext"
        const val authorityPrefix = "authority."
        const val androidKeyStore = "AndroidKeyStore"
        const val keyAlias = "someday_webdav_credential_key"
        const val transformation = "AES/GCM/NoPadding"
        const val gcmTagBits = 128
    }
}
