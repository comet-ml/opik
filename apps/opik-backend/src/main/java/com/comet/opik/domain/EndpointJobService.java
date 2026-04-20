package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobMetadata;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.RunnerType;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBatch;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RMapReactive;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.queue.DequeMoveArgs;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(EndpointJobServiceImpl.class)
public interface EndpointJobService {

    void registerAgents(UUID runnerId, String workspaceId, String userName, Map<String, LocalRunner.Agent> agents);

    UUID createJob(String workspaceId, String userName, CreateLocalRunnerJobRequest request);

    Mono<LocalRunnerJob> nextJob(UUID runnerId, String workspaceId, String userName);

    LocalRunnerJob.LocalRunnerJobPage listJobs(UUID runnerId, UUID projectId, String workspaceId, String userName,
            int page, int size);

    LocalRunnerJob getJob(UUID jobId, String workspaceId, String userName);

    List<LocalRunnerLogEntry> getJobLogs(UUID jobId, int offset, String workspaceId, String userName);

    void appendLogs(UUID jobId, String workspaceId, String userName, List<LocalRunnerLogEntry> entries);

    void reportResult(UUID jobId, String workspaceId, String userName, LocalRunnerJobResultRequest result);

    void cancelJob(UUID jobId, String workspaceId, String userName);

    void failOrphanedJobs(UUID runnerId);

    void reapStuckJobs(UUID runnerId);

    List<UUID> processHeartbeat(UUID runnerId);
}

@Slf4j
@Singleton
class EndpointJobServiceImpl implements EndpointJobService {

    private static final Set<LocalRunnerJobStatus> TERMINAL_JOB_STATUSES = Set.of(LocalRunnerJobStatus.COMPLETED,
            LocalRunnerJobStatus.FAILED);
    private static final Set<LocalRunnerJobStatus> REPORTABLE_JOB_STATUSES = Set.of(LocalRunnerJobStatus.RUNNING,
            LocalRunnerJobStatus.COMPLETED, LocalRunnerJobStatus.FAILED);

    static String jobKey(UUID jobId) {
        return "opik:runners:job:" + jobId;
    }

    static String jobLogsKey(UUID jobId) {
        return "opik:runners:job:" + jobId + ":logs";
    }

    static String pendingJobsKey(UUID runnerId) {
        return "opik:runners:jobs:" + runnerId + ":pending";
    }

    static String activeJobsKey(UUID runnerId) {
        return "opik:runners:jobs:" + runnerId + ":active";
    }

    static String runnerJobsKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId + ":jobs";
    }

    static String runnerCancellationsKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId + ":cancellations";
    }

    private static final String FIELD_ID = "id";
    private static final String FIELD_RUNNER_ID = "runner_id";
    private static final String FIELD_AGENT_NAME = "agent_name";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_WORKSPACE_ID = "workspace_id";
    private static final String FIELD_PROJECT_ID = "project_id";
    private static final String FIELD_CREATED_AT = "created_at";
    private static final String FIELD_STARTED_AT = "started_at";
    private static final String FIELD_COMPLETED_AT = "completed_at";
    private static final String FIELD_RETRY_COUNT = "retry_count";
    private static final String FIELD_MAX_RETRIES = "max_retries";
    private static final String FIELD_INPUTS = "inputs";
    private static final String FIELD_RESULT = "result";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_TRACE_ID = "trace_id";
    private static final String FIELD_TIMEOUT = "timeout";
    private static final String FIELD_MASK_ID = "mask_id";
    private static final String FIELD_BLUEPRINT_NAME = "blueprint_name";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_LAST_HEARTBEAT = "last_heartbeat";

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull RedissonReactiveClient reactiveRedisClient;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull RunnerService runnerService;
    private final @NonNull LocalRunnerConfig runnerConfig;
    private final @NonNull AnalyticsService analyticsService;

    @Inject
    EndpointJobServiceImpl(@NonNull StringRedisClient redisClient, @NonNull RedissonReactiveClient reactiveRedisClient,
            @NonNull IdGenerator idGenerator, @NonNull RunnerService runnerService,
            @NonNull LocalRunnerConfig runnerConfig, @NonNull AnalyticsService analyticsService) {
        this.redisClient = redisClient;
        this.reactiveRedisClient = reactiveRedisClient;
        this.idGenerator = idGenerator;
        this.runnerService = runnerService;
        this.runnerConfig = runnerConfig;
        this.analyticsService = analyticsService;
    }

    @Override
    public void registerAgents(@NonNull UUID runnerId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull Map<String, LocalRunner.Agent> agents) {
        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);

        if (agents.size() > runnerConfig.getMaxAgentsPerRunner()) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Too many agents. Maximum is " + runnerConfig.getMaxAgentsPerRunner())))
                    .build());
        }

        String key = RunnerServiceImpl.runnerAgentsKey(runnerId);
        RMap<String, String> agentsMap = redisClient.getMap(key);
        agentsMap.delete();

        if (!agents.isEmpty()) {
            Map<String, String> serialized = new HashMap<>();
            agents.forEach((name, agent) -> serialized.put(name, JsonUtils.writeValueAsString(agent)));
            agentsMap.putAll(serialized);
        }
    }

    @Override
    public UUID createJob(@NonNull String workspaceId, @NonNull String userName,
            @NonNull CreateLocalRunnerJobRequest request) {
        String runnerIdStr = runnerService.getActiveRunnerId(
                workspaceId, request.projectId(), userName, RunnerType.ENDPOINT);

        if (runnerIdStr == null) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorMessage(List.of(
                            "No runner configured. Pair one from the Runners page.")))
                    .build());
        }
        UUID runnerId = UUID.fromString(runnerIdStr);

        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);

        if (!runnerService.isRunnerAlive(runnerId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of(
                            "Your runner is not connected. Start it with: opik connect")))
                    .build());
        }

        RMap<String, String> agentsMap = redisClient.getMap(RunnerServiceImpl.runnerAgentsKey(runnerId));
        if (!agentsMap.containsKey(request.agentName())) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Agent '%s' is not registered on this runner".formatted(request.agentName()))))
                    .build());
        }

        RList<String> pendingJobs = redisClient.getList(pendingJobsKey(runnerId));
        if (pendingJobs.size() >= runnerConfig.getMaxPendingJobsPerRunner()) {
            throw new ClientErrorException(Response.status(429)
                    .entity(new ErrorMessage(List.of("Too many pending jobs for this runner")))
                    .build());
        }

        UUID jobId = idGenerator.generateId();
        String now = Instant.now().toString();

        Map<String, String> jobFields = new HashMap<>();
        jobFields.put(FIELD_ID, jobId.toString());
        jobFields.put(FIELD_RUNNER_ID, runnerId.toString());
        jobFields.put(FIELD_AGENT_NAME, request.agentName());
        jobFields.put(FIELD_STATUS, LocalRunnerJobStatus.PENDING.getValue());
        jobFields.put(FIELD_PROJECT_ID, request.projectId().toString());
        jobFields.put(FIELD_WORKSPACE_ID, workspaceId);
        jobFields.put(FIELD_CREATED_AT, now);
        jobFields.put(FIELD_RETRY_COUNT, "0");
        jobFields.put(FIELD_MAX_RETRIES, "1");
        if (request.inputs() != null) {
            jobFields.put(FIELD_INPUTS, JsonUtils.writeValueAsString(request.inputs()));
        }
        if (request.maskId() != null) {
            jobFields.put(FIELD_MASK_ID, request.maskId().toString());
        }
        if (request.blueprintName() != null) {
            jobFields.put(FIELD_BLUEPRINT_NAME, request.blueprintName().strip());
        }
        if (request.metadata() != null) {
            jobFields.put(FIELD_METADATA, JsonUtils.writeValueAsString(request.metadata()));
        }

        int timeout = resolveAgentTimeout(runnerId, request.agentName());
        jobFields.put(FIELD_TIMEOUT, String.valueOf(timeout));

        RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId));
        jobMap.putAll(jobFields);

        pendingJobs.add(jobId.toString());
        RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(runnerJobsKey(runnerId));
        runnerJobs.add(Instant.now().toEpochMilli(), jobId.toString());

        analyticsService.trackEvent("opik_sandbox_job_created", Map.of(
                "workspace_id", workspaceId,
                "project_id", request.projectId().toString(),
                "job_id", jobId.toString(),
                "agent_name", request.agentName()));

        return jobId;
    }

    @Override
    public Mono<LocalRunnerJob> nextJob(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull String userName) {
        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);

        String pendingKey = pendingJobsKey(runnerId);
        String activeKey = activeJobsKey(runnerId);

        RBlockingDeque<String> blockingDeque = redisClient.getBlockingDeque(pendingKey);
        Duration timeout = Duration.ofSeconds(runnerConfig.getNextJobPollTimeout().toSeconds());

        return Mono.defer(() -> Mono.fromCompletionStage(
                blockingDeque.moveAsync(timeout, DequeMoveArgs.pollFirst().addLastTo(activeKey))))
                .filter(jobIdStr -> jobIdStr != null)
                .flatMap(jobIdStr -> {
                    RMapReactive<String, String> jobMap = reactiveRedisClient.getMap(
                            jobKey(UUID.fromString(jobIdStr)), StringCodec.INSTANCE);
                    String now = Instant.now().toString();

                    return jobMap.put(FIELD_STATUS, LocalRunnerJobStatus.RUNNING.getValue())
                            .then(jobMap.put(FIELD_STARTED_AT, now))
                            .then(jobMap.put(FIELD_LAST_HEARTBEAT, now))
                            .then(jobMap.readAllMap())
                            .map(this::buildRunnerJob);
                });
    }

    @Override
    public LocalRunnerJob.LocalRunnerJobPage listJobs(@NonNull UUID runnerId, UUID projectId,
            @NonNull String workspaceId, @NonNull String userName, int page, int size) {
        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);
        RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(runnerJobsKey(runnerId));

        if (projectId != null) {
            return listJobsWithFilter(runnerJobs, projectId, workspaceId, page, size);
        }

        int total = runnerJobs.size();
        int fromIndex = page * size;
        int toIndex = fromIndex + size - 1;

        var jobIds = runnerJobs.valueRangeReversed(fromIndex, toIndex);

        List<LocalRunnerJob> jobs = new ArrayList<>();
        for (String jobIdStr : jobIds) {
            UUID jobId = UUID.fromString(jobIdStr);
            RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId));
            Map<String, String> fields = jobMap.readAllMap();
            if (fields.isEmpty()) {
                continue;
            }
            if (!workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
                continue;
            }
            jobs.add(buildRunnerJob(fields));
        }

        return LocalRunnerJob.LocalRunnerJobPage.builder()
                .page(page).size(size).total(total).content(jobs).build();
    }

    @Override
    public LocalRunnerJob getJob(@NonNull UUID jobId, @NonNull String workspaceId, @NonNull String userName) {
        var job = loadValidatedJob(jobId, workspaceId, userName);
        return buildRunnerJob(job.fields());
    }

    @Override
    public List<LocalRunnerLogEntry> getJobLogs(@NonNull UUID jobId, int offset, @NonNull String workspaceId,
            @NonNull String userName) {
        loadValidatedJob(jobId, workspaceId, userName);

        RList<String> logsList = redisClient.getList(jobLogsKey(jobId));
        int logSize = logsList.size();
        if (offset >= logSize) {
            return List.of();
        }

        List<String> entries = logsList.range(offset, logSize - 1);
        return entries.stream()
                .map(json -> JsonUtils.readValue(json, LocalRunnerLogEntry.class))
                .toList();
    }

    @Override
    public void appendLogs(@NonNull UUID jobId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull List<LocalRunnerLogEntry> entries) {
        if (entries.size() > runnerConfig.getMaxLogEntriesPerBatch()) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Too many log entries. Maximum per batch is " + runnerConfig.getMaxLogEntriesPerBatch())))
                    .build());
        }

        loadValidatedJob(jobId, workspaceId, userName);

        RList<String> logsList = redisClient.getList(jobLogsKey(jobId));
        List<String> serialized = entries.stream()
                .map(JsonUtils::writeValueAsString)
                .toList();
        logsList.addAll(serialized);
    }

    @Override
    public void reportResult(@NonNull UUID jobId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull LocalRunnerJobResultRequest result) {
        LocalRunnerJobStatus resultStatus = result.status();
        if (!REPORTABLE_JOB_STATUSES.contains(resultStatus)) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Invalid result status. Must be one of: " + REPORTABLE_JOB_STATUSES)))
                    .build());
        }

        var job = loadValidatedJob(jobId, workspaceId, userName);
        RMap<String, String> jobMap = job.map();
        Map<String, String> fields = job.fields();

        String currentStatus = fields.get(FIELD_STATUS);
        LocalRunnerJobStatus current = parseJobStatus(currentStatus);
        if (current != null
                && (TERMINAL_JOB_STATUSES.contains(current) || current == LocalRunnerJobStatus.CANCELLED)) {
            return;
        }

        if (result.result() != null) {
            jobMap.put(FIELD_RESULT, JsonUtils.writeValueAsString(result.result()));
        }
        if (result.error() != null) {
            jobMap.put(FIELD_ERROR, result.error());
        }
        if (result.traceId() != null) {
            jobMap.put(FIELD_TRACE_ID, result.traceId().toString());
        }

        if (TERMINAL_JOB_STATUSES.contains(resultStatus)) {
            jobMap.put(FIELD_STATUS, resultStatus.getValue());
            jobMap.put(FIELD_COMPLETED_AT, Instant.now().toString());

            String runnerIdStr = fields.get(FIELD_RUNNER_ID);
            if (runnerIdStr != null) {
                UUID runnerId = UUID.fromString(runnerIdStr);
                RList<String> activeList = redisClient.getList(activeJobsKey(runnerId));
                activeList.remove(jobId.toString());
            }

            expireJobAndLogs(jobId, jobMap);
        }
    }

    @Override
    public void cancelJob(@NonNull UUID jobId, @NonNull String workspaceId, @NonNull String userName) {
        var job = loadValidatedJob(jobId, workspaceId, userName);
        RMap<String, String> jobMap = job.map();
        Map<String, String> fields = job.fields();

        String currentStatus = fields.get(FIELD_STATUS);
        LocalRunnerJobStatus current = parseJobStatus(currentStatus);
        if (current != null
                && (TERMINAL_JOB_STATUSES.contains(current) || current == LocalRunnerJobStatus.CANCELLED)) {
            return;
        }

        jobMap.put(FIELD_STATUS, LocalRunnerJobStatus.CANCELLED.getValue());
        jobMap.put(FIELD_COMPLETED_AT, Instant.now().toString());

        String runnerIdStr = fields.get(FIELD_RUNNER_ID);
        if (runnerIdStr != null) {
            UUID runnerId = UUID.fromString(runnerIdStr);
            RList<String> pendingJobs = redisClient.getList(pendingJobsKey(runnerId));
            boolean wasInPending = pendingJobs.remove(jobId.toString());

            if (wasInPending) {
                expireJobAndLogs(jobId, jobMap);
            } else {
                RSet<String> cancellations = redisClient.getSet(runnerCancellationsKey(runnerId));
                cancellations.add(jobId.toString());
            }
        }
    }

    @Override
    public void failOrphanedJobs(@NonNull UUID runnerId) {
        RList<String> activeJobs = redisClient.getList(activeJobsKey(runnerId));
        List<String> activeJobIds = activeJobs.readAll();
        for (String jobIdStr : activeJobIds) {
            failJob(UUID.fromString(jobIdStr), "Runner disconnected");
        }
        activeJobs.delete();

        RList<String> pendingJobs = redisClient.getList(pendingJobsKey(runnerId));
        List<String> pendingJobIds = pendingJobs.readAll();
        for (String jobIdStr : pendingJobIds) {
            failJob(UUID.fromString(jobIdStr), "Runner disconnected");
        }
        pendingJobs.delete();

        redisClient.getScoredSortedSet(runnerJobsKey(runnerId)).delete();
    }

    @Override
    public void reapStuckJobs(@NonNull UUID runnerId) {
        RList<String> activeJobs = redisClient.getList(activeJobsKey(runnerId));
        List<String> activeJobIds = activeJobs.readAll();
        Instant now = Instant.now();

        for (String jobIdStr : activeJobIds) {
            UUID jobId = UUID.fromString(jobIdStr);
            RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId));
            if (!jobMap.isExists()) {
                activeJobs.remove(jobIdStr);
                continue;
            }

            String startedAtStr = jobMap.get(FIELD_STARTED_AT);
            if (startedAtStr == null) {
                continue;
            }

            Instant startedAt = Instant.parse(startedAtStr);
            long timeoutSeconds = runnerConfig.getJobTimeout().toSeconds();
            String timeoutStr = jobMap.get(FIELD_TIMEOUT);
            if (timeoutStr != null) {
                try {
                    timeoutSeconds = Integer.parseInt(timeoutStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid timeout value '{}' for job {}, using default {}s", timeoutStr, jobId,
                            timeoutSeconds);
                }
            }

            if (Duration.between(startedAt, now).getSeconds() > timeoutSeconds) {
                log.warn("Job {} on runner {} exceeded timeout of {}s", jobId, runnerId, timeoutSeconds);
                failJob(jobId, "Job timed out after " + timeoutSeconds + "s");
                activeJobs.remove(jobIdStr);
            }
        }
    }

    @Override
    public List<UUID> processHeartbeat(@NonNull UUID runnerId) {
        RList<String> activeJobs = redisClient.getList(activeJobsKey(runnerId));
        List<String> activeJobIds = activeJobs.readAll();
        if (!activeJobIds.isEmpty()) {
            String now = Instant.now().toString();
            RBatch batch = redisClient.createBatch();
            for (String jobIdStr : activeJobIds) {
                UUID jobId = UUID.fromString(jobIdStr);
                batch.<String, String>getMap(jobKey(jobId), StringCodec.INSTANCE)
                        .putAsync(FIELD_LAST_HEARTBEAT, now);
            }
            batch.execute();
        }

        RSet<String> cancellations = redisClient.getSet(runnerCancellationsKey(runnerId));
        Set<String> cancelledIds = cancellations.readAll();
        cancellations.delete();

        return cancelledIds.stream().map(UUID::fromString).toList();
    }

    // --- Private methods ---

    private void failJob(UUID jobId, String error) {
        RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId));
        if (!jobMap.isExists()) {
            return;
        }
        String status = jobMap.get(FIELD_STATUS);
        LocalRunnerJobStatus current = parseJobStatus(status);
        if (current == LocalRunnerJobStatus.COMPLETED || current == LocalRunnerJobStatus.FAILED
                || current == LocalRunnerJobStatus.CANCELLED) {
            return;
        }
        jobMap.put(FIELD_STATUS, LocalRunnerJobStatus.FAILED.getValue());
        jobMap.put(FIELD_ERROR, error);
        jobMap.put(FIELD_COMPLETED_AT, Instant.now().toString());

        expireJobAndLogs(jobId, jobMap);
    }

    private void expireJobAndLogs(UUID jobId, RMap<String, String> jobMap) {
        Duration ttl = runnerConfig.getCompletedJobTtl().toJavaDuration();
        jobMap.expire(ttl);
        RList<String> logsList = redisClient.getList(jobLogsKey(jobId));
        logsList.expire(ttl);
    }

    private int resolveAgentTimeout(UUID runnerId, String agentName) {
        try {
            RMap<String, String> agentsMap = redisClient.getMap(RunnerServiceImpl.runnerAgentsKey(runnerId));
            String agentJson = agentsMap.get(agentName);
            if (agentJson != null) {
                JsonNode meta = JsonUtils.getJsonNodeFromString(agentJson);
                if (meta.has("timeout") && meta.get("timeout").isNumber() && meta.get("timeout").asInt() > 0) {
                    return meta.get("timeout").asInt();
                }
            }
        } catch (Exception e) {
            log.info("Could not resolve agent timeout for agent '{}' on runner '{}'", agentName, runnerId, e);
        }
        return (int) runnerConfig.getJobTimeout().toSeconds();
    }

    private record ValidatedJob(RMap<String, String> map, Map<String, String> fields) {
    }

    private ValidatedJob loadValidatedJob(UUID jobId, String workspaceId, String userName) {
        RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId));
        Map<String, String> fields = jobMap.readAllMap();
        if (fields.isEmpty()) {
            throw new NotFoundException("Job not found: '%s'".formatted(jobId));
        }
        if (!workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
            throw new NotFoundException("Job not found: '%s'".formatted(jobId));
        }
        String runnerIdStr = fields.get(FIELD_RUNNER_ID);
        if (runnerIdStr == null) {
            throw new NotFoundException("Job not found: '%s'".formatted(jobId));
        }
        RScoredSortedSet<String> userRunners = redisClient.getScoredSortedSet(
                RunnerServiceImpl.workspaceUserRunnersKey(workspaceId, userName));
        if (!userRunners.contains(runnerIdStr)) {
            throw new NotFoundException("Job not found: '%s'".formatted(jobId));
        }
        return new ValidatedJob(jobMap, fields);
    }

    private LocalRunnerJob buildRunnerJob(Map<String, String> fields) {
        JsonNode inputs = RunnerServiceImpl.parseJsonNode(fields.get(FIELD_INPUTS));
        JsonNode result = RunnerServiceImpl.parseJsonNode(fields.get(FIELD_RESULT));

        return LocalRunnerJob.builder()
                .id(UUID.fromString(fields.get(FIELD_ID)))
                .runnerId(UUID.fromString(fields.get(FIELD_RUNNER_ID)))
                .agentName(fields.get(FIELD_AGENT_NAME))
                .status(parseJobStatus(fields.get(FIELD_STATUS)))
                .inputs(inputs)
                .result(result)
                .error(fields.get(FIELD_ERROR))
                .projectId(RunnerServiceImpl.parseUUID(fields.get(FIELD_PROJECT_ID)))
                .traceId(RunnerServiceImpl.parseUUID(fields.get(FIELD_TRACE_ID)))
                .maskId(RunnerServiceImpl.parseUUID(fields.get(FIELD_MASK_ID)))
                .blueprintName(fields.get(FIELD_BLUEPRINT_NAME))
                .metadata(parseMetadata(fields.get(FIELD_METADATA)))
                .timeout(RunnerServiceImpl.parseIntValue(fields.get(FIELD_TIMEOUT)))
                .createdAt(RunnerServiceImpl.parseInstant(fields.get(FIELD_CREATED_AT)))
                .startedAt(RunnerServiceImpl.parseInstant(fields.get(FIELD_STARTED_AT)))
                .completedAt(RunnerServiceImpl.parseInstant(fields.get(FIELD_COMPLETED_AT)))
                .build();
    }

    private LocalRunnerJob.LocalRunnerJobPage listJobsWithFilter(RScoredSortedSet<String> runnerJobs,
            UUID projectId, String workspaceId, int page, int size) {
        var allJobIds = runnerJobs.valueRangeReversed(0, -1);

        List<LocalRunnerJob> matched = new ArrayList<>();
        for (String jobIdStr : allJobIds) {
            UUID jobId = UUID.fromString(jobIdStr);
            RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId));
            Map<String, String> fields = jobMap.readAllMap();
            if (fields.isEmpty() || !workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
                continue;
            }
            if (!projectId.toString().equals(fields.get(FIELD_PROJECT_ID))) {
                continue;
            }
            matched.add(buildRunnerJob(fields));
        }

        int total = matched.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);

        return LocalRunnerJob.LocalRunnerJobPage.builder()
                .page(page).size(size).total(total).content(matched.subList(fromIndex, toIndex)).build();
    }

    private LocalRunnerJobStatus parseJobStatus(String value) {
        if (value == null) {
            return null;
        }
        for (LocalRunnerJobStatus s : LocalRunnerJobStatus.values()) {
            if (s.getValue().equals(value)) {
                return s;
            }
        }
        return null;
    }

    private LocalRunnerJobMetadata parseMetadata(String value) {
        if (value == null) {
            return null;
        }
        return JsonUtils.readValue(value, LocalRunnerJobMetadata.class);
    }
}
