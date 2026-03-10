package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.ExperimentGroupEnrichInfoHolder;
import com.comet.opik.api.Project;
import com.comet.opik.api.grouping.GroupBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.grouping.GroupingFactory.DATASET_ID;
import static com.comet.opik.api.grouping.GroupingFactory.PROJECT_ID;
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ExperimentGroupEnricher {

    private final @NonNull DatasetService datasetService;
    private final @NonNull ProjectService projectService;

    public Mono<ExperimentGroupEnrichInfoHolder> getEnrichInfoHolder(List<List<String>> allGroupValues,
            List<GroupBy> groups, String workspaceId) {
        Set<UUID> datasetIds = extractUuidsFromGroupValues(allGroupValues, groups, DATASET_ID);
        Set<UUID> projectIds = extractUuidsFromGroupValues(allGroupValues, groups, PROJECT_ID);

        Mono<Map<UUID, Dataset>> datasetsMono = loadEntityMap(
                () -> datasetService.findByIds(datasetIds, workspaceId),
                this::getDatasetMap);

        Mono<Map<UUID, Project>> projectsMono = loadEntityMap(
                () -> projectService.findByIds(workspaceId, projectIds),
                this::getProjectMap);

        return Mono.zip(datasetsMono, projectsMono)
                .map(tuple -> ExperimentGroupEnrichInfoHolder.builder()
                        .datasetMap(tuple.getT1())
                        .projectMap(tuple.getT2())
                        .build());
    }

    private <T, R> Mono<Map<UUID, R>> loadEntityMap(
            Callable<List<T>> serviceCall,
            Function<List<T>, Map<UUID, R>> mapper) {
        return Mono.fromCallable(serviceCall)
                .subscribeOn(Schedulers.boundedElastic())
                .map(mapper);
    }

    private Set<UUID> extractUuidsFromGroupValues(List<List<String>> allGroupValues, List<GroupBy> groups,
            String fieldName) {
        int nestingIdx = groups.stream()
                .filter(g -> fieldName.equals(g.field()))
                .findFirst()
                .map(groups::indexOf)
                .orElse(-1);

        if (nestingIdx == -1) {
            return Set.of();
        }

        return allGroupValues.stream()
                .map(groupValues -> groupValues.get(nestingIdx))
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .filter(s -> !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(s))
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    private Map<UUID, Dataset> getDatasetMap(List<Dataset> datasets) {
        return datasets.stream()
                .collect(Collectors.toMap(Dataset::id, Function.identity()));
    }

    private Map<UUID, Project> getProjectMap(List<Project> projects) {
        return projects.stream()
                .collect(Collectors.toMap(Project::id, Function.identity()));
    }
}
