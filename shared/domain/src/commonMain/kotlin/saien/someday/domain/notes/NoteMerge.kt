package saien.someday.domain.notes

import kotlin.time.Instant

data class NoteMergeSnapshot(
    val versionId: String?,
    val title: String,
    val markdownBody: String,
    val createdAt: Instant,
    val timeZoneId: String? = null,
)

enum class NoteMergeField {
    Title,
    MarkdownBody,
    CreatedAt,
    TimeZone,
}

data class NoteMergeResult(
    val title: String,
    val markdownBody: String,
    val createdAt: Instant,
    val timeZoneId: String?,
    val conflicts: List<NoteMergeField>,
) {
    val autoMerged: Boolean = conflicts.isEmpty()
}

object NoteThreeWayMerger {
    fun merge(
        base: NoteMergeSnapshot,
        local: NoteMergeSnapshot,
        remote: NoteMergeSnapshot,
    ): NoteMergeResult {
        val conflicts = mutableListOf<NoteMergeField>()
        val title = mergeScalar(
            base = base.title,
            local = local.title,
            remote = remote.title,
            field = NoteMergeField.Title,
            conflicts = conflicts,
        )
        val markdownBody = mergeMarkdownBody(
            base = base.markdownBody,
            local = local.markdownBody,
            remote = remote.markdownBody,
            conflicts = conflicts,
        )
        val createdAt = mergeScalar(
            base = base.createdAt,
            local = local.createdAt,
            remote = remote.createdAt,
            field = NoteMergeField.CreatedAt,
            conflicts = conflicts,
        )
        val timeZoneId = mergeScalar(
            base = base.timeZoneId,
            local = local.timeZoneId,
            remote = remote.timeZoneId,
            field = NoteMergeField.TimeZone,
            conflicts = conflicts,
        )

        return NoteMergeResult(
            title = title,
            markdownBody = markdownBody,
            createdAt = createdAt,
            timeZoneId = timeZoneId,
            conflicts = conflicts.distinct(),
        )
    }

    private fun <T> mergeScalar(
        base: T,
        local: T,
        remote: T,
        field: NoteMergeField,
        conflicts: MutableList<NoteMergeField>,
    ): T =
        when {
            local == remote -> local
            local == base -> remote
            remote == base -> local
            else -> {
                conflicts += field
                local
            }
        }

    private fun mergeMarkdownBody(
        base: String,
        local: String,
        remote: String,
        conflicts: MutableList<NoteMergeField>,
    ): String =
        when {
            local == remote -> local
            local == base -> remote
            remote == base -> local
            canCombineAppends(base, local, remote) -> combineAppends(base, local, remote)
            else -> {
                conflicts += NoteMergeField.MarkdownBody
                local
            }
        }

    private fun canCombineAppends(
        base: String,
        local: String,
        remote: String,
    ): Boolean =
        base.isNotEmpty() &&
            local.startsWith(base) &&
            remote.startsWith(base) &&
            local.removePrefix(base).isNotBlank() &&
            remote.removePrefix(base).isNotBlank()

    private fun combineAppends(
        base: String,
        local: String,
        remote: String,
    ): String {
        val localSuffix = local.removePrefix(base)
        val remoteSuffix = remote.removePrefix(base)
        if (localSuffix == remoteSuffix) {
            return local
        }
        return buildString {
            append(base)
            append(localSuffix)
            if (!endsWithLineBreak() && remoteSuffix.isNotBlank()) {
                append('\n')
            }
            append(remoteSuffix.trimStart('\n'))
        }
    }

    private fun StringBuilder.endsWithLineBreak(): Boolean =
        isNotEmpty() && last() == '\n'
}
