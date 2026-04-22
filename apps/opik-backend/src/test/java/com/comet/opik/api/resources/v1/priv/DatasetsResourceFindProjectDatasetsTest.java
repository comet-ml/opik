package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.filter.DatasetField;
import com.comet.opik.api.filter.DatasetFilter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DatasetDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.api.Dataset.DatasetPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Find Project Datasets")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetsResourceFindProjectDatasetsTest {

    private static final String USER = UUID.randomUUID().toString();

    private static final String[] DATASET_IGNORED_FIELDS = {"id", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "projectName", "experimentCount", "mostRecentExperimentAt", "lastCreatedExperimentAt",
            "datasetItemsCount", "lastCreatedOptimizationAt", "mostRecentOptimizationAt", "optimizationCount",
            "status", "latestVersion"};

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private DatasetResourceClient datasetResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TransactionTemplate mySqlTemplate;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplate mySqlTemplate) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.mySqlTemplate = mySqlTemplate;
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);

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

    private List<Dataset> buildDatasets() {
        return DatasetResourceClient.buildDatasetList(factory);
    }

    private UUID createAndAssert(Dataset dataset, String apiKey, String workspaceName) {
        return datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
    }

    private void findAndAssertPage(DatasetPage actualEntity, int expected, int total, int page,
            List<Dataset> expectedContent) {
        assertThat(actualEntity.size()).isEqualTo(expected);
        assertThat(actualEntity.content()).hasSize(expected);
        assertThat(actualEntity.page()).isEqualTo(page);
        assertThat(actualEntity.total()).isEqualTo(total);

        assertThat(actualEntity.content())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(DATASET_IGNORED_FIELDS)
                .isEqualTo(expectedContent);
    }

    private void saveDatasetsLastOptimizationCreated(
            Set<DatasetLastOptimizationCreated> datasetsLastOptimizationCreated, String workspaceId) {
        mySqlTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            dao.recordOptimizations(workspaceId, datasetsLastOptimizationCreated);
            return null;
        });
    }

    private void saveDatasetsLastExperimentCreated(Set<DatasetLastExperimentCreated> datasetsLastExperimentCreated,
            String workspaceId) {
        mySqlTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            dao.recordExperiments(workspaceId, datasetsLastExperimentCreated);
            return null;
        });
    }

    @Test
    @DisplayName("Success")
    void getProjectDatasets() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);
        var otherProjectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        List<Dataset> expected1 = buildDatasets().stream()
                .map(d -> d.toBuilder().projectId(projectId).build())
                .toList();
        List<Dataset> expected2 = buildDatasets().stream()
                .map(d -> d.toBuilder().projectId(projectId).build())
                .toList();

        expected1.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));
        expected2.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

        // Datasets in another project — must not appear in results
        buildDatasets().stream()
                .map(d -> d.toBuilder().projectId(otherProjectId).build())
                .forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

        Dataset dataset = buildDataset().toBuilder()
                .name("The most expressive LLM: " + UUID.randomUUID()
                        + " \uD83D\uDE05\uD83E\uDD23\uD83D\uDE02\uD83D\uDE42\uD83D\uDE43\uD83E\uDEE0")
                .description("Emoji Test \uD83E\uDD13\uD83E\uDDD0")
                .projectId(projectId)
                .build();

        createAndAssert(dataset, apiKey, workspaceName);

        int defaultPageSize = 10;
        var actualPage = datasetResourceClient.getProjectDatasets(projectId, 1, defaultPageSize,
                workspaceName, apiKey);

        var expectedContent = new ArrayList<Dataset>();
        expectedContent.add(dataset);

        expected2.reversed()
                .stream()
                .filter(__ -> expectedContent.size() < defaultPageSize)
                .forEach(expectedContent::add);

        expected1.reversed()
                .stream()
                .filter(__ -> expectedContent.size() < defaultPageSize)
                .forEach(expectedContent::add);

        findAndAssertPage(actualPage, defaultPageSize, expectedContent.size() + 1, 1, expectedContent);
    }

    @Test
    @DisplayName("when limit is 5 but there are N datasets, then return 5 datasets and total N")
    void getProjectDatasets__whenLimitIs5ButThereAre10Datasets__thenReturn5DatasetsAndTotal10() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        List<Dataset> expected1 = buildDatasets().stream()
                .map(d -> d.toBuilder().projectId(projectId).build())
                .toList();
        List<Dataset> expected2 = buildDatasets().stream()
                .map(d -> d.toBuilder().projectId(projectId).build())
                .toList();

        expected1.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));
        expected2.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

        int pageSize = 5;
        var actualPage = datasetResourceClient.getProjectDatasets(projectId, 1, pageSize, workspaceName,
                apiKey);
        var expectedContent = new ArrayList<>(expected2.reversed().subList(0, pageSize));

        findAndAssertPage(actualPage, pageSize, expected1.size() + expected2.size(), 1, expectedContent);
    }

    @Test
    @DisplayName("when fetching all datasets, then return datasets sorted by created date")
    void getProjectDatasets__whenFetchingAllDatasets__thenReturnDatasetsSortedByCreatedDate() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        List<Dataset> expected = buildDatasets().stream()
                .map(d -> d.toBuilder().projectId(projectId).build())
                .toList();
        expected.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

        var actualPage = datasetResourceClient.getProjectDatasets(projectId, 1, expected.size(), workspaceName,
                apiKey);

        findAndAssertPage(actualPage, expected.size(), expected.size(), 1, expected.reversed());
    }

    @ParameterizedTest
    @MethodSource("sortingFieldsProvider")
    @DisplayName("when fetching all datasets, then return datasets sorted by valid fields")
    void getProjectDatasets__whenFetchingAllDatasets__thenReturnDatasetsSortedByValidFields(
            Comparator<Dataset> comparator,
            SortingField sorting) {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        List<Dataset> datasets = buildDatasets().stream()
                .map(d -> d.toBuilder()
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .projectId(projectId)
                        .build())
                .toList();

        datasets.forEach(dataset -> {
            var id = createAndAssert(dataset, apiKey, workspaceName);
            saveDatasetsLastOptimizationCreated(
                    Set.of(new DatasetLastOptimizationCreated(id, Instant.now())), workspaceId);
            saveDatasetsLastExperimentCreated(
                    Set.of(new DatasetLastExperimentCreated(id, Instant.now())), workspaceId);
        });

        datasets = datasets.stream().sorted(comparator).toList();

        var actualPage = datasetResourceClient.getProjectDatasetsWithSortingField(
                projectId, datasets.size(), List.of(sorting), null, workspaceName, apiKey);

        findAndAssertPage(actualPage, datasets.size(), datasets.size(), 1, datasets);
    }

    @ParameterizedTest
    @MethodSource("validFiltersProvider")
    @DisplayName("when fetching all datasets, then return datasets filtered")
    void whenFilterProjectDatasets__thenReturnDatasetsFiltered(
            Function<List<Dataset>, DatasetFilter> getFilter,
            Function<List<Dataset>, List<Dataset>> getExpectedDatasets) {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        List<Dataset> datasets = buildDatasets().stream()
                .map(d -> d.toBuilder().projectId(projectId).build())
                .toList();

        Set<DatasetLastOptimizationCreated> datasetsLastOptimizationCreated = new HashSet<>();
        Set<DatasetLastExperimentCreated> datasetsLastExperimentCreated = new HashSet<>();

        datasets.forEach(dataset -> {
            var id = createAndAssert(dataset, apiKey, workspaceName);
            datasetsLastOptimizationCreated.add(new DatasetLastOptimizationCreated(id, Instant.now()));
            datasetsLastExperimentCreated.add(new DatasetLastExperimentCreated(id, Instant.now()));
        });

        saveDatasetsLastOptimizationCreated(datasetsLastOptimizationCreated, workspaceId);
        saveDatasetsLastExperimentCreated(datasetsLastExperimentCreated, workspaceId);

        List<Dataset> expectedDatasets = getExpectedDatasets.apply(datasets).reversed();
        DatasetFilter filter = getFilter.apply(datasets);

        var actualPage = datasetResourceClient.getProjectDatasetsWithSortingField(
                projectId, Math.max(1, expectedDatasets.size()), null, List.of(filter),
                workspaceName, apiKey);

        findAndAssertPage(actualPage, expectedDatasets.size(), expectedDatasets.size(), 1, expectedDatasets);
    }

    static Stream<Arguments> sortingFieldsProvider() {
        Comparator<Dataset> idComparator = Comparator.comparing(Dataset::id);
        Comparator<Dataset> nameComparator = Comparator.comparing(Dataset::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<Dataset> descriptionComparator = Comparator.comparing(Dataset::description,
                String.CASE_INSENSITIVE_ORDER);
        Comparator<Dataset> tagsComparator = Comparator.comparing(d -> d.tags().toString(),
                String.CASE_INSENSITIVE_ORDER);
        Comparator<Dataset> createdAtComparator = Comparator.comparing(Dataset::createdAt);
        Comparator<Dataset> createdByComparator = Comparator.comparing(Dataset::createdBy,
                String.CASE_INSENSITIVE_ORDER);
        Comparator<Dataset> lastUpdatedAtComparator = Comparator.comparing(Dataset::lastUpdatedAt);
        Comparator<Dataset> lastUpdatedByComparator = Comparator.comparing(Dataset::lastUpdatedBy,
                String.CASE_INSENSITIVE_ORDER);
        Comparator<Dataset> lastCreatedExperimentAtComparator = Comparator.comparing(
                Dataset::lastCreatedExperimentAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<Dataset> lastCreatedOptimizationAtComparator = Comparator.comparing(
                Dataset::lastCreatedOptimizationAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<Dataset> idComparatorReversed = Comparator.comparing(Dataset::id).reversed();

        return Stream.of(
                Arguments.of(
                        idComparator,
                        SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                Arguments.of(
                        idComparator.reversed(),
                        SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),
                Arguments.of(
                        nameComparator.thenComparing(idComparatorReversed),
                        SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                Arguments.of(
                        nameComparator.reversed().thenComparing(idComparatorReversed),
                        SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),
                Arguments.of(
                        descriptionComparator,
                        SortingField.builder().field(SortableFields.DESCRIPTION).direction(Direction.ASC).build()),
                Arguments.of(
                        descriptionComparator.reversed(),
                        SortingField.builder().field(SortableFields.DESCRIPTION).direction(Direction.DESC).build()),
                Arguments.of(
                        tagsComparator,
                        SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC).build()),
                Arguments.of(
                        tagsComparator.reversed(),
                        SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC).build()),
                Arguments.of(
                        createdAtComparator,
                        SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                Arguments.of(
                        createdAtComparator.reversed(),
                        SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),
                Arguments.of(
                        createdByComparator.thenComparing(idComparatorReversed),
                        SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC).build()),
                Arguments.of(
                        createdByComparator.reversed().thenComparing(idComparatorReversed),
                        SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC).build()),
                Arguments.of(
                        lastUpdatedAtComparator,
                        SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                .build()),
                Arguments.of(
                        lastUpdatedAtComparator.reversed(),
                        SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                .build()),
                Arguments.of(
                        lastUpdatedByComparator.thenComparing(idComparatorReversed),
                        SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.ASC)
                                .build()),
                Arguments.of(
                        lastUpdatedByComparator.reversed().thenComparing(idComparatorReversed),
                        SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.DESC)
                                .build()),
                Arguments.of(
                        lastCreatedExperimentAtComparator,
                        SortingField.builder().field(SortableFields.LAST_CREATED_EXPERIMENT_AT)
                                .direction(Direction.ASC).build()),
                Arguments.of(
                        lastCreatedExperimentAtComparator.reversed(),
                        SortingField.builder().field(SortableFields.LAST_CREATED_EXPERIMENT_AT)
                                .direction(Direction.DESC).build()),
                Arguments.of(
                        lastCreatedOptimizationAtComparator,
                        SortingField.builder().field(SortableFields.LAST_CREATED_OPTIMIZATION_AT)
                                .direction(Direction.ASC).build()),
                Arguments.of(
                        lastCreatedOptimizationAtComparator.reversed(),
                        SortingField.builder().field(SortableFields.LAST_CREATED_OPTIMIZATION_AT)
                                .direction(Direction.DESC).build()));
    }

    static Stream<Arguments> validFiltersProvider() {
        return Stream.of(
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.TAGS)
                                .operator(Operator.CONTAINS)
                                .value(datasets.getFirst().tags().iterator().next())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> List.of(datasets.getFirst())),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.TAGS)
                                .operator(Operator.NOT_CONTAINS)
                                .value(datasets.getFirst().tags().iterator().next())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets.subList(1, datasets.size())),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.ID)
                                .operator(Operator.EQUAL)
                                .value(datasets.getFirst().id().toString())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> List.of(datasets.getFirst())),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.ID)
                                .operator(Operator.NOT_EQUAL)
                                .value(datasets.getFirst().id().toString())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets.subList(1, datasets.size())),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.NAME)
                                .operator(Operator.EQUAL)
                                .value(datasets.getFirst().name())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> List.of(datasets.getFirst())),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.NAME)
                                .operator(Operator.NOT_EQUAL)
                                .value(datasets.getFirst().name())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets.subList(1, datasets.size())),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.NAME)
                                .operator(Operator.CONTAINS)
                                .value(datasets.getFirst().name().substring(0, 3))
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets.stream()
                                .filter(dataset -> dataset.name()
                                        .contains(datasets.getFirst().name().substring(0, 3)))
                                .toList()),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.DESCRIPTION)
                                .operator(Operator.EQUAL)
                                .value(datasets.getFirst().description())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> List.of(datasets.getFirst())),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.DESCRIPTION)
                                .operator(Operator.NOT_EQUAL)
                                .value(datasets.getFirst().description())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets.subList(1, datasets.size())),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.DESCRIPTION)
                                .operator(Operator.CONTAINS)
                                .value(datasets.getFirst().description() != null
                                        ? datasets.getFirst().description().substring(0,
                                                Math.min(3, datasets.getFirst().description().length()))
                                        : "test")
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets.stream()
                                .filter(dataset -> {
                                    String searchValue = datasets.getFirst().description() != null
                                            ? datasets.getFirst().description().substring(0,
                                                    Math.min(3, datasets.getFirst().description().length()))
                                            : "test";
                                    return dataset.description() != null
                                            && dataset.description().contains(searchValue);
                                })
                                .toList()),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.CREATED_AT)
                                .operator(Operator.NOT_EQUAL)
                                .value(Instant.now().toString())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.CREATED_AT)
                                .operator(Operator.GREATER_THAN)
                                .value(Instant.now().minus(5, ChronoUnit.SECONDS).toString())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.CREATED_BY)
                                .operator(Operator.EQUAL)
                                .value(USER)
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.CREATED_BY)
                                .operator(Operator.NOT_EQUAL)
                                .value(USER)
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> List.of()),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.CREATED_BY)
                                .operator(Operator.CONTAINS)
                                .value(USER.substring(0, 3))
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.LAST_UPDATED_AT)
                                .operator(Operator.GREATER_THAN_EQUAL)
                                .value(Instant.now().toString())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> List.of()),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.LAST_UPDATED_AT)
                                .operator(Operator.LESS_THAN)
                                .value(Instant.now().toString())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.LAST_UPDATED_BY)
                                .operator(Operator.EQUAL)
                                .value(USER)
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.LAST_UPDATED_BY)
                                .operator(Operator.NOT_EQUAL)
                                .value(USER)
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> List.of()),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.LAST_UPDATED_BY)
                                .operator(Operator.CONTAINS)
                                .value(USER.substring(0, 3))
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.LAST_CREATED_EXPERIMENT_AT)
                                .operator(Operator.LESS_THAN)
                                .value(Instant.now().toString())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.LAST_CREATED_OPTIMIZATION_AT)
                                .operator(Operator.LESS_THAN)
                                .value(Instant.now().toString())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.TYPE)
                                .operator(Operator.EQUAL)
                                .value(datasets.getFirst().type().getValue())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets.stream()
                                .filter(d -> d.type() == datasets.getFirst().type())
                                .toList()),
                Arguments.of(
                        (Function<List<Dataset>, DatasetFilter>) datasets -> DatasetFilter.builder()
                                .field(DatasetField.TYPE)
                                .operator(Operator.NOT_EQUAL)
                                .value(datasets.getFirst().type().getValue())
                                .build(),
                        (Function<List<Dataset>, List<Dataset>>) datasets -> datasets.stream()
                                .filter(d -> d.type() != datasets.getFirst().type())
                                .toList()));
    }

    @Test
    @DisplayName("when searching by dataset name, then return matching datasets")
    void getProjectDatasets__whenSearchingByDatasetName__thenReturnMatchingDatasets() {
        UUID datasetSuffix = UUID.randomUUID();
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        List<Dataset> datasets = List.of(
                buildDataset().toBuilder()
                        .name("MySQL, realtime chatboot: " + datasetSuffix).projectId(projectId).build(),
                buildDataset().toBuilder()
                        .name("Chatboot using mysql: " + datasetSuffix).projectId(projectId).build(),
                buildDataset().toBuilder()
                        .name("Chatboot MYSQL expert: " + datasetSuffix).projectId(projectId).build(),
                buildDataset().toBuilder()
                        .name("Chatboot expert (my SQL): " + datasetSuffix).projectId(projectId).build(),
                buildDataset().toBuilder()
                        .name("Chatboot expert: " + datasetSuffix).projectId(projectId).build());

        datasets.forEach(dataset -> createAndAssert(dataset, apiKey, workspaceName));

        var actualPage = datasetResourceClient.getProjectDatasets(projectId, 1, 100, "MySql", null, null,
                workspaceName, apiKey);

        assertThat(actualPage.total()).isEqualTo(3);
        assertThat(actualPage.size()).isEqualTo(3);
        assertThat(actualPage.content().stream().map(Dataset::name).toList()).contains(
                "MySQL, realtime chatboot: " + datasetSuffix,
                "Chatboot using mysql: " + datasetSuffix,
                "Chatboot MYSQL expert: " + datasetSuffix);
    }
}
