package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.annotationqueue.ConditionGroup;
import com.comet.opik.api.annotationqueue.Conditions;
import com.comet.opik.api.annotationqueue.ScoreCondition;
import com.comet.opik.api.annotationqueue.ScoreConditionOperator;
import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.AutomationDryRun;
import com.comet.opik.api.automations.AutomationRun;
import com.comet.opik.api.automations.AutomationTestRequest;
import com.comet.opik.api.automations.AutomationUpdate;
import com.comet.opik.api.automations.Recommendation;
import com.comet.opik.api.automations.Workflow;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.automations.WorkflowService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.awaitility.Awaitility;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automations end to end: the resource, the dry-run estimate, the recipes and recommendations, and the
 * executor behind the real score-routing pipeline writing into a real test suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AutomationsResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();
    private static final String PATH = "%s/v1/private/automations";
    private static final String SCORE = "resolution";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE_CONTAINER, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("A definition naming an unknown event, step or target is refused with the code an agent maps")
    void createRefusesWithCodes() {
        var project = newProject();
        String suiteName = suiteName();
        var suiteId = newSuite(project.id(), suiteName);

        assertRefused(post(definition(project.id(), suiteId).toBuilder()
                .triggers(List.of(trigger().toBuilder().event("schedule").build())).build()), "unknown_event");
        assertRefused(post(definition(project.id(), suiteId).toBuilder()
                .steps(List.of(step(suiteId).toBuilder().type("optimizer.run").build())).build()), "unknown_step");
        assertRefused(post(definition(project.id(), suiteId).toBuilder()
                .steps(List.of(step(suiteId).toBuilder().testSuiteId(UUID.randomUUID()).build())).build()),
                "target_not_found");
        assertRefused(post(definition(project.id(), suiteId).toBuilder()
                .triggers(List.of(trigger().toBuilder().scope(Automation.Scope.THREAD).build())).build()),
                "step_scope_mismatch");
        assertRefused(post(definition(project.id(), suiteId).toBuilder()
                .steps(List.of(step(suiteId).toBuilder().onlyIf(Automation.StepConditions.builder()
                        .groups(List.of(Automation.StepConditions.Group.builder().conditions(List.of(
                                Automation.StepConditions.Condition.builder().stepOutput("run.regressions")
                                        .operator(ScoreConditionOperator.GREATER_THAN).value(0.0).build()))
                                .build()))
                        .build()).build()))
                .build()), "condition_unknown_step");
    }

    @Test
    @DisplayName("Create resolves the suite by name, reads back with stats, and lists by project, event and recipe")
    void createThenGetAndList() {
        var project = newProject();
        String suiteName = suiteName();
        var suiteId = newSuite(project.id(), suiteName);

        var created = create(definition(project.id(), suiteId).toBuilder()
                .steps(List.of(step(null).toBuilder().testSuiteName(suiteName).build())).build());

        assertThat(created.id()).isNotNull();
        assertThat(created.projectName()).isEqualTo(project.name());
        assertThat(created.enabled()).isTrue();
        assertThat(created.createdVia()).isEqualTo(Automation.CreatedVia.MCP);
        assertThat(created.steps().getFirst().testSuiteId()).isEqualTo(suiteId);
        assertThat(created.steps().getFirst().testSuiteName()).isEqualTo(suiteName);
        assertThat(created.stats().fired()).isZero();
        assertThat(created.pausedReason()).isNull();

        assertThat(get(created.id())).isEqualTo(created);
        assertThat(list(Map.of("project_id", project.id())).total()).isEqualTo(1);
        assertThat(list(Map.of("project_id", project.id(), "event", "feedback_score:created")).total()).isEqualTo(1);
        assertThat(list(Map.of("project_id", project.id(), "event", "prompt:committed")).total()).isZero();
        assertThat(list(Map.of("project_id", project.id(), "recipe", "collect")).total()).isZero();
    }

    @Test
    @DisplayName("A dry run replays the definition against the project's scores and writes nothing")
    void dryRunEstimates() {
        var project = newProject();
        String suiteName = suiteName();
        var suiteId = newSuite(project.id(), suiteName);
        score(newTrace(project.name()), 0.2);
        score(newTrace(project.name()), 0.9);

        try (var response = request(PATH.formatted(baseURI), Map.of("dry_run", true))
                .post(Entity.json(definition(project.id(), suiteId)))) {
            assertThat(response.getStatus()).isEqualTo(200);
            var preview = response.readEntity(AutomationDryRun.class);
            assertThat(preview.estimate().windowDays()).isEqualTo(14);
            assertThat(preview.estimate().fired()).isEqualTo(2);
            assertThat(preview.estimate().matched()).isEqualTo(1);
            assertThat(preview.estimate().items()).isEqualTo(1);
            assertThat(preview.automation().steps().getFirst().testSuiteName()).isEqualTo(suiteName);
        }
        assertThat(list(Map.of("project_id", project.id())).total()).isZero();
    }

    @Test
    @DisplayName("A score below the threshold puts the trace in the suite once, with a run rollup to show for it")
    void scoredTraceLandsInSuite() {
        var project = newProject();
        String suiteName = suiteName();
        var suiteId = newSuite(project.id(), suiteName);
        var automation = create(definition(project.id(), suiteId));

        var low = newTrace(project.name());
        var high = newTrace(project.name());
        score(low, 0.2);
        score(high, 0.9);

        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(suiteItems(suiteId)).containsExactly(low));

        var runs = runs(automation.id());
        assertThat(runs.total()).isEqualTo(1);
        var run = runs.content().getFirst();
        assertThat(run.kind()).isEqualTo(AutomationRun.Kind.ROLLUP);
        assertThat(run.status()).isEqualTo(AutomationRun.Status.DONE);
        assertThat(run.trigger().matched()).isEqualTo(1);
        assertThat(run.steps().getFirst().done()).isEqualTo(1);
        assertThat(get(run.id(), AutomationRun.class, "runs").id()).isEqualTo(run.id());
        assertThat(get(automation.id()).stats().itemsWritten()).isEqualTo(1);

        // A second score on the same trace re-evaluates it; the ledger keeps the suite at one copy.
        score(low, 0.1);
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(runs(automation.id()).content().getFirst().trigger().fired())
                        .isGreaterThanOrEqualTo(2));
        assertThat(suiteItems(suiteId)).containsExactly(low);
    }

    @Test
    @DisplayName("A shadow run reports the decision for one trace without writing")
    void testRunsInShadow() {
        var project = newProject();
        String suiteName = suiteName();
        var suiteId = newSuite(project.id(), suiteName);
        var automation = create(definition(project.id(), suiteId).toBuilder().enabled(false).build());
        var low = newTrace(project.name());
        score(low, 0.2);
        var unscored = newTrace(project.name());

        try (var response = request(PATH.formatted(baseURI) + "/" + automation.id() + "/test", Map.of())
                .post(Entity.json(AutomationTestRequest.builder().traceId(low).build()))) {
            assertThat(response.getStatus()).isEqualTo(200);
            var run = response.readEntity(AutomationRun.class);
            assertThat(run.kind()).isEqualTo(AutomationRun.Kind.SINGLE);
            assertThat(run.trigger().matched()).isEqualTo(1);
            assertThat(run.steps().getFirst().status()).isEqualTo(AutomationRun.StepStatus.DONE);
        }
        try (var response = request(PATH.formatted(baseURI) + "/" + automation.id() + "/test", Map.of())
                .post(Entity.json(AutomationTestRequest.builder().traceId(unscored).build()))) {
            var run = response.readEntity(AutomationRun.class);
            assertThat(run.trigger().matched()).isZero();
            assertThat(run.steps().getFirst().status()).isEqualTo(AutomationRun.StepStatus.SKIPPED);
        }
        assertThat(suiteItems(suiteId)).isEmpty();
    }

    @Test
    @DisplayName("Update changes what was sent, delete removes the automation")
    void updateAndDelete() {
        var project = newProject();
        String suiteName = suiteName();
        var suiteId = newSuite(project.id(), suiteName);
        var automation = create(definition(project.id(), suiteId));

        try (var response = request(PATH.formatted(baseURI) + "/" + automation.id(), Map.of())
                .method("PATCH", Entity.json(AutomationUpdate.builder().name("Renamed").enabled(false).build()))) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
        var updated = get(automation.id());
        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.triggers()).isEqualTo(automation.triggers());

        try (var response = request(PATH.formatted(baseURI) + "/delete", Map.of())
                .post(Entity.json(new BatchDelete(Set.of(automation.id()))))) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
        try (var response = request(PATH.formatted(baseURI) + "/" + automation.id(), Map.of()).get()) {
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("Collect turns on from a recipe, creating the suite it names, and turns off again")
    void workflowEnableDisable() {
        var project = newProject();

        var workflows = workflows(project.id());
        assertThat(workflows).extracting(Workflow::recipe).containsExactly("collect", "align");
        assertThat(workflows).allMatch(workflow -> workflow.state() == Workflow.State.OFF);
        assertThat(workflows.getFirst().overrides().testSuiteName()).isEqualTo("Regression · " + project.name());

        var request = Workflow.EnableRequest.builder().recipe("collect").projectId(project.id())
                .createdVia(Automation.CreatedVia.MCP).build();
        var preview = enable(request, true);
        assertThat(preview.created()).isEmpty();
        assertThat(preview.wouldCreate()).hasSize(1);
        assertThat(list(Map.of("project_id", project.id())).total()).isZero();

        var enabled = enable(request, false);
        assertThat(enabled.created()).hasSize(1);
        var created = enabled.created().getFirst();
        assertThat(created.recipe().id()).isEqualTo("collect");
        assertThat(created.steps().getFirst().testSuiteName()).isEqualTo("Regression · " + project.name());
        assertThat(created.steps().getFirst().testSuiteId()).isNotNull();
        assertThat(created.limits().maxItemsPerDay()).isEqualTo(WorkflowService.DEFAULT_MAX_ITEMS_PER_DAY);
        assertThat(workflows(project.id()).getFirst().state()).isEqualTo(Workflow.State.ON);
        assertThat(enable(request, false).created()).isEmpty();
        assertThat(list(Map.of("project_id", project.id(), "recipe", "collect")).total()).isEqualTo(1);

        try (var response = request(PATH.formatted(baseURI) + "/workflows/disable", Map.of())
                .post(Entity.json(Workflow.DisableRequest.builder().recipe("collect").projectId(project.id())
                        .build()))) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.readEntity(Workflow.Disabled.class).disabled()).containsExactly(created.id());
        }
        assertThat(workflows(project.id()).getFirst().state()).isEqualTo(Workflow.State.OFF);
    }

    @Test
    @DisplayName("Recommendations start with Collect, carry its apply call, and stay hidden once dismissed")
    void recommendations() {
        var project = newProject();

        var first = recommendations(project.id());
        assertThat(first).isNotEmpty();
        var collect = first.getFirst();
        assertThat(collect.kind()).isEqualTo(Recommendation.Kind.SETUP);
        assertThat(collect.workflow()).isEqualTo("collect");
        assertThat(collect.apply().operation()).isEqualTo("workflow.enable");
        assertThat(collect.apply().data().get("recipe").asText()).isEqualTo("collect");
        assertThat(collect.reason()).doesNotContain("%").contains("14 days");

        try (var response = request(PATH.formatted(baseURI) + "/recommendations/" + collect.id() + "/dismiss",
                Map.of()).post(Entity.json(Map.of("project_id", project.id())))) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
        assertThat(recommendations(project.id())).extracting(Recommendation::id).doesNotContain(collect.id());

        enable(Workflow.EnableRequest.builder().recipe("collect").projectId(project.id()).build(), false);
        var after = recommendations(project.id());
        assertThat(after).extracting(Recommendation::kind).contains(Recommendation.Kind.REVIEW);
        assertThat(after).extracting(Recommendation::workflow).doesNotContain("collect");
    }

    private Automation definition(UUID projectId, UUID suiteId) {
        return Automation.builder()
                .projectId(projectId)
                .name("Low resolution traces to the suite")
                .triggers(List.of(trigger()))
                .steps(List.of(step(suiteId)))
                .limits(Automation.Limits.builder().maxItemsPerDay(200).build())
                .createdVia(Automation.CreatedVia.MCP)
                .build();
    }

    private static Automation.Trigger trigger() {
        return Automation.Trigger.builder()
                .event("feedback_score:created")
                .scope(Automation.Scope.TRACE)
                .conditions(Conditions.builder().groups(List.of(ConditionGroup.builder().conditions(List.of(
                        ScoreCondition.builder().scoreName(SCORE).operator(ScoreConditionOperator.LESS_THAN).value(0.5)
                                .build()))
                        .build())).build())
                .build();
    }

    private static Automation.Step step(UUID suiteId) {
        return Automation.Step.builder().id("keep").type("test_suite.add").testSuiteId(suiteId).build();
    }

    private Project newProject() {
        String name = "project-" + UUID.randomUUID();
        UUID id = projectResourceClient.createProject(name, API_KEY, TEST_WORKSPACE);
        return new Project(id, name);
    }

    private record Project(UUID id, String name) {
    }

    /** Dataset names are unique per workspace, so each test gets its own suite name. */
    private String suiteName() {
        return "Regression-" + UUID.randomUUID();
    }

    private UUID newSuite(UUID projectId, String name) {
        return datasetResourceClient.createDataset(
                Dataset.builder().name(name).projectId(projectId).type(DatasetType.TEST_SUITE).build(),
                API_KEY, TEST_WORKSPACE);
    }

    private UUID newTrace(String projectName) {
        // Pinned to the SDK source: the factory picks any source, and the executor only runs on
        // production traffic, as queue routing does.
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null).projectName(projectName).startTime(Instant.now()).endTime(Instant.now())
                .source(Source.SDK).feedbackScores(null).usage(null).build();
        return traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
    }

    /** Scores, then waits until the trace reads back with it: what the executor and the test endpoint read. */
    private void score(UUID traceId, double value) {
        traceResourceClient.feedbackScore(traceId, FeedbackScore.builder()
                .name(SCORE).value(BigDecimal.valueOf(value)).source(ScoreSource.SDK).build(),
                TEST_WORKSPACE, API_KEY);
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY)
                        .feedbackScores()).anySatisfy(score -> {
                            assertThat(score.name()).isEqualTo(SCORE);
                            assertThat(score.value()).isEqualByComparingTo(BigDecimal.valueOf(value));
                        }));
    }

    private List<UUID> suiteItems(UUID suiteId) {
        return datasetResourceClient.getDatasetItems(suiteId, 1, 100, null, API_KEY, TEST_WORKSPACE).content()
                .stream().map(item -> item.traceId()).toList();
    }

    private jakarta.ws.rs.client.Invocation.Builder request(String path, Map<String, Object> query) {
        var target = client.target(path);
        for (var entry : query.entrySet()) {
            target = target.queryParam(entry.getKey(), entry.getValue());
        }
        return target.request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE);
    }

    private Response post(Automation automation) {
        return request(PATH.formatted(baseURI), Map.of()).post(Entity.json(automation));
    }

    private Automation create(Automation automation) {
        try (var response = post(automation)) {
            assertThat(response.getStatus()).isEqualTo(201);
            assertThat(response.getLocation()).isNotNull();
            return response.readEntity(Automation.class);
        }
    }

    private static void assertRefused(Response response, String code) {
        try (response) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.readEntity(ErrorMessage.class).errors().getFirst()).startsWith(code + ": ");
        }
    }

    private Automation get(UUID id) {
        return get(id, Automation.class, "");
    }

    private <T> T get(UUID id, Class<T> type, String collection) {
        try (var response = request(PATH.formatted(baseURI) + (collection.isEmpty() ? "" : "/" + collection)
                + "/" + id, Map.of()).get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(type);
        }
    }

    private Automation.AutomationPage list(Map<String, Object> query) {
        try (var response = request(PATH.formatted(baseURI), query).get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(Automation.AutomationPage.class);
        }
    }

    private AutomationRun.AutomationRunPage runs(UUID automationId) {
        try (var response = request(PATH.formatted(baseURI) + "/runs", Map.of("automation_id", automationId)).get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(AutomationRun.AutomationRunPage.class);
        }
    }

    private List<Workflow> workflows(UUID projectId) {
        try (var response = request(PATH.formatted(baseURI) + "/workflows", Map.of("project_id", projectId)).get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(new GenericType<List<Workflow>>() {
            });
        }
    }

    private Workflow.Enabled enable(Workflow.EnableRequest request, boolean dryRun) {
        try (var response = request(PATH.formatted(baseURI) + "/workflows/enable", Map.of("dry_run", dryRun))
                .post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(Workflow.Enabled.class);
        }
    }

    private List<Recommendation> recommendations(UUID projectId) {
        try (var response = request(PATH.formatted(baseURI) + "/recommendations", Map.of("project_id", projectId))
                .get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(new GenericType<List<Recommendation>>() {
            });
        }
    }
}
