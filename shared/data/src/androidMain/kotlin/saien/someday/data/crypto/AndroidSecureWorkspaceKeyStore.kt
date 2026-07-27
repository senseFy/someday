package saien.someday.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureWorkspaceKeyStore(
    context: Context,
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
) : SecureWorkspaceKeyStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun put(
        alias: String,
        workspaceKey: WorkspaceMasterKey,
    ) {
        require(alias.isNotBlank()) { "Secure storage alias must not be blank." }
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(workspaceKey.rawBytesCopy())
        val key = alias.storageKey()
        val saved = preferences.edit()
            .putString("${key}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${key}_ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        require(saved) { "Android secure preferences rejected the workspace key." }
    }

    override fun get(alias: String): WorkspaceMasterKey? =
        runCatching {
            val key = alias.storageKey()
            val encodedIv = preferences.getString("${key}_iv", null) ?: return@runCatching null
            val encodedCiphertext = preferences.getString("${key}_ciphertext", null) ?: return@runCatching null
            val cipher = Cipher.getInstance(transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(gcmTagBits, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            crypto.workspaceKeyFromBytes(cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)))
        }.getOrNull()

    override fun remove(alias: String) {
        val key = alias.storageKey()
        preferences.edit()
            .remove("${key}_iv")
            .remove("${key}_ciphertext")
            .commit()
    }

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

    private fun String.storageKey(): String =
        Base64.encodeToString(encodeToByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)

    private companion object {
        const val preferencesName = "someday_workspace_keys"
        const val androidKeyStore = "AndroidKeyStore"
        const val keyAlias = "someday_workspace_key_wrapping_key"
        const val transformation = "AES/GCM/NoPadding"
        const val gcmTagBits = 128
    }
}
