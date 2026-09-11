package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.comet.opik.domain.mcpoauth.McpOAuthToken.TYPE_ACCESS;
import static com.comet.opik.domain.mcpoauth.McpOAuthToken.TYPE_REFRESH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.ERROR_INVALID_GRANT;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_TYPE_BEARER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class McpOAuthService {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    // Every write that changes a token family's state (rotation, retry, revocation) runs under this lock, keyed by
    // family_id, so concurrent refreshes with the same token serialize and a revocation can never interleave with
    // the minting of a descendant pair. A row lock cannot do this without gap locks: a FOR UPDATE over the family
    // followed by an INSERT into the same range deadlocks against a second refresh doing the same.
    private static final String FAMILY_LOCK = "McpOAuthFamily";

    private final @NonNull TransactionTemplate template;
    private final @NonNull OpikConfiguration opikConfig;
    private final @NonNull LockService lockService;

    private McpOAuthConfig config() {
        return opikConfig.getMcpOAuth();
    }

    public String createAuthorizationCode(@NonNull CreateOAuthCodeCommand cmd) {
        String rawCode = McpOAuthTokenUtils.generateCode();

        var code = McpOAuthMapper.INSTANCE.toCode(cmd,
                UUID.randomUUID().toString(),
                McpOAuthTokenUtils.hash(rawCode),
                CODE_CHALLENGE_METHOD_S256,
                Instant.now().plus(config().getCodeTtl()));

        template.inTransaction(WRITE, handle -> {
            handle.attach(McpOAuthCodeDAO.class).save(code);
            return null;
        });

        return rawCode;
    }

    /**
     * Burns the code in its own committed transaction. Single-use consumption is committed
     * before the client/redirect/PKCE checks — a failed exchange attempt must not leave the code replayable.
     */
    public TokenResponse exchangeCode(@NonNull String code, @NonNull String codeVerifier,
            @NonNull String redirectUri, @NonNull String clientId) {
        String codeHash = McpOAuthTokenUtils.hash(code);
        Instant now = Instant.now();

        McpOAuthCode row = template.inTransaction(WRITE, handle -> {
            var codeDao = handle.attach(McpOAuthCodeDAO.class);
            if (codeDao.markUsed(codeHash) != 1) {
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }
            return codeDao.findByHash(codeHash);
        });

        if (!matchesGrantRequest(row, clientId, redirectUri, codeVerifier)) {
            throw new BadRequestException(ERROR_INVALID_GRANT);
        }

        String familyId = UUID.randomUUID().toString();
        String accessToken = McpOAuthTokenUtils.generateAccessToken();
        String refreshToken = McpOAuthTokenUtils.generateRefreshToken();

        // The authorization fixes the family's absolute lifetime; every rotation carries it forward.
        Instant absoluteExpiresAt = now.plus(config().getRefreshTokenAbsoluteTtl());

        return template.inTransaction(WRITE, handle -> {
            var tokenDao = handle.attach(McpOAuthTokenDAO.class);
            tokenDao.save(McpOAuthMapper.INSTANCE.toToken(row, TYPE_ACCESS,
                    UUID.randomUUID().toString(), McpOAuthTokenUtils.hash(accessToken),
                    familyId, now.plus(config().getAccessTokenTtl()), absoluteExpiresAt));
            tokenDao.save(McpOAuthMapper.INSTANCE.toToken(row, TYPE_REFRESH,
                    UUID.randomUUID().toString(), McpOAuthTokenUtils.hash(refreshToken),
                    familyId, refreshExpiry(now, absoluteExpiresAt), absoluteExpiresAt));

            return buildTokenResponse(accessToken, refreshToken, row.workspaceId(), row.workspaceName());
        });
    }

    /**
     * Rotates a refresh token: the presented token is revoked and a new access + refresh pair is issued in the
     * same family. The new refresh token lives {@code refreshTokenTtl} from now, capped at the family's absolute
     * expiry, so an active connector stays connected and an idle one lapses (see {@link #refreshExpiry}).
     * <p>
     * MCP hosts run several tool calls in parallel, and when the access token expires each of them answers the
     * 401 by refreshing with the same stored refresh token. Only one of those requests can be the rotation; the
     * others re-present a token that was revoked moments earlier. Within {@code refreshRotationGrace}, and for at
     * most {@code refreshRotationMaxRetries} of them, such a re-presentation is the legitimate client retrying and
     * still gets a fresh pair off the same family rather than {@code invalid_grant}: a host that sees
     * {@code invalid_grant} on refresh discards its tokens and forces the user to re-authorize (RFC 6819 §5.2.2.3
     * flags exactly this clustered-client hazard of rotation). Outside the window, or past the cap, the
     * re-presentation is treated as reuse and the whole family is revoked, per OAuth 2.1 §4.3.1.
     */
    public TokenResponse refresh(@NonNull String refreshToken, @NonNull String clientId) {
        String tokenHash = McpOAuthTokenUtils.hash(refreshToken);
        Instant now = Instant.now();

        McpOAuthToken row = template.inTransaction(READ_ONLY,
                handle -> handle.attach(McpOAuthTokenDAO.class).findByHash(tokenHash));

        // The detail on these exceptions is for the log line in OAuthTokenService; the client only ever sees
        // invalid_grant (RFC 6749 §5.2).
        if (row == null || !TYPE_REFRESH.equals(row.type())) {
            throw new BadRequestException("unknown refresh token");
        }
        if (!row.clientId().equals(clientId)) {
            throw new BadRequestException("refresh token was issued to another client");
        }
        if (!row.expiresAt().isAfter(now)) {
            throw new BadRequestException("refresh token expired at '%s'".formatted(row.expiresAt()));
        }

        return underFamilyLock(row.familyId(), () -> rotate(row, now))
                .orElseThrow(() -> new BadRequestException("refresh token reuse detected, family revoked"));
    }

    /**
     * The locked half of {@link #refresh}: re-reads the family under the lock, decides between rotation, in-grace
     * retry and reuse, and returns the minted pair, or empty when the presentation was reuse and the family has
     * just been revoked. The decision and its writes share one transaction; the caller turns "empty" into
     * {@code invalid_grant} outside it so the revocation is never rolled back by the rejection.
     */
    private Optional<TokenResponse> rotate(McpOAuthToken row, Instant now) {
        String accessToken = McpOAuthTokenUtils.generateAccessToken();
        String newRefreshToken = McpOAuthTokenUtils.generateRefreshToken();

        return template.inTransaction(WRITE, handle -> {
            var tokenDao = handle.attach(McpOAuthTokenDAO.class);

            List<McpOAuthToken> family = tokenDao.findFamily(row.familyId(), row.workspaceId());
            McpOAuthToken current = family.stream()
                    .filter(token -> token.id().equals(row.id()))
                    .findFirst()
                    .orElse(null);
            if (current == null) {
                // The presented token existed a moment ago and is gone: only the scrub job deletes rows, and it
                // deletes expired or revoked ones. Fail closed for whatever is left of the family.
                tokenDao.revokeFamily(row.familyId(), RevokedReason.REUSE);
                return Optional.empty();
            }

            if (isFamilyRevoked(family)) {
                throw new BadRequestException("refresh token family already revoked");
            }
            // Re-checked under the lock: the pre-lock check may have waited behind another rotation.
            if (!current.expiresAt().isAfter(Instant.now())) {
                throw new BadRequestException("refresh token expired at '%s'".formatted(current.expiresAt()));
            }

            boolean retry = current.revokedAt() != null;
            if (!retry && tokenDao.revoke(current.tokenHash(), RevokedReason.ROTATED) != 1) {
                throw new BadRequestException("refresh token could not be rotated");
            }
            if (retry && (!isBenignRotationRetry(current, now)
                    || countDescendantPairs(family, current) > config().getRefreshRotationMaxRetries())) {
                // Reuse detected: kill the whole lineage
                tokenDao.revokeFamily(current.familyId(), RevokedReason.REUSE);
                return Optional.empty();
            }

            // Rows minted before the column existed carry no absolute expiry; their cap starts counting now.
            Instant absoluteExpiresAt = Optional.ofNullable(current.absoluteExpiresAt())
                    .orElseGet(() -> now.plus(config().getRefreshTokenAbsoluteTtl()));

            tokenDao.save(McpOAuthMapper.INSTANCE.toRotatedToken(current, TYPE_ACCESS,
                    UUID.randomUUID().toString(), McpOAuthTokenUtils.hash(accessToken),
                    now.plus(config().getAccessTokenTtl()), absoluteExpiresAt));
            tokenDao.save(McpOAuthMapper.INSTANCE.toRotatedToken(current, TYPE_REFRESH,
                    UUID.randomUUID().toString(), McpOAuthTokenUtils.hash(newRefreshToken),
                    refreshExpiry(now, absoluteExpiresAt), absoluteExpiresAt));

            return Optional.of(buildTokenResponse(accessToken, newRefreshToken, current.workspaceId(),
                    current.workspaceName()));
        });
    }

    public void revoke(@NonNull String token) {
        String tokenHash = McpOAuthTokenUtils.hash(token);

        McpOAuthToken row = template.inTransaction(READ_ONLY,
                handle -> handle.attach(McpOAuthTokenDAO.class).findByHash(tokenHash));
        if (row == null) {
            return;
        }

        underFamilyLock(row.familyId(), () -> {
            template.inTransaction(WRITE, handle -> {
                var tokenDao = handle.attach(McpOAuthTokenDAO.class);

                if (TYPE_REFRESH.equals(row.type())) {
                    tokenDao.revokeFamily(row.familyId(), RevokedReason.CLIENT_REQUEST);
                } else {
                    tokenDao.revoke(row.tokenHash(), RevokedReason.CLIENT_REQUEST);
                }

                return null;
            });
            return Optional.empty();
        });
    }

    private <T> Optional<T> underFamilyLock(String familyId, Supplier<Optional<T>> action) {
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(familyId, FAMILY_LOCK),
                Mono.fromSupplier(action).subscribeOn(Schedulers.boundedElastic()),
                config().getRefreshLockLease())
                .blockOptional()
                .orElseGet(Optional::empty);
    }

    public ValidatedToken validateAccessTokenForWorkspace(@NonNull String token, String headerWorkspace) {
        ValidatedToken validated = validateAccessToken(token)
                .orElseThrow(() -> new NotAuthorizedException(TOKEN_TYPE_BEARER));
        if (!workspaceMatches(headerWorkspace, validated.workspaceName())) {
            throw new ClientErrorException("workspace does not match access token", Response.Status.FORBIDDEN);
        }
        return validated;
    }

    public Optional<ValidatedToken> validateAccessToken(@NonNull String token) {
        String tokenHash = McpOAuthTokenUtils.hash(token);
        Instant now = Instant.now();

        return template.inTransaction(READ_ONLY, handle -> {
            McpOAuthToken row = handle.attach(McpOAuthTokenDAO.class).findByHash(tokenHash);

            if (!isActiveAccessToken(row, now)) {
                return Optional.empty();
            }

            return Optional.of(ValidatedToken.builder()
                    .userName(row.userName())
                    .workspaceId(row.workspaceId())
                    .workspaceName(row.workspaceName())
                    .resource(row.resource())
                    .expiresAt(row.expiresAt())
                    .build());
        });
    }

    private TokenResponse buildTokenResponse(String accessToken, String refreshToken, String workspaceId,
            String workspaceName) {
        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType(TOKEN_TYPE_BEARER)
                .expiresIn(config().getAccessTokenTtl().toSeconds())
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .build();
    }

    /**
     * An authorization code is single-use and bound to the exact request that created it: the same
     * {@code client_id} and {@code redirect_uri} must be presented, and the PKCE {@code code_verifier}
     * must hash to the stored {@code code_challenge}. Any mismatch means the presenter is not the
     * legitimate client, so the exchange is rejected as {@code invalid_grant}.
     */
    private static boolean matchesGrantRequest(McpOAuthCode code, String clientId, String redirectUri,
            String codeVerifier) {
        return code.clientId().equals(clientId)
                && code.redirectUri().equals(redirectUri)
                && verifyPkce(codeVerifier, code.codeChallenge());
    }

    /**
     * Distinguishes a harmless client retry from token theft. After rotation the old refresh token is
     * revoked; if the rotation response was lost in transit the client legitimately re-presents it. Such
     * a re-presentation is benign only when the token was revoked specifically for rotation and arrives
     * within the configured grace window. Anything else is treated as reuse of a compromised token.
     */
    private boolean isBenignRotationRetry(McpOAuthToken token, Instant now) {
        return token.revokedReason() == RevokedReason.ROTATED
                && !now.isAfter(token.revokedAt().plus(config().getRefreshRotationGrace()));
    }

    /**
     * When a refresh token minted now expires: {@code refreshTokenTtl} from now (OAuth 2.1 §4.3.3 ties refresh
     * expiry to inactivity, so an active connector stays connected), never past the family's absolute expiry, which
     * the authorization fixed at {@code refreshTokenAbsoluteTtl} and every rotation carries forward.
     */
    private Instant refreshExpiry(Instant now, Instant absoluteExpiresAt) {
        Instant sliding = now.plus(config().getRefreshTokenTtl());
        return sliding.isBefore(absoluteExpiresAt) ? sliding : absoluteExpiresAt;
    }

    /**
     * A family is dead once any of its refresh tokens was revoked for something other than rotation: the client
     * asked for it ({@code /oauth/revoke}) or reuse detection fired. Rotation revocations are the normal lineage
     * and say nothing about the family; access-token-only revocations never touch refresh rows.
     */
    private static boolean isFamilyRevoked(List<McpOAuthToken> family) {
        return family.stream()
                .filter(token -> TYPE_REFRESH.equals(token.type()))
                .anyMatch(token -> token.revokedReason() == RevokedReason.REUSE
                        || token.revokedReason() == RevokedReason.CLIENT_REQUEST);
    }

    /**
     * How many token pairs already descend directly from {@code source}: one for the rotation itself, plus one
     * per in-grace retry served so far. Counted on access rows; every pair has exactly one.
     */
    private static long countDescendantPairs(List<McpOAuthToken> family, McpOAuthToken source) {
        return family.stream()
                .filter(token -> TYPE_ACCESS.equals(token.type()) && source.id().equals(token.rotatedFromId()))
                .count();
    }

    /**
     * An access token grants access only while it exists, is actually an access token, has not been
     * revoked, and has not expired.
     */
    private static boolean isActiveAccessToken(McpOAuthToken token, Instant now) {
        return token != null
                && TYPE_ACCESS.equals(token.type())
                && token.revokedAt() == null
                && token.expiresAt().isAfter(now);
    }

    /**
     * When the caller pins a workspace via header it must match the workspace the access token was issued
     * for; a blank header expresses no preference and always matches.
     */
    private static boolean workspaceMatches(String headerWorkspace, String tokenWorkspace) {
        return StringUtils.isBlank(headerWorkspace) || headerWorkspace.equals(tokenWorkspace);
    }

    /**
     * Verifies a PKCE S256 challenge per RFC 7636. The spec restricts the verifier to ASCII
     * unreserved characters, and Base64URL output is itself ASCII.
     */
    private static boolean verifyPkce(@NonNull String codeVerifier, @NonNull String codeChallenge) {
        String computed;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            computed = URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.US_ASCII),
                codeChallenge.getBytes(StandardCharsets.US_ASCII));
    }
}
