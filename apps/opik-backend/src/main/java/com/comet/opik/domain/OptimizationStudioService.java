package com.comet.opik.domain;

import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.LogItem;
import com.comet.opik.api.OptimizationStudioRun;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.daytona.DaytonaClient;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

@ImplementedBy(OptimizationStudioServiceImpl.class)
public interface OptimizationStudioService {
    Mono<UUID> create(OptimizationStudioRun run);

    Mono<OptimizationStudioRun> getById(UUID id);

    Mono<Void> createLogs(UUID runId, List<LogItem> logs);

    Mono<LogItem.LogPage> getLogs(LogCriteria criteria);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationStudioServiceImpl implements OptimizationStudioService {

    private final @NonNull DaytonaClient daytonaClient;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull OpikConfiguration config;
    private final @NonNull OptimizationStudioLogsDAO logsDAO;
    private final @NonNull OptimizationStudioRunDAO runDAO;

    @Override
    public Mono<UUID> create(@NonNull OptimizationStudioRun run) {
        var id = run.id() != null ? run.id() : UUID.randomUUID();
        var optimizationId = run.optimizationId() != null ? run.optimizationId() : UUID.randomUUID();
        var context = requestContext.get();
        String workspaceId = context.getWorkspaceId();
        String userName = context.getUserName() != null ? context.getUserName() : "unknown";

        log.info("Creating optimization studio run '{}', id '{}', optimization_id '{}', workspace '{}'",
                run.name(), id, optimizationId, workspaceId);

        // Build the run object with all required fields
        var runToSave = run.toBuilder()
                .id(id)
                .optimizationId(optimizationId)
                .build();

        // First persist the run to the database
        return runDAO.insert(workspaceId, runToSave, userName)
                .then(Mono.fromCallable(() -> {
                    // Get user context for authentication
                    String apiKey = context.getApiKey();

                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalStateException("API key not found in request context");
                    }

                    // Create Daytona sandbox
                    String sandboxId = daytonaClient.createSandbox(
                            id,
                            userName,
                            workspaceId,
                            apiKey,
                            config.getDaytona().getUrl());

                    log.info("Created Daytona sandbox '{}' for optimization run '{}'", sandboxId, id);

                    // Execute optimization script in the sandbox (includes session creation and execution)
                    log.info("About to execute optimization in sandbox '{}' for run '{}'", sandboxId, id);

                    // Serialize prompt to JSON string
                    String promptJson = run.prompt() != null ? JsonUtils.writeValueAsString(run.prompt()) : null;

                    var executeResponse = daytonaClient.executeOptimization(
                            sandboxId,
                            id,
                            optimizationId,
                            run.datasetName(),
                            run.algorithm().name(),
                            run.metric(),
                            promptJson,
                            apiKey,
                            workspaceId);
                    log.info("Execute optimization response: cmdId='{}', output='{}', exitCode={}, error='{}'",
                            executeResponse.cmdId(), executeResponse.output(), executeResponse.exitCode(), executeResponse.error());

                    log.info("Started optimization execution in sandbox '{}' for run '{}' with command ID '{}'",
                            sandboxId, id, executeResponse.cmdId());

                    return id;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<OptimizationStudioRun> getById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Getting optimization studio run by id '{}', workspace '{}'", id, workspaceId);
        return runDAO.findById(workspaceId, id);
    }

    @Override
    public Mono<Void> createLogs(@NonNull UUID runId, @NonNull List<LogItem> logs) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating {} logs for optimization run '{}', workspace '{}'", logs.size(), runId, workspaceId);

        return logsDAO.insertLogs(workspaceId, runId, logs);
    }

    @Override
    public Mono<LogItem.LogPage> getLogs(@NonNull LogCriteria criteria) {
        String workspaceId = requestContext.get().getWorkspaceId();
        UUID runId = criteria.entityId();
        int page = criteria.page() != null ? criteria.page() : 1;
        int size = criteria.size() != null ? criteria.size() : 100;

        log.info("Getting logs for optimization run '{}', workspace '{}', page '{}', size '{}'",
                runId, workspaceId, page, size);

        return logsDAO.findLogs(workspaceId, criteria)
                .collectList()
                .map(logs -> {
                    log.info("Found {} logs for optimization run '{}'", logs.size(), runId);
                    // For simplicity, return total = size since we don't have a count query
                    // In production, you'd want a separate count query
                    return LogItem.LogPage.builder()
                            .content(logs)
                            .page(page)
                            .size(logs.size())
                            .total(logs.size())
                            .build();
                });
    }
}
