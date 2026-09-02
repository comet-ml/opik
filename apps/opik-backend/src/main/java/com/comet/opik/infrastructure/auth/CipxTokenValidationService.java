package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.CipxTokenValidationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authenticates a CIPX device token: cost-api owns the signing key and the device registry.
 * <p>
 * The validation response carries everything the request context needs, so the caller is resolved from it
 * directly and Platform is not consulted. That is deliberate, not a shortcut: a device token names a
 * machine, not a Comet user, so there is no user for the Platform to authenticate.
 * <p>
 * Caches the resolved caller under the token, mirroring the API-key path, so a warm request is one cache read
 * with no outbound call at all.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxTokenValidationService {

    private static final String VALIDATE_PATH = "/v1/internal/cipx-device-tokens/validate";
    private static final String INVALID_TOKEN = "CIPX device token is not valid";
    private static final String VALIDATION_UNAVAILABLE = "CIPX device token validation is unavailable";
    private static final String NOT_AN_INGEST_ENDPOINT = "CIPX device tokens are accepted on trace and span ingest only";

    private static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /**
     * The only endpoints a device token may reach. Rejection is by this list, never by the absence of a binding.
     * Shape follows {@code RemoteAuthService.PUBLIC_ENDPOINTS}: path regex to allowed methods.
     */
    private static final Map<String, Set<String>> INGEST_ENDPOINTS = Map.of(
            "^/v1/private/spans/batch/?$", Set.of("POST", "PATCH"),
            "^/v1/private/traces/?$", Set.of("POST"),
            "^/v1/private/traces/" + UUID_REGEX + "/?$", Set.of("PATCH"));

    private record ValidateRequest(String token) {
    }

    private final @NonNull Client client;
    private final @NonNull OpikConfiguration opikConfig;
    private final @NonNull CacheService cacheService;
    private final @NonNull Provider<RequestContext> requestContext;

    public void authenticate(@NonNull String token, @NonNull ContextInfoHolder contextInfo) {
        if (!isIngestEndpoint(contextInfo)) {
            log.error("Rejecting CIPX device token outside ingest, method: '{}', path: '{}'", contextInfo.method(),
                    contextInfo.uriInfo().getRequestUri().getPath());
            throw new ClientErrorException(NOT_AN_INGEST_ENDPOINT, Response.Status.FORBIDDEN);
        }

        // Keyed by the token alone: a device's workspace is derived from its enrollment, never supplied.
        // No required permissions are passed: nothing verified any, so nothing may be cached as granted.
        var cached = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(token, "", List.of());
        if (cached.isPresent()) {
            setCredentialIntoContext(cached.get());
            return;
        }

        var validated = validate(token);
        var credentials = CacheService.AuthCredentials.builder()
                .userName(validated.userName())
                .workspaceId(validated.workspaceId())
                .workspaceName(validated.workspaceName())
                .quotas(List.of())
                .permissions(List.of())
                .deviceId(validated.deviceId())
                .build();
        setCredentialIntoContext(credentials);
        cacheService.cache(token, "", List.of(), credentials);
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
    private void setCredentialIntoContext(CacheService.AuthCredentials credentials) {
        var context = requestContext.get();
        context.setUserName(credentials.userName());
        context.setWorkspaceId(credentials.workspaceId());
        context.setWorkspaceName(credentials.workspaceName());
        context.setQuotas(credentials.quotas());
        context.setPermissions(credentials.permissions() == null ? Set.of() : Set.copyOf(credentials.permissions()));
        context.setCipxDeviceId(credentials.deviceId());
    }

    private boolean isIngestEndpoint(ContextInfoHolder contextInfo) {
        String path = contextInfo.uriInfo().getRequestUri().getPath();
        return INGEST_ENDPOINTS.entrySet().stream()
                .anyMatch(entry -> path.matches(entry.getKey()) && entry.getValue().contains(contextInfo.method()));
    }

    private ValidatedCipxToken validate(String token) {
        var config = config();
        URI target = URI.create(StringUtils.stripEnd(config.getUrl(), "/") + VALIDATE_PATH);

        try (Response response = client.target(target)
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .post(Entity.json(new ValidateRequest(token)))) {

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
