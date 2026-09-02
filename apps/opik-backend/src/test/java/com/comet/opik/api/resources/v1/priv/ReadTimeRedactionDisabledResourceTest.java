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
import org.testcontainers.containers.Network;
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
@DisplayName("Read Time Redaction Disabled Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ReadTimeRedactionDisabledResourceTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = RandomStringUtils.randomAlphabetic(10);

    private static final String ADMIN_API_KEY = UUID.randomUUID().toString();
    private static final String MEMBER_API_KEY = UUID.randomUUID().toString();

    private static final String EMAIL = "john.doe@example.com";
    private static final String PHONE = "555-123-4567";
    private static final String MASK = "[REDACTED]";

    private static final String STORED_INPUT = """
            {"prompt":"Refund for %s, callback %s"}""".formatted(EMAIL, PHONE);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    /**
     * Own network, and reuse switched off. The shared defaults hand back the same ZooKeeper as the sibling
     * redaction test, and the replicated-table migration is not idempotent against ZooKeeper state another
     * ClickHouse instance already wrote — which made the two classes fail intermittently when run together.
     */
    private final Network NETWORK = Network.newNetwork();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils
            .newZookeeperContainer(false, NETWORK);
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(false, NETWORK, ZOOKEEPER_CONTAINER);
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
                                new CustomConfig("redaction.enabled", "false"),
                                new CustomConfig("redaction.maskFields[0]", "prompt")))
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
                WORKSPACE_ID, USER, List.of(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue()));
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
    @DisplayName("with the flag off, content reaches even an unpermitted caller exactly as stored")
    void withFlagOffContentReachesUnpermittedCallerAsStored() {
        var traceId = createTraceWithPii("redaction-off-" + UUID.randomUUID());

        // MEMBER_API_KEY is mocked as not holding the permission, and mask fields are configured. Only the flag
        // is off — which must be enough to leave every response byte-identical to an install without the feature.
        var trace = traceResourceClient.getById(traceId, WORKSPACE_NAME, MEMBER_API_KEY);

        assertThat(trace.input().toString()).contains(EMAIL).contains(PHONE);
        assertThat(trace.input().toString()).doesNotContain(MASK);
    }

    @Test
    @DisplayName("streamed results are untouched with the flag off as well")
    void streamedResultsAreUntouchedWithFlagOff() {
        var projectName = "redaction-off-stream-" + UUID.randomUUID();
        createTraceWithPii(projectName);

        var streamed = traceResourceClient.getStreamAndAssertContent(MEMBER_API_KEY, WORKSPACE_NAME,
                TraceSearchStreamRequest.builder().projectName(projectName).build());

        assertThat(streamed).isNotEmpty();
        assertThat(streamed.toString()).contains(EMAIL).doesNotContain(MASK);
    }
}
