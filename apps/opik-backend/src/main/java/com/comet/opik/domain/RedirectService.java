package com.comet.opik.domain;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.TraceDetails;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_ID;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;

@ImplementedBy(RedirectServiceImpl.class)
public interface RedirectService {

    String projectRedirectUrl(UUID traceId, String workspaceName, String opikBEPath);

    String datasetRedirectUrl(UUID datasetId, String workspaceName, String opikBEPath);

    String experimentsRedirectUrl(UUID datasetId, UUID experimentId, String workspaceName, String opikBEPath);

    String optimizationsRedirectUrl(UUID datasetId, UUID optimizationId, String workspaceName, String opikBEPath);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class RedirectServiceImpl implements RedirectService {

    private static final String PROJECT_REDIRECT_URL = "%s/%s/projects/%s/traces";
    private static final String DATASET_REDIRECT_URL = "%s/%s/datasets/%s/items";
    private static final String EXPERIMENT_REDIRECT_URL = "%s/%s/experiments/%s/compare?experiments=%s";
    private static final String OPTIMIZATION_REDIRECT_URL = "%s/%s/optimizations/%s/compare?optimizations=%s";

    private final @NonNull Client client;
    private final @NonNull TraceService traceService;
    private final @NonNull DatasetService datasetService;

    @Override
    public String projectRedirectUrl(@NotNull UUID traceId, String workspaceName, @NotNull String opikBEPath) {

        TraceDetails traceDetails = traceService.getTraceDetailsById(traceId).block();
        String resolvedWorkspaceName = Optional.ofNullable(workspaceName)
                .orElseGet(() -> getWorkspaceName(traceDetails.workspaceId(), opikBEPath));

        return PROJECT_REDIRECT_URL.formatted(feBaseUrl(opikBEPath), resolvedWorkspaceName,
                traceDetails.projectId());
    }

    @Override
    public String datasetRedirectUrl(@NotNull UUID datasetId, String workspaceName, @NotNull String OpikBEBaseUrl) {

        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId, OpikBEBaseUrl);

        return DATASET_REDIRECT_URL.formatted(feBaseUrl(OpikBEBaseUrl), resolvedWorkspaceName, datasetId);
    }

    @Override
    public String experimentsRedirectUrl(@NotNull UUID datasetId, @NotNull UUID experimentId, String workspaceName,
            @NotNull String OpikBEBaseUrl) {

        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId, OpikBEBaseUrl);

        var experimentIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(experimentId), StandardCharsets.UTF_8);
        return EXPERIMENT_REDIRECT_URL.formatted(feBaseUrl(OpikBEBaseUrl), resolvedWorkspaceName, datasetId,
                experimentIdEncoded);
    }

    @Override
    public String optimizationsRedirectUrl(UUID datasetId, UUID optimizationId, String workspaceName,
            String OpikBEBaseUrl) {
        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId, OpikBEBaseUrl);

        var optimizationIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(optimizationId), StandardCharsets.UTF_8);
        return OPTIMIZATION_REDIRECT_URL.formatted(feBaseUrl(OpikBEBaseUrl), resolvedWorkspaceName, datasetId,
                optimizationIdEncoded);
    }

    private String resolveWorkspaceNameByDatasetId(String workspaceName, UUID datasetId, String OpikBEBaseUrl) {
        return Optional.ofNullable(workspaceName)
                .orElseGet(() -> {
                    String workspaceId = datasetService.findWorkspaceIdByDatasetId(datasetId);
                    return getWorkspaceName(workspaceId, OpikBEBaseUrl);
                });
    }

    private String getWorkspaceName(String workspaceId, String opikBEPath) {
        if (DEFAULT_WORKSPACE_ID.equals(workspaceId)) {
            return DEFAULT_WORKSPACE_NAME;
        }

        log.info("Request react service for workspace name by id: {}, opikBEPath {}", workspaceId, opikBEPath);
        InstrumentAsyncUtils.Segment segment = startSegment("redirect", "React", "getWorkspaceNameById");
        try (var response = client.target(URI.create(getReactBaseUrl(opikBEPath)))
                .path("workspaces")
                .path("workspace-name")
                .queryParam("id", workspaceId)
                .request()
                .get()) {

            log.info("Request react service for workspace name by id: {}, opikBEPath {} completed", workspaceId,
                    opikBEPath);
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
            log.error("Not found workspace by Id : {}", errorResponse.msg());
            throw new NotFoundException(errorResponse.msg());
        }

        log.error("Unexpected error while getting workspace name: {}", response.getStatus());
        throw new InternalServerErrorException();
    }

    private String feBaseUrl(String opikBEBaseUrl) {
        return Optional.ofNullable(
                opikBEBaseUrl.contains("/api") ? opikBEBaseUrl.substring(0, opikBEBaseUrl.indexOf("/api")) : null)
                .orElseThrow(() -> new RuntimeException("Unexpected opikBEBaseUrl %s".formatted(opikBEBaseUrl)));
    }

    private String getReactBaseUrl(String opikBEBaseUrl) {
        return opikBEBaseUrl.replace("/opik", "");
    }
}
