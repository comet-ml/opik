package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentScore;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.ExperimentField;
import com.comet.opik.api.filter.ExperimentFilter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.PromptResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.SpanType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.common.eventbus.EventBus;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.Experiment.PromptVersionLink;
import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatch;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.ExperimentsTestUtils.getQuantities;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.EXPERIMENT_IGNORED_FIELDS;
import static com.comet.opik.utils.ValidationUtils.SCALE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Find Experiments by Project")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentsResourceFindProjectExperimentsTest {

    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private static final String URL_TEMPLATE = "%s/v1/private/experiments";

    private final TestContainersSetup setup = new TestContainersSetup(Mockito.mock(EventBus.class));

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ExperimentResourceClient experimentResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private SpanResourceClient spanResourceClient;
    private PromptResourceClient promptResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.promptResourceClient = new PromptResourceClient(client, baseURI, factory);

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private Dataset buildDataset() {
        return DatasetResourceClient.buildDataset(factory);
    }

    private List<Dataset> getDatasets() {
        return DatasetResourceClient.buildDatasetList(factory);
    }

    private Prompt buildPrompt() {
        return PromptResourceClient.buildPrompt(factory);
    }

    private static PromptVersionLink buildVersionLink(PromptVersion promptVersion, String promptName) {
        return PromptVersionLink.builder()
                .id(promptVersion.id())
                .commit(promptVersion.commit())
                .promptId(promptVersion.promptId())
                .promptName(promptName)
                .build();
    }

    private void createAndAssert(ExperimentItemsBatch request, String apiKey, String workspaceName) {
        experimentResourceClient.createExperimentItem(request.experimentItems(), apiKey, workspaceName);
    }

    private void createScoreAndAssert(FeedbackScoreBatch batch, String apiKey, String workspaceName) {
        traceResourceClient.feedbackScores(batch.scores(), apiKey, workspaceName);
    }

    private UUID createAndAssert(Experiment experiment, String apiKey, String workspaceName) {
        return experimentResourceClient.create(experiment, apiKey, workspaceName);
    }

    private Experiment getExperiment(UUID id, String workspaceName, String apiKey) {
        return experimentResourceClient.getExperiment(id, apiKey, workspaceName);
    }

    private Experiment getAndAssert(UUID id, Experiment expectedExperiment, String workspaceName, String apiKey) {
        var actual = getExperiment(id, workspaceName, apiKey);
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(expectedExperiment);
        return actual;
    }

    private List<FeedbackScoreBatchItem> copyScoresFrom(List<FeedbackScoreBatchItem> scoreForTrace, Trace trace) {
        return scoreForTrace
                .stream()
                .map(feedbackScoreBatchItem -> feedbackScoreBatchItem.toBuilder()
                        .id(trace.id())
                        .projectName(trace.projectName())
                        .value(factory.manufacturePojo(BigDecimal.class).abs())
                        .build())
                .collect(toList());
    }

    private List<FeedbackScoreBatchItem> makeTraceScores(Trace trace) {
        return copyScoresFrom(
                PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class),
                trace);
    }

    private ExperimentItemsBatch addRandomExperiments(List<ExperimentItem> experimentItems) {
        var experimentItemsBatch = factory.manufacturePojo(ExperimentItemsBatch.class);
        experimentItemsBatch = experimentItemsBatch.toBuilder()
                .experimentItems(Stream.concat(
                        experimentItemsBatch.experimentItems().stream(),
                        experimentItems.stream())
                        .collect(toUnmodifiableSet()))
                .build();
        return experimentItemsBatch;
    }

    private Map<UUID, Map<String, BigDecimal>> getExpectedScoresPerExperiment(
            List<Experiment> experiments, List<ExperimentItem> experimentItems) {
        return experiments.stream()
                .map(experiment -> Map.entry(experiment.id(), experimentItems
                        .stream()
                        .filter(item -> item.experimentId().equals(experiment.id()))
                        .map(ExperimentItem::feedbackScores)
                        .flatMap(Collection::stream)
                        .collect(groupingBy(
                                FeedbackScore::name,
                                mapping(FeedbackScore::value, toList())))
                        .entrySet()
                        .stream()
                        .map(e -> Map.entry(e.getKey(), avgFromList(e.getValue())))
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue))))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private BigDecimal avgFromList(List<BigDecimal> values) {
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_EVEN);
    }

    private BigDecimal getTotalEstimatedCost(List<Span> spans) {
        return spans.stream()
                .map(Span::totalEstimatedCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal getTotalEstimatedCostAvg(List<Span> spans) {
        BigDecimal accumulated = spans.stream()
                .map(Span::totalEstimatedCost)
                .reduce(BigDecimal::add)
                .orElse(BigDecimal.ZERO);

        return accumulated.divide(BigDecimal.valueOf(spans.size()), com.comet.opik.utils.ValidationUtils.SCALE,
                RoundingMode.HALF_UP);
    }

    private Map<String, Double> getUsage(List<Span> spans) {
        return spans.stream()
                .map(Span::usage)
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.averagingLong(Map.Entry::getValue)));
    }

    private Experiment generateFullExperiment(
            String apiKey,
            String workspaceName,
            Experiment expectedExperiment,
            List<FeedbackScoreBatchItem> scoreForTrace,
            String projectName,
            UUID projectId) {

        createAndAssert(expectedExperiment, apiKey, workspaceName);

        int tracesNumber = PodamUtils.getIntegerInRange(1, 10);

        List<Trace> traces = IntStream.range(0, tracesNumber)
                .mapToObj(i -> {
                    var builder = factory.manufacturePojo(Trace.class).toBuilder()
                            .startTime(Instant.now())
                            .endTime(Instant.now().plus(PodamUtils.getIntegerInRange(100, 2000), ChronoUnit.MILLIS));
                    if (projectName != null) {
                        builder.projectName(projectName);
                    }
                    return builder.build();
                })
                .toList();

        traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

        Map<UUID, List<FeedbackScoreBatchItem>> traceIdToScoresMap = new HashMap<>();
        for (Trace trace : traces) {
            List<FeedbackScoreBatchItem> scores = copyScoresFrom(scoreForTrace, trace);
            for (FeedbackScoreBatchItem item : scores) {
                if (traces.getLast().equals(trace) && scores.getFirst().equals(item)) {
                    continue;
                }
                traceIdToScoresMap.computeIfAbsent(item.id(), k -> new ArrayList<>()).add(item);
            }
        }

        var feedbackScoreBatch = factory.manufacturePojo(FeedbackScoreBatch.class);
        feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                .scores(Stream.concat(
                        feedbackScoreBatch.scores().stream(),
                        traceIdToScoresMap.values().stream().flatMap(List::stream))
                        .toList())
                .build();

        createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

        int totalNumberOfScores = traceIdToScoresMap.size();

        var experimentItems = IntStream.range(0, totalNumberOfScores)
                .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(expectedExperiment.id())
                        .traceId(traces.get(i % traces.size()).id())
                        .feedbackScores(
                                traceIdToScoresMap.get(traces.get(i % traces.size()).id()).stream()
                                        .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                        .toList())
                        .build())
                .toList();

        var experimentItemsBatch = addRandomExperiments(experimentItems);

        createAndAssert(experimentItemsBatch, apiKey, workspaceName);

        Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment = getExpectedScoresPerExperiment(
                List.of(expectedExperiment), experimentItems);

        List<BigDecimal> quantities = getQuantities(traces.stream());

        var resultBuilder = expectedExperiment.toBuilder()
                .traceCount((long) traces.size())
                .duration(new PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2)))
                .totalEstimatedCost(null)
                .usage(null)
                .feedbackScores(
                        expectedScoresPerExperiment.get(expectedExperiment.id())
                                .entrySet()
                                .stream()
                                .map(e -> FeedbackScoreAverage.builder()
                                        .name(e.getKey())
                                        .value(avgFromList(List.of(e.getValue())))
                                        .build())
                                .toList());
        if (projectId != null) {
            resultBuilder.projectId(projectId);
        }
        return resultBuilder.build();
    }

    private Experiment createExperimentWithFeedbackScores(
            String apiKey, String workspaceName, String datasetName, String projectName, UUID projectId) {
        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetName(datasetName)
                .build();

        experimentResourceClient.create(experiment, apiKey, workspaceName);

        var expectedExperiment = getExperiment(experiment.id(), workspaceName, apiKey).toBuilder()
                .datasetName(null)
                .build();

        var traceBuilder = factory.manufacturePojo(Trace.class).toBuilder();
        if (projectName != null) {
            traceBuilder.projectName(projectName);
        }
        var trace = traceBuilder.build();

        var traces = List.of(trace);

        traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);

        var scoreForTrace1 = makeTraceScores(trace);

        var traceIdToScoresMap = Stream
                .of(scoreForTrace1.stream())
                .flatMap(Function.identity())
                .collect(groupingBy(FeedbackScoreBatchItem::id));

        var feedbackScoreBatch = factory.manufacturePojo(FeedbackScoreBatch.class);
        feedbackScoreBatch = feedbackScoreBatch.toBuilder()
                .scores(Stream.concat(
                        feedbackScoreBatch.scores().stream(),
                        traceIdToScoresMap.values().stream().flatMap(List::stream))
                        .toList())
                .build();

        createScoreAndAssert(feedbackScoreBatch, apiKey, workspaceName);

        int totalNumberOfScores = traceIdToScoresMap.size();

        var experimentItems = IntStream.range(0, totalNumberOfScores)
                .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(expectedExperiment.id())
                        .traceId(traces.getFirst().id())
                        .feedbackScores(
                                traceIdToScoresMap.get(traces.getFirst().id()).stream()
                                        .map(FeedbackScoreMapper.INSTANCE::toFeedbackScore)
                                        .toList())
                        .build())
                .toList();

        var experimentItemsBatch = addRandomExperiments(experimentItems);

        createAndAssert(experimentItemsBatch, apiKey, workspaceName);

        Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment = getExpectedScoresPerExperiment(
                List.of(expectedExperiment), experimentItems);

        List<BigDecimal> quantiles = getQuantities(traces.stream());

        var resultBuilder = expectedExperiment.toBuilder()
                .duration(new PercentageValues(quantiles.get(0), quantiles.get(1), quantiles.get(2)))
                .feedbackScores(expectedScoresPerExperiment.get(expectedExperiment.id()).entrySet().stream()
                        .map(e -> FeedbackScoreAverage.builder()
                                .name(e.getKey())
                                .value(avgFromList(List.of(e.getValue())))
                                .build())
                        .toList());
        if (projectId != null) {
            resultBuilder.projectId(projectId);
        }
        return resultBuilder.build();
    }

    private void assertExperiments(
            UUID datasetId,
            List<Experiment> expectedExperiments,
            List<Experiment> unexpectedExperiments,
            Map<UUID, Map<String, BigDecimal>> expectedScoresPerExperiment,
            List<Experiment> actualExperiments) {

        assertThat(actualExperiments).hasSize(expectedExperiments.size());

        assertThat(actualExperiments)
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                .isEqualTo(expectedExperiments);

        if (!unexpectedExperiments.isEmpty()) {
            assertThat(actualExperiments)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(EXPERIMENT_IGNORED_FIELDS)
                    .doesNotContainAnyElementsOf(unexpectedExperiments);
        }

        if (MapUtils.isNotEmpty(expectedScoresPerExperiment)) {
            for (Experiment experiment : actualExperiments) {
                var expectedScores = expectedScoresPerExperiment.get(experiment.id());
                var actualScores = getScoresMap(experiment);

                assertThat(actualScores)
                        .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                        .isEqualTo(expectedScores);
            }
        }
    }

    private Map<String, BigDecimal> getScoresMap(Experiment experiment) {
        List<FeedbackScoreAverage> feedbackScores = experiment.feedbackScores();
        if (feedbackScores != null) {
            return feedbackScores
                    .stream()
                    .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value));
        }
        return null;
    }

    static Stream<Arguments> experimentSortingFields() {
        return Stream.of(
                arguments(
                        Comparator.comparing(Experiment::name),
                        SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                arguments(
                        Comparator.comparing(Experiment::name).reversed(),
                        SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),
                arguments(
                        Comparator.comparing(Experiment::createdAt)
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                arguments(
                        Comparator.comparing(Experiment::createdAt).reversed()
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),
                arguments(
                        Comparator.comparing(Experiment::lastUpdatedAt)
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                .build()),
                arguments(
                        Comparator.comparing(Experiment::lastUpdatedAt).reversed()
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                .build()),
                arguments(
                        Comparator.comparing(Experiment::createdBy)
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC).build()),
                arguments(
                        Comparator.comparing(Experiment::createdBy).reversed()
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC).build()),
                arguments(
                        Comparator.comparing(Experiment::traceCount)
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.TRACE_COUNT).direction(Direction.ASC).build()),
                arguments(
                        Comparator.comparing(Experiment::traceCount).reversed()
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.TRACE_COUNT).direction(Direction.DESC)
                                .build()),
                arguments(
                        Comparator.comparing((Experiment e) -> e.duration().p50())
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field("duration.p50").direction(Direction.ASC).build()),
                arguments(
                        Comparator.comparing((Experiment e) -> e.duration().p50()).reversed()
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field("duration.p50").direction(Direction.DESC)
                                .build()),
                arguments(
                        Comparator.comparing((Experiment e) -> e.tags().stream().toList(),
                                com.comet.opik.api.resources.utils.ListComparators.ascending())
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC).build()),
                arguments(
                        Comparator.comparing((Experiment e) -> e.tags().stream().toList(),
                                com.comet.opik.api.resources.utils.ListComparators.descending())
                                .thenComparing(Comparator.comparing(Experiment::id).reversed())
                                .thenComparing(Comparator.comparing(Experiment::lastUpdatedAt).reversed()),
                        SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC).build()));
    }

    static Stream<Arguments> getValidFilters() {
        return Stream.of(
                Arguments.of(
                        (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                .field(ExperimentField.DATASET_ID)
                                .operator(Operator.EQUAL)
                                .value(experiment.datasetId().toString())
                                .build()),
                Arguments.of(
                        (Function<Experiment, ExperimentFilter>) experiment -> ExperimentFilter.builder()
                                .field(ExperimentField.PROMPT_IDS)
                                .operator(Operator.CONTAINS)
                                .value(experiment.promptVersion().promptId().toString())
                                .build()));
    }

    @Test
    @DisplayName("when getting experiments by project, then return only project experiments")
    void whenGettingExperimentsByProjectId__thenReturnExperiments() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var scoreForTrace = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class);
        var experiments = IntStream.range(0, 5)
                .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .build())
                .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment, scoreForTrace,
                        project.name(), projectId))
                .toList();

        var otherProject = factory.manufacturePojo(Project.class);
        var otherProjectId = projectResourceClient.createProject(otherProject, apiKey, workspaceName);
        var unexpectedExperiments = IntStream.range(0, 2)
                .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .build())
                .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment, scoreForTrace,
                        otherProject.name(), otherProjectId))
                .toList();

        var actualPage = experimentResourceClient.getProjectExperimentsWithSortingField(
                projectId, 1, experiments.size(), null, null, null, apiKey, workspaceName);

        assertThat(actualPage.total()).isEqualTo(experiments.size());
        assertExperiments(null, experiments.reversed(), unexpectedExperiments, Map.of(), actualPage.content());
    }

    @ParameterizedTest
    @MethodSource("experimentSortingFields")
    @DisplayName("when sorting by field and direction, then return page")
    void whenSortingByFieldAndDirection__thenReturnPage(
            Comparator<Experiment> comparator, SortingField sortingField) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var scoreForTrace = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class);
        var experiments = IntStream.range(0, 5)
                .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .build())
                .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment, scoreForTrace,
                        project.name(), projectId))
                .toList();

        var expectedExperiments = experiments.stream().sorted(comparator).toList();
        var expectedScores = expectedExperiments.stream()
                .map(experiment -> Map.entry(experiment.id(), experiment.feedbackScores()
                        .stream()
                        .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

        var actualPage = experimentResourceClient.getProjectExperimentsWithSortingField(
                projectId, 1, expectedExperiments.size(), null, List.of(sortingField), null, apiKey, workspaceName);

        assertThat(actualPage.total()).isEqualTo(expectedExperiments.size());
        assertThat(actualPage.size()).isEqualTo(expectedExperiments.size());
        assertExperiments(null, expectedExperiments, List.of(), expectedScores, actualPage.content());
    }

    @Test
    @DisplayName("when searching by name, then return matching experiments")
    void whenSearchingByName__thenReturnMatchingExperiments() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var name = RandomStringUtils.secure().nextAlphanumeric(10);
        var scoreForTrace = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class);
        var experiments = IntStream.range(0, 3)
                .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                        .name(name)
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .build())
                .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment, scoreForTrace,
                        project.name(), projectId))
                .toList();

        var unexpectedExperiments = IntStream.range(0, 2)
                .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .build())
                .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment, scoreForTrace,
                        project.name(), projectId))
                .toList();

        var actualPage = experimentResourceClient.getProjectExperimentsWithSortingField(
                projectId, 1, experiments.size() + unexpectedExperiments.size(), name, null, null, apiKey,
                workspaceName);

        assertThat(actualPage.total()).isEqualTo(experiments.size());
        assertExperiments(null, experiments.reversed(), unexpectedExperiments, Map.of(), actualPage.content());
    }

    @Test
    @DisplayName("when filtering by datasetId, then return only experiments with that dataset")
    void findByDatasetId() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var datasetName = RandomStringUtils.secure().nextAlphanumeric(10);

        var experiments = experimentResourceClient.generateExperimentList()
                .stream()
                .map(experiment -> experiment.toBuilder()
                        .datasetName(datasetName)
                        .projectName(project.name())
                        .projectId(projectId)
                        .build())
                .toList();
        experiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var unexpectedExperiments = List.of(experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .build());
        unexpectedExperiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                .datasetId();

        var pageSize = experiments.size() - 2;
        var expectedPage1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
        var expectedPage2 = experiments.subList(0, pageSize - 1).reversed();
        var expectedTotal = experiments.size();

        var page1 = experimentResourceClient.getProjectExperiments(projectId, 1, pageSize, datasetId, null, null,
                false, null, null, false, apiKey, workspaceName, SC_OK);
        assertThat(page1.total()).isEqualTo(expectedTotal);
        assertExperiments(datasetId, expectedPage1, unexpectedExperiments, Map.of(), page1.content());

        var page2 = experimentResourceClient.getProjectExperiments(projectId, 2, pageSize, datasetId, null, null,
                false, null, null, false, apiKey, workspaceName, SC_OK);
        assertThat(page2.total()).isEqualTo(expectedTotal);
        assertExperiments(datasetId, expectedPage2, unexpectedExperiments, Map.of(), page2.content());
    }

    @ParameterizedTest
    @MethodSource("findByFilterMetadata")
    @DisplayName("when filtering by metadata, then return matching experiments")
    void findByFilterMetadata(Operator operator, String key, String value) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var datasetName = RandomStringUtils.secure().nextAlphanumeric(10);
        var name = RandomStringUtils.secure().nextAlphanumeric(10);

        var experiments = experimentResourceClient.generateExperimentList()
                .stream()
                .map(experiment -> experiment.toBuilder()
                        .datasetName(datasetName)
                        .name(name)
                        .projectName(project.name())
                        .projectId(projectId)
                        .metadata(JsonUtils
                                .getJsonNodeFromString("{\"model\":[{\"year\":2024,\"version\":\"OpenAI, " +
                                        "Chat-GPT 4.0\",\"trueFlag\":true,\"nullField\":null}]}"))
                        .build())
                .toList();
        experiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var unexpectedExperiments = List.of(experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .build());
        unexpectedExperiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                .datasetId();
        var pageSize = experiments.size() - 2;
        var expectedPage1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
        var expectedPage2 = experiments.subList(0, pageSize - 1).reversed();
        var expectedTotal = experiments.size();

        var filters = List.of(ExperimentFilter.builder()
                .field(ExperimentField.METADATA)
                .operator(operator)
                .key(key)
                .value(value)
                .build());

        var page1 = experimentResourceClient.getProjectExperiments(projectId, 1, pageSize, datasetId, null, name,
                false, null, filters, false, apiKey, workspaceName, SC_OK);
        assertThat(page1.total()).isEqualTo(expectedTotal);
        assertExperiments(datasetId, expectedPage1, unexpectedExperiments, Map.of(), page1.content());

        var page2 = experimentResourceClient.getProjectExperiments(projectId, 2, pageSize, datasetId, null, name,
                false, null, filters, false, apiKey, workspaceName, SC_OK);
        assertThat(page2.total()).isEqualTo(expectedTotal);
        assertExperiments(datasetId, expectedPage2, unexpectedExperiments, Map.of(), page2.content());
    }

    private Stream<Arguments> findByFilterMetadata() {
        return Stream.of(
                Arguments.of(Operator.EQUAL, "$.model[0].version", "OPENAI, CHAT-GPT 4.0"),
                Arguments.of(Operator.NOT_EQUAL, "$.model[0].version", "OPENAI, CHAT-GPT Something"),
                Arguments.of(Operator.EQUAL, "model[0].year", "2024"),
                Arguments.of(Operator.NOT_EQUAL, "model[0].year", "2023"),
                Arguments.of(Operator.EQUAL, "model[0].trueFlag", "TRUE"),
                Arguments.of(Operator.NOT_EQUAL, "model[0].trueFlag", "FALSE"),
                Arguments.of(Operator.EQUAL, "model[0].nullField", "NULL"),
                Arguments.of(Operator.NOT_EQUAL, "$.model[0].version", "NULL"),
                Arguments.of(Operator.CONTAINS, "$.model[0].version", "CHAT-GPT"),
                Arguments.of(Operator.CONTAINS, "$.model[0].year", "02"),
                Arguments.of(Operator.CONTAINS, "$.model[0].trueFlag", "TRU"),
                Arguments.of(Operator.CONTAINS, "$.model[0].nullField", "NUL"),
                Arguments.of(Operator.NOT_CONTAINS, "$.model[0].version", "OPENAI, CHAT-GPT 2.0"),
                Arguments.of(Operator.STARTS_WITH, "$.model[0].version", "OPENAI, CHAT-GPT"),
                Arguments.of(Operator.ENDS_WITH, "$.model[0].version", "Chat-GPT 4.0"),
                Arguments.of(Operator.GREATER_THAN, "model[0].year", "2021"),
                Arguments.of(Operator.LESS_THAN, "model[0].year", "2031"));
    }

    @ParameterizedTest
    @MethodSource("metadataEmptyOperators")
    @DisplayName("when filtering by metadata with IS_EMPTY/IS_NOT_EMPTY, then return correct experiments")
    void findByFilterMetadataEmpty(Operator operator) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var experimentWithConfig = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .metadata(JsonUtils.getJsonNodeFromString("{\"config\":{\"name\":\"simulated\"}}"))
                .build();
        // metadata is null — config.name key is not present at all
        var experimentWithoutConfig = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .metadata(null)
                .build();

        experimentResourceClient.create(experimentWithConfig, apiKey, workspaceName);
        experimentResourceClient.create(experimentWithoutConfig, apiKey, workspaceName);

        var filters = List.of(ExperimentFilter.builder()
                .field(ExperimentField.METADATA)
                .operator(operator)
                .key("$.config.name")
                .value("")
                .build());

        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null, false,
                null, filters, false, apiKey, workspaceName, SC_OK);

        List<Experiment> expectedExperiments;
        List<Experiment> unexpectedExperiments;
        if (operator == Operator.IS_NOT_EMPTY) {
            expectedExperiments = List.of(experimentWithConfig);
            unexpectedExperiments = List.of(experimentWithoutConfig);
        } else {
            expectedExperiments = List.of(experimentWithoutConfig);
            unexpectedExperiments = List.of(experimentWithConfig);
        }

        assertThat(actualPage.total()).isEqualTo(1);
        assertExperiments(null, expectedExperiments, unexpectedExperiments, Map.of(), actualPage.content());
    }

    private Stream<Arguments> metadataEmptyOperators() {
        return Stream.of(
                Arguments.of(Operator.IS_NOT_EMPTY),
                Arguments.of(Operator.IS_EMPTY));
    }

    @ParameterizedTest
    @MethodSource("metadataEmptyNullAndBlankValueOperators")
    @DisplayName("when filtering metadata with IS_EMPTY/IS_NOT_EMPTY and key has null or blank value, then return correct experiments")
    void findByFilterMetadataEmptyWithNullAndBlankValues(Operator operator) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        // Key present with an actual value — not empty
        var experimentWithValue = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .metadata(JsonUtils.getJsonNodeFromString("{\"config\":{\"name\":\"simulated\"}}"))
                .build();
        // Key present but JSON null value — should count as empty
        var experimentWithNullValue = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .metadata(JsonUtils.getJsonNodeFromString("{\"config\":{\"name\":null}}"))
                .build();
        // Key present but empty string value — should count as empty
        var experimentWithEmptyValue = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .metadata(JsonUtils.getJsonNodeFromString("{\"config\":{\"name\":\"\"}}"))
                .build();

        experimentResourceClient.create(experimentWithValue, apiKey, workspaceName);
        experimentResourceClient.create(experimentWithNullValue, apiKey, workspaceName);
        experimentResourceClient.create(experimentWithEmptyValue, apiKey, workspaceName);

        var filters = List.of(ExperimentFilter.builder()
                .field(ExperimentField.METADATA)
                .operator(operator)
                .key("$.config.name")
                .value("")
                .build());

        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null, false,
                null, filters, false, apiKey, workspaceName, SC_OK);

        // Experiments are returned newest-first; creation order is: value, nullValue, emptyValue
        List<Experiment> expectedExperiments;
        List<Experiment> unexpectedExperiments;
        if (operator == Operator.IS_NOT_EMPTY) {
            expectedExperiments = List.of(experimentWithValue);
            unexpectedExperiments = List.of(experimentWithNullValue, experimentWithEmptyValue);
        } else {
            expectedExperiments = List.of(experimentWithEmptyValue, experimentWithNullValue);
            unexpectedExperiments = List.of(experimentWithValue);
        }

        assertThat(actualPage.total()).isEqualTo(expectedExperiments.size());
        assertExperiments(null, expectedExperiments, unexpectedExperiments, Map.of(), actualPage.content());
    }

    private Stream<Arguments> metadataEmptyNullAndBlankValueOperators() {
        return Stream.of(
                Arguments.of(Operator.IS_NOT_EMPTY),
                Arguments.of(Operator.IS_EMPTY));
    }

    @ParameterizedTest
    @MethodSource("findByFilterTags")
    @DisplayName("when filtering by tags, then return matching experiments")
    void findByFilterTags(Operator operator, String value) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var datasetName = RandomStringUtils.secure().nextAlphanumeric(10);
        var name = RandomStringUtils.secure().nextAlphanumeric(10);

        var experiments = experimentResourceClient.generateExperimentList()
                .stream()
                .map(experiment -> experiment.toBuilder()
                        .datasetName(datasetName)
                        .name(name)
                        .projectName(project.name())
                        .projectId(projectId)
                        .tags(Set.of("tag1", "tag2", "tag3"))
                        .build())
                .toList();
        experiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var unexpectedExperiments = List.of(experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .tags(Set.of("other"))
                .build());
        unexpectedExperiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var datasetId = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey)
                .datasetId();
        var pageSize = experiments.size() - 2;
        var expectedPage1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
        var expectedPage2 = experiments.subList(0, pageSize - 1).reversed();
        var expectedTotal = experiments.size();

        var filters = List.of(ExperimentFilter.builder()
                .field(ExperimentField.TAGS)
                .operator(operator)
                .value(value)
                .build());

        var page1 = experimentResourceClient.getProjectExperiments(projectId, 1, pageSize, datasetId, null, name,
                false, null, filters, false, apiKey, workspaceName, SC_OK);
        assertThat(page1.total()).isEqualTo(expectedTotal);
        assertExperiments(datasetId, expectedPage1, unexpectedExperiments, Map.of(), page1.content());

        var page2 = experimentResourceClient.getProjectExperiments(projectId, 2, pageSize, datasetId, null, name,
                false, null, filters, false, apiKey, workspaceName, SC_OK);
        assertThat(page2.total()).isEqualTo(expectedTotal);
        assertExperiments(datasetId, expectedPage2, unexpectedExperiments, Map.of(), page2.content());
    }

    private Stream<Arguments> findByFilterTags() {
        return Stream.of(
                Arguments.of(Operator.EQUAL, "tag1"),
                Arguments.of(Operator.NOT_EQUAL, "other"),
                Arguments.of(Operator.CONTAINS, "tag"),
                Arguments.of(Operator.NOT_CONTAINS, "other"));
    }

    @ParameterizedTest
    @MethodSource("tagsEmptyOperators")
    @DisplayName("when filtering by tags with IS_EMPTY/IS_NOT_EMPTY, then return correct experiments")
    void findByFilterTagsEmpty(Operator operator, boolean expectExperimentWithTags) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var experimentWithTags = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .tags(Set.of("tag1", "tag2"))
                .build();
        var experimentWithoutTags = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .tags(null)
                .build();

        experimentResourceClient.create(experimentWithTags, apiKey, workspaceName);
        experimentResourceClient.create(experimentWithoutTags, apiKey, workspaceName);

        var filters = List.of(ExperimentFilter.builder()
                .field(ExperimentField.TAGS)
                .operator(operator)
                .value("")
                .build());

        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null, false,
                null, filters, false, apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(1);
        assertThat(actualPage.content()).hasSize(1);

        var expectedExperimentId = expectExperimentWithTags
                ? experimentWithTags.id()
                : experimentWithoutTags.id();
        assertThat(actualPage.content().getFirst().id()).isEqualTo(expectedExperimentId);
    }

    private Stream<Arguments> tagsEmptyOperators() {
        return Stream.of(
                Arguments.of(Operator.IS_NOT_EMPTY, true),
                Arguments.of(Operator.IS_EMPTY, false));
    }

    @ParameterizedTest
    @MethodSource("getValidFilters")
    @DisplayName("when filtering by valid filter, then return matching experiments")
    void findByFiltering(Function<Experiment, ExperimentFilter> getFilter) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var datasetName = RandomStringUtils.secure().nextAlphanumeric(10);

        var prompt = buildPrompt();
        PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);
        PromptVersionLink versionLink = buildVersionLink(promptVersion, prompt.name());

        var experiments = experimentResourceClient.generateExperimentList()
                .stream()
                .map(experiment -> experiment.toBuilder()
                        .datasetName(datasetName)
                        .projectName(project.name())
                        .projectId(projectId)
                        .promptVersion(versionLink)
                        .promptVersions(List.of(versionLink))
                        .build())
                .toList();
        experiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var unexpectedExperiments = List.of(experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .build());
        unexpectedExperiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var pageSize = experiments.size() - 2;
        var experiment = getAndAssert(experiments.getFirst().id(), experiments.getFirst(), workspaceName, apiKey);
        var expectedPage1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
        var expectedPage2 = experiments.subList(0, pageSize - 1).reversed();
        var expectedTotal = experiments.size();

        var filters = List.of(getFilter.apply(experiment));

        var page1 = experimentResourceClient.getProjectExperiments(projectId, 1, pageSize, null, null, null,
                false, null, filters, false, apiKey, workspaceName, SC_OK);
        assertThat(page1.total()).isEqualTo(expectedTotal);
        assertExperiments(null, expectedPage1, unexpectedExperiments, Map.of(), page1.content());

        var page2 = experimentResourceClient.getProjectExperiments(projectId, 2, pageSize, null, null, null,
                false, null, filters, false, apiKey, workspaceName, SC_OK);
        assertThat(page2.total()).isEqualTo(expectedTotal);
        assertExperiments(null, expectedPage2, unexpectedExperiments, Map.of(), page2.content());
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("when filtering by optimization_id and type, then return matching experiments")
    void findByOptimizationIdAndType(ExperimentType type) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        UUID optimizationId = UUID.randomUUID();

        var experiments = experimentResourceClient.generateExperimentList()
                .stream()
                .map(experiment -> experiment.toBuilder()
                        .optimizationId(optimizationId)
                        .type(type)
                        .projectName(project.name())
                        .projectId(projectId)
                        .build())
                .toList();
        experiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var unexpectedExperiments = List.of(experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .projectId(projectId)
                .build());
        unexpectedExperiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var pageSize = experiments.size() - 2;
        var expectedPage1 = experiments.subList(pageSize - 1, experiments.size()).reversed();
        var expectedPage2 = experiments.subList(0, pageSize - 1).reversed();
        var expectedTotal = experiments.size();

        var page1 = experimentResourceClient.getProjectExperiments(projectId, 1, pageSize, null, Set.of(type),
                null, false, null, null, false, optimizationId, null, apiKey, workspaceName, SC_OK);
        assertThat(page1.total()).isEqualTo(expectedTotal);
        assertExperiments(null, expectedPage1, unexpectedExperiments, Map.of(), page1.content());

        var page2 = experimentResourceClient.getProjectExperiments(projectId, 2, pageSize, null, Set.of(type),
                null, false, null, null, false, optimizationId, null, apiKey, workspaceName, SC_OK);
        assertThat(page2.total()).isEqualTo(expectedTotal);
        assertExperiments(null, expectedPage2, unexpectedExperiments, Map.of(), page2.content());
    }

    private Stream<ExperimentType> findByOptimizationIdAndType() {
        return Stream.of(ExperimentType.TRIAL, ExperimentType.MINI_BATCH);
    }

    @Test
    @DisplayName("when filtering by experiment_ids, then return only matching experiments")
    void findByExperimentIds() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var allExperiments = IntStream.range(0, 5)
                .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                        .projectName(project.name())
                        .projectId(projectId)
                        .build())
                .toList();
        allExperiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var experimentIdsToFilter = Set.of(allExperiments.get(1).id(), allExperiments.get(3).id());
        var unexpectedExperiments = allExperiments.stream()
                .filter(e -> !experimentIdsToFilter.contains(e.id()))
                .toList();

        var page = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null,
                false, null, null, false, null, experimentIdsToFilter, apiKey, workspaceName, SC_OK);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.content()).hasSize(2);

        var actualIds = page.content().stream()
                .map(Experiment::id)
                .collect(Collectors.toSet());
        assertThat(actualIds).isEqualTo(experimentIdsToFilter);

        var unexpectedIds = unexpectedExperiments.stream()
                .map(Experiment::id)
                .collect(Collectors.toSet());
        assertThat(actualIds).doesNotContainAnyElementsOf(unexpectedIds);
    }

    @Test
    @DisplayName("when filtering by feedback_scores, then return only experiments with matching scores")
    void findByFeedbackScoresFilter() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var scoreName = "test_score_" + UUID.randomUUID().toString().substring(0, 8);
        var experimentScoreValues = List.of(
                new BigDecimal("0.8"),
                new BigDecimal("0.5"),
                new BigDecimal("0.3"));

        var experiments = new ArrayList<Experiment>();
        var traces = new ArrayList<Trace>();
        var experimentItems = new ArrayList<ExperimentItem>();
        var scores = new ArrayList<FeedbackScoreBatchItem>();

        for (int i = 0; i < experimentScoreValues.size(); i++) {
            var experiment = experimentResourceClient.createPartialExperiment()
                    .projectName(project.name())
                    .build();
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(project.name())
                    .build();

            experiments.add(experiment);
            traces.add(trace);

            experimentResourceClient.create(experiment, apiKey, workspaceName);

            experimentItems.add(factory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experiment.id())
                    .traceId(trace.id())
                    .build());

            scores.add(FeedbackScoreBatchItem.builder()
                    .id(trace.id())
                    .projectName(project.name())
                    .name(scoreName)
                    .value(experimentScoreValues.get(i))
                    .source(ScoreSource.SDK)
                    .build());
        }

        traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
        createAndAssert(new ExperimentItemsBatch(Set.copyOf(experimentItems)), apiKey, workspaceName);
        createScoreAndAssert(FeedbackScoreBatch.builder().scores(scores).build(), apiKey, workspaceName);

        var filterTestCases = List.of(
                new FilterTestCase(Operator.GREATER_THAN, "0.7", experiments.get(0).id(), 1),
                new FilterTestCase(Operator.EQUAL, "0.5", experiments.get(1).id(), 1),
                new FilterTestCase(Operator.LESS_THAN, "0.4", experiments.get(2).id(), 1),
                new FilterTestCase(Operator.GREATER_THAN_EQUAL, "0.5", null, 2),
                new FilterTestCase(Operator.LESS_THAN_EQUAL, "0.5", null, 2));

        for (var testCase : filterTestCases) {
            var filters = List.of(ExperimentFilter.builder()
                    .field(ExperimentField.FEEDBACK_SCORES)
                    .operator(testCase.operator())
                    .key(scoreName)
                    .value(testCase.filterValue())
                    .build());

            var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null,
                    false, null, filters, false, apiKey, workspaceName, SC_OK);

            assertThat(actualPage.total()).isEqualTo(testCase.expectedCount());
            assertThat(actualPage.content()).hasSize(testCase.expectedCount());

            if (testCase.expectedExperimentId() != null) {
                assertThat(actualPage.content().getFirst().id()).isEqualTo(testCase.expectedExperimentId());
            }
        }
    }

    private record FilterTestCase(Operator operator, String filterValue, UUID expectedExperimentId,
            int expectedCount) {
    }

    @ParameterizedTest
    @MethodSource("feedbackScoresEmptyOperators")
    @DisplayName("when filtering by feedback_scores with IS_EMPTY/IS_NOT_EMPTY, then return correct experiments")
    void findByFeedbackScoresEmptyFilter(Operator operator, boolean expectExperimentWithScores) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var experimentWithScores = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .build();
        var experimentWithoutScores = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .build();

        experimentResourceClient.create(experimentWithScores, apiKey, workspaceName);
        experimentResourceClient.create(experimentWithoutScores, apiKey, workspaceName);

        var traceWithScores = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(project.name())
                .build();
        var traceWithoutScores = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(project.name())
                .build();

        traceResourceClient.batchCreateTraces(List.of(traceWithScores, traceWithoutScores), apiKey, workspaceName);

        var experimentItemWithScores = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                .experimentId(experimentWithScores.id())
                .traceId(traceWithScores.id())
                .build();
        var experimentItemWithoutScores = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                .experimentId(experimentWithoutScores.id())
                .traceId(traceWithoutScores.id())
                .build();

        createAndAssert(new ExperimentItemsBatch(Set.of(experimentItemWithScores, experimentItemWithoutScores)),
                apiKey, workspaceName);

        var scoreName = "empty_test_score_" + UUID.randomUUID().toString().substring(0, 8);
        var score = FeedbackScoreBatchItem.builder()
                .id(traceWithScores.id())
                .projectName(project.name())
                .name(scoreName)
                .value(new BigDecimal("0.9"))
                .source(ScoreSource.SDK)
                .build();

        createScoreAndAssert(FeedbackScoreBatch.builder().scores(List.of(score)).build(), apiKey, workspaceName);

        var filters = List.of(ExperimentFilter.builder()
                .field(ExperimentField.FEEDBACK_SCORES)
                .operator(operator)
                .key(scoreName)
                .value("")
                .build());

        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null,
                false, null, filters, false, apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(1);
        assertThat(actualPage.content()).hasSize(1);

        var expectedExperimentId = expectExperimentWithScores
                ? experimentWithScores.id()
                : experimentWithoutScores.id();
        assertThat(actualPage.content().getFirst().id()).isEqualTo(expectedExperimentId);
    }

    private Stream<Arguments> feedbackScoresEmptyOperators() {
        return Stream.of(
                Arguments.of(Operator.IS_NOT_EMPTY, true),
                Arguments.of(Operator.IS_EMPTY, false));
    }

    @Test
    @DisplayName("when filtering by experiment_scores, then return only experiments with matching scores")
    void findByExperimentScoresFilter() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var scoreName = "exp_score_" + UUID.randomUUID().toString().substring(0, 8);
        var experimentScoreValues = List.of(
                new BigDecimal("0.85"),
                new BigDecimal("0.60"),
                new BigDecimal("0.35"));

        var experiments = new ArrayList<Experiment>();

        for (int i = 0; i < experimentScoreValues.size(); i++) {
            var experimentScore = ExperimentScore.builder()
                    .name(scoreName)
                    .value(experimentScoreValues.get(i))
                    .build();

            var experiment = experimentResourceClient.createPartialExperiment()
                    .projectName(project.name())
                    .experimentScores(List.of(experimentScore))
                    .build();

            experiments.add(experiment);
            experimentResourceClient.create(experiment, apiKey, workspaceName);
        }

        var filterTestCases = List.of(
                new FilterTestCase(Operator.GREATER_THAN, "0.75", experiments.get(0).id(), 1),
                new FilterTestCase(Operator.EQUAL, "0.60", experiments.get(1).id(), 1),
                new FilterTestCase(Operator.LESS_THAN, "0.40", experiments.get(2).id(), 1),
                new FilterTestCase(Operator.GREATER_THAN_EQUAL, "0.60", null, 2),
                new FilterTestCase(Operator.LESS_THAN_EQUAL, "0.60", null, 2));

        for (var testCase : filterTestCases) {
            var filters = List.of(ExperimentFilter.builder()
                    .field(ExperimentField.EXPERIMENT_SCORES)
                    .operator(testCase.operator())
                    .key(scoreName)
                    .value(testCase.filterValue())
                    .build());

            var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null,
                    false, null, filters, false, apiKey, workspaceName, SC_OK);

            assertThat(actualPage.total()).isEqualTo(testCase.expectedCount());
            assertThat(actualPage.content()).hasSize(testCase.expectedCount());

            if (testCase.expectedExperimentId() != null) {
                assertThat(actualPage.content().getFirst().id()).isEqualTo(testCase.expectedExperimentId());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("experimentScoresEmptyOperators")
    @DisplayName("when filtering by experiment_scores with IS_EMPTY/IS_NOT_EMPTY, then return correct experiments")
    void findByExperimentScoresEmptyFilter(Operator operator, boolean expectExperimentWithScores) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var scoreName = "empty_exp_score_" + UUID.randomUUID().toString().substring(0, 8);
        var experimentScoreWithScore = ExperimentScore.builder()
                .name(scoreName)
                .value(new BigDecimal("0.95"))
                .build();

        var experimentWithScores = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .experimentScores(List.of(experimentScoreWithScore))
                .build();

        experimentResourceClient.create(experimentWithScores, apiKey, workspaceName);

        var experimentWithoutScores = experimentResourceClient.createPartialExperiment()
                .projectName(project.name())
                .experimentScores(null)
                .build();

        experimentResourceClient.create(experimentWithoutScores, apiKey, workspaceName);

        var filters = List.of(ExperimentFilter.builder()
                .field(ExperimentField.EXPERIMENT_SCORES)
                .operator(operator)
                .key(scoreName)
                .value("")
                .build());

        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null,
                false, null, filters, false, apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(1);
        assertThat(actualPage.content()).hasSize(1);

        var expectedExperimentId = expectExperimentWithScores
                ? experimentWithScores.id()
                : experimentWithoutScores.id();
        assertThat(actualPage.content().getFirst().id()).isEqualTo(expectedExperimentId);
    }

    private Stream<Arguments> experimentScoresEmptyOperators() {
        return Stream.of(
                Arguments.of(Operator.IS_NOT_EMPTY, true),
                Arguments.of(Operator.IS_EMPTY, false));
    }

    @Test
    @DisplayName("when fetching all experiments by project, then return correct page")
    void findAll() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var experiments = experimentResourceClient.generateExperimentList()
                .stream()
                .map(e -> e.toBuilder().projectName(project.name()).build())
                .toList();
        experiments.forEach(e -> experimentResourceClient.create(e, apiKey, workspaceName));

        var pageSize = experiments.size();
        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, pageSize, null, null, null,
                false, null, null, false, apiKey, workspaceName, SC_OK);

        assertThat(actualPage.page()).isEqualTo(1);
        assertThat(actualPage.size()).isEqualTo(pageSize);
        assertThat(actualPage.total()).isEqualTo(pageSize);
        assertThat(actualPage.content()).hasSize(pageSize);
    }

    @Test
    @DisplayName("when searching by dataset deleted when there is none, then return empty page")
    void find__whenSearchingByDatasetDeletedWhenThereIsNone__thenReturnEmptyPage() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        getDatasets().forEach(dataset -> {
            datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

            experimentResourceClient.create(experimentResourceClient.createPartialExperiment()
                    .datasetName(dataset.name())
                    .projectName(project.name())
                    .build(), apiKey, workspaceName);
        });

        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null,
                true, null, null, false, apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(0);
        assertThat(actualPage.content()).isEmpty();
    }

    @Test
    @DisplayName("when searching by dataset deleted and result having experiments, then return page")
    void find__whenSearchingByDatasetDeletedAndResultHavingExperiments__thenReturnPage() {
        var workspaceName = "workspace-" + UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = "apiKey-" + UUID.randomUUID();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var datasets = new CopyOnWriteArrayList<Dataset>();
        var experiments = new CopyOnWriteArrayList<Experiment>();
        var experimentCount = 11;
        IntStream.range(0, experimentCount)
                .parallel()
                .forEach(i -> {
                    var dataset = buildDataset();
                    datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
                    datasets.add(dataset);
                    var experimentId = experimentResourceClient.create(
                            experimentResourceClient.createPartialExperiment()
                                    .datasetName(dataset.name())
                                    .projectName(project.name())
                                    .build(),
                            apiKey,
                            workspaceName);
                    experiments.add(getExperiment(experimentId, workspaceName, apiKey).toBuilder()
                            .datasetName(null)
                            .build());
                });

        datasetResourceClient.deleteDatasets(datasets, apiKey, workspaceName);

        experiments.sort(Comparator.comparing(Experiment::id).reversed());

        var actualPage = experimentResourceClient.getProjectExperiments(
                projectId, 1, experimentCount, null, null, null, true, null, null, false,
                apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(experimentCount);
        assertExperiments(null, experiments, List.of(), Map.of(), actualPage.content());
    }

    @Test
    @DisplayName("when searching by dataset deleted having feedback scores and result having datasets, then return page")
    void find__whenSearchingByDatasetDeletedHavingFeedbackScoresAndResultHavingDatasets__thenReturnPage() {
        var workspaceName = "workspace-" + UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = "apiKey-" + UUID.randomUUID();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var experimentCount = 11;
        var expectedMatchCount = 5;
        var unexpectedDatasetCount = experimentCount - expectedMatchCount;

        IntStream.range(0, unexpectedDatasetCount)
                .parallel()
                .forEach(i -> {
                    var dataset = buildDataset();
                    datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
                    createAndAssert(
                            experimentResourceClient.createPartialExperiment()
                                    .datasetName(dataset.name())
                                    .projectName(project.name())
                                    .projectId(projectId)
                                    .build(),
                            apiKey,
                            workspaceName);
                });

        var datasets = new CopyOnWriteArrayList<Dataset>();
        var experiments = new CopyOnWriteArrayList<Experiment>();
        IntStream.range(0, expectedMatchCount)
                .parallel()
                .forEach(i -> {
                    var dataset = buildDataset();
                    datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
                    datasets.add(dataset);
                    var expectedExperiment = createExperimentWithFeedbackScores(
                            apiKey, workspaceName, dataset.name(), project.name(), projectId);
                    experiments.add(expectedExperiment);
                });

        datasetResourceClient.deleteDatasets(datasets, apiKey, workspaceName);

        experiments.sort(Comparator.comparing(Experiment::id).reversed());
        var expectedScoresPerExperiment = experiments.stream()
                .collect(toMap(Experiment::id, experiment -> experiment.feedbackScores().stream()
                        .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))));

        var actualPage = experimentResourceClient.getProjectExperiments(
                projectId, 1, expectedMatchCount, null, null, null, true, null, null, false,
                apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(expectedMatchCount);
        assertExperiments(null, experiments, List.of(), expectedScoresPerExperiment, actualPage.content());
    }

    @Test
    @DisplayName("when experiments have span data, then return page with span aggregations")
    void find__whenExperimentsHaveSpanData__thenReturnPage() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var dataset = buildDataset();
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetName(dataset.name())
                .usage(null)
                .build();

        experimentResourceClient.create(experiment, apiKey, workspaceName);

        List<Span> spans = new ArrayList<>();
        List<Trace> traces = new ArrayList<>();

        IntStream.range(0, 5).forEach(i -> {
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(project.name())
                    .build();
            traceResourceClient.createTrace(trace, apiKey, workspaceName);

            var span = factory.manufacturePojo(Span.class).toBuilder()
                    .traceId(trace.id())
                    .projectName(trace.projectName())
                    .type(SpanType.llm)
                    .totalEstimatedCost(BigDecimal.valueOf(PodamUtils.getIntegerInRange(0, 10)))
                    .build();

            spanResourceClient.createSpan(span, apiKey, workspaceName);

            var experimentItem = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                    .experimentId(experiment.id())
                    .usage(null)
                    .traceId(trace.id())
                    .feedbackScores(null)
                    .build();

            experimentResourceClient.createExperimentItem(Set.of(experimentItem), apiKey, workspaceName);

            traces.add(trace);
            spans.add(span);
        });

        List<BigDecimal> quantiles = getQuantities(traces.stream());

        var expectedExperiment = experiment.toBuilder()
                .projectId(projectId)
                .duration(new PercentageValues(quantiles.get(0), quantiles.get(1), quantiles.get(2)))
                .totalEstimatedCost(getTotalEstimatedCost(spans))
                .totalEstimatedCostAvg(getTotalEstimatedCostAvg(spans))
                .usage(getUsage(spans))
                .build();

        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 1, null, null, null,
                false, null, null, false, apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(1);
        assertExperiments(null, List.of(expectedExperiment), List.of(), Map.of(), actualPage.content());
    }

    @ParameterizedTest
    @MethodSource("promptIdSearchTestCases")
    @DisplayName("when filtering by prompt_id via PROMPT_IDS filter, then return matching experiments")
    void find__whenSearchingByPromptIdAndResultHavingXExperiments__thenReturnPage(
            int experimentCount, int expectedMatchCount) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var dataset = buildDataset();
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        IntStream.range(0, experimentCount - expectedMatchCount)
                .parallel()
                .forEach(i -> {
                    var e = experimentResourceClient.createPartialExperiment()
                            .datasetName(dataset.name())
                            .projectName(project.name())
                            .projectId(projectId)
                            .build();
                    createAndAssert(e, apiKey, workspaceName);
                });

        var prompt = buildPrompt();
        PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);

        List<Experiment> expectedExperiments = IntStream.range(0, expectedMatchCount)
                .parallel()
                .mapToObj(i -> {
                    PromptVersionLink versionLink = buildVersionLink(promptVersion, prompt.name());
                    var e = experimentResourceClient.createPartialExperiment()
                            .datasetName(dataset.name())
                            .projectName(project.name())
                            .promptVersion(versionLink)
                            .promptVersions(List.of(versionLink))
                            .feedbackScores(null)
                            .build();

                    experimentResourceClient.create(e, apiKey, workspaceName);
                    return getExperiment(e.id(), workspaceName, apiKey);
                })
                .sorted(Comparator.comparing(Experiment::id).reversed())
                .toList();

        var filters = List.of(ExperimentFilter.builder()
                .field(ExperimentField.PROMPT_IDS)
                .operator(Operator.CONTAINS)
                .value(promptVersion.promptId().toString())
                .build());

        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1,
                Math.max(expectedMatchCount, 1), null, null, null, false, null, filters, false,
                apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(expectedMatchCount);
        assertExperiments(null, expectedExperiments, List.of(), Map.of(), actualPage.content());
    }

    @ParameterizedTest
    @MethodSource("promptIdSearchTestCases")
    @DisplayName("when filtering by prompt_id using new prompt version field, then return matching experiments")
    void find__whenSearchingByPromptIdUsingNewPromptVersionFieldAndResultHavingXExperiments__thenReturnPage(
            int experimentCount, int expectedMatchCount) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var dataset = buildDataset();
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        IntStream.range(0, experimentCount - expectedMatchCount)
                .parallel()
                .forEach(i -> {
                    var e = experimentResourceClient.createPartialExperiment()
                            .datasetName(dataset.name())
                            .projectName(project.name())
                            .projectId(projectId)
                            .build();
                    createAndAssert(e, apiKey, workspaceName);
                });

        var prompt = buildPrompt();
        var prompt2 = buildPrompt();
        PromptVersion promptVersion = promptResourceClient.createPromptVersion(prompt, apiKey, workspaceName);
        PromptVersion promptVersion2 = promptResourceClient.createPromptVersion(prompt2, apiKey, workspaceName);

        List<Experiment> expectedExperiments = IntStream.range(0, expectedMatchCount)
                .parallel()
                .mapToObj(i -> {
                    PromptVersionLink versionLink = buildVersionLink(promptVersion, prompt.name());
                    PromptVersionLink versionLink2 = buildVersionLink(promptVersion2, prompt2.name());

                    var e = experimentResourceClient.createPartialExperiment()
                            .datasetName(dataset.name())
                            .projectName(project.name())
                            .promptVersion(versionLink)
                            .promptVersions(List.of(versionLink, versionLink2))
                            .feedbackScores(null)
                            .build();

                    experimentResourceClient.create(e, apiKey, workspaceName);
                    return getExperiment(e.id(), workspaceName, apiKey);
                })
                .sorted(Comparator.comparing(Experiment::id).reversed())
                .toList();

        var filters1 = List.of(ExperimentFilter.builder()
                .field(ExperimentField.PROMPT_IDS)
                .operator(Operator.CONTAINS)
                .value(promptVersion.promptId().toString())
                .build());

        var page1 = experimentResourceClient.getProjectExperiments(projectId, 1,
                Math.max(expectedMatchCount, 1), null, null, null, false, null, filters1, false,
                apiKey, workspaceName, SC_OK);

        assertThat(page1.total()).isEqualTo(expectedMatchCount);
        assertExperiments(null, expectedExperiments, List.of(), Map.of(), page1.content());

        var filters2 = List.of(ExperimentFilter.builder()
                .field(ExperimentField.PROMPT_IDS)
                .operator(Operator.CONTAINS)
                .value(promptVersion2.promptId().toString())
                .build());

        var page2 = experimentResourceClient.getProjectExperiments(projectId, 1,
                Math.max(expectedMatchCount, 1), null, null, null, false, null, filters2, false,
                apiKey, workspaceName, SC_OK);

        assertThat(page2.total()).isEqualTo(expectedMatchCount);
        assertExperiments(null, expectedExperiments, List.of(), Map.of(), page2.content());
    }

    private Stream<Arguments> promptIdSearchTestCases() {
        return Stream.of(
                arguments(10, 0),
                arguments(10, 5));
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    @DisplayName("when sorting by feedback scores, then return page")
    void whenSortingByFeedbackScores__thenReturnPage(Direction direction) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var scoreForTrace = PodamFactoryUtils.manufacturePojoList(factory, FeedbackScoreBatchItem.class);
        var experiments = IntStream.range(0, 5)
                .mapToObj(i -> experimentResourceClient.createPartialExperiment()
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .build())
                .map(experiment -> generateFullExperiment(apiKey, workspaceName, experiment, scoreForTrace,
                        project.name(), projectId))
                .toList();

        var sortingField = new SortingField(
                "feedback_scores.%s".formatted(scoreForTrace.getFirst().name()),
                direction);

        Comparator<Experiment> comparing = Comparator.comparing(
                (Experiment experiment) -> experiment.feedbackScores()
                        .stream()
                        .filter(score -> score.name().equals(scoreForTrace.getFirst().name()))
                        .findFirst()
                        .map(FeedbackScoreAverage::value)
                        .orElse(null),
                direction == Direction.ASC
                        ? Comparator.nullsFirst(Comparator.naturalOrder())
                        : Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparing(Experiment::id).reversed());

        var expectedExperiments = experiments.stream().sorted(comparing).toList();
        var expectedScores = expectedExperiments.stream()
                .map(experiment -> Map.entry(experiment.id(), experiment.feedbackScores()
                        .stream()
                        .collect(toMap(FeedbackScoreAverage::name, FeedbackScoreAverage::value))))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

        var sorting = toURLEncodedQueryParam(List.of(sortingField));
        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1,
                expectedExperiments.size(), null, null, null, false, sorting, null, false,
                apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(expectedExperiments.size());
        assertExperiments(null, expectedExperiments, List.of(), expectedScores, actualPage.content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"feedback_scores", "feedback_score.dsfsdfd", "feedback_scores."})
    @DisplayName("when sorting by invalid feedback scores pattern, then ignore and return page")
    void whenSortingByInvalidFeedbackScoresPattern__thenIgnoreAndReturnPage(String field) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var sorting = toURLEncodedQueryParam(List.of(new SortingField(field, Direction.ASC)));
        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null,
                false, sorting, null, false, apiKey, workspaceName, SC_OK);

        assertThat(actualPage).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    @DisplayName("when sorting by experiment scores, then return page")
    void whenSortingByExperimentScores__thenReturnPage(Direction direction) {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = factory.manufacturePojo(Project.class);
        var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

        var scoreName = "accuracy";
        var experiments = IntStream.range(0, 5)
                .mapToObj(i -> {
                    var experiment = experimentResourceClient.createPartialExperiment()
                            .projectName(project.name())
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .build();
                    var experimentId = experimentResourceClient.create(experiment, apiKey, workspaceName);
                    var scoreValue = new BigDecimal(String.valueOf(0.90 + i * 0.01));
                    var experimentScores = List.of(
                            ExperimentScore.builder()
                                    .name(scoreName)
                                    .value(scoreValue)
                                    .build());
                    experimentResourceClient.updateExperiment(experimentId,
                            ExperimentUpdate.builder().experimentScores(experimentScores).build(),
                            apiKey, workspaceName, SC_NO_CONTENT);
                    return getExperiment(experimentId, workspaceName, apiKey);
                })
                .toList();

        var sortingField = new SortingField(
                "experiment_scores.%s".formatted(scoreName),
                direction);

        Comparator<Experiment> comparing = Comparator.comparing(
                (Experiment experiment) -> experiment.experimentScores()
                        .stream()
                        .filter(score -> score.name().equals(scoreName))
                        .findFirst()
                        .map(ExperimentScore::value)
                        .orElse(null),
                direction == Direction.ASC
                        ? Comparator.nullsFirst(Comparator.naturalOrder())
                        : Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparing(Experiment::id).reversed());

        var expectedExperiments = experiments.stream().sorted(comparing).toList();

        var sorting = toURLEncodedQueryParam(List.of(sortingField));
        var actualPage = experimentResourceClient.getProjectExperiments(projectId, 1,
                expectedExperiments.size(), null, null, null, false, sorting, null, false,
                apiKey, workspaceName, SC_OK);

        assertThat(actualPage.total()).isEqualTo(expectedExperiments.size());
        assertExperiments(null, expectedExperiments, List.of(), Map.of(), actualPage.content());
    }
}
