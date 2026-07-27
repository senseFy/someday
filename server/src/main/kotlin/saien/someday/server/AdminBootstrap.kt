package saien.someday.server

import saien.someday.server.auth.Argon2idPasswordHasher
import saien.someday.server.auth.MAX_ACCOUNT_PASSWORD_LENGTH
import saien.someday.server.auth.MIN_ACCOUNT_PASSWORD_LENGTH
import saien.someday.server.auth.isValidAccountEmail
import saien.someday.server.auth.isValidAccountPassword
import saien.someday.server.auth.normalizeAccountEmail
import saien.someday.server.persistence.AuthRepository
import saien.someday.server.persistence.DatabaseMigrator
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val environment = System.getenv()
    val config = ServerConfig.fromEnvironment(environment)
    val email = normalizeAccountEmail(
        environment["SOMEDAY_ADMIN_EMAIL"]
            ?: error("SOMEDAY_ADMIN_EMAIL is required."),
    )
    require(isValidAccountEmail(email)) { "SOMEDAY_ADMIN_EMAIL is invalid." }
    val password = readBootstrapPassword(environment)
    require(isValidAccountPassword(password)) {
        "The admin password must contain $MIN_ACCOUNT_PASSWORD_LENGTH..$MAX_ACCOUNT_PASSWORD_LENGTH characters."
    }

    DatabaseMigrator.migrate(config)
    val passwordHash = Argon2idPasswordHasher(
        maxConcurrent = config.argon2MaxConcurrent,
    ).hash(password)
    val user = AuthRepository(config).createAdminUser(email, passwordHash)
        ?: error("An account with SOMEDAY_ADMIN_EMAIL already exists; no privileges were changed.")
    println("Created Someday admin ${user.email} (${user.id}).")
}

private fun readBootstrapPassword(environment: Map<String, String>): String {
    val inlinePassword = environment["SOMEDAY_ADMIN_PASSWORD"]
    val passwordFile = environment["SOMEDAY_ADMIN_PASSWORD_FILE"]?.takeIf { it.isNotBlank() }
    require(inlinePassword == null || passwordFile == null) {
        "Set only one of SOMEDAY_ADMIN_PASSWORD or SOMEDAY_ADMIN_PASSWORD_FILE."
    }
    if (passwordFile != null) {
        val path = Path.of(passwordFile)
        require(Files.isRegularFile(path)) { "SOMEDAY_ADMIN_PASSWORD_FILE must name a regular file." }
        require(Files.size(path) <= MAX_PASSWORD_FILE_BYTES) {
            "SOMEDAY_ADMIN_PASSWORD_FILE is unexpectedly large."
        }
        return Files.readString(path).removeSuffix("\n").removeSuffix("\r")
    }
    if (inlinePassword != null) return inlinePassword
    return System.console()
        ?.readPassword("New Someday admin password: ")
        ?.concatToString()
        ?: error(
            "No interactive console is available; set SOMEDAY_ADMIN_PASSWORD_FILE " +
                "(preferred) or SOMEDAY_ADMIN_PASSWORD.",
        )
}

private const val MAX_PASSWORD_FILE_BYTES = 1024L
