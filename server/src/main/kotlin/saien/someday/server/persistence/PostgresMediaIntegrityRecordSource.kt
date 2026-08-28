package saien.someday.server.persistence

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import saien.someday.server.media.MediaBlobKey
import saien.someday.server.media.MediaBlobMetadata
import saien.someday.server.media.MediaIntegrityRecord
import saien.someday.server.media.MediaIntegrityRecordSource

class PostgresMediaIntegrityRecordSource(
    private val connections: DatabaseConnectionProvider,
) : MediaIntegrityRecordSource {
    override fun forEachRecord(consumer: (MediaIntegrityRecord) -> Unit) {
        connections.connection().use { connection ->
            connection.isReadOnly = true
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            var failure: Throwable? = null
            try {
                selectAllScopes(connection)
                connection.prepareStatement(
                    SELECT_MEDIA_RECORDS,
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY,
                ).use { statement ->
                    statement.fetchSize = RECORD_FETCH_SIZE
                    statement.executeQuery().use { result ->
                        while (result.next()) consumer(result.mediaIntegrityRecord())
                    }
                }
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                try {
                    connection.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure?.addSuppressed(rollbackFailure) ?: throw rollbackFailure
                }
            }
        }
    }

    private fun selectAllScopes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                "SELECT set_config('someday.user_id', '*', true), " +
                    "set_config('someday.workspace_id', '*', true)",
            )
        }
    }

    private fun ResultSet.mediaIntegrityRecord(): MediaIntegrityRecord = MediaIntegrityRecord(
        key = MediaBlobKey(
            userId = getObject("user_id", UUID::class.java),
            workspaceId = getString("workspace_id"),
            mediaId = getString("media_id"),
        ),
        expected = MediaBlobMetadata(
            bytes = getLong("ciphertext_bytes"),
            sha256 = getString("ciphertext_sha256"),
        ),
    )

    private companion object {
        const val RECORD_FETCH_SIZE = 256
        val SELECT_MEDIA_RECORDS =
            """
            SELECT user_id, workspace_id, media_id, ciphertext_bytes, ciphertext_sha256
            FROM someday_media_v3_objects
            ORDER BY user_id, workspace_id, media_id
            """.trimIndent()
    }
}
