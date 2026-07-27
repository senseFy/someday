package saien.someday.sync.webdav

class WebDavPathResolver(
    appDirectory: String,
) {
    private val rootSegments: List<String> =
        appDirectory
            .trim()
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .map { safeSegment(it) }
            .ifEmpty { listOf("someday") }

    val root: String = rootSegments.joinToString(separator = "/", postfix = "/")

    /** App-owned V2 sync tree root (`log-v2/`). */
    fun v2RootDirectory(): String = collectionPath("log-v2")

    fun v2EpochPointer(): String = objectPath("log-v2", "control", "epoch-pointer.enc")

    fun v2RetainedEpochPointer(epochId: String): String = objectPath(
        "log-v2", "control", "pointer-history", "${safeSegment(epochId)}.enc",
    )

    fun v2CheckpointChunk(
        epochId: String,
        checkpointId: String,
        chunkIndex: Int,
        chunkId: String,
    ): String = objectPath(
        "log-v2",
        "epochs",
        safeSegment(epochId),
        "checkpoints",
        safeSegment(checkpointId),
        "chunks",
        "${chunkIndex.toString().padStart(6, '0')}-${safeSegment(chunkId)}.enc",
    )

    fun v2CheckpointManifest(
        epochId: String,
        checkpointId: String,
    ): String = objectPath(
        "log-v2",
        "epochs",
        safeSegment(epochId),
        "checkpoints",
        safeSegment(checkpointId),
        "manifest.enc",
    )

    fun v2LogManifestDirectory(epochId: String): String = collectionPath(
        "log-v2",
        "epochs",
        safeSegment(epochId),
        "manifests",
    )

    fun v2LogManifest(epochId: String, writerDeviceId: String): String = objectPath(
        "log-v2",
        "epochs",
        safeSegment(epochId),
        "manifests",
        "${safeSegment(writerDeviceId)}.enc",
    )

    fun v2LogSegment(
        epochId: String,
        writerDeviceId: String,
        ordinal: Long,
        segmentId: String,
    ): String = objectPath(
        "log-v2",
        "epochs",
        safeSegment(epochId),
        "logs",
        logShard(writerDeviceId),
        safeSegment(writerDeviceId),
        "${ordinal.toString().padStart(12, '0')}-${safeSegment(segmentId)}.enc",
    )

    fun v2RepairDirectory(epochId: String, objectId: String): String = collectionPath(
        "log-v2", "epochs", safeSegment(epochId), "repairs", logShard(objectId), safeSegment(objectId),
    )

    fun v2RepairReplica(epochId: String, objectId: String, repairWriterDeviceId: String): String = objectPath(
        "log-v2", "epochs", safeSegment(epochId), "repairs", logShard(objectId), safeSegment(objectId),
        "${safeSegment(repairWriterDeviceId)}.enc",
    )

    fun pairingInviteDirectory(): String = collectionPath("workspace-pairing", "1")

    fun pairingInvite(inviteId: String): String =
        objectPath("workspace-pairing", "1", "${safeSegment(inviteId)}.json.enc")

    fun parentDirectories(path: String): List<String> {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }.dropLast(1)
        if (segments.isEmpty()) {
            return emptyList()
        }
        return segments.runningFold("") { prefix, segment ->
            if (prefix.isBlank()) "$segment/" else "$prefix$segment/"
        }.drop(1)
    }

    fun relativePath(path: String): String? {
        val trimmed = path.trim().trimStart('/')
        val marker = root.trimStart('/')
        val index = trimmed.indexOf(marker)
        val relativePath = if (index >= 0) trimmed.substring(index) else trimmed
        return relativePath.takeIf { it == root.trimEnd('/') || it.startsWith(root) }
    }

    private fun objectPath(vararg segments: String): String =
        root + segments.joinToString(separator = "/")

    private fun collectionPath(vararg segments: String): String =
        objectPath(*segments).trimEnd('/') + "/"

    private fun logShard(value: String): String =
        stableWebDavShardHash("log-shard", value).take(2)
}

internal fun safeSegment(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotBlank()) { "WebDAV path segment must not be blank." }
    require(trimmed != "." && trimmed != ".." && !trimmed.contains('/')) {
        "WebDAV path segment must stay inside the app-owned directory."
    }
    return buildString {
        trimmed.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.'
            ) {
                append(char)
            } else {
                append('%')
                append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

internal fun stableWebDavShardHash(vararg parts: String): String {
    var hash = -0x340d631b7bdddcdbL
    parts.forEach { part ->
        part.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toLong()
            hash *= 0x100000001b3L
        }
        hash = hash xor 0xff
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
