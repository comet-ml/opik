package com.comet.opik.domain;

import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Project;
import com.comet.opik.api.events.webhooks.AlertEvent;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.AlertEventType.TRACE_GUARDRAILS_TRIGGERED;
import static java.util.stream.Collectors.groupingBy;

@ImplementedBy(GuardrailsServiceImpl.class)
public interface GuardrailsService {
    Mono<Void> addTraceGuardrails(List<Guardrail> guardrails);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailsServiceImpl implements GuardrailsService {
    private final @NonNull ProjectService projectService;
    private final @NonNull GuardrailsDAO guardrailsDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull EventBus eventBus;

    @Override
    public Mono<Void> addTraceGuardrails(List<Guardrail> guardrails) {
        if (guardrails.isEmpty()) {
            return Mono.empty();
        }

        var entityType = EntityType.TRACE;

        // group guardrails by project name to resolve project ids
        Map<String, List<Guardrail>> guardrailsPerProject = guardrails
                .stream()
                .map(guardrail -> {
                    UUID id = idGenerator.generateId();
                    IdGenerator.validateVersion(guardrail.entityId(), entityType.getType()); // validate trace id

                    return guardrail.toBuilder()
                            .id(id)
                            .projectName(WorkspaceUtils.getProjectName(guardrail.projectName()))
                            .build();
                })
                .collect(groupingBy(Guardrail::projectName));

        return projectService.retrieveByNamesOrCreate(guardrailsPerProject.keySet())
                .map(ProjectService::groupByName)
                .map(projectMap -> mergeProjectsAndGuardrails(projectMap, guardrailsPerProject))
                .flatMap(projects -> processGuardrailsBatch(entityType, projects, guardrails.size()))
                .then();
    }

    private List<Pair<Project, List<Guardrail>>> mergeProjectsAndGuardrails(
            Map<String, Project> projectMap, Map<String, List<Guardrail>> guardrailsPerProject) {
        return guardrailsPerProject.keySet()
                .stream()
                .map(projectName -> {
                    Project project = projectMap.get(projectName);
                    return Pair.of(
                            project,
                            guardrailsPerProject.get(projectName)
                                    .stream()
                                    .map(item -> item.toBuilder().projectId(project.id()).build()) // set projectId
                                    .toList());
                })
                .toList();
    }

    private Mono<Long> processGuardrailsBatch(
            EntityType entityType, List<Pair<Project, List<Guardrail>>> projects, int actualBatchSize) {
        return Mono.deferContextual(ctx -> Flux.fromIterable(projects)
                .flatMap(projectDto -> guardrailsDAO.addGuardrails(entityType, projectDto.getValue())
                        .doOnSuccess(__ -> {
                            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                            String workspaceName = ctx.get(RequestContext.WORKSPACE_NAME);
                            String userName = ctx.get(RequestContext.USER_NAME);
                            raiseAlertEventIfApplicable(projectDto.getValue(), workspaceId, workspaceName, userName);
                        }))
                .reduce(0L, Long::sum));
    }

    private void raiseAlertEventIfApplicable(List<Guardrail> guardrails, String workspaceId, String workspaceName,
            String userName) {
        if (CollectionUtils.isEmpty(guardrails)) {
            return;
        }

        var failedGuardrails = guardrails.stream()
                .filter(guardrail -> GuardrailResult.FAILED == guardrail.result())
                .toList();

        if (CollectionUtils.isNotEmpty(failedGuardrails)) {
            var projectId = guardrails.getFirst().projectId();
            eventBus.post(AlertEvent.builder()
                    .eventType(TRACE_GUARDRAILS_TRIGGERED)
                    .workspaceId(workspaceId)
                    .workspaceName(workspaceName)
                    .userName(userName)
                    .projectId(projectId)
                    .payload(failedGuardrails)
                    .build());
        }
    }
}
