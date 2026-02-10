package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentBatchUpdate;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for batch tag operations using tagsToAdd and tagsToRemove.
 *
 * Tests the new batch tag API that allows atomic add/remove operations in a single call.
 * Currently covers experiments. Can be expanded to test traces, spans, threads, and datasets.
 *
 * Key features tested:
 * - Adding tags via tagsToAdd
 * - Removing tags via tagsToRemove
 * - Combined add/remove in single operation
 * - Duplicate prevention (arrayDistinct)
 * - Validation (50 tag limit, 100 char limit)
 * - Backwards compatibility with tags + mergeTags
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Batch Tag Operations")
@ExtendWith(DropwizardAppExtensionProvider.class)
class BatchTagOperationsTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "test-workspace";

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private static final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final WireMockUtils.WireMockRuntime wireMock;

    private ClientSupport client;
    private String baseUrl;

    {
        Startables.deepStart(REDIS, MYSQL, ZOOKEEPER, CLICKHOUSE).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory analyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(),
                analyticsFactory,
                wireMock.runtimeInfo(),
                REDIS.getRedisURI());
    }

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, "test-user");
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Experiments")
    class ExperimentTagOperations {

        @Test
        @DisplayName("Should add tags using tagsToAdd")
        void batchUpdateWhenAddingTags() {
            var experiment = createExperiment(Set.of("initial"));

            batchUpdate(experiment.id(), Set.of("new-1", "new-2"), null);

            assertThat(getExperiment(experiment.id()).tags())
                    .containsExactlyInAnyOrder("initial", "new-1", "new-2");
        }

        @Test
        @DisplayName("Should remove tags using tagsToRemove")
        void batchUpdateWhenRemovingTags() {
            var experiment = createExperiment(Set.of("tag1", "tag2", "tag3"));

            batchUpdate(experiment.id(), null, Set.of("tag2", "tag3"));

            assertThat(getExperiment(experiment.id()).tags()).containsExactly("tag1");
        }

        @Test
        @DisplayName("Should add and remove in single operation")
        void batchUpdateWhenAddingAndRemovingSimultaneously() {
            var experiment = createExperiment(Set.of("old-1", "old-2", "keep"));

            batchUpdate(experiment.id(), Set.of("new-1", "new-2"), Set.of("old-1", "old-2"));

            assertThat(getExperiment(experiment.id()).tags())
                    .containsExactlyInAnyOrder("keep", "new-1", "new-2");
        }

        @Test
        @DisplayName("Should prevent duplicates and handle non-existent tags")
        void batchUpdateWhenHandlingEdgeCases() {
            var experiment = createExperiment(Set.of("existing"));

            batchUpdate(experiment.id(), Set.of("existing", "new"), null);
            assertThat(getExperiment(experiment.id()).tags())
                    .containsExactlyInAnyOrder("existing", "new");

            batchUpdate(experiment.id(), null, Set.of("non-existent"));
            assertThat(getExperiment(experiment.id()).tags())
                    .containsExactlyInAnyOrder("existing", "new");
        }

        static Stream<Arguments> invalidTagPayloads() {
            Set<String> tooManyTags = IntStream.range(0, 51)
                    .mapToObj(i -> "tag-" + i)
                    .collect(Collectors.toSet());
            Set<String> tooLongTag = Set.of("a".repeat(101));

            return Stream.of(
                    Arguments.of("50 tag limit", tooManyTags),
                    Arguments.of("100 character tag length", tooLongTag));
        }

        @ParameterizedTest(name = "Should validate {0}")
        @MethodSource("invalidTagPayloads")
        @DisplayName("Should reject invalid tag payloads")
        void batchUpdateWhenInvalidTagPayload(String description, Set<String> invalidTags) {
            var experiment = createExperiment(Set.of());

            var response = batchUpdateRequest()
                    .method(HttpMethod.PATCH, Entity.json(ExperimentBatchUpdate.builder()
                            .ids(Set.of(experiment.id()))
                            .update(ExperimentUpdate.builder().tagsToAdd(invalidTags).build())
                            .build()));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("Should reject update when total tags would exceed limit via sequential adds")
        void batchUpdateWhenExceedingTotalTagLimitViaSequentialAdds() {
            Set<String> initialTags = IntStream.range(0, 45)
                    .mapToObj(i -> "existing-" + i)
                    .collect(Collectors.toSet());
            var experiment = createExperiment(initialTags);

            var response = batchUpdateRequest()
                    .method(HttpMethod.PATCH, Entity.json(ExperimentBatchUpdate.builder()
                            .ids(Set.of(experiment.id()))
                            .update(ExperimentUpdate.builder()
                                    .tagsToAdd(Set.of("new-1", "new-2", "new-3", "new-4", "new-5", "new-6"))
                                    .build())
                            .build()));

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("Should maintain backwards compatibility with tags + mergeTags")
        void batchUpdateWhenUsingLegacyMergeTags() {
            var experiment = createExperiment(Set.of("existing"));

            batchUpdateRequest()
                    .method(HttpMethod.PATCH, Entity.json(ExperimentBatchUpdate.builder()
                            .ids(Set.of(experiment.id()))
                            .update(ExperimentUpdate.builder().tags(Set.of("merged")).build())
                            .mergeTags(true)
                            .build()));

            assertThat(getExperiment(experiment.id()).tags())
                    .containsExactlyInAnyOrder("existing", "merged");
        }

        Experiment createExperiment(Set<String> tags) {
            var experiment = factory.manufacturePojo(Experiment.class).toBuilder()
                    .tags(tags)
                    .datasetName("test-dataset")
                    .promptVersion(null)
                    .promptVersions(null)
                    .duration(null)
                    .totalEstimatedCost(null)
                    .totalEstimatedCostAvg(null)
                    .type(ExperimentType.REGULAR)
                    .optimizationId(null)
                    .usage(null)
                    .projectId(null)
                    .datasetVersionId(null)
                    .datasetVersionSummary(null)
                    .build();

            try (var response = client.target(baseUrl + "/v1/private/experiments")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(experiment))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
                var id = TestUtils.getIdFromLocation(response.getLocation());
                return getExperiment(id);
            }
        }

        void batchUpdate(UUID id, Set<String> toAdd, Set<String> toRemove) {
            var update = ExperimentUpdate.builder();
            if (toAdd != null)
                update.tagsToAdd(toAdd);
            if (toRemove != null)
                update.tagsToRemove(toRemove);

            try (var response = client.target(baseUrl + "/v1/private/experiments/batch")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .method(HttpMethod.PATCH, Entity.json(ExperimentBatchUpdate.builder()
                            .ids(Set.of(id))
                            .update(update.build())
                            .build()))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(response.hasEntity()).isFalse();
            }
        }

        Builder batchUpdateRequest() {
            return client.target(baseUrl + "/v1/private/experiments/batch")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME);
        }

        Experiment getExperiment(UUID id) {
            return client.target(baseUrl + "/v1/private/experiments/" + id)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .get(Experiment.class);
        }
    }
}
