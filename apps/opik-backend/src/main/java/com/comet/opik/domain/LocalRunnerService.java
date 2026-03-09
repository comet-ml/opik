package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerConnectRequest;
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobMetadata;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.LocalRunnerPairResponse;
import com.comet.opik.api.runner.LocalRunnerStatus;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@ImplementedBy(LocalRunnerServiceImpl.class)
public interface LocalRunnerService {

    LocalRunnerPairResponse generatePairingCode(String workspaceId, String userName);

    UUID connect(String workspaceId, String userName, LocalRunnerConnectRequest request);

    LocalRunner.LocalRunnerPage listRunners(String workspaceId, int page, int size);

    LocalRunner getRunner(String workspaceId, UUID runnerId);

    void registerAgents(UUID runnerId, String workspaceId, Map<String, LocalRunner.Agent> agents);

    LocalRunnerHeartbeatResponse heartbeat(UUID runnerId, String workspaceId);

    UUID createJob(String workspaceId, String userName, CreateLocalRunnerJobRequest request);

    CompletionStage<LocalRunnerJob> nextJob(UUID runnerId, String workspaceId);

    LocalRunnerJob.LocalRunnerJobPage listJobs(UUID runnerId, String project, String workspaceId,
            int page, int size);

    LocalRunnerJob getJob(UUID jobId, String workspaceId);

    List<LocalRunnerLogEntry> getJobLogs(UUID jobId, int offset, String workspaceId);

    void appendLogs(UUID jobId, String workspaceId, List<LocalRunnerLogEntry> entries);

    void reportResult(UUID jobId, String workspaceId, LocalRunnerJobResultRequest result);

    void cancelJob(UUID jobId, String workspaceId);

    void reapDeadRunners();
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class LocalRunnerServiceImpl implements LocalRunnerService {

    private static final String PAIRING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int PAIRING_CODE_LENGTH = 6;

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

    private static String userRunnerKey(String workspaceId, String userName) {
        return "opik:runners:workspace:" + workspaceId + ":user:" + userName + ":runner";
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

    private static final Set<LocalRunnerJobStatus> TERMINAL_JOB_STATUSES = Set.of(LocalRunnerJobStatus.COMPLETED,
            LocalRunnerJobStatus.FAILED);

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
    private static final String FIELD_PROJECT = "project";
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
    private static final String FIELD_METADATA = "metadata";

    private final @NonNull RedissonClient redisClient;
    private final @NonNull LocalRunnerConfig runnerConfig;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public LocalRunnerPairResponse generatePairingCode(@NonNull String workspaceId, @NonNull String userName) {
        String code = generatePairingCodeString();
        UUID runnerId = idGenerator.generateId();

        RBucket<String> pairBucket = redisClient.getBucket(pairKey(code), StringCodec.INSTANCE);
        boolean claimed = pairBucket.setIfAbsent(runnerId + ":" + workspaceId,
                runnerConfig.getPairingCodeTtl().toJavaDuration());
        if (!claimed) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of("Pairing code collision. Please try again.")))
                    .build());
        }

        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId), StringCodec.INSTANCE);
        runnerMap.putAll(Map.of(
                FIELD_NAME, "",
                FIELD_STATUS, LocalRunnerStatus.PAIRING.getValue(),
                FIELD_WORKSPACE_ID, workspaceId,
                FIELD_USER_NAME, userName));
        runnerMap.expire(runnerConfig.getPairingRunnerTtl().toJavaDuration());

        addRunnerToWorkspace(workspaceId, runnerId);
        setUserRunner(workspaceId, userName, runnerId);

        return LocalRunnerPairResponse.builder()
                .pairingCode(code)
                .runnerId(runnerId)
                .expiresInSeconds((int) runnerConfig.getPairingCodeTtl().toSeconds())
                .build();
    }

    @Override
    public UUID connect(@NonNull String workspaceId, @NonNull String userName,
            @NonNull LocalRunnerConnectRequest request) {
        UUID runnerId;

        if (StringUtils.isNotBlank(request.pairingCode())) {
            runnerId = claimPairingCode(request.pairingCode(), workspaceId);
        } else {
            runnerId = idGenerator.generateId();
            addRunnerToWorkspace(workspaceId, runnerId);
        }

        evictExistingRunner(workspaceId, userName, runnerId);
        setUserRunner(workspaceId, userName, runnerId);
        activateRunner(runnerId, workspaceId, userName, request.runnerName());

        return runnerId;
    }

    @Override
    public LocalRunner.LocalRunnerPage listRunners(@NonNull String workspaceId, int page, int size) {
        RScoredSortedSet<String> runnerIds = redisClient.getScoredSortedSet(
                workspaceRunnersKey(workspaceId), StringCodec.INSTANCE);
        int total = runnerIds.size();

        int fromIndex = page * size;
        int toIndex = fromIndex + size - 1;

        var ids = runnerIds.valueRange(fromIndex, toIndex);

        List<LocalRunner> runners = new ArrayList<>();
        for (String runnerIdStr : ids) {
            UUID runnerId = UUID.fromString(runnerIdStr);
            LocalRunner runner = loadRunner(runnerId, workspaceId);
            if (runner != null) {
                runners.add(runner);
            }
        }

        return LocalRunner.LocalRunnerPage.builder()
                .page(page).size(size).total(total).content(runners).build();
    }

    @Override
    public LocalRunner getRunner(@NonNull String workspaceId, @NonNull UUID runnerId) {
        LocalRunner runner = loadRunner(runnerId, workspaceId);
        if (runner == null) {
            throw new NotFoundException("Runner not found: " + runnerId);
        }
        return runner;
    }

    @Override
    public void registerAgents(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull Map<String, LocalRunner.Agent> agents) {
        validateRunnerWorkspace(runnerId, workspaceId);

        if (agents.size() > runnerConfig.getMaxAgentsPerRunner()) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Too many agents. Maximum is " + runnerConfig.getMaxAgentsPerRunner())))
                    .build());
        }

        String key = runnerAgentsKey(runnerId);
        RMap<String, String> agentsMap = redisClient.getMap(key, StringCodec.INSTANCE);
        agentsMap.delete();

        if (!agents.isEmpty()) {
            Map<String, String> serialized = new HashMap<>();
            agents.forEach((name, agent) -> serialized.put(name, JsonUtils.writeValueAsString(agent)));
            agentsMap.putAll(serialized);
        }
    }

    @Override
    public LocalRunnerHeartbeatResponse heartbeat(@NonNull UUID runnerId, @NonNull String workspaceId) {
        RMap<String, String> runnerMap = redisClient.getMap(
                runnerKey(runnerId), StringCodec.INSTANCE);
        Map<String, String> fields = runnerMap.readAllMap();

        if (fields.isEmpty()) {
            throw new ClientErrorException(Response.status(Response.Status.GONE)
                    .entity(new ErrorMessage(List.of("Runner not found or evicted: " + runnerId)))
                    .build());
        }

        if (!workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
            throw new NotFoundException("Runner not found: " + runnerId);
        }

        String userName = fields.get(FIELD_USER_NAME);
        if (userName != null) {
            RBucket<String> currentRunnerBucket = redisClient.getBucket(
                    userRunnerKey(workspaceId, userName), StringCodec.INSTANCE);
            String currentRunnerId = currentRunnerBucket.get();
            if (currentRunnerId != null && !currentRunnerId.equals(runnerId.toString())) {
                throw new ClientErrorException(Response.status(Response.Status.GONE)
                        .entity(new ErrorMessage(List.of("Runner evicted by a newer connection")))
                        .build());
            }
        }

        setHeartbeat(runnerId);

        RList<String> activeJobs = redisClient.getList(
                activeJobsKey(runnerId), StringCodec.INSTANCE);
        List<String> activeJobIds = activeJobs.readAll();
        String now = Instant.now().toString();
        for (String jobIdStr : activeJobIds) {
            UUID jobId = UUID.fromString(jobIdStr);
            RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId), StringCodec.INSTANCE);
            if (jobMap.isExists()) {
                jobMap.put(FIELD_LAST_HEARTBEAT, now);
            }
        }

        RSet<String> cancellations = redisClient.getSet(
                runnerCancellationsKey(runnerId), StringCodec.INSTANCE);
        Set<String> cancelledIds = cancellations.readAll();
        cancellations.delete();

        return LocalRunnerHeartbeatResponse.builder()
                .cancelledJobIds(cancelledIds.stream().map(UUID::fromString).toList())
                .build();
    }

    @Override
    public UUID createJob(@NonNull String workspaceId, @NonNull String userName,
            @NonNull CreateLocalRunnerJobRequest request) {
        UUID runnerId;

        if (request.runnerId() == null) {
            String runnerIdStr = redisClient.<String>getBucket(
                    userRunnerKey(workspaceId, userName), StringCodec.INSTANCE).get();

            if (runnerIdStr == null) {
                throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorMessage(List.of(
                                "No runner configured. Pair one from the Runners page.")))
                        .build());
            }
            runnerId = UUID.fromString(runnerIdStr);
        } else {
            runnerId = request.runnerId();
        }

        validateRunnerWorkspace(runnerId, workspaceId);

        if (!isRunnerAlive(runnerId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of(
                            "Your runner is not connected. Start it with: opik connect")))
                    .build());
        }

        RList<String> pendingJobs = redisClient.getList(
                pendingJobsKey(runnerId), StringCodec.INSTANCE);
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
        jobFields.put(FIELD_PROJECT, request.project() != null ? request.project() : "default");
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
        if (request.metadata() != null) {
            jobFields.put(FIELD_METADATA, JsonUtils.writeValueAsString(request.metadata()));
        }

        int timeout = resolveAgentTimeout(runnerId, request.agentName());
        jobFields.put(FIELD_TIMEOUT, String.valueOf(timeout));

        RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId), StringCodec.INSTANCE);
        jobMap.putAll(jobFields);

        pendingJobs.add(jobId.toString());
        RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(
                runnerJobsKey(runnerId), StringCodec.INSTANCE);
        runnerJobs.add(Instant.now().toEpochMilli(), jobId.toString());

        return jobId;
    }

    @Override
    public CompletionStage<LocalRunnerJob> nextJob(@NonNull UUID runnerId, @NonNull String workspaceId) {
        validateRunnerWorkspace(runnerId, workspaceId);

        String pendingKey = pendingJobsKey(runnerId);
        String activeKey = activeJobsKey(runnerId);

        RBlockingDeque<String> blockingDeque = redisClient.getBlockingDeque(pendingKey, StringCodec.INSTANCE);

        return blockingDeque.pollFirstAsync(runnerConfig.getNextJobPollTimeout().toSeconds(), TimeUnit.SECONDS)
                .thenApplyAsync(jobIdStr -> {
                    if (jobIdStr == null) {
                        return null;
                    }

                    RList<String> activeList = redisClient.getList(activeKey, StringCodec.INSTANCE);
                    activeList.add(jobIdStr);

                    UUID jobId = UUID.fromString(jobIdStr);
                    RMap<String, String> jobMap = redisClient.getMap(
                            jobKey(jobId), StringCodec.INSTANCE);
                    String now = Instant.now().toString();
                    jobMap.put(FIELD_STATUS, LocalRunnerJobStatus.RUNNING.getValue());
                    jobMap.put(FIELD_STARTED_AT, now);
                    jobMap.put(FIELD_LAST_HEARTBEAT, now);

                    return buildRunnerJob(jobMap.readAllMap());
                });
    }

    @Override
    public LocalRunnerJob.LocalRunnerJobPage listJobs(@NonNull UUID runnerId, String project,
            @NonNull String workspaceId, int page, int size) {
        RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(
                runnerJobsKey(runnerId), StringCodec.INSTANCE);

        if (project != null) {
            return listJobsWithFilter(runnerJobs, project, workspaceId, page, size);
        }

        int total = runnerJobs.size();
        int fromIndex = page * size;
        int toIndex = fromIndex + size - 1;

        var jobIds = runnerJobs.valueRangeReversed(fromIndex, toIndex);

        List<LocalRunnerJob> jobs = new ArrayList<>();
        for (String jobIdStr : jobIds) {
            UUID jobId = UUID.fromString(jobIdStr);
            RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId), StringCodec.INSTANCE);
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
            String project, String workspaceId, int page, int size) {
        var allJobIds = runnerJobs.valueRangeReversed(0, -1);

        List<LocalRunnerJob> matched = new ArrayList<>();
        for (String jobIdStr : allJobIds) {
            UUID jobId = UUID.fromString(jobIdStr);
            RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId), StringCodec.INSTANCE);
            Map<String, String> fields = jobMap.readAllMap();
            if (fields.isEmpty() || !workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
                continue;
            }
            if (!project.equals(fields.get(FIELD_PROJECT))) {
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
    public LocalRunnerJob getJob(@NonNull UUID jobId, @NonNull String workspaceId) {
        var job = loadValidatedJob(jobId, workspaceId);
        return buildRunnerJob(job.fields());
    }

    @Override
    public List<LocalRunnerLogEntry> getJobLogs(@NonNull UUID jobId, int offset, @NonNull String workspaceId) {
        loadValidatedJob(jobId, workspaceId);

        RList<String> logsList = redisClient.getList(jobLogsKey(jobId), StringCodec.INSTANCE);
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
    public void appendLogs(@NonNull UUID jobId, @NonNull String workspaceId,
            @NonNull List<LocalRunnerLogEntry> entries) {
        if (entries.size() > runnerConfig.getMaxLogEntriesPerBatch()) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Too many log entries. Maximum per batch is " + runnerConfig.getMaxLogEntriesPerBatch())))
                    .build());
        }

        loadValidatedJob(jobId, workspaceId);

        RList<String> logsList = redisClient.getList(jobLogsKey(jobId), StringCodec.INSTANCE);
        List<String> serialized = entries.stream()
                .map(JsonUtils::writeValueAsString)
                .toList();
        logsList.addAll(serialized);
    }

    @Override
    public void reportResult(@NonNull UUID jobId, @NonNull String workspaceId,
            @NonNull LocalRunnerJobResultRequest result) {
        LocalRunnerJobStatus resultStatus = result.status();
        if (!TERMINAL_JOB_STATUSES.contains(resultStatus)) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Invalid result status. Must be one of: " + TERMINAL_JOB_STATUSES)))
                    .build());
        }

        var job = loadValidatedJob(jobId, workspaceId);
        RMap<String, String> jobMap = job.map();
        Map<String, String> fields = job.fields();

        String currentStatus = fields.get(FIELD_STATUS);
        LocalRunnerJobStatus current = parseJobStatus(currentStatus);
        if (current != null && (TERMINAL_JOB_STATUSES.contains(current) || current == LocalRunnerJobStatus.CANCELLED)) {
            return;
        }

        String now = Instant.now().toString();
        jobMap.put(FIELD_STATUS, resultStatus.getValue());
        jobMap.put(FIELD_COMPLETED_AT, now);
        if (result.result() != null) {
            jobMap.put(FIELD_RESULT, JsonUtils.writeValueAsString(result.result()));
        }
        if (result.error() != null) {
            jobMap.put(FIELD_ERROR, result.error());
        }
        if (result.traceId() != null) {
            jobMap.put(FIELD_TRACE_ID, result.traceId().toString());
        }

        String runnerIdStr = fields.get(FIELD_RUNNER_ID);
        if (runnerIdStr != null) {
            UUID runnerId = UUID.fromString(runnerIdStr);
            RList<String> activeList = redisClient.getList(
                    activeJobsKey(runnerId), StringCodec.INSTANCE);
            activeList.remove(jobId.toString());
        }

        expireJobAndLogs(jobId, jobMap);
    }

    @Override
    public void cancelJob(@NonNull UUID jobId, @NonNull String workspaceId) {
        var job = loadValidatedJob(jobId, workspaceId);
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
                    pendingJobsKey(runnerId), StringCodec.INSTANCE);
            boolean wasInPending = pendingJobs.remove(jobId.toString());

            if (wasInPending) {
                expireJobAndLogs(jobId, jobMap);
            } else {
                RSet<String> cancellations = redisClient.getSet(
                        runnerCancellationsKey(runnerId), StringCodec.INSTANCE);
                cancellations.add(jobId.toString());
            }
        }
    }

    @Override
    public void reapDeadRunners() {
        RSet<String> workspacesWithRunners = redisClient.getSet(
                WORKSPACES_WITH_RUNNERS_KEY, StringCodec.INSTANCE);
        Set<String> workspaceIds = workspacesWithRunners.readAll();

        for (String workspaceId : workspaceIds) {
            try {
                reapWorkspaceRunners(workspaceId);
            } catch (Exception e) {
                log.error("Failed to reap runners for workspace {}", workspaceId, e);
            }
        }
    }

    private void reapWorkspaceRunners(String workspaceId) {
        RScoredSortedSet<String> runnerIds = redisClient.getScoredSortedSet(
                workspaceRunnersKey(workspaceId), StringCodec.INSTANCE);
        var ids = runnerIds.readAll();

        for (String runnerIdStr : ids) {
            UUID runnerId = UUID.fromString(runnerIdStr);
            try {
                reapRunner(runnerId, workspaceId);
            } catch (Exception e) {
                log.error("Failed to reap runner {} in workspace {}", runnerId, workspaceId, e);
            }
            try {
                reapStuckJobs(runnerId);
            } catch (Exception e) {
                log.error("Failed to reap stuck jobs for runner {} in workspace {}", runnerId, workspaceId, e);
            }
        }

        if (runnerIds.isEmpty()) {
            workspacesWithRunners(workspaceId, false);
        }
    }

    private void reapRunner(UUID runnerId, String workspaceId) {
        if (isRunnerAlive(runnerId)) {
            return;
        }

        RMap<String, String> runnerMap = redisClient.getMap(
                runnerKey(runnerId), StringCodec.INSTANCE);
        if (!runnerMap.isExists()) {
            removeRunnerFromWorkspace(workspaceId, runnerId);
            return;
        }

        Map<String, String> runnerFields = runnerMap.readAllMap();

        String disconnectedAt = runnerFields.get(FIELD_DISCONNECTED_AT);
        if (disconnectedAt == null) {
            disconnectedAt = Instant.now().toString();
            runnerMap.put(FIELD_DISCONNECTED_AT, disconnectedAt);
        }

        Instant disconnected = Instant.parse(disconnectedAt);
        boolean shouldPurge = Duration.between(disconnected, Instant.now()).toSeconds() >= runnerConfig
                .getDeadRunnerPurgeTime().toSeconds();

        failOrphanedJobs(runnerId);

        if (shouldPurge) {
            purgeRunner(runnerId, workspaceId, runnerFields);
        }
    }

    private void failOrphanedJobs(UUID runnerId) {
        RList<String> activeJobs = redisClient.getList(
                activeJobsKey(runnerId), StringCodec.INSTANCE);
        List<String> activeJobIds = activeJobs.readAll();
        for (String jobIdStr : activeJobIds) {
            failJob(UUID.fromString(jobIdStr), "Runner disconnected");
        }
        activeJobs.delete();

        RList<String> pendingJobs = redisClient.getList(
                pendingJobsKey(runnerId), StringCodec.INSTANCE);
        List<String> pendingJobIds = pendingJobs.readAll();
        for (String jobIdStr : pendingJobIds) {
            failJob(UUID.fromString(jobIdStr), "Runner disconnected");
        }
        pendingJobs.delete();

        redisClient.getScoredSortedSet(runnerJobsKey(runnerId), StringCodec.INSTANCE).delete();
    }

    private void reapStuckJobs(UUID runnerId) {
        RList<String> activeJobs = redisClient.getList(
                activeJobsKey(runnerId), StringCodec.INSTANCE);
        List<String> activeJobIds = activeJobs.readAll();
        Instant now = Instant.now();

        for (String jobIdStr : activeJobIds) {
            UUID jobId = UUID.fromString(jobIdStr);
            RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId), StringCodec.INSTANCE);
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
        RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId), StringCodec.INSTANCE);
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
        RList<String> logsList = redisClient.getList(jobLogsKey(jobId), StringCodec.INSTANCE);
        logsList.expire(ttl);
    }

    private void purgeRunner(UUID runnerId, String workspaceId, Map<String, String> runnerFields) {
        log.info("Purging dead runner {} from workspace {}", runnerId, workspaceId);

        redisClient.getMap(runnerKey(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getMap(runnerAgentsKey(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getBucket(runnerHeartbeatKey(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getScoredSortedSet(runnerJobsKey(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getSet(runnerCancellationsKey(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getList(pendingJobsKey(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getList(activeJobsKey(runnerId), StringCodec.INSTANCE).delete();

        removeRunnerFromWorkspace(workspaceId, runnerId);

        String userName = runnerFields.get(FIELD_USER_NAME);
        if (userName != null) {
            RBucket<String> userRunnerBucket = redisClient.getBucket(
                    userRunnerKey(workspaceId, userName), StringCodec.INSTANCE);
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

    private UUID claimPairingCode(String code, String workspaceId) {
        RBucket<String> pairBucket = redisClient.getBucket(pairKey(code), StringCodec.INSTANCE);
        String value = pairBucket.get();
        if (value == null) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of("Invalid or expired pairing code")))
                    .build());
        }

        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Malformed pairing key value: " + value);
        }

        String storedWorkspaceId = parts[1];
        if (!workspaceId.equals(storedWorkspaceId)) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of("Pairing code belongs to a different workspace")))
                    .build());
        }

        pairBucket.delete();
        return UUID.fromString(parts[0]);
    }

    private void evictExistingRunner(String workspaceId, String userName, UUID newRunnerId) {
        RBucket<String> userRunnerBucket = redisClient.getBucket(
                userRunnerKey(workspaceId, userName), StringCodec.INSTANCE);
        String oldRunnerIdStr = userRunnerBucket.get();

        if (oldRunnerIdStr != null && !oldRunnerIdStr.equals(newRunnerId.toString())) {
            UUID oldRunnerId = UUID.fromString(oldRunnerIdStr);
            redisClient.getBucket(runnerHeartbeatKey(oldRunnerId), StringCodec.INSTANCE).delete();
            log.info("Evicted runner {} in workspace {}", oldRunnerId, workspaceId);
        }
    }

    private void setUserRunner(String workspaceId, String userName, UUID runnerId) {
        redisClient.getBucket(userRunnerKey(workspaceId, userName), StringCodec.INSTANCE)
                .set(runnerId.toString());
    }

    private void activateRunner(UUID runnerId, String workspaceId, String userName, String runnerName) {
        RMap<String, String> runnerMap = redisClient.getMap(
                runnerKey(runnerId), StringCodec.INSTANCE);
        runnerMap.putAll(Map.of(
                FIELD_NAME, runnerName,
                FIELD_STATUS, LocalRunnerStatus.CONNECTED.getValue(),
                FIELD_WORKSPACE_ID, workspaceId,
                FIELD_USER_NAME, userName,
                FIELD_CONNECTED_AT, Instant.now().toString()));
        runnerMap.clearExpire();
        setHeartbeat(runnerId);
    }

    private void setHeartbeat(UUID runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(
                runnerHeartbeatKey(runnerId), StringCodec.INSTANCE);
        heartbeat.set("alive", runnerConfig.getHeartbeatTtl().toJavaDuration());
    }

    private boolean isRunnerAlive(UUID runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(
                runnerHeartbeatKey(runnerId), StringCodec.INSTANCE);
        return heartbeat.isExists();
    }

    private void addRunnerToWorkspace(String workspaceId, UUID runnerId) {
        RScoredSortedSet<String> workspaceRunners = redisClient.getScoredSortedSet(
                workspaceRunnersKey(workspaceId), StringCodec.INSTANCE);
        workspaceRunners.add(Instant.now().toEpochMilli(), runnerId.toString());

        workspacesWithRunners(workspaceId, true);
    }

    private void removeRunnerFromWorkspace(String workspaceId, UUID runnerId) {
        RScoredSortedSet<String> workspaceRunners = redisClient.getScoredSortedSet(
                workspaceRunnersKey(workspaceId), StringCodec.INSTANCE);
        workspaceRunners.remove(runnerId.toString());
    }

    private void workspacesWithRunners(String workspaceId, boolean add) {
        RSet<String> workspaces = redisClient.getSet(WORKSPACES_WITH_RUNNERS_KEY, StringCodec.INSTANCE);
        if (add) {
            workspaces.add(workspaceId);
        } else {
            workspaces.remove(workspaceId);
        }
    }

    private LocalRunner loadRunner(UUID runnerId, String workspaceId) {
        RMap<String, String> runnerMap = redisClient.getMap(
                runnerKey(runnerId), StringCodec.INSTANCE);
        Map<String, String> fields = runnerMap.readAllMap();

        if (fields.isEmpty()) {
            return null;
        }

        String storedWorkspaceId = fields.get(FIELD_WORKSPACE_ID);
        if (!workspaceId.equals(storedWorkspaceId)) {
            return null;
        }

        String storedStatus = fields.get(FIELD_STATUS);
        LocalRunnerStatus status;
        if (LocalRunnerStatus.PAIRING.getValue().equals(storedStatus)) {
            status = LocalRunnerStatus.PAIRING;
        } else {
            status = isRunnerAlive(runnerId) ? LocalRunnerStatus.CONNECTED : LocalRunnerStatus.DISCONNECTED;
        }

        List<LocalRunner.Agent> agents = List.of();
        if (status == LocalRunnerStatus.CONNECTED) {
            agents = loadAgents(runnerId);
        }

        Instant connectedAt = fields.get(FIELD_CONNECTED_AT) != null
                ? Instant.parse(fields.get(FIELD_CONNECTED_AT))
                : null;

        return LocalRunner.builder()
                .id(runnerId)
                .name(fields.get(FIELD_NAME))
                .status(status)
                .connectedAt(connectedAt)
                .agents(agents)
                .build();
    }

    private List<LocalRunner.Agent> loadAgents(UUID runnerId) {
        RMap<String, String> agentsMap = redisClient.getMap(
                runnerAgentsKey(runnerId), StringCodec.INSTANCE);
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
                .project(fields.get(FIELD_PROJECT))
                .traceId(parseUUID(fields.get(FIELD_TRACE_ID)))
                .maskId(parseUUID(fields.get(FIELD_MASK_ID)))
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
                    runnerAgentsKey(runnerId), StringCodec.INSTANCE);
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

    private void validateRunnerWorkspace(UUID runnerId, String workspaceId) {
        RMap<String, String> runnerMap = redisClient.getMap(
                runnerKey(runnerId), StringCodec.INSTANCE);
        String storedWorkspaceId = runnerMap.get(FIELD_WORKSPACE_ID);
        if (!workspaceId.equals(storedWorkspaceId)) {
            throw new NotFoundException("Runner not found: " + runnerId);
        }
    }

    private record ValidatedJob(RMap<String, String> map, Map<String, String> fields) {
    }

    private ValidatedJob loadValidatedJob(UUID jobId, String workspaceId) {
        RMap<String, String> jobMap = redisClient.getMap(jobKey(jobId), StringCodec.INSTANCE);
        Map<String, String> fields = jobMap.readAllMap();
        if (fields.isEmpty()) {
            throw new NotFoundException("Job not found: " + jobId);
        }
        if (!workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
            throw new NotFoundException("Job not found: " + jobId);
        }
        return new ValidatedJob(jobMap, fields);
    }

}
