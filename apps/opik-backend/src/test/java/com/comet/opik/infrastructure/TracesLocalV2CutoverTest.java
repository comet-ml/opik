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
 * End-to-end validation of the cutover that migrates {@code traces} to its partitioned, sharding-ready
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
 * <p>Nothing here reads a reference file, deliberately. The statements below are copied, and drift between a copy and
 * the file it mirrors is accepted: it is caught by the cutover rehearsal, which runs the drivers themselves. What this
 * suite owns is the cutover's <i>logic</i>. The one thing it does pin to the live database is the column list
 * ({@link #cutoverCopiesEveryBaseColumn()}), because a base column silently left uncopied is data loss rather than
 * drift — and the reference files carry that same list, so a failure here is the prompt to update them too.
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
 * <p><b>Writes must survive the swap too (OPIK-8238).</b> Deletions were only half the problem. The last write-copying
 * statement is the delta INSERT, and between it and the {@code EXCHANGE} the procedure interposes a deletion replay, the
 * operator's go/no-go gap, the settle gate and a second replay — so every trace written to the old table in that window
 * is orphaned when it is parked. Nothing holds writes across that window — the procedure takes no ingestion-path hold
 * (OPIK-8239). Reconciliation is deliberately POST-swap, because a pre-swap sweep cannot converge against a live source
 * while a post-swap one converges against a frozen table by construction.
 * The suite covers both directions of it: the forward sweep with a negative control proving the step is load-bearing,
 * the exclusion that stops a post-swap delete being resurrected, the newer-version-wins property that makes the sweep
 * safe against live traffic, the delete-side residual the frozen resurrection guard now closes, the per-row staleness
 * scope that stops that same frozen guard destroying a post-swap re-creation (with its own negative control), the
 * residual that same scope leaves open when a client supplies a future {@code last_updated_at} — pinned with the
 * advisory that reports it, so the trade-off behind the predicate cannot be flipped silently — the reverse re-import
 * with sentinel&rarr;{@code NULL} denormalization and the sweep/replay ordering that keeps a re-created key deleted,
 * and the four-count postcondition's classification.
 *
 * <p>It also confirms {@code EXCHANGE TABLES ... ON CLUSTER} on the single-shard cluster, the sharding-ready
 * {@code Distributed} wrapper reading transparently on one shard, newest-version-wins for concurrent upserts, and it
 * measures the replay wall time, which the runbook counts toward the cutover tail. Finally it proves the
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
 * (e.g. {@code DeletionEventTest}).
 *
 * <p><b>Scope: this gate validates the cutover SQL logic, not the driver scripts.</b> The safety guards in the runbook's
 * bash drivers — {@code backfill.sh}'s reconciliation abort, {@code rollback.sh}'s wrong-stage topology assertions, the
 * replication-settle gate both {@code exchange_and_wrap.sh} and {@code reconcile.sh} run (each with its own table
 * scope), {@code reconcile.sh}'s direction detection and pass loop, {@code finalize.sh}'s empty-live refusal — are
 * exercised by the OPIK-6901 staging dry-run, not by this test (which runs the SQL those scripts wrap, directly). This
 * test asserts the logic is correct when invoked; the staging rehearsal asserts the scripts invoke it safely. What this
 * suite does cover of the reconciliation is every statement those drivers issue, and its postcondition's four counts.
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
            coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) AS end_time,
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
    void cutoverPreservesEveryDeletionAcrossExchange() {
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
        // contention), so a hard bound here would be a flaky gate on a non-correctness property. The runbook counts it
        // toward the cutover tail during the rehearsal; correctness is asserted below (the mask is applied).
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
     * The forward post-swap reconciliation (000006 {@code forward-sweep} + 000002's deletion replay aimed at the live
     * table), and the negative control that proves it is load-bearing.
     *
     * <p>This is the defect OPIK-8238 exists for. The last write-copying statement is the delta INSERT; everything
     * written to the old table after it is orphaned when that table is parked, and nothing holds those writes across
     * the swap. The negative control here is the shape a real cutover hit: without the sweep, a trace written in the
     * gap is simply gone from the live table while sitting intact in the backup.
     *
     * <p>The assertions run in the order an operator would trust them: the trace is back, the gate reports zero across
     * all three of its gating buckets, and the whole workspace's normalized fingerprint matches the frozen backup —
     * presence, version and payload, not just a row count.
     */
    @Test
    void postSwapSweepRestoresTheGapAndWithoutItThoseWritesAreLost() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);
        seedFidelityCohort(workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        // delta_start: the instant the LAST delta pass began reading. Everything written after it lands only on the old
        // table, which is exactly what the post-swap sweep has to carry.
        var deltaStart = nowMicros();
        deltaInsert(backfillStart);
        replayDeletions(backfillStart);

        // The gap: written to the OLD traces after that read, so neither the delta nor the final replay saw it.
        var gapAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        var gapWritten = mintIdsAt(5, gapAt);
        insertRows(gapWritten, workspaceId, projectId, "gap", _ -> gapAt);

        exchangeTables();
        var swapDone = nowMicros();

        assertThat(liveCount("traces", idStrings(gapWritten), workspaceId))
                .as("negative control: with reconciliation skipped, every trace written in the gap is LOST from the live"
                        + " table — the failure this whole step exists to repair")
                .isZero();
        assertThat(liveCount("traces_pre_cutover_backup", idStrings(gapWritten), workspaceId))
                .as("...and is sitting intact in the frozen backup, which is why it is recoverable at all")
                .isEqualTo(gapWritten.size());
        assertThat(forwardCounts(deltaStart, swapDone))
                .as("the gate SEES the loss before anything is repaired: five keys live in the parked backup inside the"
                        + " gap window are absent from the live table")
                .isEqualTo(ReconciliationCounts.builder().missing(gapWritten.size()).stale(0).payloadMismatch(0)
                        .newer(0).build());

        reconcileForward(deltaStart, swapDone);

        assertThat(liveCount("traces", idStrings(gapWritten), workspaceId))
                .as("after the sweep every gap-window trace is live on the successor")
                .isEqualTo(gapWritten.size());
        assertThat(forwardCounts(deltaStart, swapDone))
                .as("and the gate clears: nothing missing, nothing stale, no payload differing")
                .isEqualTo(reconciled());
        assertThat(fingerprint("traces", Shape.NEW, workspaceId))
                .as("the reconciled successor matches the frozen backup field-for-field, not merely key-for-key")
                .isEqualTo(fingerprint("traces_pre_cutover_backup", Shape.OLD, workspaceId));

        // A SECOND pass, because reconcile.sh runs one whenever the gate is still non-zero (up to --max-passes) and
        // because the runbook promises a re-run on a reconciled estate is a no-op. Both make pass 2 a real code path,
        // not a hypothetical: it re-inserts every swept row and re-runs the replay against an estate that is already
        // correct. Same idempotence check, and same reason, as deleteThenResurrectSurvivesTheReplay's repeated replay.
        reconcileForward(deltaStart, swapDone);

        assertThat(forwardCounts(deltaStart, swapDone))
                .as("a second pass changes nothing: the gate is still clean")
                .isEqualTo(reconciled());
        assertThat(fingerprint("traces", Shape.NEW, workspaceId))
                .as("and no row moved — the re-inserted copies lose to the versions already there")
                .isEqualTo(fingerprint("traces_pre_cutover_backup", Shape.OLD, workspaceId));
    }

    /**
     * The sweep must not undo a delete that landed AFTER the swap. A trace that was live when the backup froze is still
     * live in it, so a sweep with no exclusion would insert a fresh version and resurrect it — the mirror image of the
     * bug it is fixing.
     *
     * <p>The realistic shape, and the one built here: a trace that existed before the backfill (so it IS on the
     * successor) and was UPDATED inside the gap window, which is what puts it in the sweep's range. The user then
     * deletes it on the live table after the swap. It has to stay deleted, and the postcondition must not count it as
     * missing — a gate that did would be unreachable on any estate where users delete things.
     */
    @Test
    void sweepDoesNotResurrectAGapWindowTraceDeletedAfterTheSwap() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var target = mintIdsAt(1, weekInstant(0, 1));
        seedTraces(survivors, workspaceId, projectId);
        seedTraces(target, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        var deltaStart = nowMicros();
        deltaInsert(backfillStart);

        // Updated on the OLD table inside the gap window: its created_at stays historical, so only the
        // last_updated_at arm of the window predicate selects it — the arm a created_at-only sweep would miss.
        var updatedAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        insertRows(target, workspaceId, projectId, "gap-updated", _ -> updatedAt);

        exchangeTables();
        var swapDone = nowMicros();

        recordDeletionEvents(idStrings(target), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(target), workspaceId);
        assertThat(liveCount("traces", idStrings(target), workspaceId))
                .as("baseline: the post-swap delete masked the stale copy the cutover had left live")
                .isZero();

        reconcileForward(deltaStart, swapDone);

        assertThat(liveCount("traces", idStrings(target), workspaceId))
                .as("the sweep does not resurrect it: the exclusion arm drops every key bridged since swap_done")
                .isZero();
        assertThat(forwardCounts(deltaStart, swapDone))
                .as("and the gate still clears — a key deleted after the swap is legitimately absent, not missing")
                .isEqualTo(reconciled());
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("every other survivor is untouched")
                .isEqualTo(survivors.size());

        // The pass reconcile.sh would run next if the gate had not cleared. A delete is the thing a repeated sweep
        // could plausibly undo, so this is where idempotence matters most.
        reconcileForward(deltaStart, swapDone);

        assertThat(liveCount("traces", idStrings(target), workspaceId))
                .as("a second pass does not resurrect it either")
                .isZero();
        assertThat(forwardCounts(deltaStart, swapDone))
                .as("and the gate is still clean after it")
                .isEqualTo(reconciled());
    }

    /**
     * The counterweight to {@link #sweepDoesNotResurrectAGapWindowTraceDeletedAfterTheSwap()}, and the reason the
     * sweep's exclusion is bounded at {@code swap_done} rather than at {@code gap_start}.
     *
     * <p>A trace deleted and then re-created BEFORE the swap is legitimately live in the frozen backup — the re-creation
     * is what the backup froze — so it must be swept back, even though the bridge holds a delete event for it. Bounding
     * the exclusion at {@code gap_start} would drop every such key instead: a silent write loss of exactly the kind this
     * step exists to prevent, which no other test here would notice, because the postcondition excludes the same keys
     * the sweep does and the estate would therefore look clean.
     *
     * <p>That makes this the test standing between the runbook and a plausible "hardening" — narrowing the bound to
     * close the capture-before-delete residual documented in 000006 would trade a rare resurrection for a likelier loss.
     */
    @Test
    void sweepRestoresAGapWindowTraceDeletedAndReCreatedBeforeTheSwap() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var target = mintIdsAt(1, weekInstant(0, 1));
        seedTraces(survivors, workspaceId, projectId);
        seedTraces(target, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        var deltaStart = nowMicros();
        deltaInsert(backfillStart);

        // Deleted and re-created inside the gap window, both BEFORE the swap: the bridge carries the delete, and the
        // old table — and so the frozen backup — carries the re-created row as live.
        recordDeletionEvents(idStrings(target), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(target), workspaceId);
        var reCreatedAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        insertRows(target, workspaceId, projectId, "re-created-before-swap", _ -> reCreatedAt);
        // The final pre-swap replay, as exchange_and_wrap.sh runs it: its guard reads the LIVE source, where the key is
        // live again, so it correctly leaves the successor's copy alone rather than masking it.
        replayDeletions(backfillStart);

        exchangeTables();
        var swapDone = nowMicros();

        assertThat(newestNames("traces_pre_cutover_backup", idStrings(target), workspaceId))
                .as("precondition: the frozen backup holds the RE-CREATION as live, not the delete")
                .containsOnly("re-created-before-swap");
        assertThat(newestNames("traces", idStrings(target), workspaceId))
                .as("and the swap left the successor on the pre-delete version the backfill copied — the re-creation"
                        + " landed after the last delta, so it is in the gap like any other write")
                .containsOnly("seed");
        assertThat(forwardCounts(deltaStart, swapDone))
                .as("the gate sees that as a STALE key, the bucket for a live row older than the parked one — the shape"
                        + " an incomplete cutover leaves behind when the key exists on both sides")
                .isEqualTo(ReconciliationCounts.builder().missing(0).stale(1).payloadMismatch(0).newer(0).build());

        reconcileForward(deltaStart, swapDone);

        assertThat(newestNames("traces", idStrings(target), workspaceId))
                .as("the re-creation is swept back: its delete was bridged BEFORE swap_done, so the exclusion arm does"
                        + " not drop it, and the replay's guard spares it because the frozen backup shows it live")
                .containsOnly("re-created-before-swap");
        assertThat(forwardCounts(deltaStart, swapDone))
                .as("and the gate clears")
                .isEqualTo(reconciled());
    }

    /**
     * A gap-window trace that was written again AFTER the swap keeps the newer version. The sweep inserts the frozen
     * one, which loses the {@code ReplacingMergeTree} version comparison — that is what makes the sweep safe to run
     * against live traffic rather than a race with it.
     *
     * <p>It also pins the postcondition's one informational bucket: such a key is reported as {@code newer_keys}, which
     * is expected to be non-zero on a busy estate, and the gate still passes. Gating on that count would fail every
     * healthy reconciliation.
     */
    @Test
    void sweepKeepsANewerPostSwapVersionAndTheGateStillPasses() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var target = mintIdsAt(1, weekInstant(0, 1));
        seedTraces(target, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        var deltaStart = nowMicros();
        deltaInsert(backfillStart);

        var updatedAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        insertRows(target, workspaceId, projectId, "gap-updated", _ -> updatedAt);

        exchangeTables();
        var swapDone = nowMicros();

        // A post-swap write on the live successor, newer than anything the frozen backup holds.
        var postSwapAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        insertRows(target, workspaceId, projectId, "post-swap", _ -> postSwapAt);

        reconcileForward(deltaStart, swapDone);

        assertThat(newestNames("traces", idStrings(target), workspaceId))
                .as("the sweep's re-insert loses to the post-swap version — it cannot clobber live traffic")
                .containsOnly("post-swap");
        assertThat(forwardCounts(deltaStart, swapDone))
                .as("reported as newer_keys, which is informational: the three gating buckets are still 0")
                .isEqualTo(ReconciliationCounts.builder().missing(0).stale(0).payloadMismatch(0).newer(1).build());
    }

    /**
     * Reconciling a WRAPPED estate, which is the ordinary path and not an edge case: {@code exchange_and_wrap.sh
     * --with-wrap} applies the wrap in the same run, so the CUTOVER INCOMPLETE banner is printed with {@code traces}
     * already a {@code Distributed} wrapper and the reconciliation runs against that topology.
     *
     * <p>A {@code Distributed} table accepts {@code INSERT} but rejects mutations, so the replay after the sweep would
     * fail against {@code traces} — which is why {@code reconcile.sh} resolves one name the way
     * {@code tracesDistributedWrapEnabled} does and aims every statement at {@code traces_local}. Writing the shard
     * directly is correct rather than a bypass of the sharding key: the parked backup is itself a per-shard table, so
     * its rows already belong to this shard.
     *
     * <p>What this pins that the driver's own guard cannot: that the swept rows are then readable THROUGH the wrapper,
     * and that the gate — which also reads the resolved name — agrees.
     */
    @Test
    void reconciliationWorksOnAWrappedEstateThroughTracesLocal() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        var deltaStart = nowMicros();
        deltaInsert(backfillStart);

        var gapAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        var gapWritten = mintIdsAt(3, gapAt);
        insertRows(gapWritten, workspaceId, projectId, "gap", _ -> gapAt);
        // A gap-window delete too, so the replay half runs against traces_local rather than being a no-op.
        var gapDeleted = mintIds(2);
        seedTraces(gapDeleted, workspaceId, projectId);
        backfillWeek(0);
        recordDeletionEvents(idStrings(gapDeleted), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(gapDeleted), workspaceId);

        exchangeTables();
        wrapInDistributed();
        var swapDone = nowMicros();

        assertThat(isDistributed("traces"))
                .as("precondition: --with-wrap leaves the estate wrapped before reconciliation runs")
                .isTrue();

        // Every statement aimed at the shard, exactly as the driver resolves it.
        reconcileForward("traces_local", deltaStart, swapDone);

        assertThat(liveCount("traces", idStrings(gapWritten), workspaceId))
                .as("the swept rows are readable through the Distributed wrapper")
                .isEqualTo(gapWritten.size());
        assertThat(liveCount("traces", idStrings(gapDeleted), workspaceId))
                .as("and the replay's mask applied to the shard is visible through it too")
                .isZero();
        assertThat(reconciliationCounts("traces_pre_cutover_backup", Shape.OLD, "traces_local", deltaStart, swapDone))
                .as("the gate, read against the same resolved name, clears")
                .isEqualTo(reconciled());
    }

    /**
     * The delete-side residual the runbook previously accepted as inherent: a delete whose bridge row commits after the
     * final pre-swap replay has read the bridge, but before the EXCHANGE. It is covered by neither the forward replay
     * (already run) nor the rollback reverse-replay ({@code event_time >= cutover_start}), so it leaks live across the
     * swap.
     *
     * <p>Post-swap it is closable, because the resurrection guard can read the FROZEN backup instead of a live source:
     * there is no race left to lose. The test walks all three states so the sweep and the replay are not confused for
     * one another — the leak is present after the swap, still present after the sweep alone (which is mask-honored and
     * therefore cannot fix a delete), and gone after the replay.
     */
    @Test
    void postSwapReplayMasksADeleteBridgedBetweenTheFinalReplayAndTheExchange() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var leaked = mintIds(3);
        seedTraces(survivors, workspaceId, projectId);
        seedTraces(leaked, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        var deltaStart = nowMicros();
        deltaInsert(backfillStart);
        replayDeletions(backfillStart); // the final pre-swap replay reads the bridge HERE

        // ...and the bridge row lands after that read. Nothing pre-swap can mask it any more.
        recordDeletionEvents(idStrings(leaked), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(leaked), workspaceId);

        exchangeTables();
        var swapDone = nowMicros();

        assertThat(liveCount("traces", idStrings(leaked), workspaceId))
                .as("negative control: the delete leaked live across the swap")
                .isEqualTo(leaked.size());

        forwardSweep("traces", deltaStart, swapDone);
        assertThat(liveCount("traces", idStrings(leaked), workspaceId))
                .as("the sweep alone cannot fix a delete — it is mask-honored, so it neither copies nor removes it")
                .isEqualTo(leaked.size());

        postSwapDeletionReplay("traces", deltaStart, swapDone);

        assertThat(liveCount("traces", idStrings(leaked), workspaceId))
                .as("the post-swap replay masks it: the frozen backup says it is still deleted, race-free")
                .isZero();
        assertThat(liveCount("traces", idStrings(survivors), workspaceId))
                .as("and no survivor is dropped with it")
                .isEqualTo(survivors.size());
    }

    /**
     * The hazard the frozen resurrection guard introduces, and the scope that closes it. A frozen source cannot see a
     * row written AFTER the swap, so a key deleted before the swap and then re-created or patched after it looks "still
     * deleted" to the guard — and the replay would mask a live post-cutover write, turning the fix for write loss into a
     * cause of it. It is not exotic: trace ids are client-supplied and {@code TraceDAO.UPDATE} re-inserts a version, so
     * any in-flight patch to a just-deleted trace does exactly this.
     *
     * <p>The staleness scope is what prevents it, per ROW rather than per key: only rows whose own {@code created_at}
     * AND {@code last_updated_at} both predate the swap may be masked. The re-creation here carries a historical
     * {@code created_at} and a post-swap {@code last_updated_at}, so it is spared by the {@code last_updated_at} arm
     * alone — the harder of the two, and the one a batch-ingest write would not exercise.
     *
     * <p>The negative control then runs the same replay with that scope omitted — 000002's pre-swap two-arm shape,
     * aimed post-swap — and watches the write disappear, so the scope is proven load-bearing rather than merely present.
     */
    @Test
    void postSwapReplayDoesNotMaskATraceReCreatedAfterTheSwap() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        var target = mintIdsAt(1, weekInstant(0, 1));
        seedTraces(survivors, workspaceId, projectId);
        seedTraces(target, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        var deltaStart = nowMicros();
        deltaInsert(backfillStart);

        // Deleted in the gap, bridged, and correctly masked on the successor by the final pre-swap replay.
        recordDeletionEvents(idStrings(target), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(idStrings(target), workspaceId);
        replayDeletions(backfillStart);

        exchangeTables();
        var swapDone = nowMicros();
        assertThat(liveCount("traces", idStrings(target), workspaceId))
                .as("baseline: the delete was honoured across the swap")
                .isZero();

        // After the swap a client re-sends the same id — the row is live again on the successor, and the frozen backup
        // cannot know it.
        var reCreatedAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        insertRows(target, workspaceId, projectId, "re-created", _ -> reCreatedAt);
        assertThat(liveCount("traces", idStrings(target), workspaceId))
                .as("baseline: the re-creation is live")
                .isEqualTo(1L);

        reconcileForward(deltaStart, swapDone);

        assertThat(liveCount("traces", idStrings(target), workspaceId))
                .as("the post-swap re-creation SURVIVES: the staleness scope spares any row written since the swap")
                .isEqualTo(1L);
        assertThat(forwardCounts(deltaStart, swapDone))
                .as("and the gate clears — the key is masked in the frozen backup, so it never enters the parked set")
                .isEqualTo(reconciled());

        // NEGATIVE CONTROL: the same replay with the PRE-swap scope, which is inert, destroys the write.
        postSwapDeletionReplayWithoutStalenessScope(deltaStart);
        assertThat(liveCount("traces", idStrings(target), workspaceId))
                .as("without the staleness scope the frozen guard masks a live post-cutover write — the regression the"
                        + " scope exists to prevent")
                .isZero();
    }

    /**
     * The reverse direction (000006 {@code reverse-sweep}): re-import into the restored original the post-cutover writes
     * a stage B/C promote made non-live. This is what turns {@code --accept-post-cutover-write-loss} from a verdict into
     * a choice — right when the rollback was about latency, merge load or the wrap, and wrong when the successor's
     * content is what is suspect.
     *
     * <p>The sentinel denormalization is the part that cannot be skipped, and it is asserted through {@code duration}
     * rather than through {@code end_time} alone. The successor stores an unfinished trace's {@code end_time} as the
     * epoch; the original's convention is {@code NULL} and its MATERIALIZED {@code duration} guards only
     * {@code end_time IS NOT NULL}, so importing the sentinel verbatim would give every unfinished trace a duration of
     * roughly -1.79e12 ms. Restoring {@code NULL} is what makes the recomputed duration {@code NULL}.
     */
    @Test
    void reverseSweepReimportsPostCutoverWritesWithSentinelsDenormalizedToNull() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        deltaInsert(backfillStart);

        var cutoverStart = nowMicros();
        exchangeTables();

        // Accepted by the successor after cutover_start: in-progress traces, so end_time and ttft take the successor's
        // epoch / NaN DEFAULTs — the shape whose denormalization matters.
        var postCutoverAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        var postCutover = mintIdsAt(3, postCutoverAt);
        insertRows(postCutover, workspaceId, projectId, "post-cutover", _ -> postCutoverAt);

        rollbackExchangeBack(cutoverStart);
        var promoteDone = nowMicros();

        assertThat(liveCount("traces", idStrings(postCutover), workspaceId))
                .as("baseline: the promote made the post-cutover writes non-live on the restored original")
                .isZero();
        assertThat(reverseCounts(cutoverStart, cutoverStart))
                .as("the gate sizes the loss: three keys the parked successor holds are absent from the live original")
                .isEqualTo(ReconciliationCounts.builder().missing(postCutover.size()).stale(0).payloadMismatch(0)
                        .newer(0).build());

        reconcileReverse(cutoverStart, promoteDone);

        assertThat(liveCount("traces", idStrings(postCutover), workspaceId))
                .as("re-imported into the restored original")
                .isEqualTo(postCutover.size());
        assertThat(countMatching(workspaceId,
                "name = 'post-cutover' AND end_time IS NULL AND ttft IS NULL AND duration IS NULL"))
                .as("with the sentinels denormalized, so the recomputed duration is NULL — not the large NEGATIVE the"
                        + " original's expression produces from an epoch end_time it does not recognise")
                .isEqualTo(postCutover.size());
        assertThat(countMatching(workspaceId,
                "name = 'post-cutover' AND end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')"))
                .as("negative control: no re-imported row kept the successor's epoch sentinel")
                .isZero();
        assertThat(reverseCounts(cutoverStart, cutoverStart))
                .as("and the reverse gate clears")
                .isEqualTo(reconciled());
    }

    /**
     * Deletes still win over the re-imported writes. The reverse sweep runs BEFORE
     * {@code 000004_rollback_reverse_replay.sql}, so a trace deleted on the successor after {@code cutover_start} is
     * re-imported by the sweep and then masked again on the restored original by the replay — and that file's own
     * postcondition still reports 0, which is the box the runbook's rollback checklist ticks.
     *
     * <p><b>The fixture has to be DELETED AND RE-CREATED, and finding that out is the point.</b> Two shapes of
     * post-cutover delete reach the sweep completely differently:
     * <ul>
     *   <li>a plain delete — the mask is applied to the successor before the promote parks it, so the sweep's
     *   mask-honored read never sees the key. It cannot resurrect what it cannot read, whatever the row's timestamps
     *   are. A seeded survivor is doubly invisible: a lightweight delete bumps no version, and its back-dated
     *   {@code created_at}/{@code last_updated_at} also sit outside the sweep's window. Asserting on either shape
     *   passes without the sweep/replay ordering being exercised at all.</li>
     *   <li>a delete FOLLOWED BY a re-creation under the same id — the key is live again in the parked successor, its
     *   delete is still in the bridge, and the sweep genuinely re-imports it. Only the replay running afterwards keeps
     *   it deleted, which is the ordering this test exists for and the behaviour
     *   {@code 000006_post_swap_reconciliation.sql}'s {@code reverse-sweep} header specifies: the delete is honoured
     *   and the re-creation is discarded with the rest of the post-cutover writes.</li>
     * </ul>
     * Both are asserted — the first as the reason the second is the only meaningful fixture, the second with a negative
     * control showing the sweep alone brings the key back.
     */
    @Test
    void reverseSweepReimportsThenTheReplayReDeletesARecreatedPostCutoverKey() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var survivors = mintIds(SURVIVORS_PER_WEEK);
        seedTraces(survivors, workspaceId, projectId);

        var backfillStart = nowMicros();
        for (int week = 0; week < SEED_WEEKS; week++) {
            backfillWeek(week);
        }
        deltaInsert(backfillStart);

        var cutoverStart = nowMicros();
        exchangeTables();

        var postCutoverAt = Instant.from(ClickHouseDateTimeFormat.MICROS.parse(nowMicros()));
        var postCutover = mintIdsAt(3, postCutoverAt);
        insertRows(postCutover, workspaceId, projectId, "post-cutover", _ -> postCutoverAt);

        var recreated = postCutover.getFirst();
        var plainlyDeleted = postCutover.get(1);
        var untouched = Set.of(postCutover.get(2).id().toString());

        // Shape 1: deleted and left deleted. The mask lands on the successor before the promote, so the parked copy
        // carries it and the sweep's mask-honored read skips the key entirely.
        recordDeletionEvents(Set.of(plainlyDeleted.id().toString()), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(Set.of(plainlyDeleted.id().toString()), workspaceId);

        // Shape 2: deleted, then re-created under the same id at a newer version — so it is LIVE in the parked copy
        // while its delete is still in the bridge. This is the one the sweep will bring back.
        recordDeletionEvents(Set.of(recreated.id().toString()), workspaceId, projectId.toString(), "user_request");
        lightweightDelete(Set.of(recreated.id().toString()), workspaceId);
        var recreatedAt = postCutoverAt.plusSeconds(1);
        insertRows(List.of(recreated), workspaceId, projectId, "re-created", _ -> recreatedAt);

        rollbackExchangeBack(cutoverStart);
        var promoteDone = nowMicros();

        // Negative control: the sweep ALONE resurrects the re-created key, which is what makes the ordering
        // load-bearing rather than incidental — and leaves the plainly-deleted one alone, which is why it is not a
        // usable fixture. Asserted before the replay runs, on the same estate the full run then repairs.
        reverseSweep(cutoverStart, promoteDone);
        assertThat(liveCount("traces", Set.of(recreated.id().toString()), workspaceId))
                .as("negative control: the sweep on its own re-imports the re-created key")
                .isEqualTo(1L);
        assertThat(liveCount("traces", Set.of(plainlyDeleted.id().toString()), workspaceId))
                .as("while a plainly-deleted key is masked in the parked copy, so the sweep cannot see it at all")
                .isZero();

        reconcileReverse(cutoverStart, promoteDone);

        assertThat(liveCount("traces", Set.of(recreated.id().toString()), workspaceId))
                .as("after the replay the post-cutover delete wins over the re-creation the sweep brought back")
                .isZero();
        assertThat(verifyReplayPostcondition(cutoverStart))
                .as("and 000004_rollback_verify_replay.sql still reports 0 after the re-import")
                .isZero();
        assertThat(liveCount("traces", untouched, workspaceId))
                .as("while a post-cutover write that was never deleted is live again")
                .isEqualTo(untouched.size());
    }

    /**
     * The delete-side residual the post-swap replay's staleness scope cannot prevent, pinned so the trade-off behind it
     * cannot be flipped by accident — and the advisory that makes it visible.
     *
     * <p>{@code last_updated_at} is CLIENT-SUPPLIED on the batch-ingest path: {@code TraceDAO} binds
     * {@code Trace.lastUpdatedAt} verbatim and the API accepts any value before 2300. A genuinely pre-swap trace can
     * therefore carry a future timestamp, fall outside the replay's
     * {@code created_at < swap_done AND last_updated_at < swap_done} scope, and keep its captured delete unmasked.
     *
     * <p><b>This asserts the residual, not a bug to be fixed here.</b> Scoping on {@code created_at} alone would close
     * it and open a worse one: the merge path preserves {@code created_at}, so a post-swap PATCH of a pre-existing
     * trace would then be masked, destroying a write that lives only on the successor. Over-sparing leaves a deleted
     * trace visible with its key still in the bridge, so it can be re-applied; over-masking is unrecoverable. The
     * companion assertion is the one that matters operationally: {@code leak-check-forward} reports the key, so the
     * residual is surfaced rather than silent. A control row deleted with an ordinary timestamp proves the replay is
     * working normally on the same estate, so a green here is not just "the replay did nothing".
     */
    @Test
    void postSwapReplaySparesAFutureDatedRowAndTheLeakCheckReportsIt() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var at = Instant.parse("2025-03-04T10:00:00Z");
        var gapStart = "2025-03-04 09:00:00";

        // Both written pre-swap, inside the gap window, and both deleted before the swap. They differ only in the
        // client-supplied last_updated_at: one honest, one dated past any plausible swap_done.
        var honest = ID_GENERATOR.generateId().toString();
        var futureDated = ID_GENERATOR.generateId().toString();
        insertShapedTrace(honest, workspaceId, projectId, "gap", at, null, null, at, at);
        insertShapedTrace(futureDated, workspaceId, projectId, "gap", at, null, null, at,
                Instant.parse("2027-01-01T00:00:00Z"));

        // The end state is CONSTRUCTED rather than played out, because the delta anchor cannot be placed before a
        // back-dated created_at. It is the same state either way, and the state is all the replay reads: the successor
        // holds each key as a copy carrying the source's version, the parked backup has each key MASKED, and the bridge
        // holds both events. In production the copy comes from the backfill/delta and the mask from a delete that fired
        // too late for the final pre-swap replay — the only window in which the post-swap replay is what has to mask it.
        exchangeTables();
        var swapDone = nowMicros();
        forwardSweep("traces", gapStart, swapDone);

        var deleted = Set.of(honest, futureDated);
        recordDeletionEvents(deleted, workspaceId, projectId.toString(), "user_request");
        lightweightDeleteFrom("traces_pre_cutover_backup", deleted, workspaceId, projectId);

        postSwapDeletionReplay("traces", gapStart, swapDone);

        assertThat(liveCount("traces", Set.of(honest), workspaceId))
                .as("control: an ordinarily-dated pre-swap row IS masked, so the replay is working on this estate")
                .isZero();
        assertThat(liveCount("traces", Set.of(futureDated), workspaceId))
                .as("the residual: a client-supplied future last_updated_at puts the row outside the staleness scope,"
                        + " so its captured delete is not re-applied")
                .isEqualTo(1L);
        assertThat(forwardCounts(gapStart, swapDone))
                .as("and the four counts cannot see it — the key is not live in the frozen backup, so it never enters"
                        + " the parked set the gate classifies")
                .isEqualTo(reconciled());
        assertThat(leakCheckForward(gapStart))
                .as("which is why the advisory exists: it reports exactly the leaked key, by version rather than by"
                        + " timestamp")
                .isEqualTo(1L);
    }

    /**
     * The postcondition's classification, one key per bucket, on the FULL {@code (workspace_id, project_id, id)} key.
     *
     * <p>The baseline matters as much as the perturbations: a clean sweep must classify NOTHING, which is what makes a
     * zero meaningful. Each cohort is then moved into exactly one bucket by editing the live side only — masking the
     * swept row and re-inserting where a different version is needed, so every side holds one physical row and
     * {@code FINAL} has no arbitrary choice to make. Building the "matching" row with the sweep itself, rather than by
     * hand, is deliberate: it makes the baseline a statement about the real copy path rather than about whether two
     * tables happen to share column DEFAULTs.
     *
     * <p>The reused id is the full-key case: the same id under two projects, matching in one and absent in the other,
     * counts once as missing. A gate keyed on {@code id} alone would report zero there.
     */
    @Test
    void reconciliationCountsClassifyEachKeyByItsVersionRelationOnTheFullKey() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        var otherProjectId = ID_GENERATOR.generateId();
        var at = Instant.parse("2025-03-04T10:00:00Z");
        var gapStart = "2025-03-04 09:00:00";

        var matching = ID_GENERATOR.generateId().toString();
        var stale = ID_GENERATOR.generateId().toString();
        var payload = ID_GENERATOR.generateId().toString();
        var newer = ID_GENERATOR.generateId().toString();
        var missing = ID_GENERATOR.generateId().toString();
        var reused = ID_GENERATOR.generateId().toString();
        for (var id : List.of(matching, stale, payload, newer, missing, reused)) {
            insertShapedTrace(id, workspaceId, projectId, "cohort", at, null, null, at, at);
        }
        insertShapedTrace(reused, workspaceId, otherProjectId, "cohort", at, null, null, at, at);

        // Park the seeded rows and start from an empty successor, so every live row below is one this test placed.
        exchangeTables();
        var swapDone = nowMicros();
        forwardSweep("traces", gapStart, swapDone);

        assertThat(forwardCounts(gapStart, swapDone))
                .as("baseline: a clean sweep classifies nothing — which is what makes a zero in any bucket meaningful")
                .isEqualTo(reconciled());

        // MISSING: absent from the live table, with no bridge event, so the exclusion arm does not forgive it.
        lightweightDeleteScoped(Set.of(missing), workspaceId, projectId);
        // MISSING, full-key: present under one project and absent under the other; it must count once, not zero.
        lightweightDeleteScoped(Set.of(reused), workspaceId, otherProjectId);
        // STALE: the live row is at an OLDER version than the parked one.
        lightweightDeleteScoped(Set.of(stale), workspaceId, projectId);
        insertShapedTrace(stale, workspaceId, projectId, "cohort", at, Instant.EPOCH, Double.NaN, at,
                at.minusSeconds(60));
        // PAYLOAD MISMATCH: same version, differing content — the case a version comparison alone cannot see.
        lightweightDeleteScoped(Set.of(payload), workspaceId, projectId);
        insertShapedTrace(payload, workspaceId, projectId, "differs", at, Instant.EPOCH, Double.NaN, at, at);
        // NEWER: post-swap traffic touched it; informational, and must not gate.
        insertShapedTrace(newer, workspaceId, projectId, "cohort", at, Instant.EPOCH, Double.NaN, at,
                at.plusSeconds(60));

        assertThat(forwardCounts(gapStart, swapDone))
                .as("each key lands in exactly one bucket, counted per full key: two missing (one of them the reused"
                        + " id's other project), one stale, one payload mismatch, one newer")
                .isEqualTo(ReconciliationCounts.builder().missing(2).stale(1).payloadMismatch(1).newer(1).build());
    }

    /**
     * The backfill's window bounds. A {@code DateTime64} literal carrying no timezone is parsed in the session's
     * timezone, while every column it meets is {@code DateTime64(n, 'UTC')}, so the session is set explicitly here: an
     * unpinned literal only diverges where it is not UTC. The row is seeded an hour into the week, so a bound read in
     * a westward zone starts the window after it and the copy silently skips it — a hole in the migration, in the week
     * the driver reported as done.
     *
     * <p>The row carries no {@code ttft} either, so the projection's NaN sentinel is asserted with it: a window test
     * that copied the wrong columns would otherwise pass. Its epoch {@code end_time} sentinel is deliberately NOT
     * asserted — 000001 leaves that literal unpinned by design, so under this session the copy writes a shifted
     * instant rather than 0, and requiring 0 would fail on correct code.
     */
    @Test
    void backfillIsUnaffectedByTheSessionTimezone() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        insertShapedTrace(workspaceId, projectId, "absent-both", weekInstant(0, 0), null, null);

        backfillWeekWestwardSession(0);

        var copied = copiedRow(workspaceId, "absent-both");
        assertThat(copied.rows()).as("the week's window still selects the row").isEqualTo(1);
        assertThat(copied.ttftIsNaN()).as("NaN ttft sentinel").isTrue();
    }

    /**
     * The delta's own window bounds: it re-reads the source for everything touched at or after the anchor, on
     * {@code created_at} OR {@code last_updated_at}. Both bounds parse the same value, so a westward session
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

        deltaInsertWestwardSession(ClickHouseDateTimeFormat.formatMicros(anchor));

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

        assertThat(bridgedDeletionsSinceWestwardSession(backfillStart))
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
     * <p>Asserted only on what a merge cannot change. Whether the duplicates still exist when this reads is not
     * observable deterministically — two same-partition parts this small are prime merge candidates — so the test pins
     * the invariant that holds either way: one live row, and one distinct content at the newest version. {@code
     * liveCount} rules out the vacuous case where nothing was copied at all.
     */
    @Test
    void backfillThenDeltaSequenceIsNotATie() {
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

        assertThat(liveCount("traces_local_v2", Set.of(id), workspaceId))
                .as("they dedup to one live row")
                .isEqualTo(1);
        assertThat(versionTies("traces_local_v2", Shape.NEW, workspaceId))
                .as("identical re-copies at one version are not a tie")
                .isZero();
    }

    /**
     * The tie aggregate's POSITIVE branch: a key carrying more than one DISTINCT row at its newest version is counted, and one
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
     * <p>Four keys, separating ranking from totalling and content from row count. {@code tied} carries two DIFFERING
     * contents at its newest version and is the only one that counts. {@code dup} carries two IDENTICAL ones, which is
     * what the delta produces on a faithful copy. {@code deep} has more rows overall but a single content at its
     * newest. {@code single} has one row. So summing, taking a plain maximum instead of {@code argMax} over the
     * version, or counting rows instead of distinct contents each fail here.
     */
    @Test
    void versionTieAggregateCountsOnlyASharedNewestVersion() {
        // (key, version, content) triples standing in for the per-version row counts the shipped block derives.
        var ties = scalar("""
                SELECT count() AS c
                FROM (
                    SELECT key, argMax(distinct_at_version, version) AS distinct_at_newest
                    FROM (
                        SELECT key, version, uniqExact(content) AS distinct_at_version
                        FROM VALUES('key String, version UInt32, content String',
                                    ('tied', 2, 'a'), ('tied', 2, 'b'), ('tied', 1, 'a'),
                                    ('dup', 2, 'a'), ('dup', 2, 'a'), ('dup', 1, 'b'),
                                    ('deep', 2, 'a'), ('deep', 1, 'a'), ('deep', 1, 'b'),
                                    ('single', 1, 'a'))
                        GROUP BY key, version
                    )
                    GROUP BY key
                )
                WHERE distinct_at_newest > 1
                """, statement -> {
        });

        assertThat(ties).as("only the key whose newest version carries DIFFERING content counts as tied").isEqualTo(1);
    }

    /**
     * The {@code version-ties} block must not count a key merely because it has SEVERAL versions — only one whose NEWEST
     * version carries more than one DISTINCT row. That distinction is the whole content of the aggregate: ranking by version
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

    /**
     * Schema-drift guard. The cutover copies a fixed column list, and the fidelity fingerprint also lists fixed
     * columns — so a base column added to {@code traces} by a future migration would be silently left uncopied, with no
     * existing check failing. This asserts the cutover's {@link #COPIED_COLUMNS} equals the live stored columns of
     * {@code traces}, and that {@code traces_local_v2} mirrors them plus only the {@code is_deleted} meta-column. Adding
     * a stored column to either table fails this until it is added to {@code COPIED_COLUMNS} (and thus to the copy).
     */
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
                SETTINGS max_insert_block_size = 100000, max_partitions_per_insert_block = 2000
                """.formatted(COPIED_COLUMNS, COPIED_SELECT),
                statement -> statement.bind("week_lo", weekLo).bind("week_hi", weekHi));
    }

    /**
     * {@link #backfillWeek(int)} with the session put WEST of UTC.
     *
     * <p>The direction is load-bearing. An unpinned literal read in a westward zone resolves LATER in absolute terms,
     * so a bound moves past rows that belong inside it, which is the silent failure. An eastward zone moves bounds
     * earlier and would let an unpinned literal pass, so it would not discriminate.
     */
    private void backfillWeekWestwardSession(int week) {
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
                SETTINGS max_insert_block_size = 100000,
                         max_partitions_per_insert_block = 2000,
                         session_timezone = 'America/New_York'
                """.formatted(COPIED_COLUMNS, COPIED_SELECT),
                statement -> statement.bind("week_lo", weekLo).bind("week_hi", weekHi));
    }

    /**
     * The delta-insert: re-copy every row written during the backfill window. Anchored on
     * {@code created_at OR last_updated_at >= backfill_start} so it is complete regardless of the client-supplied
     * {@code last_updated_at} on the batch-ingest path (see class Javadoc).
     */
    private void deltaInsert(String backfillStart) {
        execute("""
                INSERT INTO traces_local_v2 (
                %s
                )
                SELECT
                %s
                FROM traces
                WHERE created_at >= toDateTime64(:backfill_start, 6, 'UTC')
                   OR last_updated_at >= toDateTime64(:backfill_start, 6, 'UTC')
                SETTINGS max_insert_block_size = 100000, max_partitions_per_insert_block = 2000
                """.formatted(COPIED_COLUMNS, COPIED_SELECT),
                statement -> statement.bind("backfill_start", backfillStart));
    }

    /** {@link #deltaInsert(String)} with the session put WEST of UTC; see {@link #backfillWeekWestwardSession(int)}. */
    private void deltaInsertWestwardSession(String backfillStart) {
        execute("""
                INSERT INTO traces_local_v2 (
                %s
                )
                SELECT
                %s
                FROM traces
                WHERE created_at >= toDateTime64(:backfill_start, 6, 'UTC')
                   OR last_updated_at >= toDateTime64(:backfill_start, 6, 'UTC')
                SETTINGS max_insert_block_size = 100000,
                         max_partitions_per_insert_block = 2000,
                         session_timezone = 'America/New_York'
                """.formatted(COPIED_COLUMNS, COPIED_SELECT),
                statement -> statement.bind("backfill_start", backfillStart));
    }

    /**
     * Reads the bridge for the cutover window and removes the captured deletes from the destination in a single
     * mutation (mirrors 000002). Single full-key branch: since OPIK-7483 every trace delete carries its project_id, so
     * events match the full key {@code (workspace_id, project_id, id)} (exact; a reused id in another project is
     * untouched) — without this replay those deletions silently leak across the swap. The branch also requires the id is
     * NOT currently live on the source (the resurrection guard), so a deleted-then-recreated id is not dropped. Returns
     * the wall time, which the runbook counts toward the cutover tail.
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
                        WHERE source_table = 'traces'
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
                        FROM traces
                        WHERE id IN (
                            SELECT toFixedString(deleted_id, 36)
                            FROM deletion_events_local
                            WHERE source_table = 'traces'
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

    /**
     * The POST-SWAP forward deletion replay (000006 {@code forward-deletion-replay}), run right after the sweep so
     * bridged deletes win over what it re-inserted. A separate statement from {@link #replayDeletions(String)}, exactly
     * as it is a separate block from 000002 in the shipped SQL: it masks rows on the LIVE table and reads its
     * resurrection guard from the FROZEN backup, which is race-free where a live source cannot be — and is what closes
     * the delete-side residual (a delete bridged after the final pre-swap replay read the bridge, but before the swap).
     *
     * <p>The third arm — the staleness scope on {@code swapDone} — has no counterpart pre-swap, because the shadow
     * receives no writes except the copy. It is what stops the frozen guard destroying a post-cutover write: the guard
     * cannot see a row written after the swap, so a key deleted before it and re-created after looks "still deleted".
     * The scope is per ROW, so a leaked stale copy is masked while a newer version of the same key survives.
     * {@link #postSwapDeletionReplayWithoutStalenessScope} is the negative control that proves it load-bearing.
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
                      WHERE source_table = 'traces'
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
                      FROM traces_pre_cutover_backup
                      WHERE id IN (
                          SELECT toFixedString(deleted_id, 36)
                          FROM deletion_events_local
                          WHERE source_table = 'traces'
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
     * NEGATIVE CONTROL for {@link #postSwapDeletionReplay}: the same statement with the staleness scope deliberately
     * omitted, i.e. 000002's two-arm predicate aimed at the post-swap estate. It exists to be run once, on a trace
     * re-created after the swap, and watched to destroy it — without which "the scope is load-bearing" is an assertion
     * about code nobody has seen fail. Never a shape the runbook ships.
     */
    private void postSwapDeletionReplayWithoutStalenessScope(String gapStart) {
        execute("""
                DELETE FROM traces
                WHERE (workspace_id, project_id, id) IN (
                      SELECT
                          workspace_id,
                          toFixedString(project_id, 36),
                          toFixedString(deleted_id, 36)
                      FROM deletion_events_local
                      WHERE source_table = 'traces'
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
                      FROM traces_pre_cutover_backup
                      WHERE id IN (
                          SELECT toFixedString(deleted_id, 36)
                          FROM deletion_events_local
                          WHERE source_table = 'traces'
                            AND event_time >= toDateTime64(:gap_start, 6, 'UTC')
                            AND length(deleted_id) = 36
                      )
                  )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """, statement -> statement.bind("gap_start", gapStart));
    }

    /**
     * One forward reconciliation pass, in the order {@code reconcile.sh} runs the two blocks: sweep, then replay, so
     * bridged deletes win over anything the sweep re-inserted. Encoded once because that order is load-bearing — a test
     * that reversed it would still pass on every case where nothing was deleted.
     *
     * <p>Callers that need to observe the two halves separately — proving the sweep alone cannot fix a delete, or that
     * the replay without its staleness scope destroys a re-creation — call them individually instead.
     */
    private void reconcileForward(String gapStart, String swapDone) {
        reconcileForward("traces", gapStart, swapDone);
    }

    /** As above against the live table {@code reconcile.sh} resolves: {@code traces_local} on a wrapped estate. */
    private void reconcileForward(String liveTable, String gapStart, String swapDone) {
        forwardSweep(liveTable, gapStart, swapDone);
        postSwapDeletionReplay(liveTable, gapStart, swapDone);
    }

    /**
     * One reverse reconciliation pass: the sweep, then {@code 000004_rollback_reverse_replay.sql} unchanged. Same
     * ordering rule, same reason — the replay is what makes post-cutover deletes win over the re-imported writes.
     */
    private void reconcileReverse(String cutoverStart, String promoteDone) {
        reverseSweep(cutoverStart, promoteDone);
        reverseReplay(cutoverStart);
    }

    /**
     * The POST-SWAP forward sweep (000006 {@code forward-sweep}): copy the gap window out of the FROZEN
     * {@code traces_pre_cutover_backup} into the live successor. The source is the old original, so the projection is
     * the backfill's: {@code end_time} and {@code ttft} are still Nullable and coalesce to the successor's sentinels.
     * The live table is the statement's one parameter, mirroring the {@code ${LIVE_TABLE}} the shipped block carries.
     *
     * <p>Mask-honored ({@code apply_deleted_mask} stays at its default) and idempotent (the target is a
     * {@code ReplacingMergeTree}, so a re-inserted row loses to anything newer). The {@code NOT IN} arm is what stops it
     * resurrecting a gap-window trace deleted AFTER the swap; deletes bridged BEFORE the swap are handled instead by the
     * deletion replay that runs after it, so a trace deleted and re-created before the freeze is still swept back.
     */
    private void forwardSweep(String liveTable, String gapStart, String swapDone) {
        execute("""
                INSERT INTO %s (
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
                    environment
                )
                SELECT
                    id,
                    workspace_id,
                    project_id,
                    name,
                    start_time,
                    coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) AS end_time,
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
                    environment
                FROM traces_pre_cutover_backup
                WHERE (created_at >= toDateTime64(:gap_start, 6, 'UTC')
                    OR last_updated_at >= toDateTime64(:gap_start, 6, 'UTC'))
                  AND (workspace_id, project_id, id) NOT IN (
                      SELECT
                          workspace_id,
                          toFixedString(project_id, 36),
                          toFixedString(deleted_id, 36)
                      FROM deletion_events_local
                      WHERE source_table = 'traces'
                        AND event_time >= toDateTime64(:swap_done, 6, 'UTC')
                        AND project_id != ''
                        AND length(project_id) = 36
                        AND length(deleted_id) = 36
                  )
                SETTINGS max_partitions_per_insert_block = 2000
                """.formatted(liveTable),
                statement -> statement.bind("gap_start", gapStart).bind("swap_done", swapDone));
    }

    /**
     * The REVERSE sweep (000006 {@code reverse-sweep}): re-import the post-cutover writes a promote made non-live, out
     * of {@code traces_post_rollback_backup} and back into the restored original.
     *
     * <p>The projection is the inverse of the forward one, and it is what this statement is about: the successor's
     * non-nullable epoch / NaN sentinels are denormalized back to the original's {@code NULL} convention. Without that,
     * the original's MATERIALIZED {@code duration} — which guards {@code end_time IS NOT NULL} and knows nothing of the
     * epoch — recomputes a large NEGATIVE value for every re-imported trace that had not ended.
     *
     * <p>The epoch literal pins {@code 'UTC'} here and deliberately does not in the forward direction: forward, the
     * sentinel being WRITTEN has to agree with the successor's own unpinned DEFAULT and duration expression; reverse,
     * the sentinel being READ was written by the backend as an absolute {@code Instant.EPOCH}. Same asymmetry, and the
     * same reasoning, as 000004_rollback_sentinel_repair.sql.
     */
    private void reverseSweep(String gapStart, String swapDone) {
        execute("""
                INSERT INTO traces (
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
                    environment
                )
                SELECT
                    id,
                    workspace_id,
                    project_id,
                    name,
                    start_time,
                    nullIf(end_time, toDateTime64('1970-01-01 00:00:00', 6, 'UTC')) AS end_time,
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
                    if(isNaN(ttft), NULL, ttft) AS ttft,
                    source,
                    environment
                FROM traces_post_rollback_backup
                WHERE (created_at >= toDateTime64(:gap_start, 6, 'UTC')
                    OR last_updated_at >= toDateTime64(:gap_start, 6, 'UTC'))
                  AND (workspace_id, project_id, id) NOT IN (
                      SELECT
                          workspace_id,
                          toFixedString(project_id, 36),
                          toFixedString(deleted_id, 36)
                      FROM deletion_events_local
                      WHERE source_table = 'traces'
                        AND event_time >= toDateTime64(:swap_done, 6, 'UTC')
                        AND project_id != ''
                        AND length(project_id) = 36
                        AND length(deleted_id) = 36
                  )
                SETTINGS max_partitions_per_insert_block = 2000
                """,
                statement -> statement.bind("gap_start", gapStart).bind("swap_done", swapDone));
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
                    WHERE source_table = 'traces'
                      AND event_time >= toDateTime64(:cutover_start, 6, 'UTC')
                      AND project_id != ''
                      AND length(project_id) = 36
                      AND length(deleted_id) = 36
                )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """, statement -> statement.bind("cutover_start", cutoverStart));
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
        lightweightDeleteFrom("traces", ids, workspaceId, projectId);
    }

    /**
     * The same lightweight delete against a named table, so a test can put the PARKED backup into the state a delete
     * that fired before the freeze would have left it in.
     */
    private void lightweightDeleteFrom(String table, Set<String> ids, String workspaceId, UUID projectId) {
        execute("""
                DELETE FROM %s
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND id IN :ids
                """.formatted(table),
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
                    WHERE source_table = 'traces'
                      AND event_time >= toDateTime64(:cutover_start, 6, 'UTC')
                      AND project_id != ''
                      AND length(project_id) = 36
                      AND length(deleted_id) = 36
                )
                """.formatted(DATABASE_NAME, DATABASE_NAME);
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("cutover_start", cutoverStart)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("resurrected", Long.class)))))
                .block();
    }

    /**
     * The reconciliation postcondition (000006_verify_reconciliation), reimplemented inline like the rest of this class.
     * For every key live in the PARKED table inside the gap window, the live table's version of that key is classified:
     * absent -> {@code missing}; strictly older -> {@code stale}; same version with a differing normalized fingerprint
     * -> {@code payloadMismatch}; newer -> {@code newer}. The gate is the first three at 0; {@code newer} is
     * informational and expected to be non-zero on a busy estate, the same idiom
     * {@code 000004_rollback_verify_sentinels.sql} uses for {@code negative_from_sentinel}.
     *
     * <p>The fingerprints come from {@link #rowHash}, so they are the exact normalization {@code verify.sh} and the
     * fidelity assertions use, and they follow {@link #COPIED_COLUMNS} — which means a base column added by a future
     * migration is covered here by construction, not by a second hand-maintained list. The VERSION is compared as a
     * microsecond epoch for the same reason it is hashed as one: the copy truncates nanoseconds to microseconds, so
     * comparing the raw columns would report every faithfully-copied row with a sub-microsecond {@code last_updated_at}
     * as stale.
     *
     * <p>No {@code clusterAllReplicas}, unlike the replay and sentinel gates: a Replicated table returns one copy per
     * replica through it, which would multiply both sides of the join. The driver runs the cluster-wide settle gate
     * before reading this instead, which is what makes a single-replica read representative.
     *
     * @param exclusionFrom the bridge {@code event_time} floor for keys that are LEGITIMATELY absent from the live
     *                      table: {@code swap_done} forward (only post-swap deletes are absent, the forward replay's
     *                      resurrection guard sparing anything live in the parked table), {@code cutover_start} reverse
     *                      (the guard-less reverse replay masks every key bridged since then).
     */
    private ReconciliationCounts reconciliationCounts(String parkedTable, Shape parkedShape, String liveTable,
            String gapStart, String exclusionFrom) {
        var parkedHash = rowHash(parkedShape == Shape.OLD ? OLD_HASH_OVERRIDES : NEW_HASH_OVERRIDES);
        var liveHash = rowHash(parkedShape == Shape.OLD ? NEW_HASH_OVERRIDES : OLD_HASH_OVERRIDES);
        var parkedVersion = parkedShape == Shape.OLD
                ? "toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6))"
                : "toUnixTimestamp64Micro(last_updated_at)";
        var liveVersion = parkedShape == Shape.OLD
                ? "toUnixTimestamp64Micro(last_updated_at)"
                : "toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6))";
        var sql = """
                WITH
                    parked AS (
                        SELECT
                            (workspace_id, project_id, id) AS key,
                            %s AS parked_version,
                            %s AS parked_fingerprint
                        FROM %s FINAL
                        WHERE (created_at >= toDateTime64(:gap_start, 6, 'UTC')
                            OR last_updated_at >= toDateTime64(:gap_start, 6, 'UTC'))
                          AND (workspace_id, project_id, id) NOT IN (
                              SELECT
                                  workspace_id,
                                  toFixedString(project_id, 36),
                                  toFixedString(deleted_id, 36)
                              FROM deletion_events_local
                              WHERE source_table = 'traces'
                                AND event_time >= toDateTime64(:exclusion_from, 6, 'UTC')
                                AND project_id != ''
                                AND length(project_id) = 36
                                AND length(deleted_id) = 36
                          )
                    ),
                    live AS (
                        SELECT
                            (workspace_id, project_id, id) AS key,
                            %s AS live_version,
                            %s AS live_fingerprint
                        FROM %s FINAL
                        WHERE (workspace_id, project_id, id) IN (SELECT key FROM parked)
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
                """.formatted(parkedVersion, parkedHash, parkedTable, liveVersion, liveHash, liveTable);
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("gap_start", gapStart)
                                .bind("exclusion_from", exclusionFrom)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> ReconciliationCounts.builder()
                                .missing(row.get("missing_keys", Long.class))
                                .stale(row.get("stale_keys", Long.class))
                                .payloadMismatch(row.get("payload_mismatch_keys", Long.class))
                                .newer(row.get("newer_keys", Long.class))
                                .build()))))
                .block();
    }

    /** Forward direction: the parked pre-cutover backup (OLD shape) against the live successor. */
    private ReconciliationCounts forwardCounts(String gapStart, String swapDone) {
        return reconciliationCounts("traces_pre_cutover_backup", Shape.OLD, "traces", gapStart, swapDone);
    }

    /**
     * 000006's {@code leak-check-forward}: captured deletes still live on the successor at a version the frozen backup
     * itself held. {@code apply_deleted_mask = 0} is what lets the backup side see rows the mask hides, and
     * {@code _row_exists = 1} restores mask-honoring on the live side by hand, since the setting is statement-wide.
     */
    private long leakCheckForward(String gapStart) {
        var sql = """
                WITH
                    bridged AS (
                        SELECT
                            workspace_id,
                            toFixedString(project_id, 36) AS project_id,
                            toFixedString(deleted_id, 36) AS id
                        FROM deletion_events_local
                        WHERE source_table = 'traces'
                          AND event_time >= toDateTime64(:gap_start, 6, 'UTC')
                          AND project_id != ''
                          AND length(project_id) = 36
                          AND length(deleted_id) = 36
                    ),
                    deleted AS (
                        SELECT
                            (workspace_id, project_id, id) AS key,
                            toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)) AS version
                        FROM traces_pre_cutover_backup
                        WHERE _row_exists = 0
                          AND (workspace_id, project_id, id) IN (SELECT workspace_id, project_id, id FROM bridged)
                    )
                SELECT count() AS c
                FROM (
                    SELECT
                        (workspace_id, project_id, id) AS key,
                        toUnixTimestamp64Micro(last_updated_at) AS version
                    FROM traces FINAL
                    WHERE _row_exists = 1
                      AND (workspace_id, project_id, id) IN (SELECT workspace_id, project_id, id FROM bridged)
                ) AS live
                WHERE (live.key, live.version) IN (SELECT key, version FROM deleted)
                SETTINGS apply_deleted_mask = 0, use_skip_indexes_if_final = 1
                """;
        return template
                .nonTransaction(connection -> Mono
                        .from(connection.createStatement(sql)
                                .bind("gap_start", gapStart)
                                .execute())
                        .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get("c", Long.class)))))
                .block();
    }

    /** Reverse direction: the parked successor (NEW shape) against the restored original. */
    private ReconciliationCounts reverseCounts(String gapStart, String cutoverStart) {
        return reconciliationCounts("traces_post_rollback_backup", Shape.NEW, "traces", gapStart, cutoverStart);
    }

    /**
     * The counts 000006_verify_reconciliation.sql returns. {@code missing}, {@code stale} and {@code payloadMismatch}
     * are the gate; {@code newer} is informational and expected to be non-zero wherever post-swap traffic touched a
     * gap-window key. Built through the builder at every call site so each expected number is read with the bucket it
     * belongs to — four positional longs would be indistinguishable from each other at a glance.
     */
    @Builder(toBuilder = true)
    private record ReconciliationCounts(long missing, long stale, long payloadMismatch, long newer) {
    }

    /** The passing gate: nothing missing, nothing stale, no payload differing, and no post-swap write to tolerate. */
    private static ReconciliationCounts reconciled() {
        return ReconciliationCounts.builder().missing(0).stale(0).payloadMismatch(0).newer(0).build();
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
        return sentinelCounts(
                """
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
                        .formatted(DATABASE_NAME),
                windowFrom, windowTo);
    }

    /**
     * The same counts evaluated under a non-UTC {@code session_timezone}, which is the only way this suite can catch an
     * unpinned epoch literal: the container runs UTC.
     */
    private SentinelCounts sentinelCountsUnderForeignTimezone(String windowFrom, String windowTo) {
        return sentinelCounts(
                """
                        SELECT
                            uniqExactIf((workspace_id, project_id, id), end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')) AS sentinel_end_time,
                            uniqExactIf((workspace_id, project_id, id), isNaN(ttft)) AS sentinel_ttft,
                            uniqExactIf((workspace_id, project_id, id),
                                        duration < 0 AND end_time = toDateTime64('1970-01-01 00:00:00', 9, 'UTC')) AS negative_from_sentinel,
                            uniqExactIf((workspace_id, project_id, id), duration < 0 AND end_time IS NULL) AS stale_duration
                        FROM clusterAllReplicas('{cluster}', %s.traces)
                        WHERE (   (created_at      >= toDateTime64(:from, 6, 'UTC') AND created_at      < toDateTime64(:to, 6, 'UTC'))
                               OR (last_updated_at >= toDateTime64(:from, 6, 'UTC') AND last_updated_at < toDateTime64(:to, 6, 'UTC')))
                        SETTINGS session_timezone = 'America/New_York'
                        """
                        .formatted(DATABASE_NAME),
                windowFrom, windowTo);
    }

    private SentinelCounts sentinelCounts(String sql, String windowFrom, String windowTo) {
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
     * What the copy landed on the successor for one named row: whether it arrived at all, and whether the projection
     * wrote the NaN {@code ttft} sentinel. One query, so {@code any()} over a single-row match is that row; callers
     * assert {@code rows} first, so an empty match cannot be mistaken for a value.
     */
    private CopiedRow copiedRow(String workspaceId, String name) {
        var sql = """
                SELECT
                    count() AS rows,
                    any(isNaN(ttft)) AS ttft_is_nan
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
                                row.get("ttft_is_nan", Boolean.class))))))
                .block();
    }

    private record CopiedRow(long rows, boolean ttftIsNaN) {
    }

    /**
     * Bridge events the deletion replay's window selects, with the session put WEST of UTC; see
     * {@link #backfillWeekWestwardSession(int)}. The predicate repeats {@link #replayDeletions(String)}'s outer
     * subquery, which is the point: it is what the replay filters on, and the two must stay in step.
     */
    private long bridgedDeletionsSinceWestwardSession(String backfillStart) {
        return scalar("""
                SELECT count() AS c
                FROM deletion_events_local
                WHERE source_table = 'traces'
                  AND event_time >= toDateTime64(:backfill_start, 6, 'UTC')
                  AND project_id != ''
                  AND length(project_id) = 36
                  AND length(deleted_id) = 36
                SETTINGS session_timezone = 'America/New_York'
                """, statement -> statement.bind("backfill_start", backfillStart));
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
    private long versionTies(String table, Shape shape, String workspaceId) {
        return scalar("""
                SELECT count() AS c
                FROM (
                    SELECT key, argMax(distinct_at_version, version) AS distinct_at_newest
                    FROM (
                        SELECT
                            (workspace_id, project_id, id) AS key,
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
