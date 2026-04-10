package com.comet.opik.domain.relay;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.relay.ActivateRequest;
import com.comet.opik.api.relay.CreateSessionRequest;
import com.comet.opik.api.relay.CreateSessionResponse;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.LocalRunnerService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.google.inject.ImplementedBy;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBatch;
import org.redisson.api.RMap;
import org.redisson.client.codec.StringCodec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relay pairing service: registers short-lived pairing sessions and verifies
 * activation HMACs to transition a runner row to CONNECTED.
 */
@ImplementedBy(RelayServiceImpl.class)
public interface RelayService {

    /** Create a new relay pairing session tied to a project and activation key. */
    CreateSessionResponse create(String workspaceId, String userName, CreateSessionRequest request);

    /** Verify the activation HMAC and activate the runner for the session. Returns the runner id. */
    UUID activate(String workspaceId, String userName, UUID sessionId, ActivateRequest request);
}

@Slf4j
@Singleton
class RelayServiceImpl implements RelayService {

    static final int ACTIVATION_KEY_BYTES = 32;
    static final int DEFAULT_TTL_SECONDS = 300;

    private static final String METER_NAMESPACE = "opik.relay";

    private static final String FIELD_WORKSPACE_ID = "workspace_id";
    private static final String FIELD_USER_NAME = "user_name";
    private static final String FIELD_PROJECT_ID = "project_id";
    private static final String FIELD_RUNNER_ID = "runner_id";
    private static final String FIELD_ACTIVATION_KEY = "activation_key";
    private static final String FIELD_TTL_SECONDS = "ttl_seconds";
    private static final String FIELD_CREATED_AT = "created_at";
    private static final String FIELD_ACTIVATED = "activated";
    private static final String FIELD_ACTIVATED_VALUE = "1";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ProjectService projectService;
    private final @NonNull LocalRunnerService localRunnerService;

    private final LongCounter sessionsCreated;
    private final LongCounter sessionsActivated;
    private final LongCounter activationHmacFailures;
    private final LongCounter activationConflicts;
    private final LongCounter activationNotFound;

    @Inject
    RelayServiceImpl(@NonNull StringRedisClient redisClient, @NonNull IdGenerator idGenerator,
            @NonNull ProjectService projectService, @NonNull LocalRunnerService localRunnerService) {
        this.redisClient = redisClient;
        this.idGenerator = idGenerator;
        this.projectService = projectService;
        this.localRunnerService = localRunnerService;

        // Instantiating the meter at construction time (rather than in a class-level
        // static initializer) keeps this service testable when the global OpenTelemetry
        // provider is reset between test runs.
        Meter meter = GlobalOpenTelemetry.get().getMeter(METER_NAMESPACE);
        this.sessionsCreated = meter.counterBuilder("relay.sessions.created")
                .setDescription("Number of relay pairing sessions created")
                .build();
        this.sessionsActivated = meter.counterBuilder("relay.sessions.activated")
                .setDescription("Number of relay pairing sessions activated")
                .build();
        this.activationHmacFailures = meter.counterBuilder("relay.sessions.activation_hmac_failures")
                .setDescription("Number of relay activations rejected due to invalid HMAC")
                .build();
        this.activationConflicts = meter.counterBuilder("relay.sessions.activation_conflicts")
                .setDescription("Number of relay activations rejected due to already-activated session")
                .build();
        this.activationNotFound = meter.counterBuilder("relay.sessions.activation_not_found")
                .setDescription("Number of relay activations rejected because the session was missing or expired")
                .build();
    }

    @Override
    public CreateSessionResponse create(@NonNull String workspaceId, @NonNull String userName,
            @NonNull CreateSessionRequest request) {

        byte[] activationKeyBytes;
        try {
            activationKeyBytes = Base64.getDecoder().decode(request.activationKey());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("activation_key must be valid base64");
        }
        if (activationKeyBytes.length != ACTIVATION_KEY_BYTES) {
            throw new BadRequestException("activation_key must decode to exactly " + ACTIVATION_KEY_BYTES + " bytes");
        }

        // Verify the project exists in the caller's workspace. Throws NotFoundException if missing.
        projectService.get(request.projectId(), workspaceId);

        UUID sessionId = idGenerator.generateId();
        UUID runnerId = idGenerator.generateId();
        int ttlSeconds = request.ttlSeconds() != null ? request.ttlSeconds() : DEFAULT_TTL_SECONDS;

        Map<String, String> sessionFields = Map.of(
                FIELD_WORKSPACE_ID, workspaceId,
                FIELD_USER_NAME, userName,
                FIELD_PROJECT_ID, request.projectId().toString(),
                FIELD_RUNNER_ID, runnerId.toString(),
                FIELD_ACTIVATION_KEY, request.activationKey(),
                FIELD_TTL_SECONDS, String.valueOf(ttlSeconds),
                FIELD_CREATED_AT, Instant.now().toString());

        // Write the hash and set its TTL in a single Redis round-trip. The spec
        // allows a best-effort write, but pipelining is cheap and keeps the session
        // from being briefly persisted without an expiry window.
        String sessionKey = RelaySessionKey.key(sessionId);
        RBatch batch = redisClient.createBatch();
        batch.<String, String>getMap(sessionKey, StringCodec.INSTANCE).putAllAsync(sessionFields);
        batch.<String, String>getMap(sessionKey, StringCodec.INSTANCE).expireAsync(Duration.ofSeconds(ttlSeconds));
        batch.execute();

        sessionsCreated.add(1);
        log.info("relay session created sessionId={} runnerId={} workspaceId={} userName={} ttlSeconds={}",
                sessionId, runnerId, workspaceId, userName, ttlSeconds);
        return CreateSessionResponse.builder()
                .sessionId(sessionId)
                .runnerId(runnerId)
                .build();
    }

    @Override
    public UUID activate(@NonNull String workspaceId, @NonNull String userName, @NonNull UUID sessionId,
            @NonNull ActivateRequest request) {

        RMap<String, String> sessionMap = redisClient.getMap(RelaySessionKey.key(sessionId));
        Map<String, String> fields = sessionMap.readAllMap();
        if (fields.isEmpty()) {
            activationNotFound.add(1);
            log.warn("relay session not found sessionId={} workspaceId={} userName={}",
                    sessionId, workspaceId, userName);
            throw new NotFoundException("Relay session not found: " + sessionId);
        }

        String sessionWorkspace = fields.get(FIELD_WORKSPACE_ID);
        if (!workspaceId.equals(sessionWorkspace)) {
            // Return 404 rather than 403 to avoid leaking session existence across workspaces.
            activationNotFound.add(1);
            log.warn("relay session workspace mismatch sessionId={} callerWorkspaceId={} userName={}",
                    sessionId, workspaceId, userName);
            throw new NotFoundException("Relay session not found: " + sessionId);
        }

        // The service is the only writer, and `create` validates the activation_key is
        // valid 32-byte base64 before storing. A decode failure here would indicate data
        // corruption or out-of-band writes; we treat it as a programming error.
        byte[] activationKey = Base64.getDecoder().decode(fields.get(FIELD_ACTIVATION_KEY));

        byte[] providedHmac;
        try {
            providedHmac = Base64.getDecoder().decode(request.hmac());
        } catch (IllegalArgumentException e) {
            activationHmacFailures.add(1);
            log.warn("relay activation hmac not valid base64 sessionId={} workspaceId={} userName={}",
                    sessionId, workspaceId, userName);
            throw new ForbiddenException("Invalid activation hmac");
        }

        byte[] expectedHmac = computeActivationHmac(sessionId, activationKey, request.runnerName());

        if (!MessageDigest.isEqual(expectedHmac, providedHmac)) {
            activationHmacFailures.add(1);
            log.warn("relay activation hmac mismatch sessionId={} workspaceId={} userName={}",
                    sessionId, workspaceId, userName);
            throw new ForbiddenException("Invalid activation hmac");
        }

        boolean won = sessionMap.fastPutIfAbsent(FIELD_ACTIVATED, FIELD_ACTIVATED_VALUE);
        if (!won) {
            activationConflicts.add(1);
            log.warn("relay session already activated sessionId={} workspaceId={} userName={}",
                    sessionId, workspaceId, userName);
            throw new ClientErrorException(
                    Response.status(Response.Status.CONFLICT)
                            .entity(new ErrorMessage(List.of("Session already activated: " + sessionId)))
                            .build());
        }

        UUID projectId = UUID.fromString(fields.get(FIELD_PROJECT_ID));
        UUID runnerId = UUID.fromString(fields.get(FIELD_RUNNER_ID));

        localRunnerService.activateFromRelay(workspaceId, userName, projectId, runnerId, request.runnerName());

        sessionsActivated.add(1);
        log.info("relay session activated sessionId={} runnerId={} workspaceId={} userName={}",
                sessionId, runnerId, workspaceId, userName);
        return runnerId;
    }

    /**
     * Compute the activation HMAC. Exposed package-private so the cross-language
     * HMAC test vectors can pin the byte layout without going through Redis.
     *
     * <p>Key: {@code activationKey} (32 raw bytes).
     * <p>Message: {@code sessionIdBytes(16) || SHA256(runnerNameBytes)(32)}.
     * <p>Algorithm: HMAC-SHA256.
     */
    static byte[] computeActivationHmac(UUID sessionId, byte[] activationKey, String runnerName) {
        byte[] sessionIdBytes = uuidToBytes(sessionId);
        byte[] runnerNameHash = sha256(runnerName.getBytes(StandardCharsets.UTF_8));
        byte[] message = new byte[sessionIdBytes.length + runnerNameHash.length];
        System.arraycopy(sessionIdBytes, 0, message, 0, sessionIdBytes.length);
        System.arraycopy(runnerNameHash, 0, message, sessionIdBytes.length, runnerNameHash.length);
        return hmacSha256(activationKey, message);
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
