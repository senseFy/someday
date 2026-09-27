package saien.someday.data.importing.dayone

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import saien.someday.data.export.LocalDataExportDocument
import saien.someday.data.export.LocalDataImportSummary
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.media.SomedayAssetUri
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DayOneImportContractTest {
    private val image = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )
    private val digest = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
    private val uri = SomedayAssetUri(MediaAssetId.fromCanonicalValue("a".repeat(64)))

    @Test
    fun importsMatchingImageOnceButPreservesBothTextPlacementsAndOtherContent() {
        var writes = 0
        var document: LocalDataExportDocument? = null
        val service = DayOneImportService(importPhoto = { bytes, name ->
            writes++
            assertContentEquals(image, bytes)
            assertEquals("$digest.png", name)
            uri
        }) { converted -> document = converted; summary(converted) }
        val entry = entry(
            text = "Before\n\n![](dayone-moment://photo-a)\n\nBetween\n\n![again](dayone-moment://photo-a)\n\nAfter",
            photos = listOf(photo("photo-a")),
        )
        val result = service.importArchive(dayOneZip(
            "Export/Journal.json" to journal(entry), "Export/photos/$digest.png" to image, sizes64 = true,
        ))
        assertEquals("Before\n\n![Photo]($uri)\n\nBetween\n\n![Photo]($uri)\n\nAfter", document!!.notes.single().markdownBody)
        assertEquals(1, writes)
        assertEquals(1, result.photosImported)
        assertEquals(1, result.photosReferenced)
        assertEquals(0, result.photosMissing + result.photosUnresolved + result.photosRejected)
    }

    @Test
    fun textOnlyExportPreservesTextAndReportsMissingAndOrphanedAttachments() {
        var document: LocalDataExportDocument? = null
        val result = DayOneImportService { converted -> document = converted; summary(converted) }
            .importArchive(dayOneZip("Journal.json" to journal(entry(
                text = "Before\n\n![](dayone-moment://photo-a)\n\nAfter\n\n![](dayone-moment://orphan)",
                photos = listOf(photo("photo-a")),
            ))))
        assertEquals(
            "Before\n\n[Photo: photo-a — file not included]\n\nAfter\n\n[Photo: orphan — unresolved reference]",
            document!!.notes.single().markdownBody,
        )
        assertEquals(1, result.photosMissing)
        assertEquals(1, result.photosUnresolved)
        assertEquals(0, result.photosImported)
        assertEquals(1, result.notesCreated)
    }

    @Test
    fun omittedAttachmentMetadataAndRejectedImageStillPreserveText() {
        val (document, result) = convert(entry(
            text = "Kept", photos = listOf(JsonObject(photo("photo-a") - "type")),
        ))
        assertEquals("Kept\n\n[Photo: photo-a — unresolved reference]", document.notes.single().markdownBody)
        assertEquals(1, result.photosUnresolved)
        assertEquals(0, result.photosRejected)

        var converted: LocalDataExportDocument? = null
        val rejected = DayOneImportService(importPhoto = { _, _ -> null }) { converted = it; summary(it) }
            .importArchive(dayOneZip(
                "Journal.json" to journal(entry(text = "Kept", photos = listOf(photo("photo-a")))),
                "photos/$digest.png" to image,
            ))
        assertEquals("Kept\n\n[Photo: photo-a — unsupported or invalid image]", converted!!.notes.single().markdownBody)
        assertEquals(1, rejected.photosRejected)
        assertEquals(1, rejected.notesCreated)
    }

    @Test
    fun mismatchedHashCorruptImageAndUnrelatedFilesNeverReachImageStorage() {
        for ((metadataDigest, bytes, expectedMissing, expectedRejected) in listOf(
            Quadruple("b".repeat(32), image, 0, 1), // expected path, wrong bytes
            Quadruple(digest, byteArrayOf(1, 2, 3), 0, 1),
            Quadruple("c".repeat(32), null, 1, 0),
        )) {
            var document: LocalDataExportDocument? = null
            val files = mutableListOf("Journal.json" to journal(entry(photos = listOf(photo("photo-a", metadataDigest)))))
            if (bytes != null) files += "photos/$metadataDigest.png" to bytes
            files += "photos/unrelated.png" to image
            val result = DayOneImportService(importPhoto = { _, _ -> error("Must not import an unverified image") }) {
                document = it; summary(it)
            }.importArchive(dayOneZip(*files.toTypedArray()))
            assertNotNull(document)
            assertEquals(expectedMissing, result.photosMissing)
            assertEquals(expectedRejected, result.photosRejected)
            assertEquals(0, result.photosImported)
            assertFalse(document.notes.single().markdownBody.contains("someday-asset://"))
        }
    }

    @Test
    fun richTextHonorsHeaderZeroInlineRunsWhitespaceAndChecklistInsteadOfFallbackText() {
        val rich = """{"meta":{"version":1},"contents":[
          {"text":"Title ","attributes":{"line":{"header":2}}},
          {"text":"bold","attributes":{"bold":true,"line":{"header":2}}},
          {"text":"\nBody ","attributes":{"line":{"header":0}}},
          {"text":" padded ","attributes":{"italic":true}},
          {"text":"tail\n"},
          {"text":"done\n","attributes":{"line":{"header":0,"checked":true,"indentLevel":1}}},
          {"text":"child","attributes":{"line":{"listStyle":"bulleted","indentLevel":2}}}
        ]}"""
        val (document, result) = convert(entry(text = "NOT THE BODY", rich = rich))
        assertEquals("## Title **bold**\nBody  *padded* tail\n- [x] done\n  - child", document.notes.single().markdownBody)
        assertEquals(1, result.richTextConverted)
        assertEquals(0, result.richTextFallbacks)
    }

    @Test
    fun fallsBackForMalformedOrFutureRichTextButDoesNotSilentlyLoseOnlyBody() {
        for (rich in listOf(
            "invalid", """{"meta":{"version":2},"contents":[{"text":"new format"}]}""",
            """{"meta":{"version":"future"},"contents":[{"text":"new format"}]}""", """{"contents":[{"text":{}}]}""",
        )) {
            val (document, result) = convert(entry(text = "# Original Markdown\n\nKept", rich = rich))
            assertEquals("# Original Markdown\n\nKept", document.notes.single().markdownBody)
            assertEquals(1, result.richTextFallbacks)
        }
        assertFailsWith<IllegalArgumentException> { convert(entry(rich = "invalid")) }
    }

    @Test
    fun inlinePhotosBecomeStandalonePreviewBlocksAndAmbiguousIdentifiersNeverMatch() {
        var document: LocalDataExportDocument? = null
        val service = DayOneImportService(importPhoto = { _, _ -> uri }) { document = it; summary(it) }
        val result = service.importArchive(dayOneZip(
            "Journal.json" to journal(entry(text = "Before![](dayone-moment://photo-a)After", photos = listOf(photo("photo-a")))),
            "photos/$digest.png" to image,
        ))
        assertEquals("Before\n![Photo]($uri)\nAfter", document!!.notes.single().markdownBody)
        assertEquals(1, result.photosImported)
        for (ambiguous in listOf(
            entry(photos = listOf(photo("same"), photo("same", "b".repeat(32)))),
            entry(text = "![](dayone-moment://same)", photos = listOf(photo("same")), audios = listOf(photo("same"))),
        )) {
            val rejected = service.importArchive(dayOneZip("Journal.json" to journal(ambiguous), "photos/$digest.png" to image))
            assertEquals(0, rejected.photosImported)
            assertEquals(1, rejected.photosUnresolved)
        }
    }

    @Test
    fun unclosedFencesAndNestedBackticksRemainLiteralButFollowingLinksAreConverted() {
        for (literal in listOf(
            "``a ` ![](dayone-moment://example) b``",
            "~~~text\n![](dayone-moment://example)\n~~~~",
            "```text\n![](dayone-moment://example)",
            "\\[example](dayone-moment://example)",
        )) {
            val (document, result) = convert(entry(text = literal))
            assertEquals(literal, document.notes.single().markdownBody)
            assertEquals(0, result.photosReferenced)
        }
        val (document, result) = convert(entry(text = "`code` ![](dayone-moment://orphan)"))
        assertEquals("`code` [Photo: orphan — unresolved reference]", document.notes.single().markdownBody)
        assertEquals(1, result.photosUnresolved)
    }

    @Test
    fun preservesCodeAndAppendsUnplacedAttachmentsByOrderWithoutImportingAudio() {
        val code = "`![](dayone-moment://example)`\n\n```\n![](dayone-moment://example)\n```"
        val (document, result) = convert(entry(
            text = code,
            photos = listOf(photo("second", order = 2), photo("first", order = 0)),
            audios = listOf(buildJsonObject { put("identifier", "audio-a"); put("orderInEntry", 1) }),
        ))
        assertEquals(
            "$code\n\n[Photo: first — file not included]\n\n[Audio: audio-a — unsupported attachment]\n\n[Photo: second — file not included]",
            document.notes.single().markdownBody,
        )
        assertEquals(2, result.photosReferenced)
        assertEquals(1, result.audiosReferenced)
        assertEquals(0, result.photosUnresolved)
    }

    @Test
    fun richEmbeddedImageUsesItsPositionAndMissingAudioIsNonfatal() {
        var document: LocalDataExportDocument? = null
        val rich = """{"contents":[{"text":"Before\n"},{"embeddedObjects":[{"type":"photo","identifier":"photo-a"}]},{"text":"After"}]}"""
        val result = DayOneImportService(importPhoto = { _, _ -> uri }) { document = it; summary(it) }
            .importArchive(dayOneZip(
                "Journal.json" to journal(entry(
                    text = "do not append fallback", rich = rich, photos = listOf(photo("photo-a")),
                    audios = listOf(buildJsonObject { put("identifier", "audio-a") }),
                )), "photos/$digest.png" to image,
            ))
        assertEquals("Before\n![Photo]($uri)\nAfter\n\n[Audio: audio-a — unsupported attachment]", document!!.notes.single().markdownBody)
        assertEquals(1, result.photosImported)
        assertEquals(1, result.audiosReferenced)
    }

    @Test
    fun invalidDatesVersionsDuplicatesAndStorageFailureDoNotReachNoteImporter() {
        var noteWrites = 0
        val service = DayOneImportService(importPhoto = { _, _ -> error("storage unavailable") }) {
            noteWrites++; summary(it)
        }
        val valid = entry(text = "safe")
        for (bytes in listOf(
            journal(JsonObject(valid + ("creationDate" to JsonPrimitive("private-invalid-date")))),
            journal(valid, valid),
            journal(valid).decodeToString().replace("\"1.0\"", "\"99.0\"").encodeToByteArray(),
            """{"entries":[{"private":"secret diary text"}]}""".encodeToByteArray(),
        )) {
            val failure = assertFailsWith<IllegalArgumentException> {
                service.importArchive(dayOneZip("Journal.json" to bytes))
            }
            assertFalse(failure.toString().contains("private"))
            assertFalse(failure.toString().contains("secret diary text"))
        }
        assertFailsWith<IllegalStateException> {
            service.importArchive(dayOneZip("Journal.json" to journal(entry(photos = listOf(photo("photo-a")))), "photos/$digest.png" to image))
        }
        assertEquals(0, noteWrites)
    }

    @Test
    fun blankEntryAndMissingModifiedDateAreDeterministicAndKeepTimezoneDate() {
        val (first, _) = convert(entry())
        val (second, _) = convert(entry())
        assertEquals(first, second)
        val note = first.notes.single()
        assertEquals("Day One 2026-05-02", note.title)
        assertEquals(note.createdAt, note.updatedAt)
        assertEquals("Asia/Shanghai", note.timeZoneId)
    }

    private fun convert(entry: JsonObject): Pair<LocalDataExportDocument, DayOneImportSummary> {
        var document: LocalDataExportDocument? = null
        val result = DayOneImportService { document = it; summary(it) }
            .importArchive(dayOneZip("Journal.json" to journal(entry)))
        return assertNotNull(document) to result
    }

    private fun photo(id: String, hash: String = digest, order: Int = 0): JsonObject = buildJsonObject {
        put("identifier", id); put("md5", hash); put("type", "png"); put("orderInEntry", order)
    }

    private fun entry(
        text: String? = null,
        rich: String? = null,
        photos: List<JsonObject> = emptyList(),
        audios: List<JsonObject> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("uuid", "00000000000000000000000000000001")
        put("creationDate", "2026-05-01T16:30:00Z")
        put("timeZone", "Asia/Shanghai")
        text?.let { put("text", it) }
        rich?.let { put("richText", it) }
        put("photos", JsonArray(photos)); put("audios", JsonArray(audios))
    }

    private fun journal(vararg entries: JsonObject): ByteArray = buildJsonObject {
        put("metadata", buildJsonObject { put("version", "1.0") })
        put("entries", JsonArray(entries.toList()))
    }.toString().encodeToByteArray()

    private fun summary(document: LocalDataExportDocument) = LocalDataImportSummary(
        notebooksCreated = document.notebooks.size, notebooksReused = 0, notesCreated = document.notes.size, notesSkipped = 0,
    )

    private data class Quadruple(val hash: String, val bytes: ByteArray?, val missing: Int, val rejected: Int)
}
