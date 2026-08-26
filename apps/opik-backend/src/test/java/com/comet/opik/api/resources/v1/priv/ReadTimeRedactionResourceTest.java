package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.connect.ActivateRequest;
import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.LocalRunnersResourceClient;
import com.comet.opik.api.resources.utils.resources.PairingResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.RunnerType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Read Time Redaction Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ReadTimeRedactionResourceTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.randomAlphabetic(10);

    private static final String ADMIN_API_KEY = UUID.randomUUID().toString();
    private static final String MEMBER_API_KEY = UUID.randomUUID().toString();

    private static final String EMAIL = "john.doe@example.com";
    private static final String PHONE = "555-123-4567";
    private static final String RULES_JSON = """
            [{"regex":"(?<![\\\\w.+-])[\\\\w.+-]+@[\\\\w-]+\\\\.[\\\\w.]+","replace":"[EMAIL]"},\
            {"regex":"\\\\b\\\\d{3}-\\\\d{3}-\\\\d{4}\\\\b","replace":"[PHONE]"}]""";

    private static final String STORED_INPUT = """
            {"prompt":"Refund for %s, callback %s"}""".formatted(EMAIL, PHONE);

    /**
     * Deliberately a value the PHONE rule matches. Nothing but the structural exemption keeps it intact, so if
     * the exemption is missing on a path the assertion fails rather than passing by luck.
     */
    private static final String RULE_MATCHING_THREAD_ID = PHONE;

    private static final String RUNNER_AGENT = "redaction-agent";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                new CustomConfig("redaction.enabled", "true"),
                                new CustomConfig("redaction.rules", RULES_JSON)))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private TraceResourceClient traceResourceClient;
    private LocalRunnersResourceClient runnersClient;
    private PairingResourceClient pairingClient;
    private ProjectResourceClient projectClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.runnersClient = new LocalRunnersResourceClient(client, baseURI);
        this.pairingClient = new PairingResourceClient(client, baseURI);
        this.projectClient = new ProjectResourceClient(client, baseURI, factory);

        ClientSupportUtils.config(client);

        // The platform returns the caller's permissions on the auth call itself, so that is what decides.
        AuthTestUtils.mockTargetWorkspaceWithPermissions(wireMock.server(), ADMIN_API_KEY, WORKSPACE_NAME,
                WORKSPACE_ID, USER, List.of(WorkspaceUserPermission.TRACE_ORIGINAL_DATA_VIEW.getValue()));
        AuthTestUtils.mockTargetWorkspaceWithPermissions(wireMock.server(), MEMBER_API_KEY, WORKSPACE_NAME,
                WORKSPACE_ID, USER, List.of());
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID createTraceWithPii(String projectName) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .input(JsonUtils.getJsonNodeFromString(STORED_INPUT))
                .output(null)
                .metadata(null)
                .feedbackScores(null)
                .comments(null)
                .guardrailsValidations(null)
                .usage(null)
                .build();

        return traceResourceClient.createTrace(trace, ADMIN_API_KEY, WORKSPACE_NAME);
    }

    @Test
    @DisplayName("stored content reaches a caller who may see originals")
    void storedContentReachesCallerWhoMaySeeOriginals() {
        var traceId = createTraceWithPii("redaction-admin-" + UUID.randomUUID());

        var trace = traceResourceClient.getById(traceId, WORKSPACE_NAME, ADMIN_API_KEY);

        assertThat(trace.input().toString()).contains(EMAIL).contains(PHONE);
    }

    @Test
    @DisplayName("content is redacted for a caller who may not")
    void contentIsRedactedForCallerWhoMayNot() {
        var traceId = createTraceWithPii("redaction-member-" + UUID.randomUUID());

        var trace = traceResourceClient.getById(traceId, WORKSPACE_NAME, MEMBER_API_KEY);

        assertThat(trace.input().toString())
                .doesNotContain(EMAIL)
                .doesNotContain(PHONE)
                .contains("[EMAIL]")
                .contains("[PHONE]");
    }

    @Test
    @DisplayName("the list endpoint is redacted too, without being touched")
    void listEndpointIsRedactedToo() {
        var projectName = "redaction-list-" + UUID.randomUUID();
        createTraceWithPii(projectName);

        var traces = traceResourceClient.getByProjectName(projectName, MEMBER_API_KEY, WORKSPACE_NAME);

        assertThat(traces).isNotEmpty();
        assertThat(traces.toString()).doesNotContain(EMAIL).contains("[EMAIL]");
    }

    @Test
    @DisplayName("streamed search results are redacted, which a response filter would have missed")
    void streamedSearchResultsAreRedacted() {
        var projectName = "redaction-stream-" + UUID.randomUUID();
        createTraceWithPii(projectName);

        var streamed = traceResourceClient.getStreamAndAssertContent(MEMBER_API_KEY, WORKSPACE_NAME,
                TraceSearchStreamRequest.builder().projectName(projectName).build());

        assertThat(streamed).isNotEmpty();
        assertThat(streamed.toString()).doesNotContain(EMAIL).doesNotContain(PHONE).contains("[EMAIL]");
    }

    @Test
    @DisplayName("streamed items keep the structural exemptions the paged path applies")
    void streamedItemsKeepTheStructuralExemptions() {
        // The streamed path is rewritten by hand rather than through the serializer, so it needs the same
        // exemptions or the two representations of one trace disagree. thread_id here matches a configured rule:
        // without the exemption it comes back rewritten from search while the UI shows it intact, and
        // get_trace_by_id on a rewritten id returns nothing.
        var projectName = "redaction-exempt-" + UUID.randomUUID();
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .threadId(RULE_MATCHING_THREAD_ID)
                .input(JsonUtils.getJsonNodeFromString(STORED_INPUT))
                .output(null)
                .metadata(null)
                .feedbackScores(null)
                .comments(null)
                .guardrailsValidations(null)
                .usage(null)
                .build();
        traceResourceClient.createTrace(trace, ADMIN_API_KEY, WORKSPACE_NAME);

        var streamed = traceResourceClient.getStreamAndAssertContent(MEMBER_API_KEY, WORKSPACE_NAME,
                TraceSearchStreamRequest.builder().projectName(projectName).build());

        assertThat(streamed).hasSize(1);
        assertThat(streamed.getFirst().threadId()).isEqualTo(RULE_MATCHING_THREAD_ID);
        assertThat(streamed.getFirst().input().toString()).doesNotContain(EMAIL).contains("[EMAIL]");
    }

    /**
     * A local runner job is the async case: {@code nextJob} suspends and its response is written from the
     * reactor thread that resumes it, where the request-scoped context does not exist. The decision therefore
     * has to travel on the request rather than on the thread, and these two tests are what says it does — the
     * unpermitted caller is masked, and the permitted one is not, which is the half that a fail-closed
     * interceptor got wrong.
     */
    private UUID connectRunner(String apiKey) {
        UUID projectId = projectClient.createProject("redaction-runner-" + UUID.randomUUID(), apiKey,
                WORKSPACE_NAME);

        byte[] activationKey = new byte[32];
        new SecureRandom().nextBytes(activationKey);

        var session = pairingClient.createSession(CreateSessionRequest.builder()
                .projectId(projectId)
                .activationKey(Base64.getEncoder().encodeToString(activationKey))
                .ttlSeconds(300)
                .type(RunnerType.ENDPOINT)
                .build(), apiKey, WORKSPACE_NAME);

        String runnerName = "redaction-runner";
        UUID runnerId = pairingClient.activate(session.sessionId(), ActivateRequest.builder()
                .runnerName(runnerName)
                .hmac(hmac(session.sessionId(), activationKey, runnerName))
                .build(), apiKey, WORKSPACE_NAME);

        runnersClient.registerAgents(runnerId, Map.of(RUNNER_AGENT, LocalRunner.Agent.builder()
                .name(RUNNER_AGENT).build()), apiKey, WORKSPACE_NAME);
        runnersClient.heartbeat(runnerId, apiKey, WORKSPACE_NAME);

        return runnerId;
    }

    private static String hmac(UUID sessionId, byte[] activationKey, String runnerName) {
        try {
            var sessionIdBytes = ByteBuffer.allocate(16)
                    .putLong(sessionId.getMostSignificantBits())
                    .putLong(sessionId.getLeastSignificantBits())
                    .array();
            byte[] runnerNameHash = MessageDigest.getInstance("SHA-256")
                    .digest(runnerName.getBytes(StandardCharsets.UTF_8));
            byte[] message = new byte[sessionIdBytes.length + runnerNameHash.length];
            System.arraycopy(sessionIdBytes, 0, message, 0, sessionIdBytes.length);
            System.arraycopy(runnerNameHash, 0, message, sessionIdBytes.length, runnerNameHash.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(activationKey, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID createJobWithPii(UUID runnerId, String apiKey) {
        LocalRunner runner = runnersClient.getRunner(runnerId, apiKey, WORKSPACE_NAME);

        return runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                .agentName(RUNNER_AGENT)
                .projectId(runner.projectId())
                .inputs(JsonUtils.getJsonNodeFromString(STORED_INPUT))
                .build(), apiKey, WORKSPACE_NAME);
    }

    @Test
    @DisplayName("a long-polled job is redacted, though its response is written off the request thread")
    void aLongPolledJobIsRedacted() {
        UUID runnerId = connectRunner(ADMIN_API_KEY);
        UUID jobId = createJobWithPii(runnerId, ADMIN_API_KEY);

        LocalRunnerJob polled;
        try (var response = runnersClient.callNextJob(runnerId, MEMBER_API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(200);
            polled = response.readEntity(LocalRunnerJob.class);
        }

        assertThat(polled.id()).isEqualTo(jobId);
        assertThat(polled.inputs().toString())
                .doesNotContain(EMAIL)
                .doesNotContain(PHONE)
                .contains("[EMAIL]")
                .contains("[PHONE]");

        // The same job read through the synchronous endpoint, which the interceptor has always covered: the two
        // representations of one job have to agree, and before the decision travelled with the request they did
        // not.
        var fetched = runnersClient.getJob(jobId, MEMBER_API_KEY, WORKSPACE_NAME);

        assertThat(fetched.inputs()).isEqualTo(polled.inputs());
    }

    @Test
    @DisplayName("a long-polled job reaches a permitted caller as stored")
    void aLongPolledJobReachesAPermittedCallerAsStored() {
        // Failing closed on the async path instead of carrying the decision rewrote this response too, for a
        // caller who is allowed to see it and whose permission was never consulted.
        UUID runnerId = connectRunner(ADMIN_API_KEY);
        createJobWithPii(runnerId, ADMIN_API_KEY);

        try (var response = runnersClient.callNextJob(runnerId, ADMIN_API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(LocalRunnerJob.class).inputs().toString())
                    .contains(EMAIL)
                    .contains(PHONE);
        }
    }

    @Test
    @DisplayName("streamed and paged reads of one trace agree")
    void streamedAndPagedReadsAgree() {
        var projectName = "redaction-parity-" + UUID.randomUUID();
        var traceId = createTraceWithPii(projectName);

        var paged = traceResourceClient.getById(traceId, WORKSPACE_NAME, MEMBER_API_KEY);
        var streamed = traceResourceClient.getStreamAndAssertContent(MEMBER_API_KEY, WORKSPACE_NAME,
                TraceSearchStreamRequest.builder().projectName(projectName).build());

        assertThat(streamed).hasSize(1);
        assertThat(streamed.getFirst().threadId()).isEqualTo(paged.threadId());
        assertThat(streamed.getFirst().input()).isEqualTo(paged.input());
    }
}
