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
import com.comet.opik.domain.topics.TopicVectorsDAO.BackfillProject;
import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.TASK;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.assertVectors;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.doneStatus;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.expectedVectors;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.outputLabel;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.summaryOf;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.traceCreatedAt;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.vector;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("the window read finds a done status without a vector, with its summary, and nothing once the "
            + "vector of its task is written")
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

        // The same key of another task is another vector.
        vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 3).toBuilder().task("other_task").build()))
                .block();
        assertCandidates(findUnvectored(workspaceId, lower), candidate(workspaceId, projectId, traceId, 3));

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
    @DisplayName("a trace with a status row is left out by the window read, and the trace-delete cascade deletes its "
            + "status row with its vectors")
    void statusRowAndCascade() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var unembeddable = ID_GENERATOR.generateId();
        var embedded = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, unembeddable, 3))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, unembeddable, 3))).block();
        vectorsDAO.insert(List.of(vector(workspaceId, projectId, embedded, 3))).block();
        var lower = Instant.now().minus(1, ChronoUnit.HOURS);
        var found = findUnvectored(workspaceId, lower);
        assertCandidates(found, candidate(workspaceId, projectId, unembeddable, 3));

        vectorsDAO.insertStatuses(List.of(new TopicVectorStatus(found.getFirst(), SkipReason.INVALID_VECTOR))).block();

        assertThat(findUnvectored(workspaceId, lower)).isEmpty();
        assertThat(tables.statuses(workspaceId, List.of(unembeddable)))
                .containsExactly(Map.entry(unembeddable, "invalid_vector"));

        vectorsDAO.deleteByTraceIds(Set.of(unembeddable, embedded), projectId)
                .contextWrite(context -> context.put(RequestContext.USER_NAME, "user")
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertThat(tables.statuses(workspaceId, List.of(unembeddable))).isEmpty();
        assertThat(tables.vectors(workspaceId, List.of(embedded))).isEmpty();
        // Its done status is ai-labels', not deleted here: with the status row gone, the read finds it again.
        assertCandidates(findUnvectored(workspaceId, lower), candidate(workspaceId, projectId, unembeddable, 3));
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
    @DisplayName("a window with no new done status reads no rows: the summary, vector and status sides get no "
            + "id_at bound of 1970 from an empty done")
    void emptyWindowReadsNoRows() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId(Instant.now().minus(2, ChronoUnit.DAYS));
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 1))).block();
        vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 1))).block();

        assertThat(findUnvectored(workspaceId, Instant.now())).isEmpty();

        assertThat(findUnvectoredReadRows(workspaceId)).isZero();
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
        var byKey = Comparator.comparing(Candidate::projectId)
                .thenComparing(Candidate::traceId);
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

        var window = new TopicVectorsDAO.Slice(lower.minus(8, ChronoUnit.DAYS), null);
        var found = vectorsDAO.findBackfillProjects(workspaceId, TASK, lower, window, 10).collectList().block();
        var tracesWithVectors = vectorsDAO.countTracesWithVectorsByProject(workspaceId, TASK,
                new TopicVectorsDAO.Slice(lower, null), List.of(first, second), 10).block();

        assertThat(tracesByProject(found)).isEqualTo(Map.of(first, 3L, second, 1L));
        assertThat(found).allSatisfy(project -> assertThat(project.lastDoneAt()).isBetween(before,
                Instant.now().plusSeconds(1)));
        // The key's latest row is the tombstone, so the row it replaces does not count before the merge either.
        assertThat(tracesWithVectors).isEqualTo(Map.of(first, 2L));
        // A status window that starts after the traces were created still counts them: the window read returns them.
        assertThat(tracesByProject(vectorsDAO.findBackfillProjects(workspaceId, TASK, before, window, 10)
                .collectList().block()))
                .isEqualTo(Map.of(first, 3L, second, 1L));
    }

    @Test
    @DisplayName("the discovery's slices of trace creation time cover the window and add up: traces created days "
            + "apart are each counted once, and only the asked projects are counted")
    void discoveryInSlices() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var notAsked = ID_GENERATOR.generateId();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // A week apart or more: the discovery slice is a week, so each trace is in its own slice.
        var createdAts = List.of(now.minus(3, ChronoUnit.DAYS), now.minus(10, ChronoUnit.DAYS),
                now.minus(20, ChronoUnit.DAYS));
        var statuses = new ArrayList<AiLabelStatus>();
        var vectors = new ArrayList<TopicVector>();
        for (var owner : List.of(projectId, notAsked)) {
            for (var createdAt : createdAts) {
                var traceId = ID_GENERATOR.generateId();
                statuses.add(doneStatus(workspaceId, owner, traceId, 1).toBuilder().traceCreatedAt(createdAt).build());
                vectors.add(vector(workspaceId, owner, traceId, 1).toBuilder().traceCreatedAt(createdAt).build());
            }
        }
        statusDAO.insert(statuses).block();
        vectorsDAO.insert(vectors).block();
        var lower = now.minus(25, ChronoUnit.DAYS);

        var slices = vectorsDAO.slices(lower.minus(8, ChronoUnit.DAYS));
        var found = Flux.fromIterable(slices)
                .concatMap(slice -> vectorsDAO.findBackfillProjects(workspaceId, TASK, lower, slice, 10))
                .collect(Collectors.toMap(BackfillProject::projectId, Function.identity(),
                        BackfillProject::merge))
                .block();
        var tracesWithVectors = Flux.fromIterable(vectorsDAO.slices(lower))
                .concatMap(slice -> vectorsDAO.countTracesWithVectorsByProject(workspaceId, TASK, slice,
                        List.of(projectId), 10))
                .flatMapIterable(Map::entrySet)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum))
                .block();

        assertThat(slices).hasSizeGreaterThan(3);
        assertThat(tracesByProject(List.copyOf(found.values()))).isEqualTo(Map.of(projectId, 3L, notAsked, 3L));
        assertThat(tracesWithVectors).isEqualTo(Map.of(projectId, 3L));
        assertThat(vectorsDAO.countTracesWithVectorsByProject(workspaceId, TASK, slices.getFirst(), List.of(), 10)
                .block()).isEmpty();
    }

    @Test
    @DisplayName("bounds with nanoseconds are cut to the microsecond: a status and a trace exactly at the bounds' "
            + "microsecond are in the window")
    void nanosecondBounds() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId();
        var updatedAt = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        tables.insertStatusAt(workspaceId, projectId, traceId, 1, updatedAt);
        var nanos = Duration.ofNanos(999);

        var found = vectorsDAO.findUnvectored(windowRead(workspaceId, updatedAt.plus(nanos), updatedAt.plus(nanos),
                traceCreatedAt(traceId).plus(nanos)).build()).collectList().block();

        assertThat(found).containsExactly(candidate(workspaceId, projectId, traceId, 1).toBuilder()
                .statusUpdatedAt(updatedAt).build());
    }

    @Test
    @DisplayName("NON_BLANK in ClickHouse calls blank exactly what Python's str.strip() removes, which opik-inference "
            + "rejects")
    void nonBlankMatchesPythonStrip() {
        // Every character for which Python's str.isspace() is true.
        var pythonWhitespace = codePoints(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0,
                0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028,
                0x2029, 0x202F, 0x205F, 0x3000);
        // Not whitespace in Python: zero width space, byte order mark, a letter.
        var notWhitespace = codePoints(0x200B, 0xFEFF, 'a');

        assertThat(matchNonBlank(pythonWhitespace)).containsOnly(0);
        assertThat(matchNonBlank(notWhitespace)).containsOnly(1);
    }

    @Test
    @DisplayName("the backfill state: a started backfill is not started again, a discovery keeps its start and drained "
            + "mark, progress never passes the total, and a workspace not discovered for long starts again")
    void backfillState() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var first = ID_GENERATOR.generateId();
        var second = ID_GENERATOR.generateId();
        var third = ID_GENERATOR.generateId();
        var t0 = Instant.parse("2026-11-01T10:00:00.123456Z");
        List.of(first, second, third).forEach(projectId -> tables.project(workspaceId, projectId));
        var row = TopicBackfill.builder().workspaceId(workspaceId).backfillStartedAt(t0).backfillLastDoneAt(t0);
        backfillService.start(List.of(row.projectId(first).build(), row.projectId(second).build()));
        backfillService.start(List.of(row.projectId(first).backfillStartedAt(t0.plusSeconds(60)).build()));
        var t1 = t0.plusSeconds(100);
        backfillService
                .record(List.of(TopicBackfillProgress.builder().workspaceId(workspaceId).projectId(first).drainedAt(t1)
                        .written(5).build()));

        var started = backfillService.findByKeys(workspaceId, List.of(first, second, third));
        // Never discovered, so its total is 0 and nothing written counts yet.
        assertThat(started).isEqualTo(Map.of(
                first, row.projectId(first).backfillStartedAt(t0).backfillDrainedAt(t1).build(),
                second, row.projectId(second).backfillStartedAt(t0).backfillDrainedAt(null).build()));
        assertThat(backfillService.findUndrained(List.of(workspaceId)))
                .isEqualTo(Map.of(workspaceId, List.of(started.get(second))));

        var t2 = t0.plusSeconds(200);
        var t3 = t0.plusSeconds(300);
        var found = TopicBackfill.builder().workspaceId(workspaceId).backfillStartedAt(t3).backfillLastDoneAt(t2);
        backfillService.discovered(workspaceId, List.of(
                found.projectId(first).backfillTracesTotal(10).backfillTracesDone(4).build(),
                found.projectId(third).backfillTracesTotal(7).backfillTracesDone(0).build()), t3, t0.minusSeconds(1));
        var progress = TopicBackfillProgress.builder().workspaceId(workspaceId).projectId(third).written(5).build();
        backfillService.record(List.of(progress, progress));

        assertThat(backfillService.findByKeys(workspaceId, List.of(first, second, third))).isEqualTo(Map.of(
                // Its start and drained mark are kept; the counts and the latest status are the discovery's.
                first, found.projectId(first).backfillStartedAt(t0).backfillDrainedAt(t1).backfillTracesTotal(10)
                        .backfillTracesDone(4).build(),
                // Not found by the discovery: left as it was.
                second, row.projectId(second).backfillStartedAt(t0).backfillDrainedAt(null).build(),
                // New: starts a backfill. 5 + 5 written of 7.
                third, found.projectId(third).backfillStartedAt(t3).backfillDrainedAt(null).backfillTracesTotal(7)
                        .backfillTracesDone(7).build()));
        assertThat(vectorsCheckedAt(workspaceId)).isEqualTo(t3);

        // The tick comes back long after t3: every backfill starts again.
        var t4 = t3.plus(3, ChronoUnit.DAYS);
        backfillService.discovered(workspaceId, List.of(), t4, t4.minus(2, ChronoUnit.DAYS));

        var reset = TopicBackfill.builder().workspaceId(workspaceId).backfillStartedAt(t4);
        assertThat(backfillService.findByKeys(workspaceId, List.of(first, second, third))).isEqualTo(Map.of(
                first, reset.projectId(first).backfillLastDoneAt(t2).build(),
                second, reset.projectId(second).backfillLastDoneAt(t0).build(),
                third, reset.projectId(third).backfillLastDoneAt(t2).build()));
        assertThat(vectorsCheckedAt(workspaceId)).isEqualTo(t4);
    }

    @Test
    @DisplayName("a project row another writer created has no backfill until one starts, and the backfill writes "
            + "leave that writer's columns as they are")
    void backfillOnARowOfAnotherWriter() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        tables.project(workspaceId, projectId);
        // A discovery run created the row: only its own columns are set.
        template.inTransaction(WRITE, handle -> handle.createUpdate("""
                INSERT INTO topic_projects (workspace_id, project_id, saved_runs) VALUES (:workspaceId, :projectId, 2)
                """).bind("workspaceId", workspaceId).bind("projectId", projectId.toString()).execute());
        assertThat(backfillService.findByKeys(workspaceId, List.of(projectId))).isEmpty();
        var t0 = Instant.parse("2026-11-01T10:00:00Z");

        var backfill = TopicBackfill.builder().workspaceId(workspaceId).projectId(projectId).backfillStartedAt(t0)
                .backfillLastDoneAt(t0).backfillTracesTotal(3).build();
        backfillService.start(List.of(backfill));

        assertThat(backfillService.findByKeys(workspaceId, List.of(projectId))).isEqualTo(Map.of(projectId, backfill));
        int savedRuns = template.inTransaction(READ_ONLY, handle -> handle.createQuery("""
                SELECT saved_runs FROM topic_projects WHERE workspace_id = :workspaceId AND project_id = :projectId
                """).bind("workspaceId", workspaceId).bind("projectId", projectId.toString()).mapTo(Integer.class)
                .one());
        assertThat(savedRuns).isEqualTo(2);
    }

    @Test
    @DisplayName("a discovery removes the rows of projects that no longer exist, also one it was about to write again, "
            + "in more than one batch")
    void discoveryRemovesRowsOfDeletedProjects() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var kept = ID_GENERATOR.generateId();
        // More than topics.storage.deleteBatchSize (2 in the tests), so the delete takes two transactions.
        var deleted = List.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId(), ID_GENERATOR.generateId());
        tables.project(workspaceId, kept);
        var t0 = Instant.parse("2026-11-01T10:00:00Z");
        var row = TopicBackfill.builder().workspaceId(workspaceId).backfillStartedAt(t0).backfillLastDoneAt(t0);
        // Left behind by a failed delete on ProjectsDeleted.
        backfillService.start(Stream.concat(Stream.of(kept), deleted.stream())
                .map(projectId -> row.projectId(projectId).build())
                .toList());

        // The first deleted project's statuses are still there, so the discovery finds it again.
        int removed = backfillService.discovered(workspaceId, List.of(row.projectId(kept).backfillTracesTotal(3)
                .build(), row.projectId(deleted.getFirst()).backfillTracesTotal(3).build()), t0.plusSeconds(60), t0);

        assertThat(removed).isEqualTo(deleted.size());
        assertThat(backfillService.findByKeys(workspaceId, Stream.concat(Stream.of(kept), deleted.stream()).toList()))
                .containsOnlyKeys(kept);
    }

    @Test
    @DisplayName("recording a tick's progress counts the rows it updated: 0 for a project whose row is gone")
    void recordBackfillsCountsTheRowsUpdated() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var kept = ID_GENERATOR.generateId();
        var gone = ID_GENERATOR.generateId();
        var t0 = Instant.parse("2026-11-01T10:00:00Z");
        backfillService.start(List.of(TopicBackfill.builder().workspaceId(workspaceId).projectId(kept)
                .backfillStartedAt(t0).backfillLastDoneAt(t0).backfillTracesTotal(5).build()));
        var progress = TopicBackfillProgress.builder().workspaceId(workspaceId).written(1);

        int[] updated = template.inTransaction(WRITE, handle -> handle.attach(TopicProjectDAO.class)
                .recordBackfills(List.of(progress.projectId(kept).build(), progress.projectId(gone).build())));

        assertThat(updated).containsExactly(1, 0);
    }

    private Instant vectorsCheckedAt(String workspaceId) {
        return template.inTransaction(READ_ONLY, handle -> handle.createQuery(
                "SELECT vectors_checked_at FROM topic_workspaces WHERE workspace_id = :workspaceId")
                .bind("workspaceId", workspaceId).mapTo(Instant.class).one());
    }

    private static List<String> codePoints(int... codePoints) {
        return Arrays.stream(codePoints).mapToObj(Character::toString).toList();
    }

    /** {@code match(c, NON_BLANK)} in ClickHouse for each string. */
    private List<Integer> matchNonBlank(List<String> values) {
        return templateAsync.nonTransaction(connection -> Flux
                .from(connection.createStatement("""
                        SELECT match(value, :non_blank) AS matched FROM (SELECT arrayJoin(:values) AS value)
                        """)
                        .bind("non_blank", TopicVectorsDAO.NON_BLANK)
                        .bind("values", values.toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> row.get("matched", Integer.class)))
                .collectList())
                .block();
    }

    private static Map<UUID, Long> tracesByProject(List<BackfillProject> found) {
        return found.stream().collect(Collectors.toMap(BackfillProject::projectId, BackfillProject::traces));
    }

    /** The window read as the tick walks it: slices counted back from {@code start}, the newest open-ended. */
    private List<Candidate> sliced(String workspaceId, UUID projectId, List<UUID> excluded,
            Instant lower, Instant upper, Instant start, Instant createdAfter, Duration slice) {
        var candidates = new ArrayList<Candidate>();
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

    private void assertCandidates(List<Candidate> actual, Candidate... expected) {
        assertThat(actual).usingRecursiveFieldByFieldElementComparatorIgnoringFields("statusUpdatedAt")
                .containsExactlyInAnyOrder(expected);
    }

    /** The candidate of a status {@link #doneStatus} built; {@code statusUpdatedAt} is not known. */
    private Candidate candidate(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        return Candidate.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(traceCreatedAt(traceId))
                .taskVersion(taskVersion)
                .statusUpdatedAt(Instant.EPOCH)
                .summary(summaryOf(traceId, taskVersion))
                .build();
    }

    private List<Candidate> findUnvectored(String workspaceId, Instant lower) {
        return findUnvectored(workspaceId, lower, Instant.EPOCH);
    }

    private List<Candidate> findUnvectored(String workspaceId, Instant lower, Instant createdAfter) {
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
                INSERT INTO topic_vectors (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    embed_model, embed_dimensions, text, vector, is_deleted)
                SELECT workspace_id, project_id, task, trace_id, trace_created_at, task_version, embed_model,
                    embed_dimensions, text, vector, 1
                FROM topic_vectors
                WHERE workspace_id = :workspace_id AND trace_id = :trace_id AND task_version = :task_version
                LIMIT 1
                """, Map.of("workspace_id", workspaceId, "trace_id", traceId.toString(), "task_version", taskVersion));
    }

    private long findUnvectoredReadRows(String workspaceId) {
        return templateAsync.nonTransaction(connection -> Mono
                .from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .then(Mono.from(connection.createStatement("""
                        SELECT sum(read_rows) AS read_rows
                        FROM system.query_log
                        WHERE type = 'QueryFinish' AND log_comment LIKE :log_comment
                        """)
                        .bind("log_comment", "topics:find_unvectored:" + workspaceId + ":%")
                        .execute()))
                .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("read_rows", Long.class)))))
                .block();
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
