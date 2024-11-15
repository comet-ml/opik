package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Feedback Scores Resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackScoreResourceTest {

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    private static final TestDropwizardAppExtensionUtils.AppContextConfig contextConfig;
    private static final String RESOURCE_PATH = "%s/v1/private/feedback-scores";

    static {
        Startables.deepStart(REDIS, MY_SQL_CONTAINER, CLICK_HOUSE_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        contextConfig = TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MY_SQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .cacheTtlInSeconds(null)
                .build();

        app = newTestDropwizardAppExtension(contextConfig);
    }
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, podamFactory);
        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, workspaceId);
    }

    @Nested
    @DisplayName("Get Feedback Score names")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetFeedbackScoreNames {

        @ParameterizedTest
        @MethodSource
        @DisplayName("when get feedback score names, then return feedback score names")
        void getFeedbackScoreNames__whenGetFeedbackScoreNames__thenReturnFeedbackScoreNames(
                boolean userProjectId,
                boolean withExperimentsOnly,
                List<String> names,
                List<String> otherNames) {
            // given
            var apiKey = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // when
            String projectName = UUID.randomUUID().toString();

            UUID projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
            Project project = projectResourceClient.getProject(projectId, apiKey, workspaceName);

            // Create multiple values feedback scores
            List<String> multipleValuesFeedbackScores = names.subList(0, names.size() - 1);

            List<List<FeedbackScoreBatchItem>> multipleValuesFeedbackScoreList = createMultiValueScores(
                    multipleValuesFeedbackScores, project, apiKey, workspaceName);

            List<List<FeedbackScoreBatchItem>> singleValueScores = createMultiValueScores(List.of(names.getLast()),
                    project, apiKey, workspaceName);

            createExperimentsItems(apiKey, workspaceName, multipleValuesFeedbackScoreList, singleValueScores);

            // Create unexpected feedback scores
            var unexpectedProject = podamFactory.manufacturePojo(Project.class);

            List<List<FeedbackScoreBatchItem>> unexpectedScores = createMultiValueScores(otherNames, unexpectedProject,
                    apiKey, workspaceName);

            if (!withExperimentsOnly) {
                createExperimentsItems(apiKey, workspaceName, unexpectedScores, List.of());
            }

            WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI)).path("/names");

            if (userProjectId) {
                webTarget = webTarget.queryParam("project_id", projectId);
            }

            if (withExperimentsOnly) {
                webTarget = webTarget.queryParam("with_experiments_only", withExperimentsOnly);
            }

            List<String> expectedNames = (withExperimentsOnly || userProjectId)
                    ? names
                    : Stream.of(names, otherNames).flatMap(List::stream).toList();

            try (var actualResponse = webTarget
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                // then
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                var actualEntity = actualResponse.readEntity(FeedbackScoreNames.class);

                assertThat(actualEntity.scores()).hasSize(expectedNames.size());
                assertThat(actualEntity
                        .scores()
                        .stream()
                        .map(FeedbackScoreNames.ScoreName::name)
                        .toList()).containsExactlyInAnyOrderElementsOf(expectedNames);
            }
        }

        Stream<Arguments> getFeedbackScoreNames__whenGetFeedbackScoreNames__thenReturnFeedbackScoreNames() {
            return Stream.of(
                    Arguments.of(true, false,
                            PodamFactoryUtils.manufacturePojoList(podamFactory, String.class),
                            PodamFactoryUtils.manufacturePojoList(podamFactory, String.class)),
                    Arguments.of(false, true,
                            PodamFactoryUtils.manufacturePojoList(podamFactory, String.class),
                            PodamFactoryUtils.manufacturePojoList(podamFactory, String.class)),
                    Arguments.of(true, true,
                            PodamFactoryUtils.manufacturePojoList(podamFactory, String.class),
                            PodamFactoryUtils.manufacturePojoList(podamFactory, String.class)),
                    Arguments.of(false, false,
                            PodamFactoryUtils.manufacturePojoList(podamFactory, String.class),
                            PodamFactoryUtils.manufacturePojoList(podamFactory, String.class)));
        }
    }

    private List<List<FeedbackScoreBatchItem>> createMultiValueScores(List<String> multipleValuesFeedbackScores,
            Project project, String apiKey, String workspaceName) {
        return IntStream.range(0, multipleValuesFeedbackScores.size())
                .mapToObj(i -> {

                    Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                            .name(project.name())
                            .build();

                    traceResourceClient.createTrace(trace, apiKey, workspaceName);

                    List<FeedbackScoreBatchItem> scores = multipleValuesFeedbackScores.stream()
                            .map(name -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .name(name)
                                    .projectName(project.name())
                                    .id(trace.id())
                                    .build())
                            .toList();

                    traceResourceClient.feedbackScore(scores, apiKey, workspaceName);

                    return scores;
                }).toList();
    }

    private void createExperimentsItems(String apiKey, String workspaceName,
            List<List<FeedbackScoreBatchItem>> multipleValuesFeedbackScoreList,
            List<List<FeedbackScoreBatchItem>> singleValueScores) {

        UUID experimentId = experimentResourceClient.createExperiment(apiKey, workspaceName);

        Stream.of(multipleValuesFeedbackScoreList, singleValueScores)
                .flatMap(List::stream)
                .flatMap(List::stream)
                .map(FeedbackScoreBatchItem::id)
                .distinct()
                .forEach(traceId -> {
                    var experimentItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                            .traceId(traceId)
                            .experimentId(experimentId)
                            .build();

                    experimentResourceClient.createExperimentItem(Set.of(experimentItem), apiKey, workspaceName);
                });
    }
}