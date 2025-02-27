package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.WorkspaceSettings;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.hc.core5.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset Experiments E2E Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class WorkspaceResourceTest {

    private static final String BASE_RESOURCE_URI = "%s/v1/private/workspaces";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        StreamReadConstraints.overrideDefaultStreamReadConstraints(
                StreamReadConstraints.builder().maxStringLength(100000000).build());

        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        var contextConfig = TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .modules(List.of(
                        new AbstractModule() {
                            @Override
                            protected void configure() {
                                WorkspaceSettings instance = new WorkspaceSettings();
                                instance.setMaxSizeToAllowSorting(0.1);

                                bind(WorkspaceSettings.class).toInstance(instance);
                            }
                        }))
                .build();

        APP = newTestDropwizardAppExtension(contextConfig);
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId,
                UUID.randomUUID().toString());
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class WorkspaceMetadata {

        @Test
        @DisplayName("when workspace has less data than the limit then dynamo sort is enabled")
        void when__workspace_has_less_data_than_the_limit_then_dynamo_sort_is_enabled() {

            String apiKey = UUID.randomUUID().toString();
            String testWorkspace = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, testWorkspace, workspaceId);

            createData(apiKey, testWorkspace, false);

            // Create workspace
            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("metadata")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, testWorkspace)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                assertThat(actualResponse.readEntity(com.comet.opik.api.WorkspaceMetadata.class)).isEqualTo(
                        new com.comet.opik.api.WorkspaceMetadata(true));
            }
        }

        @Test
        @DisplayName("when workspace has more data than the limit then dynamo sort is disabled")
        void when__workspace_has_more_data_than_the_limit_then_dynamo_sort_is_disabled() {

            String apiKey = UUID.randomUUID().toString();
            String testWorkspace = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, testWorkspace, workspaceId);

            createData(apiKey, testWorkspace, true);

            // Create workspace
            try (var actualResponse = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .path("metadata")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, testWorkspace)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                assertThat(actualResponse.readEntity(com.comet.opik.api.WorkspaceMetadata.class)).isEqualTo(
                        new com.comet.opik.api.WorkspaceMetadata(false));
            }
        }

    }

    private void createData(String apiKey, String testWorkspace, boolean useLargeData) {
        // create trace
        Trace trace = factory.manufacturePojo(Trace.class);
        UUID traceId = traceResourceClient.createTrace(trace, apiKey, testWorkspace);

        // Create spans
        List<Span> spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                .map(span -> span.toBuilder()
                        .traceId(traceId)
                        .projectName(trace.projectName())
                        .input(useLargeData ? generateLargeJson() : span.input())
                        .output(useLargeData ? generateLargeJson() : span.output())
                        .build())
                .toList();

        spanResourceClient.batchCreateSpans(spans, apiKey, testWorkspace);
    }

    private JsonNode generateLargeJson() {
        int sizeInMB = 50;
        int totalChars = sizeInMB * 1024 * 1024;

        String repeatedPattern = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"; // 36 characters
        int patternLength = repeatedPattern.length();

        StringBuilder sb = new StringBuilder(totalChars);
        for (int i = 0; i < totalChars; i++) {
            sb.append(repeatedPattern.charAt(i % patternLength)); // Repeat pattern
        }

        return JsonUtils.readTree(Map.of("large", sb.toString()));
    }

}