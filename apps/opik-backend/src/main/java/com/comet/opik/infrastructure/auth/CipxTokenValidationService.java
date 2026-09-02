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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates a CIPX device token: cost-api owns the signing key and the device registry, so the token is
 * validated by asking it rather than verified locally. Nothing here does crypto, and revocation takes effect
 * within the credentials cache TTL because the registry is re-checked on every cold call.
 * <p>
 * The validation response carries everything the request context needs, so the caller is resolved from it
 * directly and the react service is not consulted. That is deliberate, not a shortcut: a device token names a
 * machine, not a Comet user, so there is no user for the react service to authenticate.
 * <p>
 * Caches the resolved caller under the token, mirroring the API-key path, so a warm request is one cache read
 * with no outbound call at all.
 * <p>
 * The validator lives under {@code /v1/internal/} deliberately, and must stay there. cost-api's public routing
 * contract is the {@code /v1/private/ai-spend/} prefix, which nginx forwards to it from the internet; an
 * endpoint that answers a caller's token with a user name, workspace and device id would be a validation
 * oracle behind nothing but the service credential. Nothing routes {@code /v1/internal/} publicly, and this
 * endpoint's only caller is in-cluster.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxTokenValidationService {

    private static final String VALIDATE_PATH = "/v1/internal/cipx-device-tokens/validate";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String INVALID_TOKEN = "CIPX device token is not valid";
    private static final String VALIDATION_UNAVAILABLE = "CIPX device token validation is unavailable";
    private static final String NOT_AN_INGEST_ENDPOINT = "CIPX device tokens are accepted on trace and span ingest only";

    private static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /**
     * The only endpoints a device token may reach: what the cipx shipper actually calls. This is the whole
     * authorization for the credential -- resolving the caller from the validation response means no path or
     * permission check happens anywhere else, so without an explicit allowlist a device token would
     * authenticate for every {@code /v1/private/*} endpoint, reads and deletes included. Rejection is by this
     * list, never by the absence of a binding. Shape follows {@code RemoteAuthService.PUBLIC_ENDPOINTS}: path
     * regex to allowed methods.
     */
    private static final Map<String, Set<String>> INGEST_ENDPOINTS = Map.of(
            "^/v1/private/spans/batch/?$", Set.of("POST", "PATCH"),
            "^/v1/private/traces/?$", Set.of("POST"),
            "^/v1/private/traces/" + UUID_REGEX + "/?$", Set.of("PATCH"));

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record ValidateRequest(String token, String workspaceName) {
    }

    private final @NonNull Client client;
    private final @NonNull OpikConfiguration opikConfig;
    private final @NonNull CacheService cacheService;
    private final @NonNull Provider<RequestContext> requestContext;

    public void authenticate(@NonNull String token, String headerWorkspace, @NonNull ContextInfoHolder contextInfo) {
        // Checked before the token is even validated, so a device token learns nothing from a non-ingest path
        // and a misdirected request costs no outbound call.
        if (!isIngestEndpoint(contextInfo)) {
            log.info("Rejecting CIPX device token outside ingest, method: '{}', path: '{}'", contextInfo.method(),
                    contextInfo.uriInfo().getRequestUri().getPath());
            throw new ClientErrorException(NOT_AN_INGEST_ENDPOINT, Response.Status.FORBIDDEN);
        }

        // A blank workspace header is allowed: cost-api resolves the workspace from the token itself, and only
        // rejects a non-blank header that disagrees.
        String requestWorkspaceName = StringUtils.defaultString(headerWorkspace);

        // No required permissions are passed: nothing verified any, so nothing may be cached as granted.
        var cached = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(token, requestWorkspaceName, List.of());
        if (cached.isPresent()) {
            setCredentialIntoContext(cached.get(), requestWorkspaceName);
            return;
        }

        var validated = validate(token, requestWorkspaceName);
        var credentials = CacheService.AuthCredentials.builder()
                .userName(validated.userName())
                .workspaceId(validated.workspaceId())
                .workspaceName(validated.workspaceName())
                .quotas(List.of())
                .permissions(List.of())
                .deviceId(validated.deviceId())
                .build();
        setCredentialIntoContext(credentials, requestWorkspaceName);
        cacheService.cache(token, requestWorkspaceName, List.of(), credentials);
    }

    /**
     * Fills the request context straight from the validation response.
     * <p>
     * <b>Quotas and permissions are deliberately empty.</b> A device token identifies a machine enrolled to an
     * enterprise AI-Spend workspace, not a Comet user, so there is nobody for the react service to resolve a
     * role or a quota for. Two consequences, both accepted rather than overlooked: {@code @UsageLimited} cannot
     * trip for this credential, and {@code @RequiredPermissions} goes unverified. What bounds the credential
     * instead is {@link #INGEST_ENDPOINTS} -- it may reach the four ingest endpoints and nothing else. Do not
     * "fix" this by calling the react service: it has no user to authenticate here, and the call fails.
     */
    private void setCredentialIntoContext(CacheService.AuthCredentials credentials, String fallbackWorkspaceName) {
        var context = requestContext.get();
        context.setUserName(credentials.userName());
        context.setWorkspaceId(credentials.workspaceId());
        context.setWorkspaceName(Optional.ofNullable(credentials.workspaceName()).orElse(fallbackWorkspaceName));
        context.setQuotas(credentials.quotas());
        context.setPermissions(credentials.permissions() == null ? Set.of() : Set.copyOf(credentials.permissions()));
        context.setCipxDeviceId(credentials.deviceId());
    }

    private boolean isIngestEndpoint(ContextInfoHolder contextInfo) {
        String path = contextInfo.uriInfo().getRequestUri().getPath();
        return INGEST_ENDPOINTS.entrySet().stream()
                .anyMatch(entry -> path.matches(entry.getKey()) && entry.getValue().contains(contextInfo.method()));
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
