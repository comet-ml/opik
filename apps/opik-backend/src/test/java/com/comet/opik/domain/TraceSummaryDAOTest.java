package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TraceSummaryDAOTest {

    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = newTestDropwizardAppExtension(TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .build());
    }

    @Test
    @DisplayName("when summaries are batch inserted, then they are persisted and readable by workspace")
    void batchInsert__whenSummariesInserted__thenPersisted(TraceSummaryDAO traceSummaryDAO,
            TransactionTemplateAsync templateAsync) {
        var workspaceId = "workspace-" + UUID.randomUUID();

        var summaryA = TraceSummary.builder()
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .summary("The user asked how to reset their password.")
                .build();
        var summaryB = TraceSummary.builder()
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .summary("The user requested the weather forecast.")
                .build();

        traceSummaryDAO.batchInsert(List.of(summaryA, summaryB))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, "user-" + UUID.randomUUID()))
                .block();

        Map<UUID, String> stored = readByWorkspace(templateAsync, workspaceId);

        assertThat(stored).hasSize(2);
        assertThat(stored).containsEntry(summaryA.traceId(), summaryA.summary());
        assertThat(stored).containsEntry(summaryB.traceId(), summaryB.summary());
    }

    @Test
    @DisplayName("when a summary exists for the trace, then findByTraceId returns it")
    void findByTraceId__whenExists__thenReturnsSummary(TraceSummaryDAO traceSummaryDAO) {
        var workspaceId = "workspace-" + UUID.randomUUID();
        var summary = TraceSummary.builder()
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .summary("The user asked to summarize a document.")
                .build();

        traceSummaryDAO.batchInsert(List.of(summary))
                .contextWrite(ctx -> withContext(ctx, workspaceId))
                .block();

        String found = traceSummaryDAO.findByTraceId(summary.traceId())
                .contextWrite(ctx -> withContext(ctx, workspaceId))
                .block();

        assertThat(found).isEqualTo(summary.summary());
    }

    @Test
    @DisplayName("when no summary exists for the trace, then findByTraceId is empty")
    void findByTraceId__whenMissing__thenEmpty(TraceSummaryDAO traceSummaryDAO) {
        var workspaceId = "workspace-" + UUID.randomUUID();

        String found = traceSummaryDAO.findByTraceId(UUID.randomUUID())
                .contextWrite(ctx -> withContext(ctx, workspaceId))
                .block();

        assertThat(found).isNull();
    }

    private Context withContext(Context ctx, String workspaceId) {
        return ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                .put(RequestContext.USER_NAME, "user-" + UUID.randomUUID());
    }

    private Map<UUID, String> readByWorkspace(TransactionTemplateAsync templateAsync, String workspaceId) {
        List<Map.Entry<UUID, String>> rows = templateAsync.<Map.Entry<UUID, String>>stream(connection -> {
            var statement = connection.createStatement(
                    "SELECT id, summary FROM trace_summaries FINAL WHERE workspace_id = :workspace_id");
            statement.bind("workspace_id", workspaceId);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> Map.entry(
                            UUID.fromString(row.get("id", String.class)),
                            row.get("summary", String.class))));
        }).collectList().block();

        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
