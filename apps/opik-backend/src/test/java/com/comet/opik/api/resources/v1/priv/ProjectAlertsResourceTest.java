package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.resources.AlertResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.alerts.AlertAssertions.assertAlerts;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Project Alerts Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ProjectAlertsResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private AlertResourceClient alertResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        String baseUrl = TestUtils.getBaseUrl(client);
        alertResourceClient = new AlertResourceClient(client);
        projectResourceClient = new ProjectResourceClient(client, baseUrl, factory);

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(setup.wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    private Pair<String, String> prepareMockWorkspace() {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        mockTargetWorkspace(apiKey, workspaceName, workspaceId);
        return Pair.of(apiKey, workspaceName);
    }

    /**
     * Generates an alert without any scope:project trigger configs.
     * Use the projectId field on Alert to scope to a project — the system will create the config internally.
     */
    private Alert generateAlertWithoutProjectScope() {
        var alert = factory.manufacturePojo(Alert.class);

        var webhook = alert.webhook().toBuilder()
                .createdBy(null)
                .createdAt(null)
                .secretToken(UUID.randomUUID().toString())
                .build();

        var triggers = alert.triggers().stream()
                .map(trigger -> {
                    var filteredConfigs = trigger.triggerConfigs().stream()
                            .filter(c -> c.type() != AlertTriggerConfigType.SCOPE_PROJECT)
                            .map(c -> c.toBuilder().createdBy(null).createdAt(null).build())
                            .toList();
                    return trigger.toBuilder()
                            .triggerConfigs(filteredConfigs.isEmpty() ? null : filteredConfigs)
                            .createdBy(null)
                            .createdAt(null)
                            .build();
                })
                .toList();

        return alert.toBuilder()
                .webhook(webhook)
                .createdBy(null)
                .createdAt(null)
                .projectId(null)
                .triggers(triggers)
                .build();
    }

    @Nested
    @DisplayName("Project Scoped Alerts:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ProjectScopedAlerts {

        @Test
        @DisplayName("when creating alert with projectId, then projectId is stored and returned")
        void createAlert__whenProjectIdProvided__thenProjectIdStoredAndReturned() {
            var mock = prepareMockWorkspace();
            var projectId = projectResourceClient.createProject(
                    "test-project-" + UUID.randomUUID(), mock.getLeft(), mock.getRight());

            var alert = generateAlertWithoutProjectScope().toBuilder()
                    .projectId(projectId)
                    .build();

            var alertId = alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);
            assertThat(alertId).isNotNull();

            var expectedAlert = alert.toBuilder().id(alertId).build();
            var stored = alertResourceClient.getAlertById(alertId, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_OK);

            assertAlerts(expectedAlert, stored, true);
        }

        @Test
        @DisplayName("when creating alert without projectId, then projectId is null")
        void createAlert__whenNoProjectId__thenProjectIdIsNull() {
            var mock = prepareMockWorkspace();

            var alert = generateAlertWithoutProjectScope();
            var alertId = alertResourceClient.createAlert(alert, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_CREATED);

            var expectedAlert = alert.toBuilder().id(alertId).build();
            var stored = alertResourceClient.getAlertById(alertId, mock.getLeft(), mock.getRight(),
                    HttpStatus.SC_OK);

            assertAlerts(expectedAlert, stored, true);
        }

        @Test
        @DisplayName("when finding alerts by project, then only project-scoped alerts are returned")
        void findAlertsByProject__whenAlertsExist__thenReturnOnlyScopedAlerts() {
            var mock = prepareMockWorkspace();
            var projectId = projectResourceClient.createProject(
                    "test-project-" + UUID.randomUUID(), mock.getLeft(), mock.getRight());
            var otherProjectId = projectResourceClient.createProject(
                    "other-project-" + UUID.randomUUID(), mock.getLeft(), mock.getRight());

            var scopedAlerts = IntStream.range(0, 3)
                    .mapToObj(i -> {
                        var a = generateAlertWithoutProjectScope().toBuilder().projectId(projectId).build();
                        var id = alertResourceClient.createAlert(a, mock.getLeft(), mock.getRight(),
                                HttpStatus.SC_CREATED);
                        return a.toBuilder().id(id).build();
                    })
                    .toList();

            // Alerts scoped to a different project — must NOT appear in results
            IntStream.range(0, 2).forEach(i -> alertResourceClient.createAlert(
                    generateAlertWithoutProjectScope().toBuilder().projectId(otherProjectId).build(),
                    mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED));

            // Alert with no project scope — must NOT appear in results
            alertResourceClient.createAlert(generateAlertWithoutProjectScope(),
                    mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            var page = alertResourceClient.findAlertsByProject(
                    projectId, mock.getLeft(), mock.getRight(), 1, 10, HttpStatus.SC_OK);

            assertThat(page.total()).isEqualTo(3);
            assertThat(page.content()).hasSize(3);

            for (var expected : scopedAlerts) {
                var actual = page.content().stream()
                        .filter(a -> a.id().equals(expected.id()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Expected alert not found in page: " + expected.id()));
                assertAlerts(expected, actual, true);
            }
        }

        @Test
        @DisplayName("when finding alerts by project with no matching alerts, then return empty page")
        void findAlertsByProject__whenNoMatchingAlerts__thenReturnEmptyPage() {
            var mock = prepareMockWorkspace();
            var projectId = projectResourceClient.createProject(
                    "empty-project-" + UUID.randomUUID(), mock.getLeft(), mock.getRight());
            var otherProjectId = projectResourceClient.createProject(
                    "other-project-" + UUID.randomUUID(), mock.getLeft(), mock.getRight());

            alertResourceClient.createAlert(
                    generateAlertWithoutProjectScope().toBuilder().projectId(otherProjectId).build(),
                    mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);

            var page = alertResourceClient.findAlertsByProject(
                    projectId, mock.getLeft(), mock.getRight(), 1, 10, HttpStatus.SC_OK);

            assertThat(page.total()).isEqualTo(0);
            assertThat(page.content()).isEmpty();
        }

        @Test
        @DisplayName("when creating alert with both projectId and scope:project trigger config, then return 400")
        void createAlert__whenBothProjectIdAndScopeProjectConfig__thenReturn400() {
            var mock = prepareMockWorkspace();
            var projectId = projectResourceClient.createProject(
                    "test-project-" + UUID.randomUUID(), mock.getLeft(), mock.getRight());

            var scopeConfig = AlertTriggerConfig.builder()
                    .type(AlertTriggerConfigType.SCOPE_PROJECT)
                    .configValue(Map.of(AlertTriggerConfig.PROJECT_IDS_CONFIG_KEY,
                            JsonUtils.writeValueAsString(Set.of(projectId))))
                    .build();

            var trigger = factory.manufacturePojo(AlertTrigger.class).toBuilder()
                    .triggerConfigs(List.of(scopeConfig))
                    .createdBy(null)
                    .createdAt(null)
                    .build();

            var alert = generateAlertWithoutProjectScope().toBuilder()
                    .projectId(projectId)
                    .triggers(List.of(trigger))
                    .build();

            try (var response = alertResourceClient.createAlertWithResponse(alert, mock.getLeft(),
                    mock.getRight())) {
                assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("when finding alerts globally, then return alerts with and without project scope")
        void findAlerts__globally__thenReturnAllAlerts() {
            var mock = prepareMockWorkspace();
            var projectId = projectResourceClient.createProject(
                    "test-project-" + UUID.randomUUID(), mock.getLeft(), mock.getRight());

            var scopedAlert = generateAlertWithoutProjectScope().toBuilder().projectId(projectId).build();
            var scopedId = alertResourceClient.createAlert(
                    scopedAlert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);
            var expectedScoped = scopedAlert.toBuilder().id(scopedId).build();

            var unscopedAlert = generateAlertWithoutProjectScope();
            var unscopedId = alertResourceClient.createAlert(
                    unscopedAlert, mock.getLeft(), mock.getRight(), HttpStatus.SC_CREATED);
            var expectedUnscoped = unscopedAlert.toBuilder().id(unscopedId).build();

            var page = alertResourceClient.findAlerts(mock.getLeft(), mock.getRight(),
                    1, 10, null, null, HttpStatus.SC_OK);

            var actualScoped = page.content().stream()
                    .filter(a -> a.id().equals(scopedId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Scoped alert not found in global list"));
            assertAlerts(expectedScoped, actualScoped, true);

            var actualUnscoped = page.content().stream()
                    .filter(a -> a.id().equals(unscopedId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Unscoped alert not found in global list"));
            assertAlerts(expectedUnscoped, actualUnscoped, true);
        }
    }
}
