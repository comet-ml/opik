package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.CipxTokenValidationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.inject.Inject;
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

/**
 * Resolves a CIPX device token to its caller attributes by asking cost-api, which owns the signing key and the
 * device registry. Nothing is verified locally: revocation and org standing are re-checked on every validation,
 * so no crypto and no key distribution live here.
 * <p>
 * Results go through the same credentials cache and TTL as API keys, so a batch of ingest requests from one
 * machine costs one validation call and revocation still lands within that TTL.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxTokenValidationService {

    private static final String VALIDATE_PATH = "/v1/private/ai-spend/devices/validate";
    private static final String INVALID_TOKEN = "CIPX device token is not valid";
    private static final String VALIDATION_UNAVAILABLE = "CIPX device token validation is unavailable";

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record ValidateRequest(String token, String workspaceName) {
    }

    private final @NonNull Client client;
    private final @NonNull OpikConfiguration opikConfig;
    private final @NonNull CacheService cacheService;

    public ValidatedCipxToken validateTokenForWorkspace(@NonNull String token, String headerWorkspace) {
        // A blank workspace header is allowed: cost-api resolves the workspace from the token itself, and only
        // rejects a non-blank header that disagrees.
        String requestWorkspaceName = StringUtils.defaultString(headerWorkspace);

        var cached = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(token, requestWorkspaceName, List.of());
        if (cached.isPresent()) {
            return ValidatedCipxToken.builder()
                    .userName(cached.get().userName())
                    .workspaceId(cached.get().workspaceId())
                    .workspaceName(cached.get().workspaceName())
                    .deviceId(cached.get().deviceId())
                    .build();
        }

        var validated = validate(token, requestWorkspaceName);
        cacheService.cache(token, requestWorkspaceName, List.of(), CacheService.AuthCredentials.builder()
                .userName(validated.userName())
                .workspaceId(validated.workspaceId())
                .workspaceName(validated.workspaceName())
                .deviceId(validated.deviceId())
                .build());
        return validated;
    }

    private ValidatedCipxToken validate(String token, String workspaceName) {
        var config = config();
        URI target = URI.create(StringUtils.stripEnd(config.getUrl(), "/") + VALIDATE_PATH);

        try (Response response = client.target(target)
                .request()
                .accept(MediaType.APPLICATION_JSON)
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
