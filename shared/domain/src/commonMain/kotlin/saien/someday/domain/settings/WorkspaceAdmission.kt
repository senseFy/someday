package saien.someday.domain.settings

/** Account login does not transfer the key of an existing workspace. */
enum class WorkspaceAdmissionState {
    Pending,
    FirstWorkspace,
    JoinRequired,
    Ready,
    Unavailable,
}

data class WorkspaceAdmissionStatus(
    val state: WorkspaceAdmissionState,
    val initializedWorkspaceCount: Int? = null,
    val recoveryAvailable: Boolean = false,
)

fun interface WorkspaceAdmissionManager {
    fun status(): WorkspaceAdmissionStatus
}
