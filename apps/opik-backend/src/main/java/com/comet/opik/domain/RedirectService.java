package com.comet.opik.domain;

import com.comet.opik.api.TraceDetails;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

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

    private static final String TRACE_REDIRECT_URL = "%s/%s/projects/%s/traces?tab=logs&logsType=traces&trace=%s";
    private static final String DATASET_REDIRECT_URL = "%s/%s/datasets/%s/items";
    private static final String EXPERIMENT_REDIRECT_URL = "%s/%s/experiments/%s/compare?experiments=%s";
    private static final String OPTIMIZATION_REDIRECT_URL = "%s/%s/optimizations/%s/compare?optimizations=%s";

    private final @NonNull WorkspaceNameService workspaceNameService;
    private final @NonNull TraceService traceService;
    private final @NonNull DatasetService datasetService;
    private final @NonNull @Config("authentication") AuthenticationConfig authentication;

    @Override
    public String projectRedirectUrl(@NotNull UUID traceId, String workspaceName, @NotNull String opikBEPath) {

        TraceDetails traceDetails = traceService.getTraceDetailsById(traceId).block();
        String resolvedWorkspaceName = Optional.ofNullable(workspaceName)
                .orElseGet(() -> workspaceNameService.getWorkspaceName(
                        traceDetails.workspaceId(), authentication.getReactService().url()));

        return TRACE_REDIRECT_URL.formatted(feBaseUrl(opikBEPath), resolvedWorkspaceName,
                traceDetails.projectId(), traceId);
    }

    @Override
    public String datasetRedirectUrl(@NotNull UUID datasetId, String workspaceName, @NotNull String OpikBEBaseUrl) {

        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId);

        return DATASET_REDIRECT_URL.formatted(feBaseUrl(OpikBEBaseUrl), resolvedWorkspaceName, datasetId);
    }

    @Override
    public String experimentsRedirectUrl(@NotNull UUID datasetId, @NotNull UUID experimentId, String workspaceName,
            @NotNull String OpikBEBaseUrl) {

        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId);

        var experimentIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(experimentId), StandardCharsets.UTF_8);
        return EXPERIMENT_REDIRECT_URL.formatted(feBaseUrl(OpikBEBaseUrl), resolvedWorkspaceName, datasetId,
                experimentIdEncoded);
    }

    @Override
    public String optimizationsRedirectUrl(UUID datasetId, UUID optimizationId, String workspaceName,
            String OpikBEBaseUrl) {
        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId);

        var optimizationIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(optimizationId), StandardCharsets.UTF_8);
        return OPTIMIZATION_REDIRECT_URL.formatted(feBaseUrl(OpikBEBaseUrl), resolvedWorkspaceName, datasetId,
                optimizationIdEncoded);
    }

    private String resolveWorkspaceNameByDatasetId(String workspaceName, UUID datasetId) {
        return Optional.ofNullable(workspaceName)
                .orElseGet(() -> {
                    String workspaceId = datasetService.findWorkspaceIdByDatasetId(datasetId);
                    return workspaceNameService.getWorkspaceName(workspaceId,
                            authentication.getReactService().url());
                });
    }

    private String feBaseUrl(String opikBEBaseUrl) {
        return Optional.ofNullable(
                opikBEBaseUrl.contains("/api") ? opikBEBaseUrl.substring(0, opikBEBaseUrl.indexOf("/api")) : null)
                .orElseThrow(() -> new RuntimeException("Unexpected opikBEBaseUrl %s".formatted(opikBEBaseUrl)));
    }
}
