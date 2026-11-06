package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.DatasetDAO;
import com.comet.opik.domain.DemoData;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private static final TimeBasedEpochGenerator ID_GENERATOR = Generators.timeBasedEpochGenerator();

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
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), apiKey, workspaceName, workspaceId,
                UUID.randomUUID().toString());
    }

    private Dataset buildDataset() {
        return DatasetResourceClient.buildDataset(factory);
    }

    /**
     * Seeds a dataset with no project, as created before project scoping. No API creates one any more.
     */
    private Dataset saveProjectlessDataset(String name, String workspaceId) {
        var dataset = buildDataset().toBuilder()
                .id(ID_GENERATOR.generate())
                .name(name)
                .projectId(null)
                .projectName(null)
                .build();
        mySqlTemplate.inTransaction(WRITE, handle -> {
            handle.attach(DatasetDAO.class).save(dataset, workspaceId);
            return null;
        });
        return dataset;
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
                DatasetIdentifier.builder().datasetName(datasetName).projectName(projectName).build(), apiKey,
                workspaceName);

        assertThat(dataset.projectId()).isEqualTo(projectId);
    }

    @Test
    @DisplayName("Create datasets with the same name in two projects creates one dataset per project")
    void createDatasetsWithSameNameInTwoProjects() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String projectName = "project-" + UUID.randomUUID();
        String otherProjectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
        var otherProjectId = projectResourceClient.createProject(otherProjectName, apiKey, workspaceName);

        String datasetName = "dataset-" + UUID.randomUUID();
        var dataset = buildDataset().toBuilder().id(null).name(datasetName).projectId(projectId).build();
        var otherDataset = buildDataset().toBuilder().id(null).name(datasetName).projectId(otherProjectId).build();

        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        datasetResourceClient.createDataset(otherDataset, apiKey, workspaceName);

        var actualDataset = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetName).projectName(projectName).build(), apiKey,
                workspaceName);
        var actualOtherDataset = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetName).projectName(otherProjectName).build(), apiKey,
                workspaceName);

        assertDataset(actualDataset, dataset);
        assertDataset(actualOtherDataset, otherDataset);
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
    @DisplayName("Get dataset by name in a project does not return another project's dataset")
    void getDatasetByNameDoesNotReachAnotherProject() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String projectName = "project-" + UUID.randomUUID();
        projectResourceClient.createProject(projectName, apiKey, workspaceName);
        var otherProjectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        var otherDataset = buildDataset().toBuilder().id(null).projectId(otherProjectId).build();
        datasetResourceClient.createDataset(otherDataset, apiKey, workspaceName);

        var identifier = DatasetIdentifier.builder()
                .datasetName(otherDataset.name())
                .projectName(projectName)
                .build();
        try (var response = datasetResourceClient.callGetDatasetByIdentifier(identifier, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Get dataset by name in an unknown project returns not found")
    void getDatasetByNameInUnknownProjectReturnsNotFound() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);
        var dataset = buildDataset().toBuilder().id(null).projectId(projectId).build();
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        var identifier = DatasetIdentifier.builder()
                .datasetName(dataset.name())
                .projectName("project-" + UUID.randomUUID())
                .build();
        try (var response = datasetResourceClient.callGetDatasetByIdentifier(identifier, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Delete dataset by name in a project keeps the dataset with that name in another project")
    void deleteDatasetByNameKeepsOtherProjectDataset() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
        var otherProjectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        String datasetName = "dataset-" + UUID.randomUUID();
        var dataset = buildDataset().toBuilder().id(null).name(datasetName).projectId(projectId).build();
        var otherDataset = buildDataset().toBuilder().id(null).name(datasetName).projectId(otherProjectId).build();
        var datasetId = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        var otherDatasetId = datasetResourceClient.createDataset(otherDataset, apiKey, workspaceName);

        datasetResourceClient.deleteDatasetByName(
                DatasetIdentifier.builder().datasetName(datasetName).projectName(projectName).build(), apiKey,
                workspaceName);

        try (var response = datasetResourceClient.callGetDatasetById(datasetId, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
        assertDataset(datasetResourceClient.getDatasetById(otherDatasetId, apiKey, workspaceName), otherDataset);
    }

    @Test
    @DisplayName("Put dataset items without a project uses the only dataset with that name, whatever its project")
    void putDatasetItemsWithoutProjectUsesOnlyDatasetWithThatName() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);
        var dataset = buildDataset().toBuilder().id(null).projectId(projectId).build();
        var datasetId = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        var item = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .id(null)
                .tags(Set.of())
                .build();
        var batch = DatasetItemBatch.builder()
                .datasetName(dataset.name())
                .items(List.of(item))
                .build();

        datasetResourceClient.createDatasetItems(batch, workspaceName, apiKey);

        var itemsPage = datasetResourceClient.getDatasetItems(datasetId, 1, 10, null, apiKey, workspaceName);
        assertThat(itemsPage.content())
                .usingRecursiveComparison()
                .ignoringFields(DATASET_ITEM_IGNORED_FIELDS)
                .isEqualTo(List.of(item));
    }

    @Test
    @DisplayName("Put dataset items without a project is rejected when several projects have a dataset with that name")
    void putDatasetItemsWithoutProjectRejectedWhenNameInSeveralProjects() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        String datasetName = "dataset-" + UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                    workspaceName);
            datasetResourceClient.createDataset(
                    buildDataset().toBuilder().id(null).name(datasetName).projectId(projectId).build(), apiKey,
                    workspaceName);
        }

        var batch = DatasetItemBatch.builder()
                .datasetName(datasetName)
                .items(List.of(DatasetResourceClient.buildDatasetItem(factory).toBuilder().id(null).build()))
                .build();
        var expectedError = new ErrorMessage(HttpStatus.SC_BAD_REQUEST,
                "Dataset name exists in several projects, please provide project_name: '%s'".formatted(datasetName));

        try (var response = datasetResourceClient.callCreateDatasetItems(batch, workspaceName, apiKey)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            assertThat(response.readEntity(ErrorMessage.class)).isEqualTo(expectedError);
        }
    }

    @Test
    @DisplayName("Rename dataset to a name another project uses succeeds")
    void renameDatasetToNameUsedByAnotherProject() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, UUID.randomUUID().toString());

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);
        var otherProjectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        var dataset = buildDataset().toBuilder().id(null).projectId(projectId).build();
        var otherDataset = buildDataset().toBuilder().id(null).projectId(otherProjectId).build();
        var datasetId = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        datasetResourceClient.createDataset(otherDataset, apiKey, workspaceName);

        var update = DatasetUpdate.builder()
                .name(otherDataset.name())
                .description(dataset.description())
                .visibility(dataset.visibility())
                .tags(dataset.tags())
                .build();
        try (var response = datasetResourceClient.callUpdateDataset(datasetId, update, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }

        var expectedDataset = dataset.toBuilder().name(otherDataset.name()).build();
        assertDataset(datasetResourceClient.getDatasetById(datasetId, apiKey, workspaceName), expectedDataset);
    }

    @Test
    @DisplayName("Get dataset by name without a project finds a project-less dataset, as it belongs to the Default Project")
    void getDatasetByNameWithoutProjectFindsProjectlessDataset() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        projectResourceClient.createProject(DEFAULT_PROJECT, apiKey, workspaceName);
        var projectless = saveProjectlessDataset("dataset-" + UUID.randomUUID(), workspaceId);

        var withoutProject = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(projectless.name()).build(), apiKey, workspaceName);
        var inDefaultProject = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(projectless.name()).projectName(DEFAULT_PROJECT).build(),
                apiKey, workspaceName);

        assertDataset(withoutProject, projectless);
        assertDataset(inDefaultProject, projectless);
    }

    @Test
    @DisplayName("Get dataset by name in another project does not find a project-less dataset")
    void getDatasetByNameInAnotherProjectDoesNotFindProjectlessDataset() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        projectResourceClient.createProject(DEFAULT_PROJECT, apiKey, workspaceName);
        String projectName = "project-" + UUID.randomUUID();
        projectResourceClient.createProject(projectName, apiKey, workspaceName);
        var projectless = saveProjectlessDataset("dataset-" + UUID.randomUUID(), workspaceId);

        var identifier = DatasetIdentifier.builder().datasetName(projectless.name()).projectName(projectName).build();
        try (var response = datasetResourceClient.callGetDatasetByIdentifier(identifier, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Get dataset by name prefers the Default Project's dataset over a project-less one with the same name")
    void getDatasetByNamePrefersDefaultProjectDatasetOverProjectless() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var defaultProjectId = projectResourceClient.createProject(DEFAULT_PROJECT, apiKey, workspaceName);
        String datasetName = "dataset-" + UUID.randomUUID();
        saveProjectlessDataset(datasetName, workspaceId);
        var defaultProjectDataset = buildDataset().toBuilder()
                .id(ID_GENERATOR.generate())
                .name(datasetName)
                .projectId(defaultProjectId)
                .projectName(null)
                .build();
        mySqlTemplate.inTransaction(WRITE, handle -> {
            handle.attach(DatasetDAO.class).save(defaultProjectDataset, workspaceId);
            return null;
        });

        var actual = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(datasetName).build(), apiKey, workspaceName);

        assertDataset(actual, defaultProjectDataset);
    }

    @Test
    @DisplayName("Default Project datasets list includes project-less datasets but not project-less demo datasets")
    void findDefaultProjectDatasetsIncludesProjectlessButNotDemoDatasets() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var defaultProjectId = projectResourceClient.createProject(DEFAULT_PROJECT, apiKey, workspaceName);
        var otherProjectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);
        var projectless = saveProjectlessDataset("dataset-" + UUID.randomUUID(), workspaceId);
        saveProjectlessDataset(DemoData.DATASETS.getFirst(), workspaceId);

        var defaultProjectPage = datasetResourceClient.getDatasetsByProjectId(defaultProjectId, workspaceName,
                apiKey);
        var otherProjectPage = datasetResourceClient.getDatasetsByProjectId(otherProjectId, workspaceName, apiKey);

        assertThat(defaultProjectPage.total()).isEqualTo(1);
        assertThat(defaultProjectPage.content()).hasSize(1);
        assertDataset(defaultProjectPage.content().getFirst(), projectless);
        assertThat(otherProjectPage.content()).isEmpty();
    }

    @Test
    @DisplayName("Create dataset in the Default Project with a project-less dataset's name returns conflict")
    void createDatasetInDefaultProjectWithProjectlessNameReturnsConflict() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var defaultProjectId = projectResourceClient.createProject(DEFAULT_PROJECT, apiKey, workspaceName);
        var projectless = saveProjectlessDataset("dataset-" + UUID.randomUUID(), workspaceId);

        var dataset = buildDataset().toBuilder().id(null).name(projectless.name()).projectId(defaultProjectId).build();
        try (var response = datasetResourceClient.callCreateDataset(dataset, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        }
    }

    @Test
    @DisplayName("Rename a Default Project dataset to a project-less dataset's name returns conflict")
    void renameDefaultProjectDatasetToProjectlessNameReturnsConflict() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var defaultProjectId = projectResourceClient.createProject(DEFAULT_PROJECT, apiKey, workspaceName);
        var projectless = saveProjectlessDataset("dataset-" + UUID.randomUUID(), workspaceId);
        var dataset = buildDataset().toBuilder().id(null).projectId(defaultProjectId).build();
        var datasetId = datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

        var update = DatasetUpdate.builder()
                .name(projectless.name())
                .description(dataset.description())
                .visibility(dataset.visibility())
                .tags(dataset.tags())
                .build();
        try (var response = datasetResourceClient.callUpdateDataset(datasetId, update, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        }
    }

    @Test
    @DisplayName("Put dataset items without a project into a project-less dataset's name adds them to that dataset")
    void putDatasetItemsWithoutProjectUsesProjectlessDataset() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectless = saveProjectlessDataset("dataset-" + UUID.randomUUID(), workspaceId);
        var item = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .id(null)
                .tags(Set.of())
                .build();

        datasetResourceClient.createDatasetItems(DatasetItemBatch.builder()
                .datasetName(projectless.name())
                .items(List.of(item))
                .build(), workspaceName, apiKey);

        var itemsPage = datasetResourceClient.getDatasetItems(projectless.id(), 1, 10, null, apiKey, workspaceName);
        assertThat(itemsPage.content())
                .usingRecursiveComparison()
                .ignoringFields(DATASET_ITEM_IGNORED_FIELDS)
                .isEqualTo(List.of(item));
    }
}
