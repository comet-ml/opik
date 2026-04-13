package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.BridgeCommand;
import com.comet.opik.api.runner.BridgeCommandBatchResponse;
import com.comet.opik.api.runner.BridgeCommandResultRequest;
import com.comet.opik.api.runner.BridgeCommandStatus;
import com.comet.opik.api.runner.BridgeCommandSubmitRequest;
import com.comet.opik.api.runner.BridgeCommandType;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerConnectRequest;
import com.comet.opik.api.runner.LocalRunnerConnectResponse;
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobMetadata;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.LocalRunnerPairResponse;
import com.comet.opik.api.runner.LocalRunnerStatus;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.redisson.api.BatchResult;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBatch;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RMapReactive;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.queue.DequeMoveArgs;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ImplementedBy(LocalRunnerServiceImpl.class)
public interface LocalRunnerService {

    LocalRunnerPairResponse generatePairingCode(String workspaceId, String userName, UUID projectId);

    LocalRunnerConnectResponse connect(String workspaceId, String userName, LocalRunnerConnectRequest request);

    // Equivalent of connect()'s post-validation steps, for the opik-connect pairing flow.
    // Caller (OpikConnectService) is responsible for authenticating via activation HMAC.
    void activateFromOpikConnect(String workspaceId, String userName, UUID projectId, UUID runnerId,
            String runnerName);

    LocalRunner.LocalRunnerPage listRunners(String workspaceId, String userName, UUID projectId,
            LocalRunnerStatus status, int page, int size);

    LocalRunner getRunner(String workspaceId, String userName, UUID runnerId);

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

    LocalRunnerHeartbeatResponse heartbeat(UUID runnerId, String workspaceId, String userName,
            List<String> capabilities);

    UUID createBridgeCommand(UUID runnerId, String workspaceId, String userName, BridgeCommandSubmitRequest request);

    Mono<BridgeCommandBatchResponse> nextBridgeCommands(UUID runnerId, String workspaceId, String userName,
            int maxCommands);

    void reportBridgeCommandResult(UUID runnerId, String workspaceId, String userName, UUID commandId,
            BridgeCommandResultRequest request);

    BridgeCommand getBridgeCommand(UUID runnerId, String workspaceId, String userName, UUID commandId);

    Mono<BridgeCommand> awaitBridgeCommand(UUID runnerId, String workspaceId, String userName, UUID commandId,
            int timeoutSeconds);

    void patchChecklist(UUID runnerId, String workspaceId, String userName, JsonNode updates);

    void reapDeadRunners();
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LocalRunnerServiceImpl implements LocalRunnerService {

    private static final String PAIRING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int PAIRING_CODE_LENGTH = 6;
    private static final java.util.regex.Pattern CAPABILITY_PATTERN = java.util.regex.Pattern.compile("[a-z_]+");

    private static final String WORKSPACES_WITH_RUNNERS_KEY = "opik:runners:workspaces:with_runners";

    private static String pairKey(String code) {
        return "opik:runners:pair:" + code;
    }

    private static String runnerKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId;
    }

    private static String runnerAgentsKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId + ":agents";
    }

    private static String runnerHeartbeatKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId + ":heartbeat";
    }

    private static String workspaceRunnersKey(String workspaceId) {
        return "opik:runners:workspace:" + workspaceId + ":runners";
    }

    private static String projectUserRunnerKey(String workspaceId, UUID projectId, String userName) {
        return "opik:runners:workspace:" + workspaceId + ":project:" + projectId + ":user:" + userName + ":runner";
    }

    private static String projectRunnersKey(String workspaceId, UUID projectId) {
        return "opik:runners:workspace:" + workspaceId + ":project:" + projectId + ":runners";
    }

    private static String workspaceUserRunnersKey(String workspaceId, String userName) {
        return "opik:runners:workspace:" + workspaceId + ":user:" + userName + ":runners";
    }

    private static String jobKey(UUID jobId) {
        return "opik:runners:job:" + jobId;
    }

    private static String jobLogsKey(UUID jobId) {
        return "opik:runners:job:" + jobId + ":logs";
    }

    private static String pendingJobsKey(UUID runnerId) {
        return "opik:runners:jobs:" + runnerId + ":pending";
    }

    private static String activeJobsKey(UUID runnerId) {
        return "opik:runners:jobs:" + runnerId + ":active";
    }

    private static String runnerJobsKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId + ":jobs";
    }

    private static String runnerCancellationsKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId + ":cancellations";
    }

    private static String bridgeCommandKey(UUID commandId) {
        return "opik:runners:bridge:command:" + commandId;
    }

    private static String bridgeCommandDoneKey(UUID commandId) {
        return "opik:runners:bridge:command:" + commandId + ":done";
    }

    private static String bridgePendingKey(UUID runnerId) {
        return "opik:runners:bridge:" + runnerId + ":pending";
    }

    private static String bridgeActiveKey(UUID runnerId) {
        return "opik:runners:bridge:" + runnerId + ":active";
    }

    private static String bridgeRateKey(UUID runnerId, long minute) {
        return "opik:runners:bridge:" + runnerId + ":rate:" + minute;
    }

    private static String bridgeWriteRateKey(UUID runnerId, long minute) {
        return "opik:runners:bridge:" + runnerId + ":write_rate:" + minute;
    }

    private static final Set<LocalRunnerJobStatus> TERMINAL_JOB_STATUSES = Set.of(LocalRunnerJobStatus.COMPLETED,
            LocalRunnerJobStatus.FAILED);
    private static final Set<LocalRunnerJobStatus> REPORTABLE_JOB_STATUSES = Set.of(LocalRunnerJobStatus.RUNNING,
            LocalRunnerJobStatus.COMPLETED, LocalRunnerJobStatus.FAILED);

    private static final Set<BridgeCommandStatus> REPORTABLE_BRIDGE_STATUSES = Set.of(
            BridgeCommandStatus.COMPLETED, BridgeCommandStatus.FAILED);

    private static final String FIELD_ID = "id";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_WORKSPACE_ID = "workspace_id";
    private static final String FIELD_USER_NAME = "user_name";
    private static final String FIELD_CONNECTED_AT = "connected_at";
    private static final String FIELD_DISCONNECTED_AT = "disconnected_at";
    private static final String FIELD_LAST_HEARTBEAT = "last_heartbeat";
    private static final String FIELD_RUNNER_ID = "runner_id";
    private static final String FIELD_AGENT_NAME = "agent_name";
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
    private static final String FIELD_CAPABILITIES = "capabilities";
    private static final String FIELD_CHECKLIST = "checklist";

    private static final String BRIDGE_FIELD_COMMAND_ID = "command_id";
    private static final String BRIDGE_FIELD_RUNNER_ID = "runner_id";
    private static final String BRIDGE_FIELD_TYPE = "type";
    private static final String BRIDGE_FIELD_ARGS = "args";
    private static final String BRIDGE_FIELD_STATUS = "status";
    private static final String BRIDGE_FIELD_RESULT = "result";
    private static final String BRIDGE_FIELD_ERROR = "error";
    private static final String BRIDGE_FIELD_TIMEOUT_SECONDS = "timeout_seconds";
    private static final String BRIDGE_FIELD_SUBMITTED_AT = "submitted_at";
    private static final String BRIDGE_FIELD_PICKED_UP_AT = "picked_up_at";
    private static final String BRIDGE_FIELD_COMPLETED_AT = "completed_at";
    private static final String BRIDGE_FIELD_DURATION_MS = "duration_ms";
    private static final String BRIDGE_FIELD_WORKSPACE_ID = "workspace_id";

    private static final List<String> DEFAULT_CAPABILITIES = List.of("jobs");
    private static final String BRIDGE_FIELD_COMPLETED_FLAG = "completed_flag";
    private static final String BRIDGE_DONE_SENTINEL = "done";

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull RedissonReactiveClient reactiveRedisClient;
    private final @NonNull LocalRunnerConfig runnerConfig;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ProjectService projectService;

    @Override
    public LocalRunnerPairResponse generatePairingCode(@NonNull String workspaceId, @NonNull String userName,
            @NonNull UUID projectId) {
        String projectName = projectService.get(projectId, workspaceId).name();

        String code = generatePairingCodeString();
        UUID runnerId = idGenerator.generateId();

        String pairPayload = JsonUtils.writeValueAsString(
                new PairingCodePayload(runnerId, workspaceId, projectId, projectName));

        RBucket<String> pairBucket = redisClient.getBucket(pairKey(code));
        boolean claimed = pairBucket.setIfAbsent(pairPayload,
                runnerConfig.getPairingCodeTtl().toJavaDuration());
        if (!claimed) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of("Pairing code collision. Please try again.")))
                    .build());
        }

        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
        runnerMap.putAll(Map.of(
                FIELD_NAME, "",
                FIELD_STATUS, LocalRunnerStatus.PAIRING.getValue(),
                FIELD_WORKSPACE_ID, workspaceId,
                FIELD_USER_NAME, userName,
                FIELD_PROJECT_ID, projectId.toString()));
        runnerMap.expire(runnerConfig.getDeadRunnerPurgeTime().toJavaDuration().multipliedBy(2));

        registerRunnerInSets(workspaceId, userName, projectId, runnerId);

        return LocalRunnerPairResponse.builder()
                .pairingCode(code)
                .runnerId(runnerId)
                .expiresInSeconds((int) runnerConfig.getPairingCodeTtl().toSeconds())
                .build();
    }

    @Override
    public LocalRunnerConnectResponse connect(@NonNull String workspaceId, @NonNull String userName,
            @NonNull LocalRunnerConnectRequest request) {
        PairingCodePayload payload = claimPairingCode(request.pairingCode(), workspaceId);
        UUID runnerId = payload.runnerId();
        UUID projectId = payload.projectId();

        evictExistingRunner(workspaceId, projectId, userName, runnerId);
        setUserRunner(workspaceId, projectId, userName, runnerId);
        activateRunner(runnerId, workspaceId, projectId, userName, request.runnerName());

        return LocalRunnerConnectResponse.builder()
                .runnerId(runnerId)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .projectName(payload.projectName())
                .build();
    }

    @Override
    public void activateFromOpikConnect(@NonNull String workspaceId, @NonNull String userName,
            @NonNull UUID projectId, @NonNull UUID runnerId, @NonNull String runnerName) {
        // Mirrors the post-pairing-code steps of connect(): evict any existing runner for
        // this (workspace, project, user), register the new runner in the relevant sets,
        // point the user -> runner bucket at it, and flip the runner row to CONNECTED.
        evictExistingRunner(workspaceId, projectId, userName, runnerId);
        registerRunnerInSets(workspaceId, userName, projectId, runnerId);
        setUserRunner(workspaceId, projectId, userName, runnerId);
        activateRunner(runnerId, workspaceId, projectId, userName, runnerName);
    }

    @Override
    public LocalRunner.LocalRunnerPage listRunners(@NonNull String workspaceId, @NonNull String userName,
            @NonNull UUID projectId, LocalRunnerStatus status, int page, int size) {
        RSet<String> projectRunnerIds = redisClient.getSet(
                projectRunnersKey(workspaceId, projectId));
        var projectIds = projectRunnerIds.readAll();

        RScoredSortedSet<String> userRunnerIds = redisClient.getScoredSortedSet(
                workspaceUserRunnersKey(workspaceId, userName));
        var allUserIds = userRunnerIds.valueRangeReversed(0, -1);

        List<String> matchedIds = new ArrayList<>();
        for (String runnerIdStr : allUserIds) {
            if (!projectIds.contains(runnerIdStr)) {
                continue;
            }
            if (status != null) {
                UUID runnerId = UUID.fromString(runnerIdStr);
                LocalRunnerStatus resolvedStatus = resolveStatus(runnerId);
                if (resolvedStatus != status) {
                    continue;
                }
            }
            matchedIds.add(runnerIdStr);
        }

        int total = matchedIds.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);

        List<LocalRunner> runners = new ArrayList<>();
        for (String runnerIdStr : matchedIds.subList(fromIndex, toIndex)) {
            UUID runnerId = UUID.fromString(runnerIdStr);
            LocalRunner runner = loadRunner(runnerId, workspaceId, status);
            if (runner != null) {
                runners.add(runner);
            }
        }

        return LocalRunner.LocalRunnerPage.builder()
                .page(page).size(size).total(total).content(runners).build();
    }

    @Override
    public LocalRunner getRunner(@NonNull String workspaceId, @NonNull String userName, @NonNull UUID runnerId) {
        validateRunnerOwnership(runnerId, workspaceId, userName);
        LocalRunner runner = loadRunner(runnerId, workspaceId);
        if (runner == null) {
            throw new NotFoundException("Runner not found: " + runnerId);
        }
        return runner;
    }

    @Override
    public void registerAgents(@NonNull UUID runnerId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull Map<String, LocalRunner.Agent> agents) {
        validateRunnerOwnership(runnerId, workspaceId, userName);

        if (agents.size() > runnerConfig.getMaxAgentsPerRunner()) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Too many agents. Maximum is " + runnerConfig.getMaxAgentsPerRunner())))
                    .build());
        }

        String key = runnerAgentsKey(runnerId);
        RMap<String, String> agentsMap = redisClient.getMap(key);
        agentsMap.delete();

        if (!agents.isEmpty()) {
            Map<String, String> serialized = new HashMap<>();
            agents.forEach((name, agent) -> serialized.put(name, JsonUtils.writeValueAsString(agent)));
            agentsMap.putAll(serialized);
        }
    }

    @Override
    public LocalRunnerHeartbeatResponse heartbeat(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull String userName, List<String> capabilities) {
        if (!isRunnerOwnedByUser(runnerId, workspaceId, userName)) {
            throw new ClientErrorException(Response.status(Response.Status.GONE)
                    .entity(new ErrorMessage(List.of("Runner not found: " + runnerId)))
                    .build());
        }

        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
        String projectIdStr = runnerMap.get(FIELD_PROJECT_ID);
        if (projectIdStr == null) {
            throw new IllegalStateException("Runner " + runnerId + " has no project_id");
        }

        UUID projectId = UUID.fromString(projectIdStr);
        RBucket<String> currentRunnerBucket = redisClient.getBucket(
                projectUserRunnerKey(workspaceId, projectId, userName));
        String currentRunnerId = currentRunnerBucket.get();
        if (currentRunnerId != null && !currentRunnerId.equals(runnerId.toString())) {
            throw new ClientErrorException(Response.status(Response.Status.GONE)
                    .entity(new ErrorMessage(List.of("Runner evicted by a newer connection")))
                    .build());
        }

        setHeartbeat(runnerId);

        if (capabilities != null && !capabilities.isEmpty()) {
            for (String cap : capabilities) {
                if (cap == null || !CAPABILITY_PATTERN.matcher(cap).matches()) {
                    throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                            .entity(new ErrorMessage(List.of("Invalid capability value: " + cap)))
                            .build());
                }
            }
            runnerMap.put(FIELD_CAPABILITIES, String.join(",", capabilities));
        }

        RList<String> activeJobs = redisClient.getList(
                activeJobsKey(runnerId));
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

        RSet<String> cancellations = redisClient.getSet(
                runnerCancellationsKey(runnerId));
        Set<String> cancelledIds = cancellations.readAll();
        cancellations.delete();

        return LocalRunnerHeartbeatResponse.builder()
                .cancelledJobIds(cancelledIds.stream().map(UUID::fromString).toList())
                .build();
    }

    @Override
    public UUID createJob(@NonNull String workspaceId, @NonNull String userName,
            @NonNull CreateLocalRunnerJobRequest request) {
        String runnerIdStr = redisClient.<String>getBucket(
                projectUserRunnerKey(workspaceId, request.projectId(), userName)).get();

        if (runnerIdStr == null) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorMessage(List.of(
                            "No runner configured. Pair one from the Runners page.")))
                    .build());
        }
        UUID runnerId = UUID.fromString(runnerIdStr);

        validateRunnerOwnership(runnerId, workspaceId, userName);

        if (!isRunnerAlive(runnerId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of(
                            "Your runner is not connected. Start it with: opik connect")))
                    .build());
        }

        RMap<String, String> agentsMap = redisClient.getMap(
                runnerAgentsKey(runnerId));
        if (!agentsMap.containsKey(request.agentName())) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Agent '%s' is not registered on this runner".formatted(request.agentName()))))
                    .build());
        }

        RList<String> pendingJobs = redisClient.getList(
                pendingJobsKey(runnerId));
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
        RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(
                runnerJobsKey(runnerId));
        runnerJobs.add(Instant.now().toEpochMilli(), jobId.toString());

        return jobId;
    }

    @Override
    public Mono<LocalRunnerJob> nextJob(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull String userName) {
        validateRunnerOwnership(runnerId, workspaceId, userName);

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
        validateRunnerOwnership(runnerId, workspaceId, userName);
        RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(
                runnerJobsKey(runnerId));

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
        if (current != null && (TERMINAL_JOB_STATUSES.contains(current) || current == LocalRunnerJobStatus.CANCELLED)) {
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
                RList<String> activeList = redisClient.getList(
                        activeJobsKey(runnerId));
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
        if (current != null && (TERMINAL_JOB_STATUSES.contains(current) || current == LocalRunnerJobStatus.CANCELLED)) {
            return;
        }

        jobMap.put(FIELD_STATUS, LocalRunnerJobStatus.CANCELLED.getValue());
        jobMap.put(FIELD_COMPLETED_AT, Instant.now().toString());

        String runnerIdStr = fields.get(FIELD_RUNNER_ID);
        if (runnerIdStr != null) {
            UUID runnerId = UUID.fromString(runnerIdStr);
            RList<String> pendingJobs = redisClient.getList(
                    pendingJobsKey(runnerId));
            boolean wasInPending = pendingJobs.remove(jobId.toString());

            if (wasInPending) {
                expireJobAndLogs(jobId, jobMap);
            } else {
                RSet<String> cancellations = redisClient.getSet(
                        runnerCancellationsKey(runnerId));
                cancellations.add(jobId.toString());
            }
        }
    }

    @Override
    public void reapDeadRunners() {
        RSet<String> workspacesWithRunners = redisClient.getSet(
                WORKSPACES_WITH_RUNNERS_KEY);
        Set<String> workspaceIds = workspacesWithRunners.readAll();

        int remaining = runnerConfig.getReaperMaxRunnersPerCycle();
        for (String workspaceId : workspaceIds) {
            if (remaining <= 0) {
                log.info("Reached reaper limit of '{}' runners per cycle, deferring remaining workspaces",
                        runnerConfig.getReaperMaxRunnersPerCycle());
                break;
            }
            try {
                remaining = reapWorkspaceRunners(workspaceId, remaining);
            } catch (Exception e) {
                log.error("Failed to reap runners for workspace '{}'", workspaceId, e);
            }
        }
    }

    private int reapWorkspaceRunners(String workspaceId, int remaining) {
        RScoredSortedSet<String> runnerIds = redisClient.getScoredSortedSet(
                workspaceRunnersKey(workspaceId));
        var ids = runnerIds.readAll();

        for (String runnerIdStr : ids) {
            if (remaining <= 0) {
                log.info("Reached reaper limit, deferring remaining runners in workspace '{}'", workspaceId);
                break;
            }
            UUID runnerId = UUID.fromString(runnerIdStr);
            try {
                reapRunner(runnerId, workspaceId);
            } catch (Exception e) {
                log.error("Failed to reap runner '{}' in workspace '{}'", runnerId, workspaceId, e);
            }
            try {
                reapStuckJobs(runnerId);
            } catch (Exception e) {
                log.error("Failed to reap stuck jobs for runner '{}' in workspace '{}'", runnerId, workspaceId, e);
            }
            try {
                reapStaleBridgeCommands(runnerId);
            } catch (Exception e) {
                log.error("Failed to reap stale bridge commands for runner '{}' in workspace '{}'", runnerId,
                        workspaceId, e);
            }
            remaining--;
        }

        if (runnerIds.isEmpty()) {
            workspacesWithRunners(workspaceId, false);
        }
        return remaining;
    }

    private void reapRunner(UUID runnerId, String workspaceId) {
        if (isRunnerAlive(runnerId)) {
            return;
        }

        RMap<String, String> runnerMap = redisClient.getMap(
                runnerKey(runnerId));

        if (!runnerMap.isExists()) {
            log.error("Runner map not found for dead runner '{}' in workspace '{}', skipping", runnerId, workspaceId);
            return;
        }

        String disconnectedAt = runnerMap.get(FIELD_DISCONNECTED_AT);
        if (disconnectedAt == null) {
            disconnectedAt = Instant.now().toString();
            runnerMap.put(FIELD_DISCONNECTED_AT, disconnectedAt);
        }

        Instant disconnected = Instant.parse(disconnectedAt);
        boolean shouldPurge = Duration.between(disconnected, Instant.now()).toSeconds() >= runnerConfig
                .getDeadRunnerPurgeTime().toSeconds();

        if (shouldPurge) {
            failOrphanedJobs(runnerId);
            failOrphanedBridgeCommands(runnerId);
            purgeRunner(runnerId, workspaceId, runnerMap.get(FIELD_USER_NAME));
        }
    }

    private void failOrphanedJobs(UUID runnerId) {
        RList<String> activeJobs = redisClient.getList(
                activeJobsKey(runnerId));
        List<String> activeJobIds = activeJobs.readAll();
        for (String jobIdStr : activeJobIds) {
            failJob(UUID.fromString(jobIdStr), "Runner disconnected");
        }
        activeJobs.delete();

        RList<String> pendingJobs = redisClient.getList(
                pendingJobsKey(runnerId));
        List<String> pendingJobIds = pendingJobs.readAll();
        for (String jobIdStr : pendingJobIds) {
            failJob(UUID.fromString(jobIdStr), "Runner disconnected");
        }
        pendingJobs.delete();

        redisClient.getScoredSortedSet(runnerJobsKey(runnerId)).delete();
    }

    private void reapStuckJobs(UUID runnerId) {
        RList<String> activeJobs = redisClient.getList(
                activeJobsKey(runnerId));
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

    private void purgeRunner(UUID runnerId, String workspaceId, String userName) {
        log.info("Purging dead runner '{}' from workspace '{}'", runnerId, workspaceId);

        String projectIdStr = redisClient.<String, String>getMap(runnerKey(runnerId)).get(FIELD_PROJECT_ID);

        redisClient.deleteKeys(
                runnerKey(runnerId),
                runnerAgentsKey(runnerId),
                runnerHeartbeatKey(runnerId),
                runnerCancellationsKey(runnerId),
                pendingJobsKey(runnerId),
                activeJobsKey(runnerId),
                bridgePendingKey(runnerId),
                bridgeActiveKey(runnerId));

        removeRunnerFromWorkspace(workspaceId, userName, runnerId);

        if (projectIdStr != null) {
            UUID projectId = UUID.fromString(projectIdStr);
            RSet<String> projectRunners = redisClient.getSet(
                    projectRunnersKey(workspaceId, projectId));
            projectRunners.remove(runnerId.toString());
            if (projectRunners.isEmpty()) {
                projectRunners.delete();
            }

            RBucket<String> userRunnerBucket = redisClient.getBucket(
                    projectUserRunnerKey(workspaceId, projectId, userName));
            String currentId = userRunnerBucket.get();
            if (runnerId.toString().equals(currentId)) {
                userRunnerBucket.delete();
            }
        }
    }

    private String generatePairingCodeString() {
        StringBuilder sb = new StringBuilder(PAIRING_CODE_LENGTH);
        for (int i = 0; i < PAIRING_CODE_LENGTH; i++) {
            sb.append(PAIRING_CODE_ALPHABET
                    .charAt(RandomUtils.secure().randomInt(0, PAIRING_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record PairingCodePayload(UUID runnerId, String workspaceId, UUID projectId, String projectName) {
    }

    private PairingCodePayload claimPairingCode(String code, String workspaceId) {
        RBucket<String> pairBucket = redisClient.getBucket(pairKey(code));
        String value = pairBucket.getAndDelete();
        if (value == null) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of("Invalid or expired pairing code")))
                    .build());
        }

        PairingCodePayload payload = JsonUtils.readValue(value, PairingCodePayload.class);

        if (!workspaceId.equals(payload.workspaceId())) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of("Pairing code belongs to a different workspace")))
                    .build());
        }

        return payload;
    }

    private void evictExistingRunner(String workspaceId, UUID projectId, String userName, UUID newRunnerId) {
        RBucket<String> userRunnerBucket = redisClient.getBucket(
                projectUserRunnerKey(workspaceId, projectId, userName));
        String oldRunnerIdStr = userRunnerBucket.get();

        if (oldRunnerIdStr != null && !oldRunnerIdStr.equals(newRunnerId.toString())) {
            UUID oldRunnerId = UUID.fromString(oldRunnerIdStr);
            redisClient.getBucket(runnerHeartbeatKey(oldRunnerId)).delete();

            RMap<String, String> oldRunnerMap = redisClient.getMap(runnerKey(oldRunnerId));
            if (oldRunnerMap.isExists()) {
                oldRunnerMap.put(FIELD_DISCONNECTED_AT, Instant.now().toString());
            }

            failOrphanedJobs(oldRunnerId);
            failOrphanedBridgeCommands(oldRunnerId);

            RSet<String> projectRunners = redisClient.getSet(
                    projectRunnersKey(workspaceId, projectId));
            projectRunners.remove(oldRunnerIdStr);

            removeRunnerFromWorkspace(workspaceId, userName, oldRunnerId);

            log.info("Evicted runner '{}' in workspace '{}'", oldRunnerId, workspaceId);
        }
    }

    private void setUserRunner(String workspaceId, UUID projectId, String userName, UUID runnerId) {
        redisClient.getBucket(projectUserRunnerKey(workspaceId, projectId, userName))
                .set(runnerId.toString());
    }

    private void activateRunner(UUID runnerId, String workspaceId, UUID projectId, String userName,
            String runnerName) {
        RMap<String, String> runnerMap = redisClient.getMap(
                runnerKey(runnerId));
        runnerMap.putAll(Map.of(
                FIELD_NAME, runnerName,
                FIELD_STATUS, LocalRunnerStatus.CONNECTED.getValue(),
                FIELD_WORKSPACE_ID, workspaceId,
                FIELD_USER_NAME, userName,
                FIELD_PROJECT_ID, projectId.toString(),
                FIELD_CONNECTED_AT, Instant.now().toString()));
        runnerMap.clearExpire();
        setHeartbeat(runnerId);
    }

    private void setHeartbeat(UUID runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(
                runnerHeartbeatKey(runnerId));
        heartbeat.set("alive", runnerConfig.getHeartbeatTtl().toJavaDuration());
    }

    private boolean isRunnerAlive(UUID runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(
                runnerHeartbeatKey(runnerId));
        return heartbeat.isExists();
    }

    private void registerRunnerInSets(String workspaceId, String userName, UUID projectId, UUID runnerId) {
        double score = Instant.now().toEpochMilli();

        RScoredSortedSet<String> workspaceRunners = redisClient.getScoredSortedSet(
                workspaceRunnersKey(workspaceId));
        workspaceRunners.add(score, runnerId.toString());

        RScoredSortedSet<String> userRunners = redisClient.getScoredSortedSet(
                workspaceUserRunnersKey(workspaceId, userName));
        userRunners.add(score, runnerId.toString());

        RSet<String> projectRunners = redisClient.getSet(
                projectRunnersKey(workspaceId, projectId));
        projectRunners.add(runnerId.toString());

        workspacesWithRunners(workspaceId, true);
    }

    private void removeRunnerFromWorkspace(String workspaceId, String userName, UUID runnerId) {
        removeRunnerFromWorkspaceOnly(workspaceId, runnerId);

        RScoredSortedSet<String> userRunners = redisClient.getScoredSortedSet(
                workspaceUserRunnersKey(workspaceId, userName));
        userRunners.remove(runnerId.toString());
    }

    private void removeRunnerFromWorkspaceOnly(String workspaceId, UUID runnerId) {
        RScoredSortedSet<String> workspaceRunners = redisClient.getScoredSortedSet(
                workspaceRunnersKey(workspaceId));
        workspaceRunners.remove(runnerId.toString());
    }

    private void workspacesWithRunners(String workspaceId, boolean add) {
        RSet<String> workspaces = redisClient.getSet(WORKSPACES_WITH_RUNNERS_KEY);
        if (add) {
            workspaces.add(workspaceId);
        } else {
            workspaces.remove(workspaceId);
        }
    }

    private LocalRunnerStatus resolveStatus(UUID runnerId) {
        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
        String storedStatus = runnerMap.get(FIELD_STATUS);
        return determineStatus(storedStatus, runnerId);
    }

    private LocalRunnerStatus determineStatus(String storedStatus, UUID runnerId) {
        if (LocalRunnerStatus.PAIRING.getValue().equals(storedStatus)) {
            return LocalRunnerStatus.PAIRING;
        }
        return isRunnerAlive(runnerId) ? LocalRunnerStatus.CONNECTED : LocalRunnerStatus.DISCONNECTED;
    }

    private LocalRunner loadRunner(UUID runnerId, String workspaceId) {
        return loadRunner(runnerId, workspaceId, null);
    }

    private LocalRunner loadRunner(UUID runnerId, String workspaceId, LocalRunnerStatus preResolvedStatus) {
        RMap<String, String> runnerMap = redisClient.getMap(
                runnerKey(runnerId));
        Map<String, String> fields = runnerMap.readAllMap();

        if (fields.isEmpty()) {
            return null;
        }

        String storedWorkspaceId = fields.get(FIELD_WORKSPACE_ID);
        if (!workspaceId.equals(storedWorkspaceId)) {
            return null;
        }

        LocalRunnerStatus status;
        if (preResolvedStatus != null) {
            status = preResolvedStatus;
        } else {
            status = determineStatus(fields.get(FIELD_STATUS), runnerId);
        }

        List<LocalRunner.Agent> agents = List.of();
        if (status == LocalRunnerStatus.CONNECTED) {
            agents = loadAgents(runnerId);
        }

        Instant connectedAt = fields.get(FIELD_CONNECTED_AT) != null
                ? Instant.parse(fields.get(FIELD_CONNECTED_AT))
                : null;

        List<String> capabilities = parseCapabilities(fields.get(FIELD_CAPABILITIES));
        JsonNode checklist = parseJsonNode(fields.get(FIELD_CHECKLIST));

        return LocalRunner.builder()
                .id(runnerId)
                .name(fields.get(FIELD_NAME))
                .projectId(parseUUID(fields.get(FIELD_PROJECT_ID)))
                .status(status)
                .connectedAt(connectedAt)
                .agents(agents)
                .capabilities(capabilities)
                .checklist(checklist)
                .build();
    }

    private List<LocalRunner.Agent> loadAgents(UUID runnerId) {
        RMap<String, String> agentsMap = redisClient.getMap(
                runnerAgentsKey(runnerId));
        Map<String, String> allAgents = agentsMap.readAllMap();

        List<LocalRunner.Agent> agents = new ArrayList<>();
        for (var entry : allAgents.entrySet()) {
            try {
                LocalRunner.Agent agent = JsonUtils.readValue(entry.getValue(), LocalRunner.Agent.class);
                agents.add(agent.toBuilder().name(entry.getKey()).build());
            } catch (UncheckedIOException e) {
                log.warn("Failed to parse agent metadata for agent '{}' on runner '{}'", entry.getKey(), runnerId, e);
            }
        }
        return agents;
    }

    private LocalRunnerJob buildRunnerJob(Map<String, String> fields) {
        JsonNode inputs = parseJsonNode(fields.get(FIELD_INPUTS));
        JsonNode result = parseJsonNode(fields.get(FIELD_RESULT));

        return LocalRunnerJob.builder()
                .id(UUID.fromString(fields.get(FIELD_ID)))
                .runnerId(UUID.fromString(fields.get(FIELD_RUNNER_ID)))
                .agentName(fields.get(FIELD_AGENT_NAME))
                .status(parseJobStatus(fields.get(FIELD_STATUS)))
                .inputs(inputs)
                .result(result)
                .error(fields.get(FIELD_ERROR))
                .projectId(parseUUID(fields.get(FIELD_PROJECT_ID)))
                .traceId(parseUUID(fields.get(FIELD_TRACE_ID)))
                .maskId(parseUUID(fields.get(FIELD_MASK_ID)))
                .blueprintName(fields.get(FIELD_BLUEPRINT_NAME))
                .metadata(parseMetadata(fields.get(FIELD_METADATA)))
                .timeout(parseIntValue(fields.get(FIELD_TIMEOUT)))
                .createdAt(parseInstant(fields.get(FIELD_CREATED_AT)))
                .startedAt(parseInstant(fields.get(FIELD_STARTED_AT)))
                .completedAt(parseInstant(fields.get(FIELD_COMPLETED_AT)))
                .build();
    }

    private JsonNode parseJsonNode(String value) {
        if (value == null) {
            return null;
        }
        return JsonUtils.getJsonNodeFromString(value);
    }

    private Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        return Instant.parse(value);
    }

    private int parseIntValue(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private UUID parseUUID(String value) {
        if (value == null) {
            return null;
        }
        return UUID.fromString(value);
    }

    private LocalRunnerJobMetadata parseMetadata(String value) {
        if (value == null) {
            return null;
        }
        return JsonUtils.readValue(value, LocalRunnerJobMetadata.class);
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

    private int resolveAgentTimeout(UUID runnerId, String agentName) {
        try {
            RMap<String, String> agentsMap = redisClient.getMap(
                    runnerAgentsKey(runnerId));
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

    private void validateRunnerOwnership(UUID runnerId, String workspaceId, String userName) {
        if (!isRunnerOwnedByUser(runnerId, workspaceId, userName)) {
            throw new NotFoundException("Runner not found: " + runnerId);
        }
    }

    private boolean isRunnerOwnedByUser(UUID runnerId, String workspaceId, String userName) {
        RScoredSortedSet<String> userRunners = redisClient.getScoredSortedSet(
                workspaceUserRunnersKey(workspaceId, userName));
        return userRunners.contains(runnerId.toString());
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
                workspaceUserRunnersKey(workspaceId, userName));
        if (!userRunners.contains(runnerIdStr)) {
            throw new NotFoundException("Job not found: '%s'".formatted(jobId));
        }
        return new ValidatedJob(jobMap, fields);
    }

    private Map<String, String> loadValidatedBridgeCommand(UUID runnerId, String workspaceId, UUID commandId) {
        RMap<String, String> commandMap = redisClient.getMap(bridgeCommandKey(commandId));
        Map<String, String> fields = commandMap.readAllMap();
        if (fields.isEmpty()) {
            throw new NotFoundException("Command not found: " + commandId);
        }
        if (!workspaceId.equals(fields.get(BRIDGE_FIELD_WORKSPACE_ID))) {
            throw new NotFoundException("Command not found: " + commandId);
        }
        if (!runnerId.toString().equals(fields.get(BRIDGE_FIELD_RUNNER_ID))) {
            throw new NotFoundException("Command not found: " + commandId);
        }
        return fields;
    }

    private List<String> parseCapabilities(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CAPABILITIES;
        }
        return List.of(value.split(","));
    }

    private boolean hasCapability(UUID runnerId, String capability) {
        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
        String caps = runnerMap.get(FIELD_CAPABILITIES);
        List<String> capabilities = parseCapabilities(caps);
        return capabilities.contains(capability);
    }

    @Override
    public UUID createBridgeCommand(@NonNull UUID runnerId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull BridgeCommandSubmitRequest request) {
        validateRunnerOwnership(runnerId, workspaceId, userName);

        if (!isRunnerAlive(runnerId)) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorMessage(List.of("Runner is not connected")))
                    .build());
        }

        if (!hasCapability(runnerId, "bridge")) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of("Runner does not support bridge commands")))
                    .build());
        }

        RList<String> pendingList = redisClient.getList(bridgePendingKey(runnerId));
        if (pendingList.size() >= runnerConfig.getBridgeMaxPendingPerRunner()) {
            throw new ClientErrorException(Response.status(429)
                    .entity(new ErrorMessage(List.of("Too many pending commands")))
                    .build());
        }

        checkBridgeRateLimit(runnerId, request.type());
        String argsJson = validateAndSerializePayload(request.args(), "args");

        int timeoutSeconds = resolveCommandTimeout(request.timeoutSeconds());
        UUID commandId = idGenerator.generateId();
        String now = Instant.now().toString();

        Map<String, String> commandFields = new HashMap<>();
        commandFields.put(BRIDGE_FIELD_COMMAND_ID, commandId.toString());
        commandFields.put(BRIDGE_FIELD_RUNNER_ID, runnerId.toString());
        commandFields.put(BRIDGE_FIELD_TYPE, request.type().getValue());
        commandFields.put(BRIDGE_FIELD_ARGS, argsJson);
        commandFields.put(BRIDGE_FIELD_STATUS, BridgeCommandStatus.PENDING.getValue());
        commandFields.put(BRIDGE_FIELD_TIMEOUT_SECONDS, String.valueOf(timeoutSeconds));
        commandFields.put(BRIDGE_FIELD_SUBMITTED_AT, now);
        commandFields.put(BRIDGE_FIELD_WORKSPACE_ID, workspaceId);

        RBatch batch = redisClient.createBatch();
        batch.<String, String>getMap(bridgeCommandKey(commandId), StringCodec.INSTANCE)
                .putAllAsync(commandFields);
        batch.<String, String>getMap(bridgeCommandKey(commandId), StringCodec.INSTANCE)
                .expireAsync(Duration.ofSeconds(timeoutSeconds * 2 + 60));
        batch.<String>getList(bridgePendingKey(runnerId), StringCodec.INSTANCE)
                .addAsync(commandId.toString());
        batch.execute();

        return commandId;
    }

    @Override
    public Mono<BridgeCommandBatchResponse> nextBridgeCommands(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull String userName, int maxCommands) {
        validateRunnerOwnership(runnerId, workspaceId, userName);

        String pendingKey = bridgePendingKey(runnerId);
        String activeKey = bridgeActiveKey(runnerId);

        RBlockingDeque<String> blockingDeque = redisClient.getBlockingDeque(pendingKey);
        Duration timeout = Duration.ofSeconds(runnerConfig.getBridgePollTimeout().toSeconds());

        return Mono.defer(() -> Mono.fromCompletionStage(
                blockingDeque.moveAsync(timeout, DequeMoveArgs.pollFirst().addLastTo(activeKey))))
                .filter(commandIdStr -> commandIdStr != null)
                .flatMap(firstCommandId -> Mono.fromCallable(() -> {
                    List<String> commandIds = new ArrayList<>();
                    commandIds.add(firstCommandId);

                    for (int i = 1; i < maxCommands; i++) {
                        String nextId = blockingDeque.move(DequeMoveArgs.pollFirst().addLastTo(activeKey));
                        if (nextId == null) {
                            break;
                        }
                        commandIds.add(nextId);
                    }

                    String now = Instant.now().toString();

                    RBatch readBatch = redisClient.createBatch();
                    for (String cmdIdStr : commandIds) {
                        UUID commandId = UUID.fromString(cmdIdStr);
                        readBatch.<String, String>getMap(
                                bridgeCommandKey(commandId), StringCodec.INSTANCE).readAllMapAsync();
                    }
                    BatchResult<?> batchResult = readBatch.execute();
                    List<?> responses = batchResult.getResponses();

                    List<String> liveCommandIds = new ArrayList<>();
                    List<Map<String, String>> liveFields = new ArrayList<>();
                    RList<String> activeList = redisClient.getList(activeKey);
                    for (int i = 0; i < commandIds.size(); i++) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> fields = (Map<String, String>) responses.get(i);
                        if (fields.isEmpty() || fields.containsKey(BRIDGE_FIELD_COMPLETED_FLAG)) {
                            activeList.remove(commandIds.get(i));
                            continue;
                        }
                        BridgeCommandStatus status = parseBridgeCommandStatus(
                                fields.get(BRIDGE_FIELD_STATUS));
                        if (status != null && status.isTerminal()) {
                            activeList.remove(commandIds.get(i));
                            continue;
                        }
                        liveCommandIds.add(commandIds.get(i));
                        liveFields.add(fields);
                    }

                    if (!liveCommandIds.isEmpty()) {
                        RBatch statusBatch = redisClient.createBatch();
                        for (String cmdIdStr : liveCommandIds) {
                            UUID commandId = UUID.fromString(cmdIdStr);
                            var batchMap = statusBatch.<String, String>getMap(
                                    bridgeCommandKey(commandId), StringCodec.INSTANCE);
                            batchMap.putAsync(BRIDGE_FIELD_STATUS,
                                    BridgeCommandStatus.PICKED_UP.getValue());
                            batchMap.putAsync(BRIDGE_FIELD_PICKED_UP_AT, now);
                        }
                        statusBatch.execute();
                    }

                    List<BridgeCommandBatchResponse.BridgeCommandItem> items = new ArrayList<>();
                    for (Map<String, String> fields : liveFields) {
                        items.add(buildBridgeCommandItem(fields));
                    }

                    return BridgeCommandBatchResponse.builder().commands(items).build();
                }).subscribeOn(Schedulers.boundedElastic()))
                .defaultIfEmpty(BridgeCommandBatchResponse.builder().commands(List.of()).build());
    }

    @Override
    public void reportBridgeCommandResult(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull String userName, @NonNull UUID commandId, @NonNull BridgeCommandResultRequest request) {
        if (!REPORTABLE_BRIDGE_STATUSES.contains(request.status())) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Invalid result status. Must be one of: " + REPORTABLE_BRIDGE_STATUSES)))
                    .build());
        }
        if (request.result() == null && request.error() == null) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of("Either result or error must be provided")))
                    .build());
        }
        String resultJson = validateAndSerializePayload(request.result(), "result");
        String errorJson = validateAndSerializePayload(request.error(), "error");

        validateRunnerOwnership(runnerId, workspaceId, userName);

        Map<String, String> fields = loadValidatedBridgeCommand(runnerId, workspaceId, commandId);

        BridgeCommandStatus currentStatus = parseBridgeCommandStatus(fields.get(BRIDGE_FIELD_STATUS));
        if (currentStatus != null && currentStatus.isTerminal()) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of("Command already completed")))
                    .build());
        }

        RMap<String, String> commandMap = redisClient.getMap(bridgeCommandKey(commandId));
        String prev = commandMap.putIfAbsent(BRIDGE_FIELD_COMPLETED_FLAG, "1");
        if (prev != null) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of("Command already completed")))
                    .build());
        }

        Map<String, String> updates = new HashMap<>();
        updates.put(BRIDGE_FIELD_STATUS, request.status().getValue());
        updates.put(BRIDGE_FIELD_COMPLETED_AT, Instant.now().toString());
        if (resultJson != null) {
            updates.put(BRIDGE_FIELD_RESULT, resultJson);
        }
        if (errorJson != null) {
            updates.put(BRIDGE_FIELD_ERROR, errorJson);
        }
        if (request.durationMs() != null) {
            updates.put(BRIDGE_FIELD_DURATION_MS, request.durationMs().toString());
        }

        RBatch resultBatch = redisClient.createBatch();
        resultBatch.<String, String>getMap(bridgeCommandKey(commandId), StringCodec.INSTANCE)
                .putAllAsync(updates);
        resultBatch.<String, String>getMap(bridgeCommandKey(commandId), StringCodec.INSTANCE)
                .expireAsync(runnerConfig.getBridgeCompletedCommandTtl().toJavaDuration());
        resultBatch.getList(bridgeActiveKey(runnerId), StringCodec.INSTANCE)
                .removeAsync(commandId.toString());
        resultBatch.execute();

        writeBridgeDoneSentinel(commandId);
    }

    @Override
    public BridgeCommand getBridgeCommand(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull String userName, @NonNull UUID commandId) {
        validateRunnerOwnership(runnerId, workspaceId, userName);

        Map<String, String> fields = loadValidatedBridgeCommand(runnerId, workspaceId, commandId);

        return buildBridgeCommand(fields);
    }

    private static final int DEEP_MERGE_MAX_DEPTH = 10;

    @Override
    public void patchChecklist(@NonNull UUID runnerId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull JsonNode updates) {
        if (!updates.isObject()) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of("Checklist must be a JSON object")))
                    .build());
        }

        validateRunnerOwnership(runnerId, workspaceId, userName);

        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
        if (!runnerMap.isExists()) {
            throw new NotFoundException("Runner not found: " + runnerId);
        }

        String existingJson = runnerMap.get(FIELD_CHECKLIST);
        JsonNode merged;
        if (existingJson != null) {
            JsonNode existing = JsonUtils.getJsonNodeFromString(existingJson);
            merged = deepMerge(existing, updates, 0);
        } else {
            merged = updates;
        }

        String serialized = JsonUtils.writeValueAsString(merged);
        if (serialized.length() > runnerConfig.getBridgeMaxPayloadBytes()) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Checklist too large: " + serialized.length() + " bytes (max "
                                    + runnerConfig.getBridgeMaxPayloadBytes() + ")")))
                    .build());
        }

        runnerMap.put(FIELD_CHECKLIST, serialized);
    }

    private JsonNode deepMerge(JsonNode base, JsonNode override, int depth) {
        if (depth > DEEP_MERGE_MAX_DEPTH || !base.isObject() || !override.isObject()) {
            return override;
        }
        ObjectNode result = base.deepCopy();
        var fieldIterator = override.fields();
        while (fieldIterator.hasNext()) {
            var entry = fieldIterator.next();
            String fieldName = entry.getKey();
            JsonNode overrideValue = entry.getValue();
            if (result.has(fieldName) && result.get(fieldName).isObject() && overrideValue.isObject()) {
                result.set(fieldName, deepMerge(result.get(fieldName), overrideValue, depth + 1));
            } else {
                result.set(fieldName, overrideValue);
            }
        }
        return result;
    }

    @Override
    public Mono<BridgeCommand> awaitBridgeCommand(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull String userName, @NonNull UUID commandId, int timeoutSeconds) {
        int effectiveTimeout = Math.min(Math.max(timeoutSeconds, 1),
                (int) runnerConfig.getBridgeMaxCommandTimeout().toSeconds());
        validateRunnerOwnership(runnerId, workspaceId, userName);

        Map<String, String> fields = loadValidatedBridgeCommand(runnerId, workspaceId, commandId);

        BridgeCommandStatus status = parseBridgeCommandStatus(fields.get(BRIDGE_FIELD_STATUS));
        if (status != null && status.isTerminal()) {
            return Mono.just(buildBridgeCommand(fields));
        }

        RBlockingQueue<String> doneQueue = redisClient.getBlockingQueue(bridgeCommandDoneKey(commandId));

        return Mono.fromCompletionStage(
                doneQueue.pollAsync(effectiveTimeout, TimeUnit.SECONDS))
                .flatMap(signal -> Mono.fromCallable(() -> {
                    Map<String, String> updatedFields = redisClient
                            .<String, String>getMap(bridgeCommandKey(commandId)).readAllMap();
                    if (updatedFields.isEmpty()) {
                        return buildBridgeCommand(fields);
                    }
                    return buildBridgeCommand(updatedFields);
                }).subscribeOn(Schedulers.boundedElastic()))
                .defaultIfEmpty(buildBridgeCommand(fields));
    }

    void failOrphanedBridgeCommands(UUID runnerId) {
        RList<String> pendingList = redisClient.getList(bridgePendingKey(runnerId));
        List<String> pendingIds = pendingList.readAll();
        for (String cmdIdStr : pendingIds) {
            timeoutBridgeCommand(UUID.fromString(cmdIdStr));
        }
        pendingList.delete();

        RList<String> activeList = redisClient.getList(bridgeActiveKey(runnerId));
        List<String> activeIds = activeList.readAll();
        for (String cmdIdStr : activeIds) {
            timeoutBridgeCommand(UUID.fromString(cmdIdStr));
        }
        activeList.delete();
    }

    void reapStaleBridgeCommands(UUID runnerId) {
        RList<String> activeList = redisClient.getList(bridgeActiveKey(runnerId));
        List<String> activeIds = activeList.readAll();
        Instant now = Instant.now();
        List<String> toRemove = new ArrayList<>();

        for (String cmdIdStr : activeIds) {
            UUID commandId = UUID.fromString(cmdIdStr);
            RMap<String, String> cmdMap = redisClient.getMap(bridgeCommandKey(commandId));
            if (!cmdMap.isExists()) {
                toRemove.add(cmdIdStr);
                continue;
            }

            String pickedUpAtStr = cmdMap.get(BRIDGE_FIELD_PICKED_UP_AT);
            if (pickedUpAtStr == null) {
                continue;
            }

            String timeoutStr = cmdMap.get(BRIDGE_FIELD_TIMEOUT_SECONDS);
            int timeoutSecs = parseIntValue(timeoutStr);
            if (timeoutSecs <= 0) {
                timeoutSecs = (int) runnerConfig.getBridgeDefaultCommandTimeout().toSeconds();
            }

            Instant pickedUpAt = Instant.parse(pickedUpAtStr);
            if (Duration.between(pickedUpAt, now).getSeconds() > timeoutSecs + 10) {
                log.warn("Bridge command {} on runner {} exceeded timeout of {}s", commandId, runnerId, timeoutSecs);
                timeoutBridgeCommand(commandId);
                toRemove.add(cmdIdStr);
            }
        }

        for (String id : toRemove) {
            activeList.remove(id);
        }
    }

    private void timeoutBridgeCommand(UUID commandId) {
        RMap<String, String> cmdMap = redisClient.getMap(bridgeCommandKey(commandId));
        if (!cmdMap.isExists()) {
            return;
        }
        String status = cmdMap.get(BRIDGE_FIELD_STATUS);
        BridgeCommandStatus current = parseBridgeCommandStatus(status);
        if (current != null && current.isTerminal()) {
            return;
        }
        String prev = cmdMap.putIfAbsent(BRIDGE_FIELD_COMPLETED_FLAG, "1");
        if (prev != null) {
            return;
        }
        cmdMap.put(BRIDGE_FIELD_STATUS, BridgeCommandStatus.TIMED_OUT.getValue());
        cmdMap.put(BRIDGE_FIELD_COMPLETED_AT, Instant.now().toString());
        cmdMap.expire(runnerConfig.getBridgeCompletedCommandTtl().toJavaDuration());
        writeBridgeDoneSentinel(commandId);
    }

    private void writeBridgeDoneSentinel(UUID commandId) {
        RBlockingQueue<String> doneQueue = redisClient.getBlockingQueue(bridgeCommandDoneKey(commandId));
        doneQueue.offer(BRIDGE_DONE_SENTINEL);
        doneQueue.expire(Duration.ofSeconds(runnerConfig.getBridgeMaxCommandTimeout().toSeconds() + 30));
    }

    private String validateAndSerializePayload(JsonNode payload, String fieldName) {
        if (payload == null) {
            return null;
        }
        String serialized = JsonUtils.writeValueAsString(payload);
        if (serialized.length() > runnerConfig.getBridgeMaxPayloadBytes()) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            fieldName + " payload too large: " + serialized.length() + " bytes (max "
                                    + runnerConfig.getBridgeMaxPayloadBytes() + ")")))
                    .build());
        }
        return serialized;
    }

    private int resolveCommandTimeout(Integer requested) {
        int maxTimeout = (int) runnerConfig.getBridgeMaxCommandTimeout().toSeconds();
        int defaultTimeout = (int) runnerConfig.getBridgeDefaultCommandTimeout().toSeconds();
        if (requested == null || requested <= 0) {
            return defaultTimeout;
        }
        return Math.min(requested, maxTimeout);
    }

    private void checkBridgeRateLimit(UUID runnerId, BridgeCommandType type) {
        long minute = Instant.now().getEpochSecond() / 60;

        RAtomicLong rateCounter = redisClient.getAtomicLong(bridgeRateKey(runnerId, minute));
        long count = rateCounter.incrementAndGet();
        if (count == 1) {
            rateCounter.expire(Duration.ofSeconds(120));
        }
        if (count > runnerConfig.getBridgeMaxCommandsPerMinute()) {
            throw new ClientErrorException(Response.status(429)
                    .entity(new ErrorMessage(List.of("Rate limit exceeded")))
                    .build());
        }

        if (type.isWriteCommand()) {
            RAtomicLong writeRateCounter = redisClient.getAtomicLong(bridgeWriteRateKey(runnerId, minute));
            long writeCount = writeRateCounter.incrementAndGet();
            if (writeCount == 1) {
                writeRateCounter.expire(Duration.ofSeconds(120));
            }
            if (writeCount > runnerConfig.getBridgeMaxWriteCommandsPerMinute()) {
                throw new ClientErrorException(Response.status(429)
                        .entity(new ErrorMessage(List.of("Write rate limit exceeded")))
                        .build());
            }
        }
    }

    private BridgeCommandBatchResponse.BridgeCommandItem buildBridgeCommandItem(Map<String, String> fields) {
        return BridgeCommandBatchResponse.BridgeCommandItem.builder()
                .commandId(UUID.fromString(fields.get(BRIDGE_FIELD_COMMAND_ID)))
                .type(parseBridgeCommandType(fields.get(BRIDGE_FIELD_TYPE)))
                .args(parseJsonNode(fields.get(BRIDGE_FIELD_ARGS)))
                .timeoutSeconds(parseIntValue(fields.get(BRIDGE_FIELD_TIMEOUT_SECONDS)))
                .submittedAt(parseInstant(fields.get(BRIDGE_FIELD_SUBMITTED_AT)))
                .build();
    }

    private BridgeCommand buildBridgeCommand(Map<String, String> fields) {
        return BridgeCommand.builder()
                .commandId(UUID.fromString(fields.get(BRIDGE_FIELD_COMMAND_ID)))
                .runnerId(UUID.fromString(fields.get(BRIDGE_FIELD_RUNNER_ID)))
                .type(parseBridgeCommandType(fields.get(BRIDGE_FIELD_TYPE)))
                .status(parseBridgeCommandStatus(fields.get(BRIDGE_FIELD_STATUS)))
                .args(parseJsonNode(fields.get(BRIDGE_FIELD_ARGS)))
                .result(parseJsonNode(fields.get(BRIDGE_FIELD_RESULT)))
                .error(parseJsonNode(fields.get(BRIDGE_FIELD_ERROR)))
                .timeoutSeconds(parseIntValue(fields.get(BRIDGE_FIELD_TIMEOUT_SECONDS)))
                .submittedAt(parseInstant(fields.get(BRIDGE_FIELD_SUBMITTED_AT)))
                .pickedUpAt(parseInstant(fields.get(BRIDGE_FIELD_PICKED_UP_AT)))
                .completedAt(parseInstant(fields.get(BRIDGE_FIELD_COMPLETED_AT)))
                .durationMs(parseLong(fields.get(BRIDGE_FIELD_DURATION_MS)))
                .build();
    }

    private BridgeCommandType parseBridgeCommandType(String value) {
        if (value == null) {
            return null;
        }
        for (BridgeCommandType t : BridgeCommandType.values()) {
            if (t.getValue().equals(value)) {
                return t;
            }
        }
        return null;
    }

    private BridgeCommandStatus parseBridgeCommandStatus(String value) {
        if (value == null) {
            return null;
        }
        for (BridgeCommandStatus s : BridgeCommandStatus.values()) {
            if (s.getValue().equals(value)) {
                return s;
            }
        }
        return null;
    }

    private Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
