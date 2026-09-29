package saien.someday.domain.workspace

/** Local identity captured before dispatching workspace work or opening an external picker. */
data class WorkspaceProductSnapshot(
    val workspaceId: String,
    val accountIncarnation: String,
    val authorityBindingId: String?,
    val writerDeviceId: String,
    val localRevision: Long = 0,
)

class WorkspaceProductReadOnlyException : IllegalStateException("This local workspace is read-only.")
class WorkspaceProductChangedException : IllegalStateException("The local workspace changed before this work completed.")

/**
 * [capture], [read], and [mutate] may read the local database and belong on an IO dispatcher.
 * [isCurrent] only checks an in-memory snapshot and is safe when applying a completed UI result.
 * Blocks are synchronous and must not perform network work or acquire the workspace lifecycle lock.
 */
interface WorkspaceProductAccess {
    fun capture(): WorkspaceProductSnapshot
    fun isCurrent(snapshot: WorkspaceProductSnapshot): Boolean
    fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T
    fun <T> mutate(snapshot: WorkspaceProductSnapshot? = null, block: () -> T): T
}

/** Used by standalone local UI fixtures that have no replaceable workspace. */
object UnrestrictedWorkspaceProductAccess : WorkspaceProductAccess {
    private val identity = WorkspaceProductSnapshot("local", "local", null, "local")
    override fun capture(): WorkspaceProductSnapshot = identity
    override fun isCurrent(snapshot: WorkspaceProductSnapshot): Boolean = snapshot == identity
    override fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T {
        if (!isCurrent(snapshot)) throw WorkspaceProductChangedException()
        return block()
    }
    override fun <T> mutate(snapshot: WorkspaceProductSnapshot?, block: () -> T): T {
        if (snapshot != null && !isCurrent(snapshot)) throw WorkspaceProductChangedException()
        return block()
    }
}
