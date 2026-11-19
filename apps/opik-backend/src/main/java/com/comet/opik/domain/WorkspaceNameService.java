package com.comet.opik.domain;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.URI;
import java.util.Optional;

import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_ID;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;

/**
 * Service responsible for fetching workspace names from the React service.
 */
@Slf4j
@Singleton
public class WorkspaceNameService {

    /**
     * The cache of failed lookups, next to {@code workspace_name_by_id}: {@link #nameOrId} answers with the id while a
     * workspace is in it. Its TTL is {@code cacheManager.caches.workspace_name_lookup_failures}.
     */
    static final String LOOKUP_FAILURES_CACHE = "workspace_name_lookup_failures";

    private final @NonNull Client client;
    private final @NonNull AuthenticationConfig authentication;
    private final @NonNull CacheManager cacheManager;
    private final @NonNull CacheConfiguration cacheConfiguration;

    @Inject
    public WorkspaceNameService(@NonNull Client client,
            @NonNull @Config("authentication") AuthenticationConfig authentication,
            @NonNull CacheManager cacheManager,
            @NonNull @Config("cacheManager") CacheConfiguration cacheConfiguration) {
        this.client = client;
        this.authentication = authentication;
        this.cacheManager = cacheManager;
        this.cacheConfiguration = cacheConfiguration;
    }

    /**
     * The workspace's name, or its id when the lookup fails, the name is blank, or the React service isn't configured.
     * Never throws. A failed lookup is remembered in {@link #LOOKUP_FAILURES_CACHE} and not tried again until it
     * expires: {@link #getWorkspaceName} caches names but not failures, so without this a down React service would be
     * called on every use.
     */
    public String nameOrId(@NonNull String workspaceId) {
        var url = Optional.ofNullable(authentication.getReactService())
                .map(AuthenticationConfig.UrlConfig::url)
                .filter(StringUtils::isNotBlank);
        if (url.isEmpty() || lookupFailed(workspaceId)) {
            return workspaceId;
        }
        try {
            var name = getWorkspaceName(workspaceId, url.get());
            if (StringUtils.isNotBlank(name)) {
                return name;
            }
        } catch (RuntimeException exception) {
            log.warn("Workspace name lookup failed, using the id, workspace '{}'", workspaceId, exception);
        }
        rememberLookupFailure(workspaceId);
        return workspaceId;
    }

    private boolean lookupFailed(String workspaceId) {
        if (!cacheConfiguration.isEnabled()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(cacheManager.getSync(lookupFailureKey(workspaceId), Boolean.class));
        } catch (RuntimeException exception) {
            log.warn("Reading a workspace name lookup failure from the cache failed, workspace '{}'", workspaceId,
                    exception);
            return false;
        }
    }

    private void rememberLookupFailure(String workspaceId) {
        if (!cacheConfiguration.isEnabled()) {
            return;
        }
        var ttl = cacheConfiguration.getCaches().getOrDefault(LOOKUP_FAILURES_CACHE,
                cacheConfiguration.getDefaultDuration());
        try {
            cacheManager.putAsync(lookupFailureKey(workspaceId), true, ttl).exceptionally(exception -> {
                log.warn("Caching a workspace name lookup failure failed, workspace '{}'", workspaceId, exception);
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("Caching a workspace name lookup failure failed, workspace '{}'", workspaceId, exception);
        }
    }

    private static String lookupFailureKey(String workspaceId) {
        return "%s:-%s".formatted(LOOKUP_FAILURES_CACHE, workspaceId);
    }

    /**
     * Retrieves the workspace name for the given workspace ID.
     * Returns the default workspace name for the default workspace ID.
     * For other workspace IDs, fetches the name from the React service.
     *
     * @param workspaceId the workspace ID
     * @param reactServiceBaseUrl the base URL of the React service
     * @return the workspace name
     * @throws NotFoundException if the workspace is not found
     * @throws InternalServerErrorException if an unexpected error occurs
     */
    @Cacheable(name = "workspace_name_by_id", key = "$workspaceId", returnType = String.class)
    public String getWorkspaceName(@NonNull String workspaceId, @NonNull String reactServiceBaseUrl) {
        if (DEFAULT_WORKSPACE_ID.equals(workspaceId)) {
            return DEFAULT_WORKSPACE_NAME;
        }

        log.info("Request react service for workspace name by id: '{}'", workspaceId);
        InstrumentAsyncUtils.Segment segment = startSegment("redirect", "React", "getWorkspaceNameById");
        try (var response = client.target(URI.create(reactServiceBaseUrl))
                .path("workspaces")
                .path("workspace-name")
                .queryParam("id", workspaceId)
                .request()
                .get()) {

            log.info("Request react service for workspace name by id: '{}' completed", workspaceId);
            return getWorkspaceNameFromResponse(response);
        } finally {
            endSegment(segment);
        }
    }

    private String getWorkspaceNameFromResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(String.class);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            log.error("Not found workspace by Id : '{}'", errorResponse.msg());
            throw new NotFoundException(errorResponse.msg());
        }

        log.error("Unexpected error while getting workspace name: '{}'", response.getStatus());
        throw new InternalServerErrorException();
    }
}
