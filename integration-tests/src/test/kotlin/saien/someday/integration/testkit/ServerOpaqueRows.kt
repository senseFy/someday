package saien.someday.integration.testkit

import java.sql.DriverManager

/** Reads only server-visible rows; plaintext and workspace keys must never appear here. */
internal fun readOpaqueServerRows(accountEmail: String): String {
    val statements = listOf(
        """
            SELECT row_to_json(value)::text
            FROM (
                SELECT workspace_id, epoch_id, pointer_digest, pointer_object_json,
                       contract_id, schema_set_version, key_set_version, remote_profile,
                       checkpoint_id, checkpoint_digest
                FROM someday_sync_v2_epochs
                WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
            ) value
        """.trimIndent(),
        """
            SELECT row_to_json(value)::text
            FROM (
                SELECT workspace_id, epoch_id, checkpoint_id, chunk_index, chunk_id,
                       chunk_digest, object_count, plaintext_bytes, encrypted_object_json
                FROM someday_sync_v2_checkpoint_chunks
                WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
            ) value
        """.trimIndent(),
        """
            SELECT row_to_json(value)::text
            FROM (
                SELECT workspace_id, epoch_id, checkpoint_id, checkpoint_digest, chunk_count,
                       total_object_count, chunk_refs_fingerprint, encrypted_object_json
                FROM someday_sync_v2_checkpoint_manifests
                WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
            ) value
        """.trimIndent(),
        """
            SELECT row_to_json(value)::text
            FROM (
                SELECT workspace_id, epoch_id, object_id, object_type, object_digest,
                       mutation_id, cursor, ciphertext_digest, encrypted_object_json
                FROM someday_sync_v2_objects
                WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
            ) value
        """.trimIndent(),
        """
            SELECT row_to_json(value)::text
            FROM (
                SELECT workspace_id, epoch_id, object_id, object_digest, mutation_id, cursor
                FROM someday_sync_v2_changes
                WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
            ) value
        """.trimIndent(),
        """
            SELECT row_to_json(value)::text
            FROM (
                SELECT workspace_id, epoch_id, mutation_id, object_id, object_digest, cursor
                FROM someday_sync_v2_mutations
                WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
            ) value
        """.trimIndent(),
        """
            SELECT row_to_json(value)::text
            FROM (
                SELECT workspace_id, media_id, ciphertext_bytes, ciphertext_sha256
                FROM someday_media_v3_objects
                WHERE user_id = (SELECT id FROM someday_users WHERE email = ?)
            ) value
        """.trimIndent(),
        """
            SELECT row_to_json(value)::text
            FROM (
                SELECT password_hash
                FROM someday_users
                WHERE email = ?
            ) value
        """.trimIndent(),
    )
    return DriverManager.getConnection(
        requiredEnvironment("SOMEDAY_DB_URL"),
        requiredEnvironment("SOMEDAY_DB_USER"),
        requiredEnvironment("SOMEDAY_DB_PASSWORD"),
    ).use { connection ->
        connection.prepareStatement("SELECT set_config('someday.user_id', '*', false)").use { it.execute() }
        connection.prepareStatement("SELECT set_config('someday.workspace_id', '*', false)").use { it.execute() }
        buildString {
            statements.forEach { sql ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, accountEmail)
                    statement.executeQuery().use { rows ->
                        while (rows.next()) appendLine(rows.getString(1))
                    }
                }
            }
        }
    }
}
