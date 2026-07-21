package com.comet.opik.domain;

import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.v1.jobs.OptimizationStalledReaperJob;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.common.eventbus.EventBus;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Service-level coverage for the stalled Optimization Studio run reaper (OPIK-7159). Drives
 * {@code optimizationService.reconcileStalledStudioOptimizations(...).block()} directly so each
 * threshold / filtering case can be exercised in isolation, mirroring
 * {@link OptimizationProjectMigrationServiceTest}.
 * <p>
 * Whether a seeded run counts as "stalled" is controlled purely by the timeout arguments rather than
 * by wall-clock waiting: a {@link #IMMEDIATE zero timeout} makes any run of that status stalled, and a
 * {@link #NEVER 7-day timeout} makes nothing in this suite stalled. Assertions target the specific
 * seeded run's resulting status rather than global counts, so they are robust to execution order (the
 * reaper query is fleet-wide).
 * <p>
 * Because {@code reconcile} appends the {@code [System]} reason and only flips the row to
 * {@code ERROR} <em>after</em> that append completes, asserting the ERROR transition also proves the
 * log-sync step ran successfully.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OptimizationStalledReaperServiceTest {

    // Zero timeout => a run of the matched status is stalled the moment it exists.
    private static final Duration IMMEDIATE = Duration.ZERO;
    // Far longer than the age of any run seeded in this suite => never stalled.
    private static final Duration NEVER = Duration.ofDays(7);
    // Scan-floor margin; every seeded run is fresh so any positive value keeps it inside the lookback window.
    private static final Duration LOOKBACK_MARGIN = Duration.ofDays(7);
    private static final int BATCH_SIZE = 100;

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final GenericContainer<?> MINIO = MinIOContainerUtils.newMinIOContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER, MINIO).join();

        String minioUrl = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        APP = newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .authCacheTtlInSeconds(null)
                        .mockEventBus(Mockito.mock(EventBus.class))
                        .minioUrl(minioUrl)
                        .isMinIO(true)
                        .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private OptimizationResourceClient optimizationResourceClient;
    private OptimizationService optimizationService;
    private Injector injector;

    @BeforeAll
    void beforeAll(ClientSupport client, OptimizationService optimizationService, Injector injector) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        this.optimizationResourceClient = new OptimizationResourceClient(client, baseURI, podamFactory);
        this.optimizationService = optimizationService;
        this.injector = injector;

        mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE_NAME, WORKSPACE_ID, USER);
    }

    @Test
    @DisplayName("reaper job is constructible via Guice (@Config qualifier wiring)")
    void reaperJobIsInjectableFromGuice() {
        // The reaper is disabled/unscheduled in config-test.yml, but the bean is still bound — so this
        // proves the explicit @Inject constructor + @Config("optimizationStalledReaper") qualifier wire
        // correctly at boot (the Quartz/Guice fragility the explicit-ctor change guards against).
        assertThat(injector.getInstance(OptimizationStalledReaperJob.class)).isNotNull();
    }

    static Stream<Arguments> stalledRuns() {
        return Stream.of(
                // INITIALIZED matched by the initialized branch (running timeout kept long so only the
                // initialized branch can fire).
                arguments(OptimizationStatus.INITIALIZED, IMMEDIATE, NEVER),
                // RUNNING matched by the running branch (initialized timeout kept long).
                arguments(OptimizationStatus.RUNNING, NEVER, IMMEDIATE));
    }

    @ParameterizedTest
    @MethodSource("stalledRuns")
    @DisplayName("marks a stalled studio run as ERROR")
    void marksStalledStudioRunAsError(OptimizationStatus status, Duration initializedTimeout, Duration runningTimeout) {
        var id = seedStudioRun(status);

        long transitioned = reconcile(initializedTimeout, runningTimeout, BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.ERROR);
        assertThat(transitioned).isGreaterThanOrEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("stalledRuns")
    @DisplayName("leaves a studio run within its timeout untouched")
    void leavesRunWithinTimeoutUntouched(OptimizationStatus status, Duration ignoredInit, Duration ignoredRunning) {
        var id = seedStudioRun(status);

        // Both timeouts far exceed the run's age, so neither branch matches.
        reconcile(NEVER, NEVER, BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(status);
    }

    @Test
    @DisplayName("ignores a non-studio run even when stalled")
    void ignoresNonStudioRun() {
        // No studioConfig => studio_config = '' => skipped by the reaper's `studio_config != ''` filter,
        // even with a zero timeout that would otherwise mark an INITIALIZED run stalled.
        var id = seedRun(OptimizationStatus.INITIALIZED, null);

        reconcile(IMMEDIATE, IMMEDIATE, BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.INITIALIZED);
    }

    @Test
    @DisplayName("ignores a run in a terminal status")
    void ignoresTerminalRun() {
        // Terminal statuses are excluded by the reaper query. Zero timeouts prove the status filter
        // (not the age filter) is what protects the run.
        var id = seedStudioRun(OptimizationStatus.COMPLETED);

        reconcile(IMMEDIATE, IMMEDIATE, BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.COMPLETED);
    }

    @Test
    @DisplayName("returns zero when nothing is stalled")
    void returnsZeroWhenNothingStalled() {
        seedStudioRun(OptimizationStatus.INITIALIZED);

        // Timeouts far exceed the age of any run seeded across this suite, so nothing qualifies.
        long transitioned = reconcile(NEVER, NEVER, BATCH_SIZE);

        assertThat(transitioned).isZero();
    }

    @Test
    @DisplayName("respects the batch size limit per cycle")
    void respectsBatchSizeLimit() {
        seedStudioRun(OptimizationStatus.INITIALIZED);
        seedStudioRun(OptimizationStatus.INITIALIZED);

        // At least two INITIALIZED studio runs are stalled, but batchSize=1 caps the query (LIMIT 1)
        // to one transition per cycle.
        long transitioned = reconcile(IMMEDIATE, NEVER, 1);

        assertThat(transitioned).isEqualTo(1);
    }

    private long reconcile(Duration initializedTimeout, Duration runningTimeout, int batchSize) {
        Long transitioned = optimizationService
                .reconcileStalledStudioOptimizations(initializedTimeout, runningTimeout, LOOKBACK_MARGIN, batchSize)
                .block();
        assertThat(transitioned).isNotNull();
        return transitioned;
    }

    /**
     * Seeds a studio run (non-empty studio_config) in the requested status. New studio runs are always
     * created as INITIALIZED by the service, so RUNNING / terminal states are reached via the update
     * endpoint, which preserves studio_config.
     */
    private UUID seedStudioRun(OptimizationStatus status) {
        var id = seedRun(OptimizationStatus.INITIALIZED, studioConfig());
        switch (status) {
            case INITIALIZED -> {
            }
            case RUNNING -> transition(id, OptimizationStatus.RUNNING);
            case COMPLETED, CANCELLED, ERROR -> {
                transition(id, OptimizationStatus.RUNNING);
                transition(id, status);
            }
        }
        return id;
    }

    private UUID seedRun(OptimizationStatus status, OptimizationStudioConfig studioConfig) {
        var optimization = optimizationResourceClient.createPartialOptimization()
                .status(status)
                .studioConfig(studioConfig)
                .build();
        return optimizationResourceClient.upsert(optimization, API_KEY, TEST_WORKSPACE_NAME);
    }

    private void transition(UUID id, OptimizationStatus status) {
        optimizationResourceClient.update(id, OptimizationUpdate.builder().status(status).build(),
                API_KEY, TEST_WORKSPACE_NAME, 204);
    }

    private OptimizationStudioConfig studioConfig() {
        // opikApiKey is @JsonIgnore and populated server-side, so we omit it.
        return podamFactory.manufacturePojo(OptimizationStudioConfig.class).toBuilder()
                .opikApiKey(null)
                .build();
    }

    private OptimizationStatus statusOf(UUID id) {
        return optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200).status();
    }
}
