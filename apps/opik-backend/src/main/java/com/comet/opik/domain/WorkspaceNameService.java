package com.comet.opik.domain;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
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
import java.time.Duration;
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

    /** How long {@link #nameOrId} answers with the id after a lookup failed, before it tries again. */
    static final Duration LOOKUP_FAILURE_TTL = Duration.ofMinutes(10);

    private final @NonNull Client client;
    private final @NonNull AuthenticationConfig authentication;
    private final @NonNull Cache<String, Boolean> failedLookups;

    @Inject
    public WorkspaceNameService(@NonNull Client client,
            @NonNull @Config("authentication") AuthenticationConfig authentication) {
        this(client, authentication, Ticker.systemTicker());
    }

    WorkspaceNameService(@NonNull Client client, @NonNull AuthenticationConfig authentication,
            @NonNull Ticker ticker) {
        this.client = client;
        this.authentication = authentication;
        this.failedLookups = CacheBuilder.newBuilder()
                .expireAfterWrite(LOOKUP_FAILURE_TTL)
                .maximumSize(10_000)
                .ticker(ticker)
                .build();
    }

    /**
     * The workspace's name, or its id when the lookup fails, the name is blank, or the React service isn't configured.
     * Never throws. A failed lookup isn't tried again for {@link #LOOKUP_FAILURE_TTL}: {@link #getWorkspaceName}
     * caches names but not failures, so without this a down React service would be called on every use.
     */
    public String nameOrId(@NonNull String workspaceId) {
        var url = Optional.ofNullable(authentication.getReactService())
                .map(AuthenticationConfig.UrlConfig::url)
                .filter(StringUtils::isNotBlank);
        if (url.isEmpty() || failedLookups.getIfPresent(workspaceId) != null) {
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
        failedLookups.put(workspaceId, true);
        return workspaceId;
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
