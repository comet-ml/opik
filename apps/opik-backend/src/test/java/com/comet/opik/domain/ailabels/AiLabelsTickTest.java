package com.comet.opik.domain.ailabels;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.v1.jobs.AiLabelsJob;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.lock.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * The tick against real ClickHouse (post-cutover {@code traces}, legacy {@code spans}), MySQL and Redis, with
 * opik-inference stubbed in WireMock. Tests call {@link AiLabelsTickService#tick(Instant)} directly; the scheduled job
 * fires once at startup and then only every hour.
 *
 * <p>Tasks: {@code trace_summary} (the bundled generate task, applying from 2020) and {@code agent_outcome}, a decide
 * task of two questions, so each trace costs 3 engine calls: 2 in a decide job and 1 in a generate job.
 */
@DisplayName("ai-labels tick")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AiLabelsTickTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final String DECIDE_PATH = "/v1/batch/decide";
    private static final String GENERATE_PATH = "/v1/batch/generate";
    private static final String JOB_PATH = "/v1/batch/jobs/";
    private static final String STATUS_PATH = "/v1/batch/jobs/status";
    private static final String DECIDE_TASK = "agent_outcome";
    private static final String GENERATE_TASK = "trace_summary";
    private static final Set<String> TASKS = Set.of(DECIDE_TASK, GENERATE_TASK);
    // Two decide questions and one generate output.
    private static final int LABELS_PER_TRACE = 3;
    private static final String DECIDE_YAML = """
            name: agent_outcome
            endpoint: decide
            cache: false
            version: 1
            applies_from: 2020-01-01T00:00:00Z
            questions:
              succeeded: {type: noul, instructions: "Did the agent accomplish the user's goal?"}
              topic: {type: choice, instructions: "Main topic?", criteria: {billing: null, technical: null, other: null}}
            """;
    private static final String WIDE_TASK = "wide_outcome";
    private static final int WIDE_QUESTIONS = 31;
    // With agent_outcome's 2 questions, 33 in all: more than one decide item holds, so each trace is split in two.
    private static final String WIDE_YAML = """
            name: wide_outcome
            endpoint: decide
            cache: false
            version: 1
            applies_from: 2020-01-01T00:00:00Z
            questions:
            """ + IntStream.range(0, WIDE_QUESTIONS)
            .mapToObj(i -> "  q%02d: {type: noul, instructions: \"Check %d?\"}\n".formatted(i, i))
            .collect(Collectors.joining());
    // The sweep cadence is 1 s, counted from the previous tick's end, by which its sweep lock was taken. The extra half
    // second covers clock drift between the lock and the test.
    private static final Duration TICK_GAP = Duration.ofMillis(1_500);
    // Wide enough that the tick asserting "not yet retried" lands inside D even on a slow runner.
    private static final Duration RETRY_DELAY = Duration.ofSeconds(8);
    // ClickHouse stamps updated_at with its own clock, which can drift from ours.
    private static final Duration CLOCK_MARGIN = Duration.ofSeconds(1);

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    // Not reused: the poller, the budget and the opt-in read whole tables, and the traces cutover is destructive.
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, zookeeper, clickHouse).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysql);
        try {
            PostCutoverTraces.migrate(clickHouse);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                        new CustomConfig("aiLabels.enabled", "true"),
                        new CustomConfig("aiLabels.inference.url", wireMock.server().baseUrl()),
                        new CustomConfig("aiLabels.inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tasksDir", writeTasks(Map.of(DECIDE_TASK, DECIDE_YAML)).toString()),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.tickBudget", "30s"),
                        new CustomConfig("aiLabels.sweepInterval", "1s"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("aiLabels.quietPeriod", "0s"),
                        new CustomConfig("aiLabels.retry.delay", RETRY_DELAY.toSeconds() + "s"),
                        new CustomConfig("aiLabels.sweep.buckets", "1")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private LockService lockService;
    private GuiceJobManager jobManager;
    private AiLabelsTickService tickService;
    private InferenceClient client;
    private AiLabelSweepService sweepService;
    private AiLabelTaskRegistry registry;
    private AiLabelRenderer renderer;
    private AiLabelItemBuilder itemBuilder;
    private AiLabelResultMapper resultMapper;
    private AiLabelStatusDAO statusDAO;
    private AiLabelsDAO labelsDAO;
    private Connection clickHouseConnection;

    private Instant lastTick = Instant.EPOCH;
    private Instant lastTickEnd = Instant.EPOCH;
    private long defaultBudget;
    private int defaultChunkTraces;
    private long defaultRenderBudgetBytes;
    private long defaultMinItemBytes;
    private long defaultPerCallBytes;
    private long defaultMaxJobBytes;
    private io.dropwizard.util.Duration defaultTickBudget;
    private io.dropwizard.util.Duration defaultReadTimeout;
    // What the status call reports for each job: absent is running, and a lost job is left out.
    private final Map<String, String> jobStatuses = new ConcurrentHashMap<>();
    private final Set<String> lostJobs = ConcurrentHashMap.newKeySet();

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template, @Jit LockService lockService,
            GuiceJobManager jobManager, @Jit AiLabelsTickService tickService, @Jit InferenceClient client,
            @Jit AiLabelSweepService sweepService, @Jit AiLabelTaskRegistry registry, @Jit AiLabelRenderer renderer,
            @Jit AiLabelItemBuilder itemBuilder, @Jit AiLabelResultMapper resultMapper,
            @Jit AiLabelStatusDAO statusDAO, @Jit AiLabelsDAO labelsDAO) throws SQLException {
        this.opikConfig = opikConfig;
        this.template = template;
        this.lockService = lockService;
        this.jobManager = jobManager;
        this.tickService = tickService;
        this.client = client;
        this.sweepService = sweepService;
        this.registry = registry;
        this.renderer = renderer;
        this.itemBuilder = itemBuilder;
        this.resultMapper = resultMapper;
        this.statusDAO = statusDAO;
        this.labelsDAO = labelsDAO;
        this.clickHouseConnection = clickHouse.createConnection("");
        this.defaultBudget = config().getBudgetBytes();
        this.defaultChunkTraces = config().getChunkTraces();
        this.defaultMaxJobBytes = config().getMaxJobBytes();
        this.defaultRenderBudgetBytes = config().getRenderBudgetBytes();
        this.defaultMinItemBytes = config().getMinItemBytes();
        this.defaultPerCallBytes = config().getPerCallBytes();
        this.defaultTickBudget = config().getTickBudget();
        this.defaultReadTimeout = config().getInference().getReadTimeout();

        // The job fires once at startup: wait for that tick to release the leader lock, so it can't race the tests.
        lockService.bestEffortLock(new LockService.Lock("ai_labels:leader"), Mono.just(true), Mono.empty(),
                Duration.ofSeconds(5), Duration.ofSeconds(60)).block();
        lastTick = Instant.now();
        lastTickEnd = lastTick;
    }

    @AfterAll
    void afterAll() throws SQLException {
        clickHouseConnection.close();
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE");
            handle.execute("DELETE FROM ai_label_jobs");
            handle.execute("DELETE FROM ai_label_job_items");
            return null;
        });
        jobStatuses.clear();
        lostJobs.clear();
        wireMock.server().resetAll();
        stubSubmit(DECIDE_PATH);
        stubSubmit(GENERATE_PATH);
    }

    @AfterEach
    void tearDown() {
        config().setBudgetBytes(defaultBudget);
        config().setChunkTraces(defaultChunkTraces);
        config().setRenderBudgetBytes(defaultRenderBudgetBytes);
        config().setMinItemBytes(defaultMinItemBytes);
        config().setPerCallBytes(defaultPerCallBytes);
        config().setMaxJobBytes(defaultMaxJobBytes);
        config().setTickBudget(defaultTickBudget);
        config().getInference().setReadTimeout(defaultReadTimeout);
        config().getSweep().setBuckets(1);
    }

    @Test
    @DisplayName("enabled, the tick job is scheduled every tickInterval")
    void jobIsScheduled() throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var jobKeys = new ArrayList<JobKey>();
        for (var key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            if (scheduler.getJobDetail(key).getJobClass() == AiLabelsJob.class) {
                jobKeys.add(key);
            }
        }

        assertThat(jobKeys).singleElement().satisfies(key -> assertThat(scheduler.getTriggersOfJob(key))
                .singleElement()
                .isInstanceOfSatisfying(SimpleTrigger.class, trigger -> assertThat(trigger.getRepeatInterval())
                        .isEqualTo(Duration.ofHours(1).toMillis())));
    }

    @Test
    @DisplayName("end to end: submit the expected items, load paged results with a repeat, then sweep finds nothing")
    void endToEnd() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var traceIds = List.of(trace(workspaceId, projectId, 3), trace(workspaceId, projectId, 0),
                trace(workspaceId, projectId, 1));

        tick();

        var expectedStates = render(workspaceId, projectId, traceIds, registry.get(DECIDE_TASK));
        var decide = submits(DECIDE_PATH, workspaceId);
        assertThat(decide).singleElement().satisfies(body -> {
            assertThat(body.path("org_id").asText()).isEqualTo(workspaceId);
            assertThat(body.path("cache").asBoolean()).isFalse();
            assertThat(items(body)).extracting(item -> item.path("item_id").asText())
                    .containsExactlyInAnyOrderElementsOf(traceIds.stream().map(UUID::toString).toList());
            assertThat(items(body)).allSatisfy(item -> {
                assertThat(fieldNames(item.path("questions")))
                        .containsExactly("agent_outcome.succeeded", "agent_outcome.topic");
                assertThat(AiLabelRenderer.canonicalJson(item.path("state")))
                        .isEqualTo(expectedStates.get(UUID.fromString(item.path("item_id").asText())));
            });
        });
        var summary = registry.get(GENERATE_TASK);
        var generate = submits(GENERATE_PATH, workspaceId);
        assertThat(generate).singleElement().satisfies(body -> {
            assertThat(body.path("org_id").asText()).isEqualTo(workspaceId);
            assertThat(items(body)).extracting(item -> item.path("item_id").asText())
                    .containsExactlyInAnyOrderElementsOf(traceIds.stream()
                            .map(traceId -> traceId + ":" + GENERATE_TASK).toList());
            assertThat(items(body)).allSatisfy(item -> {
                var traceId = UUID.fromString(item.path("item_id").asText().split(":")[0]);
                assertThat(item.path("messages").get(0).path("content").asText())
                        .isEqualTo(summary.prompt().system());
                // No thread: the history is empty, and the state is fitted with it.
                assertThat(item.path("messages").get(1).path("content").asText())
                        .isEqualTo(summary.prompt().user().replace(AiLabelTask.HISTORY_PLACEHOLDER, "[]")
                                .replace(AiLabelTask.STATE_PLACEHOLDER, expectedStates.get(traceId)));
                assertThat(item.path("schema")).isEqualTo(summary.schema());
                assertThat(item.path("max_tokens").asInt()).isEqualTo(64);
            });
        });

        var jobs = jobs(workspaceId);
        var cost = traceCost(workspaceId, projectId, traceIds.getFirst());
        assertThat(jobs).extracting(JobRow::endpoint, JobRow::status, JobRow::itemCount, JobRow::bytes)
                .containsExactlyInAnyOrder(tuple("decide", "submitted", 3, 3 * cost.get(AiLabelEndpoint.DECIDE)),
                        tuple("generate", "submitted", 3, 3 * cost.get(AiLabelEndpoint.GENERATE)));
        var jobIdByTask = Map.of(DECIDE_TASK, job(jobs, "decide").jobId(), GENERATE_TASK,
                job(jobs, "generate").jobId());
        // In flight in MySQL only: nothing is written to ClickHouse at submit.
        assertThat(statuses(workspaceId, traceIds)).isEmpty();
        assertThat(inFlight(workspaceId)).hasSize(6).allSatisfy(item -> {
            assertThat(item.jobId()).isEqualTo(jobIdByTask.get(item.task()));
            assertThat(item.attempt()).isOne();
            assertThat(item.taskVersion()).isOne();
            assertThat(item.rendererVersion()).isEqualTo(AiLabelRenderer.RENDERER_VERSION);
        });

        completeJob(job(jobs, "decide"), List.of(traceIds.stream().map(AiLabelsTickTest::decideResult).toList()));
        // The service's cursor can repeat an item across pages; it is loaded once.
        completeJob(job(jobs, "generate"), List.of(
                List.of(generateResult(traceIds.get(0)), generateResult(traceIds.get(1))),
                List.of(generateResult(traceIds.get(1)), generateResult(traceIds.get(2)))));
        tick();

        // One status call for both jobs.
        assertThat(statusCalls()).last().satisfies(body -> assertThat(body.path("job_ids"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(jobIdByTask.get(DECIDE_TASK), jobIdByTask.get(GENERATE_TASK)));
        assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
        assertThat(inFlight(workspaceId)).isEmpty();
        assertThat(statuses(workspaceId, traceIds)).hasSize(6).allSatisfy(status -> {
            assertThat(status.status()).isEqualTo(AiLabelStatus.Status.DONE);
            assertThat(status.attempt()).isOne();
            assertThat(status.jobId()).isEqualTo(jobIdByTask.get(status.task()));
        });
        var expectedLabels = traceIds.stream()
                .flatMap(traceId -> List.of(
                        tuple(traceId, DECIDE_TASK, "succeeded", AiLabel.AnswerType.NOUL, null, ""),
                        tuple(traceId, DECIDE_TASK, "topic", AiLabel.AnswerType.CHOICE, "billing", ""),
                        tuple(traceId, GENERATE_TASK, "output", AiLabel.AnswerType.OUTPUT, null,
                                "{\"summary\":\"summary of " + traceId + "\"}"))
                        .stream())
                .toList();
        assertThat(labels(workspaceId, traceIds))
                .extracting(AiLabel::traceId, AiLabel::task, AiLabel::key, AiLabel::answerType, AiLabel::choice,
                        AiLabel::output)
                .containsExactlyInAnyOrderElementsOf(expectedLabels);
        assertThat(labelRowCount(workspaceId)).isEqualTo(expectedLabels.size());

        tick();

        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
        assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
    }

    @Test
    @DisplayName("history: the turns of one thread in one chunk each get the earlier ones")
    void threadTurnsInOneChunkSeeEachOther() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var turns = IntStream.rangeClosed(1, 3).mapToObj(turn -> trace(workspaceId, projectId, 0, "thread-1"))
                .toList();

        tick();

        var turnJson = "{\"input\":\"{\\\"question\\\":\\\"where is my refund?\\\"}\","
                + "\"output\":\"{\\\"answer\\\":\\\"it is on its way\\\"}\"}";
        var expectedHistory = Map.of(turns.get(0), "[]", turns.get(1), "[" + turnJson + "]",
                turns.get(2), "[" + turnJson + "," + turnJson + "]");
        var summary = registry.get(GENERATE_TASK);
        var states = render(workspaceId, projectId, turns, summary);
        assertThat(submits(GENERATE_PATH, workspaceId)).singleElement().satisfies(body -> assertThat(items(body))
                .hasSize(3)
                .allSatisfy(item -> {
                    var traceId = UUID.fromString(item.path("item_id").asText().split(":")[0]);
                    assertThat(item.path("messages").get(1).path("content").asText()).isEqualTo(summary.prompt()
                            .user().replace(AiLabelTask.HISTORY_PLACEHOLDER, expectedHistory.get(traceId))
                            .replace(AiLabelTask.STATE_PLACEHOLDER, states.get(traceId)));
                }));
    }

    @Test
    @DisplayName("budget: whole traces are admitted up to the budget, and loading frees it for the next tick")
    void budget() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var traceIds = IntStream.range(0, 4).mapToObj(i -> trace(workspaceId, projectId, 1)).toList();
        // The traces render alike, so each one costs the same: its decide and generate items together.
        long cost = traceCost(workspaceId, projectId, traceIds.getFirst()).values().stream()
                .mapToLong(Long::longValue).sum();
        // Room for 3 traces and half of a fourth.
        long budget = 3 * cost + cost / 2;
        config().setBudgetBytes(budget);

        tick();

        assertThat(submits(DECIDE_PATH, workspaceId)).singleElement()
                .satisfies(body -> assertThat(items(body)).hasSize(3));
        assertThat(submits(GENERATE_PATH, workspaceId)).singleElement()
                .satisfies(body -> assertThat(items(body)).hasSize(3));
        assertThat(inFlightBytes()).isEqualTo(3 * cost);
        var firstAdmitted = inFlight(workspaceId).stream().map(AiLabelJobItem::traceId).collect(Collectors.toSet());

        tick();

        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
        assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);

        completeJobs(workspaceId);
        tick();

        var lastTrace = traceIds.stream().filter(traceId -> !firstAdmitted.contains(traceId)).toList();
        assertThat(lastTrace).hasSize(1);
        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(2).last()
                .satisfies(body -> assertThat(itemIds(body)).containsExactly(lastTrace.getFirst().toString()));
        assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(2);
        assertThat(inFlightBytes()).isEqualTo(cost);
    }

    @Test
    @DisplayName("a full budget skips the sweep until loading frees room")
    void fullBudgetSkipsTheSweep(@Jit AiLabelSweepDAO sweepDAO) {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var first = trace(workspaceId, projectId, 1);
        long cost = traceCost(workspaceId, projectId, first).values().stream().mapToLong(Long::longValue).sum();
        config().setBudgetBytes(cost);
        var sweeps = new AtomicInteger();
        var service = tickService(template, statusDAO, registry,
                new AiLabelSweepService(countingSweeps(sweepDAO, sweeps), template, config()));

        tick(service);

        assertThat(sweeps).hasValue(1);
        assertThat(inFlightBytes()).isEqualTo(cost);
        var second = trace(workspaceId, projectId, 1);

        tick(service);

        assertThat(sweeps).hasValue(1);
        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
        // The skipped sweep leaves the last one's gauges in place, and this tick had no workspaces with candidates.
        assertThat(service.lastSweep()).isEqualTo(new AiLabelsTickService.SweepObservation(1, 1));
        assertThat(service.lastTickWorkspaces()).isZero();

        completeJobs(workspaceId);
        tick(service);

        assertThat(sweeps).hasValue(2);
        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(2).last()
                .satisfies(body -> assertThat(itemIds(body)).containsExactly(second.toString()));
    }

    @Test
    @DisplayName("fairness: workspaces share the budget in one tick, whatever their order or size")
    void fairness() {
        // Every item at the floor, so every trace costs the same: 2 items.
        config().setMinItemBytes(8 * 1024);
        long cost = 2 * 8 * 1024;
        config().setBudgetBytes(7 * cost);
        var big = enabledWorkspace();
        var small = enabledWorkspace();
        var bigProject = UUID.randomUUID();
        var smallProject = UUID.randomUUID();
        IntStream.range(0, 12).forEach(i -> trace(big, bigProject, 0));
        var smallTraces = List.of(trace(small, smallProject, 0), trace(small, smallProject, 0));

        tick();

        // In either order, the small workspace's 2 traces go out and the big one takes the other 5.
        assertThat(inFlight(small)).extracting(AiLabelJobItem::traceId).containsOnlyElementsOf(smallTraces)
                .hasSize(4);
        assertThat(inFlight(big).stream().map(AiLabelJobItem::traceId).distinct()).hasSize(5);
        assertThat(inFlightBytes()).isEqualTo(7 * cost);
        // Turns render about what fits their share: one job per job key, however many turns.
        assertThat(submits(DECIDE_PATH, big)).hasSize(1);
        assertThat(submits(GENERATE_PATH, big)).hasSize(1);
    }

    @Test
    @DisplayName("one trace id in two projects of a workspace: never twice in one job, and each project's is labeled")
    void traceIdInTwoProjects() {
        var workspaceId = enabledWorkspace();
        var traceId = trace(workspaceId, UUID.randomUUID(), 0);
        trace(workspaceId, UUID.randomUUID(), 0, "", traceId);

        tick();

        assertThat(submits(DECIDE_PATH, workspaceId)).singleElement()
                .satisfies(body -> assertThat(itemIds(body)).containsExactly(traceId.toString()));
        assertThat(submits(GENERATE_PATH, workspaceId)).singleElement()
                .satisfies(body -> assertThat(itemIds(body)).containsExactly(traceId + ":" + GENERATE_TASK));

        // Settled is judged per project, so the copy goes out once the first loads, in a job of its own.
        completeJobs(workspaceId);
        tick();

        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(2)
                .allSatisfy(body -> assertThat(itemIds(body)).containsExactly(traceId.toString()));

        completeJobs(workspaceId);
        tick();
        tick();

        assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(2);
        assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
    }

    @Test
    @DisplayName("429: only that workspace backs off, until Retry-After; the others proceed")
    void throttled() {
        var throttled = enabledWorkspace();
        var other = enabledWorkspace();
        var throttledTrace = trace(throttled, UUID.randomUUID(), 0);
        var otherTrace = trace(other, UUID.randomUUID(), 0);
        // Wide enough that the second tick can't cross it on a slow runner.
        var retryAfter = Duration.ofSeconds(8);
        var stubs = List.of(stubQuotaExceeded(DECIDE_PATH, throttled, retryAfter),
                stubQuotaExceeded(GENERATE_PATH, throttled, retryAfter));

        tick();
        var throttledAt = Instant.now();

        // The submit isn't retried, and the workspace's other job isn't tried.
        assertThat(submits(DECIDE_PATH, throttled)).hasSize(1);
        assertThat(submits(GENERATE_PATH, throttled)).isEmpty();
        assertThat(inFlight(throttled)).isEmpty();
        assertThat(inFlight(other)).extracting(AiLabelJobItem::traceId).containsOnly(otherTrace).hasSize(2);

        tick();

        assertThat(submits(DECIDE_PATH, throttled)).hasSize(1);
        assertThat(inFlight(throttled)).isEmpty();

        stubs.forEach(wireMock.server()::removeStub);
        pauseUntil(throttledAt.plus(retryAfter));
        tick();

        assertThat(submits(DECIDE_PATH, throttled)).hasSize(2);
        assertThat(submits(GENERATE_PATH, throttled)).hasSize(1);
        assertThat(inFlight(throttled)).extracting(AiLabelJobItem::traceId).containsOnly(throttledTrace).hasSize(2);
    }

    @Test
    @DisplayName("sweep buckets: a guard trip skips only its own bucket, and the first bucket swept rotates")
    void guardTripSkipsOnlyItsBucket(@Jit AiLabelSweepDAO sweepDAO) {
        config().getSweep().setBuckets(2);
        var tripped = workspaceInBucket(0, 2);
        var swept = workspaceInBucket(1, 2);
        enable(tripped);
        enable(swept);
        var trippedTrace = trace(tripped, UUID.randomUUID(), 0);
        var sweptTrace = trace(swept, UUID.randomUUID(), 0);
        var sweptBuckets = new ArrayList<Integer>();
        var guardedDAO = spy(sweepDAO);
        doAnswer(invocation -> {
            List<String> bucket = invocation.getArgument(0);
            sweptBuckets.add(invocation.getArgument(2));
            return bucket.contains(tripped)
                    ? Flux.error(new IllegalStateException("Code: 158. DB::Exception: Limit for rows (controlled by "
                            + "'max_rows_to_read' setting) exceeded. (TOO_MANY_ROWS)"))
                    : invocation.callRealMethod();
        }).when(guardedDAO).sweep(anyList(), anyList(), anyInt(), anyCollection());
        var service = tickService(template, statusDAO, registry,
                new AiLabelSweepService(guardedDAO, template, config()));

        tick(service);

        // Bucket 0 trips first, and bucket 1 is still swept and submitted in the same tick.
        assertThat(sweptBuckets).containsExactly(0, 1);
        assertThat(inFlight(swept)).extracting(AiLabelJobItem::traceId).containsOnly(sweptTrace).hasSize(2);
        assertThat(inFlight(tripped)).isEmpty();
        assertThat(submits(DECIDE_PATH, tripped)).isEmpty();
        // The tripped bucket was still reached: its query ran, and the guard stopped it.
        assertThat(service.lastSweep()).isEqualTo(new AiLabelsTickService.SweepObservation(1, 2));
        assertThat(service.lastTickWorkspaces()).isEqualTo(1);

        tick(service);

        assertThat(sweptBuckets).containsExactly(0, 1, 1, 0);
    }

    @Nested
    @DisplayName("failures")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Failures {

        @Test
        @DisplayName("a retryable item failure comes back after D, up to X attempts; a non-retryable one is terminal")
        void itemFailures() {
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var retried = trace(workspaceId, projectId, 0);
            var terminal = trace(workspaceId, projectId, 0);
            Function<UUID, ObjectNode> failGenerate = traceId -> errorResult(traceId + ":" + GENERATE_TASK,
                    traceId.equals(retried) ? "deadline_exceeded" : "input_too_long", traceId.equals(retried));

            tick();
            completeJobs(workspaceId, failGenerate);
            tick();
            var firstFailureAt = Instant.now();

            assertThat(statuses(workspaceId, List.of(retried, terminal)))
                    .extracting(AiLabelStatus::traceId, AiLabelStatus::task, AiLabelStatus::status,
                            AiLabelStatus::retryable, AiLabelStatus::attempt, AiLabelStatus::errorCode)
                    .containsExactlyInAnyOrder(
                            tuple(retried, DECIDE_TASK, AiLabelStatus.Status.DONE, false, 1, ""),
                            tuple(retried, GENERATE_TASK, AiLabelStatus.Status.FAILED, true, 1, "deadline_exceeded"),
                            tuple(terminal, DECIDE_TASK, AiLabelStatus.Status.DONE, false, 1, ""),
                            tuple(terminal, GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1, "input_too_long"));

            tick();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);

            pauseUntil(firstFailureAt.plus(RETRY_DELAY).plus(CLOCK_MARGIN));
            tick();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(2).last()
                    .satisfies(body -> assertThat(itemIds(body)).containsExactly(retried + ":" + GENERATE_TASK));
            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
            assertThat(inFlight(workspaceId)).extracting(AiLabelJobItem::traceId, AiLabelJobItem::task,
                    AiLabelJobItem::attempt).containsExactly(tuple(retried, GENERATE_TASK, 2));

            completeJobs(workspaceId, failGenerate);
            tick();
            var secondFailureAt = Instant.now();

            assertThat(status(workspaceId, retried, GENERATE_TASK))
                    .extracting(AiLabelStatus::status, AiLabelStatus::retryable, AiLabelStatus::attempt)
                    .containsExactly(AiLabelStatus.Status.FAILED, true, 2);

            pauseUntil(secondFailureAt.plus(RETRY_DELAY).plus(CLOCK_MARGIN));
            tick();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(2);
        }

        @Test
        @DisplayName("left out of the status call: the job's items become retryable job_lost, and come back after D")
        void jobLost() {
            var workspaceId = enabledWorkspace();
            var traceId = trace(workspaceId, UUID.randomUUID(), 0);

            tick();
            jobs(workspaceId).forEach(job -> lostJobs.add(job.jobId()));
            tick();
            var lostAt = Instant.now();

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::status, AiLabelStatus::retryable, AiLabelStatus::attempt,
                            AiLabelStatus::errorCode)
                    .containsOnly(tuple(AiLabelStatus.Status.FAILED, true, 1, AiLabelsTickService.JOB_LOST));

            pauseUntil(lostAt.plus(RETRY_DELAY).plus(CLOCK_MARGIN));
            tick();

            assertThat(inFlight(workspaceId)).extracting(AiLabelJobItem::attempt).containsOnly(2).hasSize(2);
            assertThat(jobs(workspaceId)).extracting(JobRow::status)
                    .containsExactlyInAnyOrder("loaded", "loaded", "submitted", "submitted");
        }

        @Test
        @DisplayName("400 on submit: every pair in that job is terminal invalid_request; other jobs go ahead")
        void submitRejected() {
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var traceIds = List.of(trace(workspaceId, projectId, 0), trace(workspaceId, projectId, 0));
            wireMock.server().stubFor(post(urlEqualTo(GENERATE_PATH))
                    .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId)))
                    .willReturn(error(400, "invalid_request")));

            tick();
            var rejectedAt = Instant.now();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
            assertThat(jobs(workspaceId)).extracting(JobRow::endpoint).containsExactly("decide");
            assertThat(inFlight(workspaceId)).extracting(AiLabelJobItem::task).containsOnly(DECIDE_TASK).hasSize(2);
            assertThat(statuses(workspaceId, traceIds))
                    .extracting(AiLabelStatus::task, AiLabelStatus::status, AiLabelStatus::retryable,
                            AiLabelStatus::attempt, AiLabelStatus::errorCode)
                    .containsExactlyInAnyOrder(
                            tuple(GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1, "invalid_request"),
                            tuple(GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1, "invalid_request"));

            pauseUntil(rejectedAt.plus(RETRY_DELAY).plus(CLOCK_MARGIN));
            tick();

            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
        }

        @Test
        @DisplayName("a trace over the render budget is terminal render_over_budget, and never submitted")
        void renderOverBudget() {
            config().setRenderBudgetBytes(10);
            var workspaceId = enabledWorkspace();
            var traceId = trace(workspaceId, UUID.randomUUID(), 1);

            tick();

            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::task, AiLabelStatus::status, AiLabelStatus::retryable,
                            AiLabelStatus::attempt, AiLabelStatus::errorCode, AiLabelStatus::compactionLevel)
                    .containsExactlyInAnyOrder(
                            tuple(DECIDE_TASK, AiLabelStatus.Status.FAILED, false, 1,
                                    AiLabelsTickService.RENDER_OVER_BUDGET, AiLabelsTickService.OVER_BUDGET_LEVEL),
                            tuple(GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1,
                                    AiLabelsTickService.RENDER_OVER_BUDGET, AiLabelsTickService.OVER_BUDGET_LEVEL));
            assertThat(submits(DECIDE_PATH, workspaceId)).isEmpty();
            assertThat(submits(GENERATE_PATH, workspaceId)).isEmpty();
        }

        @Test
        @DisplayName("items too large for any job are terminal item_too_large, and never submitted")
        void itemTooLargeForAnyJob() {
            config().setMaxJobBytes(4_096 + 100);
            var workspaceId = enabledWorkspace();
            var traceId = trace(workspaceId, UUID.randomUUID(), 1);

            tick();
            tick();

            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::task, AiLabelStatus::status, AiLabelStatus::retryable,
                            AiLabelStatus::attempt, AiLabelStatus::errorCode)
                    .containsExactlyInAnyOrder(
                            tuple(DECIDE_TASK, AiLabelStatus.Status.FAILED, false, 1,
                                    AiLabelsTickService.ITEM_TOO_LARGE),
                            tuple(GENERATE_TASK, AiLabelStatus.Status.FAILED, false, 1,
                                    AiLabelsTickService.ITEM_TOO_LARGE));
            assertThat(submits(DECIDE_PATH, workspaceId)).isEmpty();
            assertThat(submits(GENERATE_PATH, workspaceId)).isEmpty();
            assertThat(jobs(workspaceId)).isEmpty();
        }

        @Test
        @DisplayName("a submit failing with the service down drops the tick's other jobs; the next tick resubmits")
        void submitFailed() {
            var workspaceId = enabledWorkspace();
            var traceId = trace(workspaceId, UUID.randomUUID(), 0);
            var stubs = List.of(DECIDE_PATH, GENERATE_PATH).stream()
                    .map(path -> wireMock.server().stubFor(post(urlEqualTo(path))
                            .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId)))
                            .willReturn(error(503, "overloaded"))))
                    .toList();

            tick();

            // The client's two retries of the first job, then the tick's other job isn't tried.
            assertThat(submits(DECIDE_PATH, workspaceId).size() + submits(GENERATE_PATH, workspaceId).size())
                    .isEqualTo(3);
            assertThat(jobs(workspaceId)).isEmpty();
            assertThat(statuses(workspaceId, List.of(traceId))).isEmpty();

            stubs.forEach(wireMock.server()::removeStub);
            tick();

            assertThat(jobs(workspaceId)).hasSize(2);
            assertThat(inFlight(workspaceId)).extracting(AiLabelJobItem::attempt).containsOnly(1).hasSize(2);
        }

        @Test
        @DisplayName("a hung submit is cut at the tick's deadline, so the tick never outlives the leader lease")
        void hungSubmitEndsTheTickOnTime() {
            config().setTickBudget(io.dropwizard.util.Duration.seconds(3));
            config().getInference().setReadTimeout(io.dropwizard.util.Duration.seconds(2));
            var workspaceId = enabledWorkspace();
            trace(workspaceId, UUID.randomUUID(), 0);
            List.of(DECIDE_PATH, GENERATE_PATH).forEach(path -> wireMock.server().stubFor(post(urlEqualTo(path))
                    .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId)))
                    .willReturn(aResponse().withStatus(202).withFixedDelay(20_000))));
            var service = tickService(template, statusDAO, registry, sweepService);

            tick(service);
            var took = Duration.between(lastTick, Instant.now());

            // Sends may run one read timeout past the 3 s budget: against 3 attempts of 20 s per job without it.
            assertThat(took).isLessThan(Duration.ofSeconds(7));
            // One job's attempts, cut at the send deadline; with the service down, the other job isn't tried.
            var decides = submits(DECIDE_PATH, workspaceId).size();
            var generates = submits(GENERATE_PATH, workspaceId).size();
            assertThat(Math.min(decides, generates)).isZero();
            assertThat(decides + generates).isBetween(1, 3);
            assertThat(jobs(workspaceId)).isEmpty();
        }

        @Test
        @DisplayName("a chunk whose read fails renders trace by trace until the deadline, and what's rendered is sent")
        void renderFallbackStopsAtTheDeadline() {
            config().setTickBudget(io.dropwizard.util.Duration.seconds(3));
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var traceIds = IntStream.range(0, 6).mapToObj(i -> trace(workspaceId, projectId, 0)).toList();
            var slowRenderer = spy(renderer);
            doAnswer(invocation -> {
                List<TraceRef> refs = invocation.getArgument(1);
                if (refs.size() > 1) {
                    return Flux.error(new IllegalStateException("the chunk's read failed"));
                }
                return Mono.delay(Duration.ofSeconds(1)).thenMany(Flux.defer(() -> {
                    try {
                        return (Flux<?>) invocation.callRealMethod();
                    } catch (Throwable exception) {
                        return Flux.error(exception);
                    }
                }));
            }).when(slowRenderer).render(anyString(), anyList(), anyCollection());
            var service = new AiLabelsTickService(config(), template, client, lockService, sweepService, registry,
                    slowRenderer, itemBuilder, resultMapper, statusDAO, labelsDAO);

            tick(service);
            var took = Duration.between(lastTick, Instant.now());

            // 6 traces at 1 s each against a 3 s budget: the ones rendered in time still go out after it.
            assertThat(took).isLessThan(Duration.ofSeconds(6));
            var sent = inFlight(workspaceId).stream().map(AiLabelJobItem::traceId).collect(Collectors.toSet());
            assertThat(sent).hasSizeBetween(1, 5);
            // The rest have no failure, so the next sweep finds them.
            assertThat(statuses(workspaceId, traceIds)).isEmpty();

            tick();

            assertThat(inFlight(workspaceId).stream().map(AiLabelJobItem::traceId).distinct())
                    .containsExactlyInAnyOrderElementsOf(traceIds);
        }

        @Test
        @DisplayName("an item a terminal job never reports is retryable missing_result")
        void missingResult() {
            var workspaceId = enabledWorkspace();
            var projectId = UUID.randomUUID();
            var reported = trace(workspaceId, projectId, 0);
            var unreported = trace(workspaceId, projectId, 0);

            tick();
            var jobs = jobs(workspaceId);
            completeJob(job(jobs, "decide"), List.of(List.of(decideResult(reported))));
            completeJob(job(jobs, "generate"),
                    List.of(List.of(generateResult(reported), generateResult(unreported))));
            tick();

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(status(workspaceId, reported, DECIDE_TASK).status()).isEqualTo(AiLabelStatus.Status.DONE);
            assertThat(status(workspaceId, unreported, DECIDE_TASK))
                    .extracting(AiLabelStatus::status, AiLabelStatus::retryable, AiLabelStatus::attempt,
                            AiLabelStatus::errorCode)
                    .containsExactly(AiLabelStatus.Status.FAILED, true, 1, AiLabelsTickService.MISSING_RESULT);
            assertThat(status(workspaceId, unreported, GENERATE_TASK).status()).isEqualTo(AiLabelStatus.Status.DONE);
        }
    }

    @Nested
    @DisplayName("crash points")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CrashPoints {

        @Test
        @DisplayName("after the 202, before the job row: the next sweep resubmits, and the orphan is never read")
        void crashBeforeJobRow() {
            var workspaceId = enabledWorkspace();
            var traceIds = List.of(trace(workspaceId, UUID.randomUUID(), 1));
            var crashingTemplate = spy(template);
            doThrow(new IllegalStateException("crash")).when(crashingTemplate)
                    .inTransaction(same(WRITE), any(TxAction.class));

            tick(tickService(crashingTemplate, statusDAO));

            // Each job fails on its own: the other one is still submitted.
            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
            assertThat(jobs(workspaceId)).isEmpty();
            assertThat(statuses(workspaceId, traceIds)).isEmpty();

            tick();

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(2);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(2);
            assertConverges(workspaceId, traceIds);
        }

        @Test
        @DisplayName("the job row and its items commit together: a failure after both inserts leaves neither")
        void jobAndItemsCommitTogether() {
            var workspaceId = enabledWorkspace();
            var traceIds = List.of(trace(workspaceId, UUID.randomUUID(), 1));
            var crashingTemplate = spy(template);
            doAnswer(invocation -> template.inTransaction(WRITE, handle -> {
                invocation.<TxAction<?>>getArgument(1).execute(handle);
                throw new IllegalStateException("crash");
            })).when(crashingTemplate).inTransaction(same(WRITE), any(TxAction.class));

            tick(tickService(crashingTemplate, statusDAO));

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
            assertThat(jobs(workspaceId)).isEmpty();
            assertThat(inFlight(workspaceId)).isEmpty();

            tick();

            assertConverges(workspaceId, traceIds);
        }

        @Test
        @DisplayName("labels written, status rows not: the job is loaded again and converges")
        void crashBetweenLabelsAndStatus() {
            var workspaceId = enabledWorkspace();
            var traceIds = List.of(trace(workspaceId, UUID.randomUUID(), 1), trace(workspaceId, UUID.randomUUID(), 0));

            tick();
            completeJobs(workspaceId);
            tick(tickService(template, failingInsertsOf(AiLabelStatus.Status.DONE)));

            assertThat(labels(workspaceId, traceIds)).hasSize(6);
            assertThat(statuses(workspaceId, traceIds)).isEmpty();
            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("submitted");
            assertThat(inFlight(workspaceId)).hasSize(4);

            tick();

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(statuses(workspaceId, traceIds)).hasSize(4)
                    .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.DONE));
            assertThat(labels(workspaceId, traceIds)).hasSize(6);
            assertThat(labelRowCount(workspaceId)).isEqualTo(12);

            tick();

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
        }

        @Test
        @DisplayName("a load that fails partway writes nothing of that job; the next tick loads it whole")
        void failedLoadWritesNothing() {
            var tasksConfig = new AiLabelsConfig();
            tasksConfig.setEnabled(true);
            tasksConfig.setTasksDir(writeTasks(Map.of(DECIDE_TASK, DECIDE_YAML, WIDE_TASK, WIDE_YAML)).toString());
            var service = tickService(template, statusDAO, new AiLabelTaskRegistry(tasksConfig), sweepService);
            var workspaceId = enabledWorkspace();
            var traceId = trace(workspaceId, UUID.randomUUID(), 0);

            tick(service);
            var jobs = jobs(workspaceId);
            var decide = job(jobs, "decide");
            assertThat(submits(DECIDE_PATH, workspaceId)).singleElement()
                    .satisfies(body -> assertThat(itemIds(body)).containsExactly(traceId + ":p0", traceId + ":p1"));
            completeJob(job(jobs, "generate"), List.of(List.of(generateResult(traceId))));
            completeJob(decide, List.of(List.of(decideResult(traceId + ":p0")), List.of(wideResult(traceId + ":p1"))));
            // The second page fails once, with an error the client doesn't retry, so the first load stops after :p0.
            var secondPage = urlEqualTo(JOB_PATH + decide.jobId() + "?cursor=1");
            wireMock.server().stubFor(get(secondPage).inScenario("interrupted").whenScenarioStateIs(STARTED)
                    .willReturn(json(400, InferenceJson.MAPPER.createObjectNode().set("error",
                            InferenceJson.MAPPER.createObjectNode().put("code", "invalid_request")
                                    .put("message", "test").put("retryable", false))))
                    .willSetStateTo("recovered"));
            wireMock.server().stubFor(get(secondPage).inScenario("interrupted").whenScenarioStateIs("recovered")
                    .willReturn(json(200, jobView(decide.jobId(), "decide", "completed",
                            List.of(wideResult(traceId + ":p1")), null))));

            tick(service);

            // The generate job loaded; the decide job wrote nothing, not even its first page, and is still in flight.
            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::task, AiLabelStatus::status)
                    .containsExactly(tuple(GENERATE_TASK, AiLabelStatus.Status.DONE));
            assertThat(jobs(workspaceId)).extracting(JobRow::endpoint, JobRow::status)
                    .containsExactlyInAnyOrder(tuple("decide", "submitted"), tuple("generate", "loaded"));

            tick(service);

            assertThat(jobs(workspaceId)).extracting(JobRow::status).containsOnly("loaded");
            assertThat(statuses(workspaceId, List.of(traceId)))
                    .extracting(AiLabelStatus::task, AiLabelStatus::status, AiLabelStatus::attempt,
                            AiLabelStatus::errorCode)
                    .containsExactlyInAnyOrder(
                            tuple(DECIDE_TASK, AiLabelStatus.Status.DONE, 1, ""),
                            tuple(WIDE_TASK, AiLabelStatus.Status.DONE, 1, ""),
                            tuple(GENERATE_TASK, AiLabelStatus.Status.DONE, 1, ""));
            assertThat(labels(workspaceId, List.of(traceId))).filteredOn(label -> label.task().equals(WIDE_TASK))
                    .hasSize(WIDE_QUESTIONS);

            tick(service);

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(1);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(1);
        }

        /** Completes whatever is in flight, loads it, and checks a further sweep finds nothing. */
        private void assertConverges(String workspaceId, List<UUID> traceIds) {
            completeJobs(workspaceId);
            tick();

            assertThat(statuses(workspaceId, traceIds)).hasSize(traceIds.size() * TASKS.size())
                    .allSatisfy(status -> assertThat(status.status()).isEqualTo(AiLabelStatus.Status.DONE));
            assertThat(labels(workspaceId, traceIds)).hasSize(traceIds.size() * LABELS_PER_TRACE);

            var decideSubmits = submits(DECIDE_PATH, workspaceId).size();
            var generateSubmits = submits(GENERATE_PATH, workspaceId).size();
            tick();

            assertThat(submits(DECIDE_PATH, workspaceId)).hasSize(decideSubmits);
            assertThat(submits(GENERATE_PATH, workspaceId)).hasSize(generateSubmits);
        }

        private AiLabelStatusDAO failingInsertsOf(AiLabelStatus.Status status) {
            var crashing = spy(statusDAO);
            doAnswer(invocation -> {
                List<AiLabelStatus> rows = invocation.getArgument(0);
                return rows.stream().anyMatch(row -> row.status() == status)
                        ? Mono.error(new IllegalStateException("crash"))
                        : invocation.callRealMethod();
            }).when(crashing).insert(anyList());
            return crashing;
        }
    }

    @Test
    @DisplayName("leader: of two jobs contending on the leader lock, only one ticks")
    void leader() throws InterruptedException {
        var contended = mock(AiLabelsTickService.class);
        when(contended.leaderLease()).thenReturn(Duration.ofSeconds(30));
        var ticks = new AtomicInteger();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            ticks.incrementAndGet();
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(contended).tick(any());
        var first = new AiLabelsJob(config(), contended, lockService);
        var second = new AiLabelsJob(config(), contended, lockService);

        first.doJob(null);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        second.doJob(null);

        Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> ticks.get() == 1);

        release.countDown();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            second.doJob(null);
            assertThat(ticks.get()).isGreaterThanOrEqualTo(2);
        });
    }

    private AiLabelsConfig config() {
        return opikConfig.getAiLabels();
    }

    private AiLabelsTickService tickService(TransactionTemplate template, AiLabelStatusDAO statusDAO) {
        return tickService(template, statusDAO, registry, sweepService);
    }

    private AiLabelsTickService tickService(TransactionTemplate template, AiLabelStatusDAO statusDAO,
            AiLabelTaskRegistry registry, AiLabelSweepService sweepService) {
        return new AiLabelsTickService(config(), template, client, lockService, sweepService, registry, renderer,
                itemBuilder, resultMapper, statusDAO, labelsDAO);
    }

    private static AiLabelSweepDAO countingSweeps(AiLabelSweepDAO sweepDAO, AtomicInteger sweeps) {
        var counting = spy(sweepDAO);
        doAnswer(invocation -> {
            sweeps.incrementAndGet();
            return invocation.callRealMethod();
        }).when(counting).sweep(anyList(), anyList(), anyInt(), anyCollection());
        return counting;
    }

    private void tick() {
        tick(tickService);
    }

    private void tick(AiLabelsTickService service) {
        pauseUntil(lastTickEnd.plus(TICK_GAP));
        stubStatus();
        lastTick = Instant.now();
        try {
            service.tick(lastTick);
        } finally {
            lastTickEnd = Instant.now();
        }
    }

    private static void pauseUntil(Instant instant) {
        var wait = Duration.between(Instant.now(), instant);
        if (wait.isPositive()) {
            Mono.delay(wait).block();
        }
    }

    // --- Fixtures ---

    private static Path writeTasks(Map<String, String> decideTasks) {
        try (var bundled = AiLabelsTickTest.class.getClassLoader()
                .getResourceAsStream(AiLabelTaskLoader.CLASSPATH_DIRECTORY + "/trace_summary.yaml")) {
            var summary = new String(bundled.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceFirst("(?m)^applies_from: .*$", "applies_from: 2020-01-01T00:00:00Z");
            var directory = Files.createTempDirectory("ai-labels-tasks");
            Files.writeString(directory.resolve("trace_summary.yaml"), summary);
            for (var task : decideTasks.entrySet()) {
                Files.writeString(directory.resolve(task.getKey() + ".yaml"), task.getValue());
            }
            return directory;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String enabledWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        enable(workspaceId);
        return workspaceId;
    }

    private static String workspaceInBucket(int bucket, int buckets) {
        return Stream.generate(() -> UUID.randomUUID().toString())
                .filter(workspaceId -> AiLabelSweepService.bucketOf(workspaceId, buckets) == bucket)
                .findFirst()
                .orElseThrow();
    }

    private void enable(String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
    }

    /** An ended trace, created a minute ago, with {@code spans} spans. Inserted into the shard to read back at once. */
    private UUID trace(String workspaceId, UUID projectId, int spans) {
        return trace(workspaceId, projectId, spans, "");
    }

    private UUID trace(String workspaceId, UUID projectId, int spans, String threadId) {
        return trace(workspaceId, projectId, spans, threadId, ID_GENERATOR.generateId());
    }

    private UUID trace(String workspaceId, UUID projectId, int spans, String threadId, UUID traceId) {
        execute("""
                INSERT INTO %s.%s (workspace_id, project_id, id, name, start_time, end_time, input, output, metadata,
                    thread_id, created_at, last_updated_at, is_deleted)
                VALUES ('%s', '%s', '%s', 'support chat', now64(6) - toIntervalSecond(60),
                    now64(6) - toIntervalSecond(2), '{"question":"where is my refund?"}',
                    '{"answer":"it is on its way"}', '{"channel":"web"}', '%s', now64(6) - toIntervalSecond(60),
                    now64(6) - toIntervalSecond(1), 0)
                """.formatted(DATABASE_NAME, PostCutoverTraces.SHARD, workspaceId, projectId, traceId, threadId));
        for (int i = 0; i < spans; i++) {
            execute("""
                    INSERT INTO %s.spans (id, workspace_id, project_id, trace_id, name, type, start_time, end_time,
                        input, output)
                    VALUES ('%s', '%s', '%s', '%s', 'step %d', 'llm', now64(9) - toIntervalSecond(%d),
                        now64(9) - toIntervalSecond(%d), '{"prompt":"step %d"}', '{"text":"done %d"}')
                    """.formatted(DATABASE_NAME, ID_GENERATOR.generateId(), workspaceId, projectId, traceId, i,
                    50 - i, 40 - i, i, i));
        }
        return traceId;
    }

    private void execute(String sql) {
        try (var statement = clickHouseConnection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The canonical rendered state of each trace, as the items of {@code task} must carry it. */
    private Map<UUID, String> render(String workspaceId, UUID projectId, List<UUID> traceIds, AiLabelTask task) {
        var refs = traceIds.stream().map(traceId -> new TraceRef(projectId, traceId, Instant.EPOCH)).toList();
        return renderer.render(workspaceId, refs, List.of(task))
                .cast(RenderOutcome.Rendered.class)
                .collectMap(rendered -> rendered.trace().traceId(),
                        rendered -> AiLabelRenderer.canonicalJson(rendered.state()))
                .block();
    }

    // --- opik-inference stubs ---

    private void stubSubmit(String path) {
        wireMock.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"job_id\":\"job-{{randomValue type='UUID'}}\",\"items\":1}")
                .withTransformers("response-template")));
    }

    private StubMapping stubQuotaExceeded(String path, String workspaceId, Duration retryAfter) {
        return wireMock.server().stubFor(post(urlEqualTo(path))
                .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId)))
                .willReturn(error(429, "quota_exceeded")
                        .withHeader("Retry-After", String.valueOf(retryAfter.toSeconds()))));
    }

    /** Completes every submitted job of the workspace with a success for each of its items. */
    private void completeJobs(String workspaceId) {
        completeJobs(workspaceId, null);
    }

    /** As {@link #completeJobs(String)}, with {@code generateResults} answering the generate items. */
    private void completeJobs(String workspaceId, Function<UUID, ObjectNode> generateResults) {
        for (var job : jobs(workspaceId)) {
            if (!job.status().equals("submitted")) {
                continue;
            }
            var traceIds = jobTraceIds(job);
            var results = traceIds.stream()
                    .map(traceId -> switch (job.endpoint()) {
                        case "decide" -> decideResult(traceId);
                        default -> generateResults == null ? generateResult(traceId) : generateResults.apply(traceId);
                    })
                    .toList();
            completeJob(job, List.of(results));
        }
    }

    /**
     * The status call's answer for every submitted job, from {@link #jobStatuses} and {@link #lostJobs}, stubbed just
     * before each tick: the tick polls exactly the jobs submitted before it.
     */
    private void stubStatus() {
        var submitted = template.inTransaction(READ_ONLY, handle -> handle
                .createQuery("SELECT job_id, endpoint FROM ai_label_jobs WHERE status = 'submitted'")
                .map((rs, ctx) -> Map.entry(rs.getString("job_id"), rs.getString("endpoint")))
                .list());
        var body = InferenceJson.MAPPER.createObjectNode();
        var views = body.putArray("jobs");
        submitted.stream()
                .filter(job -> !lostJobs.contains(job.getKey()))
                .forEach(job -> views.add(jobView(job.getKey(), job.getValue(),
                        jobStatuses.getOrDefault(job.getKey(), "running"), List.of(), null)));
        wireMock.server().stubFor(post(urlEqualTo(STATUS_PATH)).willReturn(json(200, body)));
    }

    /** Marks the job completed, its results served as {@code pages} with cursors 0, 1, ... */
    private void completeJob(JobRow job, List<List<ObjectNode>> pages) {
        var path = JOB_PATH + job.jobId();
        jobStatuses.put(job.jobId(), "completed");
        for (int page = 0; page < pages.size(); page++) {
            Integer next = page + 1 < pages.size() ? page + 1 : null;
            wireMock.server().stubFor(get(urlEqualTo(path + "?cursor=" + page))
                    .willReturn(json(200, jobView(job.jobId(), job.endpoint(), "completed", pages.get(page), next))));
        }
    }

    private static ObjectNode jobView(String jobId, String endpoint, String status, List<ObjectNode> results,
            Integer nextCursor) {
        var view = InferenceJson.MAPPER.createObjectNode()
                .put("job_id", jobId)
                .put("endpoint", endpoint)
                .put("status", status)
                .put("cancel_requested", false);
        view.putObject("counts").put("pending", 0).put("ok", results.size()).put("failed", 0).put("cancelled", 0);
        view.putArray("results").addAll(results);
        if (nextCursor == null) {
            view.putNull("next_cursor");
        } else {
            view.put("next_cursor", nextCursor);
        }
        return view;
    }

    private static ObjectNode decideResult(UUID traceId) {
        return decideResult(traceId.toString());
    }

    private static ObjectNode decideResult(String itemId) {
        var result = itemResponse(itemId);
        var answers = ((ObjectNode) result.path("response").path("result")).putObject("answers");
        answers.putObject("agent_outcome.succeeded").put("type", "noul").put("noul", 0.75);
        var topic = answers.putObject("agent_outcome.topic").put("type", "choice").put("choice", "billing")
                .put("confidence", 0.5);
        topic.putObject("probabilities").put("billing", 0.5).put("technical", 0.25).put("other", 0.25);
        return result;
    }

    private static ObjectNode wideResult(String itemId) {
        var result = itemResponse(itemId);
        var answers = ((ObjectNode) result.path("response").path("result")).putObject("answers");
        IntStream.range(0, WIDE_QUESTIONS).forEach(i -> answers.putObject("%s.q%02d".formatted(WIDE_TASK, i))
                .put("type", "noul").put("noul", 0.5));
        return result;
    }

    private static ObjectNode generateResult(UUID traceId) {
        var result = itemResponse(traceId + ":" + GENERATE_TASK);
        ((ObjectNode) result.path("response").path("result")).putObject("output")
                .put("summary", "summary of " + traceId);
        return result;
    }

    private static ObjectNode itemResponse(String itemId) {
        var result = InferenceJson.MAPPER.createObjectNode().put("item_id", itemId);
        var response = result.putObject("response")
                .put("request_id", UUID.randomUUID().toString())
                .put("cache_hit", false)
                .put("latency_ms", 10);
        response.putObject("usage").put("input_tokens", 100).put("output_tokens", 10);
        response.putObject("result");
        return result;
    }

    private static ObjectNode errorResult(String itemId, String code, boolean retryable) {
        var result = InferenceJson.MAPPER.createObjectNode().put("item_id", itemId);
        result.putObject("error").put("code", code).put("message", "failed").put("retryable", retryable);
        return result;
    }

    private static ResponseDefinitionBuilder error(int status, String code) {
        var body = InferenceJson.MAPPER.createObjectNode();
        body.putObject("error").put("code", code).put("message", "error").put("retryable", status == 429);
        return json(status, body);
    }

    private static ResponseDefinitionBuilder json(int status, JsonNode body) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body.toString());
    }

    private List<JsonNode> statusCalls() {
        return wireMock.server().findAll(postRequestedFor(urlEqualTo(STATUS_PATH))).stream()
                .map(request -> {
                    try {
                        return InferenceJson.MAPPER.readTree(request.getBodyAsString());
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                })
                .toList();
    }

    private List<JsonNode> submits(String path, String workspaceId) {
        return wireMock.server()
                .findAll(postRequestedFor(urlEqualTo(path))
                        .withRequestBody(matchingJsonPath("$.workspace_id", equalTo(workspaceId))))
                .stream()
                .map(request -> {
                    try {
                        return InferenceJson.MAPPER.readTree(request.getBodyAsString());
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                })
                .toList();
    }

    private static List<JsonNode> items(JsonNode body) {
        return StreamSupport.stream(body.path("items").spliterator(), false).toList();
    }

    private static List<String> itemIds(JsonNode body) {
        return items(body).stream().map(item -> item.path("item_id").asText()).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // --- State ---

    record JobRow(String jobId, String endpoint, String status, int itemCount, long bytes) {
    }

    private List<JobRow> jobs(String workspaceId) {
        return template.inTransaction(READ_ONLY, handle -> handle.createQuery("""
                SELECT job_id, endpoint, status, item_count, bytes FROM ai_label_jobs
                WHERE workspace_id = :workspaceId ORDER BY submitted_at
                """)
                .bind("workspaceId", workspaceId)
                .map((rs, ctx) -> new JobRow(rs.getString("job_id"), rs.getString("endpoint"),
                        rs.getString("status"), rs.getInt("item_count"), rs.getLong("bytes")))
                .list());
    }

    /** The workspace's in-flight (trace, task) rows, in MySQL. */
    private List<AiLabelJobItem> inFlight(String workspaceId) {
        var jobIds = jobs(workspaceId).stream().map(JobRow::jobId).toList();
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return template.inTransaction(READ_ONLY, handle -> handle.attach(AiLabelJobDAO.class).findItems(jobIds));
    }

    private static JobRow job(List<JobRow> jobs, String endpoint) {
        return jobs.stream().filter(job -> job.endpoint().equals(endpoint)).findFirst().orElseThrow();
    }

    /**
     * What one trace's items cost against the budget, per endpoint: each item's request bytes plus the per-call
     * charge, at least the floor.
     */
    private Map<AiLabelEndpoint, Long> traceCost(String workspaceId, UUID projectId, UUID traceId) {
        var tasks = registry.tasks();
        var builder = new AiLabelItemBuilder();
        return renderer.render(workspaceId, List.of(new TraceRef(projectId, traceId, Instant.EPOCH)), tasks)
                .cast(RenderOutcome.Rendered.class)
                .flatMapIterable(rendered -> builder.build(workspaceId, rendered, tasks.stream()
                        .filter(task -> task.usesHistory() == rendered.withHistory())
                        .toList()))
                .collect(Collectors.groupingBy(item -> item.plan().endpoint(),
                        Collectors.summingLong(item -> Math.max(
                                item.bytes() + config().getPerCallBytes() * item.engineCalls(),
                                config().getMinItemBytes()))))
                .block();
    }

    private long inFlightBytes() {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelJobDAO.class).inFlightBytes());
    }

    private Set<UUID> jobTraceIds(JobRow job) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(AiLabelJobDAO.class)
                .findItems(List.of(job.jobId()))).stream()
                .map(AiLabelJobItem::traceId)
                .collect(Collectors.toSet());
    }

    private List<AiLabelStatus> statuses(String workspaceId, List<UUID> traceIds) {
        return statusDAO.findLatestByTraceIds(workspaceId, Set.copyOf(traceIds)).collectList().block();
    }

    private AiLabelStatus status(String workspaceId, UUID traceId, String task) {
        return statuses(workspaceId, List.of(traceId)).stream()
                .filter(status -> status.task().equals(task))
                .findFirst()
                .orElseThrow();
    }

    private List<AiLabel> labels(String workspaceId, List<UUID> traceIds) {
        return labelsDAO.findLatestByTraceIds(workspaceId, Set.copyOf(traceIds)).collectList().block();
    }

    /** Physical rows, before any dedup: a reload writes its labels again. */
    private long labelRowCount(String workspaceId) {
        try (var statement = clickHouseConnection.createStatement();
                var result = statement.executeQuery("SELECT count() FROM %s.ai_labels WHERE workspace_id = '%s'"
                        .formatted(DATABASE_NAME, workspaceId))) {
            result.next();
            return result.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
