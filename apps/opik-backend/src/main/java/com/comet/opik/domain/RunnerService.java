package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.HeartbeatResponse;
import com.comet.opik.api.runner.JobResultRequest;
import com.comet.opik.api.runner.JobStatus;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.api.runner.RunnerStatus;
import com.comet.opik.infrastructure.RunnerConfig;
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
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@ImplementedBy(RunnerServiceImpl.class)
public interface RunnerService {

    PairResponse generatePairingCode(String workspaceId, String userName);

    UUID connect(String workspaceId, String userName, ConnectRequest request);

    LocalRunner.LocalRunnerPage listRunners(String workspaceId, int page, int size);

    LocalRunner getRunner(String workspaceId, UUID runnerId);

    void registerAgents(UUID runnerId, String workspaceId, Map<String, LocalRunner.Agent> agents);

    HeartbeatResponse heartbeat(UUID runnerId, String workspaceId);

    UUID createJob(String workspaceId, String userName, CreateJobRequest request);

    CompletionStage<LocalRunnerJob> nextJob(UUID runnerId, String workspaceId);

    LocalRunnerJob.LocalRunnerJobPage listJobs(UUID runnerId, String project, String workspaceId,
            int page, int size);

    LocalRunnerJob getJob(UUID jobId, String workspaceId);

    List<LogEntry> getJobLogs(UUID jobId, int offset, String workspaceId);

    void appendLogs(UUID jobId, String workspaceId, List<LogEntry> entries);

    void reportResult(UUID jobId, String workspaceId, JobResultRequest result);

    void cancelJob(UUID jobId, String workspaceId);

    void reapDeadRunners();
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class RunnerServiceImpl implements RunnerService {

    private static final String PAIRING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int PAIRING_CODE_LENGTH = 6;
    private static final int PAIRING_CODE_TTL_SECONDS = 300;
    private static final int PAIRING_RUNNER_TTL_SECONDS = 600;
    private static final int MAX_AGENTS_PER_RUNNER = 50;
    private static final int MAX_LOG_ENTRIES_PER_BATCH = 1000;

    private static final String PAIR_KEY = "opik:runners:pair:%s";
    private static final String RUNNER_KEY = "opik:runners:runner:%s";
    private static final String RUNNER_AGENTS_KEY = "opik:runners:runner:%s:agents";
    private static final String RUNNER_HEARTBEAT_KEY = "opik:runners:runner:%s:heartbeat";
    private static final String WORKSPACE_RUNNERS_KEY = "opik:runners:workspace:%s:runners";
    private static final String USER_RUNNER_KEY = "opik:runners:workspace:%s:user:%s:runner";
    private static final String WORKSPACES_WITH_RUNNERS_KEY = "opik:runners:workspaces:with_runners";
    private static final String JOB_KEY = "opik:runners:job:%s";
    private static final String JOB_LOGS_KEY = "opik:runners:job:%s:logs";
    private static final String PENDING_JOBS_KEY = "opik:runners:jobs:%s:pending";
    private static final String ACTIVE_JOBS_KEY = "opik:runners:jobs:%s:active";
    private static final String RUNNER_JOBS_KEY = "opik:runners:runner:%s:jobs";
    private static final String RUNNER_CANCELLATIONS_KEY = "opik:runners:runner:%s:cancellations";

    private static final Set<JobStatus> TERMINAL_JOB_STATUSES = Set.of(JobStatus.COMPLETED, JobStatus.FAILED);

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

    private final @NonNull RedissonClient redisClient;
    private final @NonNull RunnerConfig runnerConfig;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public PairResponse generatePairingCode(@NonNull String workspaceId, @NonNull String userName) {
        String code = generateUniqueCode();
        UUID runnerId = idGenerator.generateId();

        RBucket<String> pairBucket = redisClient.getBucket(PAIR_KEY.formatted(code), StringCodec.INSTANCE);
        pairBucket.set(runnerId + ":" + workspaceId, Duration.ofSeconds(PAIRING_CODE_TTL_SECONDS));

        RMap<String, String> runnerMap = redisClient.getMap(RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        runnerMap.putAll(Map.of(
                FIELD_NAME, "",
                FIELD_STATUS, RunnerStatus.PAIRING.getValue(),
                FIELD_WORKSPACE_ID, workspaceId,
                FIELD_USER_NAME, userName));
        runnerMap.expire(Duration.ofSeconds(PAIRING_RUNNER_TTL_SECONDS));

        addRunnerToWorkspace(workspaceId, runnerId.toString());
        setUserRunner(workspaceId, userName, runnerId.toString());

        return PairResponse.builder()
                .pairingCode(code)
                .runnerId(runnerId)
                .expiresInSeconds(PAIRING_CODE_TTL_SECONDS)
                .build();
    }

    @Override
    public UUID connect(@NonNull String workspaceId, @NonNull String userName,
            @NonNull ConnectRequest request) {
        String runnerId;

        if (StringUtils.isNotBlank(request.pairingCode())) {
            runnerId = claimPairingCode(request.pairingCode(), workspaceId);
        } else {
            runnerId = idGenerator.generateId().toString();
            addRunnerToWorkspace(workspaceId, runnerId);
        }

        evictExistingRunner(workspaceId, userName, runnerId);
        setUserRunner(workspaceId, userName, runnerId);
        activateRunner(runnerId, workspaceId, userName, request.runnerName());

        return UUID.fromString(runnerId);
    }

    @Override
    public LocalRunner.LocalRunnerPage listRunners(@NonNull String workspaceId, int page, int size) {
        RSet<String> runnerIds = redisClient.getSet(
                WORKSPACE_RUNNERS_KEY.formatted(workspaceId), StringCodec.INSTANCE);
        Set<String> ids = runnerIds.readAll();

        List<LocalRunner> runners = new ArrayList<>();
        for (String runnerId : ids) {
            LocalRunner runner = loadRunner(runnerId, workspaceId);
            if (runner != null) {
                runners.add(runner);
            }
        }

        int total = runners.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<LocalRunner> pageContent = runners.subList(fromIndex, toIndex);

        return LocalRunner.LocalRunnerPage.builder()
                .page(page)
                .size(size)
                .total(total)
                .content(pageContent)
                .build();
    }

    @Override
    public LocalRunner getRunner(@NonNull String workspaceId, @NonNull UUID runnerId) {
        LocalRunner runner = loadRunner(runnerId.toString(), workspaceId);
        if (runner == null) {
            throw new NotFoundException("Runner not found");
        }
        return runner;
    }

    @Override
    public void registerAgents(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull Map<String, LocalRunner.Agent> agents) {
        validateRunnerWorkspace(runnerId.toString(), workspaceId);

        if (agents.size() > MAX_AGENTS_PER_RUNNER) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Too many agents. Maximum is " + MAX_AGENTS_PER_RUNNER)))
                    .build());
        }

        String key = RUNNER_AGENTS_KEY.formatted(runnerId);
        RMap<String, String> agentsMap = redisClient.getMap(key, StringCodec.INSTANCE);
        agentsMap.delete();

        if (!agents.isEmpty()) {
            Map<String, String> serialized = new HashMap<>();
            agents.forEach((name, agent) -> serialized.put(name, JsonUtils.writeValueAsString(agent)));
            agentsMap.putAll(serialized);
        }
    }

    @Override
    public HeartbeatResponse heartbeat(@NonNull UUID runnerId, @NonNull String workspaceId) {
        RMap<String, String> runnerMap = redisClient.getMap(
                RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        Map<String, String> fields = runnerMap.readAllMap();

        if (fields.isEmpty()) {
            throw new ClientErrorException(Response.status(Response.Status.GONE)
                    .entity(new ErrorMessage(List.of("Runner not found or evicted")))
                    .build());
        }

        if (!workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
            throw new NotFoundException("Runner not found");
        }

        String userName = fields.get(FIELD_USER_NAME);
        if (userName != null) {
            RBucket<String> currentRunnerBucket = redisClient.getBucket(
                    USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE);
            String currentRunnerId = currentRunnerBucket.get();
            if (currentRunnerId != null && !currentRunnerId.equals(runnerId.toString())) {
                throw new ClientErrorException(Response.status(Response.Status.GONE)
                        .entity(new ErrorMessage(List.of("Runner evicted by a newer connection")))
                        .build());
            }
        }

        setHeartbeat(runnerId.toString());

        RList<String> activeJobs = redisClient.getList(
                ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        List<String> activeJobIds = activeJobs.readAll();
        String now = Instant.now().toString();
        for (String jobId : activeJobIds) {
            RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
            if (jobMap.isExists()) {
                jobMap.put(FIELD_LAST_HEARTBEAT, now);
            }
        }

        RSet<String> cancellations = redisClient.getSet(
                RUNNER_CANCELLATIONS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        Set<String> cancelledIds = cancellations.readAll();
        cancellations.delete();

        return HeartbeatResponse.builder()
                .cancelledJobIds(cancelledIds.stream().map(UUID::fromString).toList())
                .build();
    }

    @Override
    public UUID createJob(@NonNull String workspaceId, @NonNull String userName,
            @NonNull CreateJobRequest request) {
        String runnerId;

        if (request.runnerId() == null) {
            runnerId = redisClient.<String>getBucket(
                    USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE).get();

            if (runnerId == null) {
                throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorMessage(List.of(
                                "No runner configured. Pair one from the Runners page.")))
                        .build());
            }
        } else {
            runnerId = request.runnerId().toString();
        }

        validateRunnerWorkspace(runnerId, workspaceId);

        if (!isRunnerAlive(runnerId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of(
                            "Your runner is not connected. Start it with: opik connect")))
                    .build());
        }

        RList<String> pendingJobs = redisClient.getList(
                PENDING_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        if (pendingJobs.size() >= runnerConfig.getMaxPendingJobsPerRunner()) {
            throw new ClientErrorException(Response.status(429)
                    .entity(new ErrorMessage(List.of("Too many pending jobs for this runner")))
                    .build());
        }

        String jobId = idGenerator.generateId().toString();
        String now = Instant.now().toString();

        Map<String, String> jobFields = new HashMap<>();
        jobFields.put(FIELD_ID, jobId);
        jobFields.put(FIELD_RUNNER_ID, runnerId);
        jobFields.put(FIELD_AGENT_NAME, request.agentName());
        jobFields.put(FIELD_STATUS, JobStatus.PENDING.getValue());
        jobFields.put(FIELD_PROJECT, request.project() != null ? request.project() : "default");
        jobFields.put(FIELD_WORKSPACE_ID, workspaceId);
        jobFields.put(FIELD_CREATED_AT, now);
        jobFields.put(FIELD_RETRY_COUNT, "0");
        jobFields.put(FIELD_MAX_RETRIES, "1");
        if (request.inputs() != null) {
            jobFields.put(FIELD_INPUTS, JsonUtils.writeValueAsString(request.inputs()));
        }

        int timeout = resolveAgentTimeout(runnerId, request.agentName());
        jobFields.put(FIELD_TIMEOUT, String.valueOf(timeout));

        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        jobMap.putAll(jobFields);

        pendingJobs.add(jobId);
        RSet<String> runnerJobs = redisClient.getSet(
                RUNNER_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        runnerJobs.add(jobId);

        return UUID.fromString(jobId);
    }

    @Override
    public CompletionStage<LocalRunnerJob> nextJob(@NonNull UUID runnerId, @NonNull String workspaceId) {
        validateRunnerWorkspace(runnerId.toString(), workspaceId);

        String pendingKey = PENDING_JOBS_KEY.formatted(runnerId);
        String activeKey = ACTIVE_JOBS_KEY.formatted(runnerId);

        RBlockingDeque<String> blockingDeque = redisClient.getBlockingDeque(pendingKey, StringCodec.INSTANCE);

        return blockingDeque.pollFirstAsync(runnerConfig.getNextJobPollTimeout().toSeconds(), TimeUnit.SECONDS)
                .thenApplyAsync(jobId -> {
                    if (jobId == null) {
                        return null;
                    }

                    RList<String> activeList = redisClient.getList(activeKey, StringCodec.INSTANCE);
                    activeList.add(jobId);

                    RMap<String, String> jobMap = redisClient.getMap(
                            JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
                    String now = Instant.now().toString();
                    jobMap.put(FIELD_STATUS, JobStatus.RUNNING.getValue());
                    jobMap.put(FIELD_STARTED_AT, now);
                    jobMap.put(FIELD_LAST_HEARTBEAT, now);

                    return buildRunnerJob(jobMap.readAllMap());
                });
    }

    @Override
    public LocalRunnerJob.LocalRunnerJobPage listJobs(@NonNull UUID runnerId, String project,
            @NonNull String workspaceId, int page, int size) {
        RSet<String> runnerJobs = redisClient.getSet(
                RUNNER_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        Set<String> allJobIds = runnerJobs.readAll();

        List<LocalRunnerJob> jobs = new ArrayList<>();
        for (String jobId : allJobIds) {
            RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
            Map<String, String> fields = jobMap.readAllMap();
            if (fields.isEmpty()) {
                continue;
            }
            if (!workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
                continue;
            }
            if (project != null && !project.equals(fields.get(FIELD_PROJECT))) {
                continue;
            }
            jobs.add(buildRunnerJob(fields));
        }

        jobs.sort(Comparator.comparing(LocalRunnerJob::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int total = jobs.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<LocalRunnerJob> pageContent = jobs.subList(fromIndex, toIndex);

        return LocalRunnerJob.LocalRunnerJobPage.builder()
                .page(page)
                .size(size)
                .total(total)
                .content(pageContent)
                .build();
    }

    @Override
    public LocalRunnerJob getJob(@NonNull UUID jobId, @NonNull String workspaceId) {
        var job = loadValidatedJob(jobId.toString(), workspaceId);
        return buildRunnerJob(job.fields());
    }

    @Override
    public List<LogEntry> getJobLogs(@NonNull UUID jobId, int offset, @NonNull String workspaceId) {
        loadValidatedJob(jobId.toString(), workspaceId);

        RList<String> logsList = redisClient.getList(JOB_LOGS_KEY.formatted(jobId), StringCodec.INSTANCE);
        int logSize = logsList.size();
        if (offset >= logSize) {
            return List.of();
        }

        List<String> entries = logsList.range(offset, logSize - 1);
        return entries.stream()
                .map(json -> JsonUtils.readValue(json, LogEntry.class))
                .toList();
    }

    @Override
    public void appendLogs(@NonNull UUID jobId, @NonNull String workspaceId,
            @NonNull List<LogEntry> entries) {
        if (entries.size() > MAX_LOG_ENTRIES_PER_BATCH) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Too many log entries. Maximum per batch is " + MAX_LOG_ENTRIES_PER_BATCH)))
                    .build());
        }

        loadValidatedJob(jobId.toString(), workspaceId);

        RList<String> logsList = redisClient.getList(JOB_LOGS_KEY.formatted(jobId), StringCodec.INSTANCE);
        List<String> serialized = entries.stream()
                .map(JsonUtils::writeValueAsString)
                .toList();
        logsList.addAll(serialized);
    }

    @Override
    public void reportResult(@NonNull UUID jobId, @NonNull String workspaceId,
            @NonNull JobResultRequest result) {
        var job = loadValidatedJob(jobId.toString(), workspaceId);
        RMap<String, String> jobMap = job.map();
        Map<String, String> fields = job.fields();

        JobStatus resultStatus = result.status();
        if (!TERMINAL_JOB_STATUSES.contains(resultStatus)) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of(
                            "Invalid result status. Must be one of: " + TERMINAL_JOB_STATUSES)))
                    .build());
        }

        String currentStatus = fields.get(FIELD_STATUS);
        JobStatus current = parseJobStatus(currentStatus);
        if (current != null && (TERMINAL_JOB_STATUSES.contains(current) || current == JobStatus.CANCELLED)) {
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

        String runnerId = fields.get(FIELD_RUNNER_ID);
        if (runnerId != null) {
            RList<String> activeList = redisClient.getList(
                    ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
            activeList.remove(jobId.toString());
        }

        expireJobAndLogs(jobId.toString(), jobMap);
    }

    @Override
    public void cancelJob(@NonNull UUID jobId, @NonNull String workspaceId) {
        var job = loadValidatedJob(jobId.toString(), workspaceId);
        RMap<String, String> jobMap = job.map();
        Map<String, String> fields = job.fields();

        String currentStatus = fields.get(FIELD_STATUS);
        JobStatus current = parseJobStatus(currentStatus);
        if (current != null && (TERMINAL_JOB_STATUSES.contains(current) || current == JobStatus.CANCELLED)) {
            return;
        }

        jobMap.put(FIELD_STATUS, JobStatus.CANCELLED.getValue());
        jobMap.put(FIELD_COMPLETED_AT, Instant.now().toString());

        String runnerId = fields.get(FIELD_RUNNER_ID);
        if (runnerId != null) {
            RList<String> pendingJobs = redisClient.getList(
                    PENDING_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
            boolean wasInPending = pendingJobs.remove(jobId.toString());

            if (wasInPending) {
                expireJobAndLogs(jobId.toString(), jobMap);
            } else {
                RSet<String> cancellations = redisClient.getSet(
                        RUNNER_CANCELLATIONS_KEY.formatted(runnerId), StringCodec.INSTANCE);
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
        RSet<String> runnerIds = redisClient.getSet(
                WORKSPACE_RUNNERS_KEY.formatted(workspaceId), StringCodec.INSTANCE);
        Set<String> ids = runnerIds.readAll();

        for (String runnerId : ids) {
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

    private void reapRunner(String runnerId, String workspaceId) {
        if (isRunnerAlive(runnerId)) {
            return;
        }

        RMap<String, String> runnerMap = redisClient.getMap(
                RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
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

    private void failOrphanedJobs(String runnerId) {
        RList<String> activeJobs = redisClient.getList(
                ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        List<String> activeJobIds = activeJobs.readAll();
        for (String jobId : activeJobIds) {
            failJob(jobId, "Runner disconnected");
        }
        activeJobs.delete();

        RList<String> pendingJobs = redisClient.getList(
                PENDING_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        List<String> pendingJobIds = pendingJobs.readAll();
        for (String jobId : pendingJobIds) {
            failJob(jobId, "Runner disconnected");
        }
        pendingJobs.delete();

        redisClient.getSet(RUNNER_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
    }

    private void reapStuckJobs(String runnerId) {
        RList<String> activeJobs = redisClient.getList(
                ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        List<String> activeJobIds = activeJobs.readAll();
        Instant now = Instant.now();

        for (String jobId : activeJobIds) {
            RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
            if (!jobMap.isExists()) {
                activeJobs.remove(jobId);
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
                } catch (NumberFormatException ignored) {
                }
            }

            if (Duration.between(startedAt, now).getSeconds() > timeoutSeconds) {
                log.warn("Job {} on runner {} exceeded timeout of {}s", jobId, runnerId, timeoutSeconds);
                failJob(jobId, "Job timed out after " + timeoutSeconds + "s");
                activeJobs.remove(jobId);
            }
        }
    }

    private void failJob(String jobId, String error) {
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        if (!jobMap.isExists()) {
            return;
        }
        String status = jobMap.get(FIELD_STATUS);
        JobStatus current = parseJobStatus(status);
        if (current == JobStatus.COMPLETED || current == JobStatus.FAILED || current == JobStatus.CANCELLED) {
            return;
        }
        jobMap.put(FIELD_STATUS, JobStatus.FAILED.getValue());
        jobMap.put(FIELD_ERROR, error);
        jobMap.put(FIELD_COMPLETED_AT, Instant.now().toString());

        expireJobAndLogs(jobId, jobMap);
    }

    private void expireJobAndLogs(String jobId, RMap<String, String> jobMap) {
        Duration ttl = runnerConfig.getCompletedJobTtl().toJavaDuration();
        jobMap.expire(ttl);
        RList<String> logsList = redisClient.getList(JOB_LOGS_KEY.formatted(jobId), StringCodec.INSTANCE);
        logsList.expire(ttl);
    }

    private void purgeRunner(String runnerId, String workspaceId, Map<String, String> runnerFields) {
        log.info("Purging dead runner {} from workspace {}", runnerId, workspaceId);

        redisClient.getMap(RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getMap(RUNNER_AGENTS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getBucket(RUNNER_HEARTBEAT_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getSet(RUNNER_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getSet(RUNNER_CANCELLATIONS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getList(PENDING_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getList(ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();

        removeRunnerFromWorkspace(workspaceId, runnerId);

        String userName = runnerFields.get(FIELD_USER_NAME);
        if (userName != null) {
            RBucket<String> userRunnerBucket = redisClient.getBucket(
                    USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE);
            String currentId = userRunnerBucket.get();
            if (runnerId.equals(currentId)) {
                userRunnerBucket.delete();
            }
        }
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(PAIRING_CODE_LENGTH);
            for (int i = 0; i < PAIRING_CODE_LENGTH; i++) {
                sb.append(PAIRING_CODE_ALPHABET
                        .charAt(RandomUtils.secure().randomInt(0, PAIRING_CODE_ALPHABET.length())));
            }
            String code = sb.toString();

            RBucket<String> existing = redisClient.getBucket(PAIR_KEY.formatted(code), StringCodec.INSTANCE);
            if (!existing.isExists()) {
                return code;
            }
        }
        throw new IllegalStateException("Failed to generate unique pairing code after 10 attempts");
    }

    private String claimPairingCode(String code, String workspaceId) {
        RBucket<String> pairBucket = redisClient.getBucket(PAIR_KEY.formatted(code), StringCodec.INSTANCE);
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
        return parts[0];
    }

    private void evictExistingRunner(String workspaceId, String userName, String newRunnerId) {
        RBucket<String> userRunnerBucket = redisClient.getBucket(
                USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE);
        String oldRunnerId = userRunnerBucket.get();

        if (oldRunnerId != null && !oldRunnerId.equals(newRunnerId)) {
            redisClient.getBucket(RUNNER_HEARTBEAT_KEY.formatted(oldRunnerId), StringCodec.INSTANCE).delete();
            log.info("Evicted runner {} in workspace {}", oldRunnerId, workspaceId);
        }
    }

    private void setUserRunner(String workspaceId, String userName, String runnerId) {
        redisClient.getBucket(USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE)
                .set(runnerId);
    }

    private void activateRunner(String runnerId, String workspaceId, String userName, String runnerName) {
        RMap<String, String> runnerMap = redisClient.getMap(
                RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        runnerMap.putAll(Map.of(
                FIELD_NAME, runnerName,
                FIELD_STATUS, RunnerStatus.CONNECTED.getValue(),
                FIELD_WORKSPACE_ID, workspaceId,
                FIELD_USER_NAME, userName,
                FIELD_CONNECTED_AT, Instant.now().toString()));
        runnerMap.clearExpire();
        setHeartbeat(runnerId);
    }

    private void setHeartbeat(String runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(
                RUNNER_HEARTBEAT_KEY.formatted(runnerId), StringCodec.INSTANCE);
        heartbeat.set("alive", runnerConfig.getHeartbeatTtl().toJavaDuration());
    }

    private boolean isRunnerAlive(String runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(
                RUNNER_HEARTBEAT_KEY.formatted(runnerId), StringCodec.INSTANCE);
        return heartbeat.isExists();
    }

    private void addRunnerToWorkspace(String workspaceId, String runnerId) {
        RSet<String> workspaceRunners = redisClient.getSet(
                WORKSPACE_RUNNERS_KEY.formatted(workspaceId), StringCodec.INSTANCE);
        workspaceRunners.add(runnerId);

        workspacesWithRunners(workspaceId, true);
    }

    private void removeRunnerFromWorkspace(String workspaceId, String runnerId) {
        RSet<String> workspaceRunners = redisClient.getSet(
                WORKSPACE_RUNNERS_KEY.formatted(workspaceId), StringCodec.INSTANCE);
        workspaceRunners.remove(runnerId);
    }

    private void workspacesWithRunners(String workspaceId, boolean add) {
        RSet<String> workspaces = redisClient.getSet(WORKSPACES_WITH_RUNNERS_KEY, StringCodec.INSTANCE);
        if (add) {
            workspaces.add(workspaceId);
        } else {
            workspaces.remove(workspaceId);
        }
    }

    private LocalRunner loadRunner(String runnerId, String workspaceId) {
        RMap<String, String> runnerMap = redisClient.getMap(
                RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        Map<String, String> fields = runnerMap.readAllMap();

        if (fields.isEmpty()) {
            return null;
        }

        String storedWorkspaceId = fields.get(FIELD_WORKSPACE_ID);
        if (!workspaceId.equals(storedWorkspaceId)) {
            return null;
        }

        String storedStatus = fields.get(FIELD_STATUS);
        RunnerStatus status;
        if (RunnerStatus.PAIRING.getValue().equals(storedStatus)) {
            status = RunnerStatus.PAIRING;
        } else {
            status = isRunnerAlive(runnerId) ? RunnerStatus.CONNECTED : RunnerStatus.DISCONNECTED;
        }

        List<LocalRunner.Agent> agents = List.of();
        if (status == RunnerStatus.CONNECTED) {
            agents = loadAgents(runnerId);
        }

        Instant connectedAt = fields.get(FIELD_CONNECTED_AT) != null
                ? Instant.parse(fields.get(FIELD_CONNECTED_AT))
                : null;

        return LocalRunner.builder()
                .id(UUID.fromString(runnerId))
                .name(fields.get(FIELD_NAME))
                .status(status)
                .connectedAt(connectedAt)
                .agents(agents)
                .build();
    }

    private List<LocalRunner.Agent> loadAgents(String runnerId) {
        RMap<String, String> agentsMap = redisClient.getMap(
                RUNNER_AGENTS_KEY.formatted(runnerId), StringCodec.INSTANCE);
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

    private JobStatus parseJobStatus(String value) {
        if (value == null) {
            return null;
        }
        for (JobStatus s : JobStatus.values()) {
            if (s.getValue().equals(value)) {
                return s;
            }
        }
        return null;
    }

    private int resolveAgentTimeout(String runnerId, String agentName) {
        try {
            RMap<String, String> agentsMap = redisClient.getMap(
                    RUNNER_AGENTS_KEY.formatted(runnerId), StringCodec.INSTANCE);
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

    private void validateRunnerWorkspace(String runnerId, String workspaceId) {
        RMap<String, String> runnerMap = redisClient.getMap(
                RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        validateRunnerWorkspace(runnerMap, workspaceId);
    }

    private void validateRunnerWorkspace(RMap<String, String> runnerMap, String workspaceId) {
        String storedWorkspaceId = runnerMap.get(FIELD_WORKSPACE_ID);
        if (!workspaceId.equals(storedWorkspaceId)) {
            throw new NotFoundException("Runner not found");
        }
    }

    private record ValidatedJob(RMap<String, String> map, Map<String, String> fields) {
    }

    private ValidatedJob loadValidatedJob(String jobId, String workspaceId) {
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        Map<String, String> fields = jobMap.readAllMap();
        if (fields.isEmpty()) {
            throw new NotFoundException("Job not found");
        }
        if (!workspaceId.equals(fields.get(FIELD_WORKSPACE_ID))) {
            throw new NotFoundException("Job not found");
        }
        return new ValidatedJob(jobMap, fields);
    }
}
