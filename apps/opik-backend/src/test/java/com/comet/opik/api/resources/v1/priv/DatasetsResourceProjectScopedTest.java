package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
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

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private DatasetResourceClient datasetResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);

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
            assertThat(response.getStatus()).isEqualTo(404);
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

        String projectNameA = "project-" + UUID.randomUUID();
        String projectNameB = "project-" + UUID.randomUUID();
        var projectIdA = projectResourceClient.createProject(projectNameA, apiKey, workspaceName);
        var projectIdB = projectResourceClient.createProject(projectNameB, apiKey, workspaceName);

        var datasetA = buildDataset().toBuilder().id(null).projectId(projectIdA).build();
        var datasetB = buildDataset().toBuilder().id(null).name(datasetA.name()).projectId(projectIdB).build();
        datasetResourceClient.createDataset(datasetA, apiKey, workspaceName);
        datasetResourceClient.createDataset(datasetB, apiKey, workspaceName);

        var actualA = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetA.name()).projectName(projectNameA).build(), apiKey,
                workspaceName);
        var actualB = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetA.name()).projectName(projectNameB).build(), apiKey,
                workspaceName);

        assertDataset(actualA, datasetA);
        assertDataset(actualB, datasetB);
    }

    @Test
    @DisplayName("Retrieve by name without a project is rejected when the name exists in several projects")
    void getDatasetByNameWithoutProjectReturnsConflictWhenNameIsInSeveralProjects() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String datasetName = createDatasetInTwoProjects(apiKey, workspaceName).getFirst().name();

        try (var response = datasetResourceClient.callGetDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetName).build(), apiKey, workspaceName)) {
            assertConflictForAmbiguousName(response, datasetName);
        }
    }

    @Test
    @DisplayName("Delete by name without a project is rejected when the name exists in several projects")
    void deleteDatasetByNameWithoutProjectReturnsConflictWhenNameIsInSeveralProjects() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var datasets = createDatasetInTwoProjects(apiKey, workspaceName);
        String datasetName = datasets.getFirst().name();

        try (var response = datasetResourceClient.callDeleteDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetName).build(), apiKey, workspaceName)) {
            assertConflictForAmbiguousName(response, datasetName);
        }

        datasets.forEach(dataset -> assertDataset(
                datasetResourceClient.getDatasetById(dataset.id(), apiKey, workspaceName), dataset));
    }

    @Test
    @DisplayName("Put items by name into a project without that dataset is rejected when the name exists in several other projects")
    void putDatasetItemsReturnsConflictWhenNameIsInSeveralOtherProjects() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String datasetName = createDatasetInTwoProjects(apiKey, workspaceName).getFirst().name();
        String otherProjectName = "project-" + UUID.randomUUID();
        var otherProjectId = projectResourceClient.createProject(otherProjectName, apiKey, workspaceName);

        var item = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .id(null)
                .build();
        var batch = DatasetItemBatch.builder()
                .datasetName(datasetName)
                .projectName(otherProjectName)
                .items(List.of(item))
                .build();

        try (var response = datasetResourceClient.callCreateDatasetItems(batch, workspaceName, apiKey)) {
            assertConflictForAmbiguousName(response, datasetName);
        }

        var page = datasetResourceClient.getDatasetsByProjectId(otherProjectId, workspaceName, apiKey);
        assertThat(page.content()).isEmpty();
    }

    /**
     * Creates two datasets sharing one name, each in its own new project, and returns them with their ids.
     */
    private List<Dataset> createDatasetInTwoProjects(String apiKey, String workspaceName) {
        String name = "dataset-" + UUID.randomUUID();
        return Stream.generate(() -> projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName))
                .limit(2)
                .map(projectId -> buildDataset().toBuilder().id(null).name(name).projectId(projectId).build())
                .map(dataset -> dataset.toBuilder()
                        .id(datasetResourceClient.createDataset(dataset, apiKey, workspaceName))
                        .build())
                .toList();
    }

    private void assertConflictForAmbiguousName(Response response, String datasetName) {
        var expectedError = new ErrorMessage(HttpStatus.SC_CONFLICT,
                "Multiple datasets with this name exist in different projects, specify the project. Dataset name: '%s'"
                        .formatted(datasetName));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        assertThat(response.readEntity(ErrorMessage.class)).isEqualTo(expectedError);
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
            assertThat(response.getStatus()).isEqualTo(409);
        }
    }

    @Test
    @DisplayName("Delete by name and project deletes only that project's dataset")
    void deleteDatasetByNameAndProjectKeepsSameNameInOtherProject() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String projectNameB = "project-" + UUID.randomUUID();
        var projectIdA = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);
        var projectIdB = projectResourceClient.createProject(projectNameB, apiKey, workspaceName);

        var datasetA = buildDataset().toBuilder().id(null).projectId(projectIdA).build();
        var datasetB = buildDataset().toBuilder().id(null).name(datasetA.name()).projectId(projectIdB).build();
        var idA = datasetResourceClient.createDataset(datasetA, apiKey, workspaceName);
        var idB = datasetResourceClient.createDataset(datasetB, apiKey, workspaceName);

        datasetResourceClient.deleteDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetA.name()).projectName(projectNameB).build(), apiKey,
                workspaceName);

        assertDataset(datasetResourceClient.getDatasetById(idA, apiKey, workspaceName), datasetA);
        try (var response = datasetResourceClient.callGetDatasetById(idB, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }
}
