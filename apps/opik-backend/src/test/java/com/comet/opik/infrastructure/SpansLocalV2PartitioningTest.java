package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the spans_local_v2 partition design (migration 000115) end to end: the {@code id_at DateTime64(0)
 * MATERIALIZED UUIDv7ToDateTime(toUUID(id))} → {@code PARTITION BY toYYYYMMDD(toDate32(id_at) -
 * toIntervalDay(toDayOfWeek(id_at, 1)))} chain. The key computes the honest Monday of {@code id_at}'s week in
 * {@code Date32}, so it never wraps a far-future id the way a 16-bit {@code toMonday} {@code Date} would. The
 * counterpart of {@link TracesLocalV2PartitioningTest}, pinning the same behaviors as permanent regression guards:
 *
 * <ul>
 *   <li><b>Partition stability across upserts.</b> {@code id_at} is computed by ClickHouse from the immutable
 *   {@code id}, so two versions of the same logical row (differing only in {@code last_updated_at}) must land in one
 *   weekly partition — the property {@code ReplacingMergeTree}'s in-partition dedup depends on. Regresses if the
 *   {@code id_at} expression or the partition key stops deriving from the immutable {@code id}.</li>
 *   <li><b>Pruning with the read predicates.</b> The {@code SpanDAO} read path emits the same {@code Date32}
 *   week-start bounds as the partition key, paired with its id-range, and they prune where the id-range alone does
 *   not (the planner doesn't infer {@code id → id_at} monotonicity through {@code UUIDv7ToDateTime}). Read via
 *   {@code EXPLAIN indexes = 1} across the {@code MinMax} and {@code Partition} entries — see {@link #prunedParts}.</li>
 *   <li><b>Both operands, in every direction.</b> The wrap is reachable through the bound as well as the column, and
 *   how it fails depends on direction: a wrapped lower bound only widens, a wrapped <b>upper</b> bound drops every
 *   ordinary row, and a wrapped equality never matches. All three are pinned below, since a lower-bound case alone
 *   cannot detect a wrapped bound at all (OPIK-8241).</li>
 *   <li><b>Honest far-future isolation.</b> A legitimate row whose UUIDv7 carries a far-future timestamp lands in its
 *   own distinct, honest weekly partition, never mixed with a real recent week.</li>
 *   <li><b>Week-expression correctness.</b> The {@code Date32} Monday equals {@code toMonday} across the in-range
 *   calendar and stays honest where {@code toMonday} wraps — both far-future ids and the epoch week a non-v7 id lands
 *   in — independent of the datetime setting.</li>
 * </ul>
 *
 * <p>Spans add a guard with no traces analogue: a {@code trace_id}-keyed scan must keep every partition. Spans are
 * partitioned on their own id's week, which can be later than their trace's, so pruning such a scan by a week derived
 * from {@code trace_id} would silently drop valid spans (see {@code SpanDAO.DELETE_FOR_RETENTION}).</p>
 *
 * <p>Otherwise this keeps full parity with {@link TracesLocalV2PartitioningTest}, including the point-lookup equality
 * shape that no {@code SpanDAO} path emits today. The two DAOs are close relatives that evolve separately, so the
 * shapes are pinned on both sides rather than only where each is currently reachable.</p>
 *
 * <p>The far-future cases here run against the partitioned successor, where {@code id_at} is honest and the wrap is
 * worst. The counterpart on the legacy table — the default topology of this suite, and of any install that has not
 * cut over — is {@code FindSpansResourceTest.searchSpansStream__whenCursorCarriesAFarFutureTimestamp__*}.</p>
 *
 * <p>Runs directly against ClickHouse via {@link TransactionTemplateAsync} over the test container's connection factory
 * — no Dropwizard app — mirroring the raw column-level access of {@link SpansLocalV2TableTest}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpansLocalV2PartitioningTest {

    /**
     * A fixed historical Monday that weekly ids are minted at week offsets from. Fixed (not {@code now}-derived) so the
     * partition math is deterministic and the seeded weeks never overlap the wall-clock (now-based) rows other suites
     * insert into this reused container — a {@code now}-derived anchor could also drift across a week boundary
     * mid-suite. It is an actual Monday, so {@code toMonday} of it is itself; its exact value is otherwise immaterial,
     * since every assertion here is relative (part counts, distinct-partition counts), never a literal partition name.
     * The table has no TTL, so a far-past anchor is never evicted.
     */
    private static final LocalDate ANCHOR_MONDAY = LocalDate.of(2025, 3, 3);

    /**
     * A timestamp in the band where a 16-bit {@code toMonday} {@code Date} wraps — the ~2201 the litellm bug
     * (BerriAI/litellm#31294) mints, and the neighbourhood of the 2199 ids prod already holds. Shared by the
     * far-future guard and its expected-Monday oracle so the two can never drift apart.
     */
    private static final Instant FAR_FUTURE_INSTANT = Instant.parse("2201-06-01T00:00:00Z");

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);

    private final TransactionTemplateAsync transactionTemplateAsync;

    {
        Startables.deepStart(zookeeperContainer, clickHouseContainer).join();
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        transactionTemplateAsync = TransactionTemplateAsync.create(
                ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouseContainer, DATABASE_NAME).build());
    }

    /**
     * The two versions of one id (an upsert) must occupy exactly one weekly partition — the property
     * {@code ReplacingMergeTree}'s in-partition dedup depends on. Their {@code last_updated_at} values straddle a week
     * boundary on purpose: placement must follow the id-derived {@code id_at} (week 0 for both), so a regression that
     * repartitioned on the version column would split them into two partitions and fail here. Asserts the distinct
     * partition count rather than the physical row count: the count is invariant under merges (a merge only collapses
     * rows within a partition, never moves them across one), so the guard needs no merge coordination.
     */
    @Test
    void bothVersionsOfSameIdLandInOneWeeklyPartition() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId();
        var weekInstant0 = weekInstant(0);
        var id = ID_GENERATOR.generateId(weekInstant0);
        insert(List.of(id), workspaceId, projectId, traceId, weekInstant0);
        insert(List.of(id), workspaceId, projectId, traceId, weekInstant(1));

        var actualDistinctPartitions = distinctPartitionsFor(workspaceId, projectId, id);
        assertThat(actualDistinctPartitions).isEqualTo(1L);
    }

    @Test
    void idRangePredicateAloneDoesNotPrunePartitions() {
        var seed = seedConsecutiveWeeklyPartitions();

        var actualParts = prunedParts("""
                SELECT
                    id
                FROM spans_local_v2
                WHERE workspace_id = :workspace_id
                    AND id >= :id_lo
                    AND id <= :id_hi
                """, statement -> statement
                .bind("workspace_id", seed.workspaceId())
                .bind("id_lo", seed.ids().get(1))
                .bind("id_hi", seed.ids().get(2)));

        // Queries the same inner id range (weeks 1..2 of the four seeded) as
        // idRangeWithWeekStartBoundPrunesPartitions, so the two are a controlled pair whose only difference is the
        // added week-start bound. With no id_at predicate neither the id_at MinMax nor the partition key has anything
        // to constrain (the planner doesn't infer id -> id_at monotonicity through UUIDv7ToDateTime), so every part is
        // read. Should the target LTS start inferring that, this fails — the signal to revisit whether the read path
        // still needs its explicit id_at predicate.
        assertThat(actualParts.selected()).isEqualTo(actualParts.total());
    }

    /**
     * The predicate the SpanDAO read path emits: each id-range bound carries a parallel {@code Date32} week-start
     * bound derived from the same UUIDv7, the same expression on both sides. That makes it the partition key's own
     * expression, which the planner matches directly rather than inferring monotonicity over each part's {@code id_at}
     * {@code MinMax} — see {@link #prunedParts}.
     * <p>
     * Both bounds are pinned independently rather than through a single {@code selected < total}, which one bound
     * working alone would already satisfy. ids 1..2 are the inner two of the four seeded weeks, so week 0 sits below
     * the range and week 3 above: dropping either bound must give back the parts on that side. The comparison is
     * between the three runs, never against an absolute part count — {@code Initial Parts} counts every active part in
     * the container-wide table, which the other tests in this class also write to.
     */
    @Test
    void idRangeWithWeekStartBoundPrunesPartitions() {
        var seed = seedConsecutiveWeeklyPartitions();
        Consumer<Statement> binder = statement -> statement
                .bind("workspace_id", seed.workspaceId())
                .bind("id_lo", seed.ids().get(1))
                .bind("id_hi", seed.ids().get(2));

        var lowerBoundOnly = prunedParts(
                """
                        SELECT
                            id
                        FROM spans_local_v2
                        WHERE workspace_id = :workspace_id
                            AND id >= :id_lo
                            AND id <= :id_hi
                            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                >= (toDate32(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC'), 1)))
                        """,
                binder);
        var upperBoundOnly = prunedParts(
                """
                        SELECT
                            id
                        FROM spans_local_v2
                        WHERE workspace_id = :workspace_id
                            AND id >= :id_lo
                            AND id <= :id_hi
                            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                <= (toDate32(UUIDv7ToDateTime(toUUID(:id_hi), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:id_hi), 'UTC'), 1)))
                        """,
                binder);
        // Run last on purpose: a background merge can only ever collapse parts, so measuring the two-bound query
        // against the most-merged state keeps the inequalities below one-directional.
        var bothBounds = prunedParts(
                """
                        SELECT
                            id
                        FROM spans_local_v2
                        WHERE workspace_id = :workspace_id
                            AND id >= :id_lo
                            AND id <= :id_hi
                            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                >= (toDate32(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC'), 1)))
                            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                <= (toDate32(UUIDv7ToDateTime(toUUID(:id_hi), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:id_hi), 'UTC'), 1)))
                        """,
                binder);

        // Each bound prunes what the other cannot: the week-3 parts only the upper bound excludes, and the week-0
        // parts only the lower one does. Together they also imply pruning happens at all, since a planner that ignored
        // the id_at predicate would select every part in all three runs and fail both comparisons.
        assertThat(bothBounds.selected())
                .isLessThan(lowerBoundOnly.selected())
                .isLessThan(upperBoundOnly.selected());
    }

    /**
     * The equality counterpart of the range bounds above. No {@code SpanDAO} read path emits a week-start equality
     * today — its by-id lookups carry no {@code id_at} predicate at all — but the shape is pinned anyway for parity with
     * {@link TracesLocalV2PartitioningTest}: the two DAOs are close relatives that evolve separately, so if a spans
     * by-id path later grows the equality bound that {@code TraceDAO.SELECT_DETAILS_BY_ID} already has, this is the
     * guard that says whether it prunes.
     */
    @Test
    void idPointLookupWithWeekStartEqualityPrunesPartitions() {
        var seed = seedConsecutiveWeeklyPartitions();

        var actualParts = prunedParts(
                """
                        SELECT
                            id
                        FROM spans_local_v2
                        WHERE workspace_id = :workspace_id
                            AND id = :id
                            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                = (toDate32(UUIDv7ToDateTime(toUUID(:id), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:id), 'UTC'), 1)))
                        """,
                statement -> statement
                        .bind("workspace_id", seed.workspaceId())
                        .bind("id", seed.ids().get(1)));

        // Equality on the week-start expression prunes to the single week id 1 lands in; the other three seeded weeks
        // (and every out-of-window part) fall away, so selected drops below total.
        assertThat(actualParts.selected()).isLessThan(actualParts.total());
    }

    /**
     * The read-path counterpart of {@link #farFutureRowIsolatesIntoItsOwnHonestWeeklyPartition()}, and the regression
     * that shipped for traces (OPIK-7456) and was left on spans until OPIK-8241: honest partitioning is worthless if
     * the read predicate then filters those rows back out. Every {@code id}-range bound in the DAOs carries a parallel
     * week-start bound documented as "a strict consequence of the id-range" — so it must never exclude a row the
     * id-range admits. Under {@code toMonday} it did: a ~2201 id clears {@code id >= :id_lo} but its 16-bit
     * {@code Date} wraps to a ~2021 Monday and fails the week bound, so the row vanishes from a result it belongs in.
     * Seeds a present-day span and a far-future span, applies the exact predicate the read path emits with the
     * present-day id as the lower bound, and asserts BOTH come back.
     */
    @Test
    void weekStartLowerBoundKeepsFarFutureRowsThatTheIdRangeAdmits() {
        var seed = seedPresentAndFarFuture();

        var returnedIds = idsMatching(
                """
                        SELECT id
                        FROM spans_local_v2
                        WHERE workspace_id = :workspace_id
                        AND id >= :id_lo
                        AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                            >= (toDate32(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:id_lo), 'UTC'), 1)))
                        """,
                seed.workspaceId(), statement -> statement.bind("id_lo", seed.present()));

        assertThat(returnedIds).containsExactlyInAnyOrder(seed.present().toString(), seed.farFuture().toString());
    }

    /**
     * The mirror of {@link #weekStartLowerBoundKeepsFarFutureRowsThatTheIdRangeAdmits()}, and the direction where a
     * wrapped bound is catastrophic rather than merely imprecise: on an <b>upper</b> bound it does not lose the
     * far-future row, it loses every <em>ordinary</em> row.
     *
     * <p>{@code :last_received_span_id} is a pagination cursor lifted from a row the previous page returned, so it is
     * a real span id and can itself be far-future — the far-future spans sort first under {@code ORDER BY id DESC},
     * which is exactly when it happens. With {@code toMonday} on the bound side that cursor wraps to a past week, and
     * every ordinary row — whose honest week is later — fails {@code <=}. The page comes back empty and pagination
     * stops dead. Seeds both rows, pages with the far-future id as the cursor, and asserts the present-day row still
     * returns.
     */
    @Test
    void weekStartUpperBoundKeepsOrdinaryRowsWhenTheCursorIsFarFuture() {
        var seed = seedPresentAndFarFuture();

        var returnedIds = idsMatching(
                """
                        SELECT id
                        FROM spans_local_v2
                        WHERE workspace_id = :workspace_id
                        AND id < :last_received_span_id
                        AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                            <= (toDate32(UUIDv7ToDateTime(toUUID(:last_received_span_id), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:last_received_span_id), 'UTC'), 1)))
                        """,
                seed.workspaceId(), statement -> statement.bind("last_received_span_id", seed.farFuture()));

        assertThat(returnedIds).containsExactly(seed.present().toString());
    }

    /**
     * The bound side of a week bound has to be Date32 too, and an equality is where mixing the forms fails hardest:
     * it holds only if both sides agree for every id, so a {@code toMonday} bound against the honest column never
     * matches a far-future row at all.
     *
     * <p>Only converting the <em>column</em> is what this catches, and it is the trap a single-operand check falls
     * into: with {@code toMonday} on both sides the equality still holds for a far-future id, because both operands
     * wrap into the same wrong week and agree there. It fails only once the column is honest and the bound is not —
     * which is why the column-side fix alone leaves the wrap reachable.
     *
     * <p>No {@code SpanDAO} path emits this shape today, for the reason given on
     * {@link #idPointLookupWithWeekStartEqualityPrunesPartitions()}. Asserts that the far-future id <em>resolves</em>,
     * not merely that it is not lost: an equality matching nothing is indistinguishable from an absent row.
     */
    @Test
    void weekStartEqualityResolvesAFarFutureId() {
        var seed = seedPresentAndFarFuture();

        var returnedIds = idsMatching(
                """
                        SELECT id
                        FROM spans_local_v2
                        WHERE workspace_id = :workspace_id
                        AND id = :id
                        AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                            = (toDate32(UUIDv7ToDateTime(toUUID(:id), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:id), 'UTC'), 1)))
                        """,
                seed.workspaceId(), statement -> statement.bind("id", seed.farFuture()));

        assertThat(returnedIds).containsExactly(seed.farFuture().toString());
    }

    /**
     * The partition key wraps the honest {@code Date32} week in {@code toYYYYMMDD}, so it resolves to {@code UInt32}: the
     * partition id stays a human-readable {@code YYYYMMDD} (e.g. 20250303), legible in ZooKeeper paths, part directory
     * names and system.parts, rather than the opaque days-since-epoch integer a bare {@code Date32} key would produce.
     * Pinning the type makes a revert to that bare key fail here.
     */
    @Test
    void partitionKeyResolvesToUInt32ForReadableIds() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId();
        var id = ID_GENERATOR.generateId(weekInstant(0));
        insert(List.of(id), workspaceId, projectId, traceId, weekInstant(0));

        assertThat(partitionKeyTypeFor(workspaceId, projectId, id)).isEqualTo("Tuple(UInt32)");
    }

    /**
     * The correctness guarantee for legitimate rows whose UUIDv7 carries a far-future timestamp (the litellm ~2201 bug,
     * and the 2199 ids prod already holds): they must occupy their own honest weekly partition, never mixed into a real
     * recent week — otherwise a per-week {@code DROP PARTITION} / retention / tiering operation on that real week would
     * also touch these rows, and vice versa. Seeds a present-day span and a ~2201 span under one (workspace, project)
     * and asserts they land in different partitions, the far-future one in its honest ~2201 week rather than the ~2021
     * week into which a 16-bit {@code toMonday} would wrap it.
     * <p>
     * The far-future partition is asserted as the <em>exact</em> Monday from a Java oracle, not merely as falling in
     * 2201: a year-only check would pass a row that landed in the wrong week of the right year. That closes the gap
     * between {@link #honestWeekExpressionStaysHonestWhereToMondayWraps}, which pins the expression standalone, and
     * what ClickHouse actually assigns to a stored row through the materialized {@code id_at}.
     */
    @Test
    void farFutureRowIsolatesIntoItsOwnHonestWeeklyPartition() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId();
        var presentId = ID_GENERATOR.generateId(weekInstant(0));
        var farFutureId = ID_GENERATOR.generateId(FAR_FUTURE_INSTANT);
        insert(List.of(presentId, farFutureId), workspaceId, projectId, traceId, weekInstant(0));

        var presentPartition = partitionIdFor(workspaceId, projectId, presentId);
        var farFuturePartition = partitionIdFor(workspaceId, projectId, farFutureId);

        var expectedMonday = FAR_FUTURE_INSTANT.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(farFuturePartition)
                .isNotEqualTo(presentPartition)
                .isEqualTo(expectedMonday.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    /**
     * Pins the far-future-safe weekly-Monday expression {@code toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))}
     * (OPIK_7456). {@code toMonday} returns a 16-bit {@code Date} that wraps past year 2149, so a legitimate row whose
     * UUIDv7 carries a far-future timestamp partitions into a plausible recent week and mixes with real data. The
     * {@code Date32} expression computes the same Monday as {@code toMonday} across the normal range without ever
     * wrapping. Asserts the equivalence day-by-day across a full week and across a year boundary.
     */
    @ParameterizedTest(name = "honest week == toMonday for {0}")
    @ValueSource(strings = {
            "2025-03-03", "2025-03-04", "2025-03-05", "2025-03-06", "2025-03-07", "2025-03-08", "2025-03-09",
            "2024-12-30", "2024-12-31", "2025-01-01", "2025-01-05"})
    void honestWeekExpressionMatchesToMondayInRange(String date) {
        assertThat(weekProbe(date, "toMonday(d) = hw")).isEqualTo(1L);
    }

    /**
     * Where {@code toMonday}'s 16-bit {@code Date} wraps, the {@code Date32} expression stays honest — at both extremes:
     * far-future ids (litellm ~2201) that {@code toMonday} folds into a recent week, and the epoch week it underflows to
     * ~2149, reachable by any non-v7 {@code id} (a v4 or nil UUID), for which {@code UUIDv7ToDateTime} returns
     * {@code 1970-01-01}. Asserts the <em>exact</em> expected Monday as {@code YYYYMMDD} against a Java oracle
     * ({@code toMonday} can't be the oracle — it wraps), pinning both ends of the {@code Date32} window and catching an
     * off-by-one-week regression that would still land on some Monday in the right year.
     */
    @ParameterizedTest(name = "honest week is the exact Monday of {0}''s week")
    @ValueSource(strings = {"1970-01-01", "2160-06-01", "2201-06-01", "2250-06-01", "2298-06-01"})
    void honestWeekExpressionStaysHonestWhereToMondayWraps(String date) {
        var expectedMonday = LocalDate.parse(date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertThat(weekProbe(date, "toYYYYMMDD(hw)"))
                .isEqualTo(Long.parseLong(expectedMonday.format(DateTimeFormatter.BASIC_ISO_DATE)));
    }

    /**
     * Spans-only guard. A scan keyed on {@code trace_id} alone — the shape {@code SpanDAO}'s retention sweep and its
     * spans-of-one-trace reads emit — must keep every partition, because a span's week is its own id's week and can be
     * later than its trace's. The seed makes that concrete: the four spans span four weeks under one week-0 trace, so
     * any pruning derived from the trace's week would drop three of them.
     */
    @Test
    void traceIdPredicateAloneRetainsAllPartitions() {
        var seed = seedConsecutiveWeeklyPartitions();

        var actualParts = prunedParts("""
                SELECT
                    id
                FROM spans_local_v2
                WHERE workspace_id = :workspace_id
                    AND trace_id = :trace_id
                """, statement -> statement
                .bind("workspace_id", seed.workspaceId())
                .bind("trace_id", seed.traceId()));

        assertThat(actualParts.selected()).isEqualTo(actualParts.total());
    }

    /**
     * The correctness consequence of the guard above, asserted on rows rather than on parts: reading a trace's spans
     * must return the ones whose id week runs ahead of the trace's. Fails if a future partition-pruning predicate is
     * derived from {@code trace_id}, which would silently drop them.
     */
    @Test
    void traceIdPredicateAloneReturnsSpansFromWeeksAfterTheirTrace() {
        var seed = seedConsecutiveWeeklyPartitions();

        var actualIds = idsForTrace(seed.workspaceId(), seed.traceId());

        assertThat(actualIds).containsExactlyInAnyOrderElementsOf(seed.ids());
    }

    /**
     * Runs the given {@code SELECT id} and returns the ids it yields, binding {@code workspace_id} for the caller.
     * Takes the whole statement rather than a fragment, like {@link #prunedParts}, so each read-path case shows the
     * query it pins in full.
     */
    private List<String> idsMatching(String selectSql, String workspaceId, Consumer<Statement> binder) {
        return transactionTemplateAsync.stream(connection -> {
            var statement = connection.createStatement(selectSql);
            statement.bind("workspace_id", workspaceId);
            binder.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, ignored) -> row.get("id", String.class)));
        }).collectList().block();
    }

    /**
     * Seeds one present-day span and one far-future span under a fresh workspace — the fixture every read-path case
     * shares. Each case then differs only in the predicate it applies and what it expects back, which is the whole of
     * what distinguishes them.
     */
    private FarFutureSeed seedPresentAndFarFuture() {
        var workspaceId = UUID.randomUUID().toString();
        var present = ID_GENERATOR.generateId(weekInstant(0));
        var farFuture = ID_GENERATOR.generateId(FAR_FUTURE_INSTANT);
        insert(List.of(present, farFuture), workspaceId, ID_GENERATOR.generateId(), ID_GENERATOR.generateId(),
                weekInstant(0));
        return FarFutureSeed.builder().workspaceId(workspaceId).present(present).farFuture(farFuture).build();
    }

    /**
     * Seeds four consecutive weekly partitions in one INSERT: the four ids fall in four distinct weeks, so ClickHouse
     * writes one part per partition. All four share one {@code trace_id} minted before the earliest of them, the
     * layout the trace_id-keyed guards read. Returns the ids so the reads target the same rows.
     */
    private Seed seedConsecutiveWeeklyPartitions() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId(weekInstant(0));
        var ids = List.of(
                ID_GENERATOR.generateId(weekInstant(0)),
                ID_GENERATOR.generateId(weekInstant(1)),
                ID_GENERATOR.generateId(weekInstant(2)),
                ID_GENERATOR.generateId(weekInstant(3)));
        insert(ids, workspaceId, projectId, traceId, Instant.now());
        return Seed.builder().workspaceId(workspaceId).projectId(projectId).traceId(traceId).ids(ids).build();
    }

    /**
     * Multi-row batch insert following the SpanDAO idiom: the {@code <items>} StringTemplate expands one tuple per row
     * (id bound per row; workspace/project/trace/last_updated_at shared across the batch), and only the columns the
     * tests exercise are bound — the rest take their DDL defaults.
     */
    private void insert(List<UUID> ids, String workspaceId, UUID projectId, UUID traceId, Instant lastUpdatedAt) {
        var sql = TemplateUtils.getBatchSql("""
                INSERT INTO spans_local_v2 (
                    id,
                    workspace_id,
                    project_id,
                    trace_id,
                    last_updated_at
                )
                FORMAT Values
                    <items:{item |
                        (
                            :id<item.index>,
                            :workspace_id,
                            :project_id,
                            :trace_id,
                            :last_updated_at
                        )
                        <if(item.hasNext)>,<endif>
                    }>
                ;
                """, ids.size()).render();
        transactionTemplateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("project_id", projectId)
                    .bind("trace_id", traceId)
                    .bind("last_updated_at", ClickHouseDateTimeFormat.formatMicros(lastUpdatedAt));
            for (int index = 0; index < ids.size(); index++) {
                statement.bind("id" + index, ids.get(index));
            }
            return Mono.from(statement.execute());
        }).block();
    }

    private long distinctPartitionsFor(String workspaceId, UUID projectId, UUID id) {
        return transactionTemplateAsync.nonTransaction(connection -> Mono.from(connection.createStatement("""
                SELECT
                    uniqExact(_partition_id) AS distinct_partitions
                FROM spans_local_v2
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("id", id)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("distinct_partitions", Long.class)))))
                .block();
    }

    private List<UUID> idsForTrace(String workspaceId, UUID traceId) {
        return transactionTemplateAsync.stream(connection -> Flux.from(connection.createStatement("""
                SELECT
                    id
                FROM spans_local_v2
                WHERE workspace_id = :workspace_id
                    AND trace_id = :trace_id
                """)
                .bind("workspace_id", workspaceId)
                .bind("trace_id", traceId)
                .execute())
                .flatMap(result -> result.map((row, ignored) -> row.get("id", UUID.class))))
                .collectList()
                .block();
    }

    private String partitionKeyTypeFor(String workspaceId, UUID projectId, UUID id) {
        return partitionInfoFor(workspaceId, projectId, id).getRight();
    }

    private String partitionIdFor(String workspaceId, UUID projectId, UUID id) {
        return partitionInfoFor(workspaceId, projectId, id).getLeft();
    }

    private Pair<String, String> partitionInfoFor(String workspaceId, UUID projectId, UUID id) {
        return transactionTemplateAsync.nonTransaction(connection -> Mono.from(connection.createStatement("""
                SELECT
                    _partition_id AS partition_id,
                    toTypeName(_partition_value) AS key_type
                FROM spans_local_v2
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id = :id
                LIMIT 1
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("id", id)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> Pair.of(
                        row.get("partition_id", String.class), row.get("key_type", String.class))))))
                .block();
    }

    /**
     * Evaluates {@code expr} against a single date bound to {@code d} (a {@code DateTime64} at noon UTC, as {@code id_at}
     * is), with {@code hw} pre-bound to the honest weekly-Monday expression. The date is a value, so it is a bind
     * parameter; {@code expr} is a SQL fragment the test supplies (a bind can't stand in for a fragment), so it is
     * interpolated. Returns the scalar as a long ({@code toInt64} normalizes booleans/dates for a uniform read).
     */
    private long weekProbe(String date, String expr) {
        return transactionTemplateAsync.nonTransaction(connection -> Mono.from(connection.createStatement("""
                WITH toDateTime64(:date, 0, 'UTC') AS d,
                     toDate32(d) - toIntervalDay(toDayOfWeek(d, 1)) AS hw
                SELECT toInt64(%s) AS v
                """.formatted(expr))
                .bind("date", date + " 12:00:00")
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("v", Long.class)))))
                .block();
    }

    /**
     * Runs {@code EXPLAIN indexes = 1, json = 1} for the query and reports part-level pruning: {@code total} is every
     * active part in the (reused) table, {@code selected} is what survives partition analysis.
     * <p>
     * Reads the {@code MinMax} and {@code Partition} entries together, because which of the two carries the condition
     * is a planner decision, not a property of the query. ClickHouse applies them in that order, each narrowing the
     * previous one's selection, so {@code total} is {@code MinMax}'s initial count and {@code selected} is whichever
     * of the two ran last.
     * <p>
     * Reading both matters because the entry moved with OPIK-8241's fix: a {@code toMonday} bound cannot match the
     * partition key as a whole, so ClickHouse inferred monotonicity of the left expression over each part's
     * {@code id_at} {@code MinMax}; deriving both sides the same way makes the predicate the key's own expression,
     * which it matches directly. Net pruning is unchanged — the same parts and granules survive either way — but a
     * reader of {@code MinMax} alone would report the converted predicate as pruning nothing.
     * <p>
     * The table also carries four minmax skip indexes (on {@code id}, {@code id_at}, {@code created_at} and
     * {@code last_updated_at}), but EXPLAIN reports those under type {@code Skip}. These queries read one table
     * through one scan, so exactly one {@code MinMax} entry must appear; asserting that — rather than taking the first
     * match — keeps the guards from silently reading some other entry's part counts if a future ClickHouse version
     * changes how the block is reported.
     */
    private PrunedParts prunedParts(String selectSql, Consumer<Statement> binder) {
        var explainRows = transactionTemplateAsync.stream(connection -> {
            var statement = connection.createStatement("EXPLAIN indexes = 1, json = 1 %s".formatted(selectSql));
            binder.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, ignored) -> row.get("explain", String.class)));
        }).collectList().block();

        var explain = String.join("\n", explainRows);
        var entries = JsonUtils.getJsonNodeFromString(explain).findValues("Indexes").stream()
                .flatMap(JsonNode::valueStream)
                .toList();

        var minMax = entriesOfType(entries, "MinMax");
        var partition = entriesOfType(entries, "Partition");
        assertThat(minMax).as("MinMax index entries in EXPLAIN output:%n%s", explain).hasSize(1);
        assertThat(partition).as("Partition index entries in EXPLAIN output:%n%s", explain).hasSizeLessThan(2);

        var minMaxParts = JsonUtils.treeToValue(minMax.getFirst(), PrunedParts.class);
        return partition.isEmpty()
                ? minMaxParts
                : PrunedParts.builder()
                        .selected(JsonUtils.treeToValue(partition.getFirst(), PrunedParts.class).selected())
                        .total(minMaxParts.total())
                        .build();
    }

    private List<JsonNode> entriesOfType(List<JsonNode> entries, String type) {
        return entries.stream().filter(entry -> type.equals(entry.path("Type").asText())).toList();
    }

    private Instant weekInstant(int weekOffset) {
        return ANCHOR_MONDAY.plusWeeks(weekOffset).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    @Builder(toBuilder = true)
    private record Seed(String workspaceId, UUID projectId, UUID traceId, List<UUID> ids) {
    }

    /** Built through the builder, not positionally: the two ids are both {@code UUID} and swapping them would invert
     * every case below without a compile error. */
    @Builder(toBuilder = true)
    private record FarFutureSeed(String workspaceId, UUID present, UUID farFuture) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrunedParts(
            @JsonProperty("Selected Parts") int selected,
            @JsonProperty("Initial Parts") int total) {
    }
}
