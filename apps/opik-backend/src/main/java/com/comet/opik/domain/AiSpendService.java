package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@ImplementedBy(AiSpendServiceImpl.class)
public interface AiSpendService {

    Mono<WorkspaceMetricsSummaryResponse> getSummary(SpendMetricRequest request);

    Mono<SpendCompositionResponse> getComposition(SpendMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendServiceImpl implements AiSpendService {

    private final @NonNull AiSpendDAO aiSpendDAO;
    private final @NonNull ProjectService projectService;

    @Override
    public Mono<WorkspaceMetricsSummaryResponse> getSummary(@NonNull SpendMetricRequest request) {
        return resolveProject(request)
                .flatMap(aiSpendDAO::getSummary)
                .map(results -> WorkspaceMetricsSummaryResponse.builder().results(results).build());
    }

    @Override
    public Mono<SpendCompositionResponse> getComposition(@NonNull SpendMetricRequest request) {
        return resolveProject(request)
                .flatMap(aiSpendDAO::getComposition);
    }

    private Mono<SpendMetricRequest> resolveProject(SpendMetricRequest request) {
        return projectService.resolveProjectIdAndVerifyVisibility(request.projectId(), request.projectName())
                .map(projectId -> request.toBuilder().resolvedProjectId(projectId).build());
    }
}
