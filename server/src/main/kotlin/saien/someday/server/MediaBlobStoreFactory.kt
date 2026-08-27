package saien.someday.server

import saien.someday.server.media.FileSystemMediaBlobStore
import saien.someday.server.media.MediaBlobStore
import saien.someday.server.media.S3MediaBlobStore
import saien.someday.server.media.S3MediaBlobStoreConfig
import saien.someday.server.persistence.MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES

/** Creates the one explicitly configured durable media backend. */
internal fun createConfiguredMediaBlobStore(config: ServerConfig): MediaBlobStore =
    when (val storage = config.mediaStorage) {
        is ServerMediaStorage.FileSystem -> FileSystemMediaBlobStore(storage.directory)
        is ServerMediaStorage.S3 -> S3MediaBlobStore(
            S3MediaBlobStoreConfig(
                bucket = storage.bucket,
                region = storage.region,
                endpoint = storage.endpoint,
                pathStyleAccess = storage.pathStyle,
                maxObjectBytes = MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES,
            ),
        )
    }
