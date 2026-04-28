package com.comet.opik.domain;

import com.comet.opik.api.AssertionResultBatchItem;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.events.AssertionResultsCreated;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

@ImplementedBy(AssertionResultServiceImpl.class)
public interface AssertionResultService {

    Mono<Long> insertBatch(@NonNull EntityType entityType, @NonNull List<? extends FeedbackScoreItem> assertionScores);

    Mono<Void> saveBatch(EntityType entityType, List<AssertionResultBatchItem> assertionResults);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AssertionResultServiceImpl implements AssertionResultService {

    private static final Set<EntityType> SUPPORTED_ENTITY_TYPES = Set.of(EntityType.TRACE, EntityType.SPAN);

    private final @NonNull AssertionResultDAO assertionResultDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull EventBus eventBus;

    @Override
    public Mono<Long> insertBatch(@NonNull EntityType entityType,
            @NonNull List<? extends FeedbackScoreItem> assertionScores) {
        return assertionResultDAO.insertBatch(entityType, assertionScores);
    }

    @Override
    public Mono<Void> saveBatch(@NonNull EntityType entityType,
            @NonNull List<AssertionResultBatchItem> assertionResults) {
        if (!SUPPORTED_ENTITY_TYPES.contains(entityType)) {
            return Mono.error(new BadRequestException(
                    "Unsupported entity_type '%s' for assertion-results — supported types: %s"
                            .formatted(entityType, SUPPORTED_ENTITY_TYPES)));
        }
        if (assertionResults.isEmpty()) {
            return Mono.error(new BadRequestException("Argument 'assertionResults' must not be empty"));
        }

        // Validate up front so a bad id fails fast and independently of project-name normalisation.
        assertionResults.forEach(item -> IdGenerator.validateVersion(item.entityId(), entityType.getType()));

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            Set<UUID> entityIds = assertionResults.stream()
                    .map(AssertionResultBatchItem::entityId)
                    .collect(Collectors.toSet());

            Map<String, List<AssertionResultBatchItem>> itemsPerProject = assertionResults.stream()
                    .map(item -> item.toBuilder()
                            .projectName(WorkspaceUtils.getProjectName(item.projectName()))
                            .build())
                    .collect(groupingBy(AssertionResultBatchItem::projectName));

            return projectService.retrieveByNamesOrCreate(itemsPerProject.keySet())
                    .map(ProjectService::groupByName)
                    .flatMap(projectsByName -> Flux.fromIterable(itemsPerProject.entrySet())
                            .flatMap(entry -> {
                                Project project = projectsByName.get(entry.getKey());
                                List<AssertionResultBatchItem> projectItems = entry.getValue().stream()
                                        .map(item -> item.toBuilder().projectId(project.id()).build())
                                        .toList();
                                return assertionResultDAO.saveBatch(entityType, projectItems);
                            })
                            .reduce(0L, Long::sum))
                    .then(Mono.fromRunnable(
                            () -> eventBus.post(
                                    new AssertionResultsCreated(entityIds, entityType, workspaceId, userName))));
        });
    }
}
