package com.comet.opik.domain.pairing;

import com.comet.opik.api.connect.ActivateRequest;
import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.connect.CreateSessionResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.RunnerType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.RunnerService;
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

@ImplementedBy(PairingServiceImpl.class)
public interface PairingService {

    CreateSessionResponse create(String workspaceId, String userName, CreateSessionRequest request);

    UUID activate(String workspaceId, String userName, UUID sessionId, ActivateRequest request);
}

@Slf4j
@Singleton
class PairingServiceImpl implements PairingService {

    static final int ACTIVATION_KEY_BYTES = 32;
    static final int DEFAULT_TTL_SECONDS = 300;

    private static final String METER_NAMESPACE = "opik.pairing";

    private static final String FIELD_WORKSPACE_ID = "workspace_id";
    private static final String FIELD_USER_NAME = "user_name";
    private static final String FIELD_PROJECT_ID = "project_id";
    private static final String FIELD_RUNNER_ID = "runner_id";
    private static final String FIELD_ACTIVATION_KEY = "activation_key";
    private static final String FIELD_TTL_SECONDS = "ttl_seconds";
    private static final String FIELD_CREATED_AT = "created_at";
    private static final String FIELD_ACTIVATED = "activated";
    private static final String FIELD_ACTIVATED_VALUE = "1";
    private static final String FIELD_TYPE = "type";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ProjectService projectService;
    private final @NonNull RunnerService runnerService;

    // Security signal: spike == someone fishing for sessions or replaying activation
    // requests. This is the only counter that isn't trivially derivable from the
    // @Timed annotation on PairingResource — page alerts hang off this.
    private final LongCounter activationHmacFailures;

    @Inject
    PairingServiceImpl(@NonNull StringRedisClient redisClient, @NonNull IdGenerator idGenerator,
            @NonNull ProjectService projectService, @NonNull RunnerService runnerService) {
        this.redisClient = redisClient;
        this.idGenerator = idGenerator;
        this.projectService = projectService;
        this.runnerService = runnerService;

        // Instantiated at construction time (rather than in a class-level static
        // initializer) so the service stays testable when GlobalOpenTelemetry is
        // reset between test runs.
        Meter meter = GlobalOpenTelemetry.get().getMeter(METER_NAMESPACE);
        this.activationHmacFailures = meter.counterBuilder("opik.pairing.sessions.activation_hmac_failures")
                .setDescription("Number of pairing activations rejected due to invalid HMAC")
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
                FIELD_CREATED_AT, Instant.now().toString(),
                FIELD_TYPE, request.type().getValue());

        // Write the hash and set its TTL in a single Redis round-trip. The spec
        // allows a best-effort write, but pipelining is cheap and keeps the session
        // from being briefly persisted without an expiry window.
        String sessionKey = PairingSessionKey.key(sessionId);
        RBatch batch = redisClient.createBatch();
        batch.<String, String>getMap(sessionKey, StringCodec.INSTANCE).putAllAsync(sessionFields);
        batch.<String, String>getMap(sessionKey, StringCodec.INSTANCE).expireAsync(Duration.ofSeconds(ttlSeconds));
        batch.execute();

        log.info(
                "pairing session created sessionId='{}' runnerId='{}' workspaceId='{}' userName='{}' ttlSeconds='{}' type='{}'",
                sessionId, runnerId, workspaceId, userName, ttlSeconds, request.type().getValue());
        return CreateSessionResponse.builder()
                .sessionId(sessionId)
                .runnerId(runnerId)
                .build();
    }

    @Override
    public UUID activate(@NonNull String workspaceId, @NonNull String userName, @NonNull UUID sessionId,
            @NonNull ActivateRequest request) {

        RMap<String, String> sessionMap = redisClient.getMap(PairingSessionKey.key(sessionId));
        Map<String, String> fields = sessionMap.readAllMap();
        if (fields.isEmpty()) {
            log.warn("pairing session not found sessionId='{}' workspaceId='{}' userName='{}'",
                    sessionId, workspaceId, userName);
            throw new NotFoundException("Pairing session not found: " + sessionId);
        }

        String sessionWorkspace = fields.get(FIELD_WORKSPACE_ID);
        if (!workspaceId.equals(sessionWorkspace)) {
            // Return 404 rather than 403 to avoid leaking session existence across workspaces.
            log.warn("pairing session workspace mismatch sessionId='{}' callerWorkspaceId='{}' userName='{}'",
                    sessionId, workspaceId, userName);
            throw new NotFoundException("Pairing session not found: " + sessionId);
        }

        // Defense in depth: create() always writes all fields atomically via RBatch, so
        // a missing field would indicate data corruption or out-of-band writes. Surface
        // those as a clean 404 rather than letting Base64.decode(null) throw a 500.
        String storedActivationKey = fields.get(FIELD_ACTIVATION_KEY);
        String storedProjectId = fields.get(FIELD_PROJECT_ID);
        String storedRunnerId = fields.get(FIELD_RUNNER_ID);
        String storedType = fields.get(FIELD_TYPE);
        if (storedActivationKey == null || storedProjectId == null || storedRunnerId == null || storedType == null) {
            log.error("pairing session has missing fields sessionId='{}' workspaceId='{}' fields='{}'",
                    sessionId, workspaceId, fields.keySet());
            throw new NotFoundException("Pairing session not found: " + sessionId);
        }
        byte[] activationKey = Base64.getDecoder().decode(storedActivationKey);

        byte[] providedHmac;
        try {
            providedHmac = Base64.getDecoder().decode(request.hmac());
        } catch (IllegalArgumentException e) {
            activationHmacFailures.add(1);
            log.warn("pairing activation hmac not valid base64 sessionId='{}' workspaceId='{}' userName='{}'",
                    sessionId, workspaceId, userName);
            throw new ForbiddenException("Invalid activation hmac");
        }

        byte[] expectedHmac = computeActivationHmac(sessionId, activationKey, request.runnerName());

        if (!MessageDigest.isEqual(expectedHmac, providedHmac)) {
            activationHmacFailures.add(1);
            log.warn("pairing activation hmac mismatch sessionId='{}' workspaceId='{}' userName='{}'",
                    sessionId, workspaceId, userName);
            throw new ForbiddenException("Invalid activation hmac");
        }

        UUID projectId = UUID.fromString(storedProjectId);
        UUID runnerId = UUID.fromString(storedRunnerId);
        RunnerType runnerType;
        try {
            runnerType = RunnerType.fromValue(storedType);
        } catch (IllegalArgumentException e) {
            log.error("pairing session has invalid type sessionId='{}' workspaceId='{}' type='{}'",
                    sessionId, workspaceId, storedType);
            throw new NotFoundException("Pairing session not found: " + sessionId);
        }

        // Activate the runner BEFORE flipping the session's activated flag. If
        // activateFromPairing fails (Redis hiccup, internal error in one of the
        // helpers, etc.), the session must remain re-activatable so the user can
        // retry the same pairing link rather than waiting out the TTL.
        //
        // The runner-row writes inside activateFromPairing are idempotent
        // (evictExistingRunner short-circuits when the user→runner bucket already
        // points at this runnerId, set adds dedupe, bucket writes are last-write-wins),
        // so a duplicate call from a parallel activator is harmless. The CAS below
        // is what determines who returns 201 vs 409.
        runnerService.activateFromPairing(workspaceId, userName, projectId, runnerId, request.runnerName(), runnerType);

        boolean won = sessionMap.fastPutIfAbsent(FIELD_ACTIVATED, FIELD_ACTIVATED_VALUE);
        if (!won) {
            log.warn("pairing session already activated sessionId='{}' workspaceId='{}' userName='{}'",
                    sessionId, workspaceId, userName);
            throw new ClientErrorException(
                    Response.status(Response.Status.CONFLICT)
                            .entity(new ErrorMessage(List.of("Session already activated: " + sessionId)))
                            .build());
        }

        log.info("pairing session activated sessionId='{}' runnerId='{}' workspaceId='{}' userName='{}'",
                sessionId, runnerId, workspaceId, userName);
        return runnerId;
    }

    // HMAC-SHA256(activationKey, sessionIdBytes(16) || SHA256(runnerNameBytes)(32)).
    // Package-private so the cross-language HMAC test vectors in PairingServiceImplTest
    // can pin the byte layout without going through Redis.
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
