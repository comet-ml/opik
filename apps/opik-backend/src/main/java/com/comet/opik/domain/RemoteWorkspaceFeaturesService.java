package com.comet.opik.domain;

import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.auth.PlatformCredential;
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
 * with, and caches each answer per workspace. Enablement belongs to the workspace's organization, not to the caller,
 * and the caller's access to the workspace is already settled by the time an endpoint asks.
 * <p>
 * A lookup that fails reads as not enabled and is not cached, so an outage withholds a feature rather than grants
 * it, and the next request asks again. The cache is best effort: when it cannot answer, the platform is asked.
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
    private final @NonNull Duration cacheTtl;

    @Override
    public boolean isEnabled(@NonNull RequestContext caller, @NonNull WorkspaceFeature feature) {
        var credential = caller.getPlatformCredential();
        // A device token or a public read was not authenticated by the platform, so there is nobody to ask for.
        if (credential == null || StringUtils.isAnyBlank(caller.getWorkspaceId(), caller.getWorkspaceName())) {
            return false;
        }

        var cacheKey = CACHE_KEY_FORMAT.formatted(caller.getWorkspaceId(), feature.getValue());
        String cached = readCache(cacheKey);
        if (cached != null) {
            return Boolean.parseBoolean(cached);
        }

        boolean enabled;
        try {
            enabled = lookUp(caller.getWorkspaceName(), credential, feature);
        } catch (RuntimeException lookupFailed) {
            log.warn("Could not resolve feature '{}' for workspaceId '{}', treating it as not enabled",
                    feature.getValue(), caller.getWorkspaceId(), lookupFailed);
            return false;
        }

        writeCache(cacheKey, enabled);
        return enabled;
    }

    private String readCache(String cacheKey) {
        if (cacheTtl.isZero()) {
            return null;
        }
        try {
            return redissonClient.<String>getBucket(cacheKey, StringCodec.INSTANCE).get().block();
        } catch (RuntimeException cacheFailed) {
            log.warn("Could not read the workspace feature cache, asking the platform", cacheFailed);
            return null;
        }
    }

    private void writeCache(String cacheKey, boolean enabled) {
        if (cacheTtl.isZero()) {
            return;
        }
        try {
            redissonClient.<String>getBucket(cacheKey, StringCodec.INSTANCE)
                    .set(Boolean.toString(enabled), cacheTtl)
                    .block();
        } catch (RuntimeException cacheFailed) {
            log.warn("Could not write the workspace feature cache", cacheFailed);
        }
    }

    private boolean lookUp(String workspaceName, PlatformCredential credential, WorkspaceFeature feature) {
        var body = Entity.json(new WorkspaceFeaturesRequest(workspaceName, List.of(feature.getValue())));
        try (var response = requestFor(credential).post(body)) {
            if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw new IllegalStateException(
                        "the platform answered the workspace features call with status '%d'"
                                .formatted(response.getStatus()));
            }
            var features = response.readEntity(WorkspaceFeaturesResponse.class).features();
            return features != null && Boolean.TRUE.equals(features.get(feature.getValue()));
        }
    }

    private Invocation.Builder requestFor(PlatformCredential credential) {
        return switch (credential.type()) {
            case API_KEY -> featuresRequest("workspace-features")
                    .header(HttpHeaders.AUTHORIZATION, credential.value());
            case SESSION -> featuresRequest("workspace-features-session")
                    .cookie(RequestContext.SESSION_COOKIE, credential.value());
            case OAUTH_USER -> featuresRequest("workspace-features-by-username")
                    .header(OAUTH_USERNAME_HEADER, credential.value());
        };
    }

    private Invocation.Builder featuresRequest(String path) {
        return client.target(URI.create(reactServiceUrl.url()))
                .path("opik")
                .path(path)
                .request()
                .accept(MediaType.APPLICATION_JSON);
    }
}
