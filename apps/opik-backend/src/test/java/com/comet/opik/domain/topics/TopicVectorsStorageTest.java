package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelStatus;
import com.comet.opik.domain.ailabels.AiLabelStatusDAO;
import com.comet.opik.domain.ailabels.AiLabelsDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
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
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.TASK;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.assertVectors;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.doneStatus;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.expectedVectors;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.outputLabel;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.traceCreatedAt;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.vector;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The topic vectors storage against real ClickHouse and MySQL: {@link TopicVectorsDAO}'s window read, discovery,
 * writes and retention. Tests seed {@code ai_label_status}, {@code ai_labels} and {@code topic_vectors} directly.
 */
@DisplayName("topic vectors storage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicVectorsStorageTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    // Not reused: the traces cutover is destructive.
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, zookeeper, clickHouse).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysql);
        PostCutoverTraces.migrate(clickHouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true")))
                .build());
    }

    private TransactionTemplateAsync templateAsync;
    private TopicVectorsDAO vectorsDAO;
    private AiLabelStatusDAO statusDAO;
    private AiLabelsDAO labelsDAO;
    private TopicVectorsTables tables;
    private TransactionTemplate template;
    private TopicVectorBackfillService backfillService;

    @BeforeAll
    void beforeAll(TransactionTemplate template, TransactionTemplateAsync templateAsync,
            @Jit TopicVectorsDAO vectorsDAO, @Jit AiLabelStatusDAO statusDAO, @Jit AiLabelsDAO labelsDAO,
            @Jit TopicVectorBackfillService backfillService) {
        this.template = template;
        this.backfillService = backfillService;
        this.templateAsync = templateAsync;
        this.vectorsDAO = vectorsDAO;
        this.statusDAO = statusDAO;
        this.labelsDAO = labelsDAO;
        this.tables = new TopicVectorsTables(template, templateAsync);
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouse.stop();
        zookeeper.stop();
        mysql.stop();
        network.close();
    }

    @Test
    @DisplayName("the window read finds a done status without a vector, and nothing once the vector is written")
    void windowReadAndAntiJoin() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId();
        var failedTraceId = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 3),
                outputLabel(workspaceId, projectId, failedTraceId, 3))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 3))).block();
        // Done, then failed: its latest row is not done.
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, failedTraceId, 3))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, failedTraceId, 3).toBuilder()
                .status(AiLabelStatus.Status.FAILED).build())).block();
        var lower = Instant.now().minus(1, ChronoUnit.HOURS);

        var found = findUnvectored(workspaceId, lower);

        assertCandidates(found, candidate(workspaceId, projectId, traceId, 3));

        vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 3))).block();

        assertThat(findUnvectored(workspaceId, lower)).isEmpty();
        // Another version of the same trace still needs its own vector.
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 4))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 4))).block();
        assertCandidates(findUnvectored(workspaceId, lower), candidate(workspaceId, projectId, traceId, 4));
    }

    @Test
    @DisplayName("a vector whose latest row is a tombstone does not count: the window read finds its trace again, "
            + "and a newer live row hides it once more")
    void tombstonedVector() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 3))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 3))).block();
        vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 3))).block();
        var lower = Instant.now().minus(1, ChronoUnit.HOURS);
        assertThat(findUnvectored(workspaceId, lower)).isEmpty();

        insertTombstone(workspaceId, traceId, 3);

        assertCandidates(findUnvectored(workspaceId, lower), candidate(workspaceId, projectId, traceId, 3));
        assertThat(tables.vectors(workspaceId, List.of(traceId))).isEmpty();

        vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 3))).block();

        assertThat(findUnvectored(workspaceId, lower)).isEmpty();
        assertThat(tables.vectors(workspaceId, List.of(traceId))).hasSize(1);
    }

    @Test
    @DisplayName("the trace_created_at bound drops older traces from both sides alike, so the anti-join stays exact")
    void createdAtBound() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var createdAfter = now.minus(9, ChronoUnit.DAYS);
        var inside = ID_GENERATOR.generateId();
        var insideCreatedAt = createdAfter.plus(1, ChronoUnit.HOURS);
        var outside = ID_GENERATOR.generateId();
        var outsideCreatedAt = createdAfter.minus(1, ChronoUnit.HOURS);
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, inside, 1),
                outputLabel(workspaceId, projectId, outside, 1))).block();
        statusDAO.insert(List.of(
                doneStatus(workspaceId, projectId, inside, 1).toBuilder().traceCreatedAt(insideCreatedAt).build(),
                doneStatus(workspaceId, projectId, outside, 1).toBuilder().traceCreatedAt(outsideCreatedAt)
                        .build()))
                .block();
        var lower = now.minus(1, ChronoUnit.HOURS);
        var insideCandidate = candidate(workspaceId, projectId, inside, 1).toBuilder()
                .traceCreatedAt(insideCreatedAt).build();
        var outsideCandidate = candidate(workspaceId, projectId, outside, 1).toBuilder()
                .traceCreatedAt(outsideCreatedAt).build();

        assertCandidates(findUnvectored(workspaceId, lower, createdAfter), insideCandidate);
        // Without the bound both are found: the bound is what leaves the older one out.
        assertCandidates(findUnvectored(workspaceId, lower, Instant.EPOCH), insideCandidate, outsideCandidate);

        vectorsDAO.insert(List.of(vector(workspaceId, projectId, inside, 1).toBuilder()
                .traceCreatedAt(insideCreatedAt).build())).block();

        assertThat(findUnvectored(workspaceId, lower, createdAfter)).isEmpty();
        // A status rewritten long after its vector, of a trace still inside the bound, stays dropped.
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, inside, 1).toBuilder()
                .traceCreatedAt(insideCreatedAt).build())).block();
        assertThat(findUnvectored(workspaceId, lower, createdAfter)).isEmpty();
    }

    @Test
    @DisplayName("the summary read starts at the second of the oldest done trace id: that trace, whose id time has a "
            + "millisecond part, and a trace of a later week are both found")
    void idAtBoundOfTheSummaryRead() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        // id_at cuts the id's time to the second; a bound at 999 ms past it would leave this trace's row out.
        var oldest = ID_GENERATOR.generateId(Instant.now().minus(10, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS).plusMillis(999));
        var newest = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, oldest, 1),
                outputLabel(workspaceId, projectId, newest, 1))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, oldest, 1),
                doneStatus(workspaceId, projectId, newest, 1))).block();

        assertCandidates(findUnvectored(workspaceId, Instant.now().minus(1, ChronoUnit.HOURS)),
                candidate(workspaceId, projectId, oldest, 1), candidate(workspaceId, projectId, newest, 1));
    }

    @Test
    @DisplayName("statuses without a usable summary don't take the LIMIT: an older blank, Unicode-blank, missing "
            + "or other-version output leaves room for a newer usable one")
    void unusableSummariesBeforeLimit() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var blank = ID_GENERATOR.generateId();
        var missing = ID_GENERATOR.generateId();
        var otherVersion = ID_GENERATOR.generateId();
        var unicodeBlank = ID_GENERATOR.generateId();
        var usable = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, blank, 1, "   "),
                outputLabel(workspaceId, projectId, unicodeBlank, 1, "\u2003\u3000\u2028\u00A0\u2007\u202F\u0085"),
                outputLabel(workspaceId, projectId, otherVersion, 2),
                outputLabel(workspaceId, projectId, usable, 1))).block();
        var oldest = Instant.now().minus(30, ChronoUnit.MINUTES);
        tables.insertStatusAt(workspaceId, projectId, blank, 1, oldest);
        tables.insertStatusAt(workspaceId, projectId, missing, 1, oldest.plusSeconds(1));
        tables.insertStatusAt(workspaceId, projectId, otherVersion, 1, oldest.plusSeconds(2));
        tables.insertStatusAt(workspaceId, projectId, unicodeBlank, 1, oldest.plusSeconds(3));
        tables.insertStatusAt(workspaceId, projectId, usable, 1, oldest.plusSeconds(4));

        var found = vectorsDAO.findUnvectored(windowRead(workspaceId, Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now(), Instant.EPOCH).limit(1).build()).collectList().block();

        // Written by insertStatusAt, so its updated_at is known too.
        assertThat(found).containsExactly(candidate(workspaceId, projectId, usable, 1).toBuilder()
                .statusUpdatedAt(oldest.plusSeconds(4).truncatedTo(ChronoUnit.MICROS)).build());
    }

    @Test
    @DisplayName("vectors read back as written, and id_at and the partition equal ai_label_status's")
    void roundTripAndPartition() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var monday = Instant.parse("2025-03-03T00:00:00Z");
        var traceIds = List.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId(monday),
                ID_GENERATOR.generateId(monday.minusMillis(1)));
        var rows = traceIds.stream().map(traceId -> vector(workspaceId, projectId, traceId, 1)).toList();
        vectorsDAO.insert(rows).block();
        statusDAO.insert(traceIds.stream().map(traceId -> doneStatus(workspaceId, projectId, traceId, 1))
                .toList()).block();

        assertVectors(tables.vectors(workspaceId, traceIds), rows);
        assertThat(idAtAndPartition(TopicVectorsDAO.TABLE, workspaceId))
                .isEqualTo(idAtAndPartition("ai_label_status", workspaceId));
    }

    @Test
    @DisplayName("retention deletes vectors in range, and keeps experiment-linked, newer and other workspaces' traces")
    void retention() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var otherWorkspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var now = Instant.now();
        var old = ID_GENERATOR.generateId(now.minus(20, ChronoUnit.DAYS));
        var oldInExperiment = ID_GENERATOR.generateId(now.minus(20, ChronoUnit.DAYS));
        var recent = ID_GENERATOR.generateId(now.minus(1, ChronoUnit.DAYS));
        vectorsDAO.insert(List.of(vector(workspaceId, projectId, old, 1),
                vector(workspaceId, projectId, oldInExperiment, 1),
                vector(workspaceId, projectId, recent, 1),
                vector(otherWorkspaceId, projectId, old, 1))).block();
        insertExperimentItem(workspaceId, oldInExperiment);

        vectorsDAO.deleteForRetention(List.of(workspaceId),
                ID_GENERATOR.generateId(now.minus(10, ChronoUnit.DAYS)),
                ID_GENERATOR.generateId(now.minus(30, ChronoUnit.DAYS))).block();

        assertVectors(tables.vectors(workspaceId, List.of(old, oldInExperiment, recent)),
                expectedVectors(workspaceId, projectId, List.of(oldInExperiment, recent), 1));
        assertVectors(tables.vectors(otherWorkspaceId, List.of(old)),
                List.of(vector(otherWorkspaceId, projectId, old, 1)));
    }

    @Test
    @DisplayName("bounded retention deletes from each workspace's own floor up to the cutoff")
    void boundedRetention() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var now = Instant.now();
        var belowFloor = ID_GENERATOR.generateId(now.minus(20, ChronoUnit.DAYS));
        var aboveFloor = ID_GENERATOR.generateId(now.minus(12, ChronoUnit.DAYS));
        var afterCutoff = ID_GENERATOR.generateId(now.minus(5, ChronoUnit.DAYS));
        vectorsDAO.insert(List.of(vector(workspaceId, projectId, belowFloor, 1),
                vector(workspaceId, projectId, aboveFloor, 1),
                vector(workspaceId, projectId, afterCutoff, 1))).block();

        vectorsDAO.deleteForRetentionBounded(
                Map.of(workspaceId, ID_GENERATOR.generateId(now.minus(15, ChronoUnit.DAYS))),
                ID_GENERATOR.generateId(now.minus(10, ChronoUnit.DAYS)),
                ID_GENERATOR.generateId(now.minus(30, ChronoUnit.DAYS))).block();

        assertVectors(tables.vectors(workspaceId, List.of(belowFloor, aboveFloor, afterCutoff)),
                expectedVectors(workspaceId, projectId, List.of(belowFloor, afterCutoff), 1));
    }

    /**
     * Whole candidates, in any order, but {@code statusUpdatedAt}: ClickHouse stamps {@code updated_at} when
     * {@link AiLabelStatusDAO#insert} writes the status.
     */
    @Test
    @DisplayName("the window read in slices of trace creation time, per project or without some projects, finds "
            + "exactly what one read of the window finds: a trace on a slice edge, an old trace with a late "
            + "status and a trace that has its vector included")
    void slicedReadEqualsUnsliced() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projects = List.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId());
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var slice = Duration.ofDays(1);
        var createdAfter = now.minus(10, ChronoUnit.DAYS);
        // Every status is written now, so a trace created days ago has a late status.
        var createdAts = List.of(
                now.minus(12, ChronoUnit.HOURS),
                // Exactly on the edge of the first two slices, and a microsecond on its older side.
                now.minus(slice),
                now.minus(slice).minus(1, ChronoUnit.MICROS),
                now.minus(5, ChronoUnit.DAYS),
                now.minus(5, ChronoUnit.DAYS).plusSeconds(1),
                // Exactly on the window's bound, which is inclusive, and just outside it.
                createdAfter,
                createdAfter.minus(1, ChronoUnit.MICROS));
        var created = new HashMap<UUID, Instant>();
        // The oracle, from the fixture alone: (project, trace) of every trace but the one with a vector and the
        // one before the bound.
        var expectedKeys = new ArrayList<Map.Entry<UUID, UUID>>();
        for (var projectId : projects) {
            var statuses = new ArrayList<AiLabelStatus>();
            for (var createdAt : createdAts) {
                var traceId = ID_GENERATOR.generateId();
                created.put(traceId, createdAt);
                statuses.add(doneStatus(workspaceId, projectId, traceId, 1).toBuilder().traceCreatedAt(createdAt)
                        .build());
            }
            labelsDAO.insert(statuses.stream()
                    .map(status -> outputLabel(workspaceId, projectId, status.traceId(), 1)).toList()).block();
            statusDAO.insert(statuses).block();
            // One trace of the project, five days old, already has its vector.
            var vectored = statuses.get(3);
            vectorsDAO.insert(List.of(vector(workspaceId, projectId, vectored.traceId(), 1).toBuilder()
                    .traceCreatedAt(vectored.traceCreatedAt()).build())).block();
            created.remove(vectored.traceId());
            var beforeBound = statuses.getLast();
            statuses.stream().filter(status -> status != vectored && status != beforeBound)
                    .forEach(status -> expectedKeys.add(Map.entry(projectId, status.traceId())));
        }
        var lower = now.minus(1, ChronoUnit.HOURS);
        var upper = Instant.now();

        var unsliced = vectorsDAO.findUnvectored(windowRead(workspaceId, lower, upper, createdAfter).build())
                .collectList().block();

        // Per project: 7 traces, less the one with a vector and the one before the bound.
        assertThat(expectedKeys).hasSize(10);
        assertThat(unsliced).extracting(candidate -> Map.entry(candidate.projectId(), candidate.traceId()))
                .containsExactlyInAnyOrderElementsOf(expectedKeys);
        assertThat(unsliced).allSatisfy(candidate -> assertThat(candidate.traceCreatedAt())
                .isEqualTo(created.get(candidate.traceId())));
        var byKey = Comparator.comparing(TopicVectorCandidate::projectId)
                .thenComparing(TopicVectorCandidate::traceId);
        var expected = unsliced.stream().sorted(byKey).toList();

        var slicedAll = sliced(workspaceId, null, List.of(), lower, upper, now, createdAfter, slice);
        assertThat(slicedAll).extracting(candidate -> Map.entry(candidate.projectId(), candidate.traceId()))
                .containsExactlyInAnyOrderElementsOf(expectedKeys);
        assertThat(slicedAll.stream().sorted(byKey)).containsExactlyElementsOf(expected);
        // Each project on its own, in slices and in one read.
        for (var projectId : projects) {
            var ofProject = expected.stream().filter(candidate -> candidate.projectId().equals(projectId))
                    .toList();
            assertThat(sliced(workspaceId, projectId, List.of(), lower, upper, now, createdAfter, slice).stream()
                    .sorted(byKey)).containsExactlyElementsOf(ofProject);
            assertThat(vectorsDAO.findUnvectored(windowRead(workspaceId, lower, upper, createdAfter)
                    .projectId(projectId).build()).collectList().block().stream().sorted(byKey))
                    .containsExactlyElementsOf(ofProject);
        }
        // Without the second project: the first project's.
        assertThat(sliced(workspaceId, null, List.of(projects.get(1)), lower, upper, now, createdAfter, slice)
                .stream().sorted(byKey))
                .containsExactlyElementsOf(expected.stream()
                        .filter(candidate -> candidate.projectId().equals(projects.get(0))).toList());
    }

    @Test
    @DisplayName("the discovery finds each project's done status rows of the window and its latest one, and the "
            + "vector count its traces with a live vector; a failed status, a tombstoned vector, a second task "
            + "version and a rewritten row are not counted")
    void discoveryAggregates() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var first = ID_GENERATOR.generateId();
        var second = ID_GENERATOR.generateId();
        var firstTraces = IntStream.range(0, 3).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        var secondTraces = IntStream.range(0, 2).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        var before = Instant.now().minusSeconds(1);
        statusDAO.insert(firstTraces.stream().map(traceId -> doneStatus(workspaceId, first, traceId, 1)).toList())
                .block();
        statusDAO.insert(List.of(
                doneStatus(workspaceId, second, secondTraces.get(0), 1),
                doneStatus(workspaceId, second, secondTraces.get(1), 1).toBuilder()
                        .status(AiLabelStatus.Status.FAILED).build()))
                .block();
        vectorsDAO.insert(List.of(vector(workspaceId, first, firstTraces.get(0), 1),
                vector(workspaceId, first, firstTraces.get(1), 1),
                vector(workspaceId, second, secondTraces.get(0), 1))).block();
        insertTombstone(workspaceId, secondTraces.get(0), 1);
        // A relabel's second task version of one trace, and a rewrite of another's row: still one trace each.
        vectorsDAO.insert(List.of(vector(workspaceId, first, firstTraces.get(0), 2),
                vector(workspaceId, first, firstTraces.get(1), 1))).block();
        var lower = Instant.now().minus(2, ChronoUnit.HOURS);

        var found = vectorsDAO.findBackfillProjects(workspaceId, TASK, lower, lower.minus(8, ChronoUnit.DAYS), 10)
                .collectList().block();
        var tracesWithVectors = vectorsDAO.countTracesWithVectorsByProject(workspaceId, lower, 10).block();

        assertThat(found).extracting(TopicBackfillProject::projectId, TopicBackfillProject::traces)
                .containsExactlyInAnyOrder(tuple(first, 3L), tuple(second, 1L));
        assertThat(found).allSatisfy(project -> assertThat(project.lastDoneAt()).isBetween(before,
                Instant.now().plusSeconds(1)));
        assertThat(tracesWithVectors.get(first)).isEqualTo(2);
        // The key's latest row is the tombstone, so the row it replaces does not count before the merge either.
        assertThat(tracesWithVectors).doesNotContainKey(second);
        // A window that starts after the traces were created counts none of them, but still finds the projects.
        assertThat(vectorsDAO.findBackfillProjects(workspaceId, TASK, before, lower.minus(8, ChronoUnit.DAYS), 10)
                .collectList().block())
                .extracting(TopicBackfillProject::projectId, TopicBackfillProject::traces)
                .containsExactlyInAnyOrder(tuple(first, 0L), tuple(second, 0L));
    }

    @Test
    @DisplayName("the backfill table: a started row is not started again, a discovery keeps a row's start and drained "
            + "mark, progress never passes the total, and rows not checked for long start again")
    void backfillTable() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var first = ID_GENERATOR.generateId();
        var second = ID_GENERATOR.generateId();
        var third = ID_GENERATOR.generateId();
        var t0 = Instant.parse("2026-11-01T10:00:00.123456Z");
        List.of(first, second, third).forEach(projectId -> tables.project(workspaceId, projectId));
        var row = TopicVectorBackfill.builder().workspaceId(workspaceId).startedAt(t0).checkedAt(t0).lastDoneAt(t0);
        backfillService.start(List.of(row.projectId(first).build(), row.projectId(second).build()));
        backfillService.start(List.of(row.projectId(first).startedAt(t0.plusSeconds(60)).build()));
        var t1 = t0.plusSeconds(100);
        backfillService.record(List.of(TopicVectorBackfill.Progress.builder().workspaceId(workspaceId)
                .projectId(first).drainedAt(t1).written(5).build()));

        var started = backfillService.findByKeys(workspaceId, List.of(first, second, third));
        // Never discovered, so its total is 0 and nothing written counts yet.
        assertThat(started).isEqualTo(Map.of(
                first, row.projectId(first).startedAt(t0).drainedAt(t1).build(),
                second, row.projectId(second).startedAt(t0).drainedAt(null).build()));
        assertThat(backfillService.findUndrained(List.of(workspaceId)))
                .isEqualTo(Map.of(workspaceId, List.of(started.get(second))));

        var t2 = t0.plusSeconds(200);
        var t3 = t0.plusSeconds(300);
        var found = TopicVectorBackfill.builder().workspaceId(workspaceId).startedAt(t3).checkedAt(t3);
        backfillService.discovered(workspaceId, List.of(
                found.projectId(first).lastDoneAt(t2).tracesTotal(10).tracesDone(4).build(),
                found.projectId(third).lastDoneAt(t2).tracesTotal(7).tracesDone(0).build()), t3, t0.minusSeconds(1));
        var progress = TopicVectorBackfill.Progress.builder().workspaceId(workspaceId).projectId(third).written(5)
                .build();
        backfillService.record(List.of(progress, progress));

        assertThat(backfillService.findByKeys(workspaceId, List.of(first, second, third))).isEqualTo(Map.of(
                // Its start and drained mark are kept; the counts and the latest status are the discovery's.
                first, found.projectId(first).startedAt(t0).drainedAt(t1).lastDoneAt(t2).tracesTotal(10)
                        .tracesDone(4).build(),
                // Not found by the discovery, but checked with its workspace.
                second, row.projectId(second).startedAt(t0).checkedAt(t3).drainedAt(null).build(),
                // New: starts a backfill. 5 + 5 written of 7.
                third, found.projectId(third).startedAt(t3).drainedAt(null).lastDoneAt(t2).tracesTotal(7)
                        .tracesDone(7).build()));

        // The tick comes back long after t3: every row starts again.
        var t4 = t3.plus(3, ChronoUnit.DAYS);
        backfillService.discovered(workspaceId, List.of(), t4, t4.minus(2, ChronoUnit.DAYS));

        var all = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicVectorBackfillDAO.class).findByWorkspaceIds(List.of(workspaceId)));
        assertThat(all).hasSize(3).allSatisfy(reset -> {
            assertThat(reset.startedAt()).isEqualTo(t4);
            assertThat(reset.checkedAt()).isEqualTo(t4);
            assertThat(reset.drainedAt()).isNull();
            assertThat(reset.tracesTotal()).isZero();
            assertThat(reset.tracesDone()).isZero();
        });
    }

    @Test
    @DisplayName("a discovery removes the rows of projects that no longer exist, also one it was about to write again")
    void discoveryRemovesRowsOfDeletedProjects() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var kept = ID_GENERATOR.generateId();
        var deleted = ID_GENERATOR.generateId();
        tables.project(workspaceId, kept);
        var t0 = Instant.parse("2026-11-01T10:00:00Z");
        var row = TopicVectorBackfill.builder().workspaceId(workspaceId).startedAt(t0).checkedAt(t0).lastDoneAt(t0);
        // Left behind by a failed delete on ProjectsDeleted.
        backfillService.start(List.of(row.projectId(kept).build(), row.projectId(deleted).build()));

        // The deleted project's statuses are still there, so the discovery finds it again.
        int removed = backfillService.discovered(workspaceId, List.of(row.projectId(kept).tracesTotal(3).build(),
                row.projectId(deleted).tracesTotal(3).build()), t0.plusSeconds(60), t0);

        assertThat(removed).isOne();
        assertThat(backfillService.findByKeys(workspaceId, List.of(kept, deleted))).containsOnlyKeys(kept);
    }

    /** The window read as the tick walks it: slices counted back from {@code start}, the newest open-ended. */
    private List<TopicVectorCandidate> sliced(String workspaceId, UUID projectId, List<UUID> excluded,
            Instant lower, Instant upper, Instant start, Instant createdAfter, Duration slice) {
        var candidates = new ArrayList<TopicVectorCandidate>();
        Instant createdBefore = null;
        while (true) {
            var sliceStart = (createdBefore == null ? start : createdBefore).minus(slice);
            var from = sliceStart.isAfter(createdAfter) ? sliceStart : createdAfter;
            candidates.addAll(vectorsDAO.findUnvectored(windowRead(workspaceId, lower, upper, from)
                    .createdBefore(createdBefore).projectId(projectId).excludedProjectIds(excluded).build())
                    .collectList().block());
            if (!from.isAfter(createdAfter)) {
                return candidates;
            }
            createdBefore = from;
        }
    }

    private void assertCandidates(List<TopicVectorCandidate> actual, TopicVectorCandidate... expected) {
        assertThat(actual).usingRecursiveFieldByFieldElementComparatorIgnoringFields("statusUpdatedAt")
                .containsExactlyInAnyOrder(expected);
    }

    /** The candidate of a status {@link #doneStatus} built; {@code statusUpdatedAt} is not known. */
    private TopicVectorCandidate candidate(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        return TopicVectorCandidate.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(traceCreatedAt(traceId))
                .taskVersion(taskVersion)
                .statusUpdatedAt(Instant.EPOCH)
                .build();
    }

    private List<TopicVectorCandidate> findUnvectored(String workspaceId, Instant lower) {
        return findUnvectored(workspaceId, lower, Instant.EPOCH);
    }

    private List<TopicVectorCandidate> findUnvectored(String workspaceId, Instant lower, Instant createdAfter) {
        return vectorsDAO.findUnvectored(windowRead(workspaceId, lower, Instant.now(), createdAfter).build())
                .collectList().block();
    }

    /** A window read of {@link TopicVectorsFixtures#TASK} with room for 100 candidates and 10 s. */
    private static TopicVectorsFind.TopicVectorsFindBuilder windowRead(String workspaceId, Instant lowerBound,
            Instant upperBound, Instant createdAfter) {
        return TopicVectorsFind.builder()
                .workspaceId(workspaceId)
                .task(TASK)
                .lowerBound(lowerBound)
                .upperBound(upperBound)
                .createdAfter(createdAfter)
                .limit(100)
                .maxExecutionSeconds(10);
    }

    private void insertExperimentItem(String workspaceId, UUID traceId) {
        tables.execute("""
                INSERT INTO experiment_items (id, experiment_id, dataset_item_id, trace_id, workspace_id)
                VALUES (:id, :experiment_id, :dataset_item_id, :trace_id, :workspace_id)
                """, Map.of("id", ID_GENERATOR.generateId().toString(),
                "experiment_id", ID_GENERATOR.generateId().toString(),
                "dataset_item_id", ID_GENERATOR.generateId().toString(),
                "trace_id", traceId.toString(), "workspace_id", workspaceId));
    }

    /** A copy of the key's row with {@code is_deleted = 1}; ClickHouse stamps a later {@code last_updated_at}. */
    private void insertTombstone(String workspaceId, UUID traceId, int taskVersion) {
        tables.execute("""
                INSERT INTO topic_vectors (workspace_id, project_id, trace_id, trace_created_at, task_version,
                    embed_model, embed_dimensions, text, vector, is_deleted)
                SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, embed_model,
                    embed_dimensions, text, vector, 1
                FROM topic_vectors
                WHERE workspace_id = :workspace_id AND trace_id = :trace_id AND task_version = :task_version
                LIMIT 1
                """, Map.of("workspace_id", workspaceId, "trace_id", traceId.toString(), "task_version", taskVersion));
    }

    private Map<String, String> idAtAndPartition(String table, String workspaceId) {
        return templateAsync.nonTransaction(connection -> Flux
                .from(connection.createStatement("""
                        SELECT trace_id AS id, concat(toString(id_at), ' ', _partition_id) AS id_at_partition
                        FROM %s
                        WHERE workspace_id = :workspace_id
                        """.formatted(table))
                        .bind("workspace_id", workspaceId)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(row.get("id", String.class),
                        row.get("id_at_partition", String.class))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue))
                .block();
    }
}
