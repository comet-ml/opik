package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

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

    private final @NonNull TransactionTemplate template;
    private final @NonNull OpikConfiguration opikConfig;

    private McpOAuthConfig config() {
        return opikConfig.getMcpOAuth();
    }

    public String createAuthorizationCode(@NonNull CreateOAuthCodeCommand cmd) {
        String rawCode = McpOAuthTokens.generateCode();
        Instant now = Instant.now();

        var code = McpOAuthCode.builder()
                .id(UUID.randomUUID().toString())
                .codeHash(McpOAuthTokens.hash(rawCode))
                .clientId(cmd.clientId())
                .userName(cmd.userName())
                .workspaceName(cmd.workspaceName())
                .workspaceId(cmd.workspaceId())
                .codeChallenge(cmd.codeChallenge())
                .codeChallengeMethod(CODE_CHALLENGE_METHOD_S256)
                .redirectUri(cmd.redirectUri())
                .resource(cmd.resource())
                .expiresAt(now.plus(config().getCodeTtl()))
                .build();

        template.inTransaction(WRITE, handle -> {
            handle.attach(McpOAuthCodeDAO.class).save(code);
            return null;
        });

        return rawCode;
    }

    public TokenResponse exchangeCode(@NonNull String code, @NonNull String codeVerifier,
            @NonNull String redirectUri, @NonNull String clientId) {
        String codeHash = McpOAuthTokens.hash(code);
        Instant now = Instant.now();

        // Burn the code in its own committed transaction: a thrown exception rolls the enclosing
        // transaction back, so single-use consumption must commit before the client/redirect/PKCE
        // checks below — a failed exchange attempt must not leave the code replayable.
        McpOAuthCode row = template.inTransaction(WRITE, handle -> {
            var codeDao = handle.attach(McpOAuthCodeDAO.class);
            if (codeDao.markUsed(codeHash) != 1) {
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }
            return codeDao.findByHash(codeHash);
        });

        if (!row.clientId().equals(clientId) || !row.redirectUri().equals(redirectUri)
                || !verifyPkce(codeVerifier, row.codeChallenge())) {
            throw new BadRequestException(ERROR_INVALID_GRANT);
        }

        String familyId = UUID.randomUUID().toString();
        String accessToken = McpOAuthTokens.generateAccessToken();
        String refreshToken = McpOAuthTokens.generateRefreshToken();

        return template.inTransaction(WRITE, handle -> {
            var tokenDao = handle.attach(McpOAuthTokenDAO.class);
            tokenDao.save(McpOAuthToken.builder()
                    .id(UUID.randomUUID().toString())
                    .tokenHash(McpOAuthTokens.hash(accessToken))
                    .type(TYPE_ACCESS)
                    .clientId(clientId)
                    .userName(row.userName())
                    .workspaceName(row.workspaceName())
                    .workspaceId(row.workspaceId())
                    .resource(row.resource())
                    .familyId(familyId)
                    .expiresAt(now.plus(config().getAccessTokenTtl()))
                    .build());
            tokenDao.save(McpOAuthToken.builder()
                    .id(UUID.randomUUID().toString())
                    .tokenHash(McpOAuthTokens.hash(refreshToken))
                    .type(TYPE_REFRESH)
                    .clientId(clientId)
                    .userName(row.userName())
                    .workspaceName(row.workspaceName())
                    .workspaceId(row.workspaceId())
                    .resource(row.resource())
                    .familyId(familyId)
                    .expiresAt(now.plus(config().getRefreshTokenTtl()))
                    .build());

            return buildTokenResponse(accessToken, refreshToken, row.workspaceId(), row.workspaceName());
        });
    }

    public TokenResponse refresh(@NonNull String refreshToken, @NonNull String clientId) {
        String tokenHash = McpOAuthTokens.hash(refreshToken);
        Instant now = Instant.now();

        McpOAuthToken row = template.inTransaction(READ_ONLY,
                handle -> handle.attach(McpOAuthTokenDAO.class).findByHash(tokenHash));

        if (row == null || !TYPE_REFRESH.equals(row.type()) || !row.clientId().equals(clientId)
                || !row.expiresAt().isAfter(now)) {
            throw new BadRequestException(ERROR_INVALID_GRANT);
        }

        if (row.revokedAt() != null) {
            boolean benignRetry = row.revokedReason() == RevokedReason.ROTATED
                    && !now.isAfter(row.revokedAt().plus(config().getRefreshRotationGrace()));
            if (!benignRetry) {
                // Reuse detected: kill the whole lineage. Must run in its own committed transaction —
                // throwing invalid_grant from inside the same transaction would roll the revocation back.
                template.inTransaction(WRITE, handle -> handle.attach(McpOAuthTokenDAO.class)
                        .revokeFamily(row.familyId(), RevokedReason.REUSE));
            }
            throw new BadRequestException(ERROR_INVALID_GRANT);
        }

        String accessToken = McpOAuthTokens.generateAccessToken();
        String newRefreshToken = McpOAuthTokens.generateRefreshToken();

        return template.inTransaction(WRITE, handle -> {
            var tokenDao = handle.attach(McpOAuthTokenDAO.class);

            // Atomic rotation guard: only the request that flips revoked_at mints the next pair; a
            // concurrent duplicate sees 0 rows and gets invalid_grant (same outcome as the grace path).
            if (tokenDao.revoke(row.tokenHash(), RevokedReason.ROTATED) != 1) {
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }

            tokenDao.save(McpOAuthToken.builder()
                    .id(UUID.randomUUID().toString())
                    .tokenHash(McpOAuthTokens.hash(accessToken))
                    .type(TYPE_ACCESS)
                    .clientId(clientId)
                    .userName(row.userName())
                    .workspaceName(row.workspaceName())
                    .workspaceId(row.workspaceId())
                    .resource(row.resource())
                    .familyId(row.familyId())
                    .rotatedFromId(row.id())
                    .expiresAt(now.plus(config().getAccessTokenTtl()))
                    .build());
            tokenDao.save(McpOAuthToken.builder()
                    .id(UUID.randomUUID().toString())
                    .tokenHash(McpOAuthTokens.hash(newRefreshToken))
                    .type(TYPE_REFRESH)
                    .clientId(clientId)
                    .userName(row.userName())
                    .workspaceName(row.workspaceName())
                    .workspaceId(row.workspaceId())
                    .resource(row.resource())
                    .familyId(row.familyId())
                    .rotatedFromId(row.id())
                    .expiresAt(row.expiresAt())
                    .build());

            return buildTokenResponse(accessToken, newRefreshToken, row.workspaceId(), row.workspaceName());
        });
    }

    public void revoke(@NonNull String token) {
        String tokenHash = McpOAuthTokens.hash(token);

        template.inTransaction(WRITE, handle -> {
            var tokenDao = handle.attach(McpOAuthTokenDAO.class);

            McpOAuthToken row = tokenDao.findByHash(tokenHash);
            if (row == null) {
                return null;
            }

            if (TYPE_REFRESH.equals(row.type())) {
                tokenDao.revokeFamily(row.familyId(), RevokedReason.CLIENT_REQUEST);
            } else {
                tokenDao.revoke(row.tokenHash(), RevokedReason.CLIENT_REQUEST);
            }

            return null;
        });
    }

    public ValidatedToken validateAccessTokenForWorkspace(@NonNull String token, String headerWorkspace) {
        ValidatedToken validated = validateAccessToken(token)
                .orElseThrow(() -> new NotAuthorizedException(TOKEN_TYPE_BEARER));
        if (StringUtils.isNotBlank(headerWorkspace) && !headerWorkspace.equals(validated.workspaceName())) {
            throw new ClientErrorException("workspace does not match access token", Response.Status.FORBIDDEN);
        }
        return validated;
    }

    public Optional<ValidatedToken> validateAccessToken(@NonNull String token) {
        String tokenHash = McpOAuthTokens.hash(token);
        Instant now = Instant.now();

        return template.inTransaction(READ_ONLY, handle -> {
            McpOAuthToken row = handle.attach(McpOAuthTokenDAO.class).findByHash(tokenHash);

            if (row == null || !TYPE_ACCESS.equals(row.type()) || row.revokedAt() != null
                    || !row.expiresAt().isAfter(now)) {
                return Optional.empty();
            }

            return Optional.of(new ValidatedToken(
                    row.userName(), row.workspaceId(), row.workspaceName(), row.resource()));
        });
    }

    private TokenResponse buildTokenResponse(String accessToken, String refreshToken, String workspaceId,
            String workspaceName) {
        return new TokenResponse(accessToken, refreshToken, TOKEN_TYPE_BEARER,
                config().getAccessTokenTtl().toSeconds(), workspaceId, workspaceName);
    }

    private static boolean verifyPkce(String codeVerifier, String codeChallenge) {
        String computed;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            computed = URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        if (codeChallenge == null) {
            return false;
        }
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.US_ASCII),
                codeChallenge.getBytes(StandardCharsets.US_ASCII));
    }
}
