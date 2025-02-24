package com.comet.opik.domain;

import com.comet.opik.api.EMErrorResponse;
import com.comet.opik.api.TraceDetails;
import com.comet.opik.infrastructure.DeploymentConfig;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@ImplementedBy(RedirectServiceImpl.class)
public interface RedirectService {

    String projectRedirectUrl(UUID traceId, String workspaceName);

    String datasetRedirectUrl(UUID datasetId, String workspaceName);

    String experimentsRedirectUrl(UUID datasetId, UUID experimentId, String workspaceName);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class RedirectServiceImpl implements RedirectService {

    private static final String DEFAULT_BASE_URL = "http://localhost:5173/";
    private static final String DEFAULT_WORKSPACE_NAME = "default";
    private static final String PROJECT_REDIRECT_URL = "%s/%s/projects/%s/traces";
    private static final String DATASET_REDIRECT_URL = "%s/%s/datasets/%s/items";
    private static final String EXPERIMENT_REDIRECT_URL = "%s/%s/experiments/%s/compare?experiments=%s";

    private final @NonNull Client client;
    private final @NonNull TraceService traceService;
    private final @NonNull DatasetService datasetService;
    private final @NonNull @Config("deployment") DeploymentConfig config;

    @Override
    public String projectRedirectUrl(@NotNull UUID traceId, String workspaceName) {

        TraceDetails traceDetails = traceService.getTraceDetailsById(traceId).block();
        String resolvedWorkspaceName = Optional.ofNullable(workspaceName)
                .orElseGet(() -> getWorkspaceName(traceDetails.workspaceId()));

        return PROJECT_REDIRECT_URL.formatted(feBaseUrl(), resolvedWorkspaceName, traceDetails.projectId());
    }

    @Override
    public String datasetRedirectUrl(@NotNull UUID datasetId, String workspaceName) {

        String resolvedWorkspaceName = Optional.ofNullable(workspaceName)
                .orElseGet(() -> {
                    String workspaceId = datasetService.findWorkspaceIdByDatasetId(datasetId);
                    return getWorkspaceName(workspaceId);
                });

        return DATASET_REDIRECT_URL.formatted(feBaseUrl(), resolvedWorkspaceName, datasetId);
    }

    @Override
    public String experimentsRedirectUrl(@NotNull UUID datasetId, @NotNull UUID experimentId, String workspaceName) {

        String resolvedWorkspaceName = Optional.ofNullable(workspaceName)
                .orElseGet(() -> {
                    String workspaceId = datasetService.findWorkspaceIdByDatasetId(datasetId);
                    return getWorkspaceName(workspaceId);
                });

        var experimentIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(experimentId), StandardCharsets.UTF_8);
        return EXPERIMENT_REDIRECT_URL.formatted(feBaseUrl(), resolvedWorkspaceName, datasetId, experimentIdEncoded);
    }

    private String getWorkspaceName(String workspaceId) {
        if (config.getBaseUrl().equals(DEFAULT_BASE_URL)) {
            return DEFAULT_WORKSPACE_NAME;
        }

        try (var response = client.target(URI.create(config.getBaseUrl()))
                .path("api")
                .path("workspaces")
                .path("workspace-name")
                .queryParam("id", workspaceId)
                .request()
                .get()) {

            return getWorkspaceNameFromResponse(response);
        }
    }

    private String getWorkspaceNameFromResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(String.class);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            var errorResponse = response.readEntity(EMErrorResponse.class);
            throw new ClientErrorException(errorResponse.msg(), Response.Status.BAD_REQUEST);
        }

        log.error("Unexpected error while getting workspace name: {}", response.getStatus());
        throw new InternalServerErrorException();
    }

    private String feBaseUrl() {
        return config.getBaseUrl().equals(DEFAULT_BASE_URL) ? DEFAULT_BASE_URL : config.getBaseUrl() + "/opik";
    }
}
