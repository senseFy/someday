package saien.someday.server

import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.routes.accountDataRoutes
import saien.someday.server.routes.respondError
import saien.someday.server.routes.adminRoutes
import saien.someday.server.routes.authRoutes
import saien.someday.server.routes.deviceRoutes
import saien.someday.server.routes.pairingRoutes
import saien.someday.server.routes.systemV3Routes
import saien.someday.server.routes.workspaceRecoveryEnvelopeRoutes
import saien.someday.server.routes.workspaceAdmissionRoutes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val SERVER_LOGGER = LoggerFactory.getLogger("saien.someday.server")

fun main() {
    val config = ServerConfig.fromEnvironment()
    val context = ServerContext.create(config)
    try {
        embeddedServer(Netty, port = config.port, host = config.bindHost) {
            somedayServerModule(context)
        }.start(wait = true)
    } finally {
        context.close()
    }
}

fun Application.somedayServerModule(context: ServerContext = ServerContext.create()) {
    monitor.subscribe(ApplicationStopped) {
        context.close()
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = false
                encodeDefaults = true
                isLenient = false
                explicitNulls = true
            },
        )
    }
    install(StatusPages) {
        exception<AccountProtocolFailure> { call, failure ->
            call.respondError(HttpStatusCode.fromValue(failure.error.status), failure.error.code)
        }
        exception<Exception> { call, failure ->
            SERVER_LOGGER.error("Unhandled server request failure", failure)
            call.respondError(HttpStatusCode.InternalServerError, "internal_error")
        }
    }

    routing {
        get("/health") {
            call.respondText(
                text = """{"status":"ok","service":"someday"}""",
                contentType = ContentType.Application.Json,
            )
        }
        authRoutes(context)
        accountDataRoutes(context)
        deviceRoutes(context)
        pairingRoutes(context)
        workspaceRecoveryEnvelopeRoutes(context)
        workspaceAdmissionRoutes(context)
        systemV3Routes(context)
        adminRoutes(context)
    }
}
