package saien.someday.server.maintenance

import java.util.UUID
import java.security.MessageDigest
import saien.someday.server.auth.ACCOUNT_INITIAL_INCARNATION

/** A database-validated retired identity, never an operator-supplied object path. */
data class RetiredMediaNamespace(val userId: UUID, val incarnation: UUID, val storageLayout: String) {
    init {
        require(storageLayout == if (incarnation == ACCOUNT_INITIAL_INCARNATION) "legacy" else "incarnation-v1") {
            "Retired media layout does not match its incarnation."
        }
    }

    val relativeRoot: String
        get() = if (incarnation == ACCOUNT_INITIAL_INCARNATION) "$userId" else ".incarnations/v1/$userId/$incarnation"
    val s3Prefix: String get() = "media/v1/$relativeRoot/"
}

enum class MaintenanceMediaBackend { FILESYSTEM, S3 }

/** Opaque enumeration result; adapters validate it again before deletion. */
data class RetiredMediaEntry(val key: String, val versionId: String? = null)
data class RetiredMediaScan(val entries: Long)

enum class MaintenanceMediaFailureReason {
    PERMISSION_DENIED, PERMISSION_OR_RETENTION_DENIED, RETENTION_BLOCKED, UNSAFE_PATH, INCOMPLETE_SCAN, STORAGE_FAILURE,
}

/** Fixed safe message only: provider messages and object names are not operator output. */
class MaintenanceMediaFailure(val reason: MaintenanceMediaFailureReason, cause: Throwable? = null) :
    RuntimeException(reason.name.lowercase(), cause)

/** Separate operator capability. Runtime MediaBlobStore deliberately has no such methods. */
interface RetiredMediaStore : AutoCloseable {
    val backend: MaintenanceMediaBackend
    val storageIdentity: String

    /** Returns only after complete enumeration; each callback receives at most batchSize entries. */
    fun scan(namespace: RetiredMediaNamespace, consume: (List<RetiredMediaEntry>) -> Unit): RetiredMediaScan
    fun deleteBatch(namespace: RetiredMediaNamespace, entries: List<RetiredMediaEntry>)
    override fun close() {}
}

internal fun storageIdentityDigest(vararg fields: String): String = MessageDigest.getInstance("SHA-256")
    .digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
