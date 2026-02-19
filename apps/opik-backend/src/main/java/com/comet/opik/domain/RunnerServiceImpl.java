package com.comet.opik.domain;

import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.ConnectResponse;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.api.runner.Runner;
import com.comet.opik.api.runner.RunnerJob;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class RunnerServiceImpl implements RunnerService {

    private static final Duration PAIRING_CODE_TTL = Duration.ofMinutes(5);
    private static final Duration AUTH_TOKEN_TTL = Duration.ofHours(24);
    private static final String PAIRING_KEY_PREFIX = "opik:pair:";
    private static final String RUNNER_KEY_PREFIX = "opik:runner:";
    private static final String RUNNER_TOKEN_PREFIX = "opik:runner:token:";
    private static final String WORKSPACE_RUNNERS_PREFIX = "opik:workspace:";
    private static final String PROJECT_RUNNERS_PREFIX = "opik:project:";
    private static final String JOB_KEY_PREFIX = "opik:job:";
    private static final String JOBS_ALL_KEY = "opik:jobs:all";
    private static final String JOBS_PENDING_SUFFIX = ":pending";
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final @NonNull RedissonClient redissonClient;
    private final @NonNull @Config OpikConfiguration config;

    @Override
    public PairResponse generatePairingCode(@NonNull String workspaceId) {
        String code = generateCode(6);
        String runnerId = UUID.randomUUID().toString();
        String key = PAIRING_KEY_PREFIX + code;

        var bucket = redissonClient.<String>getBucket(key, StringCodec.INSTANCE);
        bucket.set(runnerId + ":" + workspaceId, PAIRING_CODE_TTL.toSeconds(), TimeUnit.SECONDS);

        var runnerMap = redissonClient.<String, String>getMap(RUNNER_KEY_PREFIX + runnerId, StringCodec.INSTANCE);
        runnerMap.put("status", "pairing");
        runnerMap.put("workspace_id", workspaceId);

        log.info("Generated pairing code '{}' with runner '{}' for workspace '{}'", code, runnerId, workspaceId);

        return PairResponse.builder()
                .pairingCode(code)
                .runnerId(runnerId)
                .expiresInSeconds(PAIRING_CODE_TTL.toSeconds())
                .build();
    }

    @Override
    public ConnectResponse connect(@NonNull ConnectRequest request) {
        String pairKey = PAIRING_KEY_PREFIX + request.pairingCode().toUpperCase();
        var pairBucket = redissonClient.<String>getBucket(pairKey, StringCodec.INSTANCE);
        String pairValue = pairBucket.get();

        if (pairValue == null) {
            throw new NotFoundException("Invalid or expired pairing code");
        }

        pairBucket.delete();

        // pairValue is "runnerId:workspaceId"
        String[] parts = pairValue.split(":", 2);
        String runnerId = parts[0];
        String workspaceId = parts[1];

        String authToken = generateHexToken(48);

        var runnerMap = redissonClient.<String, String>getMap(RUNNER_KEY_PREFIX + runnerId, StringCodec.INSTANCE);
        runnerMap.put("name", request.runnerName());
        runnerMap.put("workspace_id", workspaceId);
        runnerMap.put("status", "connected");
        runnerMap.put("connected_at", Instant.now().toString());

        var tokenBucket = redissonClient.<String>getBucket(RUNNER_TOKEN_PREFIX + authToken, StringCodec.INSTANCE);
        tokenBucket.set(runnerId + ":" + workspaceId, AUTH_TOKEN_TTL.toSeconds(), TimeUnit.SECONDS);

        var workspaceRunners = redissonClient.<String>getSet(WORKSPACE_RUNNERS_PREFIX + workspaceId + ":runners",
                StringCodec.INSTANCE);
        workspaceRunners.add(runnerId);

        log.info("Runner '{}' connected with id '{}' in workspace '{}'",
                request.runnerName(), runnerId, workspaceId);

        return ConnectResponse.builder()
                .runnerId(runnerId)
                .authToken(authToken)
                .redisUrl(config.getRunner().getRedisUrl())
                .workspaceId(workspaceId)
                .build();
    }

    @Override
    public List<Runner> listRunners(@NonNull String workspaceId) {
        var workspaceRunners = redissonClient.<String>getSet(WORKSPACE_RUNNERS_PREFIX + workspaceId + ":runners",
                StringCodec.INSTANCE);
        Set<String> runnerIds = workspaceRunners.readAll();

        if (runnerIds == null || runnerIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Runner> runners = new ArrayList<>();
        for (String runnerId : runnerIds) {
            try {
                Runner runner = loadRunner(runnerId);
                if (runner != null) {
                    runners.add(runner);
                }
            } catch (Exception e) {
                log.warn("Failed to load runner '{}'", runnerId, e);
            }
        }

        return runners;
    }

    @Override
    public Runner getRunner(@NonNull String runnerId, @NonNull String workspaceId) {
        Runner runner = loadRunner(runnerId);
        if (runner == null) {
            throw new NotFoundException("Runner not found");
        }
        return runner;
    }

    @Override
    public RunnerJob createJob(@NonNull CreateJobRequest request, @NonNull String workspaceId) {
        String targetRunnerId = request.runnerId();

        if (targetRunnerId == null || targetRunnerId.isBlank()) {
            var projectRunners = redissonClient.<String>getSet(PROJECT_RUNNERS_PREFIX + request.project() + ":runners",
                    StringCodec.INSTANCE);
            Set<String> runnerIds = projectRunners.readAll();

            if (runnerIds == null || runnerIds.isEmpty()) {
                throw new NotFoundException("No runners available for project '" + request.project() + "'");
            }

            targetRunnerId = runnerIds.iterator().next();
        }

        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Map<String, String> jobData = new HashMap<>();
        jobData.put("id", jobId);
        jobData.put("runner_id", targetRunnerId);
        jobData.put("agent_name", request.agentName());
        jobData.put("status", "pending");
        jobData.put("inputs", unwrapJsonNode(request.inputs()));
        jobData.put("project", request.project());
        jobData.put("created_at", now.toString());

        var jobMap = redissonClient.<String, String>getMap(JOB_KEY_PREFIX + jobId, StringCodec.INSTANCE);
        jobMap.putAll(jobData);

        var allJobs = redissonClient.<String>getSet(JOBS_ALL_KEY, StringCodec.INSTANCE);
        allJobs.add(jobId);

        var pendingQueue = redissonClient.<String>getList("opik:jobs:" + targetRunnerId + JOBS_PENDING_SUFFIX,
                StringCodec.INSTANCE);
        pendingQueue.add(0, jobId);

        log.info("Created job '{}' for agent '{}' on runner '{}'", jobId, request.agentName(), targetRunnerId);

        return RunnerJob.builder()
                .id(jobId)
                .runnerId(targetRunnerId)
                .agentName(request.agentName())
                .status("pending")
                .inputs(request.inputs())
                .project(request.project())
                .createdAt(now)
                .build();
    }

    @Override
    public List<RunnerJob> listJobs(@NonNull String runnerId, String project) {
        var allJobs = redissonClient.<String>getSet(JOBS_ALL_KEY, StringCodec.INSTANCE);
        Set<String> jobIds = allJobs.readAll();

        if (jobIds == null || jobIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<RunnerJob> jobs = new ArrayList<>();
        for (String jobId : jobIds) {
            try {
                RunnerJob job = loadJob(jobId);
                if (job != null && runnerId.equals(job.runnerId())) {
                    if (project == null || project.equals(job.project())) {
                        jobs.add(job);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load job '{}'", jobId, e);
            }
        }

        return jobs;
    }

    @Override
    public RunnerJob getJob(@NonNull String jobId) {
        RunnerJob job = loadJob(jobId);
        if (job == null) {
            throw new NotFoundException("Job not found");
        }
        return job;
    }

    @Override
    public List<LogEntry> getJobLogs(@NonNull String jobId, int offset) {
        String logKey = JOB_KEY_PREFIX + jobId + ":logs";
        var logList = redissonClient.<String>getList(logKey, StringCodec.INSTANCE);
        int size = logList.size();

        if (offset >= size) {
            return Collections.emptyList();
        }

        List<String> rawEntries = logList.range(offset, size - 1);
        List<LogEntry> entries = new ArrayList<>();
        for (String raw : rawEntries) {
            try {
                entries.add(JsonUtils.readValue(raw, LogEntry.class));
            } catch (Exception e) {
                entries.add(LogEntry.builder().stream("stdout").text(raw).build());
            }
        }
        return entries;
    }

    private String unwrapJsonNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return "{}";
        }
        if (!node.isTextual()) {
            return node.toString();
        }
        String text = node.textValue();
        try {
            return unwrapJsonNode(JsonUtils.readTree(text));
        } catch (Exception e) {
            return text;
        }
    }

    private Runner loadRunner(String runnerId) {
        var runnerMap = redissonClient.<String, String>getMap(RUNNER_KEY_PREFIX + runnerId, StringCodec.INSTANCE);
        Map<String, String> data = runnerMap.readAllMap();

        if (data == null || data.isEmpty()) {
            return null;
        }

        var agentsMap = redissonClient.<String, String>getMap(RUNNER_KEY_PREFIX + runnerId + ":agents",
                StringCodec.INSTANCE);
        Map<String, String> agentsData = agentsMap.readAllMap();

        List<Runner.AgentInfo> agents = new ArrayList<>();
        if (agentsData != null) {
            for (var entry : agentsData.entrySet()) {
                String agentName = entry.getKey();
                String value = entry.getValue();
                String project;
                List<Runner.ParamInfo> params = Collections.emptyList();

                try {
                    var node = JsonUtils.getJsonNodeFromString(value);
                    if (node.isObject() && node.has("project")) {
                        project = node.get("project").asText();
                        var paramsNode = node.get("params");
                        if (paramsNode != null && paramsNode.isArray()) {
                            params = new ArrayList<>();
                            for (var p : paramsNode) {
                                params.add(Runner.ParamInfo.builder()
                                        .name(p.get("name").asText())
                                        .type(p.has("type") ? p.get("type").asText() : "str")
                                        .build());
                            }
                        }
                    } else {
                        project = value;
                    }
                } catch (Exception e) {
                    project = value;
                }

                agents.add(Runner.AgentInfo.builder()
                        .name(agentName)
                        .project(project)
                        .params(params)
                        .build());
            }
        }

        String connectedAtStr = data.get("connected_at");
        Instant connectedAt = connectedAtStr != null ? Instant.parse(connectedAtStr) : null;

        return Runner.builder()
                .id(runnerId)
                .name(data.get("name"))
                .status(data.get("status"))
                .connectedAt(connectedAt)
                .agents(agents)
                .build();
    }

    private RunnerJob loadJob(String jobId) {
        var jobMap = redissonClient.<String, String>getMap(JOB_KEY_PREFIX + jobId, StringCodec.INSTANCE);
        Map<String, String> data = jobMap.readAllMap();

        if (data == null || data.isEmpty()) {
            return null;
        }

        return RunnerJob.builder()
                .id(data.get("id"))
                .runnerId(data.get("runner_id"))
                .agentName(data.get("agent_name"))
                .status(data.get("status"))
                .inputs(parseJsonNode(data.get("inputs")))
                .result(parseJsonNode(data.get("result")))
                .error(data.get("error"))
                .stdout(data.get("stdout"))
                .project(data.get("project"))
                .traceId(data.get("trace_id"))
                .createdAt(parseInstant(data.get("created_at")))
                .startedAt(parseInstant(data.get("started_at")))
                .completedAt(parseInstant(data.get("completed_at")))
                .build();
    }

    private com.fasterxml.jackson.databind.JsonNode parseJsonNode(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return JsonUtils.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String generateCode(int length) {
        var random = new SecureRandom();
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private String generateHexToken(int length) {
        var random = new SecureRandom();
        var bytes = new byte[length / 2];
        random.nextBytes(bytes);
        var sb = new StringBuilder(length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
