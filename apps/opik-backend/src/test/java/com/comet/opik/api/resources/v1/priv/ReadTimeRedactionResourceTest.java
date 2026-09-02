package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Span;
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
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
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
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
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

    private static final String ADMIN_SESSION_TOKEN = UUID.randomUUID().toString();
    private static final String MEMBER_SESSION_TOKEN = UUID.randomUUID().toString();

    private static final String EMAIL = "john.doe@example.com";
    private static final String PHONE = "555-123-4567";
    private static final String MASK = "[REDACTED]";

    private static final String RUNNER_AGENT = "redaction-agent";

    /**
     * A structural field the config does not name, alongside content it does. It has to survive so the assertions
     * can tell masking from wholesale destruction of the payload.
     */
    private static final String MODEL = "gpt-4o-2024-08-06";

    private static final String STORED_INPUT = """
            {"prompt":"Refund for %s, callback %s","model":"%s"}""".formatted(EMAIL, PHONE, MODEL);

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
                                new CustomConfig("redaction.maskFields[0]", "prompt")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private ClientSupport clientSupport;
    private LocalRunnersResourceClient runnersClient;
    private PairingResourceClient pairingClient;
    private ProjectResourceClient projectClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.clientSupport = client;
        this.runnersClient = new LocalRunnersResourceClient(client, baseURI);
        this.pairingClient = new PairingResourceClient(client, baseURI);
        this.projectClient = new ProjectResourceClient(client, baseURI, factory);

        ClientSupportUtils.config(client);

        // The platform returns the caller's permissions on the auth call itself, so that is what decides.
        AuthTestUtils.mockTargetWorkspaceWithPermissions(wireMock.server(), ADMIN_API_KEY, WORKSPACE_NAME,
                WORKSPACE_ID, USER, List.of(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue()));
        AuthTestUtils.mockTargetWorkspaceWithPermissions(wireMock.server(), MEMBER_API_KEY, WORKSPACE_NAME,
                WORKSPACE_ID, USER, List.of());

        // The same two callers over a session cookie, which resolves permissions through its own endpoint.
        AuthTestUtils.mockSessionCookieTargetWorkspaceWithPermissions(wireMock.server(), ADMIN_SESSION_TOKEN,
                WORKSPACE_NAME, WORKSPACE_ID, USER,
                List.of(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue()));
        AuthTestUtils.mockSessionCookieTargetWorkspaceWithPermissions(wireMock.server(), MEMBER_SESSION_TOKEN,
                WORKSPACE_NAME, WORKSPACE_ID, USER, List.of());
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

        assertThat(trace.input().toString()).contains(EMAIL).contains(PHONE).contains(MODEL);
    }

    @Test
    @DisplayName("content is redacted for a caller who may not")
    void contentIsRedactedForCallerWhoMayNot() {
        var traceId = createTraceWithPii("redaction-member-" + UUID.randomUUID());

        var trace = traceResourceClient.getById(traceId, WORKSPACE_NAME, MEMBER_API_KEY);

        assertThat(trace.input().toString())
                .doesNotContain(EMAIL)
                .doesNotContain(PHONE)
                .contains(MASK)
                // The field the config does not name is returned as stored, so the payload stays navigable.
                .contains(MODEL);
    }

    @Test
    @DisplayName("the list endpoint is redacted too, without being touched")
    void listEndpointIsRedactedToo() {
        var projectName = "redaction-list-" + UUID.randomUUID();
        createTraceWithPii(projectName);

        var traces = traceResourceClient.getByProjectName(projectName, MEMBER_API_KEY, WORKSPACE_NAME);

        assertThat(traces).isNotEmpty();
        assertThat(traces.toString()).doesNotContain(EMAIL).contains(MASK).contains(MODEL);
    }

    @Test
    @DisplayName("streamed search results are redacted, which a response filter would have missed")
    void streamedSearchResultsAreRedacted() {
        var projectName = "redaction-stream-" + UUID.randomUUID();
        createTraceWithPii(projectName);

        var streamed = traceResourceClient.getStreamAndAssertContent(MEMBER_API_KEY, WORKSPACE_NAME,
                TraceSearchStreamRequest.builder().projectName(projectName).build());

        assertThat(streamed).isNotEmpty();
        assertThat(streamed.toString()).doesNotContain(EMAIL).doesNotContain(PHONE).contains(MASK);
    }

    @Test
    @DisplayName("streamed and paged reads agree, including on the fields that are not masked")
    void streamedAndPagedReadsAgree() {
        // Guards the divergence that a second, separate masking implementation for the streamed path caused: the
        // streamed items skipped the structural exemptions the paged path applied, so search returned rewritten
        // ids and models while the UI showed them intact. One implementation in the row mapper is what keeps
        // these two assertions true together.
        var projectName = "redaction-parity-" + UUID.randomUUID();
        var traceId = createTraceWithPii(projectName);

        var paged = traceResourceClient.getById(traceId, WORKSPACE_NAME, MEMBER_API_KEY);
        var streamed = traceResourceClient.getStreamAndAssertContent(MEMBER_API_KEY, WORKSPACE_NAME,
                TraceSearchStreamRequest.builder().projectName(projectName).build());

        assertThat(streamed).hasSize(1);
        assertThat(streamed.getFirst().id()).isEqualTo(paged.id());
        assertThat(streamed.getFirst().input()).isEqualTo(paged.input());
    }

    @Test
    @DisplayName("spans are masked too, so the wiring is not trace-only")
    void spansAreMaskedToo() {
        var projectName = "redaction-span-" + UUID.randomUUID();
        var traceId = createTraceWithPii(projectName);

        var span = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(traceId)
                .parentSpanId(null)
                .input(JsonUtils.getJsonNodeFromString(STORED_INPUT))
                .output(null)
                .metadata(null)
                .feedbackScores(null)
                .comments(null)
                .usage(null)
                .totalEstimatedCost(null)
                .build();
        spanResourceClient.createSpan(span, ADMIN_API_KEY, WORKSPACE_NAME);

        var asStored = spanResourceClient.getById(span.id(), WORKSPACE_NAME, ADMIN_API_KEY);
        var masked = spanResourceClient.getById(span.id(), WORKSPACE_NAME, MEMBER_API_KEY);

        assertThat(asStored.input().toString()).contains(EMAIL).contains(MODEL);
        assertThat(masked.input().toString()).doesNotContain(EMAIL).contains(MASK).contains(MODEL);
    }

    @Test
    @DisplayName("a response that cannot be masked is refused rather than served as stored")
    void aResponseThatCannotBeMaskedIsRefused() {
        // The CSV export is generated by a job with no caller and downloaded by callers whose permissions
        // differ, so it can only be withheld. Serving it would be a hole in the control, not a limitation of it.
        try (var response = downloadExport(MEMBER_API_KEY)) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
        }
    }

    @Test
    @DisplayName("the refusal is the masking decision, not the endpoint refusing everyone")
    void theRefusalIsTheMaskingDecision() {
        // Same path, same nonexistent job, caller who may see originals: anything but 403 proves the 403 above
        // came from the masking decision rather than from authentication or the path itself.
        try (var response = downloadExport(ADMIN_API_KEY)) {
            assertThat(response.getStatus()).isNotEqualTo(HttpStatus.SC_FORBIDDEN);
        }
    }

    private jakarta.ws.rs.core.Response downloadExport(String apiKey) {
        return clientSupport.target(baseURI)
                .path("v1/private/datasets/export-jobs")
                .path(UUID.randomUUID().toString())
                .path("download")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                .get();
    }

    /**
     * The same two decisions over a session cookie rather than an api key.
     * <p>
     * Browser and OAuth callers resolve their permissions through a different endpoint of the workspace
     * permissions API than api-key callers do, so a change that drops permissions on one path only, or inverts
     * the decision, would leave every api-key test green.
     */
    private Trace getTraceWithSessionCookie(UUID traceId, String sessionToken) {
        try (var response = clientSupport.target("%s/v1/private/traces/%s".formatted(baseURI, traceId))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .cookie(SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(Trace.class);
        }
    }

    @Test
    @DisplayName("a session caller without the permission is masked, as an api key caller is")
    void aSessionCallerWithoutThePermissionIsMasked() {
        var traceId = createTraceWithPii("redaction-session-member-" + UUID.randomUUID());

        var trace = getTraceWithSessionCookie(traceId, MEMBER_SESSION_TOKEN);

        assertThat(trace.input().get("prompt").asText()).isEqualTo(MASK);
        assertThat(trace.input().toString()).doesNotContain(EMAIL).doesNotContain(PHONE);
        // The unnamed field survives, so this says masking rather than wholesale destruction.
        assertThat(trace.input().get("model").asText()).isEqualTo(MODEL);
    }

    @Test
    @DisplayName("a session caller holding the permission reads stored content, as an api key caller does")
    void aSessionCallerHoldingThePermissionReadsStoredContent() {
        var traceId = createTraceWithPii("redaction-session-admin-" + UUID.randomUUID());

        var trace = getTraceWithSessionCookie(traceId, ADMIN_SESSION_TOKEN);

        assertThat(trace.input().toString()).contains(EMAIL).contains(PHONE);
    }

    /**
     * A runner job is read from Redis rather than through a masked DAO, so it is masked in the resource or not
     * at all — the coverage boundary of moving masking into the DAOs. It is also the async case: {@code nextJob}
     * suspends and its response is written from the reactor thread that resumes it, where the request-scoped
     * context does not exist, so the masker is captured before the chain is subscribed. These two tests are what
     * says both halves work — the unpermitted caller is masked, and the permitted one is not.
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
    @DisplayName("a long-polled job is masked, though its response is written off the request thread")
    void aLongPolledJobIsMasked() {
        UUID runnerId = connectRunner(ADMIN_API_KEY);
        UUID jobId = createJobWithPii(runnerId, ADMIN_API_KEY);

        LocalRunnerJob polled;
        try (var response = runnersClient.callNextJob(runnerId, MEMBER_API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(200);
            polled = response.readEntity(LocalRunnerJob.class);
        }

        assertThat(polled.id()).isEqualTo(jobId);
        assertThat(polled.inputs().get("prompt").asText()).isEqualTo(MASK);
        assertThat(polled.inputs().toString()).doesNotContain(EMAIL).doesNotContain(PHONE);
        // The unnamed field survives, so this says masking rather than wholesale destruction.
        assertThat(polled.inputs().get("model").asText()).isEqualTo(MODEL);

        // The same job as a permitted caller sees it, which bounds what redaction was allowed to touch: every
        // other field has to survive intact. Asserting only on inputs() would pass just as well if the rules
        // had also rewritten agent_name, blueprint_name or the metadata on their way out.
        var stored = runnersClient.getJob(jobId, ADMIN_API_KEY, WORKSPACE_NAME);

        assertThat(stored.inputs().toString()).contains(EMAIL).contains(PHONE);
        assertThat(polled).isEqualTo(stored.toBuilder().inputs(polled.inputs()).build());

        // And the two representations of one job have to agree with each other, which is the part that a
        // thread-bound decision got wrong: before it travelled with the request, only the synchronous read
        // was masked.
        assertThat(runnersClient.getJob(jobId, MEMBER_API_KEY, WORKSPACE_NAME).inputs())
                .isEqualTo(polled.inputs());
    }

    @Test
    @DisplayName("a long-polled job reaches a permitted caller as stored")
    void aLongPolledJobReachesAPermittedCallerAsStored() {
        // Failing closed on the async path instead of carrying the decision rewrote this response too, for a
        // caller who is allowed to see it and whose permission was never consulted.
        UUID runnerId = connectRunner(ADMIN_API_KEY);
        UUID jobId = createJobWithPii(runnerId, ADMIN_API_KEY);

        LocalRunnerJob polled;
        try (var response = runnersClient.callNextJob(runnerId, ADMIN_API_KEY, WORKSPACE_NAME)) {
            assertThat(response.getStatus()).isEqualTo(200);
            polled = response.readEntity(LocalRunnerJob.class);
        }

        assertThat(polled.inputs().toString()).contains(EMAIL).contains(PHONE);

        // Whole job, not just inputs: "reaches a permitted caller as stored" is a claim about the entire
        // response, and the interceptor either leaves it alone or it does not.
        assertThat(polled).isEqualTo(runnersClient.getJob(jobId, ADMIN_API_KEY, WORKSPACE_NAME));
    }
}
