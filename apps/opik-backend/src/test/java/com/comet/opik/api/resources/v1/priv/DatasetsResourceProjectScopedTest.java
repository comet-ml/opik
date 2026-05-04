package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.core.Response;
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
                DatasetIdentifier.builder().datasetName(datasetName).projectName(projectName).build(),
                apiKey, workspaceName);

        assertThat(dataset.projectId()).isEqualTo(projectId);
    }

    @Test
    @DisplayName("Same name across v1 and v2-in-project coexists in the same workspace (both insert orders)")
    void v1AndV2NamesakesCoexist() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectAId = projectResourceClient.createProject("project-A-" + UUID.randomUUID(), apiKey, workspaceName);

        // v1 first, then v2 in Project A
        String name1 = "shared-name-" + UUID.randomUUID();
        var v1First = buildDataset().toBuilder().id(null).name(name1).projectId(null).build();
        datasetResourceClient.createDataset(v1First, apiKey, workspaceName);
        var v2Second = buildDataset().toBuilder().id(null).name(name1).projectId(projectAId).build();
        datasetResourceClient.createDataset(v2Second, apiKey, workspaceName);

        // Reverse order: v2 first, then v1
        String name2 = "shared-name-" + UUID.randomUUID();
        var v2First = buildDataset().toBuilder().id(null).name(name2).projectId(projectAId).build();
        datasetResourceClient.createDataset(v2First, apiKey, workspaceName);
        var v1Second = buildDataset().toBuilder().id(null).name(name2).projectId(null).build();
        datasetResourceClient.createDataset(v1Second, apiKey, workspaceName);
    }

    @Test
    @DisplayName("Two v1 datasets with same name in same workspace still 409s")
    void duplicateV1DatasetReturns409() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String name = "dup-v1-" + UUID.randomUUID();
        var first = buildDataset().toBuilder().id(null).name(name).projectId(null).build();
        datasetResourceClient.createDataset(first, apiKey, workspaceName);

        var second = buildDataset().toBuilder().id(null).name(name).projectId(null).build();
        try (var response = datasetResourceClient.callCreateDataset(second, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.CONFLICT.getStatusCode());
        }
    }

    @Test
    @DisplayName("Same name across two v2 projects in same workspace coexists")
    void v2NamesakesAcrossProjectsCoexist() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectAId = projectResourceClient.createProject("project-A-" + UUID.randomUUID(), apiKey, workspaceName);
        var projectBId = projectResourceClient.createProject("project-B-" + UUID.randomUUID(), apiKey, workspaceName);

        String name = "shared-v2-" + UUID.randomUUID();
        var inA = buildDataset().toBuilder().id(null).name(name).projectId(projectAId).build();
        datasetResourceClient.createDataset(inA, apiKey, workspaceName);

        var inB = buildDataset().toBuilder().id(null).name(name).projectId(projectBId).build();
        datasetResourceClient.createDataset(inB, apiKey, workspaceName);
    }

    @Test
    @DisplayName("Two v2 datasets with same name in the same project still 409s")
    void duplicateV2DatasetInSameProjectReturns409() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        String name = "dup-v2-" + UUID.randomUUID();
        var first = buildDataset().toBuilder().id(null).name(name).projectId(projectId).build();
        datasetResourceClient.createDataset(first, apiKey, workspaceName);

        var second = buildDataset().toBuilder().id(null).name(name).projectId(projectId).build();
        try (var response = datasetResourceClient.callCreateDataset(second, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.CONFLICT.getStatusCode());
        }
    }

    @Test
    @DisplayName("Deleting v2 dataset by name does not affect v1 namesake (case 5 regression)")
    void deletingV2NamesakeDoesNotAffectV1() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        String name = "shared-name-" + UUID.randomUUID();
        var v1Id = datasetResourceClient.createDataset(
                buildDataset().toBuilder().id(null).name(name).projectId(null).build(), apiKey, workspaceName);
        var v2Id = datasetResourceClient.createDataset(
                buildDataset().toBuilder().id(null).name(name).projectId(projectId).build(), apiKey, workspaceName);

        datasetResourceClient.deleteDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(name).projectName(projectName).build(),
                apiKey, workspaceName);

        // v2 row gone, v1 row survives
        try (var response = datasetResourceClient.callGetDatasetById(v2Id, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        }
        var survivingV1 = datasetResourceClient.getDatasetById(v1Id, apiKey, workspaceName);
        assertThat(survivingV1.id()).isEqualTo(v1Id);
        assertThat(survivingV1.projectId()).isNull();
    }

    @Test
    @DisplayName("Deleting v1 dataset by name does not affect v2 namesake (case 5 reverse regression)")
    void deletingV1NamesakeDoesNotAffectV2() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        String name = "shared-name-" + UUID.randomUUID();
        var v1Id = datasetResourceClient.createDataset(
                buildDataset().toBuilder().id(null).name(name).projectId(null).build(), apiKey, workspaceName);
        var v2Id = datasetResourceClient.createDataset(
                buildDataset().toBuilder().id(null).name(name).projectId(projectId).build(), apiKey, workspaceName);

        // No projectName -> resolves to v1 (project_id IS NULL) row
        datasetResourceClient.deleteDatasetByName(name, apiKey, workspaceName);

        try (var response = datasetResourceClient.callGetDatasetById(v1Id, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        }
        var survivingV2 = datasetResourceClient.getDatasetById(v2Id, apiKey, workspaceName);
        assertThat(survivingV2.id()).isEqualTo(v2Id);
        assertThat(survivingV2.projectId()).isEqualTo(projectId);
    }

    @Test
    @DisplayName("findByName with projectId does not match a v1 row of the same name (case 7 regression)")
    void findByNameInProjectDoesNotMatchV1Row() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        // v1 row only — no v2 namesake exists
        String name = "v1-only-" + UUID.randomUUID();
        var v1Id = datasetResourceClient.createDataset(
                buildDataset().toBuilder().id(null).name(name).projectId(null).build(), apiKey, workspaceName);

        // Lookup with projectName falls back to workspace-level v1 row (existing behaviour preserved).
        var resolved = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(name).projectName(projectName).build(),
                apiKey, workspaceName);
        assertThat(resolved.id()).isEqualTo(v1Id);
        assertThat(resolved.projectId()).isNull();

        // Now create the v2 namesake; the project-scoped lookup must resolve to it, not the v1 row.
        var v2Id = datasetResourceClient.createDataset(
                buildDataset().toBuilder().id(null).name(name).projectId(projectId).build(), apiKey, workspaceName);

        var resolvedV2 = datasetResourceClient.getDatasetByIdentifier(
                DatasetIdentifier.builder().datasetName(name).projectName(projectName).build(),
                apiKey, workspaceName);
        assertThat(resolvedV2.id()).isEqualTo(v2Id);
        assertThat(resolvedV2.projectId()).isEqualTo(projectId);
    }
}
