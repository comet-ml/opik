package com.comet.opik.domain;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.v1.jobs.OptimizationStalledReaperJob;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
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

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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

    // A second, unrelated workspace — cross-workspace isolation coverage for the liveness probe.
    private static final String OTHER_API_KEY = UUID.randomUUID().toString();
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String OTHER_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);

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
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;
    private OptimizationService optimizationService;
    private Injector injector;

    @BeforeAll
    void beforeAll(ClientSupport client, OptimizationService optimizationService, Injector injector) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        this.optimizationResourceClient = new OptimizationResourceClient(client, baseURI, podamFactory);
        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.optimizationService = optimizationService;
        this.injector = injector;

        mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE_NAME, WORKSPACE_ID, USER);
        mockTargetWorkspace(wireMock.server(), OTHER_API_KEY, OTHER_WORKSPACE_NAME, OTHER_WORKSPACE_ID, USER);
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

    @Test
    @DisplayName("records the stall reason as structured error_info, not only in the studio log")
    void recordsStallReasonAsErrorInfo() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        // The seed leaves errorInfo null, so everything asserted below is the reaper's own write.
        assertThat(optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200).errorInfo()).isNull();

        reconcile(NEVER, IMMEDIATE, BATCH_SIZE);

        var reaped = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
        assertThat(reaped.status()).isEqualTo(OptimizationStatus.ERROR);
        // The UI prefers error_info.message and only falls back to scraping the studio log, so a failed
        // log fetch must not hide a reason the platform knows exactly.
        assertThat(reaped.errorInfo()).isNotNull();
        assertThat(reaped.errorInfo().message())
                .startsWith("[System] Optimization failed")
                .contains("no progress");
        // Pin the exact constants: these are what the UI renders as the failure's type and stack, and
        // isNotBlank() would keep passing if either were replaced by any other string.
        assertThat(reaped.errorInfo().exceptionType()).isEqualTo("SystemDetectedFailure");
        assertThat(reaped.errorInfo().traceback())
                .isEqualTo("[System] No traceback: this failure was detected by the platform, not reported by "
                        + "the optimizer worker.");
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
    @DisplayName("keeps a running run alive on recent trial progress despite a stale row timestamp")
    void keepsRunningRunWithRecentTrialProgress() {
        var id = seedBackdatedRunningStudioRun(Duration.ofHours(1));
        createTrialExperiment(id);

        // The row timestamp (1h old) is far past the 5-minute running timeout, but the trial experiment
        // created just above is within it — progress-based liveness keeps the run alive (OPIK-7459).
        reconcile(NEVER, Duration.ofMinutes(5), BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.RUNNING);
    }

    @Test
    @DisplayName("reaps a running run whose latest trial is itself older than the timeout")
    void reapsRunningRunWhoseTrialProgressIsStale() {
        var id = seedBackdatedRunningStudioRun(Duration.ofHours(1));
        createTrialExperiment(id);

        // With a zero timeout even the just-created trial experiment already falls outside the window,
        // so a trial that stopped being followed by new ones cannot keep the run alive indefinitely.
        reconcile(NEVER, IMMEDIATE, BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.ERROR);
    }

    @Test
    @DisplayName("reaps a stale running run that never produced a trial")
    void reapsBackdatedRunningRunWithoutTrials() {
        var id = seedBackdatedRunningStudioRun(Duration.ofHours(1));

        // Same 5-minute timeout as the keeps-alive case above: the only difference is the absence of a
        // trial experiment, proving the anti-join is what decides.
        reconcile(NEVER, Duration.ofMinutes(5), BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.ERROR);
    }

    @Test
    @DisplayName("keeps a running run alive on item-level progress within a long trial")
    void keepsRunningRunAliveOnItemProgressWithinLongTrial() {
        var id = seedBackdatedRunningStudioRun(Duration.ofHours(1));

        // The shape of a long single trial: the trial experiment row is old (backdated an hour, far past
        // the 5-minute window), but items keep arriving as dataset items are evaluated. Only the
        // item-level branch of the liveness probe can keep this run alive.
        var experimentId = createBackdatedTrialExperiment(id, Duration.ofHours(1));
        createExperimentItem(experimentId);

        reconcile(NEVER, Duration.ofMinutes(5), BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.RUNNING);
    }

    @Test
    @DisplayName("reaps a stale running run even when other runs have recent trials")
    void reapsStaleRunDespiteOtherRunsProgress() {
        var staleId = seedBackdatedRunningStudioRun(Duration.ofHours(1));
        var healthyId = seedBackdatedRunningStudioRun(Duration.ofHours(1));
        createTrialExperiment(healthyId);
        // A regular (non-studio) experiment with no optimization_id must not register as anyone's progress.
        experimentResourceClient.create(experimentResourceClient.createPartialExperiment().build(),
                API_KEY, TEST_WORKSPACE_NAME);

        // The anti-join must correlate progress per run: the healthy run's fresh trial saves only itself.
        reconcile(NEVER, Duration.ofMinutes(5), BATCH_SIZE);

        assertThat(statusOf(staleId)).isEqualTo(OptimizationStatus.ERROR);
        assertThat(statusOf(healthyId)).isEqualTo(OptimizationStatus.RUNNING);
    }

    @Test
    @DisplayName("reaps a stale run whose id is referenced by another workspace's experiment")
    void reapsStaleRunDespiteForeignWorkspaceExperiment() {
        var staleId = seedBackdatedRunningStudioRun(Duration.ofHours(1));
        // optimization_id is stored without an existence check (see ExperimentService), so a client in
        // another workspace can write an experiment carrying this run's UUID. The liveness probe matches
        // (id, workspace_id) tuples, so foreign-workspace activity must never register as this run's
        // progress and keep a dead run spinning.
        experimentResourceClient.create(experimentResourceClient.createPartialExperiment()
                .optimizationId(staleId)
                .build(), OTHER_API_KEY, OTHER_WORKSPACE_NAME);

        reconcile(NEVER, Duration.ofMinutes(5), BATCH_SIZE);

        assertThat(statusOf(staleId)).isEqualTo(OptimizationStatus.ERROR);
    }

    @Test
    @DisplayName("keeps a stale INITIALIZED run alive on trial progress (a failed mark_running is not a dead run)")
    void keepsStaleInitializedRunWithRecentTrialProgress() {
        var id = seedBackdatedInitializedStudioRun(Duration.ofHours(1));
        createTrialExperiment(id);

        // An INITIALIZED run with trial rows means mark_running never landed while the worker went on doing
        // real work — a single failed callback, not a run that failed to start. Reaping it here used to
        // ERROR a healthy, actively-evaluating run and orphan its trials, so the progress veto covers
        // INITIALIZED too, on the runningTimeout window.
        reconcile(IMMEDIATE, NEVER, BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.INITIALIZED);
    }

    @Test
    @DisplayName("reaps a stale INITIALIZED run that never produced a trial")
    void reapsStaleInitializedRunWithoutTrials() {
        var id = seedBackdatedInitializedStudioRun(Duration.ofHours(1));

        // The common case the initializedTimeout exists for: no trials at all, so the progress probe finds
        // nothing and the last_updated_at branch still reaps it. Extending the veto must not weaken this.
        reconcile(IMMEDIATE, IMMEDIATE, BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.ERROR);
        // Pins the INITIALIZED branch of buildStalledReason. Without this, an inverted branch order
        // would diagnose this run as "no progress" and only the status assertion above would run.
        assertThat(reasonOf(id)).contains("failed to start");
    }

    @Test
    @DisplayName("reaps an INITIALIZED run past the hard ceiling despite fresh trial progress")
    void reapsInitializedRunPastHardCapDespiteProgress() {
        var id = seedStudioRun(OptimizationStatus.INITIALIZED);
        createTrialExperiment(id);
        backdateRunStart(id, Duration.ofHours(2));

        // The ceiling had to grow an INITIALIZED branch along with the veto: otherwise a zombie worker
        // writing rows for a run whose mark_running never landed would keep the spinner alive forever,
        // which is the one guarantee this job must never lose.
        reconcile(NEVER, NEVER, Duration.ofHours(1), BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.ERROR);
        // The ceiling branch must win over the INITIALIZED one: telling the user this run "failed to
        // start" would be wrong — it started and kept writing trials.
        assertThat(reasonOf(id))
                .contains("exceeded the maximum running time")
                .doesNotContain("failed to start");
    }

    @Test
    @DisplayName("reaps a stale run whose unfinished-trace items break the heavyweight GET query")
    void reapsStaleRunDespiteUnmappableGetById() {
        var id = seedBackdatedRunningStudioRun(Duration.ofHours(1));
        var experimentId = createTrialExperiment(id);
        // An item referencing a still-unfinished trace is exactly what a worker killed mid-trial leaves
        // behind — it used to make the full GET/FIND query drop the run (fixed by FIND's NaN guards; see
        // OptimizationsResourceTest). The reaper's re-read must stay off that query regardless, so a
        // future FIND regression can never make it skip such a run on every cycle and resurrect the
        // eternal spinner.
        createExperimentItem(experimentId, false);

        reconcile(NEVER, IMMEDIATE, BATCH_SIZE);

        assertThat(statusSnapshotOf(id)).isEqualTo(OptimizationStatus.ERROR);
    }

    @Test
    @DisplayName("reaps a running run past the hard ceiling despite fresh trial progress")
    void reapsRunningRunPastHardCapDespiteProgress() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        createTrialExperiment(id);
        // Started 2h ago, but the row itself was written a moment ago — the state a metadata PATCH or an
        // SDK re-upsert leaves behind. The ceiling used to be measured from last_updated_at, so such a
        // write postponed the backstop indefinitely and the spinner never died (review: baz-reviewer).
        backdateRunStart(id, Duration.ofHours(2));

        // Fresh trial progress AND a fresh row timestamp: the progress branch is satisfied and the
        // no-progress branch cannot fire, so only the 1h ceiling can reap this run.
        reconcile(NEVER, Duration.ofMinutes(5), Duration.ofHours(1), BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.ERROR);
        // The ceiling branch must also win over the RUNNING one, and must quote the ceiling's own
        // duration — "no progress for over 5 minutes" would be both the wrong cause and the wrong number.
        assertThat(reasonOf(id))
                .contains("exceeded the maximum running time")
                .doesNotContain("no progress");
    }

    @Test
    @DisplayName("re-upsert preserves the run's creation time")
    void reUpsertPreservesCreatedAt() {
        var optimization = optimizationResourceClient.createPartialOptimization()
                .studioConfig(studioConfig())
                .build();
        var id = optimizationResourceClient.upsert(optimization, API_KEY, TEST_WORKSPACE_NAME);
        var createdAt = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200).createdAt();

        optimizationResourceClient.upsert(optimization.toBuilder().id(id).build(), API_KEY, TEST_WORKSPACE_NAME);

        // The upsert is a full-row replace, so without carrying created_at over the column DEFAULT
        // re-stamps it on every write — which both lies about when the run started and lets the hard
        // ceiling (measured from it) drift forward forever.
        assertThat(optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200).createdAt())
                .isEqualTo(createdAt);
    }

    @Test
    @DisplayName("restarting a finished run resets its creation time and clears the old failure")
    void restartFromTerminalStatusResetsCreatedAtAndErrorInfo() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        // Reap it, so the row carries a terminal status and a platform-written errorInfo.
        reconcile(NEVER, IMMEDIATE, BATCH_SIZE);
        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.ERROR);
        var reaped = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
        assertThat(reaped.errorInfo()).isNotNull();
        // The first attempt started well beyond any ceiling we will configure below.
        backdateRunStart(id, Duration.ofHours(48));

        // Reusing the id with a non-terminal status is a RESTART, which the SDK does on job redelivery.
        // Same datasetName as the original on purpose: optimizations is ORDER BY (workspace_id,
        // dataset_id, id), so a different dataset would write a second row the dedup never merges.
        // createdAt/lastUpdatedAt are left null so this models what the SDK actually sends.
        optimizationResourceClient.upsert(optimizationResourceClient.createPartialOptimization()
                .id(id)
                .datasetName(reaped.datasetName())
                .name(reaped.name())
                .status(OptimizationStatus.RUNNING)
                .studioConfig(studioConfig())
                .errorInfo(null)
                .createdAt(null)
                .lastUpdatedAt(null)
                .build(), API_KEY, TEST_WORKSPACE_NAME);

        var restarted = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
        // Inheriting the first attempt's clock would put the new attempt past the ceiling the moment it
        // starts: isPastHardCap short-circuits the activity veto, so it would be reaped on the next tick
        // no matter how much progress it writes.
        assertThat(restarted.createdAt()).isAfter(Instant.now().minus(Duration.ofHours(1)));
        // And the previous attempt's failure must not be pinned on it — nothing else ever clears the field.
        assertThat(restarted.errorInfo()).isNull();

        reconcile(NEVER, NEVER, Duration.ofHours(24), BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.RUNNING);
    }

    /**
     * The pre-update liveness guard is unreachable through the reaper tests by construction: every
     * "still alive" case is filtered out by the fleet query's anti-join before the Java-side veto in
     * OptimizationService#isStillDead can fire, so the guard never executes in those tests. It is the
     * thing standing between a slow-but-alive trial straddling the window boundary and a wrongful ERROR,
     * so it is asserted directly against the DAO here.
     */
    @Test
    @DisplayName("liveness probe sees a trial experiment created inside the window")
    void hasRecentStudioActivitySeesRecentTrial() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        createTrialExperiment(id);

        assertThat(hasRecentActivity(id, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("updating a trial experiment does not re-stamp its created_at into the liveness window")
    void updatingTrialExperimentDoesNotRefreshLiveness() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        var experimentId = createBackdatedTrialExperiment(id, Duration.ofHours(1));
        assertThat(hasRecentActivity(id, Duration.ofMinutes(5))).isFalse();

        // ExperimentDAO.UPDATE_BY_ID must carry created_at forward rather than let the column DEFAULT
        // re-stamp it. That property is what makes the trial timestamp a liveness clock, and it was only
        // recorded in prose — a regression re-stamping it here would ship green and pin every dead studio
        // run alive until the hard ceiling.
        experimentResourceClient.updateExperiment(experimentId,
                ExperimentUpdate.builder().name("renamed-after-the-window").build(),
                API_KEY, TEST_WORKSPACE_NAME, 204);

        assertThat(hasRecentActivity(id, Duration.ofMinutes(5))).isFalse();

        // And the end-to-end consequence, not only the probe: the run must still be reaped. A re-stamped
        // trial created_at would veto this and keep every dead run alive until the hard ceiling.
        reconcile(NEVER, IMMEDIATE, BATCH_SIZE);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.ERROR);
    }

    @Test
    @DisplayName("liveness probe sees an item created inside the window under a trial older than it")
    void hasRecentStudioActivitySeesRecentItemUnderOldTrial() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        // The long-single-trial shape: the trial row itself is far outside the window, only the
        // per-dataset-item writes are recent. This is the branch that lets the running timeout be minutes.
        var experimentId = createBackdatedTrialExperiment(id, Duration.ofHours(1));
        createExperimentItem(experimentId);

        assertThat(hasRecentActivity(id, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("liveness probe reports no activity when the only trial is older than the window")
    void hasRecentStudioActivityIgnoresTrialOutsideWindow() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        createBackdatedTrialExperiment(id, Duration.ofHours(1));

        assertThat(hasRecentActivity(id, Duration.ofMinutes(5))).isFalse();
    }

    @Test
    @DisplayName("liveness probe ignores another workspace's experiment carrying the same run id")
    void hasRecentStudioActivityIgnoresForeignWorkspaceActivity() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        // optimization_id is stored without an existence check, so a client in another workspace can
        // write an experiment carrying this run's UUID. The probe's workspace scoping is load-bearing:
        // foreign activity must never veto a reap.
        experimentResourceClient.create(experimentResourceClient.createPartialExperiment()
                .optimizationId(id)
                .build(), OTHER_API_KEY, OTHER_WORKSPACE_NAME);

        assertThat(hasRecentActivity(id, Duration.ofMinutes(5))).isFalse();
    }

    @Test
    @DisplayName("raw row fallback reads a run whose unfinished-trace items break the heavyweight GET")
    void getRowByIdReadsRunUnmappableByGetById() {
        var id = seedStudioRun(OptimizationStatus.RUNNING);
        var experimentId = createTrialExperiment(id);
        // An item pointing at a still-unfinished trace is what a worker killed mid-trial leaves behind —
        // the state that used to make the aggregating FIND drop the run entirely. The raw-row read is the
        // defense-in-depth fallback for both the status update and the upsert path, so it must return the
        // run regardless of what the aggregates would do.
        createExperimentItem(experimentId, false);

        var optimization = injector.getInstance(OptimizationDAO.class)
                .getRowById(id)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER))
                .block();

        assertThat(optimization).isNotNull();
        assertThat(optimization.id()).isEqualTo(id);
        assertThat(optimization.status()).isEqualTo(OptimizationStatus.RUNNING);
        // The fallback carries no aggregates — that is the documented tradeoff for FIND-independence.
        assertThat(optimization.numTrials()).isNull();
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
        return reconcile(initializedTimeout, runningTimeout, NEVER, batchSize);
    }

    private long reconcile(Duration initializedTimeout, Duration runningTimeout, Duration runningHardTimeout,
            int batchSize) {
        Long transitioned = optimizationService
                .reconcileStalledStudioOptimizations(initializedTimeout, runningTimeout, runningHardTimeout,
                        LOOKBACK_MARGIN, batchSize)
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
                // createPartialOptimization only nulls scores/costs/studioConfig, so Podam otherwise
                // seeds a random non-blank ErrorInfo that the upsert persists. Every "the reaper wrote
                // an ErrorInfo" assertion would then pass on the fixture value instead of on anything
                // the reaper did.
                .errorInfo(null)
                .build();
        return optimizationResourceClient.upsert(optimization, API_KEY, TEST_WORKSPACE_NAME);
    }

    private void transition(UUID id, OptimizationStatus status) {
        optimizationResourceClient.update(id, OptimizationUpdate.builder().status(status).build(),
                API_KEY, TEST_WORKSPACE_NAME, 204);
    }

    /**
     * Seeds a RUNNING studio run whose latest row version's {@code last_updated_at} is backdated by
     * {@code age}, so the row timestamp alone reads as stale. The update endpoint always stamps
     * {@code now} and the create path forces INITIALIZED for new studio runs, so neither can produce
     * this state — instead the run is upserted twice with the same id: the create (backdated a minute
     * further, forced INITIALIZED) and a RUNNING re-upsert whose backdated timestamp stays the newest
     * version for the reaper's {@code argMax} dedup.
     */
    private UUID seedBackdatedRunningStudioRun(Duration age) {
        var optimization = optimizationResourceClient.createPartialOptimization()
                .studioConfig(studioConfig())
                .lastUpdatedAt(Instant.now().minus(age).minus(Duration.ofMinutes(1)))
                .build();
        var id = optimizationResourceClient.upsert(optimization, API_KEY, TEST_WORKSPACE_NAME);
        optimizationResourceClient.upsert(optimization.toBuilder()
                .id(id)
                .status(OptimizationStatus.RUNNING)
                .lastUpdatedAt(Instant.now().minus(age))
                .build(), API_KEY, TEST_WORKSPACE_NAME);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.RUNNING);
        return id;
    }

    /**
     * Seeds an INITIALIZED studio run whose {@code last_updated_at} is backdated by {@code age}. One upsert
     * is enough here — the create path forces INITIALIZED for new studio runs anyway — so unlike
     * {@link #seedBackdatedRunningStudioRun(Duration)} there is no second version to out-rank.
     */
    private UUID seedBackdatedInitializedStudioRun(Duration age) {
        var optimization = optimizationResourceClient.createPartialOptimization()
                .studioConfig(studioConfig())
                .lastUpdatedAt(Instant.now().minus(age))
                .build();
        var id = optimizationResourceClient.upsert(optimization, API_KEY, TEST_WORKSPACE_NAME);

        assertThat(statusOf(id)).isEqualTo(OptimizationStatus.INITIALIZED);
        return id;
    }

    /**
     * Clones the run's latest row version with a backdated {@code created_at} and a fresh
     * {@code last_updated_at} — "started long ago, but something wrote the row a moment ago", the only
     * shape the hard ceiling is supposed to catch. Neither timestamp is settable through the API (create
     * stamps {@code created_at} server-side, the update endpoint stamps {@code last_updated_at}), and
     * cloning the row inside ClickHouse keeps the helper from re-plumbing every column. The new version
     * carries the newest {@code last_updated_at}, so it wins the reaper's {@code argMax} dedup while
     * {@code min(created_at)} picks up the backdated start.
     */
    private void backdateRunStart(UUID id, Duration startedAgo) {
        var startedAt = ClickHouseDateTimeFormat.formatNanos(Instant.now().minus(startedAgo));
        var sql = ("INSERT INTO %s.optimizations (id, dataset_id, name, workspace_id, project_id, "
                + "objective_name, status, metadata, studio_config, error_info, created_by, last_updated_by, "
                + "created_at, last_updated_at) "
                + "SELECT id, dataset_id, name, workspace_id, project_id, objective_name, status, metadata, "
                + "studio_config, error_info, created_by, last_updated_by, "
                + "toDateTime64('%s', 9, 'UTC'), now64(6) "
                + "FROM %s.optimizations WHERE id = '%s' AND workspace_id = '%s' "
                + "ORDER BY last_updated_at DESC LIMIT 1")
                .formatted(DATABASE_NAME, startedAt, DATABASE_NAME, id, WORKSPACE_ID);
        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("");
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to backdate run start", e);
        }
    }

    /** Creates a trial experiment linked to the run — the progress signal the reaper's liveness reads. */
    private UUID createTrialExperiment(UUID optimizationId) {
        var experiment = experimentResourceClient.createPartialExperiment()
                .optimizationId(optimizationId)
                .build();
        return experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE_NAME);
    }

    /**
     * Inserts a trial experiment row straight into ClickHouse with a backdated {@code created_at}. The
     * API always stamps {@code created_at} server-side, and the reaper probe reads {@code experiments}
     * without FINAL — so a second, backdated version of an API-created row would not hide the fresh
     * one. A single raw row is the only way to seed an "old trial" deterministically, without
     * {@code Thread.sleep}-ing the suite past the reaper window.
     */
    private UUID createBackdatedTrialExperiment(UUID optimizationId, Duration age) {
        var experiment = experimentResourceClient.createPartialExperiment()
                .optimizationId(optimizationId)
                .build();
        // Bind a canonical UTC string, not a LocalDateTime: created_at is DateTime64(9, 'UTC') and
        // setObject would leave the wire format to the driver's timezone/precision handling, so the
        // backdating (and with it the whole liveness window this test pins) would drift with the JVM
        // default timezone (review: baz-reviewer). ClickHouseDateTimeFormat is the shared helper for
        // exactly this literal form.
        var createdAt = ClickHouseDateTimeFormat.formatNanos(Instant.now().minus(age));
        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("");
                var statement = connection.prepareStatement(
                        ("INSERT INTO %s.experiments (workspace_id, dataset_id, id, name, optimization_id, "
                                + "created_at, last_updated_at, created_by, last_updated_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").formatted(DATABASE_NAME))) {
            statement.setString(1, WORKSPACE_ID);
            statement.setString(2, experiment.datasetId().toString());
            statement.setString(3, experiment.id().toString());
            statement.setString(4, experiment.name());
            statement.setString(5, optimizationId.toString());
            statement.setString(6, createdAt);
            statement.setString(7, createdAt);
            statement.setString(8, USER);
            statement.setString(9, USER);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to seed backdated trial experiment", e);
        }
        return experiment.id();
    }

    /**
     * Appends one experiment item to a trial — the per-dataset-item progress signal within a trial. The
     * item's trace is created for real first, matching the worker, which always logs the trace before
     * the item.
     */
    private void createExperimentItem(UUID experimentId) {
        createExperimentItem(experimentId, true);
    }

    private void createExperimentItem(UUID experimentId, boolean traceFinished) {
        var traceBuilder = podamFactory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null);
        if (!traceFinished) {
            traceBuilder.endTime(null).duration(null);
        }
        var traceId = traceResourceClient.createTrace(traceBuilder.build(), API_KEY, TEST_WORKSPACE_NAME);
        var item = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                .experimentId(experimentId)
                .traceId(traceId)
                .feedbackScores(null)
                .build();
        experimentResourceClient.createExperimentItem(Set.of(item), API_KEY, TEST_WORKSPACE_NAME);
    }

    /**
     * The bare status re-read the reaper itself uses. reapsStaleRunDespiteUnmappableGetById asserts
     * through it to prove the reaper path works independently of the heavyweight GET/FIND mapping.
     */
    private OptimizationStatus statusSnapshotOf(UUID id) {
        var snapshot = injector.getInstance(OptimizationDAO.class)
                .getStatusSnapshotById(id)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER))
                .block();
        assertThat(snapshot).isNotNull();
        return snapshot.status();
    }

    /**
     * The reason the reaper recorded, as the UI reads it. buildStalledReason has three mutually
     * exclusive branches (hard ceiling / RUNNING no-progress / INITIALIZED failed-to-start) and asserting
     * only the resulting status cannot tell them apart.
     */
    private String reasonOf(UUID id) {
        var errorInfo = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200).errorInfo();
        assertThat(errorInfo).isNotNull();
        return errorInfo.message();
    }

    /** The pre-update liveness guard the reaper consults before writing ERROR. */
    private boolean hasRecentActivity(UUID id, Duration window) {
        var active = injector.getInstance(OptimizationDAO.class)
                .hasRecentStudioActivity(id, window)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER))
                .block();
        assertThat(active).isNotNull();
        return active;
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
