package com.comet.opik.domain;

import com.comet.opik.api.TraceDetails;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.google.common.net.UrlEscapers;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the frontend location an SDK link points to. Each method returns a path relative to the host the link was
 * opened on: the browser resolves it against the same origin, so the response never names a host.
 */
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

    private static final String API_SEGMENT = "/api";
    // Zero or more segments of unreserved or percent-encoded characters, such as "" or "/opik"
    private static final Pattern FRONTEND_BASE_PATH = Pattern
            .compile("(/(?!\\.{1,2}(?:/|$))(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+)*");

    private final @NonNull WorkspaceNameService workspaceNameService;
    private final @NonNull TraceService traceService;
    private final @NonNull DatasetService datasetService;
    private final @NonNull @Config("authentication") AuthenticationConfig authentication;

    @Override
    public String projectRedirectUrl(@NotNull UUID traceId, String workspaceName, @NotNull String opikBEPath) {

        TraceDetails traceDetails = traceService.getTraceDetailsById(traceId).block();
        String resolvedWorkspaceName = Optional.ofNullable(workspaceName).filter(StringUtils::isNotBlank)
                .orElseGet(() -> workspaceNameService.getWorkspaceName(
                        traceDetails.workspaceId(), authentication.getReactService().url()));

        return TRACE_REDIRECT_URL.formatted(frontendBasePath(opikBEPath), pathSegment(resolvedWorkspaceName),
                traceDetails.projectId(), traceId);
    }

    @Override
    public String datasetRedirectUrl(@NotNull UUID datasetId, String workspaceName, @NotNull String OpikBEBaseUrl) {

        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId);

        return DATASET_REDIRECT_URL.formatted(frontendBasePath(OpikBEBaseUrl), pathSegment(resolvedWorkspaceName),
                datasetId);
    }

    @Override
    public String experimentsRedirectUrl(@NotNull UUID datasetId, @NotNull UUID experimentId, String workspaceName,
            @NotNull String OpikBEBaseUrl) {

        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId);

        var experimentIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(experimentId), StandardCharsets.UTF_8);
        return EXPERIMENT_REDIRECT_URL.formatted(frontendBasePath(OpikBEBaseUrl), pathSegment(resolvedWorkspaceName),
                datasetId, experimentIdEncoded);
    }

    @Override
    public String optimizationsRedirectUrl(UUID datasetId, UUID optimizationId, String workspaceName,
            String OpikBEBaseUrl) {
        String resolvedWorkspaceName = resolveWorkspaceNameByDatasetId(workspaceName, datasetId);

        var optimizationIdEncoded = URLEncoder.encode("[\"%s\"]".formatted(optimizationId), StandardCharsets.UTF_8);
        return OPTIMIZATION_REDIRECT_URL.formatted(frontendBasePath(OpikBEBaseUrl), pathSegment(resolvedWorkspaceName),
                datasetId, optimizationIdEncoded);
    }

    private String resolveWorkspaceNameByDatasetId(String workspaceName, UUID datasetId) {
        return Optional.ofNullable(workspaceName).filter(StringUtils::isNotBlank)
                .orElseGet(() -> {
                    String workspaceId = datasetService.findWorkspaceIdByDatasetId(datasetId);
                    return workspaceNameService.getWorkspaceName(workspaceId,
                            authentication.getReactService().url());
                });
    }

    /**
     * The frontend base path from the Opik URL an SDK link carries: the path before its {@code /api} segment, for
     * example {@code /opik} for {@code https://host/opik/api} and empty for {@code http://localhost:5173/api}. Only
     * the path is kept; the scheme and host are dropped.
     */
    private static String frontendBasePath(String opikUrl) {
        String path = rawPath(opikUrl);
        int apiIndex = apiSegmentIndex(path);
        if (apiIndex < 0) {
            throw new BadRequestException("Unexpected Opik URL in path parameter");
        }
        String basePath = path.substring(0, apiIndex);
        if (!FRONTEND_BASE_PATH.matcher(basePath).matches()) {
            throw new BadRequestException("Unexpected Opik URL in path parameter");
        }
        return basePath;
    }

    private static String rawPath(String opikUrl) {
        try {
            return Optional.ofNullable(new URI(opikUrl).getRawPath()).orElse("");
        } catch (URISyntaxException exception) {
            throw new BadRequestException("Unexpected Opik URL in path parameter");
        }
    }

    private static int apiSegmentIndex(String path) {
        int index = path.indexOf(API_SEGMENT);
        while (index >= 0) {
            int end = index + API_SEGMENT.length();
            if (end == path.length() || path.charAt(end) == '/') {
                return index;
            }
            index = path.indexOf(API_SEGMENT, end);
        }
        return -1;
    }

    private static String pathSegment(String value) {
        return UrlEscapers.urlPathSegmentEscaper().escape(value);
    }
}
