package com.comet.opik.infrastructure;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.template.TemplateUtils;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end validation of the cutover that migrates {@code spans} to its partitioned, sharding-ready successor
 * {@code spans_local_v2}. It rehearses the sequence against a fresh ClickHouse in raw SQL — the same steps an operator
 * runs from the {@code data-migrations/spans-local-v2-cutover} runbook — and pins the properties the cutover's
 * correctness depends on.
 *
 * <p><b>Why this is a separate suite from {@code TracesLocalV2CutoverTest} rather than a parameterisation of it.</b>
 * The two cutovers share a shape and not their statements. Five differences are structural rather than cosmetic, and
 * each of them is a place where a generalised suite would have had to carry a conditional inside the assertion that
 * decides whether data was lost:
 *
 * <ul>
 * <li><b>The dedup keys differ.</b> {@code spans} orders by
 * {@code (workspace_id, project_id, trace_id, parent_span_id, id)}; the successor drops {@code parent_span_id}
 * (migration 000115). So the source can hold TWO live rows for one span where the destination holds one, and every
 * fidelity comparison has to reduce the old-schema side a second time — {@code FINAL}, then
 * {@code argMax(<fingerprint>, last_updated_at) GROUP BY} the destination key — while the new-schema side needs
 * {@code FINAL} alone. {@link #twoParentsOnTheSourceCollapseToOneOnTheDestinationAndTheCompareAgrees()} is the
 * property; {@link #sameSpanUnderTwoParentsAtOneVersionIsAVersionTie()} is the case it cannot decide.</li>
 * <li><b>{@code parent_span_id} is {@code String} on the source and {@code FixedString(36)} on the destination, and
 * the source column can hold a value the destination cannot.</b> {@code SpanDAO}'s {@code PARTIAL_INSERT} writes
 * {@code leftPad('', 40, '*')} — forty characters — when a span's parent changes. The copy maps anything that is not
 * exactly 36 bytes to the empty (root) sentinel;
 * {@link #parentPoisonValueIsNormalizedAndAnUnguardedCopyThrows()} proves both halves, including that an unguarded
 * projection aborts the whole statement.</li>
 * <li><b>{@code usage} widens {@code Int32 → Int64} forward and NARROWS backward</b> — the only narrowing anywhere in
 * the procedure, and one ClickHouse performs silently.
 * {@link #usageRoundTripsAndTheReverseRangeCheckCatchesAnOverflow()} covers both directions.</li>
 * <li><b>Spans have no standalone delete.</b> Every span delete is the cascade of a trace delete
 * ({@code SpanService.deleteByTraceIds}), captured with {@code source_table = 'spans'} and reason {@code CASCADE}. The
 * bridge records no {@code trace_id}, so the replay's key is not a primary-key prefix here — it prunes on
 * {@code (workspace_id, project_id)} and the {@code id} skip indexes 000115 added.</li>
 * <li><b>One {@code created_at} window reaches destination partitions spread across centuries</b>, because far-future
 * ids (litellm) and non-v7 ids (the epoch week) both ride in it.
 * {@link #oneWindowCopiesEveryPartitionItsIdsReach()} pins that the single backfill statement copies them all, which
 * is what {@code max_partitions_per_insert_block} has to permit; and
 * {@link #aWrappedSourceIdAtDoesNotFollowTheRowToTheDestination()} pins that the destination partitions by the
 * {@code id}'s honest timestamp rather than inheriting the source's wrapping 32-bit {@code id_at}.</li>
 * </ul>
 *
 * <p><b>Inline SQL, by design.</b> This gate reimplements the cutover statements inline rather than executing the
 * reference {@code .sql} files the drivers ship, so it can interleave seeding, per-step assertions, and the negative
 * controls below — the same deliberate choice {@code TracesLocalV2CutoverTest} documents. It is an independent
 * validation of the cutover <i>logic</i>, not the single-source path: the driver scripts read the single-source
 * reference SQL, and the shipped SQL itself is exercised end-to-end by running those drivers against a prod clone in
 * the QA gate. The inline statements here are kept aligned with the reference SQL — identical functions, precision,
 * guards and {@code 'UTC'} pinning — so this gate and the shipped SQL stay in step.
 *
 * <p>Nothing here reads a reference file, deliberately. The one thing it does pin to the live database is the column
 * list ({@link #cutoverCopiesEveryBaseColumn()}), because a base column silently left uncopied is data loss rather
 * than drift — and the reference files carry that same list, so a failure there is the prompt to update them too.
 *
 * <p><b>Deletions must survive the swap.</b> A lightweight {@code DELETE} flips a hidden row mask; it does not bump
 * {@code last_updated_at} (the {@code ReplacingMergeTree} version column), so the version-based delta-insert is blind
 * to deletes that land while the table is being copied. The deletion-events bridge closes this: the trace-delete
 * cascade records every removed span id, and the cutover replays those keys against the destination before the swap.
 * The suite exercises rows deleted before the backfill (excluded by {@code apply_deleted_mask = 1}, never copied) and
 * rows deleted during it (asserted to leak without the replay — the negative control that proves the bridge is
 * load-bearing — and to be masked with it), plus full-key replay across a reused id and the resurrection guard.
 *
 * <p><b>Writes must survive the swap too (OPIK-8238).</b> Reconciliation is deliberately POST-swap, because a pre-swap
 * sweep cannot converge against a live source while a post-swap one converges against a frozen table by construction.
 * The suite covers the forward sweep with a negative control proving the step is load-bearing, the exclusion that stops
 * a post-swap delete being resurrected, and the four-count postcondition's classification on the destination's full
 * key.
 *
 * <p><b>Dedicated, non-reused containers</b> are required because the cutover ends in a destructive {@code EXCHANGE} +
 * {@code RENAME} of the live {@code spans} table, which must never touch a container shared with other suites.
 *
 * <p><b>Scope: this gate validates the cutover SQL logic, not the driver scripts.</b> The safety guards in the
 * runbook's bash drivers — {@code backfill.sh}'s per-window ordinary-cap derivation and headroom gate,
 * {@code rollback.sh}'s wrong-stage topology assertions, the replication-settle gate, {@code reconcile.sh}'s direction
 * detection and usage-range refusal — are exercised by the rehearsal, not by this test. What this suite covers of the
 * reconciliation is every statement those drivers issue, and its postcondition's four counts.
 *
 * <p><b>The {@code Distributed} wrap is covered here as DDL, and its product-side behaviour is covered
 * elsewhere.</b> OPIK-7799 shipped {@code spansDistributedWrapEnabled} and the {@code SpanDAO} routing, so the wrap is
 * reachable; {@code SpansDistributedWrapMutationTest} and {@code SpansUnwrappedMutationTest} prove the mutation paths
 * on both sides of the flag. What this suite adds is the cutover's half — that the gapless wrap's multi-target
 * {@code RENAME} produces the topology that flag expects, and that {@code --unwrap-only} reverses it with post-wrap
 * writes intact. The runbook defers the wrap all the same, but no longer for want of a probe: OPIK-8376 shipped
 * {@code ClickHouseSpansTopologyHealthCheck}, which fails readiness on a mismatch in either direction (see
 * {@code ClickHouseSpansTopologyHealthCheckTest} and {@code ClickHouseSpansTopologyReadinessTest}). That probe is
 * what covers the window, because a mismatch is NOT fail-loud at the point of use: spans have no standalone delete,
 * so the cascade runs in the asynchronous {@code TraceDeletedListener} and the trace delete has already returned 204
 * by the time the span delete fails. The user is told it succeeded and the spans are still live.
 *
 * <p>Run it with: {@code mvn -o test -Dtest=SpansLocalV2CutoverTest} from {@code apps/opik-backend}.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpansLocalV2CutoverTest {

    /**
     * A fixed historical Monday the seeded rows are minted at week offsets from, so the backfill can slice the source
     * by whole {@code created_at} weeks deterministically. Far in the past and never {@code now}-derived, so nothing
     * drifts across a week boundary mid-run.
     */
    private static final LocalDate ANCHOR_MONDAY = LocalDate.of(2025, 3, 3);

    /**
     * A client-backdated version stamp, well before {@code backfill_start}. A row written during the window carrying
     * this as its {@code last_updated_at} can only be caught by the delta's {@code created_at} arm.
     */
    private static final Instant BACKDATED = LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    /**
     * The far-future instant the litellm UUIDv7 bug mints (BerriAI/litellm#31294, ~2201). Fixed rather than
     * {@code now}-derived so it never lands on Feb 29 and cannot drift.
     */
    private static final Instant FAR_FUTURE = LocalDate.of(2201, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    /** Rows spread across three consecutive weekly partitions, so the backfill runs as three weekly batches. */
    private static final int SEED_WEEKS = 3;
    private static final int SURVIVORS_PER_WEEK = 30;
    private static final int PRE_EXISTING_DELETED_PER_WEEK = 10;
    private static final int CASCADE_DELETED_PER_WEEK = 40;
    private static final int DELTA_UPSERTS = 12;
    private static final int DELTA_LATE_CREATED = 8;

    private static final String[] FIDELITY_SOURCES = {"sdk", "experiment", "playground", "optimization", "evaluator"};
    private static final String[] FIDELITY_TYPES = {"general", "tool", "llm", "guardrail"};
    private static final String[] FIDELITY_ENVIRONMENTS = {"production", "staging", "dev", ""};

    /**
     * What {@code SpanDAO}'s {@code PARTIAL_INSERT} writes into {@code parent_span_id} when a span's parent changes:
     * {@code leftPad('', 40, '*')}. FORTY characters — storable in the source's {@code String} column and NOT in the
     * destination's {@code FixedString(36)}.
     */
    private static final String PARENT_POISON = "*".repeat(40);

    /**
     * The stored (non-materialized) columns the cutover copies, one per line. Both INSERT clauses are built from this
     * list, and {@link #cutoverCopiesEveryBaseColumn()} asserts it equals the live base columns of {@code spans} — so a
     * base column added by a future migration cannot be silently left uncopied. A new column here without a matching
     * SELECT entry fails arity at run.
     */
    private static final String COPIED_COLUMNS = """
            id,
            workspace_id,
            project_id,
            trace_id,
            parent_span_id,
            name,
            type,
            start_time,
            end_time,
            input,
            output,
            metadata,
            tags,
            usage,
            created_at,
            last_updated_at,
            created_by,
            last_updated_by,
            model,
            provider,
            total_estimated_cost,
            total_estimated_cost_version,
            error_info,
            truncation_threshold,
            input_slim,
            output_slim,
            ttft,
            source,
            environment""";

    /**
     * The SELECT projection the backfill, the delta and the forward sweep share: the {@link #COPIED_COLUMNS} columns,
     * with the three spans-specific transformations the runbook's 000001 header sets out — the two denullified columns
     * coalesced to their sentinels (end_time → epoch, ttft → NaN), and {@code parent_span_id} guarded on length.
     *
     * <p>The guard is not defensive programming: without it a stored {@link #PARENT_POISON} value aborts the whole
     * window with {@code TOO_LARGE_STRING_SIZE}, which
     * {@link #parentPoisonValueIsNormalizedAndAnUnguardedCopyThrows()} demonstrates directly.
     */
    private static final String COPIED_SELECT = """
            id,
            workspace_id,
            project_id,
            trace_id,
            if(length(parent_span_id) = 36, toFixedString(parent_span_id, 36), toFixedString('', 36)) AS parent_span_id,
            name,
            type,
            start_time,
            coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) AS end_time,
            input,
            output,
            metadata,
            tags,
            usage,
            created_at,
            last_updated_at,
            created_by,
            last_updated_by,
            model,
            provider,
            total_estimated_cost,
            total_estimated_cost_version,
            error_info,
            truncation_threshold,
            input_slim,
            output_slim,
            coalesce(ttft, toFloat64('nan')) AS ttft,
            source,
            environment""";

    /**
     * The {@code usage} arm of the per-row hash {@link #rowHash} builds, shared by both shapes. A {@code Map} has no
     * guaranteed key order, so it is encoded as sorted {@code key \x1e value} pairs joined on {@code \x1f} — two
     * separators, for the reason {@code tags} needs one: without the inner one a key/value boundary shift would hash
     * identically.
     *
     * <p>One constant rather than an entry in each override map, because {@code toString} of an {@code Int32} and of
     * the {@code Int64} it widens to produce the same text, so a single expression canonicalizes both shapes — and a
     * sorted-vs-unsorted or differently-separated copy on one side would make every row with a non-empty
     * {@code usage} look like a mismatch.
     */
    private static final String USAGE_HASH_ARM = "arrayStringConcat(arrayMap(k -> concat(k, '\\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\\x1f')";

    /**
     * Source-shape normalization for the per-row hash, which {@link #rowHash} builds from {@link #COPIED_COLUMNS} so
     * every copied column is covered by construction: a column added there (and {@link #cutoverCopiesEveryBaseColumn()}
     * pins that list to the live schema) is hashed automatically and can never be silently left value-unverified. A
     * column with no entry here hashes as-is.
     *
     * <p>Four arms have no traces counterpart, each because the copy transforms the column:
     * <ul>
     * <li>{@code parent_span_id} — applies the SAME length guard the projection does, so a poison value copied as the
     * root sentinel compares equal rather than reporting a mismatch.</li>
     * <li>{@code usage} — see {@link #USAGE_HASH_ARM}.</li>
     * <li>{@code trace_id} and {@code type} — FixedString / Enum8, normalized via {@code toString} exactly as
     * {@code project_id} and {@code source} are.</li>
     * </ul>
     */
    private static final Map<String, String> OLD_HASH_OVERRIDES = Map.ofEntries(
            Map.entry("project_id", "toString(project_id)"),
            Map.entry("trace_id", "toString(trace_id)"),
            Map.entry("parent_span_id", "if(length(parent_span_id) = 36, parent_span_id, '')"),
            Map.entry("type", "toString(type)"),
            Map.entry("start_time", "toUnixTimestamp64Micro(toDateTime64(start_time, 6))"),
            Map.entry("end_time", "coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0))"),
            Map.entry("created_at", "toUnixTimestamp64Micro(toDateTime64(created_at, 6))"),
            Map.entry("last_updated_at", "toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6))"),
            Map.entry("tags", "arrayStringConcat(tags, '\\x1f')"),
            Map.entry("usage", USAGE_HASH_ARM),
            Map.entry("model", "toString(model)"),
            Map.entry("provider", "toString(provider)"),
            Map.entry("total_estimated_cost", "toString(total_estimated_cost)"),
            Map.entry("total_estimated_cost_version", "toString(total_estimated_cost_version)"),
            Map.entry("ttft", "if(ttft IS NULL, 'nan', toString(ttft))"),
            Map.entry("source", "toString(source)"),
            Map.entry("environment", "toString(environment)"));

    /**
     * Destination-shape normalization, the mirror of {@link #OLD_HASH_OVERRIDES}: the sentinels are read back as the
     * source's absent values ({@code ttft} NaN → {@code 'nan'}), the timestamps are already microsecond, and
     * {@code CAST(parent_span_id AS String)} trims the {@code FixedString(36)} NUL padding back to {@code ''}.
     */
    private static final Map<String, String> NEW_HASH_OVERRIDES = Map.ofEntries(
            Map.entry("project_id", "toString(project_id)"),
            Map.entry("trace_id", "toString(trace_id)"),
            Map.entry("parent_span_id", "CAST(parent_span_id AS String)"),
            Map.entry("type", "toString(type)"),
            Map.entry("start_time", "toUnixTimestamp64Micro(start_time)"),
            Map.entry("end_time", "toUnixTimestamp64Micro(end_time)"),
            Map.entry("created_at", "toUnixTimestamp64Micro(created_at)"),
            Map.entry("last_updated_at", "toUnixTimestamp64Micro(last_updated_at)"),
            Map.entry("tags", "arrayStringConcat(tags, '\\x1f')"),
            Map.entry("usage", USAGE_HASH_ARM),
            Map.entry("model", "toString(model)"),
            Map.entry("provider", "toString(provider)"),
            Map.entry("total_estimated_cost", "toString(total_estimated_cost)"),
            Map.entry("total_estimated_cost_version", "toString(total_estimated_cost_version)"),
            Map.entry("ttft", "if(isNaN(ttft), 'nan', toString(ttft))"),
            Map.entry("source", "toString(source)"),
            Map.entry("environment", "toString(environment)"));

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer);

    private final TransactionTemplateAsync template;

    {
        Startables.deepStart(zookeeperContainer, clickHouseContainer).join();
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        template = TransactionTemplateAsync.create(
                ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouseContainer, DATABASE_NAME).build());
    }

    /**
     * Dedicated (non-reused) containers, so tear them down explicitly rather than relying only on the Ryuk reaper —
     * keeps reruns and a shared JVM from accumulating stopped-but-lingering resources. {@code PER_CLASS} lets this be
     * non-static.
     */
    @AfterAll
    void stopContainers() {
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /**
     * Restore the canonical baseline (spans = original schema, spans_local_v2 = successor schema, both empty; no stray
     * wrap/rename artifacts) before every test, independent of what the previous test left behind. A green run always
     * ends canonical, but a test that fails mid-cutover can leak any intermediate topology, so rather than assume a
     * clean hand-off this normalizes whatever is present back to canonical. Every DDL below is guarded on the tables it
     * touches, so no leaked state can make the reset itself throw and cascade into later tests. {@code end_time} being
     * Nullable is the original schema, non-Nullable the successor.
     */
    @BeforeEach
    void resetTables() {
        // 1. Wrap: `spans` is a Distributed wrapper holding no data of its own — drop it, leaving the successor under
        //    spans_local and the original under spans_pre_cutover_backup (the same shape as a partial wrap).
        if (isDistributed("spans")) {
            execute("DROP TABLE spans ON CLUSTER '{cluster}' SYNC", _ -> {
            });
        }
        // 2. Wrap (completed or partial): successor parked as spans_local, original as spans_pre_cutover_backup, with
        //    `spans` absent. Restore both names.
        if (!tableExists("spans_local_v2") && tableExists("spans_local")) {
            execute("RENAME TABLE spans_local TO spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
            });
        }
        // 2b. Rollback (completed): the successor is parked as spans_post_rollback_backup. Recover it into the
        //     successor's baseline name so step 4 truncates it back to empty.
        if (!tableExists("spans_local_v2") && tableExists("spans_post_rollback_backup")) {
            execute("RENAME TABLE spans_post_rollback_backup TO spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
            });
        }
        if (!tableExists("spans") && tableExists("spans_pre_cutover_backup")) {
            execute("RENAME TABLE spans_pre_cutover_backup TO spans ON CLUSTER '{cluster}'", _ -> {
            });
        }
        // 3. EXCHANGE (completed or partial): `spans` exists but holds the SUCCESSOR schema. Un-swap it with the parked
        //    original — under spans_pre_cutover_backup once the EXCHANGE completed, or still under spans_local_v2 if
        //    only the EXCHANGE ran and its follow-up RENAME did not.
        if (tableExists("spans") && !columnType("spans", "end_time").startsWith("Nullable")) {
            if (tableExists("spans_pre_cutover_backup")) {
                execute("EXCHANGE TABLES spans AND spans_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
                });
                execute("RENAME TABLE spans_pre_cutover_backup TO spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
                });
            } else if (tableExists("spans_local_v2")) {
                execute("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
                });
            }
        }
        // 4. Canonical now; truncate the two tables and clear any residual artifacts (IF EXISTS so a genuinely
        //    unrecoverable partial state still cannot throw here). spans_dist / spans_dist_old are the temp wrapper
        //    names the gapless wrap and stage-C rollback use around their atomic renames.
        execute("DROP TABLE IF EXISTS spans_dist ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS spans_dist_old ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS spans_local ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS spans_pre_cutover_backup ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS spans_post_rollback_backup ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        // ON CLUSTER like every other statement in this reset (and like stage A's truncate), not bare: on a
        // ReplicatedMergeTree a plain TRUNCATE need only be applied by the local replica before the client returns,
        // whereas ON CLUSTER waits for the distributed DDL task — a real barrier before the test body inserts.
        execute("TRUNCATE TABLE IF EXISTS spans ON CLUSTER '{cluster}'", _ -> {
        });
        execute("TRUNCATE TABLE IF EXISTS spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        execute("TRUNCATE TABLE IF EXISTS deletion_events_local ON CLUSTER '{cluster}'", _ -> {
        });
    }

    // --- the core cutover ----------------------------------------------------------------------------------------

    @Test
    void cutoverPreservesEveryDeletionAcrossExchange() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var otherProjectId = ID_GENERATOR.generateId();

        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var preExistingDeleted = mintIds(PRE_EXISTING_DELETED_PER_WEEK);
        var cascadeDeleted = mintIds(CASCADE_DELETED_PER_WEEK);
        // One SPAN id reused across two projects: deleted in projectId, must survive in otherProjectId. The replay's
        // key is the bridge's (workspace_id, project_id, id) — which on this table is NOT a primary-key prefix, since
        // trace_id sits between project_id and id — so this is the case that proves it still scopes correctly.
        var reusedInstant = weekInstant(0, 1);
        var reused = List.of(SeededSpan.builder()
                .id(ID_GENERATOR.generateId(reusedInstant))
                .traceId(ID_GENERATOR.generateId(reusedInstant))
                .createdAt(reusedInstant)
                .build());

        var allSeeded = new ArrayList<SeededSpan>();
        allSeeded.addAll(survivors);
        allSeeded.addAll(preExistingDeleted);
        allSeeded.addAll(cascadeDeleted);
        seedSpans(allSeeded, workspaceId, projectId);
        seedSpans(reused, workspaceId, projectId);
        seedSpans(reused, workspaceId, otherProjectId);
        var fidelityIds = seedFidelityCohort(workspaceId, projectId);

        // Pre-existing deletes: removed before the backfill starts, and NOT recorded in the bridge — INSERT SELECT
        // honors the mask and never copies them, so no replay is involved.
        lightweightDelete(idStrings(preExistingDeleted), workspaceId);

        // Anchor for BOTH the delta and the replay window, captured BEFORE the backfill so it covers the whole run.
        var backfillStart = nowMicros();

        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }

        assertThat(liveCount("spans_local_v2", idStrings(preExistingDeleted), workspaceId))
                .as("pre-existing masked rows must not be copied by the backfill")
                .isZero();
        assertThat(liveCount("spans_local_v2", idStrings(survivors), workspaceId))
                .as("all survivors backfilled")
                .isEqualTo(survivors.size());

        // Deletes during the backfill/delta window. On spans these are always the trace-delete CASCADE: the bridge
        // INSERT goes first (OPIK-8141), then the lightweight DELETE, which is what SpanService.deleteByTraceIds does.
        recordDeletionEvents(idStrings(cascadeDeleted), workspaceId, projectId.toString(), "cascade");
        lightweightDelete(idStrings(cascadeDeleted), workspaceId);
        // Reused-id delete scoped to projectId only — the copy under otherProjectId must survive.
        var reusedId = reused.getFirst().id().toString();
        recordDeletionEvents(Set.of(reusedId), workspaceId, projectId.toString(), "cascade");
        lightweightDeleteScoped(Set.of(reusedId), workspaceId, projectId);

        // During-window instant from the SAME server clock as backfillStart — NOT the JVM host clock, whose skew vs the
        // container could put these below backfillStart and flake the delta.
        var duringWindow = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        // Concurrent upserts: a newer version of a subset of survivors — caught by the delta's last_updated_at arm.
        var deltaUpserted = survivors.subList(0, DELTA_UPSERTS);
        insertRows(deltaUpserted, workspaceId, projectId, "delta-upserted", _ -> duringWindow);
        // Rows created during the window with a client-backdated last_updated_at — caught ONLY by the created_at arm.
        var deltaLateCreated = mintIdsAt(DELTA_LATE_CREATED, duringWindow);
        insertRows(deltaLateCreated, workspaceId, projectId, "late", _ -> BACKDATED);

        deltaInsert(backfillStart);

        // Negative control — before replay, the during-backfill deletes have leaked onto the destination: still fully
        // alive there, because the delta-insert cannot see a lightweight delete. This is what the bridge exists to fix.
        var leakedIds = idStrings(cascadeDeleted);
        assertThat(liveCount("spans_local_v2", leakedIds, workspaceId))
                .as("negative control: without replay, during-backfill cascade deletes leak across the copy")
                .isEqualTo(leakedIds.size());
        assertThat(liveCount("spans_local_v2", idStrings(deltaLateCreated), workspaceId))
                .as("delta created_at arm caught rows written during the window with a backdated last_updated_at")
                .isEqualTo(deltaLateCreated.size());

        // Measured and logged (not asserted): replay wall time is environment-sensitive, so a hard bound here would be a
        // flaky gate on a non-correctness property. The runbook counts it toward the cutover tail during the rehearsal
        // — and on spans that matters more than it did on traces, because the resurrection guard reads an unindexed id
        // column (000088 indexes only created_at/last_updated_at; there is no `spans` equivalent of traces' 000113).
        var replayMillis = replayDeletions(backfillStart);
        log.info("Span deletion replay covered {} ids in {} ms", leakedIds.size() + 1, replayMillis);

        assertThat(liveCount("spans_local_v2", leakedIds, workspaceId))
                .as("replay masks every bridged cascade deletion on the destination")
                .isZero();
        assertThat(liveCountScoped("spans_local_v2", Set.of(reusedId), workspaceId, otherProjectId))
                .as("full-key replay: the reused id survives in the project it was NOT deleted from")
                .isEqualTo(1L);
        assertThat(liveCountScoped("spans_local_v2", Set.of(reusedId), workspaceId, projectId))
                .as("full-key replay: the reused id is masked in the project it WAS deleted from")
                .isZero();

        assertThat(liveCount("spans_local_v2", Set.copyOf(fidelityIds), workspaceId))
                .as("every fidelity-cohort row (all columns populated, ns created_at) is backfilled")
                .isEqualTo(fidelityIds.size());

        // Fidelity QA: before the swap, the deduped, mask-honored, NORMALIZED content of source and destination must be
        // identical. Note the source side is reduced TWICE (see fingerprint) — asserting equality here also proves that
        // reduction picks the same winner ReplacingMergeTree does.
        assertThat(fingerprint("spans_local_v2", Shape.NEW, workspaceId))
                .as("normalized fidelity: destination content equals source content before the swap")
                .isEqualTo(fingerprint("spans", Shape.OLD, workspaceId));
        assertThat(derivedFingerprint("spans_local_v2", workspaceId))
                .as("derived-column parity: the successor's MATERIALIZED expressions did not drift from the source's")
                .isEqualTo(derivedFingerprint("spans", workspaceId));
        assertThat(durationMismatches(workspaceId))
                .as("duration agrees between source and destination beyond the intended ns->us truncation")
                .isZero();

        exchangeTables();

        assertThat(liveCount("spans", leakedIds, workspaceId))
                .as("0 deletion leaks across the EXCHANGE")
                .isZero();
        assertThat(liveCount("spans", idStrings(survivors), workspaceId))
                .as("every survivor is live on the swapped-in table")
                .isEqualTo(survivors.size());
        assertThat(newestNames("spans", idStrings(deltaUpserted), workspaceId))
                .as("newest-version-wins: the delta's upserts are the live rows after the swap")
                .containsExactly("delta-upserted");
    }

    // --- partition spread: the populations one window reaches ----------------------------------------------------

    /**
     * One {@code created_at} window reaches destination partitions spread across centuries, and the single backfill
     * statement copies all of them.
     *
     * <p>The seed mixes the three populations the runbook names: ordinary ids (minted with their row), far-future ids
     * (the litellm ~2201 shape), and far-past ids (a non-v7 UUID, for which {@code UUIDv7ToDateTime} returns
     * 1970-01-01 without throwing — migration 000115 records those are the COMMONER of the two). That spread is why
     * {@code max_partitions_per_insert_block} must be raised above ClickHouse's default of 100: with
     * {@code throw_on_max_partitions_per_insert_block = 1} the INSERT aborts rather than degrading, which is what the
     * traces cutover hit.
     */
    @Test
    void oneWindowCopiesEveryPartitionItsIdsReach() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var ordinary = mintIdsInWeek(0, 12);
        seedSpans(ordinary, workspaceId, projectId);
        // Far-future: a real created_at in week 0, an id minted in 2201.
        var farFuture = spansWithIdAt(6, weekInstant(0, 500), FAR_FUTURE);
        seedSpans(farFuture, workspaceId, projectId);
        // Far-past: a UUIDv4, which UUIDv7ToDateTime resolves to 1970-01-01.
        var farPast = spansWithNonV7Ids(6, weekInstant(0, 700));
        seedSpans(farPast, workspaceId, projectId);

        var all = new ArrayList<SeededSpan>();
        all.addAll(ordinary);
        all.addAll(farFuture);
        all.addAll(farPast);
        var allIds = idStrings(all);

        backfillWeek(0);

        assertThat(copiedIds(workspaceId))
                .as("one statement copies the whole window, whatever centuries its ids partition into")
                .isEqualTo(allIds);
        assertThat(destinationLogicalRows(workspaceId))
                .as("and copies each row exactly once")
                .isEqualTo((long) allIds.size());
        assertThat(scalar("""
                SELECT uniqExact(_partition_id) AS c FROM spans_local_v2 WHERE workspace_id = :workspace_id
                """, statement -> statement.bind("workspace_id", workspaceId)))
                .as("""
                        the window landed in three distinct weekly partitions — the ordinary week, the ~2201 one and \
                        the epoch one — which is the spread max_partitions_per_insert_block has to permit""")
                .isEqualTo(3L);
    }

    /**
     * A far-future id lands in its honest weekly partition, even though the source's stored {@code id_at} disagrees.
     *
     * <p>Migration 000105 typed {@code spans.id_at} {@code DateTime('UTC')} — 32 bits — so a far-future id's stored
     * value does not agree with its honest one. Whether ClickHouse wraps it mod-2^32 or saturates at 2106 is a version
     * detail this test does not depend on. What it pins is that the DESTINATION does not inherit that error: its own
     * {@code id_at} is {@code MATERIALIZED UUIDv7ToDateTime(toUUID(id))} at {@code DateTime64}, honest to 2299, so the
     * row partitions by its true ~2201 week. Anything that read the stored column instead — a partition predicate, a
     * retention sweep, an audit — would place the row somewhere it is not.
     */
    @Test
    void aWrappedSourceIdAtDoesNotFollowTheRowToTheDestination() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var farFuture = spansWithIdAt(1, weekInstant(0, 11), FAR_FUTURE);
        seedSpans(farFuture, workspaceId, projectId);
        var id = farFuture.getFirst().id();

        assertThat(scalar("""
                SELECT toUInt64(toYear(id_at) != toYear(UUIDv7ToDateTime(toUUID(id)))) AS c
                FROM spans
                WHERE workspace_id = :workspace_id AND id = :id
                """, statement -> statement.bind("workspace_id", workspaceId).bind("id", id.toString())))
                .as("""
                        the stored 32-bit spans.id_at disagrees with the id's honest UUIDv7 timestamp — which is why \
                        nothing in the cutover reads it""")
                .isEqualTo(1L);

        backfillWeek(0);
        assertThat(liveCount("spans_local_v2", idStrings(farFuture), workspaceId))
                .as("the row is copied")
                .isEqualTo(1L);
        assertThat(destinationPartitionId(id, workspaceId))
                .as("and lands in its own honest ~2201 weekly partition, isolated from real recent weeks")
                .startsWith("2201");
    }

    // --- parent_span_id: String -> FixedString(36) ---------------------------------------------------------------

    /**
     * {@code SpanDAO}'s {@code PARTIAL_INSERT} writes a FORTY-character poison value into {@code parent_span_id} when a
     * span's parent changes. On {@code spans} that column is a plain {@code String}, so the write succeeds and the value
     * is stored; {@code spans_local_v2}'s is a {@code FixedString(36)}, which cannot hold it.
     *
     * <p>Both halves are asserted, and the negative control is the point: an UNGUARDED projection does not silently
     * truncate or skip the row, it THROWS and takes the whole window with it — which is why every projection in this
     * cutover carries the length guard rather than relying on the value being rare.
     */
    @Test
    void parentPoisonValueIsNormalizedAndAnUnguardedCopyThrows() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var ordinary = mintIdsInWeek(0, 3);
        seedSpans(ordinary, workspaceId, projectId);
        var poisoned = mintIdsInWeek(0, 1);
        seedSpansWithParent(poisoned, workspaceId, projectId, PARENT_POISON);

        // Negative control FIRST, so a later assertion cannot be read as passing merely because the row was absent.
        assertThatThrownBy(() -> backfillWeekWithUnguardedParent(0))
                .as("an unguarded String -> FixedString(36) projection aborts the whole window on the poison value")
                .isNotNull();
        assertThat(destinationLogicalRows(workspaceId))
                .as("""
                        and the aborted statement is what leaves a window partially copied — the reason prerequisite \
                        #2 no longer holds until rollback.sh --stage A clears it""")
                .isLessThanOrEqualTo((long) ordinary.size() + 1);

        execute("TRUNCATE TABLE spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        backfillWeek(0);

        assertThat(liveCount("spans_local_v2", idStrings(poisoned), workspaceId))
                .as("the guarded projection copies the row rather than aborting")
                .isEqualTo(1L);
        assertThat(scalar("""
                SELECT count() AS c
                FROM spans_local_v2
                WHERE workspace_id = :workspace_id
                  AND id = :id
                  AND CAST(parent_span_id AS String) = ''
                """, statement -> statement.bind("workspace_id", workspaceId)
                .bind("id", poisoned.getFirst().id().toString())))
                .as("""
                        and normalizes the poison value to the empty (root-span) sentinel, which CAST-to-String trims \
                        back from the destination's 36 NUL bytes""")
                .isEqualTo(1L);

        // The fidelity gate must treat that normalization as a MATCH, not a mismatch: the fingerprint applies the same
        // length guard to the source side. Without that, every estate that has ever patched a span's parent would fail
        // its pre-EXCHANGE fidelity gate on a copy that did exactly what it was told.
        assertThat(fingerprint("spans_local_v2", Shape.NEW, workspaceId))
                .as("""
                        the fidelity fingerprint normalizes both sides identically, so the copy is not reported as a \
                        mismatch""")
                .isEqualTo(fingerprint("spans", Shape.OLD, workspaceId));
    }

    /**
     * The reverse of the above, and the one place getting it wrong is invisible in SQL. The successor stores an absent
     * parent as 36 NUL bytes; the original's convention is {@code ''}. Migration 000115's header records that the
     * driver surfaces the padded form to Java as 36 NUL characters — which is NOT blank, so a {@code !isBlank()} guard
     * lets it through and {@code UUID.fromString} throws — and that {@code SpanDAO}'s SQL presence checks
     * ({@code LENGTH(CAST(parent_span_id AS Nullable(String))) > 0}) would read 36 rather than 0, making every
     * re-imported root span look like a child.
     *
     * <p>So the reverse sweep's {@code CAST(... AS String)} is load-bearing, and this asserts the stored byte length
     * rather than the SQL-visible value — because the SQL-visible value is exactly what a naive check gets right by
     * accident.
     */
    @Test
    void reverseSweepRestoresTheEmptyParentRatherThanNulPadding() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var roots = mintIdsInWeek(0, 4);
        seedSpans(roots, workspaceId, projectId);

        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);
        var cutoverStart = nowMicros();
        exchangeTables();

        // A post-cutover root span, written into the successor — this is the row the rollback would make non-live and
        // the reverse sweep would re-import.
        var postCutover = mintIdsAt(1, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        insertSuccessorSpan(postCutover.getFirst(), workspaceId, projectId, "post-cutover");

        rollbackExchangeBack();
        var promoteDone = nowMicros();
        reverseSweep(cutoverStart, promoteDone);

        assertThat(scalar("""
                SELECT count() AS c
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                  AND id = :id
                  AND length(parent_span_id) = 0
                """, statement -> statement.bind("workspace_id", workspaceId)
                .bind("id", postCutover.getFirst().id().toString())))
                .as("""
                        the re-imported root span carries an EMPTY parent_span_id, not 36 NUL bytes — length() reads \
                        the stored String, which is what the read path and SpanDAO's presence checks see""")
                .isEqualTo(1L);
    }

    // --- the narrower destination dedup key ----------------------------------------------------------------------

    /**
     * {@code spans} keeps {@code parent_span_id} in its sort key and the column is mutable, so two versions of one span
     * under different parents sort to different keys and NEVER merge — the hazard migration 000115 removed by dropping
     * it from the successor's key. The consequence for the cutover is that the source can hold two live rows where the
     * destination holds one, and a naive count comparison would report a faithful copy as short.
     *
     * <p>Three assertions: the source really does hold both, the destination really does hold one (the newer), and the
     * fidelity fingerprint AGREES — which it only can because the old-schema side is reduced to the destination's key
     * with {@code argMax}, picking the same winner {@code ReplacingMergeTree} picked.
     */
    @Test
    void twoParentsOnTheSourceCollapseToOneOnTheDestinationAndTheCompareAgrees() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var at = weekInstant(0, 5);
        var span = SeededSpan.builder()
                .id(ID_GENERATOR.generateId(at))
                .traceId(ID_GENERATOR.generateId(at))
                .createdAt(at)
                .build();
        var olderParent = ID_GENERATOR.generateId(at).toString();
        var newerParent = ID_GENERATOR.generateId(at).toString();
        // Same span, two parents, DIFFERENT last_updated_at — so the winner is forced and nothing is a tie.
        insertRowsWithParent(List.of(span), workspaceId, projectId, "older", _ -> at, olderParent);
        insertRowsWithParent(List.of(span), workspaceId, projectId, "newer", _ -> at.plusSeconds(60), newerParent);

        assertThat(sourcePhysicalLiveRows(workspaceId))
                .as("the source holds BOTH rows live: parent_span_id is in its sort key, so they never merge")
                .isEqualTo(2L);
        assertThat(sourceLogicalRows(workspaceId))
                .as("...which is ONE span under the destination's dedup key")
                .isEqualTo(1L);

        backfillWeek(0);

        assertThat(destinationLogicalRows(workspaceId))
                .as("the destination collapses them to one row")
                .isEqualTo(1L);
        assertThat(newestNames("spans_local_v2", idStrings(List.of(span)), workspaceId))
                .as("keeping the newer last_updated_at, as ReplacingMergeTree does")
                .containsExactly("newer");
        assertThat(fingerprint("spans_local_v2", Shape.NEW, workspaceId))
                .as("""
                        and the fidelity compare AGREES, because the old-schema side is reduced to the destination's \
                        key with argMax rather than compared row-for-row""")
                .isEqualTo(fingerprint("spans", Shape.OLD, workspaceId));
    }

    /**
     * The case the reduction above CANNOT decide, and must therefore report rather than guess: the same span under two
     * parents at the SAME {@code last_updated_at}, with differing content. There is nothing left to rank the two rows
     * by, so {@code argMax} and {@code ReplacingMergeTree} each pick arbitrarily and may disagree.
     *
     * <p>This is the spans-only cause of a version tie — traces had only the "one key written twice at one version"
     * cause — and the {@code version-ties} block detects both with one detector, because both sides group by the
     * DESTINATION key. A tie on the source with none on the destination (which collapsed them) is the signature, which
     * {@code verify.sh} prints as {@code version_ties=src:N/dst:0} on the differing window. Which VERDICT that lands
     * in depends on how the arbitrary picks fall: coinciding, the window reaches {@code confirm-keys = 0} and is
     * reported INCONCLUSIVE; differing — the common case here, the two rows having different content — the key counts
     * as genuinely differing and the window is a MISMATCH carrying those same counts. Neither is a silent pass.
     *
     * <p>Note what is deliberately NOT asserted: which row wins. That is the whole point — it is arbitrary, and a test
     * that pinned it would be asserting an implementation detail this procedure explicitly refuses to rely on.
     */
    @Test
    void sameSpanUnderTwoParentsAtOneVersionIsAVersionTie() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var at = weekInstant(0, 5);
        var span = SeededSpan.builder()
                .id(ID_GENERATOR.generateId(at))
                .traceId(ID_GENERATOR.generateId(at))
                .createdAt(at)
                .build();
        // SAME last_updated_at, different parents AND different names, so the two rows differ in content.
        insertRowsWithParent(List.of(span), workspaceId, projectId, "one", _ -> at,
                ID_GENERATOR.generateId().toString());
        insertRowsWithParent(List.of(span), workspaceId, projectId, "two", _ -> at,
                ID_GENERATOR.generateId().toString());

        assertThat(versionTies("spans", Shape.OLD, workspaceId))
                .as("the source reports a version tie: one key, two DISTINCT contents at its newest last_updated_at")
                .isEqualTo(1L);

        backfillWeek(0);

        assertThat(versionTies("spans_local_v2", Shape.NEW, workspaceId))
                .as("""
                        the destination reports none — it collapsed them, which is the src:N/dst:0 signature \
                        verify.sh surfaces: the tie is on the side that still holds both""")
                .isZero();
    }

    /**
     * A tie between BYTE-IDENTICAL rows is deliberately NOT reported, and that distinction is load-bearing rather than
     * a nicety: the cutover itself puts several identical rows at one version on the destination, because the delta
     * re-copies every row the backfill already wrote and an unmodified row keeps its {@code last_updated_at}. Counting
     * physical rows instead of distinct contents would report a tie on every faithful copy and fail the gate on the
     * normal path.
     */
    @Test
    void identicalRowsAtOneVersionAreNotAVersionTie() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var seeded = mintIdsInWeek(0, 5);
        seedSpans(seeded, workspaceId, projectId);

        var backfillStart = nowMicros();
        backfillWeek(0);

        // Rows written DURING the window, so the delta's anchor selects them. The historical seed does not qualify —
        // its created_at and last_updated_at both predate backfill_start — which is itself the point of the anchor.
        var duringWindow = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        var written = mintIdsAt(4, duringWindow);
        insertRows(written, workspaceId, projectId, "during", _ -> duringWindow);

        // Two delta passes, which is the normal path rather than an edge case: the runbook asks the operator to re-run
        // delta_replay.sh until the pending count stops improving. An unmodified row keeps its last_updated_at, so each
        // pass lands an IDENTICAL physical version.
        deltaInsert(backfillStart);
        deltaInsert(backfillStart);

        assertThat(destinationPhysicalRows(workspaceId))
                .as("the destination really does hold duplicate physical versions at this point")
                .isGreaterThan((long) (seeded.size() + written.size()));
        assertThat(versionTies("spans_local_v2", Shape.NEW, workspaceId))
                .as("""
                        but they are byte-identical, so they are NOT a tie — counting rows rather than distinct \
                        contents would fail the gate on a faithful copy, on the normal path""")
                .isZero();
        assertThat(destinationLogicalRows(workspaceId))
                .as("and they collapse to one row per key under FINAL, as ReplacingMergeTree intends")
                .isEqualTo((long) (seeded.size() + written.size()));
    }

    // --- the fidelity gate's mismatch resolver -------------------------------------------------------------------

    /**
     * {@code verify.sh}'s {@code confirm-keys} block, which decides what a differing window MEANS — and is therefore
     * the gate's most dangerous piece of logic: a wrong 0 turns a real fidelity failure into a PASS.
     *
     * <p>It re-reads every differing key's LIVE row on both sides, without the window predicate, and counts the keys
     * that still differ. Both verdicts are asserted, because only one of them is safe to get wrong:
     *
     * <ul>
     * <li><b>0 — superseded-version artifact.</b> A strictly OLDER physical version on the destination differs in
     * content but loses the {@code ReplacingMergeTree} comparison, so both sides' live rows are identical. The windowed
     * compare can surface such a row — {@code created_at} is not in the sorting key, so a {@code created_at} predicate
     * can read a part holding a superseded version while excluding the winner's — and the resolver must call it an
     * artifact rather than fail a window whose live data matches.</li>
     * <li><b>&gt;0 — real difference.</b> A strictly NEWER version on the destination wins, so the live rows genuinely
     * disagree and the window is a real fidelity failure.</li>
     * </ul>
     *
     * <p>Both fates are built by version ORDER rather than by part layout, so neither depends on how ClickHouse happens
     * to have merged: the loser is always the lower {@code last_updated_at}.
     */
    @Test
    void confirmKeysSeparatesASupersededVersionFromAGenuineDifference() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var seeded = mintIdsInWeek(0, 4);
        seedSpans(seeded, workspaceId, projectId);
        backfillWeek(0);

        var weekLo = ClickHouseDateTimeFormat.formatMicros(weekInstant(0, 0));
        var weekHi = ClickHouseDateTimeFormat.formatMicros(weekInstant(1, 0));
        assertThat(genuinelyDifferingKeys(weekLo, weekHi, workspaceId))
                .as("precondition: a faithful copy has nothing to resolve")
                .isZero();

        // A SUPERSEDED version on the destination: different content, but an older version, so FINAL discards it.
        var superseded = seeded.getFirst();
        insertSuccessorSpanAt(superseded, workspaceId, projectId, "superseded-content",
                superseded.createdAt().minusSeconds(60), "spans_local_v2");
        assertThat(genuinelyDifferingKeys(weekLo, weekHi, workspaceId))
                .as("""
                        a differing row that LOSES the version comparison is an artifact, not a difference — \
                        reporting it would fail a window whose live data matches on both sides""")
                .isZero();

        // A WINNING version on the destination: the live rows now genuinely disagree.
        var diverged = seeded.get(1);
        insertSuccessorSpanAt(diverged, workspaceId, projectId, "diverged-content",
                diverged.createdAt().plusSeconds(60), "spans_local_v2");
        assertThat(genuinelyDifferingKeys(weekLo, weekHi, workspaceId))
                .as("while a differing row that WINS it is a real fidelity failure, reported as exactly one key")
                .isEqualTo(1L);
    }

    /**
     * The state {@code verify.sh} prints {@code MISMATCH ... version_ties=src:N/dst:0} for, and the reason it asks the
     * tie detector on that branch and not only where {@code confirm-keys} returned 0: one window can hold BOTH a
     * version tie and a genuinely differing key at once.
     *
     * <p>Getting the two to coexist deterministically needs two separate keys, because a tie on its own cannot be
     * pinned to either verdict — whether its two arbitrary winners coincide decides that, and asserting a coin toss is
     * what {@link #sameSpanUnderTwoParentsAtOneVersionIsAVersionTie()} deliberately refuses to do. So the tie supplies
     * the {@code src:N/dst:0} signature and an unrelated key supplies the difference, which is the realistic shape
     * anyway: a window is thousands of spans wide and a tie in it says nothing about the rest.
     *
     * <p>What this pins is that the two counts are independent readings of the same window, so the tie can neither
     * clear the difference nor be hidden by it. A driver that asked for ties only on the {@code confirm-keys = 0}
     * branch would print none here — leaving the operator a bare MISMATCH for a window whose tie is real, which is
     * the diagnosis the runbook's triage tells them to read.
     */
    @Test
    void aWindowCanHoldAVersionTieAndAGenuinelyDifferingKeyAtOnce() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var seeded = mintIdsInWeek(0, 4);
        seedSpans(seeded, workspaceId, projectId);

        // The tie: one span id under two parents at ONE last_updated_at, with differing content.
        var at = weekInstant(0, 5);
        var tied = SeededSpan.builder()
                .id(ID_GENERATOR.generateId(at))
                .traceId(ID_GENERATOR.generateId(at))
                .createdAt(at)
                .build();
        insertRowsWithParent(List.of(tied), workspaceId, projectId, "one", _ -> at,
                ID_GENERATOR.generateId().toString());
        insertRowsWithParent(List.of(tied), workspaceId, projectId, "two", _ -> at,
                ID_GENERATOR.generateId().toString());

        backfillWeek(0);

        // The difference, on an unrelated key: a WINNING version on the destination, so the live rows disagree.
        var diverged = seeded.getFirst();
        insertSuccessorSpanAt(diverged, workspaceId, projectId, "diverged-content",
                diverged.createdAt().plusSeconds(60), "spans_local_v2");

        var weekLo = ClickHouseDateTimeFormat.formatMicros(weekInstant(0, 0));
        var weekHi = ClickHouseDateTimeFormat.formatMicros(weekInstant(1, 0));

        assertThat(genuinelyDifferingKeys(weekLo, weekHi, workspaceId))
                .as("""
                        the window is a MISMATCH, not a confirm-keys = 0 window: at least the diverged key differs,                         and the tied key adds itself or not depending on how its two arbitrary winners fall                        """)
                .isGreaterThanOrEqualTo(1L);
        assertThat(versionTies("spans", Shape.OLD, workspaceId))
                .as("and the SAME window reports the tie, which is what the MISMATCH must be annotated with")
                .isEqualTo(1L);
        assertThat(versionTies("spans_local_v2", Shape.NEW, workspaceId))
                .as("src:N/dst:0 — the destination collapsed the tied rows, so only the source still holds both")
                .isZero();
    }

    // --- usage: Int32 -> Int64 forward, and the narrowing back ---------------------------------------------------

    /**
     * {@code usage} widens {@code Map(String, Int32) → Map(String, Int64)} forward, which is lossless and must not
     * register as a fidelity difference — the fingerprint canonicalizes both sides to sorted {@code key \x1e value}
     * pairs, and {@code toString} of an {@code Int32} and of the {@code Int64} it widens to produce identical text.
     *
     * <p>Backward it NARROWS, and that is the only narrowing anywhere in this procedure. ClickHouse converts rather
     * than refusing, so an out-of-range value would land wrapped and silently wrong in a column the product reads as a
     * token count — which is why {@code reconcile.sh} runs a range PRECHECK before the reverse sweep and refuses on a
     * non-zero result. Both halves are asserted here, including that an ordinary estate reports zero (so the check is
     * not a permanent obstacle).
     */
    @Test
    void usageRoundTripsAndTheReverseRangeCheckCatchesAnOverflow() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 4);
        seedSpans(spans, workspaceId, projectId);
        // Token-shaped Int32 values, including the column's maximum, so the widening is exercised at its boundary.
        execute("""
                ALTER TABLE spans
                UPDATE usage = map('prompt_tokens', toInt32(40000), 'total_tokens', toInt32(2147483647))
                WHERE workspace_id = :workspace_id
                SETTINGS mutations_sync = 2
                """, statement -> statement.bind("workspace_id", workspaceId));

        backfillWeek(0);

        assertThat(fingerprint("spans_local_v2", Shape.NEW, workspaceId))
                .as("""
                        the Int32 -> Int64 widening is lossless and the canonical usage encoding hides it, so it is \
                        not a fidelity difference""")
                .isEqualTo(fingerprint("spans", Shape.OLD, workspaceId));

        var gapStart = ClickHouseDateTimeFormat.formatMicros(weekInstant(0, 0).minusSeconds(1));
        // Server-side, and captured before the bridge writes below, so every deletion event lands at or after it and
        // counts as post-swap. Same clock that stamps event_time, so the ordering is exact rather than hopeful.
        var swapDone = nowMicros();
        assertThat(usageOutOfInt32Range(gapStart, swapDone))
                .as("an ordinary estate reports zero out-of-range values — the precheck is a no-op, not an obstacle")
                .isZero();

        // Now a value only the WIDENED column can hold. This is what a post-cutover write could legitimately produce
        // and what the reverse sweep would narrow back into the original's Int32 column.
        var overflowId = ID_GENERATOR.generateId().toString();
        execute("""
                INSERT INTO spans_local_v2 (id, workspace_id, project_id, trace_id, usage, created_at, last_updated_at)
                VALUES (:id, :workspace_id, :project_id, :trace_id,
                        map('total_tokens', toInt64(3000000000)), now64(6), now64(6))
                """, statement -> statement
                .bind("id", overflowId)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("trace_id", ID_GENERATOR.generateId().toString()));

        assertThat(usageOutOfInt32Range(gapStart, swapDone))
                .as("the precheck detects a value the reverse narrowing would WRAP — the failure it exists to refuse")
                .isEqualTo(1L);

        // And stops counting it once the span is deleted at or after the swap. reverse-sweep excludes those keys, so
        // they are never narrowed; counting them would refuse the whole reverse reconciliation over a row the sweep
        // would not touch — and that refusal sends the operator to resolve rows by hand rather than re-running.
        recordDeletionEvents(Set.of(overflowId), workspaceId, projectId.toString(), "cascade");
        assertThat(usageOutOfInt32Range(gapStart, swapDone))
                .as("a post-swap delete removes the row from what the sweep would import, so the precheck must not refuse on it")
                .isZero();
    }

    // --- deletion-bridge properties ------------------------------------------------------------------------------

    /**
     * A span can be deleted and then re-created under the same id during the window: ids are client-supplied, the
     * delete is a mask, and a newer insert wins under {@code FINAL}. Such an id is bridged as deleted but is LIVE again
     * on the source, and the backfill/delta already copied its live version — so a replay-by-key alone would drop a row
     * that is live on the source, which is silent data loss. The resurrection guard is what prevents it.
     */
    @Test
    void deleteThenResurrectSurvivesTheReplay() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 4);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);

        var resurrected = spans.getFirst();
        recordDeletionEvents(Set.of(resurrected.id().toString()), workspaceId, projectId.toString(), "cascade");
        lightweightDeleteScoped(Set.of(resurrected.id().toString()), workspaceId, projectId);
        // Re-created under the SAME id, after the delete — the cascade's mask has already applied (mutations_sync in
        // lightweightDeleteScoped), so this insert is genuinely live rather than being swept up by the same mutation.
        insertRows(List.of(resurrected), workspaceId, projectId, "resurrected",
                _ -> Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));

        deltaInsert(backfillStart);
        replayDeletions(backfillStart);

        assertThat(liveCount("spans_local_v2", Set.of(resurrected.id().toString()), workspaceId))
                .as("the resurrection guard spares an id that is bridged as deleted but live again on the source")
                .isEqualTo(1L);
        assertThat(newestNames("spans_local_v2", Set.of(resurrected.id().toString()), workspaceId))
                .as("and it is the RE-CREATED version that survives, not the deleted one")
                .containsExactly("resurrected");
    }

    /**
     * The final deletion replay {@code exchange_and_wrap.sh} runs immediately before the swap covers deletes bridged
     * after the main {@code delta_replay.sh} pass — a window covered by neither the earlier forward replay (bounded by
     * when that ran) nor the rollback reverse-replay (bounded by {@code cutover_start}).
     */
    @Test
    void finalReplayBeforeExchangeMasksDeletesBridgedAfterTheMainReplay() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 6);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);
        replayDeletions(backfillStart);

        // A cascade delete that lands AFTER the main replay read the bridge.
        var lateDeleted = Set.of(spans.getLast().id().toString());
        recordDeletionEvents(lateDeleted, workspaceId, projectId.toString(), "cascade");
        lightweightDeleteScoped(lateDeleted, workspaceId, projectId);

        assertThat(liveCount("spans_local_v2", lateDeleted, workspaceId))
                .as("negative control: without the final replay it is still live on the destination")
                .isEqualTo(1L);

        replayDeletions(backfillStart); // the final pre-swap replay, from the same anchor
        exchangeTables();

        assertThat(liveCount("spans", lateDeleted, workspaceId))
                .as("the final pre-swap replay masks it, so it does not leak live across the EXCHANGE")
                .isZero();
    }

    // --- post-swap reconciliation (OPIK-8238) --------------------------------------------------------------------

    /**
     * The forward sweep, with the negative control that makes it load-bearing: a span written to the old table between
     * the last delta and the {@code EXCHANGE} is simply LOST without it — present in the parked backup, absent from the
     * live table — and the four-count postcondition is what says so.
     */
    @Test
    void postSwapSweepRestoresTheGapAndWithoutItThoseWritesAreLost() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 6);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);

        // The gap: written to the old table after the last delta read it.
        var gapStart = nowMicros();
        var gapWritten = mintIdsAt(3, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        insertRows(gapWritten, workspaceId, projectId, "gap", _ -> Instant
                .from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));

        exchangeTables();
        var swapDone = nowMicros();

        // Negative control: the gap rows are NOT live, and the postcondition reports exactly them as missing.
        assertThat(liveCount("spans", idStrings(gapWritten), workspaceId))
                .as("negative control: without the sweep, gap-window spans are absent from the live table")
                .isZero();
        assertThat(liveCount("spans_pre_cutover_backup", idStrings(gapWritten), workspaceId))
                .as("...and are sitting in the parked backup, not destroyed")
                .isEqualTo(gapWritten.size());
        assertThat(forwardCounts(gapStart, swapDone).missing())
                .as("the four-count postcondition reports them as missing_keys")
                .isEqualTo(gapWritten.size());

        reconcileForward("spans", gapStart, swapDone);

        assertThat(liveCount("spans", idStrings(gapWritten), workspaceId))
                .as("the sweep carries them into the live table")
                .isEqualTo(gapWritten.size());
        assertThat(forwardCounts(gapStart, swapDone))
                .as("and the gate is clean: missing, stale and payload-mismatch all zero")
                .isEqualTo(ReconciliationCounts.reconciled());
    }

    /**
     * The sweep must not resurrect a gap-window span the user deleted AFTER the swap. Such a span is still live in the
     * frozen backup (it was live when the backup froze), so without the {@code NOT IN} exclusion the sweep would insert
     * a fresh version and undo the delete.
     *
     * <p>It also pins the delete shape of the post-swap compare mismatch, which happens on a healthy cutover: the key
     * is masked on the live side and live in the backup, so the compare reports it while the gate stays clean. This is
     * the only one of the three shapes that quiescing removes — the re-create and patch shapes, covered by
     * {@code aGapWindowSpanChangedAfterTheSwapIsReportedByTheCompareButIsNotLoss}, it cannot.
     */
    @Test
    void sweepDoesNotResurrectAGapWindowSpanDeletedAfterTheSwap() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 4);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);

        var gapStart = nowMicros();
        var gapWritten = mintIdsAt(2, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        insertRows(gapWritten, workspaceId, projectId, "gap", _ -> Instant
                .from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));

        exchangeTables();
        var swapDone = nowMicros();

        // A cascade delete AFTER the swap, on a gap-window span. It is bridged at >= swapDone, which is what the
        // exclusion keys on.
        var deletedAfterSwap = Set.of(gapWritten.getFirst().id().toString());
        recordDeletionEvents(deletedAfterSwap, workspaceId, projectId.toString(), "cascade");

        reconcileForward("spans", gapStart, swapDone);

        assertThat(liveCount("spans", deletedAfterSwap, workspaceId))
                .as("a gap-window span deleted after the swap is NOT brought back by the sweep")
                .isZero();
        assertThat(liveCount("spans", idStrings(gapWritten), workspaceId))
                .as("while the other gap-window spans still are")
                .isEqualTo(gapWritten.size() - 1);
        assertThat(genuinelyDifferingKeys("spans_pre_cutover_backup", "spans", gapStart, swapDone, workspaceId))
                .as("""
                        and the post-swap compare reports that key as differing — masked on the live side, still \
                        live in the frozen backup — which is a mismatch on a healthy cutover""")
                .isEqualTo(1L);
        assertThat(forwardCounts(gapStart, swapDone))
                .as("while the reconciliation gate, which excludes keys bridged at or after the swap, stays clean")
                .isEqualTo(ReconciliationCounts.reconciled());
    }

    /**
     * A gap-window span RE-CREATED after the swap: the cascade bridges the delete and masks the row, so the insert
     * that follows has nothing to merge onto and {@code created_at} is stamped fresh. The compare reports the key —
     * both sides are bounded on {@code created_at}, so the live row has left the window — while the reconciliation
     * counts report nothing at all, because {@code verify-forward} drops keys bridged at or after {@code swap_done}
     * from its parked set BEFORE bucketing. Not even {@code newer_keys}: the key is excluded, not classified.
     */
    @Test
    void aGapWindowSpanRecreatedAfterTheSwapIsReportedByTheCompareAndExcludedFromTheCounts() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var seeded = mintIdsInWeek(0, 3);
        seedSpans(seeded, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);

        // The gap: written to the old table after the last delta read it.
        var gapStart = nowMicros();
        var gapInstant = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        var gapWritten = mintIdsAt(1, gapInstant);
        insertRows(gapWritten, workspaceId, projectId, "gap", _ -> gapInstant);

        exchangeTables();
        var swapDone = nowMicros();

        reconcileForward("spans", gapStart, swapDone);
        assertThat(forwardCounts(gapStart, swapDone))
                .as("the sweep reconciles the gap before anything is changed on top of it")
                .isEqualTo(ReconciliationCounts.reconciled());

        // After the window closes, however long the setup above took.
        var original = gapWritten.getFirst();
        var afterSwap = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(swapDone)).plusSeconds(120);

        // The cascade that makes it a re-create: bridged at >= swapDone, and the mask is what leaves the insert
        // with no surviving row to merge onto.
        var ids = idStrings(gapWritten);
        recordDeletionEvents(ids, workspaceId, projectId.toString(), "cascade");
        lightweightDelete(ids, workspaceId);
        var recreated = SeededSpan.builder().id(original.id()).traceId(original.traceId()).createdAt(afterSwap)
                .build();
        insertSuccessorSpanAt(recreated, workspaceId, projectId, "recreated-after-swap", afterSwap);

        assertThat(liveCount("spans", ids, workspaceId))
                .as("the span is live again under its own id — nothing was lost")
                .isEqualTo(1L);
        assertThat(rowsInsideWindow("spans", ids, workspaceId, gapStart, swapDone))
                .as("but its winning created_at is past the window, which is what makes the drill-down print it as"
                        + " absent on the live side")
                .isZero();
        assertThat(genuinelyDifferingKeys("spans_pre_cutover_backup", "spans", gapStart, swapDone, workspaceId))
                .as("so the post-swap compare reports it as differing")
                .isEqualTo(1L);
        assertThat(forwardCounts(gapStart, swapDone))
                .as("""
                        while the reconciliation counts report it in NO bucket, newer_keys included: the bridge \
                        exclusion removes it from the parked set before any bucketing happens""")
                .isEqualTo(ReconciliationCounts.reconciled());
    }

    /**
     * A gap-window span PATCHED after the swap — same {@code created_at}, later {@code last_updated_at}. The claim
     * under test is how reconciliation buckets that row: the compare reports the key, the three gating counts stay
     * clean, and the change surfaces only in the informational {@code newer} count.
     *
     * <p><b>The preserved {@code created_at} is a premise of the fixture, not an assertion about {@code SpanDAO}.</b>
     * Like every case in this class the row is written straight to the table rather than through the DAO, so this
     * test would not catch a DAO change that began stamping a fresh {@code created_at} on merge — it would catch
     * that the reconciliation SQL had stopped bucketing such a row correctly. The runbook derives the DAO half from
     * {@code SpanDAO}'s {@code PARTIAL_INSERT} rather than from here.
     *
     * <p>Together with the re-create case these are why quiescing cannot turn the compare into a PASS: only a delete
     * is quiescable, and neither of these is.
     */
    @Test
    void aGapWindowSpanPatchedAfterTheSwapIsReportedByTheCompareButIsNotLoss() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var seeded = mintIdsInWeek(0, 3);
        seedSpans(seeded, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);

        // The gap: written to the old table after the last delta read it.
        var gapStart = nowMicros();
        var gapInstant = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        var gapWritten = mintIdsAt(1, gapInstant);
        insertRows(gapWritten, workspaceId, projectId, "gap", _ -> gapInstant);

        exchangeTables();
        var swapDone = nowMicros();

        reconcileForward("spans", gapStart, swapDone);
        assertThat(forwardCounts(gapStart, swapDone))
                .as("the sweep reconciles the gap before anything is changed on top of it")
                .isEqualTo(ReconciliationCounts.reconciled());

        // After the window closes, however long the setup above took.
        var original = gapWritten.getFirst();
        var afterSwap = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(swapDone)).plusSeconds(120);

        // No delete: the merge path keeps the existing created_at and only advances the version.
        insertSuccessorSpanAt(original, workspaceId, projectId, "patched-after-swap", afterSwap);

        assertThat(liveCount("spans", idStrings(gapWritten), workspaceId))
                .as("the span is present and current on the live table — nothing was lost")
                .isEqualTo(1L);
        assertThat(rowsInsideWindow("spans", idStrings(gapWritten), workspaceId, gapStart, swapDone))
                .as("created_at is preserved, so unlike a re-create it stays inside the window")
                .isEqualTo(1L);
        assertThat(genuinelyDifferingKeys("spans_pre_cutover_backup", "spans", gapStart, swapDone, workspaceId))
                .as("the post-swap compare reports it as differing even though the row counts stay equal")
                .isEqualTo(1L);
        var afterPatch = forwardCounts(gapStart, swapDone);
        assertThat(afterPatch)
                .as("""
                        while the three write-loss counts — the actual gate — stay clean, which is the distinction \
                        an operator has to make at this point""")
                .extracting(ReconciliationCounts::missing, ReconciliationCounts::stale,
                        ReconciliationCounts::payloadMismatch)
                .containsExactly(0L, 0L, 0L);
        assertThat(afterPatch.newer())
                .as("and, unlike a bridged re-create, it IS classified: the live side is ahead of the frozen backup")
                .isEqualTo(1L);
    }

    /**
     * The four counts classify each key into exactly ONE bucket, on the DESTINATION's full key. Four keys are planted,
     * one per bucket, and each count is asserted — a classification that double-counted or mis-bucketed would still sum
     * correctly, so the buckets are checked individually.
     */
    @Test
    void reconciliationCountsClassifyEachKeyByItsVersionRelationOnTheFullKey() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var seeded = mintIdsInWeek(0, 2);
        seedSpans(seeded, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);

        var gapStart = nowMicros();
        var gapInstant = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        // missing: written to the old table in the gap and never swept.
        var missing = mintIdsAt(1, gapInstant);
        insertRows(missing, workspaceId, projectId, "missing", _ -> gapInstant);
        // stale / newer: a pre-existing key updated in the gap, so the backup holds a NEWER version than the successor.
        var stale = seeded.getFirst();
        insertRows(List.of(stale), workspaceId, projectId, "gap-update", _ -> gapInstant);

        exchangeTables();
        var swapDone = nowMicros();

        // newer: a post-swap write on a gap-window key advances the live version past the parked one.
        var newer = mintIdsAt(1, gapInstant);
        insertSuccessorSpan(newer.getFirst(), workspaceId, projectId, "newer-live");
        // ...but it must also be in the parked backup's gap window, so plant it there too, older.
        insertBackupSpan(newer.getFirst(), workspaceId, projectId, "newer-parked", gapInstant);

        var counts = forwardCounts(gapStart, swapDone);
        assertThat(counts.missing())
                .as("missing_keys: written to the old table in the gap, absent from the live one")
                .isEqualTo(1L);
        assertThat(counts.stale())
                .as("""
                        stale_keys: on the live table at an OLDER version than the parked one — which a presence check \
                        would have reported as clean""")
                .isEqualTo(1L);
        assertThat(counts.payloadMismatch())
                .as("payload_mismatch_keys: none, since nothing differs at an equal version")
                .isZero();
        assertThat(counts.newer())
                .as("newer_keys: written again after the swap, deliberately left alone and NOT part of the gate")
                .isEqualTo(1L);

        reconcileForward("spans", gapStart, swapDone);

        var after = forwardCounts(gapStart, swapDone);
        assertThat(after.missing()).as("the sweep closes missing_keys").isZero();
        assertThat(after.stale()).as("and stale_keys").isZero();
        assertThat(after.payloadMismatch()).as("and payload_mismatch_keys").isZero();
        assertThat(after.newer())
                .as("while newer_keys survives, because the sweep's INSERT loses the version comparison")
                .isEqualTo(1L);
    }

    /**
     * {@code payload_mismatch_keys}, the third gating count, asserted NON-zero — every other test leaves it at 0, so
     * without this one the arm could be broken (an inverted comparison, a fingerprint that always matches) and the gate
     * would silently never fire for the class of corruption it exists to catch.
     *
     * <p>The shape is a key live on both sides, inside the gap window, at the SAME {@code last_updated_at}, with
     * differing content — the one combination the other three buckets cannot express: it is neither absent, nor older,
     * nor newer.
     *
     * <p><b>The sweep is deliberately not run here.</b> {@code reconcile.sh} says why in its own triage output: at an
     * equal version a re-insert cannot win the {@code ReplacingMergeTree} comparison, so this is the bucket a sweep
     * cannot fix and an operator has to triage. Running it would assert a coin toss.
     */
    @Test
    void payloadMismatchIsReportedWhenTheLiveRowDiffersAtAnEqualVersion() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var seeded = mintIdsInWeek(0, 2);
        seedSpans(seeded, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);

        var gapStart = nowMicros();
        var gapInstant = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        exchangeTables();
        var swapDone = nowMicros();

        // One key, planted on both sides at the SAME version and with different content.
        var diverged = mintIdsAt(1, gapInstant).getFirst();
        insertBackupSpan(diverged, workspaceId, projectId, "parked-content", gapInstant);
        insertSuccessorSpanAt(diverged, workspaceId, projectId, "live-content", gapInstant);

        var counts = forwardCounts(gapStart, swapDone);
        assertThat(counts.payloadMismatch())
                .as("""
                        payload_mismatch_keys: same key, same version, different content — the live row disagrees \
                        with the frozen one and no version ranks them""")
                .isEqualTo(1L);
        assertThat(counts.missing())
                .as("and the classification is exclusive: the key is present, so not missing_keys")
                .isZero();
        assertThat(counts.stale()).as("nor stale_keys, the versions being equal").isZero();
        assertThat(counts.newer()).as("nor newer_keys, for the same reason").isZero();
    }

    /**
     * The post-swap replay covers a delete bridged between the final PRE-swap replay and the {@code EXCHANGE} — a
     * window covered by neither the forward replay (bounded by when it ran) nor the rollback reverse-replay (bounded by
     * {@code cutover_start}). It is the residual the FROZEN resurrection guard closes, and it is why the sweep and the
     * replay are two steps rather than one: the sweep alone is shown here to be insufficient, so the two cannot be
     * confused for each other.
     */
    @Test
    void postSwapReplayMasksADeleteBridgedBetweenTheFinalReplayAndTheExchange() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 5);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);
        replayDeletions(backfillStart); // the final pre-swap replay

        // A cascade delete landing AFTER that replay read the bridge, but before the swap.
        var gapStart = nowMicros();
        var lateDeleted = Set.of(spans.getFirst().id().toString());
        recordDeletionEvents(lateDeleted, workspaceId, projectId.toString(), "cascade");
        lightweightDeleteScoped(lateDeleted, workspaceId, projectId);

        exchangeTables();
        var swapDone = nowMicros();

        forwardSweep("spans", gapStart, swapDone);
        assertThat(liveCount("spans", lateDeleted, workspaceId))
                .as("""
                        the sweep ALONE is insufficient: the span was copied before the delete fired, so it is live on \
                        the successor and mask-honoring the frozen backup does not remove it""")
                .isEqualTo(1L);

        postSwapDeletionReplay("spans", gapStart, swapDone);
        assertThat(liveCount("spans", lateDeleted, workspaceId))
                .as("""
                        the post-swap replay masks it, reading its resurrection guard from the FROZEN backup — which \
                        is race-free where a live source cannot be""")
                .isZero();
    }

    /**
     * The staleness scope (arm 3), and the data loss it prevents. A frozen source cannot see a row written AFTER the
     * swap, so a key deleted before it and re-created after looks "still deleted" to the resurrection guard — and
     * without the scope the replay would mask a live post-cutover write, turning the fix for write loss into a cause of
     * it. Not exotic: span ids are client-supplied and {@code SpanDAO}'s update path re-inserts a version.
     *
     * <p>The negative control runs FIRST and shows the write disappear, so the positive assertion cannot be read as
     * passing merely because nothing would have masked it anyway.
     */
    @Test
    void postSwapReplayDoesNotMaskASpanReCreatedAfterTheSwap() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 4);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);

        var gapStart = nowMicros();
        var recreated = spans.getFirst();
        var recreatedId = Set.of(recreated.id().toString());
        recordDeletionEvents(recreatedId, workspaceId, projectId.toString(), "cascade");
        lightweightDeleteScoped(recreatedId, workspaceId, projectId);

        exchangeTables();
        var swapDone = nowMicros();

        // Re-created on the SUCCESSOR after the swap: created_at and last_updated_at are both now, i.e. >= swap_done.
        insertSuccessorSpan(recreated, workspaceId, projectId, "recreated-after-swap");

        postSwapDeletionReplayWithoutStalenessScope("spans", gapStart);
        assertThat(liveCount("spans", recreatedId, workspaceId))
                .as("""
                        negative control: with the scope inert, the replay masks a live POST-CUTOVER write — the \
                        frozen guard cannot see it, so it still reads as deleted""")
                .isZero();

        insertSuccessorSpan(recreated, workspaceId, projectId, "recreated-again");
        postSwapDeletionReplay("spans", gapStart, swapDone);
        assertThat(liveCount("spans", recreatedId, workspaceId))
                .as("""
                        with the scope, the replay spares it: the row is stamped at or after the swap, so it is not \
                        the stale copy the guard is aimed at""")
                .isEqualTo(1L);
        assertThat(newestNames("spans", recreatedId, workspaceId))
                .as("and it is the re-created version that survives")
                .containsExactly("recreated-again");
    }

    /**
     * The reverse sweep's sentinel denormalization, and the reason it is not cosmetic. The successor stores an absent
     * {@code end_time} as the epoch and an absent {@code ttft} as NaN; the original's convention is {@code NULL}, and
     * its MATERIALIZED {@code duration} guards only {@code end_time IS NOT NULL} — it knows nothing of the epoch — so
     * importing the sentinel verbatim would give every unfinished span a duration of roughly -1.79e12 ms.
     *
     * <p>Restoring {@code NULL} is what makes the recomputed duration {@code NULL}, and the mutation-free INSERT path
     * recomputes it on write. Asserted on {@code duration} as well as on the two columns, because the columns being
     * right while the derived value is wrong is precisely the failure this repairs.
     */
    @Test
    void reverseSweepDenormalizesSentinelsSoDurationIsNullNotNegative() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 3);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);
        var cutoverStart = nowMicros();
        exchangeTables();

        // An UNFINISHED post-cutover span: no end_time and no ttft, so the successor stores both sentinels.
        var unfinished = mintIdsAt(1, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        insertSuccessorSpan(unfinished.getFirst(), workspaceId, projectId, "unfinished");
        assertThat(scalar("""
                SELECT count() AS c
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                  AND id = :id
                  AND end_time = toDateTime64('1970-01-01 00:00:00', 6)
                  AND isNaN(ttft)
                """, statement -> statement.bind("workspace_id", workspaceId)
                .bind("id", unfinished.getFirst().id().toString())))
                .as("precondition: the successor really did store the epoch / NaN sentinels")
                .isEqualTo(1L);

        rollbackExchangeBack();
        var promoteDone = nowMicros();
        reverseSweep(cutoverStart, promoteDone);

        assertThat(scalar("""
                SELECT count() AS c
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                  AND id = :id
                  AND end_time IS NULL
                  AND ttft IS NULL
                  AND duration IS NULL
                """, statement -> statement.bind("workspace_id", workspaceId)
                .bind("id", unfinished.getFirst().id().toString())))
                .as("""
                        the re-imported row carries NULL on both columns, and its recomputed duration is NULL rather \
                        than a large negative""")
                .isEqualTo(1L);
    }

    /**
     * Ordering: {@code reconcile.sh} runs the reverse replay AFTER the reverse sweep, which is what makes post-cutover
     * deletes win over the writes it re-imported. Three post-cutover fates are planted, because only one of them
     * actually needs the ordering and the other two are what make that one legible:
     *
     * <ul>
     * <li><b>written, never deleted</b> — re-imported and stays live;</li>
     * <li><b>written then deleted</b> — the sweep is MASK-HONORED, so it never re-imports it at all. The replay has
     * nothing to do for this one, which is worth pinning: it is the case one expects the ordering to be about, and it
     * is not;</li>
     * <li><b>written, deleted, then RE-CREATED</b> — this is the one. It is LIVE in the parked successor (the
     * re-creation) while bridged as deleted, so the sweep does re-import it, and only the replay running afterwards
     * removes it again. The replay is deliberately guard-less, so the delete is honoured and the re-creation is lost
     * with the other discarded writes — the rollback semantics the runbook documents rather than an accident.</li>
     * </ul>
     */
    @Test
    void reverseSweepThenReplayKeepsAPostCutoverDeleteMasked() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 3);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);
        var cutoverStart = nowMicros();
        exchangeTables();

        var kept = mintIdsAt(1, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        var removed = mintIdsAt(1, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        var recreated = mintIdsAt(1, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        insertSuccessorSpan(kept.getFirst(), workspaceId, projectId, "kept");
        insertSuccessorSpan(removed.getFirst(), workspaceId, projectId, "removed");
        insertSuccessorSpan(recreated.getFirst(), workspaceId, projectId, "recreated-before");

        recordDeletionEvents(union(idStrings(removed), idStrings(recreated)), workspaceId, projectId.toString(),
                "cascade");
        lightweightDeleteScoped(union(idStrings(removed), idStrings(recreated)), workspaceId, projectId);
        // Re-created under the SAME id after its delete settled — live again on the successor, still bridged.
        insertSuccessorSpan(recreated.getFirst(), workspaceId, projectId, "recreated-after");

        rollbackExchangeBack();
        var promoteDone = nowMicros();

        reverseSweep(cutoverStart, promoteDone);
        assertThat(liveCount("spans", idStrings(removed), workspaceId))
                .as("""
                        the sweep is MASK-HONORED, so a plainly-deleted post-cutover span is never re-imported — the \
                        ordering is not what protects this case""")
                .isZero();
        assertThat(liveCount("spans", idStrings(recreated), workspaceId))
                .as("""
                        but a deleted-then-RE-CREATED span is live in the parked successor, so the sweep does bring it \
                        back — this is the case the ordering exists for""")
                .isEqualTo(1L);

        reverseReplay(cutoverStart);
        assertThat(liveCount("spans", idStrings(recreated), workspaceId))
                .as("""
                        and the replay, running AFTER the sweep, masks it: the replay carries no resurrection guard, \
                        so the delete is honoured and the re-creation is lost with the other discarded writes""")
                .isZero();
        assertThat(liveCount("spans", idStrings(kept), workspaceId))
                .as("while the post-cutover write that was never deleted stays live")
                .isEqualTo(1L);
    }

    /**
     * The advisory {@code leak-check-forward}, and the residual it exists to report. The post-swap replay's staleness
     * scope (arm 3) bounds on {@code created_at} AND {@code last_updated_at} being below the swap — but
     * {@code last_updated_at} is CLIENT-SUPPLIED on the batch-ingest path, so a genuinely pre-swap span can carry a
     * future timestamp, fall outside that scope, and keep its captured delete unmasked.
     *
     * <p>That is a leaked delete, and the predicate stays as it is deliberately: scoping on {@code created_at} alone
     * would instead mask a post-swap PATCH of a pre-existing span, and over-masking destroys a write that exists only
     * on the successor while over-sparing leaves a recoverable, reportable residual. So the residual is DETECTED rather
     * than prevented — and this asserts the detector actually detects it, with an ordinary deleted span alongside as
     * the control, so a check that simply reported everything would fail.
     */
    @Test
    void leakCheckReportsACapturedDeleteStillLiveAtABackupVersion() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var ordinary = mintIdsInWeek(0, 3);
        seedSpans(ordinary, workspaceId, projectId);
        // One span whose client stamped last_updated_at far in the future — accepted by the API, which validates only
        // "before 2300", and bound verbatim by SpanDAO.
        var futureDated = mintIdsInWeek(0, 1);
        insertRows(futureDated, workspaceId, projectId, "future-dated",
                _ -> LocalDate.of(2299, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC));

        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);

        var gapStart = nowMicros();
        // Both deleted on the source BEFORE the swap, so the parked backup holds both masked.
        var deleted = union(idStrings(futureDated), Set.of(ordinary.getFirst().id().toString()));
        recordDeletionEvents(deleted, workspaceId, projectId.toString(), "cascade");
        lightweightDeleteScoped(deleted, workspaceId, projectId);

        exchangeTables();
        var swapDone = nowMicros();
        reconcileForward("spans", gapStart, swapDone);

        assertThat(liveCount("spans", Set.of(ordinary.getFirst().id().toString()), workspaceId))
                .as("control: an ordinary pre-swap delete IS masked by the post-swap replay")
                .isZero();
        assertThat(liveCount("spans", idStrings(futureDated), workspaceId))
                .as("""
                        while the future-dated one is spared — it falls outside the staleness scope, which is the \
                        recoverable side of a trade the predicate makes deliberately""")
                .isEqualTo(1L);
        assertThat(leakCheckForward(gapStart))
                .as("""
                        and the advisory reports exactly that one, by comparing the live row's VERSION against the \
                        versions the frozen backup held — no timestamp involved""")
                .isEqualTo(1L);
    }

    // --- rollback ------------------------------------------------------------------------------------------------

    /** Stage A discards the disposable shadow; the live {@code spans} was never touched by the backfill. */
    @Test
    void rollbackBeforeExchangeDiscardsShadowAndLeavesLiveUntouched() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 5);
        seedSpans(spans, workspaceId, projectId);
        backfillWeek(0);
        assertThat(destinationLogicalRows(workspaceId)).isEqualTo((long) spans.size());

        execute("TRUNCATE TABLE spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });

        assertThat(destinationLogicalRows(workspaceId))
                .as("stage A empties the shadow")
                .isZero();
        assertThat(sourceLogicalRows(workspaceId))
                .as("and leaves the live table untouched")
                .isEqualTo((long) spans.size());
    }

    /**
     * Stage B swaps the tables back and reverse-replays, so a span deleted AFTER the cutover does not resurrect on the
     * restored original. The reverse replay is deliberately guard-less: rollback abandons post-cutover writes while
     * still honouring post-cutover deletes.
     */
    @Test
    void rollbackAfterExchangeSwapsBackWithoutResurrectingDeletes() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 6);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);
        var cutoverStart = nowMicros();
        exchangeTables();

        // A post-cutover cascade delete, on the live successor.
        var deletedAfterCutover = Set.of(spans.getFirst().id().toString());
        recordDeletionEvents(deletedAfterCutover, workspaceId, projectId.toString(), "cascade");
        lightweightDeleteScoped(deletedAfterCutover, workspaceId, projectId);

        rollbackExchangeBack();

        assertThat(liveCount("spans", deletedAfterCutover, workspaceId))
                .as("""
                        negative control: the promote alone brings the deleted span back, because the restored \
                        original never saw the delete""")
                .isEqualTo(1L);

        reverseReplay(cutoverStart);

        assertThat(liveCount("spans", deletedAfterCutover, workspaceId))
                .as("the reverse replay re-applies it, so it does not resurrect")
                .isZero();
        assertThat(verifyReplayPostcondition(cutoverStart))
                .as("and the postcondition agrees: no bridged id since cutover_start is live again")
                .isZero();
        assertThat(columnType("spans", "end_time"))
                .as("the estate is back on the ORIGINAL schema")
                .startsWith("Nullable");
        assertThat(tableExists("spans_post_rollback_backup"))
                .as("with the successor retained as a parked backup, not dropped")
                .isTrue();
    }

    /**
     * The REVERSE postcondition ({@code verify-reverse}), which is the forward block with the two schema shapes — and
     * therefore the {@code argMax} reduction — swapped onto the other side. That mirroring is exactly the kind of thing
     * that is written once and typo'd, and it decides whether a recovery is reported as complete, so it is exercised
     * rather than inferred from the forward block passing.
     *
     * <p>The scenario is the one {@code --confirm-reimport-successor-writes} exists for: a write the successor accepted
     * after {@code cutover_start}, which the promote made non-live and the reverse sweep brings back.
     */
    @Test
    void reverseReconciliationCountsClassifyOnTheDestinationKey() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var seeded = mintIdsInWeek(0, 4);
        seedSpans(seeded, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);
        var cutoverStart = nowMicros();
        exchangeTables();

        // A post-cutover write on the successor — the set the promote discards.
        var postCutover = mintIdsAt(2, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        postCutover.forEach(span -> insertSuccessorSpan(span, workspaceId, projectId, "post-cutover"));

        rollbackExchangeBack();
        var promoteDone = nowMicros();

        assertThat(reverseCounts(cutoverStart).missing())
                .as("""
                        before the reverse sweep the post-cutover writes are parked and absent from the restored \
                        original, which the reverse postcondition reports as missing_keys""")
                .isEqualTo(postCutover.size());

        reverseSweep(cutoverStart, promoteDone);
        reverseReplay(cutoverStart);

        assertThat(reverseCounts(cutoverStart))
                .as("and after it the reverse gate is clean")
                .isEqualTo(ReconciliationCounts.reconciled());
        assertThat(liveCount("spans", idStrings(postCutover), workspaceId))
                .as("with the writes live again on the restored original")
                .isEqualTo(postCutover.size());
    }

    /**
     * The rollback's sentinel repair ({@code 000004_rollback_sentinel_repair.sql}) and the counts that verify it
     * ({@code 000004_rollback_verify_sentinels.sql}) — the one shipped rollback statement nothing else exercises.
     *
     * <p>It pins a claim about ClickHouse the runbook makes and an operator cannot check under pressure: after
     * {@code ALTER UPDATE end_time = NULL}, the original's MATERIALIZED {@code duration} — which guards only
     * {@code end_time IS NOT NULL} and so computed a large NEGATIVE value from the epoch sentinel — is RECOMPUTED by
     * the mutation rather than left stale. {@code stale_duration} in the verify block exists for exactly that doubt;
     * asserting it reaches 0 is what makes the documented repair sufficient on its own.
     */
    @Test
    void sentinelRepairRestoresNullAndRecomputesTheNegativeDuration() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        // What the flag writes while spanColumnsNonNullable is live: the epoch / NaN sentinels, stored in the still-
        // Nullable ORIGINAL. Planted directly, because that is the state a rollback inherits rather than creates.
        var at = weekInstant(0, 7);
        var affected = mintIdsInWeek(0, 1).getFirst();
        execute("""
                INSERT INTO spans (id, workspace_id, project_id, trace_id, name, start_time, end_time, ttft,
                                   created_at, last_updated_at)
                VALUES (:id, :workspace_id, :project_id, :trace_id, 'sentinel-bearing',
                        toDateTime64(:at, 9), toDateTime64('1970-01-01 00:00:00', 9), toFloat64('nan'),
                        toDateTime64(:at, 9), toDateTime64(:at_micros, 6))
                """, statement -> statement
                .bind("id", affected.id().toString())
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("trace_id", affected.traceId().toString())
                .bind("at", ClickHouseDateTimeFormat.formatNanos(at))
                .bind("at_micros", ClickHouseDateTimeFormat.formatMicros(at)));

        var windowFrom = ClickHouseDateTimeFormat.formatMicros(at.minusSeconds(60));
        var windowTo = ClickHouseDateTimeFormat.formatMicros(at.plusSeconds(60));

        var before = sentinelCounts(windowFrom, windowTo, workspaceId);
        assertThat(before.sentinelEndTime()).as("precondition: the epoch sentinel is stored").isEqualTo(1L);
        assertThat(before.sentinelTtft()).as("and the NaN one").isEqualTo(1L);
        assertThat(before.negativeFromSentinel())
                .as("""
                        and the original's MATERIALIZED duration turned it into a large negative — the failure the \
                        repair exists for, and why restoring the two columns is not the whole job""")
                .isEqualTo(1L);

        sentinelRepair(windowFrom, windowTo);

        var after = sentinelCounts(windowFrom, windowTo, workspaceId);
        assertThat(after.sentinelEndTime()).as("the repair restores NULL on end_time").isZero();
        assertThat(after.sentinelTtft()).as("and on ttft").isZero();
        assertThat(after.negativeFromSentinel()).as("so no row carries a sentinel-derived negative duration").isZero();
        assertThat(after.staleDuration())
                .as("""
                        and duration was RECOMPUTED by the mutation rather than left stale — which is what makes the \
                        ALTER UPDATE sufficient, where a MATERIALIZE COLUMN would re-evaluate the same expression""")
                .isZero();
    }

    // --- schema parity -------------------------------------------------------------------------------------------

    @Test
    void cutoverCopiesEveryBaseColumn() {
        var spansBase = baseColumns("spans");
        var successorBase = baseColumns("spans_local_v2");
        var copied = Arrays.stream(COPIED_COLUMNS.split(","))
                .map(String::trim)
                .filter(column -> !column.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        assertThat(copied)
                .as("cutover COPIED_COLUMNS must equal the stored (non-materialized) columns of spans")
                .isEqualTo(spansBase);
        assertThat(successorBase)
                .as("spans_local_v2 stored columns = spans stored columns + the is_deleted meta-column")
                .isEqualTo(union(spansBase, Set.of("is_deleted")));
    }

    /**
     * Materialized-column parity guard, the complement to {@link #cutoverCopiesEveryBaseColumn()}. The backfill does
     * not copy materialized columns (the destination recomputes them), so they are outside the copy guard — but the two
     * tables must still expose the SAME materialized columns for as long as both exist, or a materialized column added
     * to one by a future migration and not the other leaves post-cutover queries referencing a column the live table
     * lacks.
     */
    @Test
    void successorMaterializedColumnsMatchSource() {
        assertThat(materializedColumns("spans_local_v2"))
                .as("spans_local_v2 must expose exactly the same MATERIALIZED columns as spans")
                .isEqualTo(materializedColumns("spans"));
    }

    /**
     * The successor's projection must NOT reference a column that exists only on traces. This is cheap insurance
     * against the likeliest way this directory drifts: someone copies a statement across from the traces cutover.
     */
    @Test
    void cutoverProjectionReferencesNoTracesOnlyColumn() {
        assertThat(baseColumns("spans"))
                .as("""
                        spans has none of traces' exclusive columns — output_keys (migration 000044), thread_id, \
                        visibility_mode — so a projection naming one is a copy-paste from the traces cutover""")
                .doesNotContain("output_keys", "thread_id", "visibility_mode");
        assertThat(COPIED_COLUMNS)
                .as("and neither does the cutover's column list")
                .doesNotContain("output_keys")
                .doesNotContain("thread_id")
                .doesNotContain("visibility_mode");
    }

    // --- timezone independence -----------------------------------------------------------------------------------

    /**
     * Every window bound in this runbook pins {@code 'UTC'}, because these columns are {@code DateTime64(n, 'UTC')}
     * while an unpinned literal is parsed in the SERVER timezone. The session is put WEST of UTC deliberately: an
     * unpinned literal read in a westward zone resolves LATER in absolute terms, so a bound moves past rows that belong
     * inside it — the silent failure. An eastward zone moves bounds earlier and would let an unpinned literal pass, so
     * it would not discriminate.
     */
    @Test
    void backfillAndDeltaAreUnaffectedByTheSessionTimezone() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 8);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();

        backfillWeekWestwardSession(0);
        assertThat(destinationLogicalRows(workspaceId))
                .as("the backfill's week bounds mean the same instants under a westward session timezone")
                .isEqualTo((long) spans.size());

        var duringWindow = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        var written = mintIdsAt(3, duringWindow);
        insertRows(written, workspaceId, projectId, "during", _ -> duringWindow);

        deltaInsertWestwardSession(backfillStart);
        assertThat(liveCount("spans_local_v2", idStrings(written), workspaceId))
                .as("and so does the delta's anchor")
                .isEqualTo(written.size());
    }

    // --- the wrap's DDL (the cutover's half; the mutation routing is OPIK-7799's) --------------------------------

    /**
     * The gapless wrap and its reversal — the cutover's side of a change whose product side ships separately. Since
     * OPIK-7799 the wrap is reachable, and {@code SpansDistributedWrapMutationTest} proves that span mutations route to
     * {@code spans_local} under it. What is asserted here is the topology that test assumes: that the multi-target
     * {@code RENAME} leaves {@code spans} a {@code Distributed} wrapper over the shard, and that {@code --unwrap-only}
     * puts the successor back with its post-wrap writes intact.
     *
     * <p>What is asserted is the property the wrap claims: the {@code Distributed} wrapper reads the shard
     * transparently on one shard, and un-wrapping restores the successor with its post-cutover writes still live.
     */
    @Test
    void wrapReadsTransparentlyAndUnwrapRestoresTheSuccessor() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        var spans = mintIdsInWeek(0, 5);
        seedSpans(spans, workspaceId, projectId);
        var backfillStart = nowMicros();
        backfillWeek(0);
        deltaInsert(backfillStart);
        exchangeTables();
        wrapInDistributed();

        assertThat(isDistributed("spans"))
                .as("`spans` is the Distributed wrapper after the wrap")
                .isTrue();
        assertThat(liveCount("spans", idStrings(spans), workspaceId))
                .as("and reads through it return the shard's rows transparently on one shard")
                .isEqualTo(spans.size());

        // A post-wrap write, to prove the un-wrap keeps it — the contrast with stage C, which would discard it.
        var postWrap = mintIdsAt(1, Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros())));
        insertSuccessorSpan(postWrap.getFirst(), workspaceId, projectId, "post-wrap", "spans_local");

        unwrap();

        assertThat(isDistributed("spans"))
                .as("the un-wrap leaves `spans` the partitioned successor again")
                .isFalse();
        assertThat(columnType("spans", "end_time"))
                .as("...the SUCCESSOR, not the original — the un-wrap reverses sharding only")
                .doesNotStartWith("Nullable");
        assertThat(liveCount("spans", idStrings(postWrap), workspaceId))
                .as("and post-wrap writes are still live, which is the whole reason --unwrap-only exists beside stage C")
                .isEqualTo(1L);
        assertThat(tableExists("spans_dist_old"))
                .as("the data-less ex-wrapper is dropped, not left to block the next un-wrap")
                .isFalse();
    }

    // --- cutover steps (mirror the runbook SQL) ------------------------------------------------------------------

    /**
     * The backfill (000001 {@code backfill}): one {@code created_at} window, one statement. The partition cap is pinned
     * at the driver's default for the same reason {@code TracesLocalV2CutoverTest} pins its own — it is a correctness
     * gate, not pacing, and a value below what a block spans aborts the INSERT outright.
     */
    private void backfillWeek(int week) {
        runBackfill(week, COPIED_SELECT, "");
    }

    /** {@link #backfillWeek(int)} with the session put WEST of UTC; see the timezone test's Javadoc. */
    private void backfillWeekWestwardSession(int week) {
        runBackfill(week, COPIED_SELECT, ", session_timezone = 'America/New_York'");
    }

    /**
     * The negative control for {@link #parentPoisonValueIsNormalizedAndAnUnguardedCopyThrows()}: the single-pass
     * projection with the {@code parent_span_id} length guard REMOVED, so a 40-byte value reaches the
     * {@code FixedString(36)} destination directly.
     */
    private void backfillWeekWithUnguardedParent(int week) {
        var unguarded = COPIED_SELECT.replace(
                """
                        if(length(parent_span_id) = 36, toFixedString(parent_span_id, 36), toFixedString('', 36)) \
                        AS parent_span_id""",
                "toFixedString(parent_span_id, 36) AS parent_span_id");
        runBackfill(week, unguarded, "");
    }

    /**
     * One backfill statement. {@code projection} is normally {@link #COPIED_SELECT} and is parameterised only so the
     * unguarded negative control can exist.
     */
    private void runBackfill(int week, String projection, String extraSettings) {
        var weekLo = ClickHouseDateTimeFormat.formatMicros(weekInstant(week, 0));
        var weekHi = ClickHouseDateTimeFormat.formatMicros(weekInstant(week + 1, 0));
        execute("""
                INSERT INTO spans_local_v2 (
                %s
                )
                SELECT
                %s
                FROM spans
                WHERE created_at >= toDateTime64(:week_lo, 9, 'UTC')
                  AND created_at < toDateTime64(:week_hi, 9, 'UTC')
                SETTINGS max_insert_block_size = 1048576,
                         min_insert_block_size_bytes = 268435456,
                         max_partitions_per_insert_block = 20000%s
                """.formatted(COPIED_COLUMNS, projection, extraSettings),
                statement -> statement.bind("week_lo", weekLo).bind("week_hi", weekHi));
    }

    /**
     * The delta-insert: re-copy every row written during the backfill window. Anchored on
     * {@code created_at OR last_updated_at >= backfill_start} so it is complete regardless of the client-supplied
     * {@code last_updated_at} on the batch-ingest path.
     *
     * <p>NOT split into two arms, unlike the backfill, and the shipped driver does not split it either: a delta has no
     * {@code created_at} window, so there is no finite honest-{@code id_at} band to split on — and its
     * {@code last_updated_at} arm exists precisely to re-copy updates to OLD rows, which is the arm that carries the
     * far-future ids. So it always runs at the OUTLIER settings.
     */
    private void deltaInsert(String backfillStart) {
        deltaInsert(backfillStart, "");
    }

    /** {@link #deltaInsert(String)} with the session put WEST of UTC. */
    private void deltaInsertWestwardSession(String backfillStart) {
        deltaInsert(backfillStart, ", session_timezone = 'America/New_York'");
    }

    private void deltaInsert(String backfillStart, String extraSettings) {
        execute("""
                INSERT INTO spans_local_v2 (
                %s
                )
                SELECT
                %s
                FROM spans
                WHERE created_at >= toDateTime64(:backfill_start, 6, 'UTC')
                   OR last_updated_at >= toDateTime64(:backfill_start, 6, 'UTC')
                SETTINGS max_insert_block_size = 65536,
                         min_insert_block_size_bytes = 33554432,
                         max_partitions_per_insert_block = 20000%s
                """.formatted(COPIED_COLUMNS, COPIED_SELECT, extraSettings),
                statement -> statement.bind("backfill_start", backfillStart));
    }

    /**
     * Reads the bridge for the cutover window and removes the captured deletes from the destination in a single
     * mutation (mirrors 000002). Single full-key branch on the BRIDGE's key {@code (workspace_id, project_id, id)} —
     * which on this table is not a primary-key prefix, since {@code trace_id} sits between {@code project_id} and
     * {@code id}, and the bridge records no {@code trace_id}. The branch also requires the id is NOT currently live on
     * the source (the resurrection guard), so a deleted-then-recreated id is not dropped. Returns the wall time.
     */
    private long replayDeletions(String backfillStart) {
        var start = System.nanoTime();
        execute("""
                DELETE FROM spans_local_v2
                WHERE (
                    (workspace_id, project_id, id) IN (
                        SELECT
                            workspace_id,
                            toFixedString(project_id, 36),
                            toFixedString(deleted_id, 36)
                        FROM deletion_events_local
                        WHERE source_table = 'spans'
                          AND event_time >= toDateTime64(:backfill_start, 6, 'UTC')
                          AND project_id != ''
                          AND length(project_id) = 36
                          AND length(deleted_id) = 36
                    )
                    AND (workspace_id, project_id, id) NOT IN (
                        SELECT
                            workspace_id,
                            project_id,
                            id
                        FROM spans
                        WHERE id IN (
                            SELECT toFixedString(deleted_id, 36)
                            FROM deletion_events_local
                            WHERE source_table = 'spans'
                              AND event_time >= toDateTime64(:backfill_start, 6, 'UTC')
                              AND length(deleted_id) = 36
                        )
                    )
                )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """, statement -> statement.bind("backfill_start", backfillStart));
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /** One forward reconciliation pass: the sweep, then the post-swap replay so bridged deletes win over it. */
    private void reconcileForward(String liveTable, String gapStart, String swapDone) {
        forwardSweep(liveTable, gapStart, swapDone);
        postSwapDeletionReplay(liveTable, gapStart, swapDone);
    }

    /**
     * The POST-SWAP forward sweep (000006 {@code forward-sweep}): copy the gap window out of the FROZEN
     * {@code spans_pre_cutover_backup} into the live successor. The source is the old original, so the projection is
     * the backfill's — including the {@code parent_span_id} length guard, which matters here for the same reason and at
     * the worst moment: an abort would land immediately after the swap, with the gap still open.
     */
    private void forwardSweep(String liveTable, String gapStart, String swapDone) {
        execute("""
                INSERT INTO %s (
                %s
                )
                SELECT
                %s
                FROM spans_pre_cutover_backup
                WHERE (created_at >= toDateTime64(:gap_start, 6, 'UTC')
                    OR last_updated_at >= toDateTime64(:gap_start, 6, 'UTC'))
                  AND (workspace_id, project_id, id) NOT IN (
                      SELECT
                          workspace_id,
                          toFixedString(project_id, 36),
                          toFixedString(deleted_id, 36)
                      FROM deletion_events_local
                      WHERE source_table = 'spans'
                        AND event_time >= toDateTime64(:swap_done, 6, 'UTC')
                        AND project_id != ''
                        AND length(project_id) = 36
                        AND length(deleted_id) = 36
                  )
                SETTINGS max_partitions_per_insert_block = 20000,
                         min_insert_block_size_bytes = 33554432
                """.formatted(liveTable, COPIED_COLUMNS, COPIED_SELECT),
                statement -> statement.bind("gap_start", gapStart).bind("swap_done", swapDone));
    }

    /**
     * The POST-SWAP forward deletion replay (000006 {@code forward-deletion-replay}), run right after the sweep so
     * bridged deletes win over what it re-inserted. A separate statement from {@link #replayDeletions(String)}: it
     * masks rows on the LIVE table and reads its resurrection guard from the FROZEN backup, which is race-free where a
     * live source cannot be — and it carries a third arm, the staleness scope, that has no pre-swap counterpart.
     */
    private void postSwapDeletionReplay(String liveTable, String gapStart, String swapDone) {
        execute("""
                DELETE FROM %s
                WHERE created_at      <  toDateTime64(:swap_done, 6, 'UTC')
                  AND last_updated_at <  toDateTime64(:swap_done, 6, 'UTC')
                  AND (workspace_id, project_id, id) IN (
                      SELECT
                          workspace_id,
                          toFixedString(project_id, 36),
                          toFixedString(deleted_id, 36)
                      FROM deletion_events_local
                      WHERE source_table = 'spans'
                        AND event_time >= toDateTime64(:gap_start, 6, 'UTC')
                        AND project_id != ''
                        AND length(project_id) = 36
                        AND length(deleted_id) = 36
                  )
                  AND (workspace_id, project_id, id) NOT IN (
                      SELECT
                          workspace_id,
                          project_id,
                          id
                      FROM spans_pre_cutover_backup
                      WHERE id IN (
                          SELECT toFixedString(deleted_id, 36)
                          FROM deletion_events_local
                          WHERE source_table = 'spans'
                            AND event_time >= toDateTime64(:gap_start, 6, 'UTC')
                            AND length(deleted_id) = 36
                      )
                  )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """.formatted(liveTable),
                statement -> statement.bind("gap_start", gapStart).bind("swap_done", swapDone));
    }

    /**
     * The negative control for {@link #postSwapReplayDoesNotMaskASpanReCreatedAfterTheSwap()}: the post-swap replay
     * with ARM 3 — the per-row staleness scope — removed. Everything else is identical, so what it demonstrates is
     * attributable to that arm alone.
     */
    private void postSwapDeletionReplayWithoutStalenessScope(String liveTable, String gapStart) {
        execute("""
                DELETE FROM %s
                WHERE (workspace_id, project_id, id) IN (
                      SELECT
                          workspace_id,
                          toFixedString(project_id, 36),
                          toFixedString(deleted_id, 36)
                      FROM deletion_events_local
                      WHERE source_table = 'spans'
                        AND event_time >= toDateTime64(:gap_start, 6, 'UTC')
                        AND project_id != ''
                        AND length(project_id) = 36
                        AND length(deleted_id) = 36
                  )
                  AND (workspace_id, project_id, id) NOT IN (
                      SELECT
                          workspace_id,
                          project_id,
                          id
                      FROM spans_pre_cutover_backup
                      WHERE id IN (
                          SELECT toFixedString(deleted_id, 36)
                          FROM deletion_events_local
                          WHERE source_table = 'spans'
                            AND event_time >= toDateTime64(:gap_start, 6, 'UTC')
                            AND length(deleted_id) = 36
                      )
                  )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """.formatted(liveTable),
                statement -> statement.bind("gap_start", gapStart));
    }

    /**
     * The REVERSE sweep (000006 {@code reverse-sweep}): re-import the post-cutover writes a promote made non-live, out
     * of {@code spans_post_rollback_backup} and back into the restored original.
     *
     * <p>Three denormalizations, and all three are load-bearing: the epoch/NaN sentinels back to {@code NULL} (or the
     * original's MATERIALIZED {@code duration}, which guards {@code end_time IS NOT NULL} and knows nothing of the
     * epoch, recomputes a large NEGATIVE value), and {@code parent_span_id}'s {@code FixedString(36)} back to a
     * {@code String} (or every re-imported root span reads as a child — see
     * {@link #reverseSweepRestoresTheEmptyParentRatherThanNulPadding()}).
     *
     * <p>The epoch literal pins {@code 'UTC'} here and deliberately does not in the forward direction: forward, the
     * sentinel being WRITTEN has to agree with the successor's own unpinned DEFAULT and duration expression; reverse,
     * the sentinel being READ was written by the backend as an absolute {@code Instant.EPOCH}.
     */
    private void reverseSweep(String gapStart, String swapDone) {
        execute("""
                INSERT INTO spans (
                %s
                )
                SELECT
                    id,
                    workspace_id,
                    project_id,
                    trace_id,
                    CAST(parent_span_id AS String) AS parent_span_id,
                    name,
                    type,
                    start_time,
                    nullIf(end_time, toDateTime64('1970-01-01 00:00:00', 6, 'UTC')) AS end_time,
                    input,
                    output,
                    metadata,
                    tags,
                    usage,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    model,
                    provider,
                    total_estimated_cost,
                    total_estimated_cost_version,
                    error_info,
                    truncation_threshold,
                    input_slim,
                    output_slim,
                    if(isNaN(ttft), NULL, ttft) AS ttft,
                    source,
                    environment
                FROM spans_post_rollback_backup
                WHERE (created_at >= toDateTime64(:gap_start, 6, 'UTC')
                    OR last_updated_at >= toDateTime64(:gap_start, 6, 'UTC'))
                  AND (workspace_id, project_id, id) NOT IN (
                      SELECT
                          workspace_id,
                          toFixedString(project_id, 36),
                          toFixedString(deleted_id, 36)
                      FROM deletion_events_local
                      WHERE source_table = 'spans'
                        AND event_time >= toDateTime64(:swap_done, 6, 'UTC')
                        AND project_id != ''
                        AND length(project_id) = 36
                        AND length(deleted_id) = 36
                  )
                SETTINGS max_partitions_per_insert_block = 20000,
                         min_insert_block_size_bytes = 33554432
                """.formatted(COPIED_COLUMNS),
                statement -> statement.bind("gap_start", gapStart).bind("swap_done", swapDone));
    }

    private void exchangeTables() {
        execute("EXCHANGE TABLES spans AND spans_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        execute("RENAME TABLE spans_local_v2 TO spans_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
        });
    }

    /**
     * Gapless wrap (000003 wrap block): build the {@code Distributed} wrapper under a temp name first (its
     * {@code spans_local} target need not exist yet), then one atomic multi-target {@code RENAME} rotates the data to
     * {@code spans_local} and the wrapper into {@code spans} (the name freed by the first clause), so {@code spans} is
     * never absent on a node.
     */
    private void wrapInDistributed() {
        execute("""
                CREATE TABLE spans_dist ON CLUSTER '{cluster}' AS spans
                ENGINE = Distributed('{cluster}', '%s', 'spans_local', sipHash64(project_id))
                """.formatted(DATABASE_NAME), _ -> {
        });
        execute("""
                RENAME TABLE
                    spans TO spans_local,
                    spans_dist TO spans
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
    }

    /** Un-wrap (000004_rollback_unwrap): rotate the data-less wrapper out and the successor back into `spans`. */
    private void unwrap() {
        execute("""
                RENAME TABLE
                    spans TO spans_dist_old,
                    spans_local TO spans
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
        execute("DROP TABLE IF EXISTS spans_dist_old ON CLUSTER '{cluster}' SYNC", _ -> {
        });
    }

    /**
     * Rollback stage B (000004_rollback_stage_b): a single atomic multi-target RENAME rotates both names back — the
     * successor is parked as {@code spans_post_rollback_backup} (a retained backup, distinct from the disposable
     * {@code spans_local_v2} shadow) and the original returns to {@code spans}.
     */
    private void rollbackExchangeBack() {
        execute("""
                RENAME TABLE
                    spans TO spans_post_rollback_backup,
                    spans_pre_cutover_backup TO spans
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
    }

    /**
     * The reverse deletion replay (000004_rollback_reverse_replay): re-apply the deletes that fired on the successor
     * since {@code cutover_start} onto the restored original. Deliberately NO resurrection guard — rollback abandons
     * post-cutover writes while still honouring post-cutover deletes, and {@code spans} here is the RESTORED ORIGINAL,
     * where a bridged id is present as its pre-cutover version. A liveness guard would spare it and thereby UNDO the
     * user's delete.
     */
    private void reverseReplay(String cutoverStart) {
        execute("""
                DELETE FROM spans
                WHERE (workspace_id, project_id, id) IN (
                    SELECT
                        workspace_id,
                        toFixedString(project_id, 36),
                        toFixedString(deleted_id, 36)
                    FROM deletion_events_local
                    WHERE source_table = 'spans'
                      AND event_time >= toDateTime64(:cutover_start, 6, 'UTC')
                      AND project_id != ''
                      AND length(project_id) = 36
                      AND length(deleted_id) = 36
                )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """, statement -> statement.bind("cutover_start", cutoverStart));
    }

    // --- seeding / mutation helpers ------------------------------------------------------------------------------

    private void seedSpans(List<SeededSpan> spans, String workspaceId, UUID projectId) {
        insertRows(spans, workspaceId, projectId, "seed", SeededSpan::createdAt);
    }

    private void seedSpansWithParent(List<SeededSpan> spans, String workspaceId, UUID projectId, String parentSpanId) {
        insertRowsWithParent(spans, workspaceId, projectId, "seed", SeededSpan::createdAt, parentSpanId);
    }

    private void insertRows(List<SeededSpan> spans, String workspaceId, UUID projectId, String name,
            Function<SeededSpan, Instant> lastUpdatedAt) {
        insertRowsWithParent(spans, workspaceId, projectId, name, lastUpdatedAt, "");
    }

    /**
     * Batch-insert rows following {@code SpanDAO}'s batch-insert shape: {@code created_at} is the row's minted time,
     * {@code last_updated_at} is whatever {@code lastUpdatedAt} yields (server-now for upserts, a backdated stamp to
     * exercise the delta's {@code created_at} arm). {@code parent_span_id} is a parameter because it is the one column
     * whose CONTENT changes the cutover's behaviour — the poison value and the two-parents case both go through here.
     */
    private void insertRowsWithParent(List<SeededSpan> spans, String workspaceId, UUID projectId, String name,
            Function<SeededSpan, Instant> lastUpdatedAt, String parentSpanId) {
        var sql = TemplateUtils.getBatchSql("""
                INSERT INTO spans (
                    id,
                    workspace_id,
                    project_id,
                    trace_id,
                    parent_span_id,
                    name,
                    created_at,
                    last_updated_at
                )
                FORMAT Values
                    <items:{item |
                        (
                            :id<item.index>,
                            :workspace_id,
                            :project_id,
                            :trace_id<item.index>,
                            :parent_span_id,
                            :name,
                            :created_at<item.index>,
                            :last_updated_at<item.index>
                        )
                        <if(item.hasNext)>,<endif>
                    }>
                ;
                """, spans.size()).render();
        execute(sql, statement -> {
            statement.bind("workspace_id", workspaceId).bind("project_id", projectId).bind("name", name)
                    .bind("parent_span_id", parentSpanId);
            for (int i = 0; i < spans.size(); i++) {
                statement.bind("id" + i, spans.get(i).id().toString())
                        .bind("trace_id" + i, spans.get(i).traceId().toString())
                        .bind("created_at" + i, ClickHouseDateTimeFormat.formatMicros(spans.get(i).createdAt()))
                        .bind("last_updated_at" + i,
                                ClickHouseDateTimeFormat.formatMicros(lastUpdatedAt.apply(spans.get(i))));
            }
        });
    }

    /** A row written directly into the SUCCESSOR schema — a post-cutover write, or one into the wrapped shard. */
    private void insertSuccessorSpan(SeededSpan span, String workspaceId, UUID projectId, String name) {
        insertSuccessorSpan(span, workspaceId, projectId, name, "spans");
    }

    private void insertSuccessorSpan(SeededSpan span, String workspaceId, UUID projectId, String name, String table) {
        execute("""
                INSERT INTO %s (id, workspace_id, project_id, trace_id, name, created_at, last_updated_at)
                VALUES (:id, :workspace_id, :project_id, :trace_id, :name, now64(6), now64(6))
                """.formatted(table), statement -> statement
                .bind("id", span.id().toString())
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("trace_id", span.traceId().toString())
                .bind("name", name));
    }

    /**
     * As {@link #insertSuccessorSpan}, with {@code last_updated_at} stamped explicitly. The pair to
     * {@link #insertBackupSpan}: planting two rows for one key at the SAME version is the only way to reach
     * {@code payload_mismatch_keys}, and planting one either side of another's version is how the mismatch resolver's
     * two verdicts are built.
     */
    private void insertSuccessorSpanAt(SeededSpan span, String workspaceId, UUID projectId, String name, Instant at) {
        insertSuccessorSpanAt(span, workspaceId, projectId, name, at, "spans");
    }

    private void insertSuccessorSpanAt(SeededSpan span, String workspaceId, UUID projectId, String name, Instant at,
            String table) {
        execute("""
                INSERT INTO %s (id, workspace_id, project_id, trace_id, name, created_at, last_updated_at)
                VALUES (:id, :workspace_id, :project_id, :trace_id, :name,
                        toDateTime64(:created_at, 6), toDateTime64(:last_updated_at, 6))
                """.formatted(table), statement -> statement
                .bind("id", span.id().toString())
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("trace_id", span.traceId().toString())
                .bind("name", name)
                .bind("created_at", ClickHouseDateTimeFormat.formatMicros(span.createdAt()))
                .bind("last_updated_at", ClickHouseDateTimeFormat.formatMicros(at)));
    }

    /** A row planted directly in the PARKED backup, to put it in a state a gap-window write would have left. */
    private void insertBackupSpan(SeededSpan span, String workspaceId, UUID projectId, String name, Instant at) {
        execute("""
                INSERT INTO spans_pre_cutover_backup
                    (id, workspace_id, project_id, trace_id, name, created_at, last_updated_at)
                VALUES (:id, :workspace_id, :project_id, :trace_id, :name,
                        toDateTime64(:created_at, 9), toDateTime64(:last_updated_at, 6))
                """, statement -> statement
                .bind("id", span.id().toString())
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId)
                .bind("trace_id", span.traceId().toString())
                .bind("name", name)
                .bind("created_at", ClickHouseDateTimeFormat.formatNanos(at))
                .bind("last_updated_at", ClickHouseDateTimeFormat.formatMicros(at)));
    }

    /**
     * Seeds a small cohort with EVERY migrated column populated with distinct, varied values — at nanosecond
     * {@code created_at} precision, and a share of NULL {@code end_time} / {@code ttft}. The fingerprint is
     * workspace-scoped, so these rows make it sensitive to every column and to the ns-to-us truncation: an all-default
     * row would hash-match on both sides even if the copy dropped a column. Inline literals (not binds) keep
     * array/map/enum/NULL formatting reliable.
     */
    private List<String> seedFidelityCohort(String workspaceId, UUID projectId) {
        var ids = new ArrayList<String>();
        var rows = new ArrayList<String>();
        int n = SEED_WEEKS * 3;
        for (int i = 0; i < n; i++) {
            var createdAt = weekInstant(i % SEED_WEEKS, i + 1).plusNanos(i * 137L + 3); // sub-microsecond ns remainder
            var id = ID_GENERATOR.generateId(createdAt).toString();
            ids.add(id);
            var createdNs = ClickHouseDateTimeFormat.formatNanos(createdAt);
            var endTime = (i % 3 == 0)
                    ? "NULL"
                    : "toDateTime64('%s', 9)"
                            .formatted(ClickHouseDateTimeFormat.formatNanos(createdAt.plusMillis(50L + i)));
            // Weighted toward NULL, unlike the traces cohort's 1-in-4: ttft is a per-LLM-call measurement and most
            // spans are not LLM calls, which is the distribution the rollback's sentinel repair has to face.
            var ttft = (i % 4 == 0) ? String.valueOf(0.01 * (i + 1)) : "NULL";
            var errorInfo = (i % 7 == 0) ? "{\"type\":\"Err%d\"}".formatted(i) : "";
            // A root span for every third row, a child otherwise — so the empty-sentinel arm of the parent_span_id
            // normalization is exercised alongside real 36-char values.
            var parent = (i % 3 == 0) ? "" : ID_GENERATOR.generateId(createdAt).toString();
            rows.add("""
                    ('%s','%s','%s','%s','%s','seed-fidelity','%s',toDateTime64('%s', 9),%s,'in-%d','out-%d',\
                    '{"model":"m%d","n":%d}',['tag%d','g%d'],\
                    map('prompt_tokens', toInt32(%d), 'total_tokens', toInt32(%d)),\
                    toDateTime64('%s', 9),toDateTime64('%s', 6),'user%d','user%d','model-%d','provider-%d',\
                    toDecimal128('%s', 12),'v%d','%s',%d,'slim-in-%d','slim-out-%d',%s,'%s','%s')"""
                    .formatted(
                            id, workspaceId, projectId, ID_GENERATOR.generateId(createdAt), parent,
                            FIDELITY_TYPES[i % FIDELITY_TYPES.length],
                            createdNs, // start_time
                            endTime,
                            i, i, // input, output
                            i, i, // metadata
                            i, i % 4, // tags
                            // usage: always non-empty, and always explicitly Int32. The empty-map arm of the hash is
                            // exercised by the plain seeded rows, which take the column's own map() default, so there
                            // is nothing to gain here from a literal whose inferred type would have to be converted.
                            100 + i, 200 + i * 3,
                            createdNs, // created_at (ns)
                            ClickHouseDateTimeFormat.formatMicros(createdAt), // last_updated_at (us)
                            i % 5, (i + 1) % 5, // created_by, last_updated_by
                            i % 3, i % 2, // model, provider
                            // A string literal, not a float: toDecimal128 of a Float64 goes through binary floating
                            // point and would not round-trip the value the fingerprint then hashes as text.
                            String.format("%.6f", 0.001 * i),
                            i % 2, // total_estimated_cost_version
                            errorInfo,
                            10001 + (i % 2) * 10000, // truncation_threshold
                            i, i, // input_slim, output_slim
                            ttft,
                            FIDELITY_SOURCES[i % FIDELITY_SOURCES.length],
                            FIDELITY_ENVIRONMENTS[i % FIDELITY_ENVIRONMENTS.length]));
        }
        execute("""
                INSERT INTO spans (%s) VALUES %s
                """.formatted(COPIED_COLUMNS.replace("\n", " "), String.join(",\n", rows)), _ -> {
        });
        return ids;
    }

    /**
     * The lightweight delete {@code SpanDAO.DELETE_BY_IDS} issues — workspace- and id-scoped, with no {@code trace_id}.
     * {@code mutations_sync} is not set by the product, but IS set here: a test that raced its own mutation would be
     * flaky rather than wrong, and the property under test is never the mutation's asynchrony.
     */
    private void lightweightDelete(Set<String> ids, String workspaceId) {
        execute("""
                DELETE FROM spans
                WHERE workspace_id = :workspace_id
                  AND id IN :ids
                SETTINGS lightweight_deletes_sync = 2
                """,
                statement -> statement.bind("workspace_id", workspaceId).bind("ids", ids));
    }

    /** As above, scoped to one project — the shape the cascade actually issues. */
    private void lightweightDeleteScoped(Set<String> ids, String workspaceId, UUID projectId) {
        execute("""
                DELETE FROM spans
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND id IN :ids
                SETTINGS lightweight_deletes_sync = 2
                """,
                statement -> statement
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId)
                        .bind("ids", ids));
    }

    /**
     * Batch INSERT into the bridge, mirroring {@code DeletionEventDAO}'s write shape with
     * {@code source_table = 'spans'}. {@code projectId} is the real owning project of each deleted span: the cascade
     * takes it from the {@code TracesDeleted} event, which since OPIK-7483 is only ever emitted per resolved project,
     * so no span deletion event is ever bridged project-less.
     */
    private void recordDeletionEvents(Set<String> ids, String workspaceId, String projectId, String reason) {
        var idList = List.copyOf(ids);
        var sql = TemplateUtils.getBatchSql("""
                INSERT INTO deletion_events_local (
                    source_table,
                    workspace_id,
                    project_id,
                    deleted_id,
                    deletion_reason
                )
                FORMAT Values
                    <items:{item |
                        (
                            'spans',
                            :workspace_id,
                            :project_id,
                            :deleted_id<item.index>,
                            :reason
                        )
                        <if(item.hasNext)>,<endif>
                    }>
                ;
                """, idList.size()).render();
        execute(sql, statement -> {
            statement.bind("workspace_id", workspaceId).bind("project_id", projectId).bind("reason", reason);
            for (int i = 0; i < idList.size(); i++) {
                statement.bind("deleted_id" + i, idList.get(i));
            }
        });
    }

    // --- query helpers -------------------------------------------------------------------------------------------

    /**
     * The reverse-replay postcondition, mirroring {@code 000004_rollback_verify_replay.sql} — same key, same
     * {@code toFixedString(36)} casts, same window and length guards, same aggregate, and the same database
     * qualification on both tables (this is the only statement in the runbook that reads through
     * {@code clusterAllReplicas}, so qualifying both keeps it correct regardless of the session's default database).
     */
    private long verifyReplayPostcondition(String cutoverStart) {
        return scalar("""
                SELECT uniqExact(workspace_id, project_id, id) AS c
                FROM clusterAllReplicas('{cluster}', %s.spans)
                WHERE (workspace_id, project_id, id) IN (
                    SELECT
                        workspace_id,
                        toFixedString(project_id, 36),
                        toFixedString(deleted_id, 36)
                    FROM %s.deletion_events_local
                    WHERE source_table = 'spans'
                      AND event_time >= toDateTime64(:cutover_start, 6, 'UTC')
                      AND project_id != ''
                      AND length(project_id) = 36
                      AND length(deleted_id) = 36
                )
                """.formatted(DATABASE_NAME, DATABASE_NAME),
                statement -> statement.bind("cutover_start", cutoverStart));
    }

    /**
     * 000006's {@code reverse-usage-range-check}: rows the reverse sweep would import whose {@code usage} carries a
     * value outside {@code Int32}.
     * <p>
     * Reads {@code spans_local_v2} where the shipped block reads {@code spans_post_rollback_backup}: the two carry the
     * same schema, and planting the overflow in the successor shadow avoids staging a whole rollback for one predicate.
     * <p>
     * Carries {@code reverse-sweep}'s post-swap delete exclusion, because the shipped block does: the precheck must
     * count only what the sweep would actually insert, or it refuses a run over a row the narrowing never touches.
     */
    private long usageOutOfInt32Range(String gapStart, String swapDone) {
        return scalar("""
                SELECT count() AS c
                FROM spans_local_v2
                WHERE (created_at >= toDateTime64(:gap_start, 6, 'UTC')
                    OR last_updated_at >= toDateTime64(:gap_start, 6, 'UTC'))
                  AND arrayExists(v -> v > 2147483647 OR v < -2147483648, mapValues(usage))
                  AND (workspace_id, project_id, id) NOT IN (
                      SELECT
                          workspace_id,
                          toFixedString(project_id, 36),
                          toFixedString(deleted_id, 36)
                      FROM deletion_events_local
                      WHERE source_table = 'spans'
                        AND event_time >= toDateTime64(:swap_done, 6, 'UTC')
                        AND project_id != ''
                        AND length(project_id) = 36
                        AND length(deleted_id) = 36
                  )
                """, statement -> statement
                .bind("gap_start", gapStart)
                .bind("swap_done", swapDone));
    }

    /** The four reconciliation counts, forward: the parked pre-cutover backup (OLD shape) against the live successor. */
    private ReconciliationCounts forwardCounts(String gapStart, String swapDone) {
        var parkedHash = rowHash(OLD_HASH_OVERRIDES);
        var liveHash = rowHash(NEW_HASH_OVERRIDES);
        var sql = """
                WITH
                    parked AS (
                        SELECT
                            key,
                            toUnixTimestamp64Micro(toDateTime64(max(last_updated_at), 6)) AS parked_version,
                            argMax(%s, last_updated_at) AS parked_fingerprint
                        FROM (
                            SELECT (workspace_id, project_id, trace_id, id) AS key, *
                            FROM spans_pre_cutover_backup FINAL
                            WHERE (created_at >= toDateTime64(:gap_start, 6, 'UTC')
                                OR last_updated_at >= toDateTime64(:gap_start, 6, 'UTC'))
                              AND (workspace_id, project_id, id) NOT IN (
                                  SELECT
                                      workspace_id,
                                      toFixedString(project_id, 36),
                                      toFixedString(deleted_id, 36)
                                  FROM deletion_events_local
                                  WHERE source_table = 'spans'
                                    AND event_time >= toDateTime64(:swap_done, 6, 'UTC')
                                    AND project_id != ''
                                    AND length(project_id) = 36
                                    AND length(deleted_id) = 36
                              )
                        )
                        GROUP BY key
                    ),
                    live AS (
                        SELECT
                            (workspace_id, project_id, trace_id, id) AS key,
                            toUnixTimestamp64Micro(last_updated_at) AS live_version,
                            %s AS live_fingerprint
                        FROM spans FINAL
                        WHERE (workspace_id, project_id, trace_id, id) IN (SELECT key FROM parked)
                    )
                SELECT
                    countIf(live_version IS NULL) AS missing_keys,
                    countIf(live_version IS NOT NULL AND live_version < parked_version) AS stale_keys,
                    countIf(live_version IS NOT NULL AND live_version = parked_version
                            AND live_fingerprint != parked_fingerprint) AS payload_mismatch_keys,
                    countIf(live_version IS NOT NULL AND live_version > parked_version) AS newer_keys
                FROM parked
                LEFT JOIN live USING (key)
                SETTINGS join_use_nulls = 1, use_skip_indexes_if_final = 1
                """.formatted(parkedHash, liveHash);
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("gap_start", gapStart)
                                .bind("swap_done", swapDone)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> ReconciliationCounts.builder()
                                .missing(row.get("missing_keys", Long.class))
                                .stale(row.get("stale_keys", Long.class))
                                .payloadMismatch(row.get("payload_mismatch_keys", Long.class))
                                .newer(row.get("newer_keys", Long.class))
                                .build()))))
                .block();
    }

    /**
     * The four reconciliation counts, REVERSE: the parked successor (NEW shape) against the restored original — which
     * is the mirror of {@link #forwardCounts}, with the {@code argMax} reduction moved to the LIVE side because that is
     * now the one carrying the source's wider sort key.
     *
     * <p>The exclusion arm is bounded by the gap anchor rather than by a separate swap-done value, matching the shipped
     * block: the reverse replay masks every key bridged since {@code cutover_start}, so a key deleted anywhere in that
     * range is legitimately absent from the live table and must not be counted as missing.
     */
    private ReconciliationCounts reverseCounts(String gapStart) {
        var parkedHash = rowHash(NEW_HASH_OVERRIDES);
        var liveHash = rowHash(OLD_HASH_OVERRIDES);
        var sql = """
                WITH
                    parked AS (
                        SELECT
                            (workspace_id, project_id, trace_id, id) AS key,
                            toUnixTimestamp64Micro(last_updated_at) AS parked_version,
                            %s AS parked_fingerprint
                        FROM spans_post_rollback_backup FINAL
                        WHERE (created_at >= toDateTime64(:gap_start, 6, 'UTC')
                            OR last_updated_at >= toDateTime64(:gap_start, 6, 'UTC'))
                          AND (workspace_id, project_id, id) NOT IN (
                              SELECT
                                  workspace_id,
                                  toFixedString(project_id, 36),
                                  toFixedString(deleted_id, 36)
                              FROM deletion_events_local
                              WHERE source_table = 'spans'
                                AND event_time >= toDateTime64(:gap_start, 6, 'UTC')
                                AND project_id != ''
                                AND length(project_id) = 36
                                AND length(deleted_id) = 36
                          )
                    ),
                    live AS (
                        SELECT
                            key,
                            toUnixTimestamp64Micro(toDateTime64(max(last_updated_at), 6)) AS live_version,
                            argMax(%s, last_updated_at) AS live_fingerprint
                        FROM (
                            SELECT (workspace_id, project_id, trace_id, id) AS key, *
                            FROM spans FINAL
                            WHERE (workspace_id, project_id, trace_id, id) IN (SELECT key FROM parked)
                        )
                        GROUP BY key
                    )
                SELECT
                    countIf(live_version IS NULL) AS missing_keys,
                    countIf(live_version IS NOT NULL AND live_version < parked_version) AS stale_keys,
                    countIf(live_version IS NOT NULL AND live_version = parked_version
                            AND live_fingerprint != parked_fingerprint) AS payload_mismatch_keys,
                    countIf(live_version IS NOT NULL AND live_version > parked_version) AS newer_keys
                FROM parked
                LEFT JOIN live USING (key)
                SETTINGS join_use_nulls = 1, use_skip_indexes_if_final = 1
                """.formatted(parkedHash, liveHash);
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql).bind("gap_start", gapStart).execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> ReconciliationCounts.builder()
                                .missing(row.get("missing_keys", Long.class))
                                .stale(row.get("stale_keys", Long.class))
                                .payloadMismatch(row.get("payload_mismatch_keys", Long.class))
                                .newer(row.get("newer_keys", Long.class))
                                .build()))))
                .block();
    }

    /**
     * 000006's {@code leak-check-forward}: captured deletes still live on the successor at a version the frozen backup
     * itself held. {@code apply_deleted_mask = 0} is what lets the backup side see rows the mask hides, and
     * {@code _row_exists = 1} restores mask-honoring on the live side by hand, since the setting is statement-wide —
     * without it, every key the replay correctly masked would read as live and the check would indict its own fix.
     *
     * <p>The key here is the BRIDGE's {@code (workspace_id, project_id, id)}, not the destination's dedup key, because
     * a deletion event carries no {@code trace_id}. The version comparison is what makes the verdict precise regardless.
     */
    private long leakCheckForward(String gapStart) {
        return scalar("""
                WITH
                    bridged AS (
                        SELECT
                            workspace_id,
                            toFixedString(project_id, 36) AS project_id,
                            toFixedString(deleted_id, 36) AS id
                        FROM deletion_events_local
                        WHERE source_table = 'spans'
                          AND event_time >= toDateTime64(:gap_start, 6, 'UTC')
                          AND project_id != ''
                          AND length(project_id) = 36
                          AND length(deleted_id) = 36
                    ),
                    deleted AS (
                        SELECT
                            (workspace_id, project_id, id) AS key,
                            toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)) AS version
                        FROM spans_pre_cutover_backup
                        WHERE _row_exists = 0
                          AND (workspace_id, project_id, id) IN (SELECT workspace_id, project_id, id FROM bridged)
                    )
                SELECT count() AS c
                FROM (
                    SELECT
                        (workspace_id, project_id, id) AS key,
                        toUnixTimestamp64Micro(last_updated_at) AS version
                    FROM spans FINAL
                    WHERE _row_exists = 1
                      AND (workspace_id, project_id, id) IN (SELECT workspace_id, project_id, id FROM bridged)
                ) AS live
                WHERE (live.key, live.version) IN (SELECT key, version FROM deleted)
                SETTINGS apply_deleted_mask = 0, use_skip_indexes_if_final = 1
                """, statement -> statement.bind("gap_start", gapStart));
    }

    /**
     * The {@code version-ties} block: keys whose newest {@code last_updated_at} is carried by more than one DISTINCT
     * row content. Grouped by the DESTINATION key on BOTH sides, which is what makes it catch the spans-only cause (one
     * span, two parents, one version) as well as the traces one. Distinct content rather than row count is essential —
     * the cutover legitimately puts several IDENTICAL rows at one version on the successor.
     */
    private long versionTies(String table, Shape shape, String workspaceId) {
        return scalar("""
                SELECT count() AS c
                FROM (
                    SELECT key, argMax(distinct_at_version, version) AS distinct_at_newest
                    FROM (
                        SELECT
                            (workspace_id, project_id, trace_id, id) AS key,
                            last_updated_at AS version,
                            uniqExact(%s) AS distinct_at_version
                        FROM %s
                        WHERE workspace_id = :workspace_id
                        GROUP BY key, version
                    )
                    GROUP BY key
                )
                WHERE distinct_at_newest > 1
                """.formatted(rowHash(shape == Shape.OLD ? OLD_HASH_OVERRIDES : NEW_HASH_OVERRIDES), table),
                statement -> statement.bind("workspace_id", workspaceId));
    }

    /**
     * The {@code confirm-keys} block: of the keys whose WINDOWED fingerprint differs, how many still differ once each
     * side's LIVE row is read without the window predicate. 0 means the window's difference was a superseded-version
     * artifact; above 0 means a real fidelity failure.
     */
    private long genuinelyDifferingKeys(String windowLo, String windowHi, String workspaceId) {
        return genuinelyDifferingKeys("spans", "spans_local_v2", windowLo, windowHi, workspaceId);
    }

    /**
     * The same compare across an arbitrary old/new pair, so it can be run in the POST-SWAP orientation
     * ({@code spans_pre_cutover_backup} vs the live {@code spans}) as well as the pre-swap one.
     */
    private long genuinelyDifferingKeys(String oldTable, String newTable, String windowLo, String windowHi,
            String workspaceId) {
        return scalar("""
                WITH
                    diff_keys AS (
                        SELECT key
                        FROM (
                            SELECT (workspace_id, project_id, trace_id, id) AS key,
                                   argMax(%1$s, last_updated_at) AS src_hash
                            FROM %3$s FINAL
                            WHERE workspace_id = :workspace_id
                              AND created_at >= toDateTime64(:window_lo, 9, 'UTC')
                              AND created_at <  toDateTime64(:window_hi, 9, 'UTC')
                            GROUP BY key
                        ) AS s
                        FULL OUTER JOIN (
                            SELECT (workspace_id, project_id, trace_id, id) AS key, %2$s AS dst_hash
                            FROM %4$s FINAL
                            WHERE workspace_id = :workspace_id
                              AND created_at >= toDateTime64(:window_lo, 6, 'UTC')
                              AND created_at <  toDateTime64(:window_hi, 6, 'UTC')
                        ) AS d USING (key)
                        WHERE src_hash != dst_hash OR src_hash IS NULL OR dst_hash IS NULL
                    ),
                    src_live AS (
                        SELECT (workspace_id, project_id, trace_id, id) AS key,
                               argMax(%1$s, last_updated_at) AS src_hash
                        FROM %3$s FINAL
                        WHERE (workspace_id, project_id, trace_id, id) IN (SELECT key FROM diff_keys)
                        GROUP BY key
                    ),
                    dst_live AS (
                        SELECT (workspace_id, project_id, trace_id, id) AS key, %2$s AS dst_hash
                        FROM %4$s FINAL
                        WHERE (workspace_id, project_id, trace_id, id) IN (SELECT key FROM diff_keys)
                    )
                SELECT count() AS c
                FROM src_live AS s
                FULL OUTER JOIN dst_live AS d USING (key)
                WHERE src_hash != dst_hash OR src_hash IS NULL OR dst_hash IS NULL
                SETTINGS join_use_nulls = 1, use_skip_indexes_if_final = 1
                """.formatted(rowHash(OLD_HASH_OVERRIDES), rowHash(NEW_HASH_OVERRIDES), oldTable, newTable),
                statement -> statement
                        .bind("workspace_id", workspaceId)
                        .bind("window_lo", windowLo)
                        .bind("window_hi", windowHi));
    }

    /** The rollback's sentinel repair (000004_rollback_sentinel_repair), restoring NULL on both denullified columns. */
    private void sentinelRepair(String windowFrom, String windowTo) {
        execute("""
                ALTER TABLE spans
                    UPDATE end_time = NULL
                        WHERE end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')
                          AND (   (created_at      >= toDateTime64(:from, 6, 'UTC')
                               AND created_at      <  toDateTime64(:to, 6, 'UTC'))
                               OR (last_updated_at >= toDateTime64(:from, 6, 'UTC')
                               AND last_updated_at <  toDateTime64(:to, 6, 'UTC'))),
                    UPDATE ttft = NULL
                        WHERE isNaN(ttft)
                          AND (   (created_at      >= toDateTime64(:from, 6, 'UTC')
                               AND created_at      <  toDateTime64(:to, 6, 'UTC'))
                               OR (last_updated_at >= toDateTime64(:from, 6, 'UTC')
                               AND last_updated_at <  toDateTime64(:to, 6, 'UTC')))
                SETTINGS mutations_sync = 2
                """, statement -> statement.bind("from", windowFrom).bind("to", windowTo));
    }

    /**
     * The four counts of 000004_rollback_verify_sentinels, workspace-scoped and without the cluster function the
     * shipped block uses — a single-node container has one replica, and that function is about reach, not arithmetic.
     */
    private SentinelCounts sentinelCounts(String windowFrom, String windowTo, String workspaceId) {
        var sql = """
                SELECT
                    uniqExactIf((workspace_id, project_id, id),
                                end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')) AS sentinel_end_time,
                    uniqExactIf((workspace_id, project_id, id), isNaN(ttft)) AS sentinel_ttft,
                    uniqExactIf((workspace_id, project_id, id),
                                duration < 0 AND end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC'))
                        AS negative_from_sentinel,
                    uniqExactIf((workspace_id, project_id, id), duration < 0 AND end_time IS NULL) AS stale_duration
                FROM spans
                WHERE workspace_id = :workspace_id
                  AND (   (created_at      >= toDateTime64(:from, 6, 'UTC')
                       AND created_at      <  toDateTime64(:to, 6, 'UTC'))
                       OR (last_updated_at >= toDateTime64(:from, 6, 'UTC')
                       AND last_updated_at <  toDateTime64(:to, 6, 'UTC')))
                """;
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(sql)
                .bind("workspace_id", workspaceId)
                .bind("from", windowFrom)
                .bind("to", windowTo)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> SentinelCounts.builder()
                        .sentinelEndTime(row.get("sentinel_end_time", Long.class))
                        .sentinelTtft(row.get("sentinel_ttft", Long.class))
                        .negativeFromSentinel(row.get("negative_from_sentinel", Long.class))
                        .staleDuration(row.get("stale_duration", Long.class))
                        .build()))))
                .block();
    }

    private long scalar(String sql, Consumer<Statement> binder) {
        return template
                .nonTransaction(connection -> {
                    var statement = connection.createStatement(sql);
                    binder.accept(statement);
                    return Mono.from(statement.execute())
                            .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("c", Long.class))));
                })
                .block();
    }

    /**
     * Ids whose WINNING version has a {@code created_at} inside a compare window — the count that goes to zero, and
     * makes the drill-down print the row as absent, when a re-create moves the winner past the window's upper bound.
     * {@code argMax} picks the winner explicitly rather than leaning on {@code FINAL}: a {@code created_at} predicate
     * under {@code FINAL} can exclude the part holding the winner and return a superseded row as though it were live,
     * which is the artifact {@code 000005}'s confirm-keys block exists to absorb.
     */
    private long rowsInsideWindow(String table, Set<String> ids, String workspaceId, String windowLo,
            String windowHi) {
        if (ids.isEmpty()) {
            return 0L;
        }
        return scalar("""
                SELECT uniqExactIf(id, created_at >= toDateTime64(:window_lo, 6, 'UTC')
                                       AND created_at < toDateTime64(:window_hi, 6, 'UTC')) AS c
                FROM (
                    SELECT id, argMax(created_at, last_updated_at) AS created_at
                    FROM %s
                    WHERE workspace_id = :workspace_id
                      AND id IN :ids
                    GROUP BY workspace_id, project_id, trace_id, id
                )
                """.formatted(table),
                statement -> statement.bind("workspace_id", workspaceId).bind("ids", ids)
                        .bind("window_lo", windowLo).bind("window_hi", windowHi));
    }

    /** Distinct live (mask-honored) ids from {@code table} within {@code ids}. */
    private long liveCount(String table, Set<String> ids, String workspaceId) {
        if (ids.isEmpty()) {
            return 0L;
        }
        return scalar("""
                SELECT uniqExact(id) AS c
                FROM %s FINAL
                WHERE workspace_id = :workspace_id
                  AND id IN :ids
                """.formatted(table),
                statement -> statement.bind("workspace_id", workspaceId).bind("ids", ids));
    }

    private long liveCountScoped(String table, Set<String> ids, String workspaceId, UUID projectId) {
        return scalar("""
                SELECT uniqExact(id) AS c
                FROM %s FINAL
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND id IN :ids
                """.formatted(table),
                statement -> statement
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId)
                        .bind("ids", ids));
    }

    /** Live ids on the destination, as a set — so an arm's output can be compared to an expected set exactly. */
    private Set<String> copiedIds(String workspaceId) {
        return template.stream(connection -> Flux.from(connection.createStatement("""
                SELECT DISTINCT id AS id
                FROM spans_local_v2 FINAL
                WHERE workspace_id = :workspace_id
                """)
                .bind("workspace_id", workspaceId)
                .execute())
                .flatMap(result -> result.map((row, ignored) -> row.get("id", String.class))))
                .collectList().block().stream().collect(Collectors.toUnmodifiableSet());
    }

    /** Distinct spans on the source, by the DESTINATION's dedup key — not the source's own, which is wider. */
    private long sourceLogicalRows(String workspaceId) {
        return scalar("""
                SELECT uniqExact(workspace_id, project_id, trace_id, id) AS c
                FROM spans
                WHERE workspace_id = :workspace_id
                """, statement -> statement.bind("workspace_id", workspaceId));
    }

    /** Live PHYSICAL rows on the source after {@code FINAL} — which collapses by the SOURCE's own, wider key. */
    private long sourcePhysicalLiveRows(String workspaceId) {
        return scalar("""
                SELECT count() AS c
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                """, statement -> statement.bind("workspace_id", workspaceId));
    }

    private long destinationLogicalRows(String workspaceId) {
        return scalar("""
                SELECT count() AS c
                FROM spans_local_v2 FINAL
                WHERE workspace_id = :workspace_id
                """, statement -> statement.bind("workspace_id", workspaceId));
    }

    private long destinationPhysicalRows(String workspaceId) {
        return scalar("""
                SELECT count() AS c
                FROM spans_local_v2
                WHERE workspace_id = :workspace_id
                """, statement -> statement.bind("workspace_id", workspaceId));
    }

    private String destinationPartitionId(UUID id, String workspaceId) {
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement("""
                                SELECT _partition_id AS partition_id
                                FROM spans_local_v2
                                WHERE workspace_id = :workspace_id
                                  AND id = :id
                                LIMIT 1
                                """)
                                .bind("workspace_id", workspaceId)
                                .bind("id", id.toString())
                                .execute())
                        .flatMap(result -> Mono
                                .from(result.map((row, ignored) -> row.get("partition_id", String.class)))))
                .block();
    }

    private Set<String> newestNames(String table, Set<String> ids, String workspaceId) {
        return template.stream(connection -> Flux.from(connection.createStatement("""
                SELECT name
                FROM %s FINAL
                WHERE workspace_id = :workspace_id
                  AND id IN :ids
                """.formatted(table))
                .bind("workspace_id", workspaceId)
                .bind("ids", ids)
                .execute())
                .flatMap(result -> result.map((row, ignored) -> row.get("name", String.class))))
                .collectList().block().stream().collect(Collectors.toUnmodifiableSet());
    }

    private String nowMicros() {
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(
                "SELECT toString(now64(6, 'UTC')) AS n")
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("n", String.class)))))
                .block();
    }

    /** Stored (physically materialized) columns of a table — excludes {@code MATERIALIZED} / {@code ALIAS} columns. */
    private Set<String> baseColumns(String table) {
        return columnNames(table, "default_kind NOT IN ('MATERIALIZED', 'ALIAS')");
    }

    /** MATERIALIZED (recomputed, not stored-from-insert) columns of a table. */
    private Set<String> materializedColumns(String table) {
        return columnNames(table, "default_kind = 'MATERIALIZED'");
    }

    private Set<String> columnNames(String table, String defaultKindPredicate) {
        var sql = """
                SELECT name
                FROM system.columns
                WHERE database = :db
                  AND table = :t
                  AND %s
                """.formatted(defaultKindPredicate);
        return template.stream(connection -> Flux.from(connection.createStatement(sql)
                .bind("db", DATABASE_NAME)
                .bind("t", table)
                .execute())
                .flatMap(result -> result.map((row, ignored) -> row.get("name", String.class))))
                .collectList().block().stream().collect(Collectors.toUnmodifiableSet());
    }

    private boolean isDistributed(String table) {
        return "Distributed".equals(tableEngine(table));
    }

    private String tableEngine(String table) {
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(
                "SELECT engine FROM system.tables WHERE database = :db AND name = :t")
                .bind("db", DATABASE_NAME)
                .bind("t", table)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("engine", String.class)))))
                .block();
    }

    private String columnType(String table, String column) {
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(
                "SELECT type FROM system.columns WHERE database = :db AND table = :t AND name = :c")
                .bind("db", DATABASE_NAME)
                .bind("t", table)
                .bind("c", column)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("type", String.class)))))
                .block();
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(template.nonTransaction(connection -> Mono.from(connection.createStatement(
                "SELECT count() FROM system.tables WHERE database = :db AND name = :t")
                .bind("db", DATABASE_NAME)
                .bind("t", table)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get(0, Long.class) > 0))))
                .block());
    }

    // --- fidelity fingerprint ------------------------------------------------------------------------------------

    /**
     * Order-independent (count, checksum) fingerprint of the deduped, mask-honored, normalized rows for a workspace.
     *
     * <p><b>The OLD side is reduced TWICE, and that asymmetry is the whole point.</b> {@code FINAL} collapses each
     * SOURCE key — which includes {@code parent_span_id} — and then
     * {@code argMax(<fingerprint>, last_updated_at) GROUP BY} the DESTINATION key collapses across it, picking the same
     * winner {@code ReplacingMergeTree} picked. The NEW side needs {@code FINAL} alone, its own key already being the
     * comparison key. Making both sides symmetric would report a faithful copy as a count mismatch on every span whose
     * parent was ever patched.
     *
     * <p>Where the two winners can still disagree is a version TIE, which {@link #versionTies} detects and which
     * {@code verify.sh} surfaces as {@code version_ties} counts on the differing window rather than deciding.
     */
    private Fingerprint fingerprint(String table, Shape shape, String workspaceId) {
        var hash = rowHash(shape == Shape.OLD ? OLD_HASH_OVERRIDES : NEW_HASH_OVERRIDES);
        var sql = shape == Shape.OLD
                ? """
                        SELECT count() AS c, sum(row_hash) AS h
                        FROM (
                            SELECT argMax(%s, last_updated_at) AS row_hash
                            FROM %s FINAL
                            WHERE workspace_id = :workspace_id
                            GROUP BY workspace_id, project_id, trace_id, id
                        )
                        """.formatted(hash, table)
                : """
                        SELECT count() AS c, sum(%s) AS h
                        FROM %s FINAL
                        WHERE workspace_id = :workspace_id
                        """.formatted(hash, table);
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(sql)
                .bind("workspace_id", workspaceId)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> Fingerprint.builder()
                        .count(row.get("c", Long.class))
                        .checksum(row.get("h", Long.class))
                        .build()))))
                .block();
    }

    /**
     * (count, checksum) over the DETERMINISTIC derived columns of the fidelity cohort — {@code id_at} (the partition
     * key), the three {@code *_length}s and {@code truncated_input} / {@code truncated_output}. Each is the same
     * MATERIALIZED expression over faithfully-copied base columns on both tables, so equal fingerprints prove the
     * successor's expressions did not drift.
     *
     * <p>{@code id_at} is wrapped in {@code toDateTime} because the source's is a 32-bit {@code DateTime} while the
     * successor's is a {@code DateTime64}: both are second precision, so the cast only unifies the column type. There
     * is no {@code output_keys} arm — that column is traces-only (migration 000044).
     */
    private Fingerprint derivedFingerprint(String table, String workspaceId) {
        var sql = """
                SELECT
                    count() AS c,
                    sum(cityHash64(
                        id,
                        toDateTime(id_at),
                        input_length,
                        output_length,
                        metadata_length,
                        truncated_input,
                        truncated_output)) AS h
                FROM %s FINAL
                WHERE workspace_id = :workspace_id
                  AND name = 'seed-fidelity'
                """.formatted(table);
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(sql)
                .bind("workspace_id", workspaceId)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> Fingerprint.builder()
                        .count(row.get("c", Long.class))
                        .checksum(row.get("h", Long.class))
                        .build()))))
                .block();
    }

    /**
     * Count of fidelity-cohort rows whose {@code duration} disagrees between source and destination beyond the intended
     * ns-to-us truncation. The source computes duration from nanosecond timestamps and is {@code NULL} when unset; the
     * successor computes it from the microsecond copy and is {@code NaN} when unset. The 1.5 us bound is deliberate:
     * truncating both the start and end timestamps can each shift the computed duration by up to ~1 us.
     */
    private long durationMismatches(String workspaceId) {
        return scalar("""
                SELECT count() AS c
                FROM (
                    SELECT id, duration AS d FROM spans FINAL
                    WHERE workspace_id = :workspace_id AND name = 'seed-fidelity'
                ) AS s
                INNER JOIN (
                    SELECT id, duration AS d FROM spans_local_v2 FINAL
                    WHERE workspace_id = :workspace_id AND name = 'seed-fidelity'
                ) AS t USING (id)
                WHERE NOT (
                    (isNaN(t.d) AND s.d IS NULL)
                    OR (NOT isNaN(t.d) AND s.d IS NOT NULL AND abs(s.d - t.d) <= 0.0015)
                )
                """, statement -> statement.bind("workspace_id", workspaceId));
    }

    /**
     * The per-row fidelity hash for a shape, generated from {@link #COPIED_COLUMNS} in order so every copied column is
     * hashed. Each column contributes its {@code overrides} expression, or the bare column name when no normalization
     * is needed. Argument order matches on both shapes (both iterate COPIED_COLUMNS), so a faithfully-migrated row
     * hashes identically under {@link #OLD_HASH_OVERRIDES} and {@link #NEW_HASH_OVERRIDES}.
     */
    private static String rowHash(Map<String, String> overrides) {
        var args = Arrays.stream(COPIED_COLUMNS.split(","))
                .map(String::trim)
                .filter(column -> !column.isEmpty())
                .map(column -> overrides.getOrDefault(column, column))
                .collect(Collectors.joining(",\n    "));
        return "cityHash64(\n    %s)".formatted(args);
    }

    // --- primitives ----------------------------------------------------------------------------------------------

    private void execute(String sql, Consumer<Statement> binder) {
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated())).then();
        }).block();
    }

    /** Spans spread across {@link #SEED_WEEKS} weekly partitions, {@code perWeek} in each. */
    private List<SeededSpan> mintIds(int perWeek) {
        var spans = new ArrayList<SeededSpan>();
        for (int week = 0; week < SEED_WEEKS; week++) {
            spans.addAll(mintIdsInWeek(week, perWeek));
        }
        return spans;
    }

    /** Spans in one weekly partition, with ids minted at their own {@code created_at} (the ordinary shape). */
    private List<SeededSpan> mintIdsInWeek(int week, int count) {
        var spans = new ArrayList<SeededSpan>();
        for (int i = 0; i < count; i++) {
            var createdAt = weekInstant(week, i + 1);
            spans.add(SeededSpan.builder()
                    .id(ID_GENERATOR.generateId(createdAt))
                    .traceId(ID_GENERATOR.generateId(createdAt))
                    .createdAt(createdAt)
                    .build());
        }
        return spans;
    }

    /** Ids minted "now" — used for rows written during the window, so their created_at is >= backfill_start. */
    private List<SeededSpan> mintIdsAt(int count, Instant createdAt) {
        var spans = new ArrayList<SeededSpan>();
        for (int i = 0; i < count; i++) {
            spans.add(SeededSpan.builder()
                    .id(ID_GENERATOR.generateId(createdAt))
                    .traceId(ID_GENERATOR.generateId(createdAt))
                    .createdAt(createdAt)
                    .build());
        }
        return spans;
    }

    /**
     * Spans whose {@code created_at} is real but whose id is minted at {@code idAt} — the litellm shape, where
     * {@code id_at != created_at}. This is the population the split's OUTLIER arm exists for.
     */
    private List<SeededSpan> spansWithIdAt(int count, Instant createdAt, Instant idAt) {
        var spans = new ArrayList<SeededSpan>();
        for (int i = 0; i < count; i++) {
            spans.add(SeededSpan.builder()
                    .id(ID_GENERATOR.generateId(idAt.plusSeconds(i)))
                    .traceId(ID_GENERATOR.generateId(createdAt))
                    .createdAt(createdAt.plusSeconds(i))
                    .build());
        }
        return spans;
    }

    /**
     * Spans whose id is a UUIDv4 rather than a v7. {@code UUIDv7ToDateTime} returns {@code 1970-01-01} for those — no
     * throw — so they land in the EPOCH week, the far-PAST half of the outlier population. Migration 000115's header
     * records that non-v7 ids are COMMONER in production than the litellm far-future ones.
     */
    private List<SeededSpan> spansWithNonV7Ids(int count, Instant createdAt) {
        var spans = new ArrayList<SeededSpan>();
        for (int i = 0; i < count; i++) {
            spans.add(SeededSpan.builder()
                    .id(UUID.randomUUID())
                    .traceId(ID_GENERATOR.generateId(createdAt))
                    .createdAt(createdAt.plusSeconds(i))
                    .build());
        }
        return spans;
    }

    private static Set<String> idStrings(List<SeededSpan> spans) {
        return spans.stream().map(span -> span.id().toString()).collect(Collectors.toUnmodifiableSet());
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        var union = new ArrayList<>(a);
        union.addAll(b);
        return Set.copyOf(union);
    }

    /** A within-day offset so ids/created_at in the same week are distinct but stay inside their weekly partition. */
    private Instant weekInstant(int weekOffset, int secondOffset) {
        return ANCHOR_MONDAY.plusWeeks(weekOffset).atTime(1, 0).plusSeconds(secondOffset).toInstant(ZoneOffset.UTC);
    }

    // --- value types ---------------------------------------------------------------------------------------------

    /**
     * A migration schema shape. OLD is the source layout (Nullable end_time/ttft, nanosecond timestamps,
     * {@code String} parent_span_id, {@code Int32} usage values); NEW is the successor layout (epoch / NaN sentinels,
     * microsecond timestamps, {@code FixedString(36)} parent_span_id, {@code Int64} usage values). The per-row hash
     * normalizes each shape to the same canonical value for a faithfully-migrated row.
     */
    private enum Shape {
        OLD,
        NEW
    }

    /** One seeded span: the ids and the {@code created_at} the backfill slices on. */
    @Builder(toBuilder = true)
    private record SeededSpan(UUID id, UUID traceId, Instant createdAt) {
    }

    /** The rollback sentinel repair's four verification counts. */
    @Builder(toBuilder = true)
    private record SentinelCounts(long sentinelEndTime, long sentinelTtft, long negativeFromSentinel,
            long staleDuration) {
    }

    /** An order-independent fidelity fingerprint: how many logical rows, and a checksum over their canonical form. */
    @Builder(toBuilder = true)
    private record Fingerprint(long count, long checksum) {
    }

    /** The post-swap reconciliation postcondition's four counts. */
    @Builder(toBuilder = true)
    private record ReconciliationCounts(long missing, long stale, long payloadMismatch, long newer) {

        /** The gate's target: the three gating counts at zero. {@code newer} is informational and excluded. */
        private static ReconciliationCounts reconciled() {
            return ReconciliationCounts.builder().missing(0).stale(0).payloadMismatch(0).newer(0).build();
        }
    }
}
