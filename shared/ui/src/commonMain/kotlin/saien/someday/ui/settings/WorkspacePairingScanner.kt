package saien.someday.ui.settings

interface WorkspacePairingScanner {
    val available: Boolean

    /**
     * Starts camera capture only after an explicit user action. Implementations
     * must return decoded text verbatim and must never open it as a URL.
     */
    fun scan(
        onResult: (String) -> Unit,
        onCancelled: () -> Unit = {},
    )
}

object UnavailableWorkspacePairingScanner : WorkspacePairingScanner {
    override val available: Boolean = false

    override fun scan(
        onResult: (String) -> Unit,
        onCancelled: () -> Unit,
    ) {
        onCancelled()
    }
}
