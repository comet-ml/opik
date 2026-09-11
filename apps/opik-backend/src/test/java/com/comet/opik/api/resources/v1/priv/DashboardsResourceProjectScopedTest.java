package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.DashboardResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project-scoped dashboard operations")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DashboardsResourceProjectScopedTest {

    public static final String[] DASHBOARD_IGNORED_FIELDS = {"id", "workspaceId", "slug", "projectName", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private DashboardResourceClient dashboardResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        this.dashboardResourceClient = new DashboardResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, podamFactory);
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private void assertDashboard(Dashboard expected, Dashboard actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(DASHBOARD_IGNORED_FIELDS)
                .isEqualTo(expected);

        assertThat(actual.createdAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
        assertThat(actual.lastUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("Create dashboard with project_id persists and returns project_id")
    void createDashboardWithProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);

        var dashboard = dashboardResourceClient.createPartialDashboard()
                .projectId(projectId)
                .build();

        var createdDashboard = dashboardResourceClient.createAndGet(dashboard, apiKey, workspaceName);

        assertDashboard(dashboard, createdDashboard);
    }

    @Test
    @DisplayName("Create dashboard with non-existing project_id returns not found")
    void createDashboardWithNonExistingProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var dashboard = dashboardResourceClient.createPartialDashboard()
                .projectId(podamFactory.manufacturePojo(UUID.class))
                .build();

        try (var response = dashboardResourceClient.callCreate(dashboard, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Create dashboard with project_name of existing project resolves project_id")
    void createDashboardWithExistingProjectName() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);

        var dashboard = dashboardResourceClient.createPartialDashboard()
                .projectName(projectName)
                .build();

        var createdDashboard = dashboardResourceClient.createAndGet(dashboard, apiKey, workspaceName);

        var expectedDashboard = dashboard.toBuilder()
                .projectId(projectId)
                .build();
        assertDashboard(expectedDashboard, createdDashboard);
    }

    @Test
    @DisplayName("Create dashboard with project_name of non-existing project creates project and resolves project_id")
    void createDashboardWithNonExistingProjectName() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        String projectName = "new-project-" + UUID.randomUUID();

        var dashboard = dashboardResourceClient.createPartialDashboard()
                .projectName(projectName)
                .build();

        var createdDashboard = dashboardResourceClient.createAndGet(dashboard, apiKey, workspaceName);

        // Verify the project was created and the projectId was resolved
        assertThat(createdDashboard.projectId()).isNotNull();

        var expectedDashboard = dashboard.toBuilder()
                .projectId(createdDashboard.projectId())
                .build();
        assertDashboard(expectedDashboard, createdDashboard);
    }

    @Test
    @DisplayName("Find dashboards filtered by project_id returns only project dashboards")
    void findDashboardsByProjectId() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var projectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey, workspaceName);
        var otherProjectId = projectResourceClient.createProject("project-" + UUID.randomUUID(), apiKey,
                workspaceName);

        var projectDashboard = dashboardResourceClient.createPartialDashboard()
                .projectId(projectId)
                .build();
        dashboardResourceClient.createAndGet(projectDashboard, apiKey, workspaceName);

        var otherProjectDashboard = dashboardResourceClient.createPartialDashboard()
                .projectId(otherProjectId)
                .build();
        dashboardResourceClient.createAndGet(otherProjectDashboard, apiKey, workspaceName);

        var workspaceDashboard = dashboardResourceClient.createPartialDashboard().build();
        dashboardResourceClient.createAndGet(workspaceDashboard, apiKey, workspaceName);

        var page = dashboardResourceClient.find(apiKey, workspaceName, 1, 100, null, projectId,
                null, null, HttpStatus.SC_OK);

        assertThat(page.content()).hasSize(1);
        assertDashboard(projectDashboard, page.content().getFirst());
    }
}
