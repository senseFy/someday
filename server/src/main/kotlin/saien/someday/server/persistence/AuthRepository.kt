package saien.someday.server.persistence

import saien.someday.server.ServerConfig
import saien.someday.server.auth.AccountAccess
import saien.someday.server.auth.AccountError
import saien.someday.server.auth.AccountProtocolFailure
import saien.someday.server.auth.AccountRequestContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class UserRecord(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val isAdmin: Boolean,
    val disabledAt: Instant?,
)

data class DeviceRecord(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val platform: String,
    val revokedAt: Instant?,
    val incarnation: UUID,
)

class VerifiedPasswordAccount(
    val userId: UUID,
    internal val passwordHashSnapshot: String,
) {
    override fun toString(): String = "VerifiedPasswordAccount(userId=$userId, passwordHashSnapshot=<redacted>)"
}

data class SessionIssuanceSnapshot(
    val userId: UUID,
    val sessionId: UUID,
    val deviceId: UUID?,
    val email: String,
    val isAdmin: Boolean,
    val incarnation: UUID,
)

class DeviceIdAlreadyClaimedException : RuntimeException("Device id is already claimed.")
class DeviceRevokedException : RuntimeException("Device is revoked.")

data class AuthSessionSnapshot(
    val userId: UUID,
    val email: String,
    val isAdmin: Boolean,
    val userDisabledAt: Instant?,
    val sessionId: UUID,
    val sessionDeviceId: UUID?,
    val sessionExpiresAt: Instant,
    val sessionRevokedAt: Instant?,
    val deviceRevokedAt: Instant?,
)

data class RefreshSessionSnapshot(
    val refreshTokenId: UUID,
    val sessionId: UUID,
    val userId: UUID,
    val email: String,
    val isAdmin: Boolean,
    val deviceId: UUID?,
    val incarnation: UUID,
)

data class DeviceSessionRecord(
    val device: DeviceRecord,
    val issuance: SessionIssuanceSnapshot,
)

data class PairingInviteRecord(
    val userId: UUID,
    val inviteId: String,
    val creatorDeviceId: UUID,
    val envelopeJson: String?,
    val envelopeDigest: String,
    val state: String,
    val expiresAt: Instant,
    val claimId: String?,
    val claimDeviceId: UUID?,
    val incarnation: UUID,
)

sealed interface PairingInviteCreateResult {
    data class Created(val expiresAt: Instant) : PairingInviteCreateResult
    data class Replay(val expiresAt: Instant) : PairingInviteCreateResult
    data object Conflict : PairingInviteCreateResult
    data object LimitReached : PairingInviteCreateResult
}

sealed interface PairingInviteClaimResult {
    data class Claimed(val record: PairingInviteRecord) : PairingInviteClaimResult
    data object NotFound : PairingInviteClaimResult
    data object Expired : PairingInviteClaimResult
    data object Conflict : PairingInviteClaimResult
}

sealed interface PairingInviteMutationResult {
    data object Completed : PairingInviteMutationResult
    data object NotFound : PairingInviteMutationResult
    data object Expired : PairingInviteMutationResult
    data object Conflict : PairingInviteMutationResult
}

class AuthRepository(
    config: ServerConfig,
    private val connections: DatabaseConnectionProvider = directDatabaseConnectionProvider(config),
) {
    fun createUser(email: String, passwordHash: String): UserRecord? =
        createUser(email, passwordHash, isAdmin = false)

    fun createAdminUser(email: String, passwordHash: String): UserRecord? =
        createUser(email, passwordHash, isAdmin = true)

    private fun createUser(
        email: String,
        passwordHash: String,
        isAdmin: Boolean,
    ): UserRecord? {
        val userId = UUID.randomUUID()
        return try {
            connection().use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO someday_users (id, email, password_hash, is_admin)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, email)
                    statement.setString(3, passwordHash)
                    statement.setBoolean(4, isAdmin)
                    statement.executeUpdate()
                }
            }
            UserRecord(
                id = userId,
                email = email,
                passwordHash = passwordHash,
                isAdmin = isAdmin,
                disabledAt = null,
            )
        } catch (error: SQLException) {
            if (error.sqlState == POSTGRES_UNIQUE_VIOLATION) {
                null
            } else {
                throw error
            }
        }
    }

    fun findUserByEmail(email: String): UserRecord? =
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, email, password_hash, is_admin, disabled_at
                FROM someday_users
                WHERE email = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, email)
                statement.executeQuery().use { result ->
                    if (result.next()) result.toUserRecord() else null
                }
            }
        }

    fun issuePasswordSession(
        verified: VerifiedPasswordAccount,
        sessionId: UUID = UUID.randomUUID(),
        refreshTokenHash: String,
        sessionExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): SessionIssuanceSnapshot =
        AccountAdmission.transaction(connections, listOf(verified.userId)) { connection ->
            val user = connection.prepareStatement(
                "SELECT id, email, password_hash, is_admin, disabled_at FROM someday_users WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, verified.userId)
                statement.executeQuery().use { result -> if (result.next()) result.toUserRecord() else null }
            }
            if (user == null || user.disabledAt != null || user.passwordHash != verified.passwordHashSnapshot) {
                throw AccountProtocolFailure(AccountError.UNAUTHORIZED)
            }
            val incarnation = AccountAdmission.currentIncarnation(connection, user.id)
            insertSession(connection, sessionId, user.id, null, incarnation, sessionExpiresAt)
            insertRefreshToken(connection, sessionId, refreshTokenHash, refreshExpiresAt)
            SessionIssuanceSnapshot(user.id, sessionId, null, user.email, user.isAdmin, incarnation)
        }

    fun rotateRefreshToken(
        oldRefreshTokenHash: String,
        newRefreshTokenHash: String,
        refreshExpiresAt: Instant,
        now: Instant = Instant.now(),
    ): RefreshSessionSnapshot? {
        // Resolve only immutable ownership here. The token and authority are
        // re-read after account admission, before rotating the locked token.
        val owner = connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT s.user_id
                FROM someday_refresh_tokens rt
                JOIN someday_sessions s ON s.id = rt.session_id
                WHERE rt.token_hash = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, oldRefreshTokenHash)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getObject("user_id", UUID::class.java) else null
                }
            }
        } ?: return null
        return AccountAdmission.transaction(connections, listOf(owner)) { connection ->
            val refreshTokenId = connection.prepareStatement(
                """
                SELECT rt.id
                FROM someday_refresh_tokens rt
                JOIN someday_sessions s ON s.id = rt.session_id
                WHERE rt.token_hash = ? AND s.user_id = ?
                FOR UPDATE OF rt
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, oldRefreshTokenHash)
                statement.setObject(2, owner)
                statement.executeQuery().use { result ->
                    if (result.next()) result.getObject("id", UUID::class.java) else null
                }
            } ?: return@transaction null
            // Use a new READ COMMITTED statement after the token-row wait.
            // FOR UPDATE refreshes its locked row, not the other joined rows'
            // statement snapshot; a session/user/device may change while waiting.
            val snapshot = connection.prepareStatement(
                """
                SELECT
                    rt.id AS refresh_token_id,
                    rt.expires_at AS refresh_expires_at,
                    rt.revoked_at AS refresh_revoked_at,
                    s.id AS session_id,
                    s.user_id AS user_id,
                    s.device_id AS device_id,
                    s.expires_at AS session_expires_at,
                    s.revoked_at AS session_revoked_at,
                    s.data_incarnation AS session_incarnation,
                    u.email AS email,
                    u.is_admin AS is_admin,
                    u.disabled_at AS user_disabled_at,
                    d.revoked_at AS device_revoked_at,
                    d.data_incarnation AS device_incarnation
                FROM someday_refresh_tokens rt
                JOIN someday_sessions s ON s.id = rt.session_id
                JOIN someday_users u ON u.id = s.user_id
                LEFT JOIN someday_devices d ON d.id = s.device_id AND d.user_id = s.user_id
                WHERE rt.id = ? AND s.user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, refreshTokenId)
                statement.setObject(2, owner)
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        val refreshExpiresAtCurrent = result.requiredInstant("refresh_expires_at")
                        val refreshRevokedAt = result.optionalInstant("refresh_revoked_at")
                        val sessionExpiresAt = result.requiredInstant("session_expires_at")
                        val sessionRevokedAt = result.optionalInstant("session_revoked_at")
                        val userDisabledAt = result.optionalInstant("user_disabled_at")
                        val deviceRevokedAt = result.optionalInstant("device_revoked_at")
                        // Token-row waits can outlast the short account-lock
                        // budget; expiry must be checked after that wait too.
                        val checkedAt = maxOf(now, Instant.now())
                        if (
                            refreshRevokedAt == null &&
                            sessionRevokedAt == null &&
                            userDisabledAt == null &&
                            deviceRevokedAt == null &&
                            refreshExpiresAtCurrent.isAfter(checkedAt) &&
                            sessionExpiresAt.isAfter(checkedAt)
                        ) {
                            val incarnation = result.getObject("session_incarnation", UUID::class.java)
                            val currentIncarnation = AccountAdmission.currentIncarnation(connection, owner)
                            if (incarnation != currentIncarnation) {
                                throw AccountProtocolFailure(AccountError.ACCOUNT_SESSION_STALE)
                            }
                            val deviceId = result.getObject("device_id") as UUID?
                            if (deviceId != null && result.getObject("device_incarnation") != currentIncarnation) {
                                throw AccountProtocolFailure(AccountError.ACCOUNT_INCARNATION_MISMATCH)
                            }
                            RefreshSessionSnapshot(
                                refreshTokenId = result.getObject("refresh_token_id", UUID::class.java),
                                sessionId = result.getObject("session_id", UUID::class.java),
                                userId = result.getObject("user_id", UUID::class.java),
                                email = result.getString("email"),
                                isAdmin = result.getBoolean("is_admin"),
                                deviceId = deviceId,
                                incarnation = incarnation,
                            )
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            } ?: return@transaction null

            val replacementId = UUID.randomUUID()
            connection.prepareStatement(
                """
                UPDATE someday_refresh_tokens
                SET revoked_at = NOW(), replaced_by_token_id = ?
                WHERE id = ? AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, replacementId)
                statement.setObject(2, snapshot.refreshTokenId)
                check(statement.executeUpdate() == 1) { "Locked refresh token changed before rotation." }
            }
            insertRefreshToken(
                connection = connection,
                tokenId = replacementId,
                sessionId = snapshot.sessionId,
                tokenHash = newRefreshTokenHash,
                expiresAt = refreshExpiresAt,
            )
            snapshot
        }
    }

    fun logout(request: AccountRequestContext) {
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val admitted = AccountAdmission.admit(connection, request, AccountAccess.ACCOUNT)
            connection.prepareStatement(
                """
                UPDATE someday_sessions
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE id = ? AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, admitted.sessionId)
                statement.setObject(2, admitted.userId)
                statement.executeUpdate()
            }
            revokeRefreshTokensForSession(connection, admitted.sessionId)
        }
    }

    fun findAuthSession(userId: UUID, sessionId: UUID): AuthSessionSnapshot? =
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT
                    u.id AS user_id,
                    u.email AS email,
                    u.is_admin AS is_admin,
                    u.disabled_at AS user_disabled_at,
                    s.id AS session_id,
                    s.device_id AS session_device_id,
                    s.expires_at AS session_expires_at,
                    s.revoked_at AS session_revoked_at,
                    d.revoked_at AS device_revoked_at
                FROM someday_sessions s
                JOIN someday_users u ON u.id = s.user_id
                LEFT JOIN someday_devices d ON d.id = s.device_id
                WHERE s.id = ? AND s.user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, sessionId)
                statement.setObject(2, userId)
                statement.executeQuery().use { result ->
                    if (result.next()) {
                        AuthSessionSnapshot(
                            userId = result.getObject("user_id", UUID::class.java),
                            email = result.getString("email"),
                            isAdmin = result.getBoolean("is_admin"),
                            userDisabledAt = result.optionalInstant("user_disabled_at"),
                            sessionId = result.getObject("session_id", UUID::class.java),
                            sessionDeviceId = result.getObject("session_device_id") as UUID?,
                            sessionExpiresAt = result.requiredInstant("session_expires_at"),
                            sessionRevokedAt = result.optionalInstant("session_revoked_at"),
                            deviceRevokedAt = result.optionalInstant("device_revoked_at"),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    fun admitRequest(
        request: AccountRequestContext,
        access: AccountAccess = AccountAccess.ACCOUNT,
    ): AdmittedAccount = AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
        AccountAdmission.admit(connection, request, access)
    }

    fun registerDevice(
        request: AccountRequestContext,
        deviceId: UUID,
        name: String,
        platform: String,
        refreshTokenHash: String,
        sessionExpiresAt: Instant,
        refreshExpiresAt: Instant,
    ): DeviceSessionRecord =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val admitted = AccountAdmission.admit(connection, request, AccountAccess.DEVICES)
            var device = claimOrRecoverDevice(
                connection, deviceId, admitted.userId, name, platform, admitted.incarnation,
            )
            if (device.incarnation != admitted.incarnation) {
                if (admitted.deviceId != null) throw AccountProtocolFailure(AccountError.FORBIDDEN)
                connection.prepareStatement(
                    """
                    UPDATE someday_devices SET data_incarnation = ?, name = ?, platform = ?
                    WHERE id = ? AND user_id = ? AND revoked_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, admitted.incarnation)
                    statement.setString(2, name)
                    statement.setString(3, platform)
                    statement.setObject(4, device.id)
                    statement.setObject(5, admitted.userId)
                    check(statement.executeUpdate() == 1) { "Locked device enrollment changed." }
                }
                device = device.copy(name = name, platform = platform, incarnation = admitted.incarnation)
            }
            revokeDeviceSessions(
                connection = connection,
                deviceId = device.id,
            )
            val sessionId = insertSession(
                connection, UUID.randomUUID(), admitted.userId, device.id, admitted.incarnation, sessionExpiresAt,
            )
            insertRefreshToken(connection, sessionId, refreshTokenHash, refreshExpiresAt)
            DeviceSessionRecord(
                device = device,
                issuance = SessionIssuanceSnapshot(
                    admitted.userId, sessionId, device.id, admitted.email, admitted.isAdmin, admitted.incarnation,
                ),
            )
        }

    fun listDevices(request: AccountRequestContext): List<DeviceRecord> =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val admitted = AccountAdmission.admit(connection, request, AccountAccess.DEVICES)
            connection.prepareStatement(
                """
                SELECT id, user_id, name, platform, revoked_at, data_incarnation
                FROM someday_devices
                WHERE user_id = ?
                ORDER BY created_at, id
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, admitted.userId)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.toDeviceRecord())
                        }
                    }
                }
            }
        }

    fun revokeDevice(request: AccountRequestContext, deviceId: UUID): Boolean =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val admitted = AccountAdmission.admit(connection, request, AccountAccess.DEVICES)
            val updated = connection.prepareStatement(
                """
                UPDATE someday_devices
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE id = ? AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, deviceId)
                statement.setObject(2, admitted.userId)
                statement.executeUpdate()
            }
            if (updated == 0) {
                return@transaction false
            }
            connection.prepareStatement(
                """
                UPDATE someday_sessions
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE device_id = ? AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, deviceId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                UPDATE someday_refresh_tokens
                SET revoked_at = COALESCE(revoked_at, NOW())
                WHERE session_id IN (
                    SELECT id FROM someday_sessions WHERE device_id = ?
                ) AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, deviceId)
                statement.executeUpdate()
            }
            true
        }

    private fun claimOrRecoverDevice(
        connection: Connection,
        deviceId: UUID,
        userId: UUID,
        name: String,
        platform: String,
        incarnation: UUID,
    ): DeviceRecord {
        connection.prepareStatement(
            """
            INSERT INTO someday_devices (id, user_id, name, platform, data_incarnation)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, deviceId)
            statement.setObject(2, userId)
            statement.setString(3, name)
            statement.setString(4, platform)
            statement.setObject(5, incarnation)
            statement.executeUpdate()
        }
        return connection.prepareStatement(
            """
            SELECT id, user_id, name, platform, revoked_at, data_incarnation
            FROM someday_devices
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, deviceId)
            statement.executeQuery().use { result ->
                check(result.next()) { "Claimed device disappeared inside its registration transaction." }
                result.toDeviceRecord().also { existing ->
                    if (existing.userId != userId) throw DeviceIdAlreadyClaimedException()
                    if (existing.revokedAt != null) throw DeviceRevokedException()
                }
            }
        }
    }

    private fun revokeDeviceSessions(
        connection: Connection,
        deviceId: UUID,
    ) {
        connection.prepareStatement(
            """
            UPDATE someday_sessions
            SET revoked_at = COALESCE(revoked_at, NOW())
            WHERE device_id = ? AND revoked_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, deviceId)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            UPDATE someday_refresh_tokens
            SET revoked_at = COALESCE(revoked_at, NOW())
            WHERE revoked_at IS NULL AND session_id IN (
                SELECT id FROM someday_sessions
                WHERE device_id = ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, deviceId)
            statement.executeUpdate()
        }
    }

    private fun insertSession(
        connection: Connection,
        sessionId: UUID,
        userId: UUID,
        deviceId: UUID?,
        incarnation: UUID,
        expiresAt: Instant,
    ): UUID {
        connection.prepareStatement(
            """
            INSERT INTO someday_sessions (id, user_id, device_id, data_incarnation, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.setObject(2, userId)
            statement.setUuidOrNull(3, deviceId)
            statement.setObject(4, incarnation)
            statement.setInstant(5, expiresAt)
            statement.executeUpdate()
        }
        return sessionId
    }

    private fun insertRefreshToken(
        connection: Connection,
        sessionId: UUID,
        tokenHash: String,
        expiresAt: Instant,
    ): UUID {
        val tokenId = UUID.randomUUID()
        insertRefreshToken(connection, tokenId, sessionId, tokenHash, expiresAt)
        return tokenId
    }

    private fun insertRefreshToken(
        connection: Connection,
        tokenId: UUID,
        sessionId: UUID,
        tokenHash: String,
        expiresAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO someday_refresh_tokens (id, session_id, token_hash, expires_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, tokenId)
            statement.setObject(2, sessionId)
            statement.setString(3, tokenHash)
            statement.setInstant(4, expiresAt)
            statement.executeUpdate()
        }
    }

    private fun revokeRefreshTokensForSession(connection: Connection, sessionId: UUID) {
        connection.prepareStatement(
            """
            UPDATE someday_refresh_tokens
            SET revoked_at = COALESCE(revoked_at, NOW())
            WHERE session_id = ? AND revoked_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, sessionId)
            statement.executeUpdate()
        }
    }

    fun createWorkspacePairingInvite(
        request: AccountRequestContext,
        inviteId: String,
        envelopeJson: String,
        envelopeDigest: String,
        expiresAt: Instant,
        activeLimit: Int,
    ): PairingInviteCreateResult =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val admitted = AccountAdmission.admit(connection, request, AccountAccess.SYNC)
            val userId = admitted.userId
            val creatorDeviceId = checkNotNull(admitted.deviceId)
            lockPairingInvitationCount(connection, userId)
            purgeExpiredWorkspacePairingInvites(connection, userId, admitted.incarnation)
            selectWorkspacePairingInvite(connection, userId, inviteId, forUpdate = true)?.let { existing ->
                existing.requireIncarnation(admitted.incarnation)
                return@transaction existing.asCreateReplayOrConflict(
                    creatorDeviceId = creatorDeviceId,
                    envelopeJson = envelopeJson,
                    envelopeDigest = envelopeDigest,
                )
            }
            val activeCount = connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM workspace_pairing_invites
                WHERE user_id = ? AND data_incarnation = ?
                  AND state IN ('available', 'claimed') AND expires_at > NOW()
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setObject(2, admitted.incarnation)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
            if (activeCount >= activeLimit) {
                return@transaction PairingInviteCreateResult.LimitReached
            }
            val inserted = connection.prepareStatement(
                """
                INSERT INTO workspace_pairing_invites (
                    user_id, invite_id, creator_device_id, envelope_json, envelope_digest, state, expires_at,
                    data_incarnation
                )
                VALUES (?, ?, ?, ?, ?, 'available', ?, ?)
                ON CONFLICT (user_id, invite_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, inviteId)
                statement.setObject(3, creatorDeviceId)
                statement.setString(4, envelopeJson)
                statement.setString(5, envelopeDigest)
                statement.setObject(6, expiresAt.atOffset(ZoneOffset.UTC))
                statement.setObject(7, admitted.incarnation)
                statement.executeUpdate()
            }
            if (inserted == 1) {
                return@transaction PairingInviteCreateResult.Created(expiresAt)
            }
            val existing = selectWorkspacePairingInvite(connection, userId, inviteId, forUpdate = true)
                ?: return@transaction PairingInviteCreateResult.Conflict
            existing.requireIncarnation(admitted.incarnation)
            existing.asCreateReplayOrConflict(
                creatorDeviceId = creatorDeviceId,
                envelopeJson = envelopeJson,
                envelopeDigest = envelopeDigest,
            )
        }

    private fun PairingInviteRecord.asCreateReplayOrConflict(
        creatorDeviceId: UUID,
        envelopeJson: String,
        envelopeDigest: String,
    ): PairingInviteCreateResult =
        if (this.creatorDeviceId == creatorDeviceId &&
            state == "available" &&
            this.envelopeJson == envelopeJson &&
            this.envelopeDigest == envelopeDigest
        ) {
            PairingInviteCreateResult.Replay(expiresAt)
        } else {
            PairingInviteCreateResult.Conflict
        }

    fun claimWorkspacePairingInvite(
        request: AccountRequestContext,
        inviteId: String,
        claimId: String,
        now: Instant,
    ): PairingInviteClaimResult =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val admitted = AccountAdmission.admit(connection, request, AccountAccess.SYNC)
            val userId = admitted.userId
            val claimDeviceId = checkNotNull(admitted.deviceId)
            val existing = selectWorkspacePairingInvite(connection, userId, inviteId, forUpdate = true)
                ?: return@transaction PairingInviteClaimResult.NotFound
            existing.requireIncarnation(admitted.incarnation)
            if (!existing.expiresAt.isAfter(now)) {
                deleteWorkspacePairingInvite(connection, userId, inviteId)
                return@transaction PairingInviteClaimResult.Expired
            }
            when (existing.state) {
                "available" -> {
                    connection.prepareStatement(
                        """
                        UPDATE workspace_pairing_invites
                        SET state = 'claimed', claim_id = ?, claim_device_id = ?, claimed_at = ?
                        WHERE user_id = ? AND invite_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, claimId)
                        statement.setObject(2, claimDeviceId)
                        statement.setObject(3, now.atOffset(ZoneOffset.UTC))
                        statement.setObject(4, userId)
                        statement.setString(5, inviteId)
                        check(statement.executeUpdate() == 1)
                    }
                    PairingInviteClaimResult.Claimed(
                        checkNotNull(selectWorkspacePairingInvite(connection, userId, inviteId, forUpdate = false)),
                    )
                }
                "claimed" ->
                    if (existing.claimId == claimId && existing.claimDeviceId == claimDeviceId) {
                        PairingInviteClaimResult.Claimed(existing)
                    } else {
                        PairingInviteClaimResult.Conflict
                    }
                else -> PairingInviteClaimResult.Conflict
            }
        }

    fun completeWorkspacePairingInvite(
        request: AccountRequestContext,
        inviteId: String,
        claimId: String,
        now: Instant,
    ): PairingInviteMutationResult =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val admitted = AccountAdmission.admit(connection, request, AccountAccess.SYNC)
            val userId = admitted.userId
            val claimDeviceId = checkNotNull(admitted.deviceId)
            val existing = selectWorkspacePairingInvite(connection, userId, inviteId, forUpdate = true)
                ?: return@transaction PairingInviteMutationResult.NotFound
            existing.requireIncarnation(admitted.incarnation)
            if (!existing.expiresAt.isAfter(now)) {
                deleteWorkspacePairingInvite(connection, userId, inviteId)
                return@transaction PairingInviteMutationResult.Expired
            }
            if (existing.claimId != claimId || existing.claimDeviceId != claimDeviceId) {
                return@transaction PairingInviteMutationResult.Conflict
            }
            when (existing.state) {
                "claimed" -> {
                    connection.prepareStatement(
                        """
                        UPDATE workspace_pairing_invites
                        SET state = 'completed', envelope_json = NULL
                        WHERE user_id = ? AND invite_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.setString(2, inviteId)
                        check(statement.executeUpdate() == 1)
                    }
                    PairingInviteMutationResult.Completed
                }
                "completed" -> PairingInviteMutationResult.Completed
                else -> PairingInviteMutationResult.Conflict
            }
        }

    fun cancelWorkspacePairingInvite(
        request: AccountRequestContext,
        inviteId: String,
        now: Instant,
    ): PairingInviteMutationResult =
        AccountAdmission.transaction(connections, listOf(request.userId)) { connection ->
            val admitted = AccountAdmission.admit(connection, request, AccountAccess.SYNC)
            val userId = admitted.userId
            val creatorDeviceId = checkNotNull(admitted.deviceId)
            val existing = selectWorkspacePairingInvite(connection, userId, inviteId, forUpdate = true)
                ?: return@transaction PairingInviteMutationResult.NotFound
            existing.requireIncarnation(admitted.incarnation)
            if (!existing.expiresAt.isAfter(now)) {
                deleteWorkspacePairingInvite(connection, userId, inviteId)
                return@transaction PairingInviteMutationResult.Expired
            }
            if (existing.creatorDeviceId != creatorDeviceId) {
                return@transaction PairingInviteMutationResult.Conflict
            }
            when (existing.state) {
                "available" -> {
                    connection.prepareStatement(
                        """
                        UPDATE workspace_pairing_invites
                        SET state = 'cancelled', envelope_json = NULL
                        WHERE user_id = ? AND invite_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, userId)
                        statement.setString(2, inviteId)
                        check(statement.executeUpdate() == 1)
                    }
                    PairingInviteMutationResult.Completed
                }
                "cancelled" -> PairingInviteMutationResult.Completed
                else -> PairingInviteMutationResult.Conflict
            }
        }

    private fun selectWorkspacePairingInvite(
        connection: Connection,
        userId: UUID,
        inviteId: String,
        forUpdate: Boolean,
    ): PairingInviteRecord? {
        val lockClause = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT user_id, invite_id, creator_device_id, envelope_json, envelope_digest,
                   state, expires_at, claim_id, claim_device_id, data_incarnation
            FROM workspace_pairing_invites
            WHERE user_id = ? AND invite_id = ?$lockClause
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, inviteId)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                PairingInviteRecord(
                    userId = result.getObject("user_id", UUID::class.java),
                    inviteId = result.getString("invite_id"),
                    creatorDeviceId = result.getObject("creator_device_id", UUID::class.java),
                    envelopeJson = result.getString("envelope_json"),
                    envelopeDigest = result.getString("envelope_digest"),
                    state = result.getString("state"),
                    expiresAt = result.requiredInstant("expires_at"),
                    claimId = result.getString("claim_id"),
                    claimDeviceId = result.getObject("claim_device_id", UUID::class.java),
                    incarnation = result.getObject("data_incarnation", UUID::class.java),
                )
            }
        }
    }

    private fun deleteWorkspacePairingInvite(
        connection: Connection,
        userId: UUID,
        inviteId: String,
    ) {
        connection.prepareStatement(
            "DELETE FROM workspace_pairing_invites WHERE user_id = ? AND invite_id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setString(2, inviteId)
            statement.executeUpdate()
        }
    }

    private fun purgeExpiredWorkspacePairingInvites(connection: Connection, userId: UUID, incarnation: UUID) {
        connection.prepareStatement(
            "DELETE FROM workspace_pairing_invites WHERE user_id = ? AND data_incarnation = ? AND expires_at <= NOW()",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.setObject(2, incarnation)
            statement.executeUpdate()
        }
    }

    private fun PairingInviteRecord.requireIncarnation(incarnation: UUID) {
        if (this.incarnation != incarnation) {
            throw AccountProtocolFailure(AccountError.ACCOUNT_INCARNATION_MISMATCH)
        }
    }

    private fun lockPairingInvitationCount(connection: Connection, userId: UUID) {
        // Only creation needs this narrower count-and-insert serialization;
        // other account requests retain their shared admission concurrently.
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, "workspace-pairing-account\u001f$userId")
            statement.executeQuery().close()
        }
    }

    private fun connection(): Connection =
        connections.connection()

    private fun ResultSet.toUserRecord(): UserRecord =
        UserRecord(
            id = getObject("id", UUID::class.java),
            email = getString("email"),
            passwordHash = getString("password_hash"),
            isAdmin = getBoolean("is_admin"),
            disabledAt = optionalInstant("disabled_at"),
        )

    private fun ResultSet.toDeviceRecord(): DeviceRecord =
        DeviceRecord(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            name = getString("name"),
            platform = getString("platform"),
            revokedAt = optionalInstant("revoked_at"),
            incarnation = getObject("data_incarnation", UUID::class.java),
        )

    private fun ResultSet.requiredInstant(column: String): Instant =
        getObject(column, OffsetDateTime::class.java).toInstant()

    private fun ResultSet.optionalInstant(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun PreparedStatement.setInstant(index: Int, instant: Instant) {
        setObject(index, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
    }

    private fun PreparedStatement.setUuidOrNull(index: Int, uuid: UUID?) {
        if (uuid == null) {
            setNull(index, Types.OTHER)
        } else {
            setObject(index, uuid)
        }
    }

    private companion object {
        const val POSTGRES_UNIQUE_VIOLATION = "23505"
    }
}
