package saien.someday.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import saien.someday.server.ServerContext
import saien.someday.server.api.WorkspaceAdmissionResponse

fun Route.workspaceAdmissionRoutes(context: ServerContext) {
    get("/workspace/admission") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val auth = call.requireAuthenticated(context, requiredScope = "sync", requireDevice = true) ?: return@get
        val workspaceId = call.request.queryParameters.getAll("workspaceId")?.singleOrNull()
        if (workspaceId == null || !WORKSPACE_ID.matches(workspaceId)) {
            return@get call.respondError(HttpStatusCode.BadRequest, "invalid_workspace_scope")
        }
        if (!call.requireRateLimit(context, "workspace-admission:${auth.userId}:${auth.tokenDeviceId}")) return@get
        val snapshot = context.workspaceAdmissionRepository.discover(auth.requestContext, workspaceId)
        call.respond(
            WorkspaceAdmissionResponse(
                initializedWorkspaceCount = snapshot.initializedWorkspaceCount,
                localWorkspaceInitialized = snapshot.localWorkspaceInitialized,
                recoveryAvailable = snapshot.recoveryAvailable,
            ),
        )
    }
}

private val WORKSPACE_ID = Regex("^workspace-[0-9a-f]{32}$")
