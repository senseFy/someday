package saien.someday.app.desktop

import saien.someday.domain.settings.SelfHostedSessionCredentialStore
import saien.someday.domain.settings.SelfHostedSessionCredentials
import saien.someday.domain.settings.authorityBindingId
import saien.someday.domain.settings.decodeSelfHostedSessionCredentials
import saien.someday.domain.settings.encodeForSecureStorage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class DesktopSelfHostedSessionCredentialStore(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".someday", "credentials"),
    private val keychain: DesktopSelfHostedSessionKeychainStore? =
        DesktopSelfHostedSessionKeychainStore.createIfAvailable(),
) : SelfHostedSessionCredentialStore {
    private val fallback = DesktopAesSelfHostedSessionStore(directory)

    override fun load(): SelfHostedSessionCredentials? =
        (keychain?.loadText() ?: fallback.loadText())
            ?.let(::decodeSelfHostedSessionCredentials)

    override fun save(credentials: SelfHostedSessionCredentials) {
        val encoded = credentials.encodeForSecureStorage()
        if (keychain != null) {
            keychain.saveText(encoded)
            fallback.clear()
        } else {
            fallback.saveText(encoded)
        }
    }

    override fun clear() {
        keychain?.clearAll()
        fallback.clearAll()
    }

    override fun loadForAuthority(authorityBindingId: String): SelfHostedSessionCredentials? =
        (keychain?.loadText(authorityAccount(authorityBindingId)) ?: fallback.loadAuthorityText(authorityBindingId))
            ?.let(::decodeSelfHostedSessionCredentials)
            ?.takeIf { it.authorityBindingId == authorityBindingId }

    override fun saveForAuthority(authorityBindingId: String, credentials: SelfHostedSessionCredentials) {
        require(credentials.authorityBindingId == authorityBindingId)
        val encoded = credentials.encodeForSecureStorage()
        if (keychain != null) {
            keychain.saveText(authorityAccount(authorityBindingId), encoded)
            fallback.clearAuthority(authorityBindingId)
        } else {
            fallback.saveAuthorityText(authorityBindingId, encoded)
        }
    }

    override fun clearAuthority(authorityBindingId: String) {
        keychain?.clear(authorityAccount(authorityBindingId))
        fallback.clearAuthority(authorityBindingId)
    }

    private companion object {
        fun authorityAccount(authorityBindingId: String): String = "authority:$authorityBindingId"
    }
}

internal class DesktopSelfHostedSessionKeychainStore private constructor() {
    fun loadText(): String? = loadText(account)

    fun loadText(accountName: String): String? {
        val result = runSecurity(
            "find-generic-password",
            "-a",
            accountName,
            "-s",
            service,
            "-w",
        )
        return if (result.exitCode == 0) {
            normalizeSelfHostedKeychainOutput(result.output)
        } else {
            null
        }
    }

    fun saveText(value: String) = saveText(account, value)

    fun saveText(accountName: String, value: String) {
        require(value.isNotBlank()) { "Self-hosted session payload must not be blank." }
        require('\n' !in value && '\r' !in value) {
            "Self-hosted session payload must use the single-line secure-storage envelope."
        }
        val result = runSecurity(
            "add-generic-password",
            "-a",
            accountName,
            "-s",
            service,
            "-U",
            "-w",
            // Prompted input is length-limited by `security` and can silently truncate JWTs.
            // Supplying the value directly is its supported non-interactive, arbitrary-length path.
            value,
        )
        require(result.exitCode == 0 && loadText(accountName) == value) {
            "macOS Keychain rejected the self-hosted session."
        }
    }

    fun clear() {
        clear(account)
    }

    fun clear(accountName: String) {
        runSecurity("delete-generic-password", "-a", accountName, "-s", service)
    }

    fun clearAll() {
        repeat(maxAuthorityEntries) {
            if (runSecurity("delete-generic-password", "-s", service).exitCode != 0) return
        }
        error("macOS Keychain contains too many self-hosted session entries to clear safely.")
    }

    private fun runSecurity(vararg args: String): CommandResult {
        val process = ProcessBuilder(listOf("/usr/bin/security") + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return CommandResult(exitCode = process.waitFor(), output = output)
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    )

    companion object {
        private const val service = "saien.someday.selfhosted.session"
        private const val account = "default"
        private const val maxAuthorityEntries = 64

        fun createIfAvailable(): DesktopSelfHostedSessionKeychainStore? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return if (os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/security"))) {
                DesktopSelfHostedSessionKeychainStore()
            } else {
                null
            }
        }
    }
}

/**
 * The macOS `security` CLI renders generic-password values containing newlines as hexadecimal
 * text. Current credentials use a single-line envelope, but development builds may have written
 * the previous multiline payload. Only accept a hexadecimal recovery when it decodes to a valid
 * Someday credential, so arbitrary Keychain values remain fail-closed.
 */
internal fun normalizeSelfHostedKeychainOutput(output: String): String? {
    val stored = output.trimEnd('\n', '\r').takeIf { it.isNotBlank() } ?: return null
    if (decodeSelfHostedSessionCredentials(stored) != null) return stored
    val decodedHex = stored.decodeHexUtf8OrNull() ?: return stored
    return decodedHex.takeIf { decodeSelfHostedSessionCredentials(it) != null } ?: stored
}

private fun String.decodeHexUtf8OrNull(): String? {
    if (length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { index ->
            val offset = index * 2
            ((this[offset].digitToInt(16) shl 4) or this[offset + 1].digitToInt(16)).toByte()
        }.decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()
}

@OptIn(ExperimentalEncodingApi::class)
private class DesktopAesSelfHostedSessionStore(
    private val directory: Path,
) {
    private val keyPath = directory.resolve("selfhosted-session.key")
    private val sessionPath = directory.resolve("selfhosted-session.credential")
    private val random = SecureRandom()

    fun loadText(): String? = loadPath(sessionPath)

    fun loadAuthorityText(authorityBindingId: String): String? = loadPath(authorityPath(authorityBindingId))

    private fun loadPath(path: Path): String? {
        if (!Files.exists(path) || !Files.exists(keyPath)) {
            return null
        }
        val parts = Files.readString(path).split(":")
        if (parts.size != 3 || parts[0] != version) {
            return null
        }
        val nonce = Base64.decode(parts[1])
        val ciphertext = Base64.decode(parts[2])
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(gcmTagBits, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext).decodeToString()
    }

    fun saveText(value: String) = savePath(sessionPath, value)

    fun saveAuthorityText(authorityBindingId: String, value: String) = savePath(authorityPath(authorityBindingId), value)

    private fun savePath(path: Path, value: String) {
        require(value.isNotBlank()) { "Self-hosted session payload must not be blank." }
        Files.createDirectories(directory)
        restrictOwnerAccess(directory)
        val nonce = ByteArray(nonceBytes).also(random::nextBytes)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(gcmTagBits, nonce))
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        atomicWrite(path, "$version:${Base64.encode(nonce)}:${Base64.encode(ciphertext)}")
        restrictOwnerAccess(path)
    }

    fun clear() {
        Files.deleteIfExists(sessionPath)
    }

    fun clearAuthority(authorityBindingId: String) {
        Files.deleteIfExists(authorityPath(authorityBindingId))
    }

    fun clearAll() {
        clear()
        if (Files.isDirectory(directory)) {
            Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().startsWith(authorityFilePrefix) }
                    .forEach(Files::deleteIfExists)
            }
        }
    }

    private fun authorityPath(authorityBindingId: String): Path =
        directory.resolve("$authorityFilePrefix${sha256Hex(authorityBindingId)}.credential")

    private fun sha256Hex(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun key(): SecretKeySpec {
        Files.createDirectories(directory)
        restrictOwnerAccess(directory)
        if (!Files.exists(keyPath)) {
            val keyBytes = ByteArray(keySizeBytes).also(random::nextBytes)
            atomicWrite(keyPath, Base64.encode(keyBytes))
            restrictOwnerAccess(keyPath)
        }
        return SecretKeySpec(Base64.decode(Files.readString(keyPath).trim()), "AES")
    }

    private fun atomicWrite(
        path: Path,
        content: String,
    ) {
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temp, content)
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun restrictOwnerAccess(path: Path) {
        runCatching {
            val permissions = if (Files.isDirectory(path)) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            }
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private companion object {
        const val version = "v2"
        const val transformation = "AES/GCM/NoPadding"
        const val nonceBytes = 12
        const val keySizeBytes = 32
        const val gcmTagBits = 128
        val associatedData = "someday-selfhosted-session-v2".encodeToByteArray()
        const val authorityFilePrefix = "selfhosted-authority-"
    }
}
