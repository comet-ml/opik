package com.comet.opik.domain.mcpoauth;

import com.comet.opik.domain.IdGenerator;
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

    private static final String REASON_ROTATED = "rotated";
    private static final String REASON_REUSE = "reuse";
    private static final String REASON_CLIENT_REQUEST = "client_request";

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull OpikConfiguration opikConfig;

    private McpOAuthConfig config() {
        return opikConfig.getMcpOAuth();
    }

    public String createAuthorizationCode(@NonNull CreateOAuthCodeCommand cmd) {
        String rawCode = McpOAuthTokens.generateCode();
        Instant now = Instant.now();

        var code = McpOAuthCode.builder()
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

        return template.inTransaction(WRITE, handle -> {
            var codeDao = handle.attach(McpOAuthCodeDAO.class);

            if (codeDao.markUsed(codeHash) != 1) {
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }

            McpOAuthCode row = codeDao.findByHash(codeHash);
            if (!row.clientId().equals(clientId) || !row.redirectUri().equals(redirectUri)) {
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }

            if (!verifyPkce(codeVerifier, row.codeChallenge())) {
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }

            String familyId = idGenerator.generateId().toString();
            String accessToken = McpOAuthTokens.generateAccessToken();
            String refreshToken = McpOAuthTokens.generateRefreshToken();

            var tokenDao = handle.attach(McpOAuthTokenDAO.class);
            tokenDao.save(McpOAuthToken.builder()
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

        return template.inTransaction(WRITE, handle -> {
            var tokenDao = handle.attach(McpOAuthTokenDAO.class);

            McpOAuthToken row = tokenDao.findByHash(tokenHash);
            if (row == null || !TYPE_REFRESH.equals(row.type()) || !row.clientId().equals(clientId)) {
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }

            if (!row.expiresAt().isAfter(now)) {
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }

            if (row.revokedAt() != null) {
                boolean benignRetry = REASON_ROTATED.equals(row.revokedReason())
                        && !now.isAfter(row.revokedAt().plus(config().getRefreshRotationGrace()));
                if (!benignRetry) {
                    tokenDao.revokeFamily(row.familyId(), REASON_REUSE);
                }
                throw new BadRequestException(ERROR_INVALID_GRANT);
            }

            String accessToken = McpOAuthTokens.generateAccessToken();
            String newRefreshToken = McpOAuthTokens.generateRefreshToken();

            tokenDao.save(McpOAuthToken.builder()
                    .tokenHash(McpOAuthTokens.hash(accessToken))
                    .type(TYPE_ACCESS)
                    .clientId(clientId)
                    .userName(row.userName())
                    .workspaceName(row.workspaceName())
                    .workspaceId(row.workspaceId())
                    .resource(row.resource())
                    .familyId(row.familyId())
                    .rotatedFrom(row.tokenHash())
                    .expiresAt(now.plus(config().getAccessTokenTtl()))
                    .build());
            tokenDao.save(McpOAuthToken.builder()
                    .tokenHash(McpOAuthTokens.hash(newRefreshToken))
                    .type(TYPE_REFRESH)
                    .clientId(clientId)
                    .userName(row.userName())
                    .workspaceName(row.workspaceName())
                    .workspaceId(row.workspaceId())
                    .resource(row.resource())
                    .familyId(row.familyId())
                    .rotatedFrom(row.tokenHash())
                    .expiresAt(row.expiresAt())
                    .build());

            tokenDao.revoke(row.tokenHash(), REASON_ROTATED);

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
                tokenDao.revokeFamily(row.familyId(), REASON_CLIENT_REQUEST);
            } else {
                tokenDao.revoke(row.tokenHash(), REASON_CLIENT_REQUEST);
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
