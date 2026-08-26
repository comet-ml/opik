package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
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

import java.util.List;
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

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);

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
