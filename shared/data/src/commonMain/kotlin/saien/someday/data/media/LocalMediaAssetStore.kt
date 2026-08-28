@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.data.media

import saien.someday.data.crypto.SodiumWorkspaceCrypto
import saien.someday.data.local.db.SomedayDatabase
import saien.someday.domain.media.MediaAssetId
import saien.someday.domain.media.MediaAssetMetadata
import saien.someday.domain.media.canonicalMediaTypeOrNull
import saien.someday.domain.media.isSafeOriginalFileName
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import okio.Buffer
import okio.BufferedSink
import okio.FileMetadata
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

const val DEFAULT_MEDIA_IMPORT_MAX_BYTES: Long = 4L * 1_024L * 1_024L
const val MAX_MEDIA_IMPORT_BOUND_BYTES: Long = DEFAULT_MEDIA_IMPORT_MAX_BYTES

private const val MEDIA_STORAGE_DIRECTORY: String = "media-v1"
private const val OBJECTS_DIRECTORY: String = "objects"
private const val STAGING_DIRECTORY: String = ".staging"
private const val OBJECT_FILE_SUFFIX: String = ".blob"
private const val COPY_BUFFER_BYTES: Long = 8L * 1_024L
private const val MAX_ASSET_ID_ATTEMPTS: Int = 16
private const val SHA256_HEX_LENGTH: Int = 64

enum class MediaAssetLocalState(
    val storageValue: String,
) {
    Available("available"),
    Missing("missing"),
    Corrupt("corrupt"),
    ;

    companion object {
        fun fromStorageValue(value: String): MediaAssetLocalState =
            entries.firstOrNull { it.storageValue == value }
                ?: error("Unknown media asset local state: $value")
    }
}

data class LocalMediaAsset(
    val metadata: MediaAssetMetadata,
    val contentSha256: String,
    val localState: MediaAssetLocalState,
    val lastVerifiedAt: Instant?,
    /** Account/workspace on which the immutable encrypted object was last confirmed. */
    val publishedAuthorityBindingId: String?,
    val publishedWorkspaceId: String?,
    val publishedObjectDigest: String?,
)

/**
 * Bounds and verifies one immutable original. A local import has no remote
 * publication evidence by default. Materialization from an authenticated
 * authority records that binding and the completing manifest digest directly.
 */
data class MediaAssetImportRequest(
    /** Optional trusted hint; the canonical type is always detected from bytes. */
    val mediaType: String? = null,
    val originalFileName: String? = null,
    val maxBytes: Long = DEFAULT_MEDIA_IMPORT_MAX_BYTES,
    val expectedByteSize: Long? = null,
    val expectedContentSha256: String? = null,
    val expectedAssetId: MediaAssetId? = null,
    val expectedPixelWidth: Int? = null,
    val expectedPixelHeight: Int? = null,
    val maxDecodedPixelCount: Long = DEFAULT_MAX_DECODED_PIXEL_COUNT,
    val publishedAuthorityBindingId: String? = null,
    val publishedWorkspaceId: String? = null,
    val publishedObjectDigest: String? = null,
) {
    init {
        require(mediaType == null || canonicalMediaTypeOrNull(mediaType) == mediaType) {
            "Media type must be a canonical lowercase type/subtype without parameters."
        }
        require(originalFileName == null || isSafeOriginalFileName(originalFileName)) {
            "Original file name must be a safe basename of at most 255 characters."
        }
        require(maxBytes in 1L..MAX_MEDIA_IMPORT_BOUND_BYTES) {
            "Media import maxBytes must be between 1 and $MAX_MEDIA_IMPORT_BOUND_BYTES."
        }
        require(expectedByteSize == null || expectedByteSize in 1L..maxBytes) {
            "Expected media size must be positive and no larger than maxBytes."
        }
        require(expectedContentSha256 == null || expectedContentSha256.isCanonicalSha256()) {
            "Expected media SHA-256 must be 64 lowercase hexadecimal characters."
        }
        require(expectedPixelWidth == null || expectedPixelWidth > 0) {
            "Expected image width must be positive."
        }
        require(expectedPixelHeight == null || expectedPixelHeight > 0) {
            "Expected image height must be positive."
        }
        require((expectedPixelWidth == null) == (expectedPixelHeight == null)) {
            "Expected image width and height must be supplied together."
        }
        require(
            expectedPixelWidth == null ||
                expectedPixelWidth.toLong() * checkNotNull(expectedPixelHeight) <= maxDecodedPixelCount,
        ) { "Expected image dimensions exceed the decoded pixel limit." }
        require(maxDecodedPixelCount in 1L..MAX_DECODED_PIXEL_COUNT_BOUND) {
            "Decoded pixel limit must be between 1 and $MAX_DECODED_PIXEL_COUNT_BOUND."
        }
        require(publishedWorkspaceId == null || publishedWorkspaceId.isCanonicalWorkspaceId()) {
            "Published workspace id must be canonical."
        }
        require(publishedObjectDigest == null || publishedObjectDigest.isSafeObjectDigest()) {
            "Published object digest must be a bounded, non-blank scalar."
        }
        require(publishedAuthorityBindingId == null || publishedAuthorityBindingId.isSafeAuthorityBindingId()) {
            "Published authority binding must be a bounded, non-blank scalar."
        }
        require((publishedAuthorityBindingId == null) == (publishedWorkspaceId == null) &&
            (publishedAuthorityBindingId == null) == (publishedObjectDigest == null)
        ) {
            "Published account, workspace, and object evidence must be recorded together."
        }
    }
}

data class MediaAssetImportResult(
    val asset: LocalMediaAsset,
    val reusedExistingAsset: Boolean,
)

sealed interface MediaAssetVerificationResult {
    val asset: LocalMediaAsset

    data class Verified(
        override val asset: LocalMediaAsset,
    ) : MediaAssetVerificationResult

    data class Missing(
        override val asset: LocalMediaAsset,
    ) : MediaAssetVerificationResult

    data class Corrupt(
        override val asset: LocalMediaAsset,
        val observedByteSize: Long?,
        val observedContentSha256: String?,
    ) : MediaAssetVerificationResult
}

data class MediaAssetCleanupResult(
    val temporaryFilesRemoved: Int,
    val orphanObjectFilesRemoved: Int,
    val assetsMarkedMissing: Int,
    val assetsMarkedCorrupt: Int,
)

/**
 * Creates an opaque asset id for newly observed content. Implementations may use
 * workspace-keyed addressing. They must return canonical 256-bit ids and must not
 * expose the bare content digest across workspace boundaries.
 */
fun interface MediaAssetAddressingStrategy {
    fun createAssetId(contentSha256: String): MediaAssetId
}

/** Production-safe default: content equality is not disclosed by the public asset id. */
class RandomMediaAssetAddressingStrategy(
    private val secureRandomBytes: (Int) -> ByteArray = ::mediaSecureRandomBytes,
) : MediaAssetAddressingStrategy {
    override fun createAssetId(contentSha256: String): MediaAssetId {
        require(contentSha256.isCanonicalSha256()) { "Content SHA-256 must be canonical." }
        return MediaAssetId.fromCanonicalValue(secureRandomBytes(32).toHex())
    }
}

open class LocalMediaAssetStoreException(message: String) : IllegalStateException(message)

class MediaAssetImportTooLargeException(
    val maxBytes: Long,
) : LocalMediaAssetStoreException("Media input exceeds the configured $maxBytes-byte limit.")

class MediaAssetIntegrityException(message: String) : LocalMediaAssetStoreException(message)

class MediaAssetNotFoundException(assetId: MediaAssetId) :
    LocalMediaAssetStoreException("Media asset does not exist: $assetId")

class MediaAssetIdentityConflictException(message: String) : LocalMediaAssetStoreException(message)

/**
 * Blocking, app-private storage for immutable original media bytes.
 *
 * Callers own [Source] lifecycle and must invoke file operations on a background
 * dispatcher. The staging and object directories intentionally share one root
 * so [FileSystem.atomicMove] never crosses volumes.
 */
class LocalMediaAssetStore(
    private val database: SomedayDatabase,
    appPrivateRoot: Path,
    private val decodeValidator: MediaAssetDecodeValidator,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val addressingStrategy: MediaAssetAddressingStrategy = RandomMediaAssetAddressingStrategy(),
    private val inspector: MediaAssetInspector = StaticImageMediaAssetInspector,
    private val clock: () -> Instant = { Clock.System.now() },
    private val orphanGracePeriod: Duration = 24.hours,
) {
    private val queries = database.somedayQueries
    private val mediaRoot = appPrivateRoot.resolve(MEDIA_STORAGE_DIRECTORY)
    private val objectsRoot = mediaRoot.resolve(OBJECTS_DIRECTORY)
    private val stagingRoot = mediaRoot.resolve(STAGING_DIRECTORY)

    init {
        require(!orphanGracePeriod.isNegative()) { "Orphan grace period must not be negative." }
    }

    fun getAsset(assetId: MediaAssetId): LocalMediaAsset? =
        queries.selectMediaAsset(assetId.value, ::mapMediaAsset).executeAsOneOrNull()

    fun listAssets(): List<LocalMediaAsset> =
        queries.selectAllMediaAssets(::mapMediaAsset).executeAsList()

    /** Assets not yet confirmed in this exact authenticated account/workspace scope. */
    fun listAssetsPendingPublication(authorityBindingId: String, workspaceId: String): List<LocalMediaAsset> {
        require(authorityBindingId.isSafeAuthorityBindingId()) { "Authority binding is invalid." }
        require(workspaceId.isCanonicalWorkspaceId()) { "Workspace id is invalid." }
        return queries.selectMediaAssetsPendingForAuthority(authorityBindingId, workspaceId, ::mapMediaAsset)
            .executeAsList()
    }

    /**
     * Monotonically records that remote media publication completed. Repeating
     * the same transition is safe; a conflicting object digest is rejected.
     */
    fun markPublished(
        assetId: MediaAssetId,
        authorityBindingId: String,
        workspaceId: String,
        objectDigest: String,
    ): LocalMediaAsset {
        require(authorityBindingId.isSafeAuthorityBindingId()) { "Authority binding is invalid." }
        require(workspaceId.isCanonicalWorkspaceId()) { "Workspace id is invalid." }
        require(objectDigest.isSafeObjectDigest()) {
            "Published object digest must be a bounded, non-blank scalar."
        }
        var result: LocalMediaAsset? = null
        database.transaction {
            val current = getAsset(assetId) ?: throw MediaAssetNotFoundException(assetId)
            ensureCompatibleObjectDigest(current, authorityBindingId, workspaceId, objectDigest)
            queries.markMediaAssetPublished(
                published_authority_binding_id = authorityBindingId,
                published_workspace_id = workspaceId,
                published_object_digest = objectDigest,
                asset_id = assetId.value,
            )
            result = checkNotNull(getAsset(assetId))
        }
        return checkNotNull(result)
    }

    /**
     * Streams [source] into a bounded staging file, flushes it, then atomically
     * promotes it. The caller retains ownership of [source] and must close it.
     */
    fun importAsset(
        source: Source,
        request: MediaAssetImportRequest,
    ): MediaAssetImportResult = importAsset(request) { sink ->
        sink.writeAll(source)
    }

    /**
     * Streams bytes produced by [write] into the same verified import path.
     * This lets network materialization remain bounded without exposing temp
     * files or buffering the complete original. [write] must not close [BufferedSink].
     */
    fun importAsset(
        request: MediaAssetImportRequest,
        write: (BufferedSink) -> Unit,
    ): MediaAssetImportResult {
        ensureStorageDirectories()
        val temporaryPath = newTemporaryPath()
        try {
            val staged = writeStagedObject(temporaryPath, request.maxBytes, write)
            request.expectedByteSize?.let { expected ->
                if (staged.byteSize != expected) {
                    throw MediaAssetIntegrityException(
                        "Media input size ${staged.byteSize} does not match expected size $expected.",
                    )
                }
            }
            request.expectedContentSha256?.let { expected ->
                if (staged.contentSha256 != expected) {
                    throw MediaAssetIntegrityException("Media input SHA-256 does not match the expected digest.")
                }
            }
            val inspection = inspectStagedObject(temporaryPath, staged.byteSize, request)

            val existingExpectedAsset = request.expectedAssetId?.let(::getAsset)
            if (existingExpectedAsset != null && existingExpectedAsset.contentSha256 != staged.contentSha256) {
                throw MediaAssetIdentityConflictException(
                    "Expected asset id is already bound to different content.",
                )
            }
            val existingByContent = assetByContentSha256(staged.contentSha256)
            val existingBeforeCommit = existingExpectedAsset
                ?: existingByContent.takeIf { request.expectedAssetId == null }
            val intendedAssetId = existingBeforeCommit?.metadata?.id
                ?: request.expectedAssetId
                ?: allocateAssetId(staged.contentSha256)

            promoteStagedObject(temporaryPath, staged)

            val verifiedAt = clock()
            var stored: LocalMediaAsset? = null
            var reusedExisting = existingBeforeCommit != null
            database.transaction {
                val concurrentByIdentity = getAsset(intendedAssetId)
                val concurrentByContent = assetByContentSha256(staged.contentSha256)
                    ?.takeIf { request.expectedAssetId == null }
                val reusable = concurrentByIdentity ?: concurrentByContent
                if (reusable != null) {
                    if (reusable.contentSha256 != staged.contentSha256) {
                        throw MediaAssetIdentityConflictException(
                            "Asset id is already bound to different content.",
                        )
                    }
                    reusedExisting = true
                    updateLocalState(
                        reusable.metadata.id,
                        MediaAssetLocalState.Available,
                        verifiedAt,
                    )
                    applyRequestedPublication(reusable, request)
                    stored = checkNotNull(getAsset(reusable.metadata.id))
                    return@transaction
                }

                getAsset(intendedAssetId)?.let { conflicting ->
                    if (conflicting.contentSha256 != staged.contentSha256) {
                        throw MediaAssetIdentityConflictException(
                            "Generated asset id is already bound to different content.",
                        )
                    }
                }
                queries.insertMediaAsset(
                    asset_id = intendedAssetId.value,
                    content_sha256 = staged.contentSha256,
                    byte_size = staged.byteSize,
                    media_type = inspection.mediaType,
                    original_file_name = request.originalFileName,
                    pixel_width = inspection.pixelWidth.toLong(),
                    pixel_height = inspection.pixelHeight.toLong(),
                    created_at = verifiedAt.toEpochMilliseconds(),
                    local_state = MediaAssetLocalState.Available.storageValue,
                    last_verified_at = verifiedAt.toEpochMilliseconds(),
                    published_authority_binding_id = request.publishedAuthorityBindingId,
                    published_workspace_id = request.publishedWorkspaceId,
                    published_object_digest = request.publishedObjectDigest,
                )
                stored = getAsset(intendedAssetId)
                    ?: error("Media metadata insert did not produce a readable row.")
            }
            return MediaAssetImportResult(checkNotNull(stored), reusedExisting)
        } finally {
            runCatching { fileSystem.delete(temporaryPath, mustExist = false) }
        }
    }

    /** Opens an original only when its bounded local materialization is usable. */
    fun openSource(assetId: MediaAssetId): Source {
        var asset = getAsset(assetId) ?: throw MediaAssetNotFoundException(assetId)
        if (asset.localState != MediaAssetLocalState.Available) {
            asset = when (val verification = verifyAsset(assetId)) {
                is MediaAssetVerificationResult.Verified -> verification.asset
                is MediaAssetVerificationResult.Missing -> throw MediaAssetIntegrityException("Media asset file is missing.")
                is MediaAssetVerificationResult.Corrupt -> throw MediaAssetIntegrityException("Media asset file is corrupt.")
            }
        }
        val path = objectPath(asset.contentSha256)
        val file = fileSystem.metadataOrNull(path)
        if (file?.isRegularFile != true || file.size != asset.metadata.byteSize) {
            val state = if (file == null) MediaAssetLocalState.Missing else MediaAssetLocalState.Corrupt
            updateLocalState(assetId, state, clock())
            throw MediaAssetIntegrityException(
                if (state == MediaAssetLocalState.Missing) "Media asset file is missing." else "Media asset file has an invalid size.",
            )
        }
        return fileSystem.source(path)
    }

    /** Recomputes content identity without loading the original into memory. */
    fun verifyAsset(assetId: MediaAssetId): MediaAssetVerificationResult {
        val asset = getAsset(assetId) ?: throw MediaAssetNotFoundException(assetId)
        val path = objectPath(asset.contentSha256)
        val file = fileSystem.metadataOrNull(path)
        val verifiedAt = clock()
        if (file == null) {
            updateLocalState(assetId, MediaAssetLocalState.Missing, verifiedAt)
            return MediaAssetVerificationResult.Missing(checkNotNull(getAsset(assetId)))
        }
        if (!file.isRegularFile || file.size != asset.metadata.byteSize) {
            updateLocalState(assetId, MediaAssetLocalState.Corrupt, verifiedAt)
            return MediaAssetVerificationResult.Corrupt(
                asset = checkNotNull(getAsset(assetId)),
                observedByteSize = file.size,
                observedContentSha256 = null,
            )
        }

        val observed = hashObject(path, asset.metadata.byteSize)
        if (observed.byteSize != asset.metadata.byteSize || observed.contentSha256 != asset.contentSha256) {
            updateLocalState(assetId, MediaAssetLocalState.Corrupt, verifiedAt)
            return MediaAssetVerificationResult.Corrupt(
                asset = checkNotNull(getAsset(assetId)),
                observedByteSize = observed.byteSize,
                observedContentSha256 = observed.contentSha256,
            )
        }
        updateLocalState(assetId, MediaAssetLocalState.Available, verifiedAt)
        return MediaAssetVerificationResult.Verified(checkNotNull(getAsset(assetId)))
    }

    /**
     * Removes only old, unreferenced store-owned files. Fresh objects are left
     * alone so a concurrent rename-to-SQL-commit window is never collected.
     */
    fun cleanupOrphans(
        orphanedBefore: Instant = clock() - orphanGracePeriod,
    ): MediaAssetCleanupResult {
        var temporaryFilesRemoved = 0
        var orphanObjectFilesRemoved = 0
        var assetsMarkedMissing = 0
        var assetsMarkedCorrupt = 0

        fileSystem.listOrNull(stagingRoot).orEmpty().forEach { path ->
            val metadata = fileSystem.metadataOrNull(path) ?: return@forEach
            if (metadata.isRegularFile && metadata.isOldEnough(orphanedBefore)) {
                fileSystem.delete(path, mustExist = false)
                temporaryFilesRemoved++
            }
        }

        val assets = listAssets()
        val assetsByDigest = assets.associateBy(LocalMediaAsset::contentSha256)
        fileSystem.listRecursivelyOrEmpty(objectsRoot).forEach { path ->
            val metadata = fileSystem.metadataOrNull(path) ?: return@forEach
            if (!metadata.isRegularFile || !metadata.isOldEnough(orphanedBefore)) return@forEach
            val candidateDigest = path.name.removeSuffix(OBJECT_FILE_SUFFIX)
            val digest = candidateDigest.takeIf { it.isCanonicalSha256() }
            val expectedPath = digest?.let(::objectPath)
            if (digest == null || assetsByDigest[digest] == null || path != expectedPath) {
                fileSystem.delete(path, mustExist = false)
                orphanObjectFilesRemoved++
            }
        }

        val checkedAt = clock()
        assets.forEach { asset ->
            val metadata = fileSystem.metadataOrNull(objectPath(asset.contentSha256))
            when {
                metadata == null && asset.localState != MediaAssetLocalState.Missing -> {
                    updateLocalState(asset.metadata.id, MediaAssetLocalState.Missing, checkedAt)
                    assetsMarkedMissing++
                }
                metadata != null &&
                    (!metadata.isRegularFile || metadata.size != asset.metadata.byteSize) &&
                    asset.localState != MediaAssetLocalState.Corrupt -> {
                    updateLocalState(asset.metadata.id, MediaAssetLocalState.Corrupt, checkedAt)
                    assetsMarkedCorrupt++
                }
            }
        }

        return MediaAssetCleanupResult(
            temporaryFilesRemoved = temporaryFilesRemoved,
            orphanObjectFilesRemoved = orphanObjectFilesRemoved,
            assetsMarkedMissing = assetsMarkedMissing,
            assetsMarkedCorrupt = assetsMarkedCorrupt,
        )
    }

    /**
     * Removes all store-owned files that no longer have metadata. The caller
     * must guarantee that no media import is in flight, as startup and the
     * workspace lifecycle replacement lock do.
     */
    fun purgeUnreferencedFilesWithoutGracePeriod(): MediaAssetCleanupResult {
        var temporaryFilesRemoved = 0
        var orphanObjectFilesRemoved = 0
        fileSystem.listOrNull(stagingRoot).orEmpty().forEach { path ->
            if (fileSystem.metadataOrNull(path)?.isRegularFile == true) {
                fileSystem.delete(path, mustExist = false)
                temporaryFilesRemoved++
            }
        }

        val referencedDigests = listAssets().mapTo(mutableSetOf(), LocalMediaAsset::contentSha256)
        fileSystem.listRecursivelyOrEmpty(objectsRoot).forEach { path ->
            val metadata = fileSystem.metadataOrNull(path) ?: return@forEach
            if (!metadata.isRegularFile) return@forEach
            val digest = path.name.removeSuffix(OBJECT_FILE_SUFFIX)
                .takeIf { it.isCanonicalSha256() }
            if (digest == null || digest !in referencedDigests || path != objectPath(digest)) {
                fileSystem.delete(path, mustExist = false)
                orphanObjectFilesRemoved++
            }
        }
        return MediaAssetCleanupResult(
            temporaryFilesRemoved = temporaryFilesRemoved,
            orphanObjectFilesRemoved = orphanObjectFilesRemoved,
            assetsMarkedMissing = 0,
            assetsMarkedCorrupt = 0,
        )
    }

    private fun ensureStorageDirectories() {
        fileSystem.createDirectories(objectsRoot)
        fileSystem.createDirectories(stagingRoot)
    }

    private fun inspectStagedObject(
        path: Path,
        encodedByteSize: Long,
        request: MediaAssetImportRequest,
    ): MediaAssetInspection {
        val source = fileSystem.source(path).buffer()
        val inspection = try {
            inspector.inspect(
                source = source,
                encodedByteSize = encodedByteSize,
                declaredMediaType = request.mediaType,
                maxDecodedPixelCount = request.maxDecodedPixelCount,
            )
        } finally {
            source.close()
        }
        if (request.mediaType != null && inspection.mediaType != request.mediaType) {
            throw MediaAssetInspectionException("Inspected media type does not match the declared media type.")
        }
        if (inspection.decodedPixelCount > request.maxDecodedPixelCount) {
            throw MediaAssetInspectionException("Inspected image exceeds the decoded pixel limit.")
        }
        if (request.expectedPixelWidth != null &&
            (inspection.pixelWidth != request.expectedPixelWidth ||
                inspection.pixelHeight != request.expectedPixelHeight)
        ) {
            throw MediaAssetIntegrityException("Image dimensions do not match the authenticated manifest.")
        }
        validateDecodedObject(path, inspection)
        return inspection
    }

    private fun validateDecodedObject(
        path: Path,
        inspection: MediaAssetInspection,
    ) {
        val source = fileSystem.source(path).buffer()
        val decoded = try {
            decodeValidator.decode(source)
        } catch (failure: MediaAssetInspectionException) {
            throw failure
        } catch (_: Exception) {
            throw MediaAssetInspectionException("Encoded image could not be fully decoded.")
        } finally {
            source.close()
        }
        if (decoded.pixelWidth != inspection.pixelWidth || decoded.pixelHeight != inspection.pixelHeight) {
            throw MediaAssetInspectionException("Decoded image dimensions do not match its encoded metadata.")
        }
    }

    private fun applyRequestedPublication(
        current: LocalMediaAsset,
        request: MediaAssetImportRequest,
    ) {
        val authorityBindingId = request.publishedAuthorityBindingId ?: return
        val workspaceId = checkNotNull(request.publishedWorkspaceId)
        val objectDigest = checkNotNull(request.publishedObjectDigest)
        ensureCompatibleObjectDigest(current, authorityBindingId, workspaceId, objectDigest)
        queries.markMediaAssetPublished(
            published_authority_binding_id = authorityBindingId,
            published_workspace_id = workspaceId,
            published_object_digest = objectDigest,
            asset_id = current.metadata.id.value,
        )
    }

    private fun ensureCompatibleObjectDigest(
        current: LocalMediaAsset,
        requestedAuthorityBindingId: String,
        requestedWorkspaceId: String,
        requestedObjectDigest: String?,
    ) {
        if (current.publishedAuthorityBindingId != requestedAuthorityBindingId ||
            current.publishedWorkspaceId != requestedWorkspaceId
        ) return
        val stored = current.publishedObjectDigest
        if (stored != null && requestedObjectDigest != null && stored != requestedObjectDigest) {
            throw MediaAssetIdentityConflictException(
                "Media asset is already associated with a different published object digest.",
            )
        }
    }

    private fun newTemporaryPath(): Path {
        repeat(MAX_ASSET_ID_ATTEMPTS) {
            val token = mediaSecureRandomBytes(16).toHex()
            val candidate = stagingRoot.resolve("$token.part")
            if (fileSystem.metadataOrNull(candidate) == null) return candidate
        }
        error("Could not allocate a unique media staging path.")
    }

    private fun writeStagedObject(
        path: Path,
        maxBytes: Long,
        write: (BufferedSink) -> Unit,
    ): StagedObject {
        var byteSize = 0L
        var contentSha256: String? = null
        val handle = fileSystem.openReadWrite(path, mustCreate = true)
        try {
            val hashingSink = HashingSink.sha256(handle.sink())
            val boundedSink = object : Sink {
                override fun write(source: Buffer, byteCount: Long) {
                    if (byteCount > maxBytes - byteSize) {
                        throw MediaAssetImportTooLargeException(maxBytes)
                    }
                    hashingSink.write(source, byteCount)
                    byteSize += byteCount
                }

                override fun flush() = hashingSink.flush()

                override fun timeout(): Timeout = hashingSink.timeout()

                override fun close() = hashingSink.close()
            }
            val sink = boundedSink.buffer()
            try {
                write(sink)
                sink.flush()
                if (byteSize == 0L) throw MediaAssetIntegrityException("Media input is empty.")
                handle.flush()
                contentSha256 = hashingSink.hash.hex()
            } finally {
                sink.close()
            }
        } finally {
            handle.close()
        }
        return StagedObject(byteSize, checkNotNull(contentSha256))
    }

    private fun promoteStagedObject(
        temporaryPath: Path,
        staged: StagedObject,
    ) {
        val target = objectPath(staged.contentSha256)
        fileSystem.createDirectories(checkNotNull(target.parent))
        val existing = fileSystem.metadataOrNull(target)
        if (existing == null) {
            fileSystem.atomicMove(temporaryPath, target)
            return
        }
        if (!existing.isRegularFile) {
            throw MediaAssetIntegrityException("Media object path is not a regular file.")
        }
        val valid = existing.size == staged.byteSize &&
            hashObject(target, staged.byteSize).let {
                it.byteSize == staged.byteSize && it.contentSha256 == staged.contentSha256
            }
        if (valid) {
            fileSystem.delete(temporaryPath, mustExist = false)
        } else {
            // Restores the only valid byte sequence for this immutable content address.
            fileSystem.atomicMove(temporaryPath, target)
        }
    }

    private fun hashObject(
        path: Path,
        expectedMaxBytes: Long,
    ): HashedObject {
        var byteSize = 0L
        var contentSha256: String? = null
        val hashingSource = HashingSource.sha256(fileSystem.source(path))
        try {
            val buffer = Buffer()
            while (true) {
                val remaining = expectedMaxBytes - byteSize
                val nextRead = minOf(COPY_BUFFER_BYTES, remaining.coerceAtLeast(0L) + 1L)
                val read = hashingSource.read(buffer, nextRead)
                if (read == -1L) break
                if (read == 0L) continue
                byteSize += read
                buffer.clear()
                if (byteSize > expectedMaxBytes) break
            }
            if (byteSize <= expectedMaxBytes) contentSha256 = hashingSource.hash.hex()
        } finally {
            hashingSource.close()
        }
        return HashedObject(byteSize, contentSha256)
    }

    private fun objectPath(contentSha256: String): Path {
        require(contentSha256.isCanonicalSha256())
        return objectsRoot
            .resolve(contentSha256.take(2))
            .resolve(contentSha256.substring(2, 4))
            .resolve("$contentSha256$OBJECT_FILE_SUFFIX")
    }

    private fun assetByContentSha256(contentSha256: String): LocalMediaAsset? =
        queries.selectMediaAssetByContentSha256(contentSha256, ::mapMediaAsset).executeAsOneOrNull()

    private fun allocateAssetId(contentSha256: String): MediaAssetId {
        repeat(MAX_ASSET_ID_ATTEMPTS) {
            val candidate = addressingStrategy.createAssetId(contentSha256)
            if (getAsset(candidate) == null) return candidate
        }
        throw MediaAssetIdentityConflictException("Could not allocate an unused opaque media asset id.")
    }

    private fun updateLocalState(
        assetId: MediaAssetId,
        state: MediaAssetLocalState,
        verifiedAt: Instant,
    ) {
        queries.updateMediaAssetLocalState(
            local_state = state.storageValue,
            last_verified_at = verifiedAt.toEpochMilliseconds(),
            asset_id = assetId.value,
        )
    }

    private fun mapMediaAsset(
        asset_id: String,
        content_sha256: String,
        byte_size: Long,
        media_type: String,
        original_file_name: String?,
        pixel_width: Long,
        pixel_height: Long,
        created_at: Long,
        local_state: String,
        last_verified_at: Long?,
        published_authority_binding_id: String?,
        published_workspace_id: String?,
        published_object_digest: String?,
    ): LocalMediaAsset {
        require(content_sha256.isCanonicalSha256()) { "Stored media content digest is invalid." }
        require(pixel_width in 1L..Int.MAX_VALUE.toLong() && pixel_height in 1L..Int.MAX_VALUE.toLong()) {
            "Stored media dimensions are invalid."
        }
        require(published_workspace_id == null || published_workspace_id.isCanonicalWorkspaceId()) {
            "Stored published workspace id is invalid."
        }
        require(published_object_digest == null || published_object_digest.isSafeObjectDigest()) {
            "Stored published object digest is invalid."
        }
        require(published_authority_binding_id == null || published_authority_binding_id.isSafeAuthorityBindingId()) {
            "Stored published authority binding is invalid."
        }
        require((published_authority_binding_id == null) == (published_workspace_id == null) &&
            (published_authority_binding_id == null) == (published_object_digest == null)
        ) { "Stored publication evidence is incomplete." }
        return LocalMediaAsset(
            metadata = MediaAssetMetadata(
                id = MediaAssetId.fromCanonicalValue(asset_id),
                byteSize = byte_size,
                mediaType = media_type,
                originalFileName = original_file_name,
                pixelWidth = pixel_width.toInt(),
                pixelHeight = pixel_height.toInt(),
                createdAt = Instant.fromEpochMilliseconds(created_at),
            ),
            contentSha256 = content_sha256,
            localState = MediaAssetLocalState.fromStorageValue(local_state),
            lastVerifiedAt = last_verified_at?.let(Instant::fromEpochMilliseconds),
            publishedAuthorityBindingId = published_authority_binding_id,
            publishedWorkspaceId = published_workspace_id,
            publishedObjectDigest = published_object_digest,
        )
    }

    private fun FileMetadata.isOldEnough(cutoff: Instant): Boolean =
        lastModifiedAtMillis?.let { it <= cutoff.toEpochMilliseconds() } == true

    private fun FileSystem.listRecursivelyOrEmpty(directory: Path): Sequence<Path> =
        if (metadataOrNull(directory)?.isDirectory == true) listRecursively(directory) else emptySequence()

    private data class StagedObject(
        val byteSize: Long,
        val contentSha256: String,
    )

    private data class HashedObject(
        val byteSize: Long,
        val contentSha256: String?,
    )
}

private fun String.isCanonicalSha256(): Boolean =
    length == SHA256_HEX_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isSafeAuthorityBindingId(): Boolean =
    isNotBlank() && length <= 2_048 && none(Char::isISOControl)

private fun String.isSafeObjectDigest(): Boolean =
    length in 1..255 && isNotBlank() && this == trim() && none { it.isISOControl() }

private fun String.isCanonicalWorkspaceId(): Boolean =
    length == 42 && startsWith("workspace-") && drop(10).all { it in '0'..'9' || it in 'a'..'f' }

private val mediaSecureRandomCrypto: SodiumWorkspaceCrypto by lazy(::SodiumWorkspaceCrypto)

private fun mediaSecureRandomBytes(size: Int): ByteArray = mediaSecureRandomCrypto.randomBytes(size)

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
