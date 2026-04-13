package com.comet.opik.domain.connect;

import com.comet.opik.api.connect.ActivateRequest;
import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.connect.CreateSessionResponse;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.LocalRunnerService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBatch;
import org.redisson.api.RMap;
import org.redisson.api.RMapAsync;

import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("OpikConnectServiceImpl")
@ExtendWith(MockitoExtension.class)
class OpikConnectServiceImplTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String USER_NAME = "alice";

    @Nested
    @DisplayName("HMAC test vectors — cross-language contract")
    class HmacVectors {

        @Test
        @DisplayName("vector 1: trivial inputs — byte-layout anchor")
        void hmacTestVector1_trivialInputs() {
            UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            byte[] activationKey = HEX.parseHex(
                    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
            String runnerName = "test-runner";

            byte[] actual = OpikConnectServiceImpl.computeActivationHmac(sessionId, activationKey, runnerName);

            assertThat(HEX.formatHex(actual))
                    .isEqualTo("127a99c794575e17cfe04628f23eef16155fdb2c5534ac54c7c780d611d86645");
        }

        @Test
        @DisplayName("vector 2: realistic inputs — regression anchor")
        void hmacTestVector2_realisticInputs() {
            UUID sessionId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            byte[] activationKey = HEX.parseHex(
                    "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1");
            String runnerName = "devbox-01";

            byte[] actual = OpikConnectServiceImpl.computeActivationHmac(sessionId, activationKey, runnerName);

            assertThat(HEX.formatHex(actual))
                    .isEqualTo("081d9cb7963ef516ae705fe8658464901a445de154ae0f8ff7284253296585f0");
        }

        @Test
        @DisplayName("HMAC binds sessionId: same key+runnerName with different sessionId produces different tag")
        void hmacBindsSessionId() {
            UUID sessionIdA = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID sessionIdB = UUID.fromString("00000000-0000-0000-0000-000000000002");
            byte[] activationKey = HEX.parseHex(
                    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
            String runnerName = "test-runner";

            byte[] tagA = OpikConnectServiceImpl.computeActivationHmac(sessionIdA, activationKey, runnerName);
            byte[] tagB = OpikConnectServiceImpl.computeActivationHmac(sessionIdB, activationKey, runnerName);

            assertThat(tagA).isNotEqualTo(tagB);
        }

        @Test
        @DisplayName("HMAC binds runnerName: same key+sessionId with different runnerName produces different tag")
        void hmacBindsRunnerName() {
            UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            byte[] activationKey = HEX.parseHex(
                    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

            byte[] tagX = OpikConnectServiceImpl.computeActivationHmac(sessionId, activationKey, "runner-x");
            byte[] tagY = OpikConnectServiceImpl.computeActivationHmac(sessionId, activationKey, "runner-y");

            assertThat(tagX).isNotEqualTo(tagY);
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Mock
        private StringRedisClient redisClient;
        @Mock
        private IdGenerator idGenerator;
        @Mock
        private ProjectService projectService;
        @Mock
        private LocalRunnerService localRunnerService;
        @Mock
        private RBatch batch;
        @Mock(name = "batchMap")
        @SuppressWarnings("rawtypes")
        private RMapAsync batchMap;

        private OpikConnectServiceImpl service;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            service = new OpikConnectServiceImpl(redisClient, idGenerator, projectService, localRunnerService);
            lenient().when(redisClient.createBatch()).thenReturn(batch);
            lenient().when(batch.<String, String>getMap(anyString(), any())).thenReturn(batchMap);
            lenient().when(batchMap.putAllAsync(any(Map.class))).thenReturn(null);
            lenient().when(batchMap.expireAsync(any(Duration.class))).thenReturn(null);
        }

        @Test
        @DisplayName("generates fresh session and runner ids on each call")
        void generatesFreshIds() {
            UUID sessionIdA = UUID.randomUUID();
            UUID runnerIdA = UUID.randomUUID();
            UUID sessionIdB = UUID.randomUUID();
            UUID runnerIdB = UUID.randomUUID();
            when(idGenerator.generateId()).thenReturn(sessionIdA, runnerIdA, sessionIdB, runnerIdB);

            CreateSessionRequest request = validRequest();
            CreateSessionResponse a = service.create(WORKSPACE_ID, USER_NAME, request);
            CreateSessionResponse b = service.create(WORKSPACE_ID, USER_NAME, request);

            assertThat(a.sessionId()).isEqualTo(sessionIdA);
            assertThat(a.runnerId()).isEqualTo(runnerIdA);
            assertThat(b.sessionId()).isEqualTo(sessionIdB);
            assertThat(b.runnerId()).isEqualTo(runnerIdB);
            assertThat(a.sessionId()).isNotEqualTo(b.sessionId());
            assertThat(a.runnerId()).isNotEqualTo(b.runnerId());
        }

        @Test
        @DisplayName("rejects activation_key that does not decode to exactly 32 bytes")
        void rejectsWrongActivationKeyLength() {
            // 31 bytes base64-encoded lands on the same 44-char length as 32 bytes.
            String shortKeyBase64 = Base64.getEncoder().encodeToString(new byte[31]);
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(UUID.randomUUID())
                    .activationKey(shortKeyBase64)
                    .ttlSeconds(300)
                    .build();

            assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_NAME, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("32 bytes");

            verifyNoInteractions(projectService, idGenerator);
        }

        @Test
        @DisplayName("rejects malformed base64 activation_key")
        void rejectsInvalidBase64() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(UUID.randomUUID())
                    .activationKey("*".repeat(44))
                    .ttlSeconds(300)
                    .build();

            assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_NAME, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("base64");

            verifyNoInteractions(projectService, idGenerator);
        }

        @Test
        @DisplayName("propagates project-not-found from ProjectService")
        void propagatesProjectNotFound() {
            UUID projectId = UUID.randomUUID();
            CreateSessionRequest request = validRequest(projectId);
            when(projectService.get(projectId, WORKSPACE_ID)).thenThrow(new NotFoundException("no such project"));

            assertThatThrownBy(() -> service.create(WORKSPACE_ID, USER_NAME, request))
                    .isInstanceOf(NotFoundException.class);

            verifyNoInteractions(idGenerator);
        }

        @Test
        @DisplayName("stores all expected fields and sets the TTL in a single batch")
        @SuppressWarnings("unchecked")
        void storesAllFieldsAndTtl() {
            UUID sessionId = UUID.randomUUID();
            UUID runnerId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            when(idGenerator.generateId()).thenReturn(sessionId, runnerId);

            String activationKey = Base64.getEncoder().encodeToString(new byte[32]);
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(projectId)
                    .activationKey(activationKey)
                    .ttlSeconds(120)
                    .build();

            service.create(WORKSPACE_ID, USER_NAME, request);

            ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(batchMap).putAllAsync(fieldsCaptor.capture());
            verify(batchMap).expireAsync(Duration.ofSeconds(120));
            verify(batch).execute();

            Map<String, String> stored = fieldsCaptor.getValue();
            assertThat(stored).containsEntry("workspace_id", WORKSPACE_ID);
            assertThat(stored).containsEntry("user_name", USER_NAME);
            assertThat(stored).containsEntry("project_id", projectId.toString());
            assertThat(stored).containsEntry("runner_id", runnerId.toString());
            assertThat(stored).containsEntry("activation_key", activationKey);
            assertThat(stored).containsEntry("ttl_seconds", "120");
            assertThat(stored).containsKey("created_at");
        }

        @Test
        @DisplayName("defaults TTL to 300 seconds when ttl_seconds is null")
        @SuppressWarnings("unchecked")
        void defaultsTtlWhenNull() {
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID(), UUID.randomUUID());

            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(UUID.randomUUID())
                    .activationKey(Base64.getEncoder().encodeToString(new byte[32]))
                    .ttlSeconds(null)
                    .build();

            service.create(WORKSPACE_ID, USER_NAME, request);

            verify(batchMap).expireAsync(Duration.ofSeconds(OpikConnectServiceImpl.DEFAULT_TTL_SECONDS));
        }

        private CreateSessionRequest validRequest() {
            return validRequest(UUID.randomUUID());
        }

        private CreateSessionRequest validRequest(UUID projectId) {
            return CreateSessionRequest.builder()
                    .projectId(projectId)
                    .activationKey(Base64.getEncoder().encodeToString(new byte[32]))
                    .ttlSeconds(300)
                    .build();
        }
    }

    @Nested
    @DisplayName("activate")
    class Activate {

        @Mock
        private StringRedisClient redisClient;
        @Mock
        private IdGenerator idGenerator;
        @Mock
        private ProjectService projectService;
        @Mock
        private LocalRunnerService localRunnerService;
        @Mock
        @SuppressWarnings("rawtypes")
        private RMap sessionMap;

        private OpikConnectServiceImpl service;

        @BeforeEach
        void setUp() {
            service = new OpikConnectServiceImpl(redisClient, idGenerator, projectService, localRunnerService);
        }

        @Test
        @DisplayName("unknown session returns 404")
        @SuppressWarnings("unchecked")
        void unknownSessionReturns404() {
            UUID sessionId = UUID.randomUUID();
            when(redisClient.getMap(anyString())).thenReturn(sessionMap);
            when(sessionMap.readAllMap()).thenReturn(Map.of());

            assertThatThrownBy(() -> service.activate(WORKSPACE_ID, USER_NAME, sessionId, activateRequest("r", "")))
                    .isInstanceOf(NotFoundException.class);

            verifyNoInteractions(localRunnerService);
        }

        @Test
        @DisplayName("cross-workspace session returns 404 (not 403)")
        @SuppressWarnings("unchecked")
        void crossWorkspaceReturns404() {
            UUID sessionId = UUID.randomUUID();
            when(redisClient.getMap(anyString())).thenReturn(sessionMap);
            when(sessionMap.readAllMap()).thenReturn(storedFields("other-workspace", "bob",
                    UUID.randomUUID(), UUID.randomUUID(), Base64.getEncoder().encodeToString(new byte[32])));

            assertThatThrownBy(() -> service.activate(WORKSPACE_ID, USER_NAME, sessionId, activateRequest("r", "")))
                    .isInstanceOf(NotFoundException.class);

            verifyNoInteractions(localRunnerService);
        }

        @Test
        @DisplayName("invalid HMAC returns 403")
        @SuppressWarnings("unchecked")
        void invalidHmacReturns403() {
            UUID sessionId = UUID.randomUUID();
            byte[] activationKey = new byte[32];
            when(redisClient.getMap(anyString())).thenReturn(sessionMap);
            when(sessionMap.readAllMap()).thenReturn(storedFields(WORKSPACE_ID, USER_NAME,
                    UUID.randomUUID(), UUID.randomUUID(), Base64.getEncoder().encodeToString(activationKey)));

            // HMAC computed with a different key
            byte[] wrongKey = new byte[32];
            wrongKey[0] = 1;
            byte[] wrongTag = OpikConnectServiceImpl.computeActivationHmac(sessionId, wrongKey, "my-runner");
            ActivateRequest request = activateRequest("my-runner", Base64.getEncoder().encodeToString(wrongTag));

            assertThatThrownBy(() -> service.activate(WORKSPACE_ID, USER_NAME, sessionId, request))
                    .isInstanceOf(ForbiddenException.class);

            verify(sessionMap, never()).fastPutIfAbsent(anyString(), anyString());
            verifyNoInteractions(localRunnerService);
        }

        @Test
        @DisplayName("HMAC computed for different runnerName returns 403")
        @SuppressWarnings("unchecked")
        void hmacRunnerNameMismatchReturns403() {
            UUID sessionId = UUID.randomUUID();
            byte[] activationKey = new byte[32];
            when(redisClient.getMap(anyString())).thenReturn(sessionMap);
            when(sessionMap.readAllMap()).thenReturn(storedFields(WORKSPACE_ID, USER_NAME,
                    UUID.randomUUID(), UUID.randomUUID(), Base64.getEncoder().encodeToString(activationKey)));

            byte[] tagForOther = OpikConnectServiceImpl.computeActivationHmac(sessionId, activationKey, "other-runner");
            ActivateRequest request = activateRequest("actual-runner",
                    Base64.getEncoder().encodeToString(tagForOther));

            assertThatThrownBy(() -> service.activate(WORKSPACE_ID, USER_NAME, sessionId, request))
                    .isInstanceOf(ForbiddenException.class);

            verifyNoInteractions(localRunnerService);
        }

        @Test
        @DisplayName("activation race: fastPutIfAbsent false returns 409 (after the idempotent runner activation runs)")
        @SuppressWarnings("unchecked")
        void raceLostReturns409() {
            UUID sessionId = UUID.randomUUID();
            byte[] activationKey = new byte[32];
            UUID projectId = UUID.randomUUID();
            UUID runnerId = UUID.randomUUID();
            when(redisClient.getMap(anyString())).thenReturn(sessionMap);
            when(sessionMap.readAllMap()).thenReturn(storedFields(WORKSPACE_ID, USER_NAME,
                    projectId, runnerId, Base64.getEncoder().encodeToString(activationKey)));
            when(sessionMap.fastPutIfAbsent("activated", "1")).thenReturn(false);

            byte[] tag = OpikConnectServiceImpl.computeActivationHmac(sessionId, activationKey, "runner-1");
            ActivateRequest request = activateRequest("runner-1", Base64.getEncoder().encodeToString(tag));

            assertThatThrownBy(() -> service.activate(WORKSPACE_ID, USER_NAME, sessionId, request))
                    .isInstanceOfSatisfying(ClientErrorException.class,
                            ex -> assertThat(ex.getResponse().getStatus()).isEqualTo(Response.Status.CONFLICT
                                    .getStatusCode()));

            // Runner activation IS called before the CAS check — the helpers are
            // idempotent so a duplicate call from the loser of the race is safe.
            verify(localRunnerService).activateFromOpikConnect(
                    eq(WORKSPACE_ID), eq(USER_NAME), eq(projectId), eq(runnerId), eq("runner-1"));
        }

        @Test
        @DisplayName("if activateFromOpikConnect throws, the session activated flag is NOT set so retries can succeed")
        @SuppressWarnings("unchecked")
        void runnerActivationFailureLeavesSessionRetryable() {
            UUID sessionId = UUID.randomUUID();
            byte[] activationKey = new byte[32];
            UUID projectId = UUID.randomUUID();
            UUID runnerId = UUID.randomUUID();
            when(redisClient.getMap(anyString())).thenReturn(sessionMap);
            when(sessionMap.readAllMap()).thenReturn(storedFields(WORKSPACE_ID, USER_NAME,
                    projectId, runnerId, Base64.getEncoder().encodeToString(activationKey)));

            // Simulate a Redis blip / internal error inside the runner activation helpers.
            doThrow(new RuntimeException("simulated redis blip"))
                    .when(localRunnerService)
                    .activateFromOpikConnect(anyString(), anyString(), any(UUID.class), any(UUID.class), anyString());

            String runnerName = "stuck-runner";
            byte[] tag = OpikConnectServiceImpl.computeActivationHmac(sessionId, activationKey, runnerName);
            ActivateRequest request = activateRequest(runnerName, Base64.getEncoder().encodeToString(tag));

            assertThatThrownBy(() -> service.activate(WORKSPACE_ID, USER_NAME, sessionId, request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("simulated redis blip");

            // Critical: the activated flag must NOT have been set, so the next retry can succeed.
            verify(sessionMap, never()).fastPutIfAbsent(anyString(), anyString());
        }

        @Test
        @DisplayName("happy path: calls activateFromOpikConnect with the correct arguments")
        @SuppressWarnings("unchecked")
        void happyPathCallsActivateFromRelay() {
            UUID sessionId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            UUID runnerId = UUID.randomUUID();
            byte[] activationKey = new byte[32];
            when(redisClient.getMap(anyString())).thenReturn(sessionMap);
            when(sessionMap.readAllMap()).thenReturn(storedFields(WORKSPACE_ID, USER_NAME,
                    projectId, runnerId, Base64.getEncoder().encodeToString(activationKey)));
            when(sessionMap.fastPutIfAbsent("activated", "1")).thenReturn(true);

            String runnerName = "my-laptop";
            byte[] tag = OpikConnectServiceImpl.computeActivationHmac(sessionId, activationKey, runnerName);
            ActivateRequest request = activateRequest(runnerName, Base64.getEncoder().encodeToString(tag));

            UUID returnedRunnerId = service.activate(WORKSPACE_ID, USER_NAME, sessionId, request);

            assertThat(returnedRunnerId).isEqualTo(runnerId);
            verify(localRunnerService).activateFromOpikConnect(
                    eq(WORKSPACE_ID), eq(USER_NAME), eq(projectId), eq(runnerId), eq(runnerName));
        }

        private ActivateRequest activateRequest(String runnerName, String hmac) {
            return ActivateRequest.builder()
                    .runnerName(runnerName)
                    .hmac(hmac)
                    .build();
        }

        private Map<String, String> storedFields(String workspaceId, String userName, UUID projectId,
                UUID runnerId, String activationKeyBase64) {
            return Map.of(
                    "workspace_id", workspaceId,
                    "user_name", userName,
                    "project_id", projectId.toString(),
                    "runner_id", runnerId.toString(),
                    "activation_key", activationKeyBase64,
                    "ttl_seconds", "300",
                    "created_at", "2026-01-01T00:00:00Z");
        }
    }
}
