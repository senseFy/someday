package saien.someday.app.desktop

import saien.someday.domain.settings.WebDavCredentialStore
import saien.someday.domain.settings.WebDavAuthorityCredentials
import saien.someday.domain.settings.decodeWebDavAuthorityCredentials
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

class DesktopWebDavCredentialStore(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".someday", "credentials"),
    private val keychain: DesktopKeychainCredentialStore? = DesktopKeychainCredentialStore.createIfAvailable(),
) : WebDavCredentialStore {
    private val fallback = AesFileCredentialStore(directory)

    override fun load(): String? =
        keychain?.load() ?: fallback.load()

    override fun save(secret: String) {
        require(secret.isNotBlank()) { "WebDAV credential must not be blank." }
        if (keychain != null) {
            keychain.save(secret)
            fallback.clear()
        } else {
            fallback.save(secret)
        }
    }

    override fun clear() {
        keychain?.clearAll()
        fallback.clearAll()
    }

    override fun loadForAuthority(authorityBindingId: String): WebDavAuthorityCredentials? =
        (keychain?.load(authorityAccount(authorityBindingId)) ?: fallback.loadAuthority(authorityBindingId))
            ?.let(::decodeWebDavAuthorityCredentials)
            ?.takeIf { it.authorityBindingId == authorityBindingId }

    override fun saveForAuthority(credentials: WebDavAuthorityCredentials) {
        val encoded = credentials.encodeForSecureStorage()
        if (keychain != null) {
            keychain.save(authorityAccount(credentials.authorityBindingId), encoded)
            fallback.clearAuthority(credentials.authorityBindingId)
        } else {
            fallback.saveAuthority(credentials.authorityBindingId, encoded)
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

class DesktopKeychainCredentialStore private constructor() : WebDavCredentialStore {
    override fun load(): String? = load(account)

    fun load(accountName: String): String? {
        val result = runSecurity(
            "find-generic-password",
            "-a",
            accountName,
            "-s",
            service,
            "-w",
        )
        return if (result.exitCode == 0) {
            result.output.trimEnd('\n').takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    override fun save(secret: String) {
        save(account, secret)
    }

    fun save(accountName: String, secret: String) {
        require(secret.isNotBlank()) { "WebDAV credential must not be blank." }
        runSecurity("delete-generic-password", "-a", accountName, "-s", service)
        val result = runSecurity(
            "add-generic-password",
            "-a",
            accountName,
            "-s",
            service,
            "-w",
            secret,
            "-U",
        )
        require(result.exitCode == 0) { "macOS Keychain rejected the WebDAV credential." }
    }

    override fun clear() {
        clear(account)
    }

    fun clear(accountName: String) {
        runSecurity("delete-generic-password", "-a", accountName, "-s", service)
    }

    fun clearAll() {
        repeat(maxAuthorityEntries) {
            if (runSecurity("delete-generic-password", "-s", service).exitCode != 0) return
        }
        error("macOS Keychain contains too many WebDAV credential entries to clear safely.")
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
        private const val service = "saien.someday.webdav.credential"
        private const val account = "default"
        private const val maxAuthorityEntries = 64

        fun createIfAvailable(): DesktopKeychainCredentialStore? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return if (os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/security"))) {
                DesktopKeychainCredentialStore()
            } else {
                null
            }
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private class AesFileCredentialStore(
    private val directory: Path,
) : WebDavCredentialStore {
    private val keyPath = directory.resolve("webdav.key")
    private val credentialPath = directory.resolve("webdav.credential")
    private val random = SecureRandom()

    override fun load(): String? = loadPath(credentialPath)

    fun loadAuthority(authorityBindingId: String): String? = loadPath(authorityPath(authorityBindingId))

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

    override fun save(secret: String) {
        savePath(credentialPath, secret)
    }

    fun saveAuthority(authorityBindingId: String, value: String) = savePath(authorityPath(authorityBindingId), value)

    private fun savePath(path: Path, secret: String) {
        require(secret.isNotBlank()) { "WebDAV credential must not be blank." }
        Files.createDirectories(directory)
        restrictOwnerAccess(directory)
        val nonce = ByteArray(nonceBytes).also(random::nextBytes)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(gcmTagBits, nonce))
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(secret.encodeToByteArray())
        atomicWrite(path, "$version:${Base64.encode(nonce)}:${Base64.encode(ciphertext)}")
        restrictOwnerAccess(path)
    }

    override fun clear() {
        Files.deleteIfExists(credentialPath)
    }

    override fun clearAuthority(authorityBindingId: String) {
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
        val associatedData = "someday-webdav-credential-v2".encodeToByteArray()
        const val authorityFilePrefix = "webdav-authority-"
    }
}
