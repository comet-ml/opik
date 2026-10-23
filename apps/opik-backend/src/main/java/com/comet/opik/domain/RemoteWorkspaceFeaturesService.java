package com.comet.opik.domain;

import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;

/**
 * Asks the platform's workspace features API on the caller's behalf, with the credential the caller authenticated
 * with, and caches each answer per workspace for the authentication cache's TTL. Enablement belongs to the
 * workspace's organization, not to the caller, and the caller's access to the workspace is already settled by the
 * time an endpoint asks.
 * <p>
 * A lookup that fails reads as not enabled and is not cached, so an outage withholds a feature rather than grants
 * it, and the next request asks again.
 */
@Slf4j
@RequiredArgsConstructor
public class RemoteWorkspaceFeaturesService implements WorkspaceFeaturesService {

    record WorkspaceFeaturesRequest(String workspaceName, List<String> features) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorkspaceFeaturesResponse(Map<String, Boolean> features) {
    }

    private static final String CACHE_KEY_FORMAT = "workspaceFeature-%s-%s";

    private final @NonNull Client client;
    private final @NonNull AuthenticationConfig.UrlConfig reactServiceUrl;
    private final @NonNull RedissonReactiveClient redissonClient;
    private final int cacheTtlInSeconds;

    @Override
    public boolean isEnabled(@NonNull RequestContext caller, @NonNull WorkspaceFeature feature) {
        if (!isPlatformUser(caller)) {
            return false;
        }
        var cacheKey = CACHE_KEY_FORMAT.formatted(caller.getWorkspaceId(), feature.getValue());
        if (cacheTtlInSeconds > 0) {
            String cached = redissonClient.<String>getBucket(cacheKey, StringCodec.INSTANCE).get().block();
            if (cached != null) {
                return Boolean.parseBoolean(cached);
            }
        }

        boolean enabled;
        try {
            enabled = lookUp(caller, feature);
        } catch (RuntimeException lookupFailed) {
            log.warn("Could not resolve feature '{}' for workspaceId '{}', treating it as not enabled",
                    feature.getValue(), caller.getWorkspaceId(), lookupFailed);
            return false;
        }

        if (cacheTtlInSeconds > 0) {
            redissonClient.<String>getBucket(cacheKey, StringCodec.INSTANCE)
                    .set(Boolean.toString(enabled), Duration.ofSeconds(cacheTtlInSeconds))
                    .block();
        }
        return enabled;
    }

    /** A device token or a public read names no platform user, so there is nobody to ask on behalf of. */
    private static boolean isPlatformUser(RequestContext caller) {
        return caller.getCipxDeviceId() == null
                && caller.getVisibility() != Visibility.PUBLIC
                && StringUtils.isNotBlank(caller.getWorkspaceId())
                && StringUtils.isNotBlank(caller.getWorkspaceName())
                && StringUtils.isNotBlank(caller.getUserName());
    }

    private boolean lookUp(RequestContext caller, WorkspaceFeature feature) {
        var body = Entity.json(new WorkspaceFeaturesRequest(caller.getWorkspaceName(), List.of(feature.getValue())));
        try (var response = requestFor(caller).post(body)) {
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw new IllegalStateException(
                        "the platform answered the workspace features call with status '%d'"
                                .formatted(response.getStatus()));
            }
            var features = response.readEntity(WorkspaceFeaturesResponse.class).features();
            return features != null && Boolean.TRUE.equals(features.get(feature.getValue()));
        }
    }

    /**
     * The same credential the caller authenticated with. RemoteAuthService keeps it as {@code apiKey}: the api key
     * itself, which is also the request's Authorization header, or the session token; OAuth callers have none and
     * are named by user.
     */
    private Invocation.Builder requestFor(RequestContext caller) {
        String credential = caller.getApiKey();
        if (credential == null) {
            return featuresRequest("workspace-features-by-username").header(OAUTH_USERNAME_HEADER,
                    caller.getUserName());
        }
        String authorization = caller.getHeaders() == null
                ? null
                : caller.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (credential.equals(authorization)) {
            return featuresRequest("workspace-features").header(HttpHeaders.AUTHORIZATION, credential);
        }
        return featuresRequest("workspace-features-session").cookie(RequestContext.SESSION_COOKIE, credential);
    }

    private Invocation.Builder featuresRequest(String path) {
        return client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path(path)
                .request()
                .accept(MediaType.APPLICATION_JSON);
    }
}
