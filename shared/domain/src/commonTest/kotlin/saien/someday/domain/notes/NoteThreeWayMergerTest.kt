package saien.someday.domain.notes

import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteThreeWayMergerTest {
    @Test
    fun commonBaseMergeKeepsIndependentConcurrentEdits() {
        val base = NoteMergeSnapshot(
            versionId = "base-version",
            title = "Morning",
            markdownBody = "Coffee at home",
            createdAt = Instant.parse("2026-05-22T00:00:00Z"),
        )
        val local = base.copy(
            versionId = "local-version",
            title = "Morning walk",
        )
        val remote = base.copy(
            versionId = "remote-version",
            markdownBody = "Coffee at home\nRemote device added breakfast details.",
        )

        val result = NoteThreeWayMerger.merge(base = base, local = local, remote = remote)

        assertTrue(result.autoMerged)
        assertEquals("Morning walk", result.title)
        assertEquals("Coffee at home\nRemote device added breakfast details.", result.markdownBody)
        assertEquals(Instant.parse("2026-05-22T00:00:00Z"), result.createdAt)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun overlappingEditsRequireConflictCopyInsteadOfTimestampWinner() {
        val base = NoteMergeSnapshot(
            versionId = "base-version",
            title = "Morning",
            markdownBody = "Coffee at home",
            createdAt = Instant.parse("2026-05-22T00:00:00Z"),
        )
        val local = base.copy(
            versionId = "local-version",
            markdownBody = "Local rewrite of the same paragraph.",
        )
        val remote = base.copy(
            versionId = "remote-version",
            markdownBody = "Remote rewrite of the same paragraph.",
        )

        val result = NoteThreeWayMerger.merge(base = base, local = local, remote = remote)

        assertFalse(result.autoMerged)
        assertEquals(listOf(NoteMergeField.MarkdownBody), result.conflicts)
    }
}
