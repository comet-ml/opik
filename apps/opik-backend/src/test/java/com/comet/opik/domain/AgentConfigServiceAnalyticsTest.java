package com.comet.opik.domain;

import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.AgentConfigConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Provider;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentConfigService analytics tracking")
class AgentConfigServiceAnalyticsTest {

    private String workspaceId;
    private String userName;

    @Mock
    private Provider<RequestContext> requestContextProvider;
    @Mock
    private RequestContext requestContext;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private ProjectService projectService;
    @Mock
    private LockService lockService;
    @Mock
    private AgentConfigConfiguration agentConfigConfiguration;
    @Mock
    private AnalyticsService analyticsService;
    @Mock
    private AgentConfigDAO agentConfigDAO;
    @Mock
    private Handle handle;

    private AgentConfigService service;

    @BeforeEach
    void setUp() throws Exception {
        workspaceId = UUID.randomUUID().toString();
        userName = UUID.randomUUID().toString();

        lenient().when(requestContextProvider.get()).thenReturn(requestContext);
        lenient().when(requestContext.getWorkspaceId()).thenReturn(workspaceId);
        lenient().when(requestContext.getUserName()).thenReturn(userName);
        lenient().when(agentConfigConfiguration.getBlueprintLockDuration())
                .thenReturn(io.dropwizard.util.Duration.milliseconds(100));

        // LockService passes through the action directly (both overloads)
        lenient().when(lockService.executeWithLockCustomExpire(any(), any(), any()))
                .thenAnswer(inv -> inv.<Mono<?>>getArgument(1));
        //noinspection unchecked
        lenient().<Mono<?>>when(lockService.executeWithLock(any(LockService.Lock.class), any(Mono.class)))
                .thenAnswer(inv -> inv.<Mono<?>>getArgument(1));

        // TransactionTemplate executes the TxAction with our mock handle
        lenient().when(handle.attach(AgentConfigDAO.class)).thenReturn(agentConfigDAO);
        lenient().when(transactionTemplate.inTransaction(any(), any()))
                .thenAnswer(inv -> {
                    ru.vyarus.guicey.jdbi3.tx.TxAction<?> action = inv.getArgument(1);
                    return action.execute(handle);
                });

        service = new AgentConfigServiceImpl(
                requestContextProvider,
                idGenerator,
                transactionTemplate,
                projectService,
                lockService,
                agentConfigConfiguration,
                analyticsService);
    }

    static Stream<String> demoProjectNames() {
        return DemoData.PROJECTS.stream();
    }

    @Nested
    @DisplayName("createConfig analytics")
    class CreateConfigAnalytics {

        @ParameterizedTest
        @DisplayName("non-demo project: trackEvent called with full payload for both saved and deployed")
        @ValueSource(strings = {"my-real-project"})
        void createConfig_nonDemo_tracksEventWithFullPayload(String projectName) throws Exception {
            var projectId = UUID.randomUUID();
            var blueprintId = UUID.randomUUID();
            var configId = UUID.randomUUID();
            var blueprintName = UUID.randomUUID().toString();

            stubCreateConfigDaoCalls(projectId, configId, blueprintId, blueprintName, projectName);

            var request = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .blueprint(AgentBlueprint.builder()
                            .type(AgentBlueprint.BlueprintType.BLUEPRINT)
                            .description(UUID.randomUUID().toString())
                            .values(List.of())
                            .build())
                    .build();

            service.createConfig(request).block();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> savedProps = ArgumentCaptor.forClass(Map.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> deployedProps = ArgumentCaptor.forClass(Map.class);

            verify(analyticsService).trackEvent(
                    org.mockito.ArgumentMatchers.eq("opik_agent_config_saved"),
                    savedProps.capture(),
                    org.mockito.ArgumentMatchers.eq(userName));

            verify(analyticsService).trackEvent(
                    org.mockito.ArgumentMatchers.eq("opik_agent_config_deployed"),
                    deployedProps.capture(),
                    org.mockito.ArgumentMatchers.eq(userName));

            assertThat(savedProps.getValue())
                    .containsEntry("workspace_id", workspaceId)
                    .containsEntry("project_id", projectId.toString())
                    .containsEntry("blueprint_id", blueprintId.toString())
                    .containsKey("blueprint_name");

            assertThat(deployedProps.getValue())
                    .containsEntry("workspace_id", workspaceId)
                    .containsEntry("project_id", projectId.toString())
                    .containsEntry("blueprint_id", blueprintId.toString())
                    .containsKey("blueprint_name")
                    .containsEntry("environment", "prod")
                    .containsEntry("deployed_to_prod", "true");
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.domain.AgentConfigServiceAnalyticsTest#demoProjectNames")
        @DisplayName("demo project: trackEvent is never called")
        void createConfig_demoProject_doesNotTrack(String demoProjectName) throws Exception {
            var projectId = UUID.randomUUID();
            var blueprintId = UUID.randomUUID();
            var configId = UUID.randomUUID();

            stubCreateConfigDaoCalls(projectId, configId, blueprintId, UUID.randomUUID().toString(), demoProjectName);

            var request = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .projectName(demoProjectName)
                    .blueprint(AgentBlueprint.builder()
                            .type(AgentBlueprint.BlueprintType.BLUEPRINT)
                            .description(UUID.randomUUID().toString())
                            .values(List.of())
                            .build())
                    .build();

            service.createConfig(request).block();

            verify(analyticsService, never()).trackEvent(anyString(), any(), anyString());
        }

        private void stubCreateConfigDaoCalls(UUID projectId, UUID configId, UUID blueprintId, String blueprintName,
                String projectName) throws Exception {
            when(idGenerator.generateId())
                    .thenReturn(configId)
                    .thenReturn(blueprintId)
                    .thenReturn(UUID.randomUUID()); // env id

            when(agentConfigDAO.getConfigByProjectId(workspaceId, projectId)).thenReturn(null);
            lenient().when(agentConfigDAO.countBlueprints(workspaceId, projectId)).thenReturn(0L);
            lenient().when(agentConfigDAO.getEnvsByNames(any(), any(), any())).thenReturn(List.of());

            lenient().when(agentConfigDAO.getConfigByProjectId(workspaceId, projectId))
                    .thenReturn(null)
                    .thenReturn(AgentConfig.builder().id(configId).build());

            lenient().when(projectService.get(projectId, workspaceId))
                    .thenReturn(Project.builder().id(projectId).name(projectName).build());
        }
    }

    @Nested
    @DisplayName("updateConfig analytics")
    class UpdateConfigAnalytics {

        @ParameterizedTest
        @DisplayName("non-demo project: trackEvent called with full saved payload")
        @ValueSource(strings = {"another-real-project"})
        void updateConfig_nonDemo_tracksSavedEvent(String projectName) throws Exception {
            var projectId = UUID.randomUUID();
            var configId = UUID.randomUUID();
            var blueprintId = UUID.randomUUID();

            stubUpdateConfigDaoCalls(projectId, configId, blueprintId, projectName);

            var request = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .projectName(projectName)
                    .blueprint(AgentBlueprint.builder()
                            .type(AgentBlueprint.BlueprintType.BLUEPRINT)
                            .description(UUID.randomUUID().toString())
                            .values(List.of())
                            .build())
                    .build();

            service.updateConfig(request).block();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> savedProps = ArgumentCaptor.forClass(Map.class);

            verify(analyticsService).trackEvent(
                    org.mockito.ArgumentMatchers.eq("opik_agent_config_saved"),
                    savedProps.capture(),
                    org.mockito.ArgumentMatchers.eq(userName));

            assertThat(savedProps.getValue())
                    .containsEntry("workspace_id", workspaceId)
                    .containsEntry("project_id", projectId.toString())
                    .containsEntry("blueprint_id", blueprintId.toString())
                    .containsKey("blueprint_name");
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.domain.AgentConfigServiceAnalyticsTest#demoProjectNames")
        @DisplayName("demo project: trackEvent is never called")
        void updateConfig_demoProject_doesNotTrack(String demoProjectName) throws Exception {
            var projectId = UUID.randomUUID();
            var configId = UUID.randomUUID();
            var blueprintId = UUID.randomUUID();

            stubUpdateConfigDaoCalls(projectId, configId, blueprintId, demoProjectName);

            var request = AgentConfigCreate.builder()
                    .projectId(projectId)
                    .projectName(demoProjectName)
                    .blueprint(AgentBlueprint.builder()
                            .type(AgentBlueprint.BlueprintType.BLUEPRINT)
                            .description(UUID.randomUUID().toString())
                            .values(List.of())
                            .build())
                    .build();

            service.updateConfig(request).block();

            verify(analyticsService, never()).trackEvent(anyString(), any(), anyString());
        }

        private void stubUpdateConfigDaoCalls(UUID projectId, UUID configId, UUID blueprintId, String projectName)
                throws Exception {
            when(idGenerator.generateId()).thenReturn(blueprintId);
            when(agentConfigDAO.getConfigByProjectId(workspaceId, projectId))
                    .thenReturn(AgentConfig.builder().id(configId).build());
            lenient().when(agentConfigDAO.countBlueprints(workspaceId, projectId)).thenReturn(1L);
            lenient().when(projectService.get(projectId, workspaceId))
                    .thenReturn(Project.builder().id(projectId).name(projectName).build());
        }
    }

    @Nested
    @DisplayName("deployed_to_prod flag")
    class DeployedToProdFlag {

        static Stream<org.junit.jupiter.params.provider.Arguments> envNameToProdFlag() {
            return Stream.of(
                    org.junit.jupiter.params.provider.Arguments.of("prod", "true"),
                    org.junit.jupiter.params.provider.Arguments.of("PROD", "true"),
                    org.junit.jupiter.params.provider.Arguments.of("Prod", "true"),
                    org.junit.jupiter.params.provider.Arguments.of("staging", "false"),
                    org.junit.jupiter.params.provider.Arguments.of("dev", "false"));
        }

        @ParameterizedTest(name = "envName={0} → deployed_to_prod={1}")
        @MethodSource("envNameToProdFlag")
        @DisplayName("deployed_to_prod reflects case-insensitive prod comparison")
        void deployedToProd_caseInsensitive(String envName, String expectedFlag) throws Exception {
            var projectId = UUID.randomUUID();
            var configId = UUID.randomUUID();
            var blueprintId = UUID.randomUUID();
            var projectName = UUID.randomUUID().toString();
            var blueprintName = UUID.randomUUID().toString();

            when(idGenerator.generateId())
                    .thenReturn(configId)
                    .thenReturn(blueprintId)
                    .thenReturn(UUID.randomUUID()); // env id
            when(agentConfigDAO.getConfigByProjectId(workspaceId, projectId)).thenReturn(null);
            lenient().when(agentConfigDAO.countBlueprints(workspaceId, projectId)).thenReturn(0L);
            lenient().when(agentConfigDAO.getEnvsByNames(any(), any(), any())).thenReturn(List.of());

            // createConfig always deploys to "prod" — to test arbitrary env names use a blueprint with
            // a custom env stub; here we verify the helper directly via createConfig with "prod"
            // and a separate path via stubbing. Instead, directly test the flag logic:
            // The flag is String.valueOf("prod".equalsIgnoreCase(envName)), verifiable through createConfig
            // which hardcodes "prod". For non-prod envs we'd need setEnvByBlueprintName.
            // We cover the non-prod case by stubbing setEnvByBlueprintName path.
            var projectObj = Project.builder().id(projectId).name(projectName).build();
            lenient().when(projectService.get(projectId, workspaceId)).thenReturn(projectObj);

            // Stub for setEnvByBlueprintName path
            var existingConfig = AgentConfig.builder().id(configId).build();
            lenient().when(agentConfigDAO.getConfigByProjectId(workspaceId, projectId)).thenReturn(existingConfig);
            lenient().when(agentConfigDAO.getBlueprintByNameAndType(workspaceId, projectId, blueprintName,
                    AgentBlueprint.BlueprintType.BLUEPRINT))
                    .thenReturn(AgentBlueprint.builder()
                            .id(blueprintId)
                            .projectId(projectId)
                            .type(AgentBlueprint.BlueprintType.BLUEPRINT)
                            .name(blueprintName)
                            .values(List.of())
                            .build());
            lenient().when(agentConfigDAO.getEnvsByNames(any(), any(), any())).thenReturn(List.of());
            lenient().when(idGenerator.generateId()).thenReturn(UUID.randomUUID());

            service.setEnvByBlueprintName(projectId, envName, blueprintName).block();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> deployedProps = ArgumentCaptor.forClass(Map.class);
            verify(analyticsService).trackEvent(
                    org.mockito.ArgumentMatchers.eq("opik_agent_config_deployed"),
                    deployedProps.capture(),
                    org.mockito.ArgumentMatchers.eq(userName));

            assertThat(deployedProps.getValue()).containsEntry("deployed_to_prod", expectedFlag);
        }
    }
}
