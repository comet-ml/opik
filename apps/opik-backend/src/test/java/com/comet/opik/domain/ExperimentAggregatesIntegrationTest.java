package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.ExperimentField;
import com.comet.opik.api.filter.ExperimentFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.api.grouping.GroupingFactory;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Experiment Aggregates Integration Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentAggregatesIntegrationTest {

    // Fields to ignore when comparing Experiment objects - we only care about aggregated fields
    private static final String[] EXPERIMENT_AGGREGATED_FIELDS_TO_IGNORE = new String[]{
            "id", "datasetName", "projectName", "createdAt", "lastUpdatedAt",
            "promptVersion", "datasetVersionSummary",
    };

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final RandomGenerator random = new Random();

    public static final String[] IGNORED_FIELDS_DATA_ITEM = {"createdAt", "lastUpdatedAt", "experimentItems",
            "createdBy", "lastUpdatedBy", "datasetId", "tags", "datasetItemId"};

    public static final String[] IGNORED_FIELDS_EXPERIMENT_ITEM = {"createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "comments", "projectName"};

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    private ExperimentService experimentService;
    private ExperimentAggregatesService experimentAggregatesService;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector) {
        String baseUrl = TestUtils.getBaseUrl(client);

        // Mock authentication
        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        projectResourceClient = new ProjectResourceClient(client, baseUrl, factory);
        datasetResourceClient = new DatasetResourceClient(client, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(client, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(client, baseUrl);
        spanResourceClient = new SpanResourceClient(client, baseUrl);

        // Get injected dependencies
        experimentService = injector.getInstance(ExperimentService.class);
        experimentAggregatesService = injector.getInstance(ExperimentAggregatesService.class);
    }

    void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        // Clean up before each test if needed
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("countFilterScenarios")
    @DisplayName("ExperimentDAO.FIND_COUNT matches FIND_COUNT_FROM_AGGREGATES")
    void experimentCountMatchesAggregates(
            String scenarioName,
            Function<CountTestData, ExperimentSearchCriteria> criteriaBuilder) {

        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Given: Create test data
        List<Project> projects = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createProject(apiKey, workspaceName))
                .toList();

        List<Dataset> datasets = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createDataset(apiKey, workspaceName))
                .toList();

        List<Experiment> experiments = datasets
                .parallelStream()
                .map(dataset -> createExperiment(dataset, apiKey, workspaceName))
                .toList();

        // Create experiment items with feedback scores
        List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        IntStream.range(0, 5)
                .parallel()
                .forEach(i -> {

                    Project project = projects.get(i);
                    Dataset dataset = datasets.get(i);
                    Experiment experiment = experiments.get(i);

                    createExperimentItemWithData(
                            experiment.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores, apiKey, workspaceName);
                });

        // Populate aggregates
        experiments.parallelStream().forEach(experiment -> {
            experimentAggregatesService.populateAggregations(experiment.id())
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.USER_NAME, USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block();
        });

        // Build criteria using the provided function
        var testData = new CountTestData(
                datasets.stream().map(Dataset::id).toList(),
                experiments.stream().map(Experiment::id).toList(),
                projects.stream().map(Project::id).toList(),
                feedbackScores);
        var criteria = criteriaBuilder.apply(testData);

        // When: Count using both methods
        var countFromRaw = experimentService.find(1, 10, criteria)
                .map(Experiment.ExperimentPage::total)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        var countFromAggregates = experimentAggregatesService.countTotal(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Both should return the same count
        assertThat(countFromAggregates)
                .as("Count from aggregates should match count from raw data for scenario: %s", scenarioName)
                .isEqualTo(countFromRaw);
    }

    Stream<Arguments> countFilterScenarios() {
        return Stream.of(
                Arguments.of("No filters",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by dataset",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomDataset = data.datasetIds().get(random.nextInt(data.datasetIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .datasetId(randomDataset)
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Filter by experiment IDs",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomExperiment = data.experimentIds()
                                    .get(random.nextInt(data.experimentIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .experimentIds(Set.of(randomExperiment))
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Filter by dataset IDs",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .datasetIds(Set.copyOf(data.datasetIds()))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by project_id",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomProject = data.projectIds()
                                    .get(random.nextInt(data.projectIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .projectId(randomProject)
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Combined filters: dataset + project_id",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomDataset = data.datasetIds()
                                    .get(random.nextInt(data.datasetIds().size()));
                            var randomProject = data.projectIds()
                                    .get(random.nextInt(data.projectIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .datasetId(randomDataset)
                                    .projectId(randomProject)
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Combined filters: experiment IDs + project_id",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomExperiment = data.experimentIds()
                                    .get(random.nextInt(data.experimentIds().size()));
                            var randomProject = data.projectIds()
                                    .get(random.nextInt(data.projectIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .experimentIds(Set.of(randomExperiment))
                                    .projectId(randomProject)
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Filter by feedback scores > 0",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .filters(List.of(ExperimentFilter.builder()
                                        .field(ExperimentField.FEEDBACK_SCORES)
                                        .key(data.scoreNames().getFirst())
                                        .value("0")
                                        .operator(Operator.GREATER_THAN)
                                        .build()))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by feedback scores not empty",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .filters(List.of(ExperimentFilter.builder()
                                        .field(ExperimentField.FEEDBACK_SCORES)
                                        .key(data.scoreNames().getLast())
                                        .value("")
                                        .operator(Operator.IS_NOT_EMPTY)
                                        .build()))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by feedback scores empty",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .filters(List.of(ExperimentFilter.builder()
                                        .field(ExperimentField.FEEDBACK_SCORES)
                                        .key(UUID.randomUUID().toString())
                                        .value("")
                                        .operator(Operator.IS_EMPTY)
                                        .build()))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()));
    }

    private record CountTestData(List<UUID> datasetIds, List<UUID> experimentIds, List<UUID> projectIds,
            List<String> scoreNames) {
    }

    @Test
    @DisplayName("ExperimentDAO.FIND calculated values match experiment_aggregates stored values")
    void experimentDaoFindMatchesAggregates() {
        // Given: Create experiment with known test data
        // Given: Create test data
        List<Project> projects = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createProject(API_KEY, TEST_WORKSPACE))
                .toList();

        List<Dataset> datasets = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createDataset(API_KEY, TEST_WORKSPACE))
                .toList();

        List<Experiment> experiments = datasets
                .parallelStream()
                .map(dataset -> createExperiment(dataset, API_KEY, TEST_WORKSPACE))
                .toList();

        // Create experiment items with specific feedback scores for verification
        List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);

        IntStream.range(0, 5)
                .parallel()
                .forEach(i -> {
                    Project project = projects.get(i);
                    Dataset dataset = datasets.get(i);
                    Experiment experiment = experiments.get(i);

                    createExperimentItemWithData(
                            experiment.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores, API_KEY, TEST_WORKSPACE);
                });

        var searchCriteria = ExperimentSearchCriteria.builder()
                .experimentIds(Set.of(experiments.getFirst().id()))
                .entityType(EntityType.TRACE)
                .sortingFields(List.of())
                .build();

        // When: Query BEFORE aggregation (calculates from raw data)
        var beforeAggregation = experimentService.find(1, 10, searchCriteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();

        assertThat(beforeAggregation).isNotNull();
        assertThat(beforeAggregation.content()).hasSize(1);
        var rawExperiment = beforeAggregation.content().getFirst();

        // Populate experiment aggregates
        experimentAggregatesService.populateAggregations(experiments.getFirst().id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();

        // Query experiment_aggregates table directly using service method
        var experimentFromAggregates = experimentAggregatesService
                .getExperimentFromAggregates(experiments.getFirst().id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();

        // Then: Verify raw calculation matches stored aggregates using recursive comparison
        assertThat(experimentFromAggregates).as("Experiment from aggregates should not be null").isNotNull();

        assertThat(experimentFromAggregates)
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                .ignoringFields(EXPERIMENT_AGGREGATED_FIELDS_TO_IGNORE)
                .ignoringCollectionOrderInFields("experimentScores", "feedbackScores")
                .isEqualTo(rawExperiment);
    }

    @ParameterizedTest(name = "Group by {0}")
    @MethodSource("groupingTestCases")
    @DisplayName("ExperimentAggregatesService.findGroups matches ExperimentService.findGroups (raw)")
    void testFindGroupsFromAggregates(String testName, List<GroupBy> groups) {
        // Given: Isolate test with unique workspace
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        int numberOfItems = 5;

        // Create experiments with different grouping values
        List<Project> projects = IntStream.range(0, numberOfItems)
                .mapToObj(i -> createProject(apiKey, workspaceName))
                .toList();

        List<Dataset> datasets = IntStream.range(0, numberOfItems)
                .mapToObj(i -> createDataset(apiKey, workspaceName))
                .toList();

        List<Experiment> experiments = datasets.stream()
                .map(dataset -> createExperiment(dataset, apiKey, workspaceName))
                .toList();

        // Create experiment items for each experiment
        List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        IntStream.range(0, numberOfItems).parallel().forEach(i -> {
            createExperimentItemWithData(
                    experiments.get(i).id(),
                    datasets.get(i).id(),
                    projects.get(i).name(),
                    feedbackScores, apiKey, workspaceName);
        });

        // Populate aggregates for all experiments
        experiments.parallelStream()
                .forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // When: Query groups using both methods (both return Mono<ExperimentGroupResponse>)
        var criteria = com.comet.opik.api.ExperimentGroupCriteria.builder()
                .groups(groups)
                .build();

        // Query using raw calculation (ExperimentService -> ExperimentDAO)
        var groupsFromRaw = experimentService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Query using aggregates (ExperimentAggregatesService -> ExperimentAggregatesDAO)
        var groupsFromAggregates = experimentAggregatesService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Verify both return non-null results
        assertThat(groupsFromRaw).isNotNull();
        assertThat(groupsFromAggregates).isNotNull();
        assertThat(groupsFromRaw.content()).isNotEmpty();
        assertThat(groupsFromAggregates.content()).isNotEmpty();

        // Compare the ExperimentGroupResponse objects from both methods
        assertThat(groupsFromAggregates)
                .as("Groups from aggregates should match groups from raw data for scenario: %s", testName)
                .usingRecursiveComparison()
                .isEqualTo(groupsFromRaw);
    }

    @ParameterizedTest(name = "Group aggregations by {0}")
    @MethodSource("groupingTestCases")
    @DisplayName("ExperimentAggregatesService.findGroupsAggregations matches ExperimentService.findGroupsAggregations (raw)")
    void testFindGroupsAggregationsFromAggregates(String testName, List<GroupBy> groups) {
        // Given: Isolate test with unique workspace
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        int numberOfItems = 5;
        // Create experiments with different grouping values
        List<Project> projects = IntStream.range(0, numberOfItems)
                .mapToObj(i -> createProject(apiKey, workspaceName))
                .toList();

        List<Dataset> datasets = IntStream.range(0, numberOfItems)
                .mapToObj(i -> createDataset(apiKey, workspaceName))
                .toList();

        List<Experiment> experiments = datasets.stream()
                .map(dataset -> createExperiment(dataset, apiKey, workspaceName))
                .toList();

        // Create experiment items for each experiment
        List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        IntStream.range(0, numberOfItems).parallel().forEach(i -> {
            createExperimentItemWithData(
                    experiments.get(i).id(),
                    datasets.get(i).id(),
                    projects.get(i).name(),
                    feedbackScores, apiKey, workspaceName);
        });

        // Populate aggregates for all experiments
        experiments.forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block());

        // When: Query group aggregations using both methods (both return Mono<ExperimentGroupAggregationsResponse>)
        var criteria = com.comet.opik.api.ExperimentGroupCriteria.builder()
                .groups(groups)
                .build();

        // Query using raw calculation (ExperimentService -> ExperimentDAO)
        var aggregationsFromRaw = experimentService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Query using aggregates (ExperimentAggregatesService -> ExperimentAggregatesDAO)
        var aggregationsFromAggregates = experimentAggregatesService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Verify both return non-null results
        assertThat(aggregationsFromRaw).isNotNull();
        assertThat(aggregationsFromAggregates).isNotNull();
        assertThat(aggregationsFromRaw.content()).isNotEmpty();
        assertThat(aggregationsFromAggregates.content()).isNotEmpty();

        // Compare the ExperimentGroupAggregationsResponse objects from both methods
        assertThat(aggregationsFromAggregates)
                .as("Group aggregations from aggregates should match aggregations from raw data for scenario: %s",
                        testName)
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                .ignoringCollectionOrderInFields("feedbackScores", "experimentScores")
                .isEqualTo(aggregationsFromRaw);
    }

    static Stream<Arguments> groupingTestCases() {
        return Stream.of(
                Arguments.of("dataset_id", List.of(
                        GroupBy.builder()
                                .field(GroupingFactory.DATASET_ID)
                                .type(FieldType.STRING)
                                .build())),
                Arguments.of("project_id", List.of(
                        GroupBy.builder()
                                .field(GroupingFactory.PROJECT_ID)
                                .type(FieldType.STRING)
                                .build())),
                Arguments.of("dataset_id and project_id", List.of(
                        GroupBy.builder()
                                .field(GroupingFactory.DATASET_ID)
                                .type(FieldType.STRING)
                                .build(),
                        GroupBy.builder()
                                .field(GroupingFactory.PROJECT_ID)
                                .type(FieldType.STRING)
                                .build())));
    }

    // Helper methods

    private Project createProject(String apiKey, String workspaceName) {
        var projectName = "test-project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
        return projectResourceClient.getProject(projectId, apiKey, workspaceName);
    }

    private Dataset createDataset(String apiKey, String workspaceName) {
        var dataset = factory.manufacturePojo(Dataset.class);
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        return dataset;
    }

    private Experiment createExperiment(Dataset dataset, String apiKey, String workspaceName) {
        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetId(dataset.id())
                .datasetName(dataset.name()) // Use the actual dataset's name
                .build();
        experimentResourceClient.create(experiment, apiKey, workspaceName);
        return experiment;
    }

    private List<ExperimentItem> createExperimentItemWithData(UUID experimentId, UUID datasetId, String projectName,
            List<String> feedbackScores, String apiKey, String workspaceName) {

        // Create dataset item
        var datasetItems = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class)
                .stream()
                .map(item -> item.toBuilder()
                        .datasetId(datasetId)
                        .traceId(null)
                        .experimentItems(null)
                        .spanId(null)
                        .source(DatasetItemSource.SDK)
                        .build())
                .toList();

        var batch = DatasetItemBatch.builder()
                .datasetId(datasetId)
                .items(datasetItems)
                .build();

        datasetResourceClient.createDatasetItems(batch, workspaceName, apiKey);

        // Create experiment item
        List<ExperimentItem> experimentItems = datasetItems.stream()
                .map(datasetItem -> {

                    // Create trace with output containing the dataset ID (common across experiments) and experiment ID
                    // This allows filtering by a common value while maintaining experiment-specific data
                    var outputNode = JsonUtils.getJsonNodeFromString(
                            "{\"result\": \"" + datasetId.toString() + "-" + experimentId.toString() + "\"}");
                    var trace = factory.manufacturePojo(Trace.class)
                            .toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .visibilityMode(null)
                            .output(outputNode)
                            .build();

                    traceResourceClient.createTrace(trace, apiKey, workspaceName);

                    // Create spans for the trace

                    var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                            .stream()
                            .map(span -> span
                                    .toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .parentSpanId(null)
                                    .usage(spanResourceClient.getTokenUsage())
                                    .build())
                            .toList();

                    spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

                    // Create feedback scores
                    List<FeedbackScoreBatchItem> feedbackScoreItems = (List<FeedbackScoreBatchItem>) feedbackScores
                            .stream()
                            .map(name -> factory.manufacturePojo(FeedbackScoreBatchItem.class).builder()
                                    .id(trace.id())
                                    .projectName(projectName)
                                    .name(name)
                                    .value(BigDecimal.valueOf(Math.random() * 10))
                                    .source(ScoreSource.SDK)
                                    .build())
                            .toList();

                    traceResourceClient.feedbackScores(feedbackScoreItems, apiKey, workspaceName);

                    return ExperimentItem.builder()
                            .experimentId(experimentId)
                            .datasetItemId(datasetItem.id())
                            .traceId(trace.id())
                            .build();
                })
                .toList();

        experimentResourceClient.createExperimentItem(Set.copyOf(experimentItems), apiKey, workspaceName);
        return experimentItems;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasetItemCountFilterScenarios")
    @DisplayName("countDatasetItemsWithExperimentItems matches countDatasetItemsWithExperimentItemsFromAggregates")
    void testDatasetItemCountWithAggregates(String scenarioName,
            Function<DatasetItemCountTestData, DatasetItemSearchCriteria> criteriaBuilder) {

        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        List<List<UUID>> experimentIdsList = new ArrayList<>();
        List<List<UUID>> datasetIdsList = new ArrayList<>();
        List<List<String>> feedbackScoresList = new ArrayList<>();

        // Given: Create test data with multiple experiments
        IntStream.range(0, 2)
                .parallel()
                .forEach(i -> {
                    var project = createProject(apiKey, workspaceName);
                    var dataset = createDataset(apiKey, workspaceName);
                    var experiment1 = createExperiment(dataset, apiKey, workspaceName);
                    var experiment2 = createExperiment(dataset, apiKey, workspaceName);

                    // Create experiment items with specific feedback scores and varied data
                    List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);

                    createExperimentItemWithData(
                            experiment1.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores,
                            apiKey,
                            workspaceName);
                    createExperimentItemWithData(
                            experiment2.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores,
                            apiKey,
                            workspaceName);

                    experimentIdsList.add(List.of(experiment1.id(), experiment2.id()));
                    datasetIdsList.add(List.of(dataset.id()));
                    feedbackScoresList.add(feedbackScores);
                });

        // Populate aggregates for both experiments
        experimentIdsList
                .parallelStream()
                .flatMap(List::stream)
                .forEach(experimentId -> experimentAggregatesService.populateAggregations(experimentId)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // Build criteria using the provided function
        var testData = new DatasetItemCountTestData(
                datasetIdsList.getFirst().getFirst(),
                Set.copyOf(experimentIdsList.getFirst()),
                feedbackScoresList.getFirst());

        var criteria = criteriaBuilder.apply(testData);

        // When: Get count from original service method
        var datasetItemPage = datasetResourceClient.getDatasetItemsWithExperimentItems(
                criteria.datasetId(),
                List.copyOf(criteria.experimentIds()),
                criteria.search(),
                criteria.filters(),
                apiKey,
                workspaceName);

        var countFromOriginal = datasetItemPage.total();

        // When: Get count from aggregates (new version)
        var countFromAggregates = experimentAggregatesService
                .countDatasetItemsWithExperimentItemsFromAggregates(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Both should return the same count
        assertThat(countFromAggregates)
                .as("Count from aggregates should match count from original DAO for scenario: %s",
                        scenarioName)
                .isEqualTo(countFromOriginal);
    }

    Stream<Arguments> datasetItemCountFilterScenarios() {
        return Stream.of(
                Arguments.of("No filters",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by duration > 0",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.DURATION.getQueryParamField())
                                        .operator(Operator.GREATER_THAN)
                                        .value("0")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by feedback score with key",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES
                                                .getQueryParamField())
                                        .key(data.feedbackScores().getFirst())
                                        .operator(Operator.GREATER_THAN)
                                        .value("0")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by feedback score is not empty with key",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES
                                                .getQueryParamField())
                                        .key(data.feedbackScores().getFirst())
                                        .operator(Operator.IS_NOT_EMPTY)
                                        .value("")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by feedback score is empty with key",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES
                                                .getQueryParamField())
                                        .key(UUID.randomUUID().toString())
                                        .operator(Operator.IS_EMPTY)
                                        .value("")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by output field (dynamic)",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field("output.result")
                                        .operator(Operator.CONTAINS)
                                        .value(data.experimentIds().iterator().next().toString())
                                        .type(FieldType.STRING)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Search in input/output",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .search(data.experimentIds().iterator().next().toString())
                                .truncate(false)
                                .build()),

                Arguments.of("Combined filter and search",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.DURATION.getQueryParamField())
                                        .operator(Operator.GREATER_THAN)
                                        .value("0")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .search(data.experimentIds().iterator().next().toString())
                                .truncate(false)
                                .build()));
    }

    private record DatasetItemCountTestData(
            UUID datasetId,
            Set<UUID> experimentIds,
            List<String> feedbackScores) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasetItemCountFilterScenarios")
    @DisplayName("getDatasetItemsWithExperimentItemsFromAggregates: Compare with original DAO")
    void getDatasetItemsWithExperimentItemsFromAggregates(
            String scenarioName,
            Function<DatasetItemCountTestData, DatasetItemSearchCriteria> criteriaBuilder) {

        // Given: Create test data and populate aggregates
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        List<List<UUID>> experimentIdsList = new ArrayList<>();
        List<List<UUID>> datasetIdsList = new ArrayList<>();
        List<List<String>> feedbackScoresList = new ArrayList<>();

        // Given: Create test data with multiple experiments
        IntStream.range(0, 2)
                .parallel()
                .forEach(i -> {
                    var project = createProject(apiKey, workspaceName);
                    var dataset = createDataset(apiKey, workspaceName);
                    var experiment1 = createExperiment(dataset, apiKey, workspaceName);
                    var experiment2 = createExperiment(dataset, apiKey, workspaceName);

                    // Create experiment items with specific feedback scores and varied data
                    List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);

                    createExperimentItemWithData(
                            experiment1.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores,
                            apiKey,
                            workspaceName);
                    createExperimentItemWithData(
                            experiment2.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores,
                            apiKey,
                            workspaceName);

                    experimentIdsList.add(List.of(experiment1.id(), experiment2.id()));
                    datasetIdsList.add(List.of(dataset.id()));
                    feedbackScoresList.add(feedbackScores);
                });

        // Populate aggregates for both experiments
        experimentIdsList
                .parallelStream()
                .flatMap(List::stream)
                .forEach(experimentId -> experimentAggregatesService.populateAggregations(experimentId)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // Build criteria using the provided function
        var testData = new DatasetItemCountTestData(
                datasetIdsList.getFirst().getFirst(),
                Set.copyOf(experimentIdsList.getFirst()),
                feedbackScoresList.getFirst());

        var criteria = criteriaBuilder.apply(testData);

        // When: Get items from original service method
        var pageFromOriginal = datasetResourceClient.getDatasetItemsWithExperimentItems(
                criteria.datasetId(),
                List.copyOf(criteria.experimentIds()),
                criteria.search(),
                criteria.filters(),
                apiKey,
                workspaceName);

        // When: Get items from aggregates
        var pageFromAggregates = experimentAggregatesService
                .getDatasetItemsWithExperimentItemsFromAggregates(criteria, 1, 10)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Verify page properties match
        assertThat(pageFromAggregates.page())
                .as("Page number should match for scenario: %s", scenarioName)
                .isEqualTo(pageFromOriginal.page());

        assertThat(pageFromAggregates.total())
                .as("Total count should match for scenario: %s", scenarioName)
                .isEqualTo(pageFromOriginal.total());

        assertThat(pageFromAggregates.size())
                .as("Page size should match for scenario: %s", scenarioName)
                .isEqualTo(pageFromOriginal.size());

        // Then: Verify content matches exactly
        assertDatasetItemsWithExperimentItems(pageFromOriginal.content(), pageFromAggregates.content());
    }

    void assertDatasetItemsWithExperimentItems(List<DatasetItem> expectedDatasetItem,
            List<DatasetItem> actualDatasetItems) {

        assertThat(actualDatasetItems)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_DATA_ITEM)
                .isEqualTo(expectedDatasetItem);

        for (var i = 0; i < actualDatasetItems.size(); i++) {
            var actualExperiments = actualDatasetItems.get(i).experimentItems();
            var expectedExperiments = expectedDatasetItem.get(i).experimentItems();

            // Filtering by those related to experiments 1 and 3
            assertThat(actualExperiments)
                    .usingRecursiveComparison()
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                    .ignoringCollectionOrderInFields("feedbackScores")
                    .ignoringFields(IGNORED_FIELDS_EXPERIMENT_ITEM)
                    .isEqualTo(expectedExperiments);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasetItemCountFilterScenarios")
    @DisplayName("getExperimentItemsStats: aggregates should match original calculation with filters")
    void getExperimentItemsStatsFromAggregates(String scenarioName,
            Function<DatasetItemCountTestData, DatasetItemSearchCriteria> criteriaBuilder) {

        // Given: Create test data and populate aggregates
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        List<List<UUID>> experimentIdsList = new ArrayList<>();
        List<List<UUID>> datasetIdsList = new ArrayList<>();
        List<List<String>> feedbackScoresList = new ArrayList<>();

        // Given: Create test data with multiple experiments
        IntStream.range(0, 2)
                .parallel()
                .forEach(i -> {
                    var project = createProject(apiKey, workspaceName);
                    var dataset = createDataset(apiKey, workspaceName);
                    var experiment1 = createExperiment(dataset, apiKey, workspaceName);
                    var experiment2 = createExperiment(dataset, apiKey, workspaceName);

                    // Create experiment items with specific feedback scores and varied data
                    List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);

                    createExperimentItemWithData(
                            experiment1.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores,
                            apiKey,
                            workspaceName);
                    createExperimentItemWithData(
                            experiment2.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores,
                            apiKey,
                            workspaceName);

                    experimentIdsList.add(List.of(experiment1.id(), experiment2.id()));
                    datasetIdsList.add(List.of(dataset.id()));
                    feedbackScoresList.add(feedbackScores);
                });

        // Populate aggregates for both experiments
        experimentIdsList
                .parallelStream()
                .flatMap(List::stream)
                .forEach(experimentId -> experimentAggregatesService.populateAggregations(experimentId)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // Build criteria using the provided function
        var testData = new DatasetItemCountTestData(
                datasetIdsList.getFirst().getFirst(),
                Set.copyOf(experimentIdsList.getFirst()),
                feedbackScoresList.getFirst());

        var criteria = criteriaBuilder.apply(testData);

        @SuppressWarnings("unchecked")
        List<com.comet.opik.api.filter.ExperimentsComparisonFilter> filters = (List<com.comet.opik.api.filter.ExperimentsComparisonFilter>) criteria
                .filters();

        // When: Get stats from original DAO
        var statsFromOriginal = datasetResourceClient.getDatasetExperimentItemsStats(
                criteria.datasetId(),
                List.copyOf(criteria.experimentIds()),
                apiKey,
                workspaceName,
                filters);

        // When: Get stats from aggregates DAO
        var statsFromAggregates = experimentAggregatesService
                .getExperimentItemsStatsFromAggregates(
                        criteria.datasetId(),
                        criteria.experimentIds(),
                        filters)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Verify stats match
        assertThat(statsFromAggregates)
                .as("Stats from aggregates should not be null for scenario: %s", scenarioName)
                .isNotNull();

        assertThat(statsFromOriginal)
                .as("Stats from original should not be null for scenario: %s", scenarioName)
                .isNotNull();

        assertThat(statsFromAggregates)
                .as("Stats should match for scenario: %s", scenarioName)
                .usingRecursiveComparison()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .withComparatorForType(StatsUtils::compareDoubles, Double.class)
                .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "value")
                .ignoringCollectionOrder()
                .isEqualTo(statsFromOriginal);
    }

}
