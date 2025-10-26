package com.comet.opik.domain;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_ID;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;

/**
 * Service responsible for fetching workspace names from the React service.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class WorkspaceNameService {

    private final @NonNull Client client;

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
