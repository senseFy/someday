@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.crypto

interface SecureWorkspaceKeyStore {
    fun put(
        alias: String,
        workspaceKey: WorkspaceMasterKey,
    )

    fun get(alias: String): WorkspaceMasterKey?

    fun remove(alias: String)

    fun contains(alias: String): Boolean = get(alias) != null
}

class InMemorySecureWorkspaceKeyStore : SecureWorkspaceKeyStore {
    private val keys = mutableMapOf<String, WorkspaceMasterKey>()

    override fun put(
        alias: String,
        workspaceKey: WorkspaceMasterKey,
    ) {
        require(alias.isNotBlank()) { "Secure storage alias must not be blank." }
        keys[alias] = workspaceKey.copy()
    }

    override fun get(alias: String): WorkspaceMasterKey? = keys[alias]?.copy()

    override fun remove(alias: String) {
        keys.remove(alias)
    }

    fun containsAny(): Boolean = keys.isNotEmpty()
}

fun interface SecureStorageAliasGenerator {
    fun newAlias(workspaceId: String): String
}

class TimeBasedSecureStorageAliasGenerator : SecureStorageAliasGenerator {
    override fun newAlias(workspaceId: String): String =
        "workspace-$workspaceId-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
}
