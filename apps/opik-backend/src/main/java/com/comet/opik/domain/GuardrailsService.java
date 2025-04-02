package com.comet.opik.domain;

import com.comet.opik.api.GuardrailBatchItem;
import com.comet.opik.api.GuardrailsCheck;
import com.comet.opik.api.Project;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.ErrorUtils.failWithNotFound;
import static java.util.stream.Collectors.groupingBy;

@ImplementedBy(GuardrailsServiceImpl.class)
public interface GuardrailsService {
    Flux<GuardrailsCheck> getTraceGuardrails(UUID traceId);
    Mono<Void> addTraceGuardrails(List<GuardrailBatchItem> guardrails);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailsServiceImpl implements GuardrailsService {
    private final @NonNull ProjectService projectService;
    private final @NonNull GuardrailsDAO guardrailsDAO;

    @Override
    public Flux<GuardrailsCheck> getTraceGuardrails(UUID traceId) {
        throw new NotImplementedException();
    }

    @Override
    public Mono<Void> addTraceGuardrails(List<GuardrailBatchItem> guardrails) {
        if (guardrails.isEmpty()) {
            return Mono.empty();
        }

        var entityType = EntityType.TRACE;

        // group guardrails by project name to resolve project ids
        Map<String, List<GuardrailBatchItem>> guardrailsPerProject = guardrails
                .stream()
                .map(guardrail -> {
                    IdGenerator.validateVersion(guardrail.id(), entityType.getType()); // validate trace id

                    return guardrail.toBuilder()
                            .projectName(WorkspaceUtils.getProjectName(guardrail.projectName()))
                            .build();
                })
                .collect(groupingBy(GuardrailBatchItem::projectName));

        return projectService.retrieveByNamesOrCreate(guardrailsPerProject.keySet())
                .map(ProjectService::groupByName)
                .map(projectMap -> mergeProjectsAndGuardrails(projectMap, guardrailsPerProject))
                .flatMap(projects -> processGuardrailsBatch(entityType, projects, guardrails.size()))
                .then();
    }

    private List<Pair<Project, List<GuardrailBatchItem>>> mergeProjectsAndGuardrails(
            Map<String, Project> projectMap, Map<String, List<GuardrailBatchItem>> guardrailsPerProject) {
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
            EntityType entityType, List<Pair<Project, List<GuardrailBatchItem>>> projects, int actualBatchSize) {
        return Flux.fromIterable(projects)
                .flatMap(projectDto -> guardrailsDAO.addGuardrails(entityType, projectDto.getValue()))
                .reduce(0L, Long::sum)
                .flatMap(rowsUpdated -> rowsUpdated == actualBatchSize ? Mono.just(rowsUpdated) : Mono.empty())
                .switchIfEmpty(Mono.error(failWithNotFound("Error while processing guardrails batch")));
    }
}
