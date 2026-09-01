package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.CipxTokenValidationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates a CIPX device token: cost-api owns the signing key and the device registry, so the token is
 * validated by asking it rather than verified locally. Nothing here does crypto, and revocation takes effect
 * within the credentials cache TTL because the registry is re-checked on every cold call.
 * <p>
 * Caches the <em>resolved credential</em> (user, workspace, quotas, permissions, device id) under the token,
 * mirroring the API-key path: a warm request is one cache read with no outbound call. Caching only the
 * validation response would leave every ingest batch from every enrolled machine hitting the react service.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxTokenValidationService {

    private static final String VALIDATE_PATH = "/v1/private/ai-spend/devices/validate";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String INVALID_TOKEN = "CIPX device token is not valid";
    private static final String VALIDATION_UNAVAILABLE = "CIPX device token validation is unavailable";

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record ValidateRequest(String token, String workspaceName) {
    }

    private final @NonNull Client client;
    private final @NonNull OpikConfiguration opikConfig;
    private final @NonNull CacheService cacheService;
    private final @NonNull AuthService authService;
    private final @NonNull Provider<RequestContext> requestContext;

    public void authenticate(@NonNull String token, String headerWorkspace, @NonNull ContextInfoHolder contextInfo) {
        // A blank workspace header is allowed: cost-api resolves the workspace from the token itself, and only
        // rejects a non-blank header that disagrees.
        String requestWorkspaceName = StringUtils.defaultString(headerWorkspace);
        List<String> requiredPermissions = contextInfo.requiredPermissions();

        var cached = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(token, requestWorkspaceName,
                requiredPermissions);
        if (cached.isPresent()) {
            setCredentialIntoContext(cached.get(), requestWorkspaceName);
            return;
        }

        var validated = validate(token, requestWorkspaceName);
        // The same seam the MCP OAuth branch uses: resolves quotas and permissions for the caller and fills the
        // request context.
        authService.authorizeOAuth(validated.toValidatedToken(), contextInfo);
        requestContext.get().setCipxDeviceId(validated.deviceId());
        cacheResolvedCredential(token, requestWorkspaceName, requiredPermissions, validated.deviceId());
    }

    private void setCredentialIntoContext(CacheService.AuthCredentials credentials, String fallbackWorkspaceName) {
        var context = requestContext.get();
        context.setUserName(credentials.userName());
        context.setWorkspaceId(credentials.workspaceId());
        context.setWorkspaceName(Optional.ofNullable(credentials.workspaceName()).orElse(fallbackWorkspaceName));
        context.setQuotas(credentials.quotas());
        context.setPermissions(credentials.permissions() == null ? Set.of() : Set.copyOf(credentials.permissions()));
        context.setCipxDeviceId(credentials.deviceId());
    }

    /**
     * Caches whatever the authorization left in the request context, so the warm path reproduces it exactly.
     * A caller the authorization could not fully resolve is not cached: an entry missing either key cannot be
     * read back, and the next request would rather pay for a fresh resolution than serve a partial one.
     */
    private void cacheResolvedCredential(String token, String requestWorkspaceName, List<String> requiredPermissions,
            String deviceId) {
        var context = requestContext.get();
        if (StringUtils.isBlank(context.getUserName()) || StringUtils.isBlank(context.getWorkspaceId())) {
            return;
        }
        cacheService.cache(token, requestWorkspaceName, requiredPermissions, CacheService.AuthCredentials.builder()
                .userName(context.getUserName())
                .workspaceId(context.getWorkspaceId())
                .workspaceName(context.getWorkspaceName())
                .quotas(context.getQuotas())
                .permissions(List.copyOf(context.getPermissions()))
                .deviceId(deviceId)
                .build());
    }

    private ValidatedCipxToken validate(String token, String workspaceName) {
        var config = config();
        URI target = URI.create(StringUtils.stripEnd(config.getUrl(), "/") + VALIDATE_PATH);

        try (Response response = client.target(target)
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + config.getServiceToken())
                .post(Entity.json(new ValidateRequest(token, workspaceName)))) {

            if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()) {
                throw new ClientErrorException(INVALID_TOKEN, Response.Status.UNAUTHORIZED);
            }
            if (response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
                throw new ClientErrorException(INVALID_TOKEN, Response.Status.FORBIDDEN);
            }
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                log.warn("CIPX token validation replied with status: '{}'", response.getStatus());
                throw new InternalServerErrorException(VALIDATION_UNAVAILABLE);
            }

            var validated = response.readEntity(ValidatedCipxToken.class);
            if (validated == null || StringUtils.isBlank(validated.userName())
                    || StringUtils.isBlank(validated.workspaceId())) {
                log.warn("CIPX token validation returned an incomplete response");
                throw new ClientErrorException(INVALID_TOKEN, Response.Status.UNAUTHORIZED);
            }
            return validated;
        } catch (ProcessingException unreachable) {
            log.warn("Could not reach the CIPX token validator", unreachable);
            throw new InternalServerErrorException(VALIDATION_UNAVAILABLE);
        }
    }

    private CipxTokenValidationConfig config() {
        return opikConfig.getCipxTokenValidation();
    }
}
