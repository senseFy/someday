package saien.someday.server

import java.io.PrintStream
import kotlin.system.exitProcess
import saien.someday.server.persistence.RetiredAccountDataRepository

fun main(args: Array<String>) {
    exitProcess(runInvalidateReclamationCli(args.toList(), System.getenv(), System.out, System.err))
}

internal fun runInvalidateReclamationCli(
    args: List<String>,
    environment: Map<String, String>,
    output: PrintStream,
    error: PrintStream,
): Int = runMaintenanceCli(error) {
    if (args == listOf("--help")) {
        output.println("invalidate-reclamation-attestations [--execute --database-identity VALUE --ingress-stopped --maintenance-stopped]")
        output.println("Default is read-only. Use separate SOMEDAY_MAINTENANCE_DB_USER/PASSWORD; execute after restore before reopening ingress or maintenance.")
        return@runMaintenanceCli 0
    }
    val parsed = parseMaintenanceArguments(
        args,
        setOf("--database-identity"),
        setOf("--execute", "--ingress-stopped", "--maintenance-stopped"),
    )
    val repository = RetiredAccountDataRepository(maintenanceServerConfig(environment))
    val identity = repository.databaseIdentity()
    output.println("databaseIdentity=$identity")
    if ("--execute" !in parsed) {
        output.println("status=dry_run")
    } else {
        require("--ingress-stopped" in parsed && "--maintenance-stopped" in parsed) { "Restore invalidation requires stopped ingress and maintenance." }
        val expected = requireNotNull(parsed["--database-identity"]) { "The restored database identity is required." }
        require(expected == identity) { "Database identity changed." }
        repository.invalidateAfterRestore(expected)
        output.println("status=attestations_invalidated")
    }
    0
}
