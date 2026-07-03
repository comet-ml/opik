package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.podam.PodamFactoryUtils;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import lombok.Builder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeletionEventsLocalTest {

    private static final String[] DELETION_EVENTS_IGNORED_FIELDS = {"eventTime"};

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final Network network = Network.newNetwork();

    private GenericContainer<?> zooKeeperContainer;
    private ClickHouseContainer clickHouseContainer;

    private ConnectionFactory connectionFactory;

    @BeforeAll
    void beforeAll() {
        zooKeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false, network);
        clickHouseContainer = ClickHouseContainerUtils.newClickHouseContainer(false, network, zooKeeperContainer);
        Startables.deepStart(zooKeeperContainer, clickHouseContainer).join();
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        connectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouseContainer, DATABASE_NAME)
                .build();
    }

    @AfterAll
    void afterAll() {
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
        if (zooKeeperContainer != null && zooKeeperContainer.isRunning()) {
            zooKeeperContainer.stop();
        }
        network.close();
    }

    @Test
    void insertAndSelectDeletionEvent() {
        var expectedDeletionEvent = podamFactory.manufacturePojo(DeletionEvent.class);
        insert(expectedDeletionEvent);

        var actualDeletionEvents = findByWorkspaceId(expectedDeletionEvent.workspaceId());

        assertThat(actualDeletionEvents).hasSize(1);
        var actualDeletionEvent = actualDeletionEvents.getFirst();
        assertDeletionEvent(actualDeletionEvent, expectedDeletionEvent);
    }

    /**
     * Omitted event_time so it exercises the column default, matching the delete-path write.
     */
    private void insert(DeletionEvent event) {
        var sql = """
                INSERT INTO deletion_events_local
                    (source_table, workspace_id, project_id, deleted_id, deletion_reason)
                VALUES (:sourceTable, :workspaceId, :projectId, :deletedId, :deletionReason)
                """;
        Mono.usingWhen(
                connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(sql)
                        .bind("sourceTable", event.sourceTable())
                        .bind("workspaceId", event.workspaceId())
                        .bind("projectId", event.projectId())
                        .bind("deletedId", event.deletedId())
                        .bind("deletionReason", event.deletionReason())
                        .execute())
                        .flatMap(Result::getRowsUpdated)
                        .then(),
                Connection::close)
                .block();
    }

    private List<DeletionEvent> findByWorkspaceId(String workspaceId) {
        var sql = """
                SELECT event_time, source_table, workspace_id, project_id, deleted_id, deletion_reason
                FROM deletion_events_local
                WHERE workspace_id = :workspaceId
                """;
        return Mono.usingWhen(
                connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(sql)
                        .bind("workspaceId", workspaceId)
                        .execute())
                        .flatMap(result -> result.map((row, _) -> DeletionEvent.builder()
                                .eventTime(row.get("event_time", Instant.class))
                                .sourceTable(row.get("source_table", String.class))
                                .workspaceId(row.get("workspace_id", String.class))
                                .projectId(row.get("project_id", UUID.class))
                                .deletedId(row.get("deleted_id", String.class))
                                .deletionReason(row.get("deletion_reason", String.class))
                                .build()))
                        .collectList(),
                Connection::close)
                .block();
    }

    private void assertDeletionEvent(DeletionEvent actualDeletionEvent, DeletionEvent expectedDeletionEvent) {
        assertThat(actualDeletionEvent)
                .usingRecursiveComparison()
                .ignoringFields(DELETION_EVENTS_IGNORED_FIELDS)
                .isEqualTo(expectedDeletionEvent);
        assertThat(actualDeletionEvent.eventTime())
                .isCloseTo(expectedDeletionEvent.eventTime(), within(1, ChronoUnit.SECONDS));
    }

    @Builder(toBuilder = true)
    private record DeletionEvent(
            Instant eventTime,
            String sourceTable,
            String workspaceId,
            UUID projectId,
            String deletedId,
            String deletionReason) {
    }
}
