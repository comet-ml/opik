package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.ConnectResponse;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.HeartbeatResponse;
import com.comet.opik.api.runner.JobResultRequest;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.api.runner.Runner;
import com.comet.opik.api.runner.RunnerJob;
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
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@ImplementedBy(RunnerServiceImpl.class)
public interface RunnerService {

    PairResponse generatePairingCode(@NonNull String workspaceId, @NonNull String userName);

    ConnectResponse connect(@NonNull String workspaceId, @NonNull String userName,
            @NonNull ConnectRequest request);

    List<Runner> listRunners(@NonNull String workspaceId);

    Runner getRunner(@NonNull String workspaceId, @NonNull String runnerId);

    void registerAgents(@NonNull String runnerId, @NonNull Map<String, JsonNode> agents);

    HeartbeatResponse heartbeat(@NonNull String runnerId);

    RunnerJob createJob(@NonNull String workspaceId, @NonNull String userName,
            @NonNull CreateJobRequest request);

    RunnerJob nextJob(@NonNull String runnerId);

    RunnerJob.RunnerJobPage listJobs(@NonNull String runnerId, String project,
            @NonNull String workspaceId, int page, int size);

    RunnerJob getJob(@NonNull String jobId, @NonNull String workspaceId);

    List<LogEntry> getJobLogs(@NonNull String jobId, int offset, @NonNull String workspaceId);

    void appendLogs(@NonNull String jobId, @NonNull String workspaceId,
            @NonNull List<LogEntry> entries);

    void reportResult(@NonNull String jobId, @NonNull String workspaceId,
            @NonNull JobResultRequest result);

    void cancelJob(@NonNull String jobId, @NonNull String workspaceId);

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

    private static final String PAIR_KEY = "opik:pair:%s";
    private static final String RUNNER_KEY = "opik:runner:%s";
    private static final String RUNNER_AGENTS_KEY = "opik:runner:%s:agents";
    private static final String RUNNER_HEARTBEAT_KEY = "opik:runner:%s:heartbeat";
    private static final String WORKSPACE_RUNNERS_KEY = "opik:workspace:%s:runners";
    private static final String USER_RUNNER_KEY = "opik:workspace:%s:user:%s:runner";
    private static final String WORKSPACES_WITH_RUNNERS_KEY = "opik:workspaces:with_runners";
    private static final String JOB_KEY = "opik:job:%s";
    private static final String JOB_LOGS_KEY = "opik:job:%s:logs";
    private static final String PENDING_JOBS_KEY = "opik:jobs:%s:pending";
    private static final String ACTIVE_JOBS_KEY = "opik:jobs:%s:active";
    private static final String RUNNER_JOBS_KEY = "opik:runner:%s:jobs";
    private static final String RUNNER_CANCELLATIONS_KEY = "opik:runner:%s:cancellations";

    private static final String STATUS_PAIRING = "pairing";
    private static final String STATUS_CONNECTED = "connected";
    private static final String STATUS_DISCONNECTED = "disconnected";

    private static final String JOB_STATUS_PENDING = "pending";
    private static final String JOB_STATUS_RUNNING = "running";
    private static final String JOB_STATUS_COMPLETED = "completed";
    private static final String JOB_STATUS_FAILED = "failed";
    private static final String JOB_STATUS_CANCELLED = "cancelled";

    private final @NonNull RedissonClient redisClient;
    private final @NonNull RunnerConfig runnerConfig;
    private final @NonNull IdGenerator idGenerator;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public PairResponse generatePairingCode(@NonNull String workspaceId, @NonNull String userName) {
        String code = generateUniqueCode();
        String runnerId = idGenerator.generateId().toString();

        // Store pairing key
        RBucket<String> pairBucket = redisClient.getBucket(PAIR_KEY.formatted(code), StringCodec.INSTANCE);
        pairBucket.set(runnerId + ":" + workspaceId, Duration.ofSeconds(PAIRING_CODE_TTL_SECONDS));

        // Pre-create runner hash with pairing status and TTL
        RMap<String, String> runnerMap = redisClient.getMap(RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        runnerMap.putAll(Map.of(
                "name", "",
                "status", STATUS_PAIRING,
                "workspace_id", workspaceId,
                "user_name", userName));
        runnerMap.expire(Duration.ofSeconds(PAIRING_RUNNER_TTL_SECONDS));

        // Add to workspace sets
        addRunnerToWorkspace(workspaceId, runnerId);

        // Store user-runner mapping
        RBucket<String> userRunnerBucket = redisClient.getBucket(
                USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE);
        userRunnerBucket.set(runnerId);

        return PairResponse.builder()
                .pairingCode(code)
                .runnerId(runnerId)
                .expiresInSeconds(PAIRING_CODE_TTL_SECONDS)
                .build();
    }

    @Override
    public ConnectResponse connect(@NonNull String workspaceId, @NonNull String userName,
            @NonNull ConnectRequest request) {
        String runnerId;

        if (StringUtils.isNotBlank(request.pairingCode())) {
            // Pairing code flow
            runnerId = claimPairingCode(request.pairingCode(), workspaceId);
        } else {
            // API key flow — create runner directly
            runnerId = idGenerator.generateId().toString();

            RMap<String, String> runnerMap = redisClient.getMap(
                    RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
            runnerMap.putAll(Map.of(
                    "name", request.runnerName(),
                    "status", STATUS_CONNECTED,
                    "workspace_id", workspaceId,
                    "user_name", userName,
                    "connected_at", Instant.now().toString()));

            addRunnerToWorkspace(workspaceId, runnerId);
        }

        // Handle runner replacement — evict any existing runner for this user
        evictExistingRunner(workspaceId, userName, runnerId);

        // Store user-runner mapping
        RBucket<String> userRunnerBucket = redisClient.getBucket(
                USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE);
        userRunnerBucket.set(runnerId);

        // Update runner hash
        RMap<String, String> runnerMap = redisClient.getMap(
                RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        runnerMap.put("name", request.runnerName());
        runnerMap.put("status", STATUS_CONNECTED);
        runnerMap.put("connected_at", Instant.now().toString());
        runnerMap.clearExpire();

        // Set heartbeat
        setHeartbeat(runnerId);

        return ConnectResponse.builder()
                .runnerId(runnerId)
                .workspaceId(workspaceId)
                .build();
    }

    @Override
    public List<Runner> listRunners(@NonNull String workspaceId) {
        RSet<String> runnerIds = redisClient.getSet(
                WORKSPACE_RUNNERS_KEY.formatted(workspaceId), StringCodec.INSTANCE);
        Set<String> ids = runnerIds.readAll();

        List<Runner> runners = new ArrayList<>();
        for (String runnerId : ids) {
            Runner runner = loadRunner(runnerId, workspaceId);
            if (runner != null) {
                runners.add(runner);
            }
        }
        return runners;
    }

    @Override
    public Runner getRunner(@NonNull String workspaceId, @NonNull String runnerId) {
        Runner runner = loadRunner(runnerId, workspaceId);
        if (runner == null) {
            throw new NotFoundException("Runner not found");
        }
        return runner;
    }

    @Override
    public void registerAgents(@NonNull String runnerId, @NonNull Map<String, JsonNode> agents) {
        String key = RUNNER_AGENTS_KEY.formatted(runnerId);
        RMap<String, String> agentsMap = redisClient.getMap(key, StringCodec.INSTANCE);
        agentsMap.delete();

        if (!agents.isEmpty()) {
            Map<String, String> serialized = new HashMap<>();
            agents.forEach((name, meta) -> serialized.put(name, JsonUtils.writeValueAsString(meta)));
            agentsMap.putAll(serialized);
        }
    }

    @Override
    public HeartbeatResponse heartbeat(@NonNull String runnerId) {
        // Verify runner exists
        RMap<String, String> runnerMap = redisClient.getMap(
                RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        if (!runnerMap.isExists()) {
            throw new ClientErrorException(Response.status(Response.Status.GONE)
                    .entity(new ErrorMessage(List.of("Runner not found or evicted")))
                    .build());
        }

        // Check if this runner has been replaced
        String workspaceId = runnerMap.get("workspace_id");
        String userName = runnerMap.get("user_name");
        if (workspaceId != null && userName != null) {
            RBucket<String> currentRunnerBucket = redisClient.getBucket(
                    USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE);
            String currentRunnerId = currentRunnerBucket.get();
            if (currentRunnerId != null && !currentRunnerId.equals(runnerId)) {
                throw new ClientErrorException(Response.status(Response.Status.GONE)
                        .entity(new ErrorMessage(List.of("Runner evicted by a newer connection")))
                        .build());
            }
        }

        // Refresh heartbeat
        setHeartbeat(runnerId);

        // Update last_heartbeat on all active jobs
        RList<String> activeJobs = redisClient.getList(
                ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        List<String> activeJobIds = activeJobs.readAll();
        String now = Instant.now().toString();
        for (String jobId : activeJobIds) {
            RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
            if (jobMap.isExists()) {
                jobMap.put("last_heartbeat", now);
            }
        }

        // Read and clear cancellation set
        RSet<String> cancellations = redisClient.getSet(
                RUNNER_CANCELLATIONS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        Set<String> cancelledIds = cancellations.readAll();
        if (!cancelledIds.isEmpty()) {
            cancellations.delete();
        }

        return HeartbeatResponse.builder()
                .cancelledJobIds(new ArrayList<>(cancelledIds))
                .build();
    }

    @Override
    public RunnerJob createJob(@NonNull String workspaceId, @NonNull String userName,
            @NonNull CreateJobRequest request) {
        String runnerId = request.runnerId();

        if (StringUtils.isBlank(runnerId)) {
            // Look up user's runner
            RBucket<String> userRunnerBucket = redisClient.getBucket(
                    USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE);
            runnerId = userRunnerBucket.get();

            if (runnerId == null) {
                throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorMessage(List.of(
                                "No runner configured. Pair one from the Runners page.")))
                        .build());
            }
        }

        // Verify runner is online
        if (!isRunnerAlive(runnerId)) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorMessage(List.of(
                            "Your runner is not connected. Start it with: opik connect")))
                    .build());
        }

        // Check pending queue depth
        RList<String> pendingJobs = redisClient.getList(
                PENDING_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        if (pendingJobs.size() >= runnerConfig.getMaxPendingJobsPerRunner()) {
            throw new ClientErrorException(Response.status(429)
                    .entity(new ErrorMessage(List.of("Too many pending jobs for this runner")))
                    .build());
        }

        // Create job
        String jobId = idGenerator.generateId().toString();
        String now = Instant.now().toString();

        Map<String, String> jobFields = new HashMap<>();
        jobFields.put("id", jobId);
        jobFields.put("runner_id", runnerId);
        jobFields.put("agent_name", request.agentName());
        jobFields.put("status", JOB_STATUS_PENDING);
        jobFields.put("project", request.project() != null ? request.project() : "default");
        jobFields.put("workspace_id", workspaceId);
        jobFields.put("created_at", now);
        jobFields.put("retry_count", "0");
        jobFields.put("max_retries", "1");
        if (request.inputs() != null) {
            jobFields.put("inputs", JsonUtils.writeValueAsString(request.inputs()));
        }

        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        jobMap.putAll(jobFields);

        // Add to pending queue and runner's job set
        pendingJobs.add(jobId);
        RSet<String> runnerJobs = redisClient.getSet(
                RUNNER_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        runnerJobs.add(jobId);

        return buildRunnerJob(jobFields);
    }

    @Override
    public RunnerJob nextJob(@NonNull String runnerId) {
        String pendingKey = PENDING_JOBS_KEY.formatted(runnerId);
        String activeKey = ACTIVE_JOBS_KEY.formatted(runnerId);

        RBlockingDeque<String> blockingDeque = redisClient.getBlockingDeque(pendingKey, StringCodec.INSTANCE);
        String jobId;
        try {
            jobId = blockingDeque.poll(runnerConfig.getNextJobPollTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (jobId == null) {
            return null;
        }

        // Add to active list
        RList<String> activeList = redisClient.getList(activeKey, StringCodec.INSTANCE);
        activeList.add(jobId);

        // Update job status
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        String now = Instant.now().toString();
        jobMap.put("status", JOB_STATUS_RUNNING);
        jobMap.put("started_at", now);
        jobMap.put("last_heartbeat", now);

        return buildRunnerJob(jobMap.readAllMap());
    }

    @Override
    public RunnerJob.RunnerJobPage listJobs(@NonNull String runnerId, String project,
            @NonNull String workspaceId, int page, int size) {
        RSet<String> runnerJobs = redisClient.getSet(
                RUNNER_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        Set<String> allJobIds = runnerJobs.readAll();

        List<RunnerJob> jobs = new ArrayList<>();
        for (String jobId : allJobIds) {
            RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
            Map<String, String> fields = jobMap.readAllMap();
            if (fields.isEmpty()) {
                continue;
            }
            if (!workspaceId.equals(fields.get("workspace_id"))) {
                continue;
            }
            if (project != null && !project.equals(fields.get("project"))) {
                continue;
            }
            jobs.add(buildRunnerJob(fields));
        }

        // Sort by created_at descending
        jobs.sort((a, b) -> {
            if (b.createdAt() == null && a.createdAt() == null) return 0;
            if (b.createdAt() == null) return -1;
            if (a.createdAt() == null) return 1;
            return b.createdAt().compareTo(a.createdAt());
        });

        // Paginate
        int total = jobs.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<RunnerJob> pageContent = jobs.subList(fromIndex, toIndex);

        return RunnerJob.RunnerJobPage.builder()
                .page(page)
                .size(size)
                .total(total)
                .content(pageContent)
                .build();
    }

    @Override
    public RunnerJob getJob(@NonNull String jobId, @NonNull String workspaceId) {
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        Map<String, String> fields = jobMap.readAllMap();

        if (fields.isEmpty()) {
            throw new NotFoundException("Job not found");
        }
        validateWorkspaceOwnership(fields, workspaceId);

        return buildRunnerJob(fields);
    }

    @Override
    public List<LogEntry> getJobLogs(@NonNull String jobId, int offset, @NonNull String workspaceId) {
        // Validate job belongs to workspace
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        Map<String, String> fields = jobMap.readAllMap();
        if (fields.isEmpty()) {
            throw new NotFoundException("Job not found");
        }
        validateWorkspaceOwnership(fields, workspaceId);

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
    public void appendLogs(@NonNull String jobId, @NonNull String workspaceId,
            @NonNull List<LogEntry> entries) {
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        Map<String, String> fields = jobMap.readAllMap();
        if (fields.isEmpty()) {
            throw new NotFoundException("Job not found");
        }
        validateWorkspaceOwnership(fields, workspaceId);

        RList<String> logsList = redisClient.getList(JOB_LOGS_KEY.formatted(jobId), StringCodec.INSTANCE);
        List<String> serialized = entries.stream()
                .map(JsonUtils::writeValueAsString)
                .toList();
        logsList.addAll(serialized);
    }

    @Override
    public void reportResult(@NonNull String jobId, @NonNull String workspaceId,
            @NonNull JobResultRequest result) {
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        Map<String, String> fields = jobMap.readAllMap();
        if (fields.isEmpty()) {
            throw new NotFoundException("Job not found");
        }
        validateWorkspaceOwnership(fields, workspaceId);

        String now = Instant.now().toString();
        jobMap.put("status", result.status());
        jobMap.put("completed_at", now);
        if (result.result() != null) {
            jobMap.put("result", JsonUtils.writeValueAsString(result.result()));
        }
        if (result.error() != null) {
            jobMap.put("error", result.error());
        }
        if (result.traceId() != null) {
            jobMap.put("trace_id", result.traceId());
        }

        // Remove from active list
        String runnerId = fields.get("runner_id");
        if (runnerId != null) {
            RList<String> activeList = redisClient.getList(
                    ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
            activeList.remove(jobId);
        }

        // Set TTLs
        Duration ttl = Duration.ofDays(runnerConfig.getCompletedJobTtlDays());
        jobMap.expire(ttl);
        RList<String> logsList = redisClient.getList(JOB_LOGS_KEY.formatted(jobId), StringCodec.INSTANCE);
        logsList.expire(ttl);
    }

    @Override
    public void cancelJob(@NonNull String jobId, @NonNull String workspaceId) {
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        Map<String, String> fields = jobMap.readAllMap();
        if (fields.isEmpty()) {
            throw new NotFoundException("Job not found");
        }
        validateWorkspaceOwnership(fields, workspaceId);

        jobMap.put("status", JOB_STATUS_CANCELLED);

        String runnerId = fields.get("runner_id");
        if (runnerId != null) {
            RSet<String> cancellations = redisClient.getSet(
                    RUNNER_CANCELLATIONS_KEY.formatted(runnerId), StringCodec.INSTANCE);
            cancellations.add(jobId);
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
        }

        // Clean up workspace if no runners left
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
            // Runner hash already expired, clean up references
            removeRunnerFromWorkspace(workspaceId, runnerId);
            return;
        }

        Map<String, String> runnerFields = runnerMap.readAllMap();
        String connectedAt = runnerFields.get("connected_at");

        // Check if runner has been dead long enough to purge
        boolean shouldPurge = false;
        if (connectedAt != null) {
            Instant connected = Instant.parse(connectedAt);
            shouldPurge = Duration.between(connected, Instant.now()).toHours() >= runnerConfig
                    .getDeadRunnerPurgeHours();
        }

        // Fail orphaned active jobs
        failOrphanedJobs(runnerId);

        if (shouldPurge) {
            purgeRunner(runnerId, workspaceId, runnerFields);
        }
    }

    private void failOrphanedJobs(String runnerId) {
        // Fail active jobs
        RList<String> activeJobs = redisClient.getList(
                ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        List<String> activeJobIds = activeJobs.readAll();
        for (String jobId : activeJobIds) {
            failJob(jobId, "Runner disconnected");
        }
        activeJobs.delete();

        // Fail pending jobs
        RList<String> pendingJobs = redisClient.getList(
                PENDING_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        List<String> pendingJobIds = pendingJobs.readAll();
        for (String jobId : pendingJobIds) {
            failJob(jobId, "Runner disconnected");
        }
        pendingJobs.delete();
    }

    private void failJob(String jobId, String error) {
        RMap<String, String> jobMap = redisClient.getMap(JOB_KEY.formatted(jobId), StringCodec.INSTANCE);
        if (!jobMap.isExists()) {
            return;
        }
        String status = jobMap.get("status");
        if (JOB_STATUS_COMPLETED.equals(status) || JOB_STATUS_FAILED.equals(status)
                || JOB_STATUS_CANCELLED.equals(status)) {
            return;
        }
        jobMap.put("status", JOB_STATUS_FAILED);
        jobMap.put("error", error);
        jobMap.put("completed_at", Instant.now().toString());

        Duration ttl = Duration.ofDays(runnerConfig.getCompletedJobTtlDays());
        jobMap.expire(ttl);
        RList<String> logsList = redisClient.getList(JOB_LOGS_KEY.formatted(jobId), StringCodec.INSTANCE);
        logsList.expire(ttl);
    }

    private void purgeRunner(String runnerId, String workspaceId, Map<String, String> runnerFields) {
        log.info("Purging dead runner {} from workspace {}", runnerId, workspaceId);

        // Delete runner hash and related keys
        redisClient.getMap(RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getMap(RUNNER_AGENTS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getBucket(RUNNER_HEARTBEAT_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getSet(RUNNER_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getSet(RUNNER_CANCELLATIONS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getList(PENDING_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();
        redisClient.getList(ACTIVE_JOBS_KEY.formatted(runnerId), StringCodec.INSTANCE).delete();

        removeRunnerFromWorkspace(workspaceId, runnerId);

        // Remove user-runner mapping
        String userName = runnerFields.get("user_name");
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
                sb.append(PAIRING_CODE_ALPHABET.charAt(secureRandom.nextInt(PAIRING_CODE_ALPHABET.length())));
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
        String value = pairBucket.getAndDelete();
        if (value == null) {
            throw new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(List.of("Invalid or expired pairing code")))
                    .build());
        }

        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Malformed pairing key value: " + value);
        }
        return parts[0]; // runnerId
    }

    private void evictExistingRunner(String workspaceId, String userName, String newRunnerId) {
        RBucket<String> userRunnerBucket = redisClient.getBucket(
                USER_RUNNER_KEY.formatted(workspaceId, userName), StringCodec.INSTANCE);
        String oldRunnerId = userRunnerBucket.get();

        if (oldRunnerId != null && !oldRunnerId.equals(newRunnerId)) {
            // Delete old runner's heartbeat to trigger eviction on next heartbeat call
            redisClient.getBucket(RUNNER_HEARTBEAT_KEY.formatted(oldRunnerId), StringCodec.INSTANCE).delete();
            log.info("Evicted runner {} for user {} in workspace {}", oldRunnerId, userName, workspaceId);
        }
    }

    private void setHeartbeat(String runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(
                RUNNER_HEARTBEAT_KEY.formatted(runnerId), StringCodec.INSTANCE);
        heartbeat.set("alive", Duration.ofSeconds(runnerConfig.getHeartbeatTtlSeconds()));
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

    private Runner loadRunner(String runnerId, String workspaceId) {
        RMap<String, String> runnerMap = redisClient.getMap(
                RUNNER_KEY.formatted(runnerId), StringCodec.INSTANCE);
        Map<String, String> fields = runnerMap.readAllMap();

        if (fields.isEmpty()) {
            return null;
        }

        String storedWorkspaceId = fields.get("workspace_id");
        if (!workspaceId.equals(storedWorkspaceId)) {
            return null;
        }

        // Derive status from heartbeat
        String status = isRunnerAlive(runnerId) ? STATUS_CONNECTED : STATUS_DISCONNECTED;

        // Load agents
        List<Runner.Agent> agents = List.of();
        if (STATUS_CONNECTED.equals(status)) {
            agents = loadAgents(runnerId);
        }

        Instant connectedAt = fields.get("connected_at") != null
                ? Instant.parse(fields.get("connected_at"))
                : null;

        return Runner.builder()
                .id(runnerId)
                .name(fields.get("name"))
                .status(status)
                .connectedAt(connectedAt)
                .agents(agents)
                .build();
    }

    private List<Runner.Agent> loadAgents(String runnerId) {
        RMap<String, String> agentsMap = redisClient.getMap(
                RUNNER_AGENTS_KEY.formatted(runnerId), StringCodec.INSTANCE);
        Map<String, String> allAgents = agentsMap.readAllMap();

        List<Runner.Agent> agents = new ArrayList<>();
        for (var entry : allAgents.entrySet()) {
            try {
                JsonNode meta = JsonUtils.getJsonNodeFromString(entry.getValue());
                String project = meta.has("project") ? meta.get("project").asText() : "default";

                List<Runner.Agent.Param> params = new ArrayList<>();
                if (meta.has("params") && meta.get("params").isArray()) {
                    for (JsonNode param : meta.get("params")) {
                        params.add(Runner.Agent.Param.builder()
                                .name(param.has("name") ? param.get("name").asText() : "")
                                .type(param.has("type") ? param.get("type").asText() : "")
                                .build());
                    }
                }

                agents.add(Runner.Agent.builder()
                        .name(entry.getKey())
                        .project(project)
                        .params(params)
                        .build());
            } catch (Exception e) {
                log.warn("Failed to parse agent metadata for {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return agents;
    }

    private RunnerJob buildRunnerJob(Map<String, String> fields) {
        JsonNode inputs = parseJsonNode(fields.get("inputs"));
        JsonNode result = parseJsonNode(fields.get("result"));

        return RunnerJob.builder()
                .id(fields.get("id"))
                .runnerId(fields.get("runner_id"))
                .agentName(fields.get("agent_name"))
                .status(fields.get("status"))
                .inputs(inputs)
                .result(result)
                .error(fields.get("error"))
                .project(fields.get("project"))
                .traceId(fields.get("trace_id"))
                .createdAt(parseInstant(fields.get("created_at")))
                .startedAt(parseInstant(fields.get("started_at")))
                .completedAt(parseInstant(fields.get("completed_at")))
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

    private void validateWorkspaceOwnership(Map<String, String> jobFields, String workspaceId) {
        String jobWorkspaceId = jobFields.get("workspace_id");
        if (!workspaceId.equals(jobWorkspaceId)) {
            throw new NotFoundException("Job not found");
        }
    }
}
