package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicsTestData.GENERATE_PATH;
import static com.comet.opik.domain.topics.TopicsTestData.json;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A first build of a window larger than {@code topics.build.maxVectors}, against real ClickHouse and MySQL with
 * {@code /v1/generate} stubbed. The project's traces have a weekly pattern: weekday and weekend intents differ, and the
 * window's last day alone holds more weekend traces than the run reads. The newest traces would be all weekend; the
 * sample holds both, and the labelling pass then gives every trace of the window its row.
 */
@DisplayName("topics window sample")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsWindowSampleTest {

    private static final int MAX_VECTORS = 100;
    private static final int WINDOW_DAYS = 30;
    /** A Sunday: the window's last day is a Saturday. */
    private static final Instant NIGHT = Instant.parse("2026-10-18T00:00:00Z");
    private static final int PER_DAY = 10;
    private static final int LAST_DAY = MAX_VECTORS + 10;

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);
    private final WireMockServer wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, zookeeper, clickHouse).join();
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        try {
            PostCutoverTraces.migrate(clickHouse);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .runtimeInfo(new WireMockRuntimeInfo(wireMock))
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                        new CustomConfig("aiLabels.enabled", "true"),
                        new CustomConfig("aiLabels.inference.url", wireMock.baseUrl()),
                        new CustomConfig("aiLabels.inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("topics.enabled", "true"),
                        new CustomConfig("topics.build.maxVectors", String.valueOf(MAX_VECTORS)),
                        new CustomConfig("topics.vectors.tickInterval", "1h"),
                        new CustomConfig("topics.vectors.tickBudget", "30s")))
                .build());
    }

    private TopicsRebuildService rebuildService;
    private TopicsTestData data;
    private TopicsTestData.Traces traces;

    @BeforeAll
    void beforeAll(TransactionTemplate template, TransactionTemplateAsync clickHouseTemplate,
            @Jit TopicsRebuildService rebuildService, @Jit TopicVectorsDAO vectorsDAO) {
        this.rebuildService = rebuildService;
        this.traces = weeklyTraces();
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, traces);
    }

    @AfterAll
    void afterAll() {
        wireMock.stop();
    }

    @Test
    @DisplayName("a window over maxVectors builds on an even sample that sees weekdays and weekends, then every trace "
            + "of the window gets a row of the run")
    void sampleSeesTheWholeWindow() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        int count = traces.traceIds().size();
        data.seed(workspaceId, projectId, 0, count);
        wireMock.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(TopicsTestData.answer(oneTopic(), 0)));

        var runId = rebuildService.startRun(workspaceId, projectId, TopicRun.Mode.FIRST_BUILD);
        var outcome = rebuildService.firstBuild(runId, workspaceId, projectId,
                LocalDate.ofInstant(NIGHT, ZoneOffset.UTC));

        assertThat(outcome.status()).isEqualTo(TopicRun.Status.SAVED);
        var stats = json(data.runs(projectId).getFirst().stats());
        assertThat(stats.path("window_traces").asInt()).isEqualTo(count);
        assertThat(stats.path("sample_traces").asInt()).isEqualTo(MAX_VECTORS);
        assertThat(stats.path("vectors").asInt()).isEqualTo(MAX_VECTORS);
        // The newest 100 traces are all the last Saturday's; the sample reaches the weekdays too.
        var prompt = wireMock.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH))).getFirst().getBodyAsString();
        assertThat(prompt).contains("weekday intent").contains("weekend intent");

        var topics = data.topics(projectId);
        assertThat(topics).hasSize(1);
        var rows = data.traceTopics(workspaceId, projectId);
        assertThat(rows).extracting(TraceTopic::traceId).containsExactlyInAnyOrderElementsOf(traces.traceIds());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.runId()).isEqualTo(runId.toString());
            assertThat(row.source()).isEqualTo(TraceTopic.Source.RUN);
            // Every trace is near a centre of its kind, and every group is the one topic's.
            assertThat(row.topicId()).isEqualTo(topics.getFirst().id());
        });
    }

    /** One topic that takes every group. */
    private static JsonNode oneTopic() {
        var answer = InferenceJson.MAPPER.createObjectNode();
        answer.putArray("topics").addObject()
                .put("name", "Plan questions")
                .put("definition", "Questions about the user's plan");
        var assignment = answer.putArray("assignment");
        for (int cluster = 0; cluster < KMeansTopicAlgorithm.groups(MAX_VECTORS); cluster++) {
            assignment.addObject().put("cluster", cluster).put("topic", 0);
        }
        return answer;
    }

    /**
     * {@link #PER_DAY} traces on each day of the window and {@link #LAST_DAY} on its last day. Weekday traces have
     * weekday texts and vectors near one direction, weekend traces weekend texts and vectors near another.
     */
    private static TopicsTestData.Traces weeklyTraces() {
        int dimensions = TopicVectorsTickService.EMBED_DIMENSIONS;
        var random = new Random(5);
        var ids = Generators.timeBasedEpochGenerator(random);
        var weekday = unit(random, dimensions);
        var weekend = unit(random, dimensions);
        var traceIds = new ArrayList<UUID>();
        var createdAt = new ArrayList<Instant>();
        var texts = new ArrayList<String>();
        var vectors = new ArrayList<float[]>();
        for (int day = WINDOW_DAYS; day >= 1; day--) {
            var start = NIGHT.minus(Duration.ofDays(day));
            var dayOfWeek = LocalDate.ofInstant(start, ZoneOffset.UTC).getDayOfWeek();
            boolean isWeekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
            int perDay = day == 1 ? LAST_DAY : PER_DAY;
            for (int i = 0; i < perDay; i++) {
                var created = start.plus(Duration.ofDays(1).multipliedBy(i).dividedBy(perDay));
                createdAt.add(created);
                traceIds.add(ids.construct(created.toEpochMilli()));
                texts.add((isWeekend ? "weekend intent " : "weekday intent ") + texts.size());
                vectors.add(nudge(isWeekend ? weekend : weekday, random));
            }
        }
        var flat = new float[vectors.size() * dimensions];
        for (int i = 0; i < vectors.size(); i++) {
            System.arraycopy(vectors.get(i), 0, flat, i * dimensions, dimensions);
        }
        return new TopicsTestData.Traces(List.copyOf(traceIds), List.copyOf(createdAt),
                texts.toArray(String[]::new), flat, NIGHT);
    }

    private static float[] unit(Random random, int dimensions) {
        var vector = new float[dimensions];
        for (int t = 0; t < dimensions; t++) {
            vector[t] = (float) random.nextGaussian();
        }
        return normalize(vector);
    }

    /** A unit vector at cosine about 0.95 from {@code base}. */
    private static float[] nudge(float[] base, Random random) {
        var noise = unit(random, base.length);
        var vector = new float[base.length];
        for (int t = 0; t < base.length; t++) {
            vector[t] = base[t] + 0.3f * noise[t];
        }
        return normalize(vector);
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        for (int t = 0; t < vector.length; t++) {
            vector[t] /= (float) Math.sqrt(norm);
        }
        return vector;
    }
}
