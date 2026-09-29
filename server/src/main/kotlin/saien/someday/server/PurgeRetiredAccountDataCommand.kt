package saien.someday.server

import java.io.PrintStream
import java.util.UUID
import kotlin.system.exitProcess
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import saien.someday.server.maintenance.FileSystemRetiredMediaStore
import saien.someday.server.maintenance.MaintenanceMediaFailure
import saien.someday.server.maintenance.PurgeRetiredAccountData
import saien.someday.server.maintenance.RetiredAccountPurgeRequest
import saien.someday.server.maintenance.RetiredAccountPurgeStatus
import saien.someday.server.maintenance.RetiredMediaStore
import saien.someday.server.maintenance.S3RetiredMediaStore
import saien.someday.server.media.S3MediaBlobStoreConfig
import saien.someday.server.persistence.MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES
import saien.someday.server.persistence.MediaReclamationRevalidationRequired
import saien.someday.server.persistence.RetiredAccountDataRepository
import saien.someday.server.persistence.RetiredAccountVerificationChanged

fun main(args: Array<String>) {
    exitProcess(runRetiredAccountPurgeCli(args.toList(), System.getenv(), System.out, System.err))
}

internal fun runRetiredAccountPurgeCli(
    args: List<String>,
    environment: Map<String, String>,
    output: PrintStream,
    error: PrintStream,
): Int = runMaintenanceCli(error) {
    if (args == listOf("--help")) {
        output.println(PURGE_USAGE)
        return@runMaintenanceCli 0
    }
    val parsed = parseMaintenanceArguments(args, PURGE_VALUES, PURGE_FLAGS)
    val request = RetiredAccountPurgeRequest(
        userId = parsed.requiredUuid("--user"),
        incarnation = parsed.requiredUuid("--incarnation"),
        expectedDatabaseIdentity = parsed["--database-identity"],
        expectedVerificationContext = parsed["--verification-context"]?.let(::maintenanceUuid),
        expectedStorageIdentity = parsed["--storage-identity"],
        execute = "--execute" in parsed,
        publishersDrained = "--publishers-drained" in parsed,
        profileAttested = "--profile-attested" in parsed,
        retirementClockTrusted = "--retirement-clock-trusted" in parsed,
        operatorRevalidated = "--operator-revalidated" in parsed,
    )
    if (request.operatorRevalidated) {
        require(request.execute && "--reset-disabled" in parsed && environment["SOMEDAY_ACCOUNT_RESET_ENABLED"] != "true") {
            "Operator revalidation requires reset to be disabled."
        }
    }
    val config = maintenanceServerConfig(environment)
    createRetiredMediaStore(config, environment).use { media ->
        val report = PurgeRetiredAccountData(RetiredAccountDataRepository(config), media).run(request)
        output.println("user=${report.target.userId} incarnation=${report.target.incarnation} status=${report.status.name.lowercase()}")
        output.println("database_identity=${report.target.databaseIdentity}")
        output.println("verification_context=${report.target.verificationContext}")
        output.println("storage_identity=${report.storageIdentity}")
        output.println("listed_entries=${report.observedEntries} sql_rows_removed=${report.deletedSqlRows}")
        report.reclaimedAt?.let { output.println("media_reclaimed_at=$it") }
        if (report.status == RetiredAccountPurgeStatus.ASSUMPTION_VIOLATION) {
            error.println("Previously certified storage contains objects. Disable reset and revalidate the profile before explicit operator revalidation.")
        }
        if (report.status in setOf(RetiredAccountPurgeStatus.DRY_RUN, RetiredAccountPurgeStatus.RECLAIMED)) 0 else 2
    }
}

/** Maintenance never falls back to the HTTP runtime database or S3 credentials. */
internal fun maintenanceServerConfig(environment: Map<String, String>): ServerConfig {
    val user = environment.requiredMaintenanceValue("SOMEDAY_MAINTENANCE_DB_USER")
    val password = environment.requiredMaintenanceValue("SOMEDAY_MAINTENANCE_DB_PASSWORD")
    require(user != (environment["SOMEDAY_DB_USER"] ?: "someday")) { "Maintenance requires a separate database identity." }
    return ServerConfig.fromEnvironment(environment + mapOf("SOMEDAY_DB_USER" to user, "SOMEDAY_DB_PASSWORD" to password))
}

private fun createRetiredMediaStore(config: ServerConfig, environment: Map<String, String>): RetiredMediaStore =
    when (val storage = config.mediaStorage) {
        is ServerMediaStorage.FileSystem -> FileSystemRetiredMediaStore(storage.directory)
        is ServerMediaStorage.S3 -> {
            val accessKey = environment.requiredMaintenanceValue("SOMEDAY_MAINTENANCE_S3_ACCESS_KEY_ID")
            val secret = environment.requiredMaintenanceValue("SOMEDAY_MAINTENANCE_S3_SECRET_ACCESS_KEY")
            require(accessKey != environment["AWS_ACCESS_KEY_ID"]) { "Maintenance requires a separate S3 identity." }
            val credentials = environment["SOMEDAY_MAINTENANCE_S3_SESSION_TOKEN"]?.let { token ->
                require(token.isNotBlank())
                AwsSessionCredentials.create(accessKey, secret, token)
            } ?: AwsBasicCredentials.create(accessKey, secret)
            S3RetiredMediaStore(
                S3MediaBlobStoreConfig(storage.bucket, storage.region, storage.endpoint, storage.pathStyle, MAX_MEDIA_OBJECT_CIPHERTEXT_BYTES),
                StaticCredentialsProvider.create(credentials),
            )
        }
    }

internal fun parseMaintenanceArguments(args: List<String>, values: Set<String>, flags: Set<String>): Map<String, String> {
    val parsed = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val name = args[index++]
        require(name !in parsed) { "Duplicate maintenance option." }
        when (name) {
            in values -> {
                require(index < args.size && !args[index].startsWith("--")) { "Missing maintenance option value." }
                parsed[name] = args[index++]
            }
            in flags -> parsed[name] = "true"
            else -> error("Unknown maintenance option.")
        }
    }
    return parsed
}

internal fun maintenanceUuid(value: String): UUID {
    val parsed = UUID.fromString(value)
    require(parsed.toString() == value) { "Maintenance identities must be canonical UUIDs." }
    return parsed
}

internal fun Map<String, String>.requiredUuid(name: String): UUID = maintenanceUuid(requireNotNull(this[name]) { "Missing exact target UUID." })
internal fun Map<String, String>.requiredMaintenanceValue(name: String): String =
    requireNotNull(this[name]?.takeIf(String::isNotBlank)) { "Required maintenance credentials are missing." }

internal inline fun runMaintenanceCli(error: PrintStream, action: () -> Int): Int = try {
    action()
} catch (failure: Exception) {
    val reason = when (failure) {
        is MaintenanceMediaFailure -> failure.reason.name.lowercase()
        is MediaReclamationRevalidationRequired -> "operator_revalidation_required"
        is RetiredAccountVerificationChanged -> "verification_context_changed"
        is IllegalArgumentException -> "invalid_configuration_or_target"
        else -> "operation_incomplete"
    }
    error.println("Retired-account maintenance did not complete: $reason. Reclamation completion is unconfirmed; inspect the target before retrying.")
    1
}

private val PURGE_VALUES = setOf("--user", "--incarnation", "--database-identity", "--verification-context", "--storage-identity")
private val PURGE_FLAGS = setOf("--execute", "--publishers-drained", "--profile-attested", "--retirement-clock-trusted", "--operator-revalidated", "--reset-disabled")
private const val PURGE_USAGE = """purge-retired-account-data --user UUID --incarnation UUID
Default is a read-only full namespace audit. To execute, supply the dry-run
--database-identity VALUE --verification-context UUID --storage-identity VALUE, plus --execute
--publishers-drained --profile-attested. S3 also requires
--retirement-clock-trusted and at least 24 hours since retirement before certification.
After a violation/restore, disable reset and revalidate, then add
--operator-revalidated --reset-disabled. Never reuse a pre-restore deletion plan.
Dedicated SOMEDAY_MAINTENANCE_DB_USER/PASSWORD are mandatory; S3 also needs
SOMEDAY_MAINTENANCE_S3_ACCESS_KEY_ID/SECRET_ACCESS_KEY (optional SESSION_TOKEN).
The profile attestation covers complete listing, settled old publications,
no external/suspended writer, and provider retention; the command cannot prove them."""
