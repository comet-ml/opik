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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
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

/**
 * End-to-end validation of the buffered cutover that migrates {@code traces} to its partitioned, sharding-ready
 * successor {@code traces_local_v2}. It rehearses the full sequence against a fresh ClickHouse in raw SQL — the same
 * steps an operator runs from the {@code data-migrations/traces-local-v2-cutover} runbook — and pins the properties
 * the cutover's correctness depends on.
 *
 * <p><b>Inline SQL, by design.</b> This gate reimplements the cutover statements inline rather than executing the
 * reference {@code .sql} files the drivers ship, so it can interleave seeding, per-step assertions, and the negative
 * controls below — a deliberate choice. It is an independent validation of the cutover <i>logic</i>, not the
 * single-source path: the driver scripts read the single-source reference SQL (that "no copy-paste drift" property is
 * about the operator tools), and the shipped SQL itself is exercised end-to-end by running those drivers against a
 * full-volume prod clone in the QA gate (OPIK-7405). The inline statements here are kept aligned with the reference SQL
 * — identical functions, precision, and {@code 'UTC'} — so this gate and the shipped SQL stay in step.
 *
 * <p><b>Deletions must survive the swap (the core property).</b> A lightweight DELETE flips a hidden row mask; it does
 * not bump {@code last_updated_at} (the {@code ReplacingMergeTree} version column), so the version-based delta-insert is
 * blind to deletes that land while the table is being copied — the already-copied row stays alive on the destination
 * and the deletion would leak across the swap. The deletion-events bridge closes this: every delete is recorded in
 * {@code deletion_events_local} and replayed against the destination before the swap. The test exercises:
 * <ul>
 *   <li>rows deleted <b>before</b> the backfill — excluded from {@code INSERT SELECT} by the
 *   {@code apply_deleted_mask = 1} default, so they never reach the destination;</li>
 *   <li>rows deleted <b>during</b> the backfill (a large retention-shape batch and single user-shape ids) — the test
 *   asserts the leak is real (still alive on the destination after the delta-insert: the negative control that proves
 *   the bridge is load-bearing), that the replay masks them, and that there are zero leaks after the swap.</li>
 * </ul>
 *
 * <p><b>Replay matches the full key {@code (workspace_id, project_id, id)}.</b> Trace ids are not globally unique —
 * imported or crafted rows can reuse an id across projects — so replaying by {@code id} alone would over-delete a live
 * row that merely shares the id in another project. The bridge captures the resolved {@code (workspace_id, project_id)},
 * so the replay deletes by the full key, which is also the destination primary key (so the mutation prunes on it). A
 * reused id deleted in one project and surviving in another exercises this.
 *
 * <p><b>The delta is anchored on {@code created_at OR last_updated_at >= backfill_start}.</b> {@code last_updated_at} is
 * client-supplied on the batch-ingest path, so it is not a reliable "changed since" signal on its own; and a cutoff
 * taken at backfill end would miss writes that landed during the (long) backfill. But every trace write sets either a
 * fresh server {@code created_at} (batch-ingest path) or a fresh server {@code last_updated_at} (create/update merge
 * paths), so the union, anchored before the backfill, catches every row written during the window. Both arms are
 * covered: a normal upsert (new {@code last_updated_at}) and a row created during the window with a client-backdated
 * {@code last_updated_at} that only the {@code created_at} arm catches.
 *
 * <p>It also confirms {@code EXCHANGE TABLES ... ON CLUSTER} on the single-shard cluster, the sharding-ready
 * {@code Distributed} wrapper reading transparently on one shard, newest-version-wins for concurrent upserts, and it
 * measures the replay wall time so the runbook can size it against the ingestion buffer window. Finally it proves the
 * cutover is reversible: the post-wrap rollback drops the wrapper, promotes the parked old data back to {@code traces},
 * and reverse-replays so a post-cutover delete does not resurrect — and, separately, that the wrap alone can be
 * reversed ({@code --unwrap-only}) leaving the partitioned successor and its post-cutover writes live, with no parked
 * original required and the wrap re-appliable afterwards. The rollback's tail is covered too: the sentinel repair
 * restores {@code NULL} on the rows the schema-state flag wrote into the still-Nullable original and lets the mutation
 * recompute their {@code duration}, without disturbing a negative duration the source data genuinely carries.
 *
 * <p><b>Dedicated, non-reused containers</b> are required because the cutover ends in a destructive {@code EXCHANGE} +
 * {@code RENAME} of the live {@code traces} table, which must never touch a container shared with other suites. Runs
 * raw SQL over {@link TransactionTemplateAsync} with no Dropwizard app, mirroring {@link TracesLocalV2PartitioningTest}.
 *
 * <p><b>Why raw SQL and not the production DAOs.</b> The cutover orchestration this validates (backfill
 * {@code INSERT SELECT}, delta, replay, {@code EXCHANGE}, wrap) is operator SQL that no DAO owns, and it needs the
 * destructive-safe containers above, which the shared app-harness cannot provide. The seeding, delete and
 * bridge-capture helpers mirror the production write shapes ({@code TraceDAO}, {@code TraceService} delete,
 * {@code DeletionEventDAO}) and reproduce the two version-stamp regimes the delta relies on (fresh server
 * {@code created_at} vs client {@code last_updated_at}); the DAOs' own semantics are covered by their dedicated suites
 * (e.g. {@code TraceDeletionEventTest}).
 *
 * <p><b>Scope: this gate validates the cutover SQL logic, not the driver scripts.</b> The safety guards in the runbook's
 * bash drivers — {@code backfill.sh}'s reconciliation abort, {@code rollback.sh}'s wrong-stage topology assertions,
 * {@code exchange_and_wrap.sh}'s replication-settle gate, {@code finalize.sh}'s empty-live refusal — are exercised by the
 * OPIK-6901 staging dry-run, not by this test (which runs the SQL those scripts wrap, directly). This test asserts the
 * logic is correct when invoked; the staging rehearsal asserts the scripts invoke it safely.
 *
 * <p><b>On the {@code SETTINGS} these statements carry.</b> They hardcode {@code max_insert_block_size = 100000}
 * and {@code max_partitions_per_insert_block = 2000}, and omit {@code max_insert_threads} — while
 * {@code backfill.sh} / {@code delta_replay.sh} make all three configurable. The split is deliberate and follows
 * what each setting can do:
 *
 * <ul>
 * <li>{@code max_partitions_per_insert_block} is <b>mirrored because it is a correctness gate, not pacing</b>: a
 * value below the number of partitions a block spans aborts the INSERT outright ({@code TOO_MANY_PARTS}), which is
 * exactly the failure the runbook's far-future section exists for. It is pinned at the drivers' own default.</li>
 * <li>{@code max_insert_threads} is <b>omitted because it is pacing</b>, and because omitting it is meaningful
 * here in the same way it is in the drivers: an absent key inherits whatever the server sets, so this gate asserts
 * the SQL's logic rather than an operator's tuning choice.</li>
 * <li>{@code max_insert_block_size} is fixed only to keep CI memory predictable.</li>
 * </ul>
 *
 * <p>The standing gap this implies: because the mirrored values are hardcoded rather than read from the scripts,
 * this gate cannot catch a driver configured with an invalid value — that belongs to the staging dry-run. A future
 * setting that changes RESULTS rather than pacing must be mirrored here; {@code 000002}'s header asks that the SQL
 * and this test be kept in step, and pacing settings are the documented exception to that.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracesLocalV2CutoverTest {

    /**
     * A fixed historical Monday the seeded rows are minted at week offsets from, so the backfill can slice the source
     * by whole {@code created_at} weeks deterministically. Far in the past and never {@code now}-derived, so nothing
     * drifts across a week boundary mid-run. It intentionally overlaps the anchor another suite
     * ({@code TracesLocalV2PartitioningTest}) uses, which is safe: this suite runs on its own dedicated, non-reused
     * containers (see the container fields below), so its data never shares a ClickHouse instance with any other suite.
     */
    private static final LocalDate ANCHOR_MONDAY = LocalDate.of(2025, 3, 3);

    /**
     * A client-backdated version stamp, well before {@code backfill_start}. A row written during the window carrying
     * this as its {@code last_updated_at} can only be caught by the delta's {@code created_at} arm.
     */
    private static final Instant BACKDATED = LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    /** Rows spread across three consecutive weekly partitions, so the backfill runs as three weekly batches. */
    private static final int SEED_WEEKS = 3;
    private static final int SURVIVORS_PER_WEEK = 40;
    private static final int PRE_EXISTING_DELETED_PER_WEEK = 15;
    private static final int RETENTION_DELETED_PER_WEEK = 80;
    private static final int USER_DELETED_PER_WEEK = 5;
    private static final int DELTA_UPSERTS = 20;
    private static final int DELTA_LATE_CREATED = 10;

    private static final String[] FIDELITY_SOURCES = {"sdk", "experiment", "playground", "optimization", "evaluator"};
    private static final String[] FIDELITY_ENVIRONMENTS = {"production", "staging", "dev", ""};

    /**
     * Where {@link #unwrapNeedsNoParkedOriginalAndTheWrapCanBeReapplied()} parks the original while it simulates a
     * finalized estate. Test-only, and deliberately not one of the cutover's own names, so the reset can tell it apart
     * from any state the migration itself produces.
     */
    private static final String PARKED_BACKUP = "traces_pre_cutover_backup_test_parked";

    /**
     * The stored (non-materialized) columns the cutover copies, one per line. Both INSERT clauses are built from this
     * list, and {@link #cutoverCopiesEveryBaseColumn()} asserts it equals the live base columns of {@code traces} — so a
     * base column added by a future migration cannot be silently left uncopied (the fidelity fingerprint, which lists a
     * fixed set, would not catch that on its own). A new column here without a matching SELECT entry fails arity at run.
     */
    /**
     * The {@code version-ties} aggregate, with its source relation as the parameter: per (key, version) how many
     * DISTINCT row contents there are, then the count at each key's newest version, then the keys where that exceeds one.
     * Shared so the table-backed helper and the literal-relation test exercise the same expression rather than separate
     * transcriptions of it.
     */
    private static final String VERSION_TIE_AGGREGATE = """
            SELECT count() AS c
            FROM (
                SELECT key, argMax(distinct_at_version, version) AS distinct_at_newest
                FROM (
                %s
                )
                GROUP BY key
            )
            WHERE distinct_at_newest > 1
            """;

    private static final String COPIED_COLUMNS = """
            id,
            workspace_id,
            project_id,
            name,
            start_time,
            end_time,
            input,
            output,
            metadata,
            tags,
            created_at,
            last_updated_at,
            created_by,
            last_updated_by,
            error_info,
            thread_id,
            visibility_mode,
            truncation_threshold,
            input_slim,
            output_slim,
            ttft,
            source,
            environment""";

    /**
     * The SELECT projection the backfill and delta share: the {@link #COPIED_COLUMNS} columns, with the two denullified
     * columns coalesced to their sentinels (end_time → epoch, ttft → NaN). The two INSERT-SELECTs differ only in their
     * WHERE clause, so the projection is defined once here. A column added to {@link #COPIED_COLUMNS} but not here (or
     * vice versa) fails arity at run.
     */
    private static final String COPIED_SELECT = """
            id,
            workspace_id,
            project_id,
            name,
            start_time,
            coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6, 'UTC')) AS end_time,
            input,
            output,
            metadata,
            tags,
            created_at,
            last_updated_at,
            created_by,
            last_updated_by,
            error_info,
            thread_id,
            visibility_mode,
            truncation_threshold,
            input_slim,
            output_slim,
            coalesce(ttft, toFloat64('nan')) AS ttft,
            source,
            environment""";

    /**
     * A {@code SETTINGS} fragment putting the session WEST of UTC, for the tests that pin datetime literals. The
     * direction is load-bearing: an unpinned literal read in a westward zone resolves LATER in absolute terms, so a
     * bound moves past rows that belong inside it — which is the silent failure. An eastward zone moves bounds earlier
     * and would let an unpinned literal pass, so it would not discriminate.
     */
    private static final String WESTWARD_SESSION = ", session_timezone = 'America/New_York'";

    /**
     * The bridge-window predicate, shared verbatim by the four statements that read {@code deletion_events_local} over
     * the full key: the forward deletion replay, the rollback's reverse replay, the rollback postcondition, and the
     * count the timezone test compares against. One definition, so a test cannot pass against a predicate the replay
     * does not use. The bind is named {@code since} because the callers disagree on what the instant means
     * ({@code backfill_start} forward, {@code cutover_start} in reverse) while the predicate does not.
     *
     * <p>The 36-length and non-empty guards are the shipped ones: {@code deleted_id} and {@code project_id} are
     * {@code String} on the bridge but {@code FixedString(36)} on the trace tables, so a malformed event would make
     * {@code toFixedString} throw rather than simply not match. The replay's resurrection guard reads the same bridge
     * through a deliberately reduced form — it needs {@code deleted_id} only — so it is not this constant.
     */
    private static final String BRIDGE_WINDOW_PREDICATE = """
            WHERE source_table = 'traces'
              AND event_time >= toDateTime64(:since, 6, 'UTC')
              AND project_id != ''
              AND length(project_id) = 36
              AND length(deleted_id) = 36""";

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

    // Dedicated (non-reused) containers, so tear them down explicitly rather than relying only on the Ryuk reaper —
    // keeps reruns and a shared JVM from accumulating stopped-but-lingering resources. PER_CLASS lets this be non-static.
    @AfterAll
    void stopContainers() {
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /**
     * Restore the canonical baseline (traces = original schema, traces_local_v2 = successor schema, both empty; no stray
     * wrap/rename artifacts) before every test, independent of what the previous test left behind. A green run always
     * ends canonical, but a test that fails mid-cutover can leak any intermediate topology, so rather than assume a clean
     * hand-off this normalizes whatever is present back to canonical. The cutover only ever produces these shapes: the
     * completed EXCHANGE (traces = successor, original parked as traces_pre_cutover_backup) and wrap (traces =
     * Distributed over traces_local), a completed rollback (traces = original, successor parked as
     * traces_post_rollback_backup), plus the partial states where only the first of a two-statement swap/wrap ran.
     * Every DDL below is guarded on the tables it touches, so no leaked state can make the reset itself throw and
     * cascade into later tests. {@code end_time} being Nullable is the original schema, non-Nullable the successor.
     */
    @BeforeEach
    void resetTables() {
        // 0. Test-only: the un-wrap suite parks the original aside to simulate a finalized estate. Hand it back first, so
        //    a test that failed mid-way cannot leave later tests without the original schema to rebuild the baseline from.
        if (!tableExists("traces_pre_cutover_backup") && tableExists(PARKED_BACKUP)) {
            execute("RENAME TABLE " + PARKED_BACKUP + " TO traces_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
            });
        }
        // 1. Wrap: `traces` is a Distributed wrapper holding no data of its own — drop it, leaving the successor under
        //    traces_local and the original under traces_pre_cutover_backup (the same shape as a partial wrap).
        if (isDistributed("traces")) {
            execute("DROP TABLE traces ON CLUSTER '{cluster}' SYNC", _ -> {
            });
        }
        // 2. Wrap (completed or partial): successor parked as traces_local, original as traces_pre_cutover_backup, with
        //    `traces` absent. Restore both names.
        if (!tableExists("traces_local_v2") && tableExists("traces_local")) {
            execute("RENAME TABLE traces_local TO traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
            });
        }
        // 2b. Rollback (completed): the successor is parked as traces_post_rollback_backup. Recover it into the successor's
        //     baseline name so step 4 truncates it back to empty.
        if (!tableExists("traces_local_v2") && tableExists("traces_post_rollback_backup")) {
            execute("RENAME TABLE traces_post_rollback_backup TO traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
            });
        }
        if (!tableExists("traces") && tableExists("traces_pre_cutover_backup")) {
            execute("RENAME TABLE traces_pre_cutover_backup TO traces ON CLUSTER '{cluster}'", _ -> {
            });
        }
        // 3. EXCHANGE (completed or partial): `traces` exists but holds the SUCCESSOR schema. Un-swap it with the parked
        //    original — under traces_pre_cutover_backup once the EXCHANGE completed, or still under traces_local_v2 if
        //    only the EXCHANGE ran and its follow-up RENAME did not.
        if (tableExists("traces") && !columnType("traces", "end_time").startsWith("Nullable")) {
            if (tableExists("traces_pre_cutover_backup")) {
                execute("EXCHANGE TABLES traces AND traces_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
                });
                execute("RENAME TABLE traces_pre_cutover_backup TO traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
                });
            } else if (tableExists("traces_local_v2")) {
                execute("EXCHANGE TABLES traces AND traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
                });
            }
        }
        // 4. Canonical now; truncate the two tables and clear any residual artifacts (IF EXISTS so a genuinely
        //    unrecoverable partial state still cannot throw here). traces_dist / traces_dist_old are the temp wrapper
        //    names the gapless wrap and stage-C rollback use around their atomic renames — leaks only if a test died
        //    between a CREATE/RENAME and the following statement.
        execute("DROP TABLE IF EXISTS traces_dist ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_dist_old ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_local ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_pre_cutover_backup ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_post_rollback_backup ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS " + PARKED_BACKUP + " ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        // ON CLUSTER like every other statement in this reset (and like stage A's truncate), not bare: on a
        // ReplicatedMergeTree a plain TRUNCATE need only be applied by the local replica before the client returns,
        // whereas ON CLUSTER waits for the distributed DDL task — a real barrier before the test body inserts. Without
        // one, the emptying can still be settling while a test writes, which is the shape of a rare empty-table flake.
        execute("TRUNCATE TABLE IF EXISTS traces ON CLUSTER '{cluster}'", _ -> {
        });
        execute("TRUNCATE TABLE IF EXISTS traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        execute("TRUNCATE TABLE IF EXISTS deletion_events_local ON CLUSTER '{cluster}'", _ -> {
        });
    }

    @Test
    void bufferedCutoverPreservesEveryDeletionAcrossExchange() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var otherProjectId = ID_GENERATOR.generateId();

        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var preExistingDeleted = mintIds(PRE_EXISTING_DELETED_PER_WEEK);
        var retentionDeleted = mintIds(RETENTION_DELETED_PER_WEEK);
        var userDeleted = mintIds(USER_DELETED_PER_WEEK);
        // One id reused across two projects: deleted in projectId, must survive in otherProjectId (full-key replay).
        var reusedInstant = weekInstant(0, 1);
        var reusedId = ID_GENERATOR.generateId(reusedInstant);
        var reused = List.of(CategorizedId.builder().id(reusedId).createdAt(reusedInstant).build());

        // Seed the live table across the weekly partitions. created_at drives the backfill slice; id (a UUIDv7 minted
        // at the same week) drives the destination id_at partition, independently of the slice.
        var allSeeded = new ArrayList<CategorizedId>();
        allSeeded.addAll(survivors);
        allSeeded.addAll(preExistingDeleted);
        allSeeded.addAll(retentionDeleted);
        allSeeded.addAll(userDeleted);
        seedTraces(allSeeded, workspaceId, projectId);
        seedTraces(reused, workspaceId, projectId);
        seedTraces(reused, workspaceId, otherProjectId);
        // Every migrated column populated with distinct values at ns precision (+ some NULL end_time/ttft), so the
        // fidelity fingerprint below actually exercises every column and the ns->us truncation.
        var fidelityIds = seedFidelityCohort(workspaceId, projectId);

        // Pre-existing deletes: removed before the backfill starts, and NOT recorded in the bridge — INSERT SELECT
        // honors the mask and never copies them, so no replay is involved.
        lightweightDelete(idStrings(preExistingDeleted), workspaceId);

        // Anchor for BOTH the delta and the replay window, captured BEFORE the backfill so it covers the whole run.
        var backfillStart = nowMicros();

        // Weekly-batched backfill, the same INSERT SELECT the runbook runs (sentinel coalescing for the denullified
        // columns; is_deleted omitted so it defaults to 0).
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }

        // Guard: masked rows did not ride across the copy.
        assertThat(liveCount("traces_local_v2", idStrings(preExistingDeleted), workspaceId))
                .as("pre-existing masked rows must not be copied by the backfill")
                .isZero();
        assertThat(liveCount("traces_local_v2", idStrings(survivors), workspaceId))
                .as("all survivors backfilled")
                .isEqualTo(survivors.size());

        // Deletes during the backfill/delta window.
        // Retention-shape: bridge INSERT first (before the LWD), then one large lightweight DELETE.
        recordDeletionEvents(idStrings(retentionDeleted), workspaceId, projectId.toString(), "retention");
        lightweightDelete(idStrings(retentionDeleted), workspaceId);
        // User-shape: single-id deletes.
        recordDeletionEvents(idStrings(userDeleted), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(userDeleted), workspaceId);
        // Reused-id delete scoped to projectId only — the copy under otherProjectId must survive.
        recordDeletionEvents(Set.of(reusedId.toString()), workspaceId, projectId.toString(), "user_request");
        lightweightDeleteScoped(Set.of(reusedId.toString()), workspaceId, projectId);
        // During-window instant from the SAME server clock as backfillStart (a later now64(6), so >= backfillStart) —
        // NOT the JVM host clock, whose skew vs the container could put these below backfillStart and flake the delta.
        var duringWindow = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        // Concurrent upserts: a newer version of a subset of survivors — caught by the delta's last_updated_at arm.
        var deltaUpserted = survivors.subList(0, DELTA_UPSERTS);
        insertRows(deltaUpserted, workspaceId, projectId, deltaName(), _ -> duringWindow);
        // Rows created during the window with a client-backdated last_updated_at — caught ONLY by the created_at arm.
        var deltaLateCreated = mintIdsAt(DELTA_LATE_CREATED, duringWindow);
        insertRows(deltaLateCreated, workspaceId, projectId, "late", _ -> BACKDATED);

        // Delta-insert: created_at OR last_updated_at since backfill_start (see class Javadoc).
        deltaInsert(backfillStart);

        // Negative control — before replay, the during-backfill deletes have leaked onto the destination: still fully
        // alive there, because the delta-insert cannot see a lightweight delete. This is what the bridge exists to fix.
        var leakedIds = union(idStrings(retentionDeleted), idStrings(userDeleted));
        assertThat(liveCount("traces_local_v2", leakedIds, workspaceId))
                .as("negative control: without replay, during-backfill deletes leak across the copy")
                .isEqualTo(leakedIds.size());
        // Both delta arms worked: the backdated-last_updated_at rows were caught via created_at.
        assertThat(liveCount("traces_local_v2", idStrings(deltaLateCreated), workspaceId))
                .as("delta created_at arm caught rows written during the window with a backdated last_updated_at")
                .isEqualTo(deltaLateCreated.size());

        // Deletion replay: read the bridge for the window and re-issue the deletes against the destination, matched on
        // the full key so a reused id in another project is untouched.
        // Measured and logged (not asserted): replay wall time is environment-sensitive (container startup, CI
        // contention), so a hard bound here would be a flaky gate on a non-correctness property. The runbook sizes it
        // against the buffer window during the cutover rehearsal; correctness is asserted below (the mask is applied).
        var replayMillis = replayDeletions(backfillStart);
        log.info("Deletion replay covered {} ids in {} ms", leakedIds.size() + 1, replayMillis);

        // After replay, the leak is closed on the destination, before the swap.
        assertThat(liveCount("traces_local_v2", leakedIds, workspaceId))
                .as("replay masks every bridged deletion on the destination")
                .isZero();

        // The all-column fidelity cohort was copied intact (its content is checked by the fingerprint below).
        assertThat(liveCount("traces_local_v2", Set.copyOf(fidelityIds), workspaceId))
                .as("every fidelity-cohort row (all columns populated, ns created_at) is backfilled")
                .isEqualTo(fidelityIds.size());

        // Fidelity QA: before the swap, the deduped, mask-honored, NORMALIZED content of source and destination must be
        // identical. This is the same normalized fingerprint verify.sh computes per week for production QA; asserting it
        // here also proves the normalization (NULL/epoch and NULL/NaN sentinels, ns->us precision) is correct — a wrong
        // normalization would fail even on this faithfully-migrated data.
        assertThat(fingerprint("traces_local_v2", Shape.NEW, workspaceId))
                .as("normalized (count, checksum) fingerprint matches between source and destination")
                .isEqualTo(fingerprint("traces", Shape.OLD, workspaceId));

        // Derived/materialized columns are recomputed by each table's own DDL, so the base-column fingerprint above does
        // not cover them. Assert the successor's expressions yield the SAME values as the source's on the fidelity
        // cohort: the deterministic ones (lengths, truncated_*, output_keys) exactly, and duration within the intended
        // ns->us precision (source computes from nanosecond timestamps and is NULL when unset; the successor computes
        // from the microsecond copy and is NaN when unset).
        assertThat(derivedFingerprint("traces_local_v2", workspaceId))
                .as("deterministic derived columns match after the copy (no MATERIALIZED-expression drift)")
                .isEqualTo(derivedFingerprint("traces", workspaceId));
        assertThat(durationMismatches(workspaceId))
                .as("duration matches within the ns->us truncation, NULL<->NaN normalized")
                .isZero();

        // The atomic swap: EXCHANGE TABLES ... ON CLUSTER on the single-shard cluster. Record the instant just before it
        // as the rollback's reverse-replay window start (a post-cutover delete after this must not resurrect on rollback).
        var cutoverStart = nowMicros();
        exchangeTables();

        // Post-EXCHANGE, `traces` is the partitioned successor. Assert zero deletion leaks.
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("every survivor is present after the cutover")
                .isEqualTo(survivors.size());
        assertThat(liveCount("traces", idStrings(deltaLateCreated), workspaceId))
                .as("rows created during the window (backdated last_updated_at) survive the cutover")
                .isEqualTo(deltaLateCreated.size());
        assertThat(liveCount("traces", idStrings(preExistingDeleted), workspaceId))
                .as("pre-existing deletions stay deleted after the cutover")
                .isZero();
        assertThat(liveCount("traces", idStrings(retentionDeleted), workspaceId))
                .as("retention-shape deletions do not leak across the EXCHANGE")
                .isZero();
        assertThat(liveCount("traces", idStrings(userDeleted), workspaceId))
                .as("user-shape deletions do not leak across the EXCHANGE")
                .isZero();

        // Full-key replay: the reused id is gone under the deleted project but alive under the other project.
        assertThat(liveCountScoped("traces", Set.of(reusedId.toString()), workspaceId, projectId))
                .as("reused id is deleted under its own project")
                .isZero();
        assertThat(liveCountScoped("traces", Set.of(reusedId.toString()), workspaceId, otherProjectId))
                .as("reused id survives under the other project — replay did not over-delete by id alone")
                .isEqualTo(1L);

        // Newest-version-wins: the delta upserts are the surviving version after ReplacingMergeTree dedup.
        assertThat(newestNames("traces", idStrings(deltaUpserted), workspaceId))
                .as("delta upserts win under FINAL dedup after the cutover")
                .containsOnly(deltaName());

        // Sharding-ready wrap: RENAME to *_local, front it with a Distributed table keyed on project_id.
        wrapInDistributed();
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("the single-shard Distributed wrapper reads transparently")
                .isEqualTo(survivors.size());
        assertThat(liveCount("traces", leakedIds, workspaceId))
                .as("deletions stay deleted when read through the Distributed wrapper")
                .isZero();
        assertThat(liveCountScoped("traces", Set.of(reusedId.toString()), workspaceId, otherProjectId))
                .as("reused id still readable under the other project through the Distributed wrapper")
                .isEqualTo(1L);

        // Rollback (Stage C) — the wrap is reversible without resurrecting post-cutover deletes. Post-wrap the app's
        // delete DAO targets `traces_local` (OPIK-7455) and carries the full key (OPIK-7483), so simulate a post-wrap
        // delete on `traces_local` recorded in the bridge with its project, then roll back: drop the Distributed
        // wrapper, promote the parked old data back to `traces`, and reverse-replay from cutover_start.
        var postWrapDeleted = Set.of(survivors.getFirst().id().toString());
        recordDeletionEvents(postWrapDeleted, workspaceId, projectId.toString(), "user_request");
        execute("DELETE FROM traces_local WHERE workspace_id = :workspace_id AND project_id = :project_id AND id IN :ids",
                statement -> statement.bind("workspace_id", workspaceId).bind("project_id", projectId.toString())
                        .bind("ids", postWrapDeleted));
        rollbackAfterWrap(cutoverStart);

        assertThat(isDistributed("traces"))
                .as("rollback drops the Distributed wrapper; `traces` is a regular table again")
                .isFalse();
        assertThat(liveCount("traces", postWrapDeleted, workspaceId))
                .as("post-wrap delete does not resurrect on the rolled-back table")
                .isZero();
        assertThat(liveCount("traces", idStrings(survivors.subList(1, survivors.size())), workspaceId))
                .as("all other survivors are intact after rollback")
                .isEqualTo(survivors.size() - 1);
        assertThat(tableExists("traces_post_rollback_backup"))
                .as("rollback ends in the canonical state: successor data parked as traces_post_rollback_backup")
                .isTrue();
        assertThat(liveCount("traces_post_rollback_backup", postWrapDeleted, workspaceId))
                .as("parked backup keeps the successor's post-wrap delete masked (not resurrected in the backup)")
                .isZero();
        assertThat(liveCount("traces_post_rollback_backup", idStrings(survivors.subList(1, survivors.size())),
                workspaceId))
                .as("parked backup actually holds the successor data (survivors), not an empty or wrong table")
                .isEqualTo(survivors.size() - 1);
        assertThat(columnType("traces_post_rollback_backup", "end_time"))
                .as("parked backup carries the successor's non-Nullable schema, confirming the right table was parked")
                .doesNotStartWith("Nullable");
        assertThat(tableExists("traces_local_v2"))
                .as("the disposable shadow name is free after rollback (so stage A cannot truncate the backup)")
                .isFalse();
        assertThat(tableExists("traces_local"))
                .as("no leftover sharding table after rollback")
                .isFalse();
    }

    /**
     * A far-future row survives the cutover with a matching fidelity fingerprint across the {@code id_at} type change.
     * The source {@code traces.id_at} is a 32-bit {@code DateTime} that wraps a ~2201 UUIDv7 id to ~2065, while the
     * successor's {@code DateTime64} reads it back as the honest 2201; {@code derivedFingerprint} casts both to
     * {@code toDateTime} so the comparison is on the same instant regardless of width. Present-day rows exercise that
     * cast as a no-op, so only a far-future row exercises the wrap — where the successor's honest 2201 must collapse to
     * the same value the 32-bit source stores, or the fingerprint would falsely report infidelity. Seeds a far-future-id
     * row into the {@code 'seed-fidelity'} cohort and pins that it is copied, partitions into its own honest ~2201 week,
     * and leaves the source/destination derived fingerprint identical.
     */
    @Test
    void farFutureRowSurvivesCutoverWithMatchingFingerprint() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();

        // Present-day all-column cohort, plus one far-future-id row in the same cohort: a real created_at drives the
        // backfill slice, while the id minted ~2201 drives the destination id_at partition.
        seedFidelityCohort(workspaceId, projectId);
        var farFutureInstant = Instant.parse("2201-06-01T00:00:00Z");
        var farFutureId = ID_GENERATOR.generateId(farFutureInstant);
        insertRows(List.of(CategorizedId.builder().id(farFutureId).createdAt(weekInstant(0, 1)).build()),
                workspaceId, projectId, "seed-fidelity", CategorizedId::createdAt);

        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }

        assertThat(liveCount("traces_local_v2", Set.of(farFutureId.toString()), workspaceId))
                .as("far-future-id row is copied by the created_at-sliced backfill")
                .isEqualTo(1L);
        // Exact honest Monday (YYYYMMDD) computed in Java from the mint instant — asserting the precise week, not just
        // the 2201 year, catches an off-by-week regression through the copy, matching the direct-insert tests' bar.
        var expectedMonday = farFutureInstant.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(destinationPartitionId(farFutureId, workspaceId))
                .as("copied far-future row lands in its own honest ~2201 weekly partition, not a wrapped ~2065")
                .isEqualTo(expectedMonday);
        assertThat(derivedFingerprint("traces_local_v2", workspaceId))
                .as("derived fingerprint matches across the id_at type change even with a far-future row present")
                .isEqualTo(derivedFingerprint("traces", workspaceId));
    }

    /**
     * Rollback stage A (000004_rollback_stage_a): aborting before the EXCHANGE only discards the shadow — the live
     * {@code traces} table, which the backfill never writes to, must be byte-for-byte untouched.
     */
    @Test
    void rollbackBeforeExchangeDiscardsShadowAndLeavesLiveUntouched() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);

        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        assertThat(liveCount("traces_local_v2", idStrings(survivors), workspaceId))
                .as("shadow was backfilled before the abort")
                .isEqualTo(survivors.size());
        var liveBefore = fingerprint("traces", Shape.OLD, workspaceId);

        rollbackDiscardShadow();

        assertThat(liveCount("traces_local_v2", idStrings(survivors), workspaceId))
                .as("stage A discards the shadow copy")
                .isZero();
        assertThat(fingerprint("traces", Shape.OLD, workspaceId))
                .as("stage A leaves the live table untouched")
                .isEqualTo(liveBefore);
    }

    /**
     * Rollback stage B (000004_rollback_stage_b + reverse_replay): aborting after the EXCHANGE but before the wrap swaps
     * the tables back and reverse-replays, so a delete that landed on the successor after cutover_start does not
     * resurrect on the restored original. Exercises the reverse-replay's full-key branch (the only branch since
     * OPIK-7483), and pins reverse-replay idempotence — the contract {@code --reverse-replay-only}
     * relies on for re-applying an interrupted rollback replay.
     */
    @Test
    void rollbackAfterExchangeSwapsBackWithoutResurrectingDeletes() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var windowDeleted = mintIds(USER_DELETED_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);
        seedTraces(windowDeleted, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        // A delete during the window: bridged, applied to the source, then reconciled onto the destination by the replay.
        recordDeletionEvents(idStrings(windowDeleted), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(windowDeleted), workspaceId);
        deltaInsert(backfillStart);
        replayDeletions(backfillStart);

        var cutoverStart = nowMicros();
        exchangeTables();
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("survivors present on the successor after the EXCHANGE")
                .isEqualTo(survivors.size());
        assertThat(liveCount("traces", idStrings(windowDeleted), workspaceId))
                .as("window deletes did not leak across the EXCHANGE")
                .isZero();

        // Post-cutover delete on the new live table (a MergeTree post-EXCHANGE, so a lightweight DELETE works), captured
        // with its project — the reverse-replay's full-key branch.
        var postCutoverDeleted = Set.of(survivors.getFirst().id().toString());
        recordDeletionEvents(postCutoverDeleted, workspaceId, projectId.toString(), "user_request");
        lightweightDelete(postCutoverDeleted, workspaceId);

        rollbackExchangeBack(cutoverStart);

        assertThat(isDistributed("traces"))
                .as("stage B restores a regular table")
                .isFalse();
        assertThat(liveCount("traces", postCutoverDeleted, workspaceId))
                .as("post-cutover delete does not resurrect after the swap-back")
                .isZero();
        assertThat(liveCount("traces", idStrings(windowDeleted), workspaceId))
                .as("window deletes stay deleted after the swap-back")
                .isZero();
        assertThat(liveCount("traces", idStrings(survivors.subList(1, survivors.size())), workspaceId))
                .as("all other survivors are intact after the swap-back")
                .isEqualTo(survivors.size() - 1);
        assertThat(tableExists("traces_post_rollback_backup"))
                .as("canonical state: successor parked as traces_post_rollback_backup")
                .isTrue();
        assertThat(liveCount("traces_post_rollback_backup", postCutoverDeleted, workspaceId))
                .as("parked backup keeps the post-cutover delete masked (not resurrected in the backup)")
                .isZero();
        assertThat(liveCount("traces_post_rollback_backup", idStrings(windowDeleted), workspaceId))
                .as("parked backup does not resurrect window deletes")
                .isZero();
        assertThat(liveCount("traces_post_rollback_backup", idStrings(survivors.subList(1, survivors.size())),
                workspaceId))
                .as("parked backup actually holds the successor data (survivors), not an empty or wrong table")
                .isEqualTo(survivors.size() - 1);
        assertThat(columnType("traces_post_rollback_backup", "end_time"))
                .as("parked backup carries the successor's non-Nullable schema")
                .doesNotStartWith("Nullable");
        // Both signals above, plus this one, are what --reverse-replay-only and --sentinel-repair-only assert before
        // acting: the promote's RENAME consumes the parked original, so it surviving here would mean a half-done rename.
        assertThat(tableExists("traces_pre_cutover_backup"))
                .as("the promote's RENAME consumed the parked original, so its name is free after the swap-back")
                .isFalse();
        assertThat(tableExists("traces_local_v2"))
                .as("the disposable shadow name is free after rollback (so stage A cannot truncate the backup)")
                .isFalse();
        assertThat(tableExists("traces_local"))
                .as("no leftover sharding table after stage B")
                .isFalse();

        // Reverse-replay idempotence — the safety property --reverse-replay-only relies on when a stage B/C run's replay
        // is interrupted and re-applied. Re-running it against the restored original is a no-op: the post-cutover delete
        // stays masked and no live survivor is dropped.
        reverseReplay(cutoverStart);
        assertThat(liveCount("traces", postCutoverDeleted, workspaceId))
                .as("reverse-replay is idempotent: a repeat run keeps the post-cutover delete masked")
                .isZero();
        assertThat(liveCount("traces", idStrings(survivors.subList(1, survivors.size())), workspaceId))
                .as("reverse-replay is idempotent: a repeat run drops no live survivor")
                .isEqualTo(survivors.size() - 1);
    }

    /**
     * The reverse-replay postcondition gate: {@code 0} means every delete the bridge recorded since {@code cutover_start}
     * is masked on the restored {@code traces}, and any other number is an incomplete rollback serving rows users
     * deleted. The statement is reimplemented inline like the rest of this class
     * (see {@link #verifyReplayPostcondition}).
     *
     * <p>Each phase pins one way the gate can lie:
     * <ul>
     *   <li><b>a resurrected id → 1</b>, however many physical rows back it. The gate reads without {@code FINAL}
     *   across every replica, so an updated trace has several versions and each row comes back once per replica —
     *   counting rows would inflate an operator's damage estimate mid-rollback. The phase seeds a second version, but
     *   asserts only the answer: pinning the multiplicity would need background merges frozen, which these tests do not
     *   do, and the per-replica multiplicity is not reproducible on a single-replica container at all.</li>
     *   <li><b>two bridge events for one id → still 1.</b> A trace can be re-recorded (retry, re-delete), so the
     *   event count is not the id count either.</li>
     *   <li><b>masked → 0</b>, the passing case after a real replay, and the only result that ends a rollback.</li>
     *   <li><b>bridged under one project, alive under another → 0.</b> Ids are client-supplied and reusable across
     *   projects; a gate matching on {@code id} alone would report a live row the replay was never asked to touch.</li>
     *   <li><b>bridged before {@code cutover_start} → 0.</b> A trace deleted and recreated before the cutover is
     *   legitimately live while still carrying a bridge event; without the window filter the gate would call that a
     *   failed rollback and send the operator chasing a delete the replay was never asked to re-apply.</li>
     * </ul>
     */
    @Test
    void reverseReplayPostconditionGateCountsDistinctResurrectedIdsInTheReplaysWindowAndFullKey() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var otherProjectId = ID_GENERATOR.generateId();

        // Exact-count ids throughout (mintIds is per-week), so every expected number below is self-evident.
        // Deleted, bridged, then recreated — all BEFORE the cutover line. It is legitimately live, and its bridge event
        // is outside the replay's window, so the gate must not mistake it for a resurrection the replay missed.
        var preWindow = mintIdsAt(1, weekInstant(0, 1));
        seedTraces(preWindow, workspaceId, projectId);
        recordDeletionEvents(idStrings(preWindow), workspaceId, projectId.toString(), "user_request");
        lightweightDeleteScoped(idStrings(preWindow), workspaceId, projectId);
        insertRows(preWindow, workspaceId, projectId, "recreated", _ -> Instant.now());

        var cutoverStart = nowMicros();

        assertThat(liveCount("traces", idStrings(preWindow), workspaceId))
                .as("negative control: the pre-window row is live again after being recreated")
                .isEqualTo(1);
        assertThat(verifyReplayPostcondition(cutoverStart))
                .as("a live row bridged before cutover_start is outside the replay's window, so the gate reports 0")
                .isZero();

        // A post-cutover delete the replay did NOT mask, given a second version so rows outnumber ids while it lasts.
        // Nothing asserts on that multiplicity — a background merge may collapse it at any moment.
        var resurrected = mintIdsAt(1, weekInstant(1, 1));
        seedTraces(resurrected, workspaceId, projectId);
        insertRows(resurrected, workspaceId, projectId, "updated", _ -> Instant.now());
        recordDeletionEvents(idStrings(resurrected), workspaceId, projectId.toString(), "user_request");

        assertThat(verifyReplayPostcondition(cutoverStart))
                .as("one resurrected id counts once, whether or not its two versions have merged")
                .isEqualTo(1);

        // A second bridge event for the same id must not double it either. Deterministic, unlike the versions above:
        // it would fail a gate that joined the bridge instead of matching against it as a set.
        recordDeletionEvents(idStrings(resurrected), workspaceId, projectId.toString(), "user_request");
        assertThat(verifyReplayPostcondition(cutoverStart))
                .as("two bridge events for one id count once, not per event")
                .isEqualTo(1);

        // The passing case: the replay masks it, the gate clears.
        reverseReplay(cutoverStart);
        assertThat(verifyReplayPostcondition(cutoverStart))
                .as("after the replay masks the bridged delete, the gate reports 0")
                .isZero();

        // Full-key scope: id reused across projects, bridged and masked in one, alive in the other.
        var reused = mintIdsAt(1, weekInstant(2, 1));
        seedTraces(reused, workspaceId, projectId);
        seedTraces(reused, workspaceId, otherProjectId);
        recordDeletionEvents(idStrings(reused), workspaceId, projectId.toString(), "user_request");
        lightweightDeleteScoped(idStrings(reused), workspaceId, projectId);

        assertThat(liveCount("traces", idStrings(reused), workspaceId))
                .as("negative control: the reused id is still live under the other project")
                .isEqualTo(1);
        assertThat(verifyReplayPostcondition(cutoverStart))
                .as("a live row under a project the bridge never named is not a resurrection")
                .isZero();
    }

    /**
     * The sentinel repair and the counts that gate it (000004_rollback_sentinel_repair / _verify_sentinels, driven by
     * {@code rollback.sh --sentinel-repair-only}). Rows written into the still-Nullable original while
     * {@code traceColumnsNonNullable} was true carry the successor's encoding of an absent value — epoch
     * {@code end_time}, NaN {@code ttft} — and the original's MATERIALIZED {@code duration} turned the first of those
     * into a large negative, because the expression epoch-guards {@code start_time} but checks {@code end_time} for NULL
     * alone. The repair restores NULL and the mutation recomputes {@code duration} while rewriting the row.
     *
     * <p>The cohorts exist to pin what the repair must and must not touch:
     * <ul>
     *   <li><b>both sentinels, and each alone</b> — the repair carries two commands with different predicates in one
     *   mutation, so a row matching only one must get only that column restored, and the other must survive intact.</li>
     *   <li><b>a genuine negative duration</b> ({@code end_time} really before {@code start_time}, no sentinel) — the
     *   control that makes the gate's shape correct. It stays negative, so a total count of negative durations never
     *   reaches 0 on a healthy repair; gating on that number would report every successful run as a failure. This is
     *   why the shipped counts report {@code sentinel_end_time} / {@code sentinel_ttft} and deliberately not a
     *   negative-duration total.</li>
     *   <li><b>a clean row and an already-NULL row</b> — negative controls for a predicate that over-matched.</li>
     * </ul>
     *
     * <p>Restoring NULL is the only fix: {@code MATERIALIZE COLUMN duration} would re-evaluate the same expression
     * against the same sentinel. Asserting {@code duration IS NULL} after the repair is what pins that.
     */
    @Test
    void sentinelRepairRestoresNullAndRecomputesDurationLeavingGenuineNegativesAlone() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var startTime = Instant.parse("2025-03-04T10:00:00Z");
        var ended = startTime.plusMillis(100);
        // A real end_time BEFORE start_time: a negative duration owed to the source data, not to the flip.
        var endedEarly = startTime.minusSeconds(5);
        // The window the flag was live in. Everything above sits inside it; the cohort below deliberately does not.
        var windowFrom = "2025-03-04 09:00:00";
        var windowTo = "2025-03-04 11:00:00";

        // Exact counts per cohort, so every expected number below is self-evident.
        for (int i = 0; i < 4; i++) {
            insertShapedTrace(workspaceId, projectId, "sentinel-both", startTime, Instant.EPOCH, Double.NaN);
        }
        for (int i = 0; i < 2; i++) {
            insertShapedTrace(workspaceId, projectId, "sentinel-end-time", startTime, Instant.EPOCH, 1.5);
        }
        for (int i = 0; i < 2; i++) {
            insertShapedTrace(workspaceId, projectId, "sentinel-ttft", startTime, ended, Double.NaN);
        }
        for (int i = 0; i < 3; i++) {
            insertShapedTrace(workspaceId, projectId, "genuine-negative", startTime, endedEarly, 2.5);
        }
        for (int i = 0; i < 3; i++) {
            insertShapedTrace(workspaceId, projectId, "clean", startTime, ended, 2.5);
        }
        for (int i = 0; i < 2; i++) {
            insertShapedTrace(workspaceId, projectId, "absent", startTime, null, null);
        }
        // Matches the repair's predicate exactly but was written OUTSIDE the flag window, so its epoch end_time and NaN
        // ttft are values a client sent, not damage. Unbounded, the repair would set both to NULL with no way back.
        for (int i = 0; i < 2; i++) {
            insertShapedTrace(workspaceId, projectId, "outside-window", Instant.parse("2025-01-01T10:00:00Z"),
                    Instant.EPOCH, Double.NaN);
        }
        // The last_updated_at arm: created long before the window, updated inside it — which is where its sentinel came
        // from. Dropping that arm from either the repair or the counts would leave this row damaged and still pass.
        var historic = Instant.parse("2024-11-05T08:00:00Z");
        insertShapedTrace(workspaceId, projectId, "updated-in-window", startTime, Instant.EPOCH, Double.NaN,
                historic, Instant.parse("2025-03-04T10:30:00Z"));
        // The half-open boundaries. windowFrom is inclusive, windowTo exclusive, so exactly one of these is repaired;
        // flipping either operator, or swapping >= for >, moves one of them and fails.
        insertShapedTrace(workspaceId, projectId, "at-window-from", startTime, Instant.EPOCH, Double.NaN,
                historic, Instant.parse("2025-03-04T09:00:00Z"));
        insertShapedTrace(workspaceId, projectId, "at-window-to", startTime, Instant.EPOCH, Double.NaN,
                historic, Instant.parse("2025-03-04T11:00:00Z"));
        // KNOWN LIMITATION, pinned so it cannot be quietly forgotten. TraceDAO.UPDATE copies end_time/ttft verbatim when
        // the patch omits them and lets last_updated_at default to now64(6), so a trace patched inside the window (the
        // sentinel) and patched again after it ends up with a LIVE version outside the window. The repair clears the
        // older in-window version, so the counts reach 0 and report success while the live row stays damaged. Widening
        // the window to catch it would null genuine epoch values instead; see the runbook.
        var carried = ID_GENERATOR.generateId().toString();
        insertShapedTrace(carried, workspaceId, projectId, "carried-forward", startTime, Instant.EPOCH, Double.NaN,
                historic, Instant.parse("2025-03-04T10:30:00Z"));
        insertShapedTrace(carried, workspaceId, projectId, "carried-forward", startTime, Instant.EPOCH, Double.NaN,
                historic, Instant.parse("2025-03-04T12:00:00Z"));

        assertThat(sentinelCounts(windowFrom, windowTo))
                .as("before the repair, window-scoped: 9 keys are in the window — the 6 same-timestamp cohorts, the row"
                        + " updated inside it, the row exactly at windowFrom, and the carried-forward key's in-window"
                        + " version. The row at windowTo is excluded, the window being half-open")
                .isEqualTo(new SentinelCounts(9L, 9L, 9L, 0L));
        assertThat(countMatching(workspaceId, "duration < 0"))
                .as("negative control: 15 keys with a negative duration, of which only the 9 inside the window are this"
                        + " repair's business — which is what makes such a total useless as a gate")
                .isEqualTo(15);

        // The epoch literal pins 'UTC'. Unpinned it parses in the server timezone, so on a non-UTC host the predicate
        // matches nothing and the driver reports "nothing to repair" over damaged rows — a silent false negative on the
        // gate. The container runs UTC, so only an explicit foreign session timezone can catch a regression here.
        assertThat(sentinelCountsUnderForeignTimezone(windowFrom, windowTo))
                .as("the gate is independent of the server timezone: both the epoch literal and the window bounds are"
                        + " pinned to UTC")
                .isEqualTo(new SentinelCounts(9L, 9L, 9L, 0L));

        var beforeRepair = serverNow();
        repairSentinels(windowFrom, windowTo);

        // The two commands travel in ONE mutation, which is why the repair costs a single part rewrite rather than two.
        // Asserted because it is a claim the .sql header makes and nothing else would catch if ClickHouse split them.
        assertThat(sentinelRepairMutations(beforeRepair))
                .as("both commands ran, and under ONE mutation id — so the repair is a single pass over the parts, which"
                        + " is the whole reason for combining them")
                .isEqualTo(new MutationShape(1L, 2L));

        assertThat(sentinelCounts(windowFrom, windowTo))
                .as("the gate clears: no epoch end_time and no NaN ttft left on any replica")
                .isEqualTo(new SentinelCounts(0L, 0L, 0L, 0L));
        assertThat(countMatching(workspaceId, "duration < 0"))
                .as("7 remain after a fully successful repair — 3 genuine, 2 out-of-window, the one at the exclusive"
                        + " windowTo bound, and the carried-forward key — so this total is never the success criterion")
                .isEqualTo(7);

        // The property the window exists for. Without it these two are indistinguishable from the flag's damage, and
        // nothing could restore them: the parked successor encodes an absent end_time as this same epoch.
        assertThat(countMatching(workspaceId,
                "name = 'outside-window' AND end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC') AND isNaN(ttft)"))
                .as("a row matching the predicate but written outside the window keeps both of its values")
                .isEqualTo(2);

        // The last_updated_at arm and the two boundaries. Each of these fails on a different single-character change.
        assertThat(countMatching(workspaceId,
                "name = 'updated-in-window' AND end_time IS NULL AND ttft IS NULL AND duration IS NULL"))
                .as("created before the window but updated inside it: repaired, because the window matches either column")
                .isEqualTo(1);
        assertThat(countMatching(workspaceId,
                "name = 'at-window-from' AND end_time IS NULL AND ttft IS NULL"))
                .as("windowFrom is inclusive, so a row exactly on it is repaired")
                .isEqualTo(1);
        assertThat(countMatching(workspaceId,
                "name = 'at-window-to' AND end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC') AND isNaN(ttft)"))
                .as("windowTo is exclusive, so a row exactly on it keeps its sentinels")
                .isEqualTo(1);

        // The limitation, asserted rather than described. Change the window semantics without addressing it and this
        // flips, which is the point: the gate above reported success while this row is still serving an epoch end_time.
        assertThat(countMatchingLive(workspaceId,
                "name = 'carried-forward' AND end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')"))
                .as("KNOWN GAP: a sentinel carried forward past the window survives on the LIVE row, and the"
                        + " window-scoped counts cannot see it")
                .isEqualTo(1);

        assertThat(countMatching(workspaceId,
                "name = 'sentinel-both' AND end_time IS NULL AND ttft IS NULL AND duration IS NULL"))
                .as("both columns restored to NULL, and duration recomputed as NULL by the rewrite — not left negative,"
                        + " which is what a MATERIALIZE COLUMN would have done")
                .isEqualTo(4);
        assertThat(countMatching(workspaceId,
                "name = 'sentinel-end-time' AND end_time IS NULL AND ttft = 1.5 AND duration IS NULL"))
                .as("only the matching column is restored: the real ttft on an epoch-end_time row survives")
                .isEqualTo(2);
        assertThat(countMatching(workspaceId,
                "name = 'sentinel-ttft' AND ttft IS NULL AND end_time IS NOT NULL AND duration > 0"))
                .as("the ttft command leaves a real end_time and its positive duration untouched")
                .isEqualTo(2);
        assertThat(countMatching(workspaceId, "name = 'genuine-negative' AND end_time IS NOT NULL AND duration < 0"))
                .as("a genuine negative duration is not the repair's business and is left exactly as it was")
                .isEqualTo(3);
        assertThat(
                countMatching(workspaceId, "name = 'clean' AND end_time IS NOT NULL AND ttft = 2.5 AND duration > 0"))
                .as("an unaffected row is untouched by either predicate")
                .isEqualTo(3);
        assertThat(countMatching(workspaceId,
                "name = 'absent' AND end_time IS NULL AND ttft IS NULL AND duration IS NULL"))
                .as("a row that was already NULL stays NULL: neither predicate matches a NULL")
                .isEqualTo(2);
    }

    /**
     * rollback.sh refuses a wrong-stage run by reading two signals off the live {@code traces} — its engine and its
     * {@code end_time} nullability — and aborting unless they match the requested stage (the guard that stops a stage-A
     * {@code TRUNCATE} from destroying the parked original once the EXCHANGE has run). This drives the DB through the
     * three cutover states and asserts those signals are distinct in each, so the guard can always tell which stage is
     * valid. It validates the signals the guard reads, not the bash parsing itself — the script's own execution is
     * covered by the staging dry-run.
     */
    @Test
    void rollbackTopologySignalsDistinguishEveryStage() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        seedTraces(mintIds(SURVIVORS_PER_WEEK), workspaceId, projectId);
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }

        // Pre-EXCHANGE — original schema: a MergeTree with Nullable end_time. Only stage A is valid.
        assertThat(tableEngine("traces"))
                .as("pre-EXCHANGE traces is a MergeTree, not Distributed")
                .doesNotContain("Distributed");
        assertThat(columnType("traces", "end_time"))
                .as("pre-EXCHANGE end_time is Nullable (original schema)")
                .startsWith("Nullable");

        var cutoverStart = nowMicros();
        exchangeTables();

        // Post-EXCHANGE — successor schema under `traces`: still a MergeTree, but end_time is non-Nullable. Stage B is
        // valid; a stage-A run must now abort, since its guard requires Nullable end_time.
        assertThat(tableEngine("traces"))
                .as("post-EXCHANGE traces is still a MergeTree")
                .doesNotContain("Distributed");
        assertThat(columnType("traces", "end_time"))
                .as("post-EXCHANGE end_time is non-Nullable (successor schema)")
                .doesNotContain("Nullable");

        wrapInDistributed();

        // Post-wrap — Distributed wrapper: only stage C is valid.
        assertThat(tableEngine("traces"))
                .as("post-wrap traces is a Distributed wrapper")
                .isEqualTo("Distributed");

        // Roll back to the canonical baseline (traces = original; successor parked as traces_post_rollback_backup, which
        // @BeforeEach's reset recovers) so the next test starts clean. The stage-C reverse-replay still runs here; this
        // test bridged no deletes in the (cutoverStart, ∞) window, so it matches zero ids and deletes nothing.
        rollbackAfterWrap(cutoverStart);
    }

    /**
     * Un-wrap (000004_rollback_unwrap): reversing the {@code Distributed} wrap alone leaves the partitioned successor
     * live. This is the property that separates it from stage C, which reverses the whole cutover — so the assertions
     * that matter are the ones that would FAIL under stage C: post-cutover writes are still served, and the parked
     * original is still parked (un-wrap consumes nothing, so stage B/C remain available afterwards).
     *
     * <p>It also pins that no data moves: the successor's fidelity fingerprint read <i>through the wrapper</i> before the
     * un-wrap equals the one read off {@code traces} directly after it. The rename is metadata-only, and that is what
     * makes this cheap enough to be the default response to a wrap-only fault.
     *
     * <p>And it pins why no reverse-replay is needed: a delete applied post-wrap stays deleted afterwards for the trivial
     * reason that the same table stays live — nothing is promoted, so there is no frozen copy for it to resurrect from.
     */
    @Test
    void unwrapReversesTheWrapKeepingTheSuccessorAndItsPostCutoverWrites() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);
        seedFidelityCohort(workspaceId, projectId);

        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        exchangeTables();
        wrapInDistributed();

        // Written through the Distributed wrapper, i.e. after the cutover — exactly the rows a stage B/C promote makes
        // non-live. Un-wrap must keep them served.
        var postCutover = mintIds(3);
        seedTraces(postCutover, workspaceId, projectId);
        // Deleted post-wrap on the shard, which is where OPIK-7455 points the delete DAO once the wrap is live.
        var postWrapDeleted = Set.of(survivors.getFirst().id().toString());
        recordDeletionEvents(postWrapDeleted, workspaceId, projectId.toString(), "user_request");
        execute("DELETE FROM traces_local WHERE workspace_id = :workspace_id AND project_id = :project_id AND id IN :ids",
                statement -> statement.bind("workspace_id", workspaceId).bind("project_id", projectId.toString())
                        .bind("ids", postWrapDeleted));

        var throughWrapper = fingerprint("traces", Shape.NEW, workspaceId);

        // The signals rollback.sh's --unwrap-only guard reads before it will act: `traces` wrapped, and `traces_local`
        // holding the SUCCESSOR schema. The second is what stops the guard promoting an original that some earlier manual
        // step left under that name, which would revert the schema with none of stage B/C's flag reverts or repair.
        assertThat(isDistributed("traces"))
                .as("guard input: the wrap is applied")
                .isTrue();
        assertThat(columnType("traces_local", "end_time"))
                .as("guard input: traces_local is the successor, so promoting it cannot silently revert the schema")
                .doesNotStartWith("Nullable");

        unwrap();

        assertThat(isDistributed("traces"))
                .as("un-wrap removes the Distributed wrapper")
                .isFalse();
        assertThat(columnType("traces", "end_time"))
                .as("the live table is still the SUCCESSOR, not the original: un-wrap reverses sharding, not the cutover")
                .doesNotStartWith("Nullable");
        assertThat(tableExists("traces_local"))
                .as("the successor shard was promoted back into `traces`, so the sharding name is free")
                .isFalse();
        assertThat(tableExists("traces_dist_old"))
                .as("the data-less ex-wrapper is dropped, leaving no temp name behind")
                .isFalse();

        assertThat(fingerprint("traces", Shape.NEW, workspaceId))
                .as("un-wrap moves no data: the successor reads identically before (through the wrapper) and after")
                .isEqualTo(throughWrapper);
        assertThat(liveCount("traces", idStrings(postCutover), workspaceId))
                .as("post-cutover writes stay LIVE — the property stage B/C cannot preserve")
                .isEqualTo(postCutover.size());
        assertThat(liveCount("traces", postWrapDeleted, workspaceId))
                .as("a post-wrap delete stays deleted: the same table stays live, so there is nothing to resurrect from")
                .isZero();
        assertThat(liveCount("traces", idStrings(survivors.subList(1, survivors.size())), workspaceId))
                .as("every other survivor is intact")
                .isEqualTo(survivors.size() - 1);

        assertThat(tableExists("traces_pre_cutover_backup"))
                .as("the parked original is untouched, so stage B/C are still available after an un-wrap")
                .isTrue();
        assertThat(columnType("traces_pre_cutover_backup", "end_time"))
                .as("and it still holds the ORIGINAL schema — un-wrap consumed no backup")
                .startsWith("Nullable");
    }

    /**
     * The runbook's "Retrying the cutover after a stage B/C rollback — without re-backfilling" procedure. It rests on one
     * physical claim: {@code traces_post_rollback_backup} IS the object Liquibase created as {@code traces_local_v2} (a
     * ReplicatedMergeTree's replica path is fixed at CREATE and survives renames), so renaming it back yields a usable
     * shadow and the retry needs only a delta, not a second full backfill. That claim is what this pins — the procedure
     * is deliberately manual, but an operator will follow it under pressure, so the mechanism it depends on should not be
     * taken on trust.
     *
     * <p>It also pins the two consequences the runbook has to warn about, because both look like faults if unexpected:
     * the reused shadow is a <b>superset</b> of the restored original by exactly the post-cutover writes the rollback
     * discarded, so a fidelity compare legitimately differs there; and after the retry's {@code EXCHANGE} those rows are
     * <b>live again</b>.
     */
    @Test
    void rollbackBackupIsReusableAsTheShadowForARetryWithoutRebackfilling() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);
        seedFidelityCohort(workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        deltaInsert(backfillStart);

        // Walk the chain the reuse claim depends on, so a failure localizes itself instead of only showing a wrong end
        // state: the first backfill populated the shadow...
        assertThat(liveCount("traces_local_v2", idStrings(survivors), workspaceId))
                .as("the first backfill populated the shadow")
                .isEqualTo(survivors.size());

        var cutoverStart = nowMicros();
        exchangeTables();
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("...and the EXCHANGE made that copy live")
                .isEqualTo(survivors.size());
        // Accepted by the successor after cutover_start, so the stage-B promote makes it non-live. It is the row whose
        // fate the runbook has to be explicit about on a retry.
        var postCutover = mintIds(3);
        seedTraces(postCutover, workspaceId, projectId);

        // Fingerprint the shadow while it is still live. Row counts alone would accept a parked copy holding the right ids
        // with mangled or missing field values, which is not a reusable shadow — and reuse is the whole claim here.
        var parkedCopy = fingerprint("traces", Shape.NEW, workspaceId);

        rollbackExchangeBack(cutoverStart);
        // ...and the rollback parked that same copy rather than discarding it. This is the assertion the whole procedure
        // rests on: if the parked backup were empty (or recycled), reuse would be a re-backfill wearing a rename.
        assertThat(liveCount("traces_post_rollback_backup", idStrings(survivors), workspaceId))
                .as("...and the rollback parked that copy intact, which is what makes it reusable")
                .isEqualTo(survivors.size());
        assertThat(liveCount("traces", idStrings(postCutover), workspaceId))
                .as("baseline: the promote made the post-cutover writes non-live on the restored original")
                .isZero();

        // The documented reuse: hand the parked successor back to the shadow name. No re-backfill.
        execute("RENAME TABLE traces_post_rollback_backup TO traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });

        assertThat(liveCount("traces_local_v2", idStrings(survivors), workspaceId))
                .as("the reused shadow still holds everything the first backfill copied — this is the point of reusing it")
                .isEqualTo(survivors.size());
        assertThat(fingerprint("traces_local_v2", Shape.NEW, workspaceId))
                .as("and holds it unchanged: the rollback parked the copy field-for-field, so the rename yields a shadow "
                        + "the delta can resume onto rather than one that has to be rebuilt")
                .isEqualTo(parkedCopy);

        // Resume the normal forward flow from the ORIGINAL anchor, as the runbook prescribes.
        deltaInsert(backfillStart);

        // Fidelity: equal on the rows both sides have, and the shadow differs ONLY by the revived post-cutover writes.
        // A fidelity compare bounded to sealed history matches; an unbounded one legitimately reports these rows.
        assertThat(liveCount("traces_local_v2", idStrings(survivors), workspaceId))
                .as("survivors reconcile after the delta")
                .isEqualTo(survivors.size());
        assertThat(liveCount("traces_local_v2", idStrings(postCutover), workspaceId))
                .as("the reused shadow is a SUPERSET: it still carries the writes the rollback discarded")
                .isEqualTo(postCutover.size());
        assertThat(liveCount("traces", idStrings(postCutover), workspaceId))
                .as("...which the restored original does not have — hence the expected one-sided difference")
                .isZero();

        exchangeTables();

        assertThat(columnType("traces", "end_time"))
                .as("the retry's EXCHANGE lands the successor schema, from a shadow that was never re-backfilled")
                .doesNotStartWith("Nullable");
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("every originally-copied row is live after the retry")
                .isEqualTo(survivors.size());
        assertThat(liveCount("traces", idStrings(postCutover), workspaceId))
                .as("and the discarded post-cutover writes are LIVE AGAIN — the caveat the runbook must state")
                .isEqualTo(postCutover.size());
    }

    /**
     * The two properties that make un-wrap worth having as its own mode.
     *
     * <p><b>It needs no parked original.</b> Stages B and C both require {@code traces_pre_cutover_backup}, which
     * {@code finalize.sh} drops when it commits the cutover. Since the documented order is wrap → soak → finalize,
     * post-wrap-and-post-finalize is the expected steady state — and there, un-wrap is the only wrap recovery left.
     *
     * <p><b>The wrap becomes a switch.</b> wrap → un-wrap → wrap → un-wrap round-trips with the data intact, so a
     * suspected wrap fault can be backed out and re-applied once understood, rather than being a one-way door.
     *
     * <p>The finalized estate is simulated by renaming the parked original aside rather than dropping it: absence of the
     * name is the whole of what the guards read, and keeping the rows lets {@code @BeforeEach} restore the suite's
     * baseline afterwards (a real DROP would strip the only copy of the original schema for every later test).
     *
     * <p><b>The re-wrap here is the wrap SQL, not the driver</b> ({@link #wrapInDistributed()}), so "re-appliable" is a
     * statement about the DDL, not about {@code exchange_and_wrap.sh --wrap-only} — which deliberately refuses while the
     * parked original is missing, i.e. in exactly the finalized state this test simulates. That asymmetry is documented
     * in the runbook and printed by {@code rollback.sh}; asserting it belongs to the driver scope this suite excludes.
     */
    @Test
    void unwrapNeedsNoParkedOriginalAndTheWrapCanBeReapplied() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);
        seedFidelityCohort(workspaceId, projectId);

        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        exchangeTables();
        wrapInDistributed();

        execute("RENAME TABLE traces_pre_cutover_backup TO " + PARKED_BACKUP + " ON CLUSTER '{cluster}'", _ -> {
        });
        assertThat(tableExists("traces_pre_cutover_backup"))
                .as("finalized estate: the parked original is gone, so stage B/C have nothing to restore")
                .isFalse();

        // Read through the wrapper before the first reversal, then re-read at every transition below. Each rename is
        // metadata-only, so "the data survives" is a claim about content and not just about row counts holding steady.
        var beforeRoundTrip = fingerprint("traces", Shape.NEW, workspaceId);

        unwrap();

        assertThat(isDistributed("traces"))
                .as("un-wrap succeeds with no parked original — the recovery stage B/C cannot offer here")
                .isFalse();
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("the successor's rows are all still live after un-wrapping a finalized estate")
                .isEqualTo(survivors.size());
        assertThat(fingerprint("traces", Shape.NEW, workspaceId))
                .as("un-wrapping a finalized estate moved no data")
                .isEqualTo(beforeRoundTrip);

        // Re-apply, then reverse again: the wrap is a switch, not a one-way door.
        wrapInDistributed();
        assertThat(isDistributed("traces"))
                .as("the wrap can be re-applied after an un-wrap")
                .isTrue();
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("re-wrapped rows still read through the wrapper")
                .isEqualTo(survivors.size());
        assertThat(fingerprint("traces", Shape.NEW, workspaceId))
                .as("and read identically through it")
                .isEqualTo(beforeRoundTrip);

        unwrap();
        assertThat(isDistributed("traces"))
                .as("and reversed again — wrap/un-wrap round-trips")
                .isFalse();
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("data survives a full wrap/un-wrap round-trip")
                .isEqualTo(survivors.size());
        assertThat(fingerprint("traces", Shape.NEW, workspaceId))
                .as("field-for-field, across wrap -> un-wrap -> wrap -> un-wrap")
                .isEqualTo(beforeRoundTrip);

        // Hand the original back so the reset can rebuild the canonical baseline. The reset also recovers this name on
        // its own, so an assertion failure above cannot cascade into later tests.
        execute("RENAME TABLE " + PARKED_BACKUP + " TO traces_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
        });
    }

    /**
     * A trace deleted and then re-created/updated under the SAME id during the window is bridged as deleted but is live
     * again on the source (ids are client-supplied; the newer insert wins under FINAL). The replay's resurrection guard
     * must keep it on the destination — deleting it by key would drop a row that is live on the source (silent data
     * loss). Mirrors the delete_traffic + live_traffic overlap the local rehearsal produces. With the guard removed this
     * test fails (the resurrected rows come back zero).
     *
     * <p>The replay is run <b>twice</b> to also pin its idempotence: the runbook has the operator re-run delta+replay to
     * convergence, so a second replay must not change the result — in particular it must not eventually drop the
     * resurrected (live-on-source) rows.
     *
     * <p>The full-key replay branch carries the guard: a delete captured WITH its project (the only shape since
     * OPIK-7483) must spare a resurrected id.
     */
    @Test
    void deleteThenResurrectSurvivesTheReplay() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var resurrected = mintIds(3); // deleted then re-created under the same id
        var stayDeleted = mintIds(3); // deleted and NOT re-created
        seedTraces(survivors, workspaceId, projectId);
        seedTraces(resurrected, workspaceId, projectId);
        seedTraces(stayDeleted, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }

        // During the window: delete both cohorts (bridged), then re-create the resurrected cohort under the same ids
        // with a fresh last_updated_at — the newer version wins under FINAL, so they are live again on the source (caught
        // by the delta's last_updated_at arm since their created_at stays historical).
        recordDeletionEvents(idStrings(resurrected), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(resurrected), workspaceId);
        recordDeletionEvents(idStrings(stayDeleted), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(stayDeleted), workspaceId);
        // Recreate with a server-clock last_updated_at (a later now64(6), so >= backfillStart) — NOT the JVM clock,
        // whose skew vs the container could put it below backfillStart and make the delta miss the resurrection path.
        var resurrectedAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        insertRows(resurrected, workspaceId, projectId, "resurrected", _ -> resurrectedAt);

        deltaInsert(backfillStart);
        // Run the replay twice: it must be idempotent (re-runnable to convergence) and must not drop the resurrected
        // live-on-source rows on the second pass.
        replayDeletions(backfillStart);
        replayDeletions(backfillStart);

        assertThat(liveCount("traces_local_v2", idStrings(resurrected), workspaceId))
                .as("resurrection guard is idempotent: a deleted-then-recreated id stays live after a repeated replay")
                .isEqualTo(resurrected.size());
        assertThat(liveCount("traces_local_v2", idStrings(stayDeleted), workspaceId))
                .as("a deleted-and-not-recreated id is removed from the destination")
                .isZero();
        assertThat(liveCount("traces_local_v2", idStrings(survivors), workspaceId))
                .as("untouched survivors are intact")
                .isEqualTo(survivors.size());
    }

    /**
     * A delete bridged AFTER the main (step-2) replay but before the EXCHANGE must be masked by the final deletion replay
     * that {@code exchange_and_wrap.sh} runs right after capturing {@code cutover_start}. Otherwise it is covered by
     * neither the forward replay (already ran) nor the rollback reverse-replay ({@code event_time >= cutover_start}) and
     * leaks live across the swap. This pins that final-replay step (mirrors the driver's fold-in of the 000002
     * deletion-replay block into the exchange step).
     */
    @Test
    void finalReplayBeforeExchangeMasksDeletesBridgedAfterTheMainReplay() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var gapDeleted = mintIds(3); // deleted in the [main replay, EXCHANGE] gap
        seedTraces(survivors, workspaceId, projectId);
        seedTraces(gapDeleted, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        deltaInsert(backfillStart);
        replayDeletions(backfillStart); // step 2 (delta_replay.sh) — runs before the gap delete below

        // A delete lands AFTER the main replay. Without a final replay before the swap it leaks onto the successor.
        recordDeletionEvents(idStrings(gapDeleted), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(gapDeleted), workspaceId);
        assertThat(liveCount("traces_local_v2", idStrings(gapDeleted), workspaceId))
                .as("negative control: the gap delete has leaked onto the successor before the final replay")
                .isEqualTo(gapDeleted.size());

        // exchange_and_wrap.sh runs this final deletion replay right after capturing cutover_start, before the EXCHANGE.
        replayDeletions(backfillStart);
        exchangeTables();

        assertThat(liveCount("traces", idStrings(gapDeleted), workspaceId))
                .as("final deletion replay masks the gap delete — 0 leaks across the swap")
                .isZero();
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("survivors intact after the final replay + EXCHANGE")
                .isEqualTo(survivors.size());
    }

    /**
     * Schema-drift guard. The cutover copies a fixed column list, and the fidelity fingerprint also lists fixed
     * columns — so a base column added to {@code traces} by a future migration would be silently left uncopied, with no
     * existing check failing. This asserts the cutover's {@link #COPIED_COLUMNS} equals the live stored columns of
     * {@code traces}, and that {@code traces_local_v2} mirrors them plus only the {@code is_deleted} meta-column. Adding
     * a stored column to either table fails this until it is added to {@code COPIED_COLUMNS} (and thus to the copy).
     */
    /**
     * A {@code DateTime64} literal carrying no timezone is parsed in the SESSION's, while every column it meets is
     * {@code DateTime64(n, 'UTC')} — so on a non-UTC session an unpinned literal silently means something else. An
     * unpinned literal only diverges where the session is not UTC, so the session is set explicitly here. The backfill
     * has two such literals and this pins both:
     * <ul>
     *   <li>the WINDOW bounds. The row is seeded an hour into the week, so a bound read in a westward zone starts the
     *   window after it and the copy silently skips it — a hole in the migration, in the week the driver reported as
     *   done;</li>
     *   <li>the epoch SENTINEL the projection writes for an absent {@code end_time}. Unpinned it becomes a non-zero
     *   instant, and nothing fails at write time: the rollback's sentinel repair matches epoch exactly, so it would
     *   match nothing and report a clean table, while the fidelity compare normalizes an absent {@code end_time} to 0
     *   and would instead flag every migrated row that had one.</li>
     * </ul>
     *
     * <p>The sentinel is read back as {@code toUnixTimestamp64Micro}, so the assertion cannot depend on the same
     * parsing it exists to pin.
     */
    @Test
    void backfillIsUnaffectedByTheSessionTimezone() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        insertShapedTrace(workspaceId, projectId, "absent-both", weekInstant(0, 0), null, null);

        backfillWeek(0, WESTWARD_SESSION);

        var copied = copiedRow(workspaceId, "absent-both");
        assertThat(copied.rows()).as("the week's window still selects the row").isEqualTo(1);
        assertThat(copied.endTimeMicros()).as("epoch sentinel").isZero();
        assertThat(copied.ttftIsNaN()).as("NaN ttft sentinel").isTrue();
        // duration is MATERIALIZED by the shipped DDL, whose "not ended yet" branch compares end_time against its own
        // epoch literal. The sentinel the backfill wrote must satisfy that comparison, independently of the session.
        assertThat(copied.durationIsNaN()).as("NaN duration, so the DDL's epoch literal matched the one written")
                .isTrue();
    }

    /**
     * The delta's own pair of unpinned-literal sites: it re-reads the source for everything touched at or after the
     * anchor, on {@code created_at} OR {@code last_updated_at}. Both bounds parse the same value, so a westward session
     * moves them together and the delta silently narrows to rows touched after the shifted instant. That loses exactly
     * the updates the delta exists to carry — those made while the backfill ran — and, unlike a missed deletion, they
     * are invisible to the fidelity compare once the successor holds a row for the key at all.
     *
     * <p>Driven end to end rather than by counting the predicate: an INSERT SELECT reads through the session, so the
     * shift is observable in the copy itself, which the deletion replay's mutation cannot show.
     *
     * @see #deletionReplayWindowIsUnaffectedByTheSessionTimezone for the bound the session cannot reach
     */
    @Test
    void deltaIsUnaffectedByTheSessionTimezone() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        // The row the delta exists to carry: created before the anchor, so the backfill already copied it, then updated
        // after it. Only the OR's last_updated_at arm can select it. Anchor and update come from the same clock, so
        // their ordering is exact; the margin between them is minutes, orders of magnitude below any zone offset.
        var anchor = Instant.now();
        insertShapedTrace(ID_GENERATOR.generateId().toString(), workspaceId, projectId, "touched-during-backfill",
                weekInstant(0, 0), null, null, weekInstant(0, 0), anchor.plusSeconds(60));

        deltaInsert(ClickHouseDateTimeFormat.formatMicros(anchor), WESTWARD_SESSION);

        assertThat(copiedRow(workspaceId, "touched-during-backfill").rows())
                .as("the delta's window still selects the row")
                .isEqualTo(1);
    }

    /**
     * The {@code event_time} bound that decides which bridged deletions the replay applies. This is the
     * highest-consequence datetime literal in the runbook: a lightweight DELETE does not bump the version column, so the
     * delta cannot see it, and the replay is the only thing that stops the deletion leaking across the swap. A bound
     * resolved in a westward zone lands AFTER the recorded event, the replay matches nothing, and the deleted row stays
     * live on the successor — the exact leak the bridge exists to close, reported by a driver that exited 0.
     *
     * <p>Asserted on the bound's SELECTION rather than by driving the replay: {@code session_timezone} does not reach a
     * mutation's literal parsing, so a {@code DELETE} cannot be made to exhibit the shift, while the predicate it
     * filters on can. So the coverage splits: the deletion tests running under the container's own session cover the
     * replay end to end, and this covers its window not moving with the session.
     *
     * <p>The reverse replay and its postcondition carry the same bound against {@code cutover_start}, so this pins the
     * form all three share. The anchor is captured the way the drivers capture it — in UTC — so the pairing under test
     * is the real one.
     */
    @Test
    void deletionReplayWindowIsUnaffectedByTheSessionTimezone() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var id = ID_GENERATOR.generateId().toString();
        var at = weekInstant(0, 0);
        insertShapedTrace(id, workspaceId, projectId, "deleted-during-window", at, at, 1.0, at, at);

        var backfillStart = nowMicros();
        // Deleted after the anchor, with the bridge event the replay reads. event_time defaults to the server clock, so
        // it sits just after the anchor — inside the window by a margin far smaller than any timezone offset.
        lightweightDelete(Set.of(id), workspaceId);
        recordDeletionEvents(Set.of(id), workspaceId, projectId.toString(), "user");

        assertThat(bridgedDeletionsSince(backfillStart, WESTWARD_SESSION))
                .as("the replay's window still selects the bridged deletion")
                .isEqualTo(1);
    }

    /**
     * The sequence the runbook actually prescribes — backfill a week, then run the delta over the same anchor — must not
     * read as a tie on the successor. The delta re-copies every row written during the backfill window, and an
     * unmodified row keeps its {@code last_updated_at}, so the successor holds several physical rows at one version
     * until a merge collapses them. verify.sh runs before the EXCHANGE, on exactly those recent partitions, so counting
     * physical rows here would report a tie on a faithful copy and fail the cutover gate on the normal path.
     *
     * <p>Merge-independent in both directions: if a merge has already collapsed the duplicates there is one row, and if
     * it has not there are several with identical content. Either way the distinct count at the newest version is one.
     */
    @Test
    void theRunbooksBackfillThenDeltaSequenceIsNotATie() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var at = weekInstant(0, 0);
        var id = ID_GENERATOR.generateId().toString();
        insertShapedTrace(id, workspaceId, projectId, "copied-twice", at, at, 1.0, at, at);

        // The anchor must precede the row, or the delta's created_at/last_updated_at bound selects nothing and the
        // re-copy this test is about never happens. nowMicros() would sit after a seed in the anchor week.
        var backfillStart = ClickHouseDateTimeFormat.formatMicros(ANCHOR_MONDAY.minusWeeks(1).atStartOfDay()
                .toInstant(ZoneOffset.UTC));
        backfillWeek(0);
        deltaInsert(backfillStart);

        assertThat(rawRowCount("traces_local_v2", workspaceId))
                .as("the delta re-copied the row, so both physical rows are present")
                .isEqualTo(2);
        assertThat(liveCount("traces_local_v2", Set.of(id), workspaceId))
                .as("they dedup to one live row")
                .isEqualTo(1);
        assertThat(versionTies("traces_local_v2", Shape.NEW, workspaceId))
                .as("identical re-copies at one version are not a tie")
                .isZero();
    }

    /**
     * The tie aggregate's POSITIVE branch: a key whose newest version is shared by more than one row is counted, and one
     * whose newest version is unique is not, however many older versions it has.
     *
     * <p>Evaluated over a literal relation rather than a table, which is the only way this branch can be reached
     * deterministically. Holding two rows with an identical version needs a {@code ReplacingMergeTree} not to merge
     * them, and nothing here can guarantee that: {@code traces} is unpartitioned, and the successor's partition key is
     * {@code MATERIALIZED} from the row's own id, so two rows for one key always share a partition and stay merge
     * candidates. The relation stands in for the per-version row counts the shipped block derives from the table; the
     * scan that produces them, and its scoping, is covered against the real tables by
     * {@link #versionTiesDoesNotCountAKeyWhoseNewestVersionIsUnique()}.
     *
     * <p>The three keys separate ranking from totalling: {@code tied} has two rows at its newest version, {@code deep}
     * has more rows overall but only one at its newest, and {@code single} has one row. Only {@code tied} counts, so
     * summing or taking a plain maximum instead of {@code argMax} over the version fails here.
     */
    @Test
    void versionTieAggregateCountsOnlyASharedNewestVersion() {
        // (key, version, content) triples standing in for the rows the shipped block reads. 'tied' carries two
        // DIFFERENT contents at its newest version; 'dup' carries two identical ones, which is what the delta produces;
        // 'deep' has more rows overall but a single content at its newest version.
        var inner = """
                    SELECT key, version, uniqExact(content) AS distinct_at_version
                    FROM VALUES('key String, version UInt32, content String',
                                ('tied', 2, 'a'), ('tied', 2, 'b'), ('tied', 1, 'a'),
                                ('dup', 2, 'a'), ('dup', 2, 'a'), ('dup', 1, 'b'),
                                ('deep', 2, 'a'), ('deep', 1, 'a'), ('deep', 1, 'b'),
                                ('single', 1, 'a'))
                    GROUP BY key, version
                """;
        var ties = scalar(VERSION_TIE_AGGREGATE.formatted(inner), statement -> {
        });

        assertThat(ties).as("only the key whose newest version carries DIFFERING content counts as tied").isEqualTo(1);
    }

    /**
     * The {@code version-ties} block must not count a key merely because it has SEVERAL versions — only one whose NEWEST
     * version is shared by more than one row. That distinction is the whole content of the aggregate: ranking by version
     * rather than totalling rows. A key written twice with distinct {@code last_updated_at} exercises it, on both sides
     * of the copy, and is the case that must report zero.
     *
     * <p>The opposite case — a key whose newest version IS shared — is deliberately not constructed. Holding two rows
     * with an identical version in a {@code ReplacingMergeTree} means racing a background merge, so a test built on it
     * would be timing-dependent rather than strict; the same reason {@code sentinelCounts} does not pin superseded
     * versions either. The consequence is a known boundary: this pins that the aggregate ranks by version, and the
     * absence of {@code FINAL} — which would collapse the very rows the count exists to see — is argued in the
     * {@code version-ties} block itself rather than asserted here.
     */
    @Test
    void versionTiesDoesNotCountAKeyWhoseNewestVersionIsUnique() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var at = weekInstant(0, 0);
        var id = ID_GENERATOR.generateId().toString();

        // Two versions of one key: whether or not a merge has collapsed them, the newest is unique either way.
        insertShapedTrace(id, workspaceId, projectId, "older", at, at, 1.0, at, at);
        insertShapedTrace(id, workspaceId, projectId, "newer", at, at, 2.0, at, at.plusSeconds(1));
        backfillWeek(0);

        // Preconditions: versionTies returns 0 for an empty candidate set exactly as it does for a correctly-ranked
        // key, so without these every way the arrange step can silently fail leaves both assertions green — including
        // the shifted-window copy failure the sibling test exists to prove is possible.
        assertThat(liveCount("traces", Set.of(id), workspaceId)).as("fixture landed on the source").isEqualTo(1);
        assertThat(liveCount("traces_local_v2", Set.of(id), workspaceId)).as("fixture was copied").isEqualTo(1);

        assertThat(versionTies("traces", Shape.OLD, workspaceId))
                .as("a multi-version key is not a tie on the source")
                .isZero();
        assertThat(versionTies("traces_local_v2", Shape.NEW, workspaceId))
                .as("a multi-version key is not a tie on the successor")
                .isZero();
    }

    @Test
    void cutoverCopiesEveryBaseColumn() {
        var tracesBase = baseColumns("traces");
        var successorBase = baseColumns("traces_local_v2");
        var copied = Arrays.stream(COPIED_COLUMNS.split(","))
                .map(String::trim)
                .filter(column -> !column.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        assertThat(copied)
                .as("cutover COPIED_COLUMNS must equal the stored (non-materialized) columns of traces")
                .isEqualTo(tracesBase);
        assertThat(successorBase)
                .as("traces_local_v2 stored columns = traces stored columns + the is_deleted meta-column")
                .isEqualTo(union(tracesBase, Set.of("is_deleted")));
    }

    /**
     * Materialized-column parity guard, the complement to {@link #cutoverCopiesEveryBaseColumn()}. The backfill does not
     * copy materialized columns (the destination recomputes them), so they are outside the copy guard — but the two
     * tables must still expose the SAME materialized columns for as long as both exist, or a materialized column added
     * to one by a future migration and not the other leaves post-cutover queries referencing a column the live table
     * lacks. This checks presence; their values are covered by {@link #derivedFingerprint} / {@link #durationMismatches}.
     */
    @Test
    void successorMaterializedColumnsMatchSource() {
        assertThat(materializedColumns("traces_local_v2"))
                .as("traces_local_v2 must expose exactly the same MATERIALIZED columns as traces")
                .isEqualTo(materializedColumns("traces"));
    }

    /** Stored (physically materialized) columns of a table — excludes {@code MATERIALIZED} / {@code ALIAS} columns. */
    private Set<String> baseColumns(String table) {
        return columnNames(table, "default_kind NOT IN ('MATERIALIZED', 'ALIAS')");
    }

    /** MATERIALIZED (recomputed, not stored-from-insert) columns of a table. */
    private Set<String> materializedColumns(String table) {
        return columnNames(table, "default_kind = 'MATERIALIZED'");
    }

    /** Column names of a table filtered by a {@code system.columns} predicate. */
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

    // --- cutover steps (mirror the runbook SQL) ------------------------------------------------------------------

    /**
     * The runbook's backfill INSERT SELECT for one week. Columns map by name; {@code end_time} and {@code ttft} are the
     * two denullified columns, coalesced to their sentinels (epoch / NaN); {@code is_deleted} is omitted so the new
     * column defaults to 0. {@code apply_deleted_mask} stays at its default 1, so masked source rows are skipped.
     */
    private void backfillWeek(int week) {
        backfillWeek(week, "");
    }

    /**
     * As above with extra session settings appended; see {@link #WESTWARD_SESSION}.
     */
    private void backfillWeek(int week, String extraSettings) {
        var weekLo = ClickHouseDateTimeFormat.formatMicros(weekInstant(week, 0));
        var weekHi = ClickHouseDateTimeFormat.formatMicros(weekInstant(week + 1, 0));
        execute("""
                INSERT INTO traces_local_v2 (
                %s
                )
                SELECT
                %s
                FROM traces
                WHERE created_at >= toDateTime64(:week_lo, 9, 'UTC')
                  AND created_at < toDateTime64(:week_hi, 9, 'UTC')
                SETTINGS max_insert_block_size = 100000, max_partitions_per_insert_block = 2000%s
                """.formatted(COPIED_COLUMNS, COPIED_SELECT, extraSettings),
                statement -> statement.bind("week_lo", weekLo).bind("week_hi", weekHi));
    }

    /**
     * The delta-insert: re-copy every row written during the backfill window. Anchored on
     * {@code created_at OR last_updated_at >= backfill_start} so it is complete regardless of the client-supplied
     * {@code last_updated_at} on the batch-ingest path (see class Javadoc).
     */
    private void deltaInsert(String backfillStart) {
        deltaInsert(backfillStart, "");
    }

    /** As above with extra session settings appended; see {@link #WESTWARD_SESSION}. */
    private void deltaInsert(String backfillStart, String extraSettings) {
        execute("""
                INSERT INTO traces_local_v2 (
                %s
                )
                SELECT
                %s
                FROM traces
                WHERE created_at >= toDateTime64(:backfill_start, 6, 'UTC')
                   OR last_updated_at >= toDateTime64(:backfill_start, 6, 'UTC')
                SETTINGS max_insert_block_size = 100000, max_partitions_per_insert_block = 2000%s
                """.formatted(COPIED_COLUMNS, COPIED_SELECT, extraSettings),
                statement -> statement.bind("backfill_start", backfillStart));
    }

    /**
     * Reads the bridge for the cutover window and removes the captured deletes from the destination in a single
     * mutation (mirrors 000002). Single full-key branch: since OPIK-7483 every trace delete carries its project_id, so
     * events match the full key {@code (workspace_id, project_id, id)} (exact; a reused id in another project is
     * untouched) — without this replay those deletions silently leak across the swap. The branch also requires the id is
     * NOT currently live on the source (the resurrection guard), so a deleted-then-recreated id is not dropped. Returns
     * the wall time so the runbook can size it against the buffer window.
     */
    private long replayDeletions(String backfillStart) {
        var start = System.nanoTime();
        // allow_nondeterministic_mutations: a lightweight DELETE with a cross-table subquery is flagged
        // nondeterministic, but deletion_events_local is replicated and identical on every node and the window
        // predicate is fixed, so the subquery resolves to the same set on every replica. lightweight_deletes_sync = 2
        // waits for the mutation on every replica before returning, so verify/EXCHANGE never race an un-applied mask.
        execute("""
                DELETE FROM traces_local_v2
                WHERE (
                    (workspace_id, project_id, id) IN (
                        SELECT
                            workspace_id,
                            toFixedString(project_id, 36),
                            toFixedString(deleted_id, 36)
                        FROM deletion_events_local
                %s
                    )
                    AND (workspace_id, project_id, id) NOT IN (
                        SELECT
                            workspace_id,
                            project_id,
                            id
                        FROM traces
                        WHERE id IN (
                            SELECT toFixedString(deleted_id, 36)
                            FROM deletion_events_local
                            WHERE source_table = 'traces'
                              AND event_time >= toDateTime64(:since, 6, 'UTC')
                              AND length(deleted_id) = 36
                        )
                    )
                )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """.formatted(bridgeWindow(24)), statement -> statement.bind("since", backfillStart));
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /**
     * The atomic swap (000003 exchange block): EXCHANGE puts the successor under {@code traces} and the old data under
     * {@code traces_local_v2}, then a RENAME moves the old data to {@code traces_pre_cutover_backup} so its name says it
     * is the retained pre-cutover backup, not the "v2" successor.
     */
    private void exchangeTables() {
        execute("EXCHANGE TABLES traces AND traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        execute("RENAME TABLE traces_local_v2 TO traces_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
        });
    }

    // Gapless wrap (000003 wrap block): build the Distributed wrapper under a temp name first (its 'traces_local' target
    // need not exist yet), then one atomic multi-target RENAME rotates the data to traces_local and the wrapper into
    // traces (the name freed by the first clause), so traces is never absent on a node.
    private void wrapInDistributed() {
        execute("""
                CREATE TABLE traces_dist ON CLUSTER '{cluster}' AS traces
                ENGINE = Distributed('{cluster}', '%s', 'traces_local', sipHash64(project_id))
                """.formatted(DATABASE_NAME), _ -> {
        });
        execute("""
                RENAME TABLE
                    traces TO traces_local,
                    traces_dist TO traces
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
    }

    /** Rollback stage A (000004_rollback_stage_a): discard the disposable shadow; the live `traces` is untouched. */
    private void rollbackDiscardShadow() {
        execute("TRUNCATE TABLE traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
    }

    /**
     * Rollback stage B (000004_rollback_stage_b + reverse_replay): a single atomic multi-target RENAME rotates both
     * names back — the successor ({@code traces}) is parked as {@code traces_post_rollback_backup} (a retained backup,
     * distinct from the disposable {@code traces_local_v2} shadow) and the original ({@code traces_pre_cutover_backup})
     * returns to {@code traces} (the name freed by the first clause) — so there is no window where a partial failure
     * strands the successor under a wrong name. Then reverse-replay so a delete on the successor since
     * {@code cutoverStart} does not resurrect on the restored original.
     */
    private void rollbackExchangeBack(String cutoverStart) {
        execute("""
                RENAME TABLE
                    traces TO traces_post_rollback_backup,
                    traces_pre_cutover_backup TO traces
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
        reverseReplay(cutoverStart);
    }

    /**
     * Rollback stage C (000004_rollback_stage_c + reverse_replay): promote the parked original back to {@code traces}
     * GAPLESSLY with a single atomic multi-target RENAME that rotates all three names — the data-less wrapper
     * ({@code traces}) to an explicit temp name, the original ({@code traces_pre_cutover_backup}) to live {@code traces}
     * (the name freed by the first clause), and the successor shard to {@code traces_post_rollback_backup} (a retained
     * backup, distinct from the disposable {@code traces_local_v2} shadow). Then the ex-wrapper is dropped under its temp
     * name {@code traces_dist_old} — a name only the data-less wrapper ever held, so the DROP cannot hit the original
     * data regardless of replica timing. Then reverse-replay.
     */
    private void rollbackAfterWrap(String cutoverStart) {
        execute("""
                RENAME TABLE
                    traces TO traces_dist_old,
                    traces_pre_cutover_backup TO traces,
                    traces_local TO traces_post_rollback_backup
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_dist_old ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        reverseReplay(cutoverStart);
    }

    /**
     * Un-wrap (000004_rollback_unwrap): reverse the {@code Distributed} wrap and stop. A single atomic multi-target
     * {@code RENAME} rotates the data-less wrapper out to a temp name and promotes {@code traces_local} into the name it
     * frees, so {@code traces} is never absent on a node; the ex-wrapper is then dropped under {@code traces_dist_old},
     * a name only the wrapper ever held. Deliberately no promote and no reverse-replay — the successor stays live, so
     * nothing is abandoned and no bridged delete needs re-applying. It is stage C's rename minus the middle clause.
     */
    private void unwrap() {
        execute("""
                RENAME TABLE
                    traces TO traces_dist_old,
                    traces_local TO traces
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_dist_old ON CLUSTER '{cluster}' SYNC", _ -> {
        });
    }

    /**
     * The shared reverse-replay (000004_rollback_reverse_replay): re-apply the deletes captured since
     * {@code cutoverStart} onto the restored original, so they do not resurrect. Single full-key branch — since OPIK-7483
     * every delete carries its project_id, so the replay matches {@code (workspace_id, project_id, id)}. Unlike the
     * forward replay it carries NO resurrection guard by design: rollback abandons post-cutover writes while honoring
     * post-cutover deletes, so a bridged id is masked unconditionally (a guard would undo the user's delete). See the .sql header.
     */
    private void reverseReplay(String cutoverStart) {
        execute("""
                DELETE FROM traces
                WHERE (workspace_id, project_id, id) IN (
                    SELECT
                        workspace_id,
                        toFixedString(project_id, 36),
                        toFixedString(deleted_id, 36)
                    FROM deletion_events_local
                %s
                )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """.formatted(bridgeWindow(20)), statement -> statement.bind("since", cutoverStart));
    }

    private boolean isDistributed(String table) {
        return "Distributed".equals(tableEngine(table));
    }

    /** The table's engine (e.g. {@code ReplicatedReplacingMergeTree}, {@code Distributed}) from {@code system.tables}. */
    private String tableEngine(String table) {
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(
                "SELECT engine FROM system.tables WHERE database = :db AND name = :t")
                .bind("db", DATABASE_NAME)
                .bind("t", table)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("engine", String.class)))))
                .block();
    }

    /** A column's declared type (e.g. {@code Nullable(DateTime64(9, 'UTC'))}) from {@code system.columns}. */
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

    // --- seeding / mutation helpers ------------------------------------------------------------------------------

    private void seedTraces(List<CategorizedId> ids, String workspaceId, UUID projectId) {
        insertRows(ids, workspaceId, projectId, "seed", CategorizedId::createdAt);
    }

    /**
     * Batch-insert rows following the {@code TraceDAO.BATCH_INSERT} shape: {@code created_at} is the row's minted time,
     * {@code last_updated_at} is whatever {@code lastUpdatedAt} yields (server-now for upserts, a backdated stamp to
     * exercise the delta's {@code created_at} arm).
     */
    private void insertRows(List<CategorizedId> ids, String workspaceId, UUID projectId, String name,
            Function<CategorizedId, Instant> lastUpdatedAt) {
        var sql = TemplateUtils.getBatchSql("""
                INSERT INTO traces (
                    id,
                    workspace_id,
                    project_id,
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
                            :name,
                            :created_at<item.index>,
                            :last_updated_at<item.index>
                        )
                        <if(item.hasNext)>,<endif>
                    }>
                ;
                """, ids.size()).render();
        execute(sql, statement -> {
            statement.bind("workspace_id", workspaceId).bind("project_id", projectId).bind("name", name);
            for (int i = 0; i < ids.size(); i++) {
                statement.bind("id" + i, ids.get(i).id())
                        .bind("created_at" + i, ClickHouseDateTimeFormat.formatMicros(ids.get(i).createdAt()))
                        .bind("last_updated_at" + i,
                                ClickHouseDateTimeFormat.formatMicros(lastUpdatedAt.apply(ids.get(i))));
            }
        });
    }

    /**
     * Seeds a small cohort with EVERY migrated column populated with distinct, varied values — at nanosecond
     * {@code created_at} precision, and a share of NULL {@code end_time} / {@code ttft}. The fingerprint is
     * workspace-scoped, so these rows make it sensitive to every column and to the ns->us truncation: an all-default row
     * would hash-match on both sides even if the copy dropped a column. They are ordinary survivors (historical
     * created_at, never deleted). Inline literals (not binds) keep array/enum/NULL formatting reliable. Returns the ids.
     */
    private List<String> seedFidelityCohort(String workspaceId, UUID projectId) {
        var ids = new ArrayList<String>();
        var rows = new StringBuilder();
        int n = SEED_WEEKS * 3;
        for (int i = 0; i < n; i++) {
            var createdAt = weekInstant(i % SEED_WEEKS, i + 1).plusNanos(i * 137L + 3); // sub-microsecond ns remainder
            var id = ID_GENERATOR.generateId(createdAt).toString();
            ids.add(id);
            var createdNs = ClickHouseDateTimeFormat.formatNanos(createdAt);
            var endTime = (i % 3 == 0)
                    ? "NULL"
                    : "toDateTime64('" + ClickHouseDateTimeFormat.formatNanos(createdAt.plusMillis(50L + i)) + "', 9)";
            var ttft = (i % 4 == 0) ? "NULL" : String.valueOf(0.01 * (i + 1));
            var errorInfo = (i % 7 == 0) ? "{\"type\":\"Err" + i + "\"}" : "";
            var threadId = (i % 2 == 0) ? "" : "thread-" + i;
            rows.append(i == 0 ? "" : ",\n")
                    .append("('").append(id).append("','").append(workspaceId).append("','").append(projectId)
                    .append("','seed-fidelity',")
                    .append("toDateTime64('").append(createdNs).append("', 9),") // start_time
                    .append(endTime).append(",")
                    .append("'in-").append(i).append("','out-").append(i).append("',")
                    .append("'{\"model\":\"m").append(i).append("\",\"n\":").append(i).append("}',") // metadata
                    .append("['tag").append(i).append("','g").append(i % 4).append("'],") // tags
                    .append("toDateTime64('").append(createdNs).append("', 9),") // created_at (ns)
                    .append("toDateTime64('").append(ClickHouseDateTimeFormat.formatMicros(createdAt)).append("', 6),")
                    .append("'user").append(i % 5).append("','user").append((i + 1) % 5).append("',") // *_by
                    .append("'").append(errorInfo).append("','").append(threadId).append("',")
                    .append("'").append(i % 9 == 0 ? "hidden" : "default").append("',")
                    .append(10001 + (i % 2) * 10000).append(",") // truncation_threshold
                    .append("'slim-in-").append(i).append("','slim-out-").append(i).append("',")
                    .append(ttft).append(",")
                    .append("'").append(FIDELITY_SOURCES[i % FIDELITY_SOURCES.length]).append("',")
                    .append("'").append(FIDELITY_ENVIRONMENTS[i % FIDELITY_ENVIRONMENTS.length]).append("')");
        }
        execute("INSERT INTO traces (id, workspace_id, project_id, name, start_time, end_time, input, output, metadata, "
                + "tags, created_at, last_updated_at, created_by, last_updated_by, error_info, thread_id, "
                + "visibility_mode, truncation_threshold, input_slim, output_slim, ttft, source, environment) VALUES "
                + rows, _ -> {
                });
        return ids;
    }

    /**
     * One trace with explicitly chosen {@code start_time}, {@code end_time} and {@code ttft}, tagged by {@code name} so
     * a cohort can be asserted on afterwards. A {@code null} {@code endTime} or {@code ttft} stores SQL {@code NULL};
     * pass {@link Instant#EPOCH} or {@link Double#NaN} to store the sentinels the flip produced. The timestamps go over
     * the wire as text and through {@code toDateTime64} so the nanosecond precision the source column carries survives,
     * which a bound {@code Instant} would not guarantee — but they are still bound values, not spliced text. Each bind
     * is named for the column it fills and carries that column's own precision: {@code created_at} is
     * {@code DateTime64(9)} while {@code last_updated_at}, the {@code ReplacingMergeTree} version column, is
     * {@code DateTime64(6)}.
     */
    private void insertShapedTrace(String workspaceId, UUID projectId, String name, Instant startTime,
            Instant endTime, Double ttft) {
        insertShapedTrace(workspaceId, projectId, name, startTime, endTime, ttft, startTime, startTime);
    }

    /**
     * As above, with {@code created_at} and {@code last_updated_at} set independently of {@code start_time}. The repair
     * window matches on either, so a row created before it but updated inside it must still be repaired — a cohort no
     * caller of the shorter form can express, since it ties all three together.
     */
    private void insertShapedTrace(String workspaceId, UUID projectId, String name, Instant startTime,
            Instant endTime, Double ttft, Instant createdAt, Instant lastUpdatedAt) {
        insertShapedTrace(ID_GENERATOR.generateId().toString(), workspaceId, projectId, name, startTime, endTime, ttft,
                createdAt, lastUpdatedAt);
    }

    /** As above with an explicit id, so two versions of one key can be written. */
    private void insertShapedTrace(String id, String workspaceId, UUID projectId, String name, Instant startTime,
            Instant endTime, Double ttft, Instant createdAt, Instant lastUpdatedAt) {
        execute("""
                INSERT INTO traces (id, workspace_id, project_id, name, start_time, end_time, created_at,
                                    last_updated_at, ttft)
                VALUES (:id, :workspace_id, :project_id, :name, toDateTime64(:start_time, 9),
                        toDateTime64(:end_time, 9), toDateTime64(:created_at, 9),
                        toDateTime64(:last_updated_at, 6), :ttft)
                """, statement -> {
            statement.bind("id", id)
                    .bind("workspace_id", workspaceId)
                    .bind("project_id", projectId)
                    .bind("name", name)
                    .bind("start_time", ClickHouseDateTimeFormat.formatNanos(startTime))
                    .bind("created_at", ClickHouseDateTimeFormat.formatNanos(createdAt))
                    .bind("last_updated_at", ClickHouseDateTimeFormat.formatMicros(lastUpdatedAt));
            if (endTime == null) {
                statement.bindNull("end_time", String.class);
            } else {
                statement.bind("end_time", ClickHouseDateTimeFormat.formatNanos(endTime));
            }
            if (ttft == null) {
                statement.bindNull("ttft", Double.class);
            } else {
                statement.bind("ttft", ttft);
            }
        });
    }

    private void lightweightDelete(Set<String> ids, String workspaceId) {
        execute("""
                DELETE FROM traces
                WHERE workspace_id = :workspace_id
                  AND id IN :ids
                """,
                statement -> statement.bind("workspace_id", workspaceId).bind("ids", ids));
    }

    private void lightweightDeleteScoped(Set<String> ids, String workspaceId, UUID projectId) {
        execute("""
                DELETE FROM traces
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND id IN :ids
                """,
                statement -> statement
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId)
                        .bind("ids", ids));
    }

    /**
     * Batch INSERT into the bridge, mirroring {@code DeletionEventDAO}'s write shape. {@code projectId} is the real
     * owning project of each deleted trace — since OPIK-7483 every trace delete carries it (no project-less events).
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
                            'traces',
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

    /**
     * The reverse-replay postcondition, mirroring {@code 000004_rollback_verify_replay.sql} — same key, same
     * {@code toFixedString(36)} casts, same window and length guards, same aggregate, and the same database
     * qualification on both tables. Kept in step with that file; its {@code log_comment} is the one omission, being
     * observability rather than semantics, as elsewhere in this class.
     *
     * <p>The qualification is deliberate, not boilerplate: this is the only statement in the runbook that reads through
     * {@code clusterAllReplicas}, so qualifying both the outer table and the {@code IN} subquery keeps it correct
     * regardless of the connecting session's default database. A single-node container cannot show that.
     */
    private long verifyReplayPostcondition(String cutoverStart) {
        var sql = """
                SELECT uniqExact(workspace_id, project_id, id) AS resurrected
                FROM clusterAllReplicas('{cluster}', %s.traces)
                WHERE (workspace_id, project_id, id) IN (
                    SELECT
                        workspace_id,
                        toFixedString(project_id, 36),
                        toFixedString(deleted_id, 36)
                    FROM %s.deletion_events_local
                %s
                )
                """.formatted(DATABASE_NAME, DATABASE_NAME, bridgeWindow(20));
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("since", cutoverStart)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("resurrected", Long.class)))))
                .block();
    }

    /**
     * The sentinel repair (000004_rollback_sentinel_repair), reimplemented inline like the rest of this class. One
     * {@code ALTER} carrying both commands, as the shipped file does: neither predicate is on the primary key, so
     * ClickHouse cannot prune parts and a mutation rewrites every one — combining them halves that to a single pass.
     * Also mirrored: the absence of {@code ON CLUSTER} (the mutation travels by replication, not the distributed-DDL
     * queue), the {@code 'UTC'} on the epoch literal, and {@code mutations_sync = 2}, which is what makes the
     * postcondition an observation rather than an assumption on a replicated table. {@code log_comment} is the one
     * omission, being observability rather than semantics, as elsewhere in this class.
     */
    private void repairSentinels(String windowFrom, String windowTo) {
        execute("""
                ALTER TABLE traces
                    UPDATE end_time = NULL
                        WHERE end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')
                          AND (   (created_at      >= toDateTime64(:from, 6, 'UTC') AND created_at      < toDateTime64(:to, 6, 'UTC'))
                               OR (last_updated_at >= toDateTime64(:from, 6, 'UTC') AND last_updated_at < toDateTime64(:to, 6, 'UTC'))),
                    UPDATE ttft = NULL
                        WHERE isNaN(ttft)
                          AND (   (created_at      >= toDateTime64(:from, 6, 'UTC') AND created_at      < toDateTime64(:to, 6, 'UTC'))
                               OR (last_updated_at >= toDateTime64(:from, 6, 'UTC') AND last_updated_at < toDateTime64(:to, 6, 'UTC')))
                SETTINGS mutations_sync = 2
                """,
                statement -> statement.bind("from", windowFrom).bind("to", windowTo));
    }

    /**
     * The sentinel counts, mirroring {@code 000004_rollback_verify_sentinels.sql} — same predicates, same
     * {@code DateTime64} precision 9, same distinct aggregate over the full key, same absence of {@code FINAL}, and the
     * same database qualification through {@code clusterAllReplicas}.
     *
     * <p>One property of that file is deliberately NOT pinned here: that a superseded {@code ReplacingMergeTree} version
     * still carrying a sentinel is counted (and repaired) behind a clean newer one. Constructing it needs two versions
     * of one id to coexist, which a background merge may collapse at any moment, so any assertion on it would be flaky
     * rather than strict — the same reason the replay gate does not pin row multiplicity. The reasoning for omitting
     * {@code FINAL} is that the check must see exactly what the mutation rewrites; it is argued in the .sql header.
     */
    private SentinelCounts sentinelCounts(String windowFrom, String windowTo) {
        return sentinelCounts(windowFrom, windowTo, "");
    }

    /**
     * The same counts evaluated under a non-UTC {@code session_timezone}, which is the only way this suite can catch an
     * unpinned epoch literal: the container runs UTC. The clause is a compile-time constant rather than a parameter —
     * a {@code SETTINGS} value cannot be bound, so the alternative would be assembling one from an argument.
     */
    private SentinelCounts sentinelCountsUnderForeignTimezone(String windowFrom, String windowTo) {
        return sentinelCounts(windowFrom, windowTo, " SETTINGS session_timezone = 'America/New_York'");
    }

    private SentinelCounts sentinelCounts(String windowFrom, String windowTo, String settingsClause) {
        var sql = """
                SELECT
                    uniqExactIf((workspace_id, project_id, id), end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')) AS sentinel_end_time,
                    uniqExactIf((workspace_id, project_id, id), isNaN(ttft)) AS sentinel_ttft,
                    uniqExactIf((workspace_id, project_id, id),
                                duration < 0 AND end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')) AS negative_from_sentinel,
                    uniqExactIf((workspace_id, project_id, id), duration < 0 AND end_time IS NULL) AS stale_duration
                FROM clusterAllReplicas('{cluster}', %s.traces)
                WHERE (   (created_at      >= toDateTime64(:from, 6, 'UTC') AND created_at      < toDateTime64(:to, 6, 'UTC'))
                       OR (last_updated_at >= toDateTime64(:from, 6, 'UTC') AND last_updated_at < toDateTime64(:to, 6, 'UTC')))
                """
                .formatted(DATABASE_NAME)
                + settingsClause;
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("from", windowFrom)
                                .bind("to", windowTo)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> new SentinelCounts(
                                row.get("sentinel_end_time", Long.class),
                                row.get("sentinel_ttft", Long.class),
                                row.get("negative_from_sentinel", Long.class),
                                row.get("stale_duration", Long.class))))))
                .block();
    }

    /**
     * How ClickHouse recorded the sentinel repair: how many commands, under how many distinct {@code mutation_id}s.
     * {@code system.mutations} keeps one row per command but shares one id across an {@code ALTER}'s commands, which is
     * the property the repair's single-pass cost rests on.
     *
     * <p>Scoped two ways, because that table is cumulative and outlives {@link #resetTables()}: to the repair's own two
     * commands, and to mutations created at or after {@code since}. Without both, the schema migrations on this table
     * (Liquibase uses the same multi-command form, so one of its ids also covers two commands) and any earlier test's
     * mutations would be counted here.
     */
    private MutationShape sentinelRepairMutations(String since) {
        return template.nonTransaction(connection -> Mono.from(connection.createStatement("""
                SELECT uniqExact(mutation_id) AS mutationIds, count() AS commands
                FROM system.mutations
                WHERE database = :db
                  AND table = 'traces'
                  AND create_time >= parseDateTimeBestEffort(:since)
                  AND (command LIKE '%UPDATE end_time = NULL WHERE%' OR command LIKE '%UPDATE ttft = NULL WHERE%')
                """)
                .bind("db", DATABASE_NAME)
                .bind("since", since)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> new MutationShape(
                        row.get("mutationIds", Long.class),
                        row.get("commands", Long.class))))))
                .block();
    }

    /** Server clock, for bounding a {@code system.mutations} read to what a test issued after this point. */
    private String serverNow() {
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(
                "SELECT toString(now()) AS n").execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("n", String.class)))))
                .block();
    }

    /** Distinct {@code mutation_id}s and command rows behind one {@code ALTER}. */
    private record MutationShape(long mutationIds, long commands) {
    }

    /**
     * The counts {@code 000004_rollback_verify_sentinels.sql} returns. {@code endTime}, {@code ttft} and
     * {@code staleDuration} are gates; {@code negativeFromSentinel} is context for sizing the damage before a repair.
     */
    private record SentinelCounts(long endTime, long ttft, long negativeFromSentinel, long staleDuration) {
    }

    // --- query helpers -------------------------------------------------------------------------------------------

    /**
     * Distinct keys in the live {@code traces} matching a raw predicate. No {@code FINAL}, matching the scope of the
     * repair mutation and of the counts that gate it.
     */
    /**
     * What the copy landed on the successor for one named row: whether it arrived at all, and the two denullified
     * columns. {@code end_time} is read as microseconds since the epoch so the assertion needs no datetime literal of
     * its own — it must not depend on the parsing it exists to pin. One query, so {@code any()} over a single-row match
     * is that row; callers assert {@code rows} first, so an empty match cannot be mistaken for a value.
     */
    private CopiedRow copiedRow(String workspaceId, String name) {
        var sql = """
                SELECT
                    count() AS rows,
                    any(toUnixTimestamp64Micro(end_time)) AS end_time_micros,
                    any(isNaN(ttft)) AS ttft_is_nan,
                    any(isNaN(duration)) AS duration_is_nan
                FROM traces_local_v2
                WHERE workspace_id = :workspace_id AND name = :name
                """;
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("workspace_id", workspaceId)
                                .bind("name", name)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> new CopiedRow(
                                row.get("rows", Long.class),
                                row.get("end_time_micros", Long.class),
                                row.get("ttft_is_nan", Boolean.class),
                                row.get("duration_is_nan", Boolean.class))))))
                .block();
    }

    private record CopiedRow(long rows, long endTimeMicros, boolean ttftIsNaN, boolean durationIsNaN) {
    }

    /**
     * Bridge events the deletion replay's window selects, mirroring the {@code event_time} bound of 000002's replay
     * subquery — the same bound the rollback's reverse replay carries against {@code cutover_start}.
     */
    private long bridgedDeletionsSince(String backfillStart, String extraSettings) {
        return scalar("""
                SELECT count() AS c
                FROM deletion_events_local
                %s
                %s
                """.formatted(bridgeWindow(16), extraSettings.replaceFirst("^, ", "SETTINGS ")),
                statement -> statement.bind("since", backfillStart));
    }

    /**
     * The {@code version-ties} block, reimplemented inline like the rest of this class: keys whose newest
     * {@code last_updated_at} is carried by more than one DISTINCT row content. Distinct content rather than row count
     * is the whole point — the cutover puts several identical rows at one version on the successor, because 000002's
     * delta re-copies rows the backfill already wrote and an unmodified row keeps its {@code last_updated_at}. Counting
     * rows would call that a tie and fail the gate on a faithful copy. No {@code FINAL}: under it the rows this counts
     * collapse to one.
     *
     * <p>The fingerprint comes from {@code rowHash}, the same normalization {@code fingerprint} uses, so "distinct"
     * means distinct in the sense the gate cares about. Takes the table and its schema shape because the shipped block
     * reads both sides; that block selects candidates by the compare's window and sample predicates, while this
     * substitutes a per-workspace filter, the candidate set not being what is under test.
     */
    /** Physical rows, without {@code FINAL}, so a test can assert that duplicates it relies on are actually present. */
    private long rawRowCount(String table, String workspaceId) {
        return scalar("SELECT count() AS c FROM %s WHERE workspace_id = :workspace_id".formatted(table),
                statement -> statement.bind("workspace_id", workspaceId));
    }

    private long versionTies(String table, Shape shape, String workspaceId) {
        var inner = """
                    SELECT
                        (workspace_id, project_id, id) AS key,
                        last_updated_at AS version,
                        uniqExact(%s) AS distinct_at_version
                    FROM %s
                    WHERE workspace_id = :workspace_id
                    GROUP BY key, version
                """.formatted(rowHash(shape == Shape.OLD ? OLD_HASH_OVERRIDES : NEW_HASH_OVERRIDES), table);
        return scalar(VERSION_TIE_AGGREGATE.formatted(inner),
                statement -> statement.bind("workspace_id", workspaceId));
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

    private long countMatching(String workspaceId, String predicate) {
        var sql = """
                SELECT uniqExact(workspace_id, project_id, id) AS c
                FROM traces
                WHERE workspace_id = :workspace_id AND (%s)
                """.formatted(predicate);
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql).bind("workspace_id", workspaceId).execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("c", Long.class)))))
                .block();
    }

    /** As {@link #countMatching}, but {@code FINAL}-collapsed, so the predicate is asked of the LIVE row only. */
    private long countMatchingLive(String workspaceId, String predicate) {
        var sql = """
                SELECT uniqExact(workspace_id, project_id, id) AS c
                FROM traces FINAL
                WHERE workspace_id = :workspace_id AND (%s)
                """.formatted(predicate);
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql).bind("workspace_id", workspaceId).execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("c", Long.class)))))
                .block();
    }

    /** Distinct live (mask-honored) ids from {@code table} within {@code ids} — collapses ReplacingMergeTree versions. */
    private long liveCount(String table, Set<String> ids, String workspaceId) {
        if (ids.isEmpty()) {
            return 0L;
        }
        var sql = """
                SELECT uniqExact(id) AS c
                FROM %s FINAL
                WHERE workspace_id = :workspace_id
                  AND id IN :ids
                """.formatted(table);
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("workspace_id", workspaceId)
                                .bind("ids", ids)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("c", Long.class)))))
                .block();
    }

    private String destinationPartitionId(UUID id, String workspaceId) {
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement("""
                                SELECT _partition_id AS partition_id
                                FROM traces_local_v2
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

    private long liveCountScoped(String table, Set<String> ids, String workspaceId, UUID projectId) {
        var sql = """
                SELECT uniqExact(id) AS c
                FROM %s FINAL
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND id IN :ids
                """.formatted(table);
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("workspace_id", workspaceId)
                                .bind("project_id", projectId)
                                .bind("ids", ids)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("c", Long.class)))))
                .block();
    }

    private Set<String> newestNames(String table, Set<String> ids, String workspaceId) {
        var sql = """
                SELECT name
                FROM %s FINAL
                WHERE workspace_id = :workspace_id
                  AND id IN :ids
                """.formatted(table);
        return template.stream(connection -> Flux.from(connection.createStatement(sql)
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

    /**
     * A migration schema shape. OLD is the source layout (Nullable end_time/ttft, nanosecond timestamps); NEW is the
     * successor layout (epoch / NaN sentinels, microsecond timestamps). The per-row hash normalizes each shape to the
     * same canonical value for a faithfully-migrated row, so equal fingerprints prove no data was altered.
     */
    private enum Shape {
        OLD,
        NEW
    }

    @Builder(toBuilder = true)
    private record Fingerprint(long count, long checksum) {
    }

    /**
     * Order-independent (count, checksum) fingerprint of the deduped, mask-honored, normalized rows for a workspace.
     * {@code FINAL} collapses ReplacingMergeTree versions to the winner; the default {@code apply_deleted_mask} excludes
     * lightweight-deleted rows; the per-row {@code cityHash64} canonicalizes the two schema shapes so a faithful copy
     * hashes identically. {@code sum} needs no sort (bounded memory) and, unlike {@code groupBitXor}, does not cancel a
     * colliding pair within a table; with {@code id} in every row hash, a changed, missing or extra row flips the
     * aggregate. Materialized/derived columns and {@code is_deleted} are excluded — they are recomputed, not migrated
     * data; their expression parity is checked separately by {@link #derivedFingerprint} and {@link #durationMismatches}.
     */
    private Fingerprint fingerprint(String table, Shape shape, String workspaceId) {
        var hash = rowHash(shape == Shape.OLD ? OLD_HASH_OVERRIDES : NEW_HASH_OVERRIDES);
        var sql = """
                SELECT
                    count() AS c,
                    sum(%s) AS h
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
     * key), the three {@code *_length}s, {@code truncated_input} / {@code truncated_output} and {@code output_keys}.
     * Each is the same MATERIALIZED expression over faithfully-copied base columns on both tables, so equal fingerprints
     * prove the successor's expressions did not drift from the source's. {@code id_at} is wrapped in {@code toDateTime}
     * because the source's {@code id_at} is a 32-bit {@code DateTime} while the successor's is a {@code DateTime64}: both
     * are second precision, so the cast only unifies the column type — a raw cross-type hash would differ even for
     * identical instants.
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
                        truncated_output,
                        toString(output_keys))) AS h
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
     * successor computes it from the microsecond copy and is {@code NaN} when unset. So a faithful row is unset on both
     * (source NULL, dest NaN) or set on both within 1.5 microseconds (0.0015 ms); anything else is a real divergence.
     * The bound is 1.5 us, not 1 us: truncating both the start and end timestamps ns-to-us can each shift the computed
     * duration by up to ~1 us, so 0.0015 ms is a deliberate small margin over that (tightening it risks a flaky test).
     */
    private long durationMismatches(String workspaceId) {
        var sql = """
                SELECT count() AS c
                FROM (
                    SELECT id, duration AS d FROM traces FINAL
                    WHERE workspace_id = :workspace_id AND name = 'seed-fidelity'
                ) AS s
                INNER JOIN (
                    SELECT id, duration AS d FROM traces_local_v2 FINAL
                    WHERE workspace_id = :workspace_id AND name = 'seed-fidelity'
                ) AS t USING (id)
                WHERE NOT (
                    (isNaN(t.d) AND s.d IS NULL)
                    OR (NOT isNaN(t.d) AND s.d IS NOT NULL AND abs(s.d - t.d) <= 0.0015)
                )
                """;
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(sql)
                .bind("workspace_id", workspaceId)
                .execute())
                .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("c", Long.class)))))
                .block();
    }

    // Canonical per-row hash, BUILT from COPIED_COLUMNS ({@link #rowHash}) so it covers every copied column by
    // construction: a column added to COPIED_COLUMNS (which cutoverCopiesEveryBaseColumn pins to the live schema) is
    // automatically hashed and can never be silently left value-unverified. Each column hashes as-is unless it needs
    // shape-specific normalization, supplied by these override maps: timestamps as their microsecond epoch (ns
    // truncated to us, matching the copy); absent end_time as 0 (source NULL / dest epoch) and absent ttft as 'nan'
    // (source NULL / dest NaN); enums and project_id via toString; tags joined on the \x1f unit separator. A future
    // denullified column needs a matching override in both maps; without one it still hashes as-is (included, just not
    // normalized), and a wrong sentinel there makes dest != source so the fidelity assertion still catches it.
    private static final Map<String, String> OLD_HASH_OVERRIDES = Map.ofEntries(
            Map.entry("project_id", "toString(project_id)"),
            Map.entry("start_time", "toUnixTimestamp64Micro(toDateTime64(start_time, 6))"),
            Map.entry("end_time", "coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0))"),
            Map.entry("created_at", "toUnixTimestamp64Micro(toDateTime64(created_at, 6))"),
            Map.entry("last_updated_at", "toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6))"),
            Map.entry("tags", "arrayStringConcat(tags, '\\x1f')"),
            Map.entry("visibility_mode", "toString(visibility_mode)"),
            Map.entry("ttft", "if(ttft IS NULL, 'nan', toString(ttft))"),
            Map.entry("source", "toString(source)"),
            Map.entry("environment", "toString(environment)"));

    private static final Map<String, String> NEW_HASH_OVERRIDES = Map.ofEntries(
            Map.entry("project_id", "toString(project_id)"),
            Map.entry("start_time", "toUnixTimestamp64Micro(start_time)"),
            Map.entry("end_time", "toUnixTimestamp64Micro(end_time)"),
            Map.entry("created_at", "toUnixTimestamp64Micro(created_at)"),
            Map.entry("last_updated_at", "toUnixTimestamp64Micro(last_updated_at)"),
            Map.entry("tags", "arrayStringConcat(tags, '\\x1f')"),
            Map.entry("visibility_mode", "toString(visibility_mode)"),
            Map.entry("ttft", "if(isNaN(ttft), 'nan', toString(ttft))"),
            Map.entry("source", "toString(source)"),
            Map.entry("environment", "toString(environment)"));

    /**
     * {@link #BRIDGE_WINDOW_PREDICATE} with every line after the first indented by {@code spaces}, so it can be
     * interpolated into a nested subquery and still read as SQL. The first line is left bare: the {@code %s} it
     * replaces already sits at the right column.
     */
    private static String bridgeWindow(int spaces) {
        return BRIDGE_WINDOW_PREDICATE.replace("\n", "\n" + " ".repeat(spaces));
    }

    /**
     * The per-row fidelity hash for a shape, generated from {@link #COPIED_COLUMNS} in order so every copied column is
     * hashed. Each column contributes its {@code overrides} expression, or the bare column name when no normalization is
     * needed. Argument order matches on both shapes (both iterate COPIED_COLUMNS), so a faithfully-migrated row hashes
     * identically under {@link #OLD_HASH_OVERRIDES} and {@link #NEW_HASH_OVERRIDES}.
     */
    private static String rowHash(Map<String, String> overrides) {
        var args = Arrays.stream(COPIED_COLUMNS.split(","))
                .map(String::trim)
                .filter(column -> !column.isEmpty())
                .map(column -> overrides.getOrDefault(column, column))
                .collect(Collectors.joining(",\n    "));
        return "cityHash64(\n    " + args + ")";
    }

    // --- primitives ----------------------------------------------------------------------------------------------

    private void execute(String sql, Consumer<Statement> binder) {
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated())).then();
        }).block();
    }

    private List<CategorizedId> mintIds(int perWeek) {
        var ids = new ArrayList<CategorizedId>();
        for (int week = 0; week < SEED_WEEKS; week++) {
            for (int i = 0; i < perWeek; i++) {
                var createdAt = weekInstant(week, i + 1);
                ids.add(CategorizedId.builder().id(ID_GENERATOR.generateId(createdAt)).createdAt(createdAt).build());
            }
        }
        return ids;
    }

    /** Ids created "now" — used for rows written during the window, so their created_at is >= backfill_start. */
    private List<CategorizedId> mintIdsAt(int count, Instant createdAt) {
        var ids = new ArrayList<CategorizedId>();
        for (int i = 0; i < count; i++) {
            ids.add(CategorizedId.builder().id(ID_GENERATOR.generateId(createdAt)).createdAt(createdAt).build());
        }
        return ids;
    }

    private static Set<String> idStrings(List<CategorizedId> ids) {
        return ids.stream().map(id -> id.id().toString()).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var union = new ArrayList<>(a);
        union.addAll(b);
        return Set.copyOf(union);
    }

    private static String deltaName() {
        return "delta-upserted";
    }

    /** A within-day offset so ids/created_at in the same week are distinct but stay inside their weekly partition. */
    private Instant weekInstant(int weekOffset, int secondOffset) {
        return ANCHOR_MONDAY.plusWeeks(weekOffset).atTime(1, 0).plusSeconds(secondOffset).toInstant(ZoneOffset.UTC);
    }

    @Builder(toBuilder = true)
    private record CategorizedId(UUID id, Instant createdAt) {
    }
}
