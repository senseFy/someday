package saien.someday.sync

import kotlinx.coroutines.flow.MutableStateFlow

import saien.someday.domain.workspace.WorkspaceProductAccess
import saien.someday.domain.workspace.WorkspaceProductChangedException
import saien.someday.domain.workspace.WorkspaceProductReadOnlyException
import saien.someday.domain.workspace.WorkspaceProductSnapshot

/** Identity checks and writes share the same short, reentrant barrier as workspace replacement. */
class CoordinatedWorkspaceProductAccess(
    private val coordinator: WorkspaceLifecycleCoordinator,
    private val snapshotProvider: () -> WorkspaceProductSnapshot,
    private val productReadOnly: () -> Boolean,
) : WorkspaceProductAccess {
    private var revision = 0L
    private val observed = MutableStateFlow<WorkspaceProductSnapshot?>(null)

    override fun capture(): WorkspaceProductSnapshot = coordinator.productAccess { captureLocked() }

    override fun isCurrent(snapshot: WorkspaceProductSnapshot): Boolean = snapshot == observed.value

    override fun <T> read(snapshot: WorkspaceProductSnapshot, block: () -> T): T = coordinator.productAccess {
        requireCurrent(snapshot)
        block()
    }

    override fun <T> mutate(snapshot: WorkspaceProductSnapshot?, block: () -> T): T = coordinator.productAccess {
        if (snapshot != null) requireCurrent(snapshot)
        if (productReadOnly()) throw WorkspaceProductReadOnlyException()
        block()
    }

    /** Invoke after a committed workspace replacement, before publishing its completion to UI. */
    fun invalidate() = coordinator.productAccess {
        revision += 1
        observed.value = null
    }

    private fun requireCurrent(snapshot: WorkspaceProductSnapshot) {
        if (snapshot != captureLocked()) throw WorkspaceProductChangedException()
    }

    private fun captureLocked(): WorkspaceProductSnapshot {
        val current = snapshotProvider().copy(localRevision = revision)
        if (observed.value != null && observed.value != current) revision += 1
        return current.copy(localRevision = revision).also { observed.value = it }
    }
}
