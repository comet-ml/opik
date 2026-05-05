package com.comet.opik.domain.workspaces;

import com.comet.opik.api.OpikVersion;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class WorkspacesServiceTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final Network network = Network.newNetwork();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer(false);
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(
            false, network, ZOOKEEPER);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .redisUrl(REDIS.getRedisURI())
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .build());
    }

    @AfterAll
    void tearDown() {
        MYSQL.stop();
        CLICKHOUSE.stop();
        ZOOKEEPER.stop();
        REDIS.stop();
        network.close();
    }

    @Test
    @DisplayName("upsertVersion inserts a row when the workspace has no metadata yet")
    void upsertVersionInsertsNewRow(WorkspacesService service) {
        var workspaceId = UUID.randomUUID().toString();

        assertThat(service.findLastKnownVersion(workspaceId)).isEmpty();

        service.upsertVersion(workspaceId, OpikVersion.VERSION_2, Instant.now());

        assertThat(service.findLastKnownVersion(workspaceId)).contains(OpikVersion.VERSION_2);
    }

    @Test
    @DisplayName("upsertVersion overwrites the previously recorded value")
    void upsertVersionOverwritesPreviousValue(WorkspacesService service) {
        var workspaceId = UUID.randomUUID().toString();

        service.upsertVersion(workspaceId, OpikVersion.VERSION_1, Instant.now());
        assertThat(service.findLastKnownVersion(workspaceId)).contains(OpikVersion.VERSION_1);

        service.upsertVersion(workspaceId, OpikVersion.VERSION_2, Instant.now());
        assertThat(service.findLastKnownVersion(workspaceId)).contains(OpikVersion.VERSION_2);
    }

    @Test
    @DisplayName("markFirstTraceReported returns true once and false on every subsequent call")
    void markFirstTraceReportedDedupsAcrossCalls(WorkspacesService service) {
        var workspaceId = UUID.randomUUID().toString();

        assertThat(service.markFirstTraceReported(workspaceId, Instant.now())).isTrue();
        assertThat(service.markFirstTraceReported(workspaceId, Instant.now())).isFalse();
        assertThat(service.markFirstTraceReported(workspaceId, Instant.now())).isFalse();
    }

    @Test
    @DisplayName("markFirstTraceReported returns true after the row exists for another reason")
    void markFirstTraceReportedSetsTimestampOnPreexistingRow(WorkspacesService service) {
        // Row created by a different feature path (version tracking), with first_trace_reported_at NULL.
        var workspaceId = UUID.randomUUID().toString();
        service.upsertVersion(workspaceId, OpikVersion.VERSION_1, Instant.now());

        assertThat(service.markFirstTraceReported(workspaceId, Instant.now())).isTrue();
        assertThat(service.markFirstTraceReported(workspaceId, Instant.now())).isFalse();
    }

    @Test
    @DisplayName("Concurrent markFirstTraceReported calls — exactly one returns true")
    void markFirstTraceReportedExactlyOneWinsUnderConcurrency(WorkspacesService service) throws Exception {
        var workspaceId = UUID.randomUUID().toString();
        var concurrency = 16;
        var executor = Executors.newFixedThreadPool(concurrency);
        try {
            var futures = IntStream.range(0, concurrency)
                    .<Future<Boolean>>mapToObj(__ -> executor
                            .submit(() -> service.markFirstTraceReported(workspaceId, Instant.now())))
                    .toList();

            long winners = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }

            assertThat(winners).as("exactly one writer should observe the first-trace transition").isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("markMigrationSkipped is idempotent — second call does not overwrite the original")
    void markMigrationSkippedIsIdempotent(WorkspacesService service) {
        var workspaceId = UUID.randomUUID().toString();

        service.markMigrationSkipped(workspaceId, Instant.now(), "deleted_project");
        service.markMigrationSkipped(workspaceId, Instant.now(), "different_reason");

        assertThat(service.findMigrationSkippedWorkspaceIds()).contains(workspaceId);
        assertThat(service.countMigrationSkipped()).isPositive();
    }

    @Test
    @DisplayName("findMigrationSkippedWorkspaceIds returns every skipped workspace")
    void findMigrationSkippedWorkspaceIdsReturnsAll(WorkspacesService service) {
        var skippedA = UUID.randomUUID().toString();
        var skippedB = UUID.randomUUID().toString();
        var notSkipped = UUID.randomUUID().toString();

        service.markMigrationSkipped(skippedA, Instant.now(), "deleted_project");
        service.markMigrationSkipped(skippedB, Instant.now(), "deleted_project");
        // notSkipped exists in the table but with no migration_skipped_at.
        service.upsertVersion(notSkipped, OpikVersion.VERSION_2, Instant.now());

        var skippedIds = service.findMigrationSkippedWorkspaceIds();

        assertThat(skippedIds)
                .contains(skippedA, skippedB)
                .doesNotContain(notSkipped);
    }

    @Test
    @DisplayName("countMigrationSkipped reflects findMigrationSkippedWorkspaceIds().size()")
    void countMigrationSkippedMatchesFind(WorkspacesService service) {
        var beforeCount = service.countMigrationSkipped();
        var beforeIds = service.findMigrationSkippedWorkspaceIds().size();

        assertThat(beforeCount).isEqualTo(beforeIds);

        service.markMigrationSkipped(UUID.randomUUID().toString(), Instant.now(), "deleted_project");
        service.markMigrationSkipped(UUID.randomUUID().toString(), Instant.now(), "deleted_project");

        assertThat(service.countMigrationSkipped()).isEqualTo(beforeCount + 2);
        assertThat(service.findMigrationSkippedWorkspaceIds()).hasSize((int) (beforeCount + 2));
    }

    @Test
    @DisplayName("Version, first-trace, and migration-skip writers can target the same workspace row")
    void allFeatureColumnsCoexistOnSameRow(WorkspacesService service) {
        var workspaceId = UUID.randomUUID().toString();

        service.upsertVersion(workspaceId, OpikVersion.VERSION_2, Instant.now());
        assertThat(service.markFirstTraceReported(workspaceId, Instant.now())).isTrue();
        service.markMigrationSkipped(workspaceId, Instant.now(), "deleted_project");

        assertThat(service.findLastKnownVersion(workspaceId)).contains(OpikVersion.VERSION_2);
        assertThat(service.markFirstTraceReported(workspaceId, Instant.now())).isFalse();
        assertThat(service.findMigrationSkippedWorkspaceIds()).contains(workspaceId);
    }
}
