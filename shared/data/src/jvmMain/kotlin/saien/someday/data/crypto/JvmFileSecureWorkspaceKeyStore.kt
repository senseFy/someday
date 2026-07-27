package saien.someday.data.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class JvmFileSecureWorkspaceKeyStore(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".someday", "workspace-keys"),
    private val crypto: SodiumWorkspaceCrypto = SodiumWorkspaceCrypto(),
) : SecureWorkspaceKeyStore {
    private val wrappingKeyPath = directory.resolve("wrapping.key")
    private val random = SecureRandom()

    override fun put(
        alias: String,
        workspaceKey: WorkspaceMasterKey,
    ) {
        require(alias.isNotBlank()) { "Secure storage alias must not be blank." }
        Files.createDirectories(directory)
        restrictOwnerAccess(directory)
        val nonce = ByteArray(nonceBytes).also(random::nextBytes)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(), GCMParameterSpec(gcmTagBits, nonce))
        cipher.updateAAD(alias.associatedData())
        val ciphertext = cipher.doFinal(workspaceKey.rawBytesCopy())
        atomicWrite(alias.path(), "$version:${Base64.encode(nonce)}:${Base64.encode(ciphertext)}")
        restrictOwnerAccess(alias.path())
    }

    override fun get(alias: String): WorkspaceMasterKey? =
        runCatching {
            val path = alias.path()
            if (!Files.exists(path)) return@runCatching null
            val parts = Files.readString(path).split(":")
            if (parts.size != 3 || parts[0] != version) return@runCatching null
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(gcmTagBits, Base64.decode(parts[1])))
            cipher.updateAAD(alias.associatedData())
            crypto.workspaceKeyFromBytes(cipher.doFinal(Base64.decode(parts[2])))
        }.getOrNull()

    override fun remove(alias: String) {
        Files.deleteIfExists(alias.path())
    }

    private fun wrappingKey(): SecretKeySpec {
        Files.createDirectories(directory)
        restrictOwnerAccess(directory)
        if (!Files.exists(wrappingKeyPath)) {
            val keyBytes = ByteArray(keySizeBytes).also(random::nextBytes)
            atomicWrite(wrappingKeyPath, Base64.encode(keyBytes))
            restrictOwnerAccess(wrappingKeyPath)
        }
        return SecretKeySpec(Base64.decode(Files.readString(wrappingKeyPath).trim()), "AES")
    }

    private fun String.path(): Path =
        directory.resolve("${MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).hex()}.key")

    private fun String.associatedData(): ByteArray =
        "someday-workspace-key-v2|$this".encodeToByteArray()

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
    }
}
