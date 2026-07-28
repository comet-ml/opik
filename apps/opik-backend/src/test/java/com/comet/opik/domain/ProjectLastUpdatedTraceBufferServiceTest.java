package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ProjectLastUpdatedTraceBufferServiceImpl.PENDING_SET_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for the enabled (Redis-buffered) path: {@code record} buffers per-project maxima and
 * {@code flush} drains them to {@code projects.last_updated_trace_at}, asserted through the public project API.
 * The disabled/synchronous fallback is exercised by {@code ProjectsResourceTest} (feature off by default), so it is
 * intentionally not repeated here. {@code jobEnabled} is off so tests drive {@code flush()} directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ProjectLastUpdatedTraceBufferServiceTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("projectLastUpdatedFlush.enabled", "true"),
                                new CustomConfig("projectLastUpdatedFlush.jobEnabled", "false")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private ProjectLastUpdatedTraceBufferService bufferService;
    private StringRedisClient redisClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport, ProjectLastUpdatedTraceBufferService bufferService,
            StringRedisClient redisClient) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);

        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.bufferService = bufferService;
        this.redisClient = redisClient;
    }

    @Test
    void recordThenFlushPersistsMarkerToProject() {
        var seeded = seedWorkspaceWithProject();
        var lastUpdatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        bufferService.record(seeded.workspaceId(), List.of(projectMarker(seeded.projectId(), lastUpdatedAt)));
        bufferService.flush();

        assertLastUpdatedTraceAt(seeded, lastUpdatedAt);
    }

    @Test
    void recordKeepsClusterWideMaxAcrossCalls() {
        var seeded = seedWorkspaceWithProject();
        var newest = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // addIfGreater must keep only the highest timestamp regardless of the order records arrive in.
        bufferService.record(seeded.workspaceId(), List.of(projectMarker(seeded.projectId(), newest.minusSeconds(10))));
        bufferService.record(seeded.workspaceId(), List.of(projectMarker(seeded.projectId(), newest)));
        bufferService.record(seeded.workspaceId(), List.of(projectMarker(seeded.projectId(), newest.minusSeconds(5))));
        bufferService.flush();

        assertLastUpdatedTraceAt(seeded, newest);
    }

    @Test
    void flushWritesOneBatchPerWorkspace() {
        var seededA = seedWorkspaceWithProject();
        var seededB = seedWorkspaceWithProject();
        var lastUpdatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        bufferService.record(seededA.workspaceId(), List.of(projectMarker(seededA.projectId(), lastUpdatedAt)));
        bufferService.record(seededB.workspaceId(), List.of(projectMarker(seededB.projectId(), lastUpdatedAt)));
        bufferService.flush();

        assertLastUpdatedTraceAt(seededA, lastUpdatedAt);
        assertLastUpdatedTraceAt(seededB, lastUpdatedAt);
    }

    @Test
    void flushSkipsMalformedMemberButDrainsBuffer() {
        var seeded = seedWorkspaceWithProject();
        var lastUpdatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        bufferService.record(seeded.workspaceId(), List.of(projectMarker(seeded.projectId(), lastUpdatedAt)));
        // Inject a member that cannot be parsed back to a workspace/projectId pair; it must not fail the flush.
        redisClient.getScoredSortedSet(PENDING_SET_KEY)
                .add(lastUpdatedAt.toEpochMilli(), seeded.workspaceId() + ":not-a-uuid");

        bufferService.flush();

        assertLastUpdatedTraceAt(seeded, lastUpdatedAt);
        // Both the valid and the malformed members are removed, leaving nothing buffered.
        assertThat(redisClient.getScoredSortedSet(PENDING_SET_KEY).size()).isZero();
    }

    private SeededProject seedWorkspaceWithProject() {
        var apiKey = randomName("api-key");
        var workspaceName = randomName("workspace");
        var workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, randomName("user"));

        var projectId = projectResourceClient.createProject(
                factory.manufacturePojo(Project.class).toBuilder().name(randomName("project")).build(),
                apiKey, workspaceName);
        return new SeededProject(apiKey, workspaceName, workspaceId, projectId);
    }

    private void assertLastUpdatedTraceAt(SeededProject seeded, Instant expected) {
        var project = projectResourceClient.getProject(seeded.projectId(), seeded.apiKey(), seeded.workspaceName());
        assertThat(project.lastUpdatedTraceAt()).isNotNull();
        assertThat(project.lastUpdatedTraceAt().toEpochMilli()).isEqualTo(expected.toEpochMilli());
    }

    private static ProjectIdLastUpdated projectMarker(UUID projectId, Instant lastUpdatedAt) {
        return ProjectIdLastUpdated.builder().id(projectId).lastUpdatedAt(lastUpdatedAt).build();
    }

    private static String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    private record SeededProject(String apiKey, String workspaceName, String workspaceId, UUID projectId) {
    }
}
