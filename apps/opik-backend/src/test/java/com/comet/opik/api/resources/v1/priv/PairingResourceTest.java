package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.connect.ActivateRequest;
import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.connect.CreateSessionResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.LocalRunnersResourceClient;
import com.comet.opik.api.resources.utils.resources.PairingResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerStatus;
import com.comet.opik.api.runner.RunnerType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pairing Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class PairingResourceTest {

    private static final String API_KEY = randomUUID().toString();
    private static final String USER = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();

    private static final String OTHER_API_KEY = randomUUID().toString();
    private static final String OTHER_USER = randomUUID().toString();
    private static final String OTHER_WORKSPACE_ID = randomUUID().toString();
    private static final String OTHER_WORKSPACE = randomUUID().toString();

    private static final String SAME_USER_OTHER_API_KEY = randomUUID().toString();
    private static final String SAME_USER_OTHER_WORKSPACE_ID = randomUUID().toString();
    private static final String SAME_USER_OTHER_WORKSPACE = randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .build());
    }

    private PairingResourceClient pairingClient;
    private LocalRunnersResourceClient runnersClient;
    private ProjectResourceClient projectClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
        mockTargetWorkspace(OTHER_API_KEY, OTHER_WORKSPACE, OTHER_WORKSPACE_ID, OTHER_USER);
        mockTargetWorkspace(SAME_USER_OTHER_API_KEY, SAME_USER_OTHER_WORKSPACE, SAME_USER_OTHER_WORKSPACE_ID, USER);

        this.pairingClient = new PairingResourceClient(client, baseURI);
        this.runnersClient = new LocalRunnersResourceClient(client, baseURI);
        this.projectClient = new ProjectResourceClient(client, baseURI, PodamFactoryUtils.newPodamFactory());
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String user) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, user);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID createProject(String apiKey, String workspace) {
        return projectClient.createProject("pairing-test-project-" + randomUUID(), apiKey, workspace);
    }

    private static byte[] randomActivationKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    private static String computeHmac(UUID sessionId, byte[] activationKey, String runnerName) {
        try {
            byte[] sessionIdBytes = uuidToBytes(sessionId);
            byte[] runnerNameHash = MessageDigest.getInstance("SHA-256")
                    .digest(runnerName.getBytes(StandardCharsets.UTF_8));
            byte[] message = new byte[sessionIdBytes.length + runnerNameHash.length];
            System.arraycopy(sessionIdBytes, 0, message, 0, sessionIdBytes.length);
            System.arraycopy(runnerNameHash, 0, message, sessionIdBytes.length, runnerNameHash.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(activationKey, "HmacSHA256"));
            return base64(mac.doFinal(message));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private CreateSessionResponse createValidSession(UUID projectId, byte[] activationKey,
            String apiKey, String workspace) {
        CreateSessionRequest request = CreateSessionRequest.builder()
                .projectId(projectId)
                .activationKey(base64(activationKey))
                .ttlSeconds(300)
                .type(RunnerType.ENDPOINT)
                .build();
        return pairingClient.createSession(request, apiKey, workspace);
    }

    @Test
    @DisplayName("Happy path: create session, activate, runner is CONNECTED")
    void happyPath() {
        UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
        byte[] activationKey = randomActivationKey();

        CreateSessionResponse created = createValidSession(projectId, activationKey, API_KEY, TEST_WORKSPACE);
        assertThat(created.sessionId()).isNotNull();
        assertThat(created.runnerId()).isNotNull();

        String runnerName = "happy-runner";
        ActivateRequest activateRequest = ActivateRequest.builder()
                .runnerName(runnerName)
                .hmac(computeHmac(created.sessionId(), activationKey, runnerName))
                .build();
        UUID activatedRunnerId = pairingClient.activate(created.sessionId(), activateRequest, API_KEY, TEST_WORKSPACE);
        assertThat(activatedRunnerId).isEqualTo(created.runnerId());

        LocalRunner runner = runnersClient.getRunner(created.runnerId(), API_KEY, TEST_WORKSPACE);
        assertThat(runner.id()).isEqualTo(created.runnerId());
        assertThat(runner.name()).isEqualTo(runnerName);
        assertThat(runner.projectId()).isEqualTo(projectId);
        assertThat(runner.status()).isEqualTo(LocalRunnerStatus.CONNECTED);
    }

    private RedissonClient newRawRedissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(REDIS.getRedisURI());
        return Redisson.create(config);
    }

    @Nested
    @DisplayName("Create session")
    class CreateSession {

        @Test
        @DisplayName("activation_key that decodes to 31 bytes returns 400 (service-level length check)")
        void activationKeyDecodesToWrongLength() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            byte[] shortKey = new byte[31];
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(projectId)
                    .activationKey(base64(shortKey))
                    .ttlSeconds(300)
                    .type(RunnerType.ENDPOINT)
                    .build();

            try (Response response = pairingClient.callCreateSession(request, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(400);
            }
        }

        @Test
        @DisplayName("activation_key with wrong string length returns 422 (@Size check)")
        void activationKeyWrongStringLength() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(projectId)
                    .activationKey("A".repeat(40))
                    .ttlSeconds(300)
                    .type(RunnerType.ENDPOINT)
                    .build();

            try (Response response = pairingClient.callCreateSession(request, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(422);
            }
        }

        @Test
        @DisplayName("unpadded base64 activation_key (43 chars) is accepted")
        void activationKeyUnpaddedBase64Accepted() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            String unpadded = Base64.getEncoder().withoutPadding().encodeToString(randomActivationKey());
            assertThat(unpadded).hasSize(43);

            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(projectId)
                    .activationKey(unpadded)
                    .ttlSeconds(300)
                    .type(RunnerType.ENDPOINT)
                    .build();

            CreateSessionResponse response = pairingClient.createSession(request, API_KEY, TEST_WORKSPACE);
            assertThat(response.sessionId()).isNotNull();
            assertThat(response.runnerId()).isNotNull();
        }

        @Test
        @DisplayName("activation_key with invalid base64 (size ok) returns 400")
        void activationKeyInvalidBase64() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            String invalidB64 = "*".repeat(44);
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(projectId)
                    .activationKey(invalidB64)
                    .ttlSeconds(300)
                    .type(RunnerType.ENDPOINT)
                    .build();

            try (Response response = pairingClient.callCreateSession(request, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(400);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {59, 601})
        @DisplayName("ttl_seconds outside [60, 600] returns 422")
        void ttlSecondsOutOfRangeReturns422(int ttlSeconds) {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(projectId)
                    .activationKey(base64(randomActivationKey()))
                    .ttlSeconds(ttlSeconds)
                    .type(RunnerType.ENDPOINT)
                    .build();

            try (Response response = pairingClient.callCreateSession(request, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(422);
            }
        }

        @Test
        @DisplayName("non-existent project returns 404")
        void nonExistentProject() {
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(randomUUID())
                    .activationKey(base64(randomActivationKey()))
                    .ttlSeconds(300)
                    .type(RunnerType.ENDPOINT)
                    .build();

            try (Response response = pairingClient.callCreateSession(request, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        @DisplayName("project from another workspace returns 404")
        void projectFromAnotherWorkspace() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            CreateSessionRequest request = CreateSessionRequest.builder()
                    .projectId(projectId)
                    .activationKey(base64(randomActivationKey()))
                    .ttlSeconds(300)
                    .type(RunnerType.ENDPOINT)
                    .build();

            try (Response response = pairingClient.callCreateSession(request, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Activate")
    class Activate {

        @Test
        @DisplayName("invalid HMAC returns 403 and session remains activatable")
        void invalidHmacReturns403() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            byte[] activationKey = randomActivationKey();
            CreateSessionResponse created = createValidSession(projectId, activationKey, API_KEY, TEST_WORKSPACE);

            ActivateRequest bad = ActivateRequest.builder()
                    .runnerName("bad-runner")
                    .hmac(computeHmac(created.sessionId(), randomActivationKey(), "bad-runner"))
                    .build();

            try (Response response = pairingClient.callActivate(created.sessionId(), bad, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(403);
            }

            String runnerName = "good-runner";
            ActivateRequest good = ActivateRequest.builder()
                    .runnerName(runnerName)
                    .hmac(computeHmac(created.sessionId(), activationKey, runnerName))
                    .build();
            UUID runnerId = pairingClient.activate(created.sessionId(), good, API_KEY, TEST_WORKSPACE);
            assertThat(runnerId).isEqualTo(created.runnerId());
        }

        @Test
        @DisplayName("HMAC computed for a different runnerName returns 403")
        void hmacRunnerNameMismatchReturns403() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            byte[] activationKey = randomActivationKey();
            CreateSessionResponse created = createValidSession(projectId, activationKey, API_KEY, TEST_WORKSPACE);

            ActivateRequest req = ActivateRequest.builder()
                    .runnerName("runner-actual")
                    .hmac(computeHmac(created.sessionId(), activationKey, "runner-other"))
                    .build();

            try (Response response = pairingClient.callActivate(created.sessionId(), req, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(403);
            }
        }

        @Test
        @DisplayName("second activation returns 409")
        void secondActivationReturns409() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            byte[] activationKey = randomActivationKey();
            CreateSessionResponse created = createValidSession(projectId, activationKey, API_KEY, TEST_WORKSPACE);

            String runnerName = "once-runner";
            ActivateRequest req = ActivateRequest.builder()
                    .runnerName(runnerName)
                    .hmac(computeHmac(created.sessionId(), activationKey, runnerName))
                    .build();

            pairingClient.activate(created.sessionId(), req, API_KEY, TEST_WORKSPACE);

            try (Response response = pairingClient.callActivate(created.sessionId(), req, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(409);
            }
        }

        @Test
        @DisplayName("unknown session returns 404")
        void unknownSessionReturns404() {
            UUID sessionId = randomUUID();
            ActivateRequest req = ActivateRequest.builder()
                    .runnerName("ghost")
                    .hmac(computeHmac(sessionId, randomActivationKey(), "ghost"))
                    .build();

            try (Response response = pairingClient.callActivate(sessionId, req, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        @DisplayName("cross-workspace activation returns 404 (not 403) to avoid leaking existence")
        void crossWorkspaceActivationReturns404() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            byte[] activationKey = randomActivationKey();
            CreateSessionResponse created = createValidSession(projectId, activationKey, API_KEY, TEST_WORKSPACE);

            String runnerName = "runner";
            ActivateRequest req = ActivateRequest.builder()
                    .runnerName(runnerName)
                    .hmac(computeHmac(created.sessionId(), activationKey, runnerName))
                    .build();

            try (Response response = pairingClient.callActivate(created.sessionId(), req, OTHER_API_KEY,
                    OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }

            UUID runnerId = pairingClient.activate(created.sessionId(), req, API_KEY, TEST_WORKSPACE);
            assertThat(runnerId).isEqualTo(created.runnerId());
        }

        @Test
        @DisplayName("same userName in a different workspace cannot activate: proves workspace isolation is not user-only")
        void sameUserDifferentWorkspaceReturns404() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            byte[] activationKey = randomActivationKey();
            CreateSessionResponse created = createValidSession(projectId, activationKey, API_KEY, TEST_WORKSPACE);

            String runnerName = "shared-user-runner";
            ActivateRequest req = ActivateRequest.builder()
                    .runnerName(runnerName)
                    .hmac(computeHmac(created.sessionId(), activationKey, runnerName))
                    .build();

            try (Response response = pairingClient.callActivate(created.sessionId(), req, SAME_USER_OTHER_API_KEY,
                    SAME_USER_OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }

            UUID runnerId = pairingClient.activate(created.sessionId(), req, API_KEY, TEST_WORKSPACE);
            assertThat(runnerId).isEqualTo(created.runnerId());
        }

        @Test
        @DisplayName("expired session (simulated via direct Redis key delete) returns 404")
        void expiredSessionReturns404() {
            UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
            byte[] activationKey = randomActivationKey();
            CreateSessionResponse created = createValidSession(projectId, activationKey, API_KEY, TEST_WORKSPACE);

            RedissonClient raw = newRawRedissonClient();
            try {
                long deleted = raw.getKeys().delete("opik:pairing:" + created.sessionId());
                assertThat(deleted).isEqualTo(1L);
            } finally {
                raw.shutdown();
            }

            String runnerName = "expired-runner";
            ActivateRequest req = ActivateRequest.builder()
                    .runnerName(runnerName)
                    .hmac(computeHmac(created.sessionId(), activationKey, runnerName))
                    .build();
            try (Response response = pairingClient.callActivate(created.sessionId(), req, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }
}
