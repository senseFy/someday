package saien.someday.server

internal fun productionTestServerConfig(
    databaseUrl: String,
    databaseUser: String,
    databasePassword: String,
): ServerConfig =
    ServerConfig.fromEnvironment(
        mapOf(
            "SOMEDAY_DEPLOYMENT_MODE" to "production",
            "SOMEDAY_HOST" to "127.0.0.1",
            "SOMEDAY_PUBLIC_BASE_URL" to "https://someday.example",
            "SOMEDAY_DB_URL" to databaseUrl,
            "SOMEDAY_DB_USER" to databaseUser,
            "SOMEDAY_DB_PASSWORD" to databasePassword,
            "SOMEDAY_MEDIA_BACKEND" to "filesystem",
            "SOMEDAY_MEDIA_BLOB_DIR" to "${System.getProperty("java.io.tmpdir")}/someday-server-integration-media",
            "SOMEDAY_JWT_SECRET" to "integration-test-only-jwt-secret-with-at-least-thirty-two-bytes",
        ),
    )
