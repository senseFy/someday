package saien.someday.server.routes

import saien.someday.server.ServerContext
import saien.someday.server.api.StatusResponse
import saien.someday.server.auth.CredentialWorkUnavailableException
import saien.someday.server.auth.isValidAccountEmail
import saien.someday.server.auth.isValidAccountPassword
import saien.someday.server.auth.normalizeAccountEmail
import saien.someday.server.auth.scopesForDevice
import saien.someday.server.persistence.AdminDashboardSnapshot
import saien.someday.server.persistence.AdminDeviceSummary
import saien.someday.server.persistence.AdminHealthSnapshot
import saien.someday.server.persistence.AdminSessionSummary
import saien.someday.server.persistence.AdminStorageSummary
import saien.someday.server.persistence.AdminSyncActivitySummary
import saien.someday.server.persistence.AdminUserDetail
import saien.someday.server.persistence.AdminUserSummary
import saien.someday.server.persistence.UserRecord
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import java.util.UUID

fun Route.adminRoutes(context: ServerContext) {
    route("/admin") {
        install(AdminSecurityHeaders)

        get("/login") {
            call.respondHtml(loginPage())
        }

        post("/login") {
            if (!call.requireAdminPostOrigin(context, allowBearer = false)) return@post
            val parameters = call.receiveAdminLoginParameters() ?: return@post
            val email = normalizeAccountEmail(parameters["email"].orEmpty())
            val password = parameters["password"].orEmpty()
            if (!call.requireAuthenticationRateLimit(context, "admin-login", email.takeIf(::isValidAccountEmail))) {
                return@post
            }

            val user = context.repository.findUserByEmail(email)
            val passwordMatches = if (isValidAccountEmail(email) && isValidAccountPassword(password)) {
                try {
                    context.credentialHasher.verify(user?.passwordHash ?: context.dummyPasswordHash, password)
                } catch (_: CredentialWorkUnavailableException) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "authentication_busy")
                    return@post
                }
            } else {
                false
            }
            if (
                user == null ||
                user.disabledAt != null ||
                !passwordMatches
            ) {
                call.respondHtml(
                    html = loginPage("Invalid admin credentials."),
                    status = HttpStatusCode.Unauthorized,
                )
                return@post
            }
            if (!user.isAdmin) {
                call.respondHtml(
                    html = loginPage("Admin authorization required."),
                    status = HttpStatusCode.Forbidden,
                )
                return@post
            }

            val tokens = context.issueAdminBrowserSession(user)
            call.setAdminAccessCookie(context, tokens.accessToken, tokens.expiresInSeconds)
            call.respondRedirect("/admin")
        }

        post("/logout") {
            if (!call.requireAdminPostOrigin(context)) return@post
            val admin = call.requireAdmin(context) ?: return@post
            context.repository.revokeSession(admin.sessionId)
            call.clearAdminAccessCookie(context)
            if (call.wantsJson()) {
                call.respond(StatusResponse(status = "ok"))
            } else {
                call.respondRedirect("/admin/login")
            }
        }

        get {
            call.requireAdmin(context) ?: return@get
            call.respondHtml(dashboardPage(context.adminRepository.dashboard()))
        }

        get("/users") {
            call.requireAdmin(context) ?: return@get
            call.respondHtml(usersPage(context.adminRepository.listUsers()))
        }

        get("/users/{id}") {
            call.requireAdmin(context) ?: return@get
            val userId = call.uuidParameter("id") ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found")
            val detail = context.adminRepository.userDetail(userId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found")
            call.respondHtml(userDetailPage(detail))
        }

        post("/users/{id}/disable") {
            if (!call.requireAdminPostOrigin(context)) return@post
            call.requireAdmin(context) ?: return@post
            val userId = call.uuidParameter("id") ?: return@post call.respondError(HttpStatusCode.NotFound, "not_found")
            if (!context.adminRepository.disableUser(userId)) {
                call.respondError(HttpStatusCode.NotFound, "not_found")
                return@post
            }
            call.respondAdminAction("/admin/users/$userId")
        }

        post("/sessions/{id}/revoke") {
            if (!call.requireAdminPostOrigin(context)) return@post
            call.requireAdmin(context) ?: return@post
            val sessionId = call.uuidParameter("id") ?: return@post call.respondError(HttpStatusCode.NotFound, "not_found")
            if (!context.adminRepository.revokeSession(sessionId)) {
                call.respondError(HttpStatusCode.NotFound, "not_found")
                return@post
            }
            call.respondAdminAction("/admin/users")
        }

        get("/devices") {
            call.requireAdmin(context) ?: return@get
            call.respondHtml(devicesPage(context.adminRepository.listDevices()))
        }

        post("/devices/{id}/revoke") {
            if (!call.requireAdminPostOrigin(context)) return@post
            call.requireAdmin(context) ?: return@post
            val deviceId = call.uuidParameter("id") ?: return@post call.respondError(HttpStatusCode.NotFound, "not_found")
            if (!context.adminRepository.revokeDevice(deviceId)) {
                call.respondError(HttpStatusCode.NotFound, "not_found")
                return@post
            }
            call.respondAdminAction("/admin/devices")
        }

        get("/storage") {
            call.requireAdmin(context) ?: return@get
            call.respondHtml(storagePage(context.adminRepository.storage()))
        }

        get("/activity") {
            call.requireAdmin(context) ?: return@get
            call.respondHtml(syncActivityPage(context.adminRepository.syncActivity()))
        }

        get("/health") {
            call.requireAdmin(context) ?: return@get
            call.respondHtml(healthPage(context.adminRepository.health()))
        }
    }
}

private val AdminSecurityHeaders = createRouteScopedPlugin("AdminSecurityHeaders") {
    onCall { call ->
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("Content-Security-Policy", ADMIN_CONTENT_SECURITY_POLICY)
        call.response.headers.append("Referrer-Policy", "same-origin")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
    }
}

private fun ServerContext.issueAdminBrowserSession(user: UserRecord): saien.someday.server.auth.IssuedTokens {
    val sessionId = UUID.randomUUID()
    val tokens = tokenService.issueTokens(
        userId = user.id,
        sessionId = sessionId,
        deviceId = null,
        isAdmin = true,
        scopes = scopesForDevice(null),
    )
    repository.createSessionWithRefreshToken(
        sessionId = sessionId,
        userId = user.id,
        deviceId = null,
        refreshTokenHash = tokens.refreshTokenHash,
        sessionExpiresAt = Instant.now().plus(config.refreshTokenTtl),
        refreshExpiresAt = Instant.now().plus(config.refreshTokenTtl),
    )
    return tokens
}

private suspend fun ApplicationCall.requireAdmin(context: ServerContext): AuthenticatedCall? {
    val auth = requireAuthenticated(context, tokenOverride = adminAccessToken()) ?: return null
    if (!auth.isAdmin) {
        respondError(HttpStatusCode.Forbidden, "admin_required")
        return null
    }
    return auth
}

private fun ApplicationCall.adminAccessToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.removePrefix("Bearer ")
        ?.takeIf { it.isNotBlank() }
        ?: cookieValue(ADMIN_ACCESS_COOKIE)

private fun ApplicationCall.cookieValue(name: String): String? =
    request.headers[HttpHeaders.Cookie]
        ?.split(';')
        ?.map { it.trim() }
        ?.firstNotNullOfOrNull { cookie ->
            val separator = cookie.indexOf('=')
            if (separator <= 0) {
                null
            } else {
                val cookieName = cookie.substring(0, separator)
                val cookieValue = cookie.substring(separator + 1)
                cookieValue.takeIf { cookieName == name && it.isNotBlank() }
            }
        }

private fun ApplicationCall.setAdminAccessCookie(context: ServerContext, token: String, maxAgeSeconds: Long) {
    val secure = if (context.config.secureAdminCookies) "; Secure" else ""
    response.headers.append(
        HttpHeaders.SetCookie,
        "$ADMIN_ACCESS_COOKIE=$token; Path=/admin; HttpOnly; SameSite=Strict$secure; Max-Age=$maxAgeSeconds",
    )
}

private fun ApplicationCall.clearAdminAccessCookie(context: ServerContext) {
    val secure = if (context.config.secureAdminCookies) "; Secure" else ""
    response.headers.append(
        HttpHeaders.SetCookie,
        "$ADMIN_ACCESS_COOKIE=; Path=/admin; HttpOnly; SameSite=Strict$secure; Max-Age=0",
    )
}

private suspend fun ApplicationCall.requireAdminPostOrigin(
    context: ServerContext,
    allowBearer: Boolean = true,
): Boolean {
    if (allowBearer && request.headers[HttpHeaders.Authorization]?.startsWith("Bearer ") == true) return true
    if (request.headers[HttpHeaders.Origin] == context.config.publicOrigin) return true
    respondError(HttpStatusCode.Forbidden, "invalid_origin")
    return false
}

private suspend fun ApplicationCall.receiveAdminLoginParameters(): Parameters? {
    val body = try {
        receiveBoundedBody(MAX_ADMIN_LOGIN_FORM_BYTES)
    } catch (_: RequestBodyTooLarge) {
        respondError(HttpStatusCode.PayloadTooLarge, "request_body_too_large")
        return null
    } catch (_: Exception) {
        respondError(HttpStatusCode.BadRequest, "invalid_request")
        return null
    }
    return try {
        parseQueryString(
            query = body.decodeToString(throwOnInvalidSequence = true),
            limit = MAX_ADMIN_LOGIN_FORM_FIELDS,
        )
    } catch (_: Exception) {
        respondError(HttpStatusCode.BadRequest, "invalid_request")
        null
    }
}

private fun ApplicationCall.wantsJson(): Boolean =
    request.headers[HttpHeaders.Accept]?.contains(ContentType.Application.Json.toString(), ignoreCase = true) == true ||
        request.headers[HttpHeaders.Authorization]?.isNotBlank() == true

private fun ApplicationCall.uuidParameter(name: String): UUID? =
    parameters[name]?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }

private suspend fun ApplicationCall.respondAdminAction(redirectTo: String) {
    if (wantsJson()) {
        respond(StatusResponse(status = "ok"))
    } else {
        respondRedirect(redirectTo)
    }
}

private suspend fun ApplicationCall.respondHtml(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(
        text = html,
        contentType = ContentType.Text.Html.withCharset(Charsets.UTF_8),
        status = status,
    )
}

private fun loginPage(error: String? = null): String =
    adminShell("Admin Login") {
        appendLine("<section class=\"card\">")
        appendLine("<h2>Admin Login</h2>")
        if (error != null) {
            appendLine("<p class=\"error\">${error.escapeHtml()}</p>")
        }
        appendLine(
            """
            <form method="post" action="/admin/login">
              <label>Email <input type="email" name="email" autocomplete="username" required></label>
              <label>Password <input type="password" name="password" autocomplete="current-password" required></label>
              <button type="submit">Sign in</button>
            </form>
            """.trimIndent(),
        )
        appendLine("</section>")
    }

private fun dashboardPage(snapshot: AdminDashboardSnapshot): String =
    adminShell("Dashboard") {
        appendLine("<section class=\"card\">")
        appendLine("<h2>Operational overview</h2>")
        appendLine("<div class=\"metrics\">")
        metric("Users", snapshot.totalUsers)
        metric("Active users", snapshot.activeUsers)
        metric("Disabled users", snapshot.disabledUsers)
        metric("Devices", snapshot.totalDevices)
        metric("Revoked devices", snapshot.revokedDevices)
        metric("Encrypted objects", snapshot.encryptedObjects)
        metric("Accepted changes", snapshot.acceptedChanges)
        appendLine("</div>")
        appendLine("</section>")
    }

private fun usersPage(users: List<AdminUserSummary>): String =
    adminShell("Users") {
        appendLine("<section class=\"card\">")
        appendLine("<h2>Users</h2>")
        appendLine("<table><thead><tr><th>Email</th><th>Role</th><th>Status</th><th>Devices</th><th>Objects</th><th>Latest cursor</th><th>Actions</th></tr></thead><tbody>")
        users.forEach { user ->
            appendLine("<tr>")
            appendLine("<td><a href=\"/admin/users/${user.id}\">${user.email.escapeHtml()}</a></td>")
            appendLine("<td>${if (user.isAdmin) "admin" else "user"}</td>")
            appendLine("<td>${if (user.disabledAt == null) "active" else "disabled"}</td>")
            appendLine("<td>${user.deviceCount} (${user.revokedDeviceCount} revoked)</td>")
            appendLine("<td>${user.objectCount}</td>")
            appendLine("<td>${user.latestCursor?.toString()?.escapeHtml() ?: "none"}</td>")
            appendLine("<td>${disableUserForm(user)}</td>")
            appendLine("</tr>")
        }
        appendLine("</tbody></table>")
        appendLine("</section>")
    }

private fun userDetailPage(detail: AdminUserDetail): String =
    adminShell("User Detail") {
        val user = detail.user
        appendLine("<section class=\"card\">")
        appendLine("<h2>User detail</h2>")
        appendLine("<dl>")
        appendLine("<dt>Email</dt><dd>${user.email.escapeHtml()}</dd>")
        appendLine("<dt>Role</dt><dd>${if (user.isAdmin) "admin" else "user"}</dd>")
        appendLine("<dt>Status</dt><dd>${if (user.disabledAt == null) "active" else "disabled at ${formatInstant(user.disabledAt)}"}</dd>")
        appendLine("<dt>Created</dt><dd>${formatInstant(user.createdAt)}</dd>")
        appendLine("<dt>Encrypted objects</dt><dd>${user.objectCount}</dd>")
        appendLine("</dl>")
        appendLine(disableUserForm(user))
        appendLine("</section>")

        appendLine("<section class=\"card\">")
        appendLine("<h2>Devices</h2>")
        appendLine(devicesTable(detail.devices))
        appendLine("</section>")

        appendLine("<section class=\"card\">")
        appendLine("<h2>Sessions</h2>")
        appendLine("<table><thead><tr><th>Session</th><th>Device</th><th>Status</th><th>Expires</th><th>Refresh tokens</th><th>Actions</th></tr></thead><tbody>")
        detail.sessions.forEach { session ->
            appendLine("<tr>")
            appendLine("<td><code>${session.id}</code></td>")
            appendLine("<td>${session.deviceName?.escapeHtml() ?: "account session"}</td>")
            appendLine("<td>${if (session.revokedAt == null) "active" else "revoked"}</td>")
            appendLine("<td>${formatInstant(session.expiresAt)}</td>")
            appendLine("<td>${session.activeRefreshTokens}</td>")
            appendLine("<td>${revokeSessionForm(session)}</td>")
            appendLine("</tr>")
        }
        appendLine("</tbody></table>")
        appendLine("</section>")
    }

private fun devicesPage(devices: List<AdminDeviceSummary>): String =
    adminShell("Devices") {
        appendLine("<section class=\"card\">")
        appendLine("<h2>Devices</h2>")
        appendLine(devicesTable(devices))
        appendLine("</section>")
    }

private fun devicesTable(devices: List<AdminDeviceSummary>): String =
    buildString {
        appendLine("<table><thead><tr><th>Owner</th><th>Name</th><th>Platform</th><th>Status</th><th>Last sync</th><th>Objects</th><th>Actions</th></tr></thead><tbody>")
        devices.forEach { device ->
            appendLine("<tr>")
            appendLine("<td>${device.ownerEmail.escapeHtml()}</td>")
            appendLine("<td>${device.name.escapeHtml()}</td>")
            appendLine("<td>${device.platform.escapeHtml()}</td>")
            appendLine("<td>${if (device.revokedAt == null) "active" else "revoked"}</td>")
            appendLine("<td>${device.lastSyncCursor?.let { "cursor $it" } ?: formatInstant(device.lastSeenAt)}</td>")
            appendLine("<td>${device.objectCount}</td>")
            appendLine("<td>${revokeDeviceForm(device)}</td>")
            appendLine("</tr>")
        }
        appendLine("</tbody></table>")
    }

private fun storagePage(storage: AdminStorageSummary): String =
    adminShell("Storage") {
        appendLine("<section class=\"card\">")
        appendLine("<h2>Storage</h2>")
        appendLine("<div class=\"metrics\">")
        metric("Encrypted objects", storage.encryptedObjects)
        metric("Encrypted bytes", storage.encryptedBytes)
        metric("Changes", storage.changes)
        appendLine("</div>")
        appendLine("</section>")

        appendLine("<section class=\"card\">")
        appendLine("<h2>By object type</h2>")
        appendLine("<table><thead><tr><th>Type</th><th>Objects</th><th>Encrypted bytes</th><th>Latest cursor</th></tr></thead><tbody>")
        storage.byType.forEach { type ->
            appendLine("<tr><td>${type.objectType.escapeHtml()}</td><td>${type.objects}</td><td>${type.encryptedBytes}</td><td>${type.latestCursor ?: "none"}</td></tr>")
        }
        appendLine("</tbody></table>")
        appendLine("</section>")

        appendLine("<section class=\"card\">")
        appendLine("<h2>By user</h2>")
        appendLine("<table><thead><tr><th>User</th><th>Objects</th><th>Encrypted bytes</th></tr></thead><tbody>")
        storage.byUser.forEach { user ->
            appendLine("<tr><td>${user.email.escapeHtml()}</td><td>${user.objects}</td><td>${user.encryptedBytes}</td></tr>")
        }
        appendLine("</tbody></table>")
        appendLine("</section>")
    }

private fun syncActivityPage(activity: AdminSyncActivitySummary): String =
    adminShell("Sync Activity") {
        appendLine("<section class=\"card\">")
        appendLine("<h2>Sync activity</h2>")
        appendLine("<div class=\"metrics\">")
        metric("Accepted changes", activity.acceptedChanges)
        appendLine("</div>")
        appendLine("<table><thead><tr><th>Time</th><th>User</th><th>Device</th><th>Object</th><th>Mutation</th><th>Cursor</th></tr></thead><tbody>")
        activity.entries.forEach { entry ->
            appendLine("<tr>")
            appendLine("<td>${formatInstant(entry.createdAt)}</td>")
            appendLine("<td>${entry.userEmail?.escapeHtml() ?: "unknown"}</td>")
            appendLine("<td>${entry.deviceName?.escapeHtml() ?: "none"}</td>")
            appendLine("<td>${listOfNotNull(entry.objectType, entry.objectId).joinToString(":").escapeHtml()}</td>")
            appendLine("<td>${entry.mutationId?.escapeHtml() ?: "none"}</td>")
            appendLine("<td>${entry.cursor ?: "none"}</td>")
            appendLine("</tr>")
        }
        appendLine("</tbody></table>")
        appendLine("</section>")
    }

private fun healthPage(health: AdminHealthSnapshot): String =
    adminShell("Health") {
        appendLine("<section class=\"card\">")
        appendLine("<h2>Health</h2>")
        appendLine("<dl>")
        appendLine("<dt>Database</dt><dd>${health.databaseStatus.escapeHtml()}</dd>")
        appendLine("<dt>Migration</dt><dd>${health.migrationVersion.escapeHtml()} ${health.migrationDescription.escapeHtml()}</dd>")
        appendLine("<dt>Uptime</dt><dd>${health.uptimeSeconds} seconds</dd>")
        appendLine("<dt>Checked at</dt><dd>${formatInstant(health.checkedAt)}</dd>")
        appendLine("</dl>")
        appendLine("</section>")
    }

private fun StringBuilder.metric(label: String, value: Long) {
    appendLine("<div class=\"metric\"><strong>${value}</strong><span>${label.escapeHtml()}</span></div>")
}

private fun disableUserForm(user: AdminUserSummary): String =
    if (user.disabledAt != null) {
        "disabled"
    } else {
        """
        <form method="post" action="/admin/users/${user.id}/disable">
          <button type="submit">Disable user and revoke sessions</button>
        </form>
        """.trimIndent()
    }

private fun revokeSessionForm(session: AdminSessionSummary): String =
    if (session.revokedAt != null) {
        "revoked"
    } else {
        """
        <form method="post" action="/admin/sessions/${session.id}/revoke">
          <button type="submit">Revoke session</button>
        </form>
        """.trimIndent()
    }

private fun revokeDeviceForm(device: AdminDeviceSummary): String =
    if (device.revokedAt != null) {
        "revoked"
    } else {
        """
        <form method="post" action="/admin/devices/${device.id}/revoke">
          <button type="submit">Revoke device</button>
        </form>
        """.trimIndent()
    }

private fun adminShell(title: String, body: StringBuilder.() -> Unit): String =
    buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        appendLine("<title>Someday Admin - ${title.escapeHtml()}</title>")
        appendLine(
            """
            <style>
              body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 0; background: #f8fafc; color: #172033; }
              header { background: #172033; color: white; padding: 1rem 1.5rem; }
              header h1 { margin: 0 0 .75rem 0; font-size: 1.35rem; }
              nav { display: flex; gap: .75rem; flex-wrap: wrap; align-items: center; }
              nav a, nav button { color: white; background: transparent; border: 1px solid rgba(255,255,255,.35); border-radius: .45rem; padding: .35rem .6rem; text-decoration: none; font: inherit; }
              main { padding: 1.5rem; }
              .card { background: white; border: 1px solid #dbe3ef; border-radius: .75rem; padding: 1rem; margin-bottom: 1rem; box-shadow: 0 1px 2px rgba(15,23,42,.05); }
              .metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr)); gap: .75rem; }
              .metric { border: 1px solid #dbe3ef; border-radius: .6rem; padding: .75rem; background: #f8fafc; }
              .metric strong { display: block; font-size: 1.4rem; }
              .metric span { color: #526173; }
              table { border-collapse: collapse; width: 100%; }
              th, td { border-bottom: 1px solid #e5ebf3; padding: .55rem; text-align: left; vertical-align: top; }
              th { color: #526173; font-size: .85rem; text-transform: uppercase; letter-spacing: .03em; }
              input { display: block; margin: .25rem 0 .75rem; padding: .45rem; min-width: min(24rem, 100%); }
              button { cursor: pointer; }
              .error { color: #a4001d; font-weight: 600; }
              code { font-size: .85rem; }
              dl { display: grid; grid-template-columns: max-content 1fr; gap: .5rem 1rem; }
              dt { font-weight: 700; color: #526173; }
            </style>
            """.trimIndent(),
        )
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<header>")
        appendLine("<h1>Someday Admin</h1>")
        appendLine("<nav>")
        appendLine("<a href=\"/admin\">Dashboard</a>")
        appendLine("<a href=\"/admin/users\">Users</a>")
        appendLine("<a href=\"/admin/devices\">Devices</a>")
        appendLine("<a href=\"/admin/storage\">Storage</a>")
        appendLine("<a href=\"/admin/activity\">Sync Activity</a>")
        appendLine("<a href=\"/admin/health\">Health</a>")
        appendLine("<form method=\"post\" action=\"/admin/logout\" style=\"display:inline\"><button type=\"submit\">Logout</button></form>")
        appendLine("</nav>")
        appendLine("</header>")
        appendLine("<main>")
        body()
        appendLine("</main>")
        appendLine("</body></html>")
    }

private fun formatInstant(instant: Instant?): String =
    instant?.toString()?.escapeHtml() ?: "never"

private fun String.escapeHtml(): String =
    buildString(length) {
        this@escapeHtml.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

private const val ADMIN_ACCESS_COOKIE = "someday_admin_access"
private const val MAX_ADMIN_LOGIN_FORM_BYTES = 8 * 1024
private const val MAX_ADMIN_LOGIN_FORM_FIELDS = 8
private const val ADMIN_CONTENT_SECURITY_POLICY =
    "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
