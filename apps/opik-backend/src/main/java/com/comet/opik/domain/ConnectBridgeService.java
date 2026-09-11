package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.BridgeCommand;
import com.comet.opik.api.runner.BridgeCommandBatchResponse;
import com.comet.opik.api.runner.BridgeCommandResultRequest;
import com.comet.opik.api.runner.BridgeCommandStatus;
import com.comet.opik.api.runner.BridgeCommandSubmitRequest;
import com.comet.opik.api.runner.BridgeCommandType;
import com.comet.opik.infrastructure.LocalRunnerConfig;
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
import org.redisson.api.BatchResult;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBatch;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.queue.DequeMoveArgs;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ImplementedBy(ConnectBridgeServiceImpl.class)
public interface ConnectBridgeService {

    UUID createBridgeCommand(UUID runnerId, String workspaceId, String userName, BridgeCommandSubmitRequest request);

    Mono<BridgeCommandBatchResponse> nextBridgeCommands(UUID runnerId, String workspaceId, String userName,
            int maxCommands);

    void reportBridgeCommandResult(UUID runnerId, String workspaceId, String userName, UUID commandId,
            BridgeCommandResultRequest request);

    BridgeCommand getBridgeCommand(UUID runnerId, String workspaceId, String userName, UUID commandId);

    Mono<BridgeCommand> awaitBridgeCommand(UUID runnerId, String workspaceId, String userName, UUID commandId,
            int timeoutSeconds);

    void failOrphanedBridgeCommands(UUID runnerId);

    void reapStaleBridgeCommands(UUID runnerId);
}

@Slf4j
@Singleton
class ConnectBridgeServiceImpl implements ConnectBridgeService {

    private static final Set<BridgeCommandStatus> REPORTABLE_BRIDGE_STATUSES = Set.of(
            BridgeCommandStatus.COMPLETED, BridgeCommandStatus.FAILED);

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
    private static final String BRIDGE_FIELD_COMPLETED_FLAG = "completed_flag";
    private static final String BRIDGE_DONE_SENTINEL = "done";

    static String bridgeCommandKey(UUID commandId) {
        return "opik:runners:bridge:command:" + commandId;
    }

    static String bridgeCommandDoneKey(UUID commandId) {
        return "opik:runners:bridge:command:" + commandId + ":done";
    }

    static String bridgePendingKey(UUID runnerId) {
        return "opik:runners:bridge:" + runnerId + ":pending";
    }

    static String bridgeActiveKey(UUID runnerId) {
        return "opik:runners:bridge:" + runnerId + ":active";
    }

    private static String bridgeRateKey(UUID runnerId, long minute) {
        return "opik:runners:bridge:" + runnerId + ":rate:" + minute;
    }

    private static String bridgeWriteRateKey(UUID runnerId, long minute) {
        return "opik:runners:bridge:" + runnerId + ":write_rate:" + minute;
    }

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull RunnerService runnerService;
    private final @NonNull LocalRunnerConfig runnerConfig;

    @Inject
    ConnectBridgeServiceImpl(@NonNull StringRedisClient redisClient, @NonNull IdGenerator idGenerator,
            @NonNull RunnerService runnerService, @NonNull LocalRunnerConfig runnerConfig) {
        this.redisClient = redisClient;
        this.idGenerator = idGenerator;
        this.runnerService = runnerService;
        this.runnerConfig = runnerConfig;
    }

    @Override
    public UUID createBridgeCommand(@NonNull UUID runnerId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull BridgeCommandSubmitRequest request) {
        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);

        if (!runnerService.isRunnerAlive(runnerId)) {
            throw new NotFoundException(Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorMessage(List.of("Runner is not connected")))
                    .build());
        }

        if (!runnerService.hasCapability(runnerId, "bridge")) {
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
        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);

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

        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);

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
        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);

        Map<String, String> fields = loadValidatedBridgeCommand(runnerId, workspaceId, commandId);

        return buildBridgeCommand(fields);
    }

    @Override
    public Mono<BridgeCommand> awaitBridgeCommand(@NonNull UUID runnerId, @NonNull String workspaceId,
            @NonNull String userName, @NonNull UUID commandId, int timeoutSeconds) {
        int effectiveTimeout = Math.min(Math.max(timeoutSeconds, 1),
                (int) runnerConfig.getBridgeMaxCommandTimeout().toSeconds());
        runnerService.validateRunnerOwnership(runnerId, workspaceId, userName);

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

    @Override
    public void failOrphanedBridgeCommands(@NonNull UUID runnerId) {
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

    @Override
    public void reapStaleBridgeCommands(@NonNull UUID runnerId) {
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
            int timeoutSecs = RunnerServiceImpl.parseIntValue(timeoutStr);
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

    // --- Private methods ---

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
                .args(RunnerServiceImpl.parseJsonNode(fields.get(BRIDGE_FIELD_ARGS)))
                .timeoutSeconds(RunnerServiceImpl.parseIntValue(fields.get(BRIDGE_FIELD_TIMEOUT_SECONDS)))
                .submittedAt(RunnerServiceImpl.parseInstant(fields.get(BRIDGE_FIELD_SUBMITTED_AT)))
                .build();
    }

    private BridgeCommand buildBridgeCommand(Map<String, String> fields) {
        return BridgeCommand.builder()
                .commandId(UUID.fromString(fields.get(BRIDGE_FIELD_COMMAND_ID)))
                .runnerId(UUID.fromString(fields.get(BRIDGE_FIELD_RUNNER_ID)))
                .type(parseBridgeCommandType(fields.get(BRIDGE_FIELD_TYPE)))
                .status(parseBridgeCommandStatus(fields.get(BRIDGE_FIELD_STATUS)))
                .args(RunnerServiceImpl.parseJsonNode(fields.get(BRIDGE_FIELD_ARGS)))
                .result(RunnerServiceImpl.parseJsonNode(fields.get(BRIDGE_FIELD_RESULT)))
                .error(RunnerServiceImpl.parseJsonNode(fields.get(BRIDGE_FIELD_ERROR)))
                .timeoutSeconds(RunnerServiceImpl.parseIntValue(fields.get(BRIDGE_FIELD_TIMEOUT_SECONDS)))
                .submittedAt(RunnerServiceImpl.parseInstant(fields.get(BRIDGE_FIELD_SUBMITTED_AT)))
                .pickedUpAt(RunnerServiceImpl.parseInstant(fields.get(BRIDGE_FIELD_PICKED_UP_AT)))
                .completedAt(RunnerServiceImpl.parseInstant(fields.get(BRIDGE_FIELD_COMPLETED_AT)))
                .durationMs(RunnerServiceImpl.parseLong(fields.get(BRIDGE_FIELD_DURATION_MS)))
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
}
