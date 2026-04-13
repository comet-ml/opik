package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerStatus;
import com.comet.opik.api.runner.RunnerType;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(RunnerServiceImpl.class)
public interface RunnerService {

    void activateFromPairing(String workspaceId, String userName, UUID projectId, UUID runnerId,
            String runnerName, RunnerType type);

    LocalRunner.LocalRunnerPage listRunners(String workspaceId, String userName, UUID projectId,
            LocalRunnerStatus status, int page, int size);

    LocalRunner getRunner(String workspaceId, String userName, UUID runnerId);

    LocalRunnerHeartbeatResponse heartbeat(UUID runnerId, String workspaceId, String userName,
            List<String> capabilities);

    void patchChecklist(UUID runnerId, String workspaceId, String userName, JsonNode updates);

    void reapDeadRunners();

    void validateRunnerOwnership(UUID runnerId, String workspaceId, String userName);

    boolean isRunnerAlive(UUID runnerId);

    boolean isRunnerOwnedByUser(UUID runnerId, String workspaceId, String userName);

    boolean hasCapability(UUID runnerId, String capability);

    String getActiveRunnerId(String workspaceId, UUID projectId, String userName, RunnerType type);
}

@Slf4j
@Singleton
class RunnerServiceImpl implements RunnerService {

    private static final java.util.regex.Pattern CAPABILITY_PATTERN = java.util.regex.Pattern.compile("[a-z_]+");

    static final String WORKSPACES_WITH_RUNNERS_KEY = "opik:runners:workspaces:with_runners";

    static String runnerKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId;
    }

    static String runnerAgentsKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId + ":agents";
    }

    static String runnerHeartbeatKey(UUID runnerId) {
        return "opik:runners:runner:" + runnerId + ":heartbeat";
    }

    static String workspaceRunnersKey(String workspaceId) {
        return "opik:runners:workspace:" + workspaceId + ":runners";
    }

    static String projectUserRunnerKey(String workspaceId, UUID projectId, String userName, RunnerType type) {
        return "opik:runners:workspace:" + workspaceId + ":project:" + projectId + ":user:" + userName + ":runner:"
                + type.getValue();
    }

    static String projectRunnersKey(String workspaceId, UUID projectId) {
        return "opik:runners:workspace:" + workspaceId + ":project:" + projectId + ":runners";
    }

    static String workspaceUserRunnersKey(String workspaceId, String userName) {
        return "opik:runners:workspace:" + workspaceId + ":user:" + userName + ":runners";
    }

    static final String FIELD_ID = "id";
    static final String FIELD_NAME = "name";
    static final String FIELD_STATUS = "status";
    static final String FIELD_WORKSPACE_ID = "workspace_id";
    static final String FIELD_USER_NAME = "user_name";
    static final String FIELD_CONNECTED_AT = "connected_at";
    static final String FIELD_DISCONNECTED_AT = "disconnected_at";
    static final String FIELD_PROJECT_ID = "project_id";
    static final String FIELD_CAPABILITIES = "capabilities";
    static final String FIELD_CHECKLIST = "checklist";
    static final String FIELD_TYPE = "type";

    private static final List<String> DEFAULT_CAPABILITIES = List.of("jobs");
    private static final int DEEP_MERGE_MAX_DEPTH = 10;

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ProjectService projectService;
    private final @NonNull LocalRunnerConfig runnerConfig;
    private final @NonNull Provider<EndpointJobService> endpointJobService;
    private final @NonNull Provider<ConnectBridgeService> connectBridgeService;

    @Inject
    RunnerServiceImpl(@NonNull StringRedisClient redisClient, @NonNull IdGenerator idGenerator,
            @NonNull ProjectService projectService, @NonNull LocalRunnerConfig runnerConfig,
            @NonNull Provider<EndpointJobService> endpointJobService,
            @NonNull Provider<ConnectBridgeService> connectBridgeService) {
        this.redisClient = redisClient;
        this.idGenerator = idGenerator;
        this.projectService = projectService;
        this.runnerConfig = runnerConfig;
        this.endpointJobService = endpointJobService;
        this.connectBridgeService = connectBridgeService;
    }

    @Override
    public void activateFromPairing(@NonNull String workspaceId, @NonNull String userName,
            @NonNull UUID projectId, @NonNull UUID runnerId, @NonNull String runnerName, @NonNull RunnerType type) {
        // Evict any existing runner of the same type for this (workspace, project, user),
        // register the new runner in the workspace/user/project sets, point the
        // type-scoped user→runner bucket at it, and flip the runner row to CONNECTED.
        evictExistingRunner(workspaceId, projectId, userName, runnerId, type);
        registerRunnerInSets(workspaceId, userName, projectId, runnerId);
        setUserRunner(workspaceId, projectId, userName, runnerId, type);
        activateRunner(runnerId, workspaceId, projectId, userName, runnerName, type);
    }

    @Override
    public LocalRunner.LocalRunnerPage listRunners(@NonNull String workspaceId, @NonNull String userName,
            @NonNull UUID projectId, LocalRunnerStatus status, int page, int size) {
        RSet<String> projectRunnerIds = redisClient.getSet(projectRunnersKey(workspaceId, projectId));
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
        String typeStr = runnerMap.get(FIELD_TYPE);
        RunnerType type = typeStr != null ? RunnerType.fromValue(typeStr) : RunnerType.ENDPOINT;

        RBucket<String> currentRunnerBucket = redisClient.getBucket(
                projectUserRunnerKey(workspaceId, projectId, userName, type));
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

        List<UUID> cancelledJobs = type == RunnerType.ENDPOINT
                ? endpointJobService.get().processHeartbeat(runnerId)
                : List.of();

        return LocalRunnerHeartbeatResponse.builder()
                .cancelledJobIds(cancelledJobs)
                .build();
    }

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

    @Override
    public void reapDeadRunners() {
        RSet<String> workspacesWithRunners = redisClient.getSet(WORKSPACES_WITH_RUNNERS_KEY);
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

    @Override
    public void validateRunnerOwnership(UUID runnerId, String workspaceId, String userName) {
        if (!isRunnerOwnedByUser(runnerId, workspaceId, userName)) {
            throw new NotFoundException("Runner not found: " + runnerId);
        }
    }

    @Override
    public boolean isRunnerAlive(UUID runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(runnerHeartbeatKey(runnerId));
        return heartbeat.isExists();
    }

    @Override
    public boolean isRunnerOwnedByUser(UUID runnerId, String workspaceId, String userName) {
        RScoredSortedSet<String> userRunners = redisClient.getScoredSortedSet(
                workspaceUserRunnersKey(workspaceId, userName));
        return userRunners.contains(runnerId.toString());
    }

    @Override
    public boolean hasCapability(UUID runnerId, String capability) {
        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
        String caps = runnerMap.get(FIELD_CAPABILITIES);
        List<String> capabilities = parseCapabilities(caps);
        return capabilities.contains(capability);
    }

    @Override
    public String getActiveRunnerId(String workspaceId, UUID projectId, String userName, RunnerType type) {
        return redisClient.<String>getBucket(
                projectUserRunnerKey(workspaceId, projectId, userName, type)).get();
    }

    // --- Private methods ---

    private void evictExistingRunner(String workspaceId, UUID projectId, String userName,
            UUID newRunnerId, RunnerType type) {
        RBucket<String> userRunnerBucket = redisClient.getBucket(
                projectUserRunnerKey(workspaceId, projectId, userName, type));
        String oldRunnerIdStr = userRunnerBucket.get();

        if (oldRunnerIdStr != null && !oldRunnerIdStr.equals(newRunnerId.toString())) {
            UUID oldRunnerId = UUID.fromString(oldRunnerIdStr);
            redisClient.getBucket(runnerHeartbeatKey(oldRunnerId)).delete();

            RMap<String, String> oldRunnerMap = redisClient.getMap(runnerKey(oldRunnerId));
            if (oldRunnerMap.isExists()) {
                oldRunnerMap.put(FIELD_DISCONNECTED_AT, Instant.now().toString());
            }

            endpointJobService.get().failOrphanedJobs(oldRunnerId);
            connectBridgeService.get().failOrphanedBridgeCommands(oldRunnerId);

            RSet<String> projectRunners = redisClient.getSet(projectRunnersKey(workspaceId, projectId));
            projectRunners.remove(oldRunnerIdStr);

            removeRunnerFromWorkspace(workspaceId, userName, oldRunnerId);

            log.info("Evicted runner '{}' in workspace '{}'", oldRunnerId, workspaceId);
        }
    }

    private void setUserRunner(String workspaceId, UUID projectId, String userName, UUID runnerId, RunnerType type) {
        redisClient.getBucket(projectUserRunnerKey(workspaceId, projectId, userName, type))
                .set(runnerId.toString());
    }

    private void activateRunner(UUID runnerId, String workspaceId, UUID projectId, String userName,
            String runnerName, RunnerType type) {
        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
        runnerMap.putAll(Map.of(
                FIELD_NAME, runnerName,
                FIELD_STATUS, LocalRunnerStatus.CONNECTED.getValue(),
                FIELD_WORKSPACE_ID, workspaceId,
                FIELD_USER_NAME, userName,
                FIELD_PROJECT_ID, projectId.toString(),
                FIELD_CONNECTED_AT, Instant.now().toString(),
                FIELD_TYPE, type.getValue()));
        runnerMap.clearExpire();
        setHeartbeat(runnerId);
    }

    private void registerRunnerInSets(String workspaceId, String userName, UUID projectId, UUID runnerId) {
        double score = Instant.now().toEpochMilli();

        RScoredSortedSet<String> workspaceRunners = redisClient.getScoredSortedSet(
                workspaceRunnersKey(workspaceId));
        workspaceRunners.add(score, runnerId.toString());

        RScoredSortedSet<String> userRunners = redisClient.getScoredSortedSet(
                workspaceUserRunnersKey(workspaceId, userName));
        userRunners.add(score, runnerId.toString());

        RSet<String> projectRunners = redisClient.getSet(projectRunnersKey(workspaceId, projectId));
        projectRunners.add(runnerId.toString());

        workspacesWithRunners(workspaceId, true);
    }

    private void setHeartbeat(UUID runnerId) {
        RBucket<String> heartbeat = redisClient.getBucket(runnerHeartbeatKey(runnerId));
        heartbeat.set("alive", runnerConfig.getHeartbeatTtl().toJavaDuration());
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
        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
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

        String typeStr = fields.get(FIELD_TYPE);
        RunnerType type = typeStr != null ? RunnerType.fromValue(typeStr) : RunnerType.ENDPOINT;

        return LocalRunner.builder()
                .id(runnerId)
                .name(fields.get(FIELD_NAME))
                .projectId(parseUUID(fields.get(FIELD_PROJECT_ID)))
                .status(status)
                .connectedAt(connectedAt)
                .agents(agents)
                .capabilities(capabilities)
                .checklist(checklist)
                .type(type)
                .build();
    }

    private List<LocalRunner.Agent> loadAgents(UUID runnerId) {
        RMap<String, String> agentsMap = redisClient.getMap(runnerAgentsKey(runnerId));
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

    private int reapWorkspaceRunners(String workspaceId, int remaining) {
        RScoredSortedSet<String> runnerIds = redisClient.getScoredSortedSet(workspaceRunnersKey(workspaceId));
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

            RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
            String typeStr = runnerMap.get(FIELD_TYPE);
            RunnerType runnerType = typeStr != null ? RunnerType.fromValue(typeStr) : RunnerType.ENDPOINT;

            if (runnerType == RunnerType.ENDPOINT) {
                try {
                    endpointJobService.get().reapStuckJobs(runnerId);
                } catch (Exception e) {
                    log.error("Failed to reap stuck jobs for runner '{}' in workspace '{}'", runnerId, workspaceId, e);
                }
            }
            if (runnerType == RunnerType.CONNECT) {
                try {
                    connectBridgeService.get().reapStaleBridgeCommands(runnerId);
                } catch (Exception e) {
                    log.error("Failed to reap stale bridge commands for runner '{}' in workspace '{}'", runnerId,
                            workspaceId, e);
                }
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

        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));

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
            endpointJobService.get().failOrphanedJobs(runnerId);
            connectBridgeService.get().failOrphanedBridgeCommands(runnerId);
            purgeRunner(runnerId, workspaceId, runnerMap.get(FIELD_USER_NAME));
        }
    }

    private void purgeRunner(UUID runnerId, String workspaceId, String userName) {
        log.info("Purging dead runner '{}' from workspace '{}'", runnerId, workspaceId);

        RMap<String, String> runnerMap = redisClient.getMap(runnerKey(runnerId));
        String projectIdStr = runnerMap.get(FIELD_PROJECT_ID);
        String typeStr = runnerMap.get(FIELD_TYPE);
        RunnerType type = typeStr != null ? RunnerType.fromValue(typeStr) : RunnerType.ENDPOINT;

        redisClient.deleteKeys(
                runnerKey(runnerId),
                runnerAgentsKey(runnerId),
                runnerHeartbeatKey(runnerId),
                EndpointJobServiceImpl.runnerCancellationsKey(runnerId),
                EndpointJobServiceImpl.pendingJobsKey(runnerId),
                EndpointJobServiceImpl.activeJobsKey(runnerId),
                ConnectBridgeServiceImpl.bridgePendingKey(runnerId),
                ConnectBridgeServiceImpl.bridgeActiveKey(runnerId));

        removeRunnerFromWorkspace(workspaceId, userName, runnerId);

        if (projectIdStr != null) {
            UUID projectId = UUID.fromString(projectIdStr);
            RSet<String> projectRunners = redisClient.getSet(projectRunnersKey(workspaceId, projectId));
            projectRunners.remove(runnerId.toString());
            if (projectRunners.isEmpty()) {
                projectRunners.delete();
            }

            RBucket<String> userRunnerBucket = redisClient.getBucket(
                    projectUserRunnerKey(workspaceId, projectId, userName, type));
            String currentId = userRunnerBucket.get();
            if (runnerId.toString().equals(currentId)) {
                userRunnerBucket.delete();
            }
        }
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

    private List<String> parseCapabilities(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CAPABILITIES;
        }
        return List.of(value.split(","));
    }

    static JsonNode parseJsonNode(String value) {
        if (value == null) {
            return null;
        }
        return JsonUtils.getJsonNodeFromString(value);
    }

    static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        return Instant.parse(value);
    }

    static int parseIntValue(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static UUID parseUUID(String value) {
        if (value == null) {
            return null;
        }
        return UUID.fromString(value);
    }

    static Long parseLong(String value) {
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
