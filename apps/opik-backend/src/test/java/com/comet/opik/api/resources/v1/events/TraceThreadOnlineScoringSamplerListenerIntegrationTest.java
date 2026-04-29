package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Source;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.common.eventbus.EventBus;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TraceThreadOnlineScoringSamplerListenerIntegrationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER_NAME = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private final TraceThreadService traceThreadService;
    private final EventBus eventBus;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, ClickHouseContainerUtils.DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        traceThreadService = Mockito.mock(TraceThreadService.class);
        eventBus = Mockito.mock(EventBus.class);

        app = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .mockEventBus(eventBus)
                        .modules(List.of(
                                new AbstractModule() {
                                    @Override
                                    protected void configure() {
                                        bind(TraceThreadService.class).toInstance(traceThreadService);
                                    }
                                }))
                        .build());
    }

    private AutomationRuleEvaluatorResourceClient evaluatorResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER_NAME);

        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        evaluatorResourceClient = new AutomationRuleEvaluatorResourceClient(clientSupport, baseUrl);

        Mockito.reset(traceThreadService, eventBus);
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"SDK"})
    @NullSource
    void processesThreadsWithSdkOrNullSource(Source source,
            TraceThreadOnlineScoringSamplerListener listener) {
        Mockito.reset(traceThreadService, eventBus);

        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluator = createEvaluator(projectId);
        evaluatorResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var thread = createThread(projectId, source);
        var event = new TraceThreadsCreated(List.of(thread), projectId, WORKSPACE_ID, USER_NAME);

        Mockito.when(traceThreadService.updateThreadSampledValue(eq(projectId), any()))
                .thenReturn(Mono.empty());

        listener.onTraceThreadOnlineScoringSampled(event);

        verify(traceThreadService).updateThreadSampledValue(eq(projectId), any());
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, mode = EnumSource.Mode.EXCLUDE, names = {"SDK"})
    void skipsNonSdkThreads(Source source,
            TraceThreadOnlineScoringSamplerListener listener) {
        Mockito.reset(traceThreadService, eventBus);

        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluator = createEvaluator(projectId);
        evaluatorResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var thread = createThread(projectId, source);
        var event = new TraceThreadsCreated(List.of(thread), projectId, WORKSPACE_ID, USER_NAME);

        listener.onTraceThreadOnlineScoringSampled(event);

        verifyNoInteractions(traceThreadService);
    }

    private TraceThreadModel createThread(UUID projectId, Source source) {
        return factory.manufacturePojo(TraceThreadModel.class).toBuilder()
                .projectId(projectId)
                .status(TraceThreadStatus.ACTIVE)
                .source(source)
                .build();
    }

    private AutomationRuleEvaluatorTraceThreadLlmAsJudge createEvaluator(UUID projectId) {
        return factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                .projectIds(Set.of(projectId))
                .samplingRate(1.0f)
                .enabled(true)
                .filters(List.of())
                .build();
    }
}
