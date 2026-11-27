package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.DatasetDAO;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.function.TriFunction;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.resources.DatasetResourceClient.assertErrorResponse;
import static com.comet.opik.api.resources.utils.resources.DatasetResourceClient.buildAmbiguousDatasetNameError;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project-scoped dataset operations")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetsResourceProjectScopedTest {

    private static final String[] DATASET_IGNORED_FIELDS = {"id", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "projectName", "experimentCount", "mostRecentExperimentAt", "lastCreatedExperimentAt",
            "datasetItemsCount", "lastCreatedOptimizationAt", "mostRecentOptimizationAt", "optimizationCount",
            "status", "latestVersion"};

    private static final String[] DATASET_ITEM_IGNORED_FIELDS = {"id", "datasetItemId", "createdAt", "lastUpdatedAt",
            "createdBy", "lastUpdatedBy", "experimentItems", "runSummariesByExperiment", "datasetId"};

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private DatasetResourceClient datasetResourceClient;
    private ProjectResourceClient projectResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private OptimizationResourceClient optimizationResourceClient;
    private TransactionTemplate mySqlTemplate;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplate mySqlTemplate) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        this.optimizationResourceClient = new OptimizationResourceClient(client, baseURI, factory);
        this.mySqlTemplate = mySqlTemplate;

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), apiKey, workspaceName, workspaceId,
                UUID.randomUUID().toString());
    }

    private Dataset buildDataset() {
        return DatasetResourceClient.buildDataset(factory);
    }

    private void assertDataset(Dataset actual, Dataset expected) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(DATASET_IGNORED_FIELDS)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("Create dataset with project_id persists and returns project_id")
    void createDatasetWithProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var dataset = buildDataset().toBuilder()
                .id(null)
                .projectId(projectId)
                .build();

        var id = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        var fetchedDataset = datasetResourceClient.getDatasetById(id, apiKey, workspaceName);

        assertDataset(fetchedDataset, dataset);
    }

    @Test
    @DisplayName("Create dataset with non-existing project_id returns not found")
    void createDatasetWithNonExistingProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var dataset = buildDataset().toBuilder()
                .id(null)
                .projectId(factory.manufacturePojo(UUID.class))
                .build();

        try (var response = datasetResourceClient.callCreateDataset(dataset, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Create dataset with project_name of existing project resolves project_id")
    void createDatasetWithExistingProjectName() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        var dataset = buildDataset().toBuilder()
                .id(null)
                .projectName(projectName)
                .build();

        var id = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        var fetchedDataset = datasetResourceClient.getDatasetById(id, apiKey, workspaceName);

        var expectedDataset = dataset.toBuilder()
                .projectId(projectId)
                .build();
        assertDataset(fetchedDataset, expectedDataset);
    }

    @Test
    @DisplayName("Create dataset with project_name of non-existing project creates project and resolves project_id")
    void createDatasetWithNonExistingProjectName() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "new-project-" + UUID.randomUUID();

        var dataset = buildDataset().toBuilder()
                .id(null)
                .projectName(projectName)
                .build();

        var id = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        var fetchedDataset = datasetResourceClient.getDatasetById(id, apiKey, workspaceName);

        // Verify the project was created and the projectId was resolved
        assertThat(fetchedDataset.projectId()).isNotNull();

        var expectedDataset = dataset.toBuilder()
                .projectId(fetchedDataset.projectId())
                .build();
        assertDataset(fetchedDataset, expectedDataset);
    }

    @Test
    @DisplayName("Find datasets filtered by project_id returns only project datasets")
    void findDatasetsByProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);
        var otherProjectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        var projectDataset = buildDataset().toBuilder()
                .id(null)
                .projectId(projectId)
                .build();
        datasetResourceClient.createDataset(projectDataset, apiKey, workspaceName);

        var otherProjectDataset = buildDataset().toBuilder()
                .id(null)
                .projectId(otherProjectId)
                .build();
        datasetResourceClient.createDataset(otherProjectDataset, apiKey, workspaceName);

        var workspaceDataset = buildDataset().toBuilder()
                .id(null)
                .projectId(null)
                .build();
        datasetResourceClient.createDataset(workspaceDataset, apiKey, workspaceName);

        var page = datasetResourceClient.getDatasetsByProjectId(projectId, workspaceName, apiKey);

        assertThat(page.content()).hasSize(1);
        assertDataset(page.content().getFirst(), projectDataset);
    }

    @Test
    @DisplayName("Given workspace-level dataset without project_name, when putting items, then items are returned on get")
    void putDatasetItems__datasetCreatedWithoutProjectName__itemsReturnedOnGet() {
        // Given
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String datasetName = "dataset-" + UUID.randomUUID();
        var dataset = buildDataset().toBuilder()
                .id(null)
                .projectId(null)
                .projectName(null)
                .name(datasetName)
                .build();
        var datasetId = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        var item = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .id(null)
                .tags(Set.of())
                .build();

        var batch = DatasetItemBatch.builder()
                .datasetName(datasetName)
                .items(List.of(item))
                .build();

        // When
        datasetResourceClient.createDatasetItems(batch, workspaceName, apiKey);

        // Then
        var itemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 10, null, apiKey, workspaceName);

        assertThat(itemsPage.content()).hasSize(1);
        assertThat(itemsPage.content())
                .usingRecursiveComparison()
                .ignoringFields(DATASET_ITEM_IGNORED_FIELDS)
                .isEqualTo(List.of(item));
    }

    @Test
    @DisplayName("Put dataset items with project_name implicitly creates dataset scoped to that project")
    void putDatasetItemsWithProjectNameScopesDatasetToProject() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        String datasetName = "dataset-" + UUID.randomUUID();

        var item = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .id(null)
                .build();

        var batch = DatasetItemBatch.builder()
                .datasetName(datasetName)
                .projectName(projectName)
                .items(List.of(item))
                .build();

        datasetResourceClient.createDatasetItems(batch, workspaceName, apiKey);

        var dataset = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetName).build(), apiKey, workspaceName);

        assertThat(dataset.projectId()).isEqualTo(projectId);
    }

    @Test
    @DisplayName("Create dataset without a project creates it in the Default Project")
    void createDatasetWithoutProjectCreatesItInDefaultProject() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var dataset = buildDataset().toBuilder().id(null).projectId(null).projectName(null).build();

        var id = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        var defaultProjectId = projectResourceClient.getByName(DEFAULT_PROJECT, apiKey, workspaceName).id();
        var expectedDataset = dataset.toBuilder().projectId(defaultProjectId).build();
        assertDataset(datasetResourceClient.getDatasetById(id, apiKey, workspaceName), expectedDataset);
    }

    @Test
    @DisplayName("Put items without a project into a new dataset name creates the dataset in the Default Project")
    void putDatasetItemsWithoutProjectCreatesDatasetInDefaultProject() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String datasetName = "dataset-" + UUID.randomUUID();
        var item = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .id(null)
                .tags(Set.of())
                .build();

        datasetResourceClient.createDatasetItems(DatasetItemBatch.builder()
                .datasetName(datasetName)
                .items(List.of(item))
                .build(), workspaceName, apiKey);

        var defaultProjectId = projectResourceClient.getByName(DEFAULT_PROJECT, apiKey, workspaceName).id();
        var expectedDataset = Dataset.builder()
                .name(datasetName)
                .projectId(defaultProjectId)
                .type(DatasetType.DATASET)
                .visibility(Visibility.PRIVATE)
                .build();
        var page = datasetResourceClient.getDatasetsByProjectId(defaultProjectId, workspaceName, apiKey);
        assertThat(page.content())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(DATASET_IGNORED_FIELDS)
                .containsExactly(expectedDataset);
    }

    @Test
    @DisplayName("Datasets with the same name can exist in different projects")
    void createDatasetsWithSameNameInDifferentProjects() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var datasets = createDatasetInProjects(2, apiKey, workspaceName);

        datasets.forEach(expected -> assertDataset(datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(expected.name()).projectName(expected.projectName()).build(),
                apiKey, workspaceName), expected));
    }

    Stream<Arguments> nameOnlyCalls() {
        return Stream.of(
                Arguments.of(Named.of("retrieve",
                        (TriFunction<DatasetIdentifier, String, String, Response>) (identifier, apiKey,
                                workspaceName) -> datasetResourceClient.callGetDatasetByIdentifier(identifier, apiKey,
                                        workspaceName))),
                Arguments.of(Named.of("delete",
                        (TriFunction<DatasetIdentifier, String, String, Response>) (identifier, apiKey,
                                workspaceName) -> datasetResourceClient.callDeleteDatasetByIdentifier(identifier,
                                        apiKey, workspaceName))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nameOnlyCalls")
    @DisplayName("A call by name without a project is rejected when the name exists in several projects")
    void callByNameWithoutProjectReturnsConflictWhenNameIsInSeveralProjects(
            TriFunction<DatasetIdentifier, String, String, Response> call) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var datasets = createDatasetInProjects(2, apiKey, workspaceName);
        String datasetName = datasets.getFirst().name();

        try (var response = call.apply(DatasetIdentifier.builder().datasetName(datasetName).build(), apiKey,
                workspaceName)) {
            assertErrorResponse(response, HttpStatus.SC_CONFLICT, buildAmbiguousDatasetNameError(datasetName));
        }

        datasets.forEach(dataset -> assertDataset(
                datasetResourceClient.getDatasetById(dataset.id(), apiKey, workspaceName), dataset));
    }

    @FunctionalInterface
    private interface WriteByDatasetName {
        Response apply(String datasetName, String projectName, String apiKey, String workspaceName);
    }

    Stream<Arguments> writesByDatasetNameIntoProject() {
        return Stream.of(
                Arguments.of(Named.of("put dataset items",
                        (WriteByDatasetName) (datasetName, projectName, apiKey,
                                workspaceName) -> datasetResourceClient.callCreateDatasetItems(
                                        DatasetItemBatch.builder()
                                                .datasetName(datasetName)
                                                .projectName(projectName)
                                                .items(List.of(DatasetResourceClient.buildDatasetItem(factory)
                                                        .toBuilder().id(null).build()))
                                                .build(),
                                        workspaceName, apiKey))),
                Arguments.of(Named.of("create experiment",
                        (WriteByDatasetName) (datasetName, projectName, apiKey,
                                workspaceName) -> experimentResourceClient.callCreate(
                                        experimentResourceClient.createPartialExperiment()
                                                .datasetName(datasetName)
                                                .projectName(projectName)
                                                .build(),
                                        apiKey, workspaceName))),
                Arguments.of(Named.of("create optimization",
                        (WriteByDatasetName) (datasetName, projectName, apiKey,
                                workspaceName) -> optimizationResourceClient.callCreate(
                                        optimizationResourceClient.createPartialOptimization()
                                                .datasetName(datasetName)
                                                .projectName(projectName)
                                                .build(),
                                        apiKey, workspaceName))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writesByDatasetNameIntoProject")
    @DisplayName("A write by dataset name into a project without that dataset is rejected when the name exists in several other projects")
    void writeByDatasetNameReturnsConflictWhenNameIsInSeveralOtherProjects(
            WriteByDatasetName write) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String datasetName = createDatasetInProjects(2, apiKey, workspaceName).getFirst().name();
        String otherProjectName = "project-" + UUID.randomUUID();
        var otherProjectId = projectResourceClient.createProject(otherProjectName, apiKey, workspaceName);

        try (var response = write.apply(datasetName, otherProjectName, apiKey, workspaceName)) {
            assertErrorResponse(response, HttpStatus.SC_CONFLICT, buildAmbiguousDatasetNameError(datasetName));
        }

        assertNothingCreatedInProject(otherProjectId, apiKey, workspaceName);
    }

    @Test
    @DisplayName("Bulk experiment items without a project are rejected when the dataset name exists in several projects")
    void bulkUploadExperimentItemsWithoutProjectReturnsConflictWhenDatasetNameIsInSeveralProjects() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String datasetName = createDatasetInProjects(2, apiKey, workspaceName).getFirst().name();
        String experimentName = "experiment-" + UUID.randomUUID();
        var bulkUpload = ExperimentItemBulkUpload.builder()
                .experimentName(experimentName)
                .datasetName(datasetName)
                .items(List.of(ExperimentItemBulkRecord.builder()
                        .datasetItemId(ID_GENERATOR.generateId())
                        .evaluateTaskResult(JsonUtils.readTree("{\"answer\": \"42\"}"))
                        .build()))
                .build();

        try (var response = experimentResourceClient.callExperimentItemBulkUpload(bulkUpload, apiKey,
                workspaceName)) {
            assertErrorResponse(response, HttpStatus.SC_CONFLICT, buildAmbiguousDatasetNameError(datasetName));
        }

        assertThat(experimentResourceClient.findExperiments(1, 10, experimentName, apiKey, workspaceName).content())
                .isEmpty();
    }

    @Test
    @DisplayName("Retrieve by name in a project without that dataset falls back to the only other project that has it")
    void getDatasetByNameInOtherProjectFallsBackToTheOnlyMatch() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var dataset = createDatasetInProjects(1, apiKey, workspaceName).getFirst();
        String otherProjectName = "project-" + UUID.randomUUID();
        projectResourceClient.createProject(otherProjectName, apiKey, workspaceName);

        try (var response = datasetResourceClient.callGetDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(dataset.name()).projectName(otherProjectName).build(), apiKey,
                workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.getHeaderString(RequestContext.WORKSPACE_FALLBACK_HEADER)).isNotNull();
            assertDataset(response.readEntity(Dataset.class), dataset);
        }
    }

    @Test
    @DisplayName("Put items by name in a project without that dataset adds them to the only other project's dataset")
    void putDatasetItemsInOtherProjectFallsBackToTheOnlyMatch() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var dataset = createDatasetInProjects(1, apiKey, workspaceName).getFirst();
        String otherProjectName = "project-" + UUID.randomUUID();
        var otherProjectId = projectResourceClient.createProject(otherProjectName, apiKey, workspaceName);
        var item = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .id(null)
                .tags(Set.of())
                .build();

        datasetResourceClient.createDatasetItems(DatasetItemBatch.builder()
                .datasetName(dataset.name())
                .projectName(otherProjectName)
                .items(List.of(item))
                .build(), workspaceName, apiKey);

        var itemsPage = datasetResourceClient.getDatasetItems(dataset.id(), 1, 10, null, apiKey, workspaceName);
        assertThat(itemsPage.content())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(DATASET_ITEM_IGNORED_FIELDS)
                .containsExactly(item);
        assertNothingCreatedInProject(otherProjectId, apiKey, workspaceName);
    }

    @Test
    @DisplayName("Rename to a name used in another project succeeds")
    void renameDatasetToNameUsedInOtherProjectSucceeds() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var dataset = createDatasetInProjects(1, apiKey, workspaceName).getFirst();
        var otherProjectDataset = createDatasetInProjects(1, apiKey, workspaceName).getFirst();
        var update = DatasetUpdate.builder()
                .name(otherProjectDataset.name())
                .description(dataset.description())
                .build();

        try (var response = datasetResourceClient.callUpdateDataset(dataset.id(), update, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }

        var expectedDataset = dataset.toBuilder().name(otherProjectDataset.name()).build();
        assertDataset(datasetResourceClient.getDatasetById(dataset.id(), apiKey, workspaceName), expectedDataset);
    }

    /**
     * {@code inProject = false} covers datasets created before project scoping, which have no project: the unique
     * key must still reject two of them sharing a name.
     */
    @ParameterizedTest(name = "in a project: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("Rename to a name used by another dataset in the same project, or with no project, is rejected")
    void renameDatasetToNameUsedInSameScopeReturnsConflict(boolean inProject) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        UUID projectId = inProject
                ? projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName)
                : null;
        var existing = createDatasetInScope(projectId, apiKey, workspaceName, workspaceId);
        var renamed = createDatasetInScope(projectId, apiKey, workspaceName, workspaceId);
        var update = DatasetUpdate.builder().name(existing.name()).build();

        try (var response = datasetResourceClient.callUpdateDataset(renamed.id(), update, apiKey, workspaceName)) {
            assertErrorResponse(response, HttpStatus.SC_CONFLICT,
                    new ErrorMessage(List.of("Dataset already exists with name '%s'".formatted(existing.name()))));
        }

        assertDataset(datasetResourceClient.getDatasetById(renamed.id(), apiKey, workspaceName), renamed);
    }

    @Test
    @DisplayName("A dataset name already used in the same project is rejected with conflict")
    void createDatasetWithSameNameInSameProjectReturnsConflict() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);
        var dataset = buildDataset().toBuilder().id(null).projectId(projectId).build();
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        try (var response = datasetResourceClient.callCreateDataset(dataset, apiKey, workspaceName)) {
            assertErrorResponse(response, HttpStatus.SC_CONFLICT,
                    new ErrorMessage(List.of("Dataset already exists with name '%s'".formatted(dataset.name()))));
        }
    }

    @Test
    @DisplayName("Delete by name and project deletes only that project's dataset")
    void deleteDatasetByNameAndProjectKeepsSameNameInOtherProject() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var datasets = createDatasetInProjects(2, apiKey, workspaceName);
        var kept = datasets.getFirst();
        var deleted = datasets.getLast();

        datasetResourceClient.deleteDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(deleted.name()).projectName(deleted.projectName()).build(),
                apiKey, workspaceName);

        assertDataset(datasetResourceClient.getDatasetById(kept.id(), apiKey, workspaceName), kept);
        try (var response = datasetResourceClient.callGetDatasetById(deleted.id(), apiKey, workspaceName)) {
            assertErrorResponse(response, HttpStatus.SC_NOT_FOUND, new ErrorMessage(List.of("Dataset not found")));
        }
    }

    @ParameterizedTest(name = "project exists: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("Delete by name in a project without that dataset is not found and keeps the other project's dataset")
    void deleteDatasetByNameInProjectWithoutItReturnsNotFound(boolean projectExists) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var dataset = createDatasetInProjects(1, apiKey, workspaceName).getFirst();
        String otherProjectName = "project-" + UUID.randomUUID();
        if (projectExists) {
            projectResourceClient.createProject(otherProjectName, apiKey, workspaceName);
        }

        try (var response = datasetResourceClient.callDeleteDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(dataset.name()).projectName(otherProjectName).build(), apiKey,
                workspaceName)) {
            assertErrorResponse(response, HttpStatus.SC_NOT_FOUND, new ErrorMessage(List.of("Dataset not found")));
        }

        assertDataset(datasetResourceClient.getDatasetById(dataset.id(), apiKey, workspaceName), dataset);
    }

    /**
     * SDKs always send a project name, so datasets created before project scoping must stay deletable by name.
     */
    @ParameterizedTest(name = "project exists: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("Delete by name and project deletes a dataset with no project of that name")
    void deleteDatasetByNameAndProjectDeletesProjectlessDataset(boolean projectExists) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectlessDataset = createDatasetInScope(null, apiKey, workspaceName, workspaceId);
        String projectName = "project-" + UUID.randomUUID();
        if (projectExists) {
            projectResourceClient.createProject(projectName, apiKey, workspaceName);
        }

        datasetResourceClient.deleteDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(projectlessDataset.name()).projectName(projectName).build(),
                apiKey, workspaceName);

        try (var response = datasetResourceClient.callGetDatasetById(projectlessDataset.id(), apiKey,
                workspaceName)) {
            assertErrorResponse(response, HttpStatus.SC_NOT_FOUND, new ErrorMessage(List.of("Dataset not found")));
        }
    }

    /**
     * Creates one dataset per new project, all sharing one name, and returns them with their ids and project names.
     */
    private List<Dataset> createDatasetInProjects(int count, String apiKey, String workspaceName) {
        String name = "dataset-" + UUID.randomUUID();
        return Stream.generate(() -> "project-" + UUID.randomUUID())
                .limit(count)
                .map(projectName -> {
                    var dataset = buildDataset().toBuilder()
                            .id(null)
                            .name(name)
                            .projectId(projectResourceClient.createProject(projectName, apiKey, workspaceName))
                            .build();
                    return dataset.toBuilder()
                            .id(datasetResourceClient.createDataset(dataset, apiKey, workspaceName))
                            .projectName(projectName)
                            .build();
                })
                .toList();
    }

    /**
     * Creates a dataset in the project, or seeds one with no project when {@code projectId} is null. Every API path
     * now assigns a project, so that legacy shape can only be written directly.
     */
    private Dataset createDatasetInScope(UUID projectId, String apiKey, String workspaceName, String workspaceId) {
        var dataset = buildDataset().toBuilder().id(null).projectId(projectId).build();
        if (projectId != null) {
            return dataset.toBuilder().id(datasetResourceClient.createDataset(dataset, apiKey, workspaceName)).build();
        }
        var projectlessDataset = dataset.toBuilder().id(ID_GENERATOR.generateId()).build();
        mySqlTemplate.inTransaction(WRITE, handle -> {
            handle.attach(DatasetDAO.class).save(projectlessDataset, workspaceId);
            return null;
        });
        return projectlessDataset;
    }

    private void assertNothingCreatedInProject(UUID projectId, String apiKey, String workspaceName) {
        assertThat(datasetResourceClient.getDatasetsByProjectId(projectId, workspaceName, apiKey).content()).isEmpty();
        assertThat(experimentResourceClient.getProjectExperiments(projectId, 1, 10, null, null, null, false, null,
                null, false, apiKey, workspaceName, HttpStatus.SC_OK).content()).isEmpty();
        assertThat(optimizationResourceClient.findByProject(projectId, apiKey, workspaceName, 1, 10, null, null, null,
                HttpStatus.SC_OK).content()).isEmpty();
    }
}
