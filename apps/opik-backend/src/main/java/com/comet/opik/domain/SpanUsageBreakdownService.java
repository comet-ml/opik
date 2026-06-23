package com.comet.opik.domain;

import com.comet.opik.api.UsageByWorkspaceProjectUserResponse;
import com.comet.opik.api.UsageByWorkspaceProjectUserResponse.WorkspaceProjectUserCount;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class SpanUsageBreakdownService {

    private final @NonNull SpanDAO spanDAO;
    private final @NonNull ProjectService projectService;

    @WithSpan
    public Mono<UsageByWorkspaceProjectUserResponse> getSpanBreakdownPerWorkspace() {
        log.info("Getting span usage breakdown by workspace, project and user");
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(spanDAO::countSpansBreakdownPerWorkspace)
                .collectList()
                .flatMap(this::resolveProjectNames)
                .map(rows -> UsageByWorkspaceProjectUserResponse.builder().breakdown(rows).build());
    }

    private Mono<List<WorkspaceProjectUserCount>> resolveProjectNames(List<WorkspaceProjectUserCount> rows) {
        if (rows.isEmpty()) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> enrichWithProjectNames(rows))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<WorkspaceProjectUserCount> enrichWithProjectNames(List<WorkspaceProjectUserCount> rows) {
        Map<String, Map<UUID, String>> projectIdNamesByWorkspace = rows.stream()
                .collect(Collectors.groupingBy(
                        WorkspaceProjectUserCount::workspaceId,
                        Collectors.mapping(WorkspaceProjectUserCount::projectId, Collectors.toSet())))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> projectService.findIdToNameByIds(entry.getKey(), entry.getValue())));

        return rows.stream()
                .map(row -> row.toBuilder()
                        .projectName(projectIdNamesByWorkspace.getOrDefault(row.workspaceId(), Map.of())
                                .get(row.projectId()))
                        .build())
                .toList();
    }
}
