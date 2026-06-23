package com.comet.opik.domain;

import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.sorting.SpendUserSortingFactory;
import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendSummaryResponse;
import com.comet.opik.api.spend.SpendUserPage;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@ImplementedBy(AiSpendServiceImpl.class)
public interface AiSpendService {

    Mono<SpendSummaryResponse> getSummary(SpendMetricRequest request);

    Mono<SpendCompositionResponse> getComposition(SpendMetricRequest request);

    Mono<SpendBreakdownResponse> getBreakdown(SpendMetricRequest request, String laneKey);

    Mono<SpendUserPage> getUsers(SpendMetricRequest request, List<SortingField> sortingFields, String name, int page,
            int size);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendServiceImpl implements AiSpendService {

    private final @NonNull AiSpendDAO aiSpendDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull SpendUserSortingFactory spendUserSortingFactory;

    @Override
    public Mono<SpendSummaryResponse> getSummary(@NonNull SpendMetricRequest request) {
        return resolveProject(request).flatMap(aiSpendDAO::getSummary);
    }

    @Override
    public Mono<SpendCompositionResponse> getComposition(@NonNull SpendMetricRequest request) {
        return resolveProject(request).flatMap(aiSpendDAO::getComposition);
    }

    @Override
    public Mono<SpendBreakdownResponse> getBreakdown(@NonNull SpendMetricRequest request, @NonNull String laneKey) {
        Optional<SpendLane> inputLane = SpendLane.fromKey(laneKey).filter(SpendLane::hasBreakdown);
        if (inputLane.isPresent()) {
            return resolveProject(request).flatMap(resolved -> aiSpendDAO.getBreakdown(resolved, inputLane.get()));
        }

        OutputLane outputLane = OutputLane.fromKey(laneKey)
                .filter(OutputLane::hasBreakdown)
                .orElseThrow(() -> new BadRequestException("No breakdown available for lane: %s".formatted(laneKey)));
        return resolveProject(request).flatMap(resolved -> aiSpendDAO.getOutputBreakdown(resolved, outputLane));
    }

    @Override
    public Mono<SpendUserPage> getUsers(@NonNull SpendMetricRequest request,
            @NonNull List<SortingField> sortingFields, String name, int page, int size) {
        return resolveProject(request)
                .flatMap(resolved -> aiSpendDAO.getUsers(resolved, sortingFields, name, page, size))
                .map(userPage -> userPage.toBuilder()
                        .sortableBy(spendUserSortingFactory.getSortableFields())
                        .build());
    }

    private Mono<SpendMetricRequest> resolveProject(SpendMetricRequest request) {
        return projectService.resolveProjectIdAndVerifyVisibility(request.projectId(), request.projectName())
                .map(projectId -> request.toBuilder().resolvedProjectId(projectId).build());
    }
}
