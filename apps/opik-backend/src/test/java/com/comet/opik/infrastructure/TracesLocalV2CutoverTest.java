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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/**
 * End-to-end validation of the buffered cutover that migrates {@code traces} to its partitioned, sharding-ready
 * successor {@code traces_local_v2}. It rehearses the full sequence against a fresh ClickHouse in raw SQL — the same
 * steps an operator runs from the {@code data-migrations/traces-local-v2-cutover} runbook — and pins the properties
 * the cutover's correctness depends on.
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
 * and reverse-replays so a post-cutover delete does not resurrect.
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

    private static final int REPLAY_BUDGET_SECONDS = 60;

    private static final String[] FIDELITY_SOURCES = {"sdk", "experiment", "playground", "optimization", "evaluator"};
    private static final String[] FIDELITY_ENVIRONMENTS = {"production", "staging", "dev", ""};

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

    /**
     * Restore the canonical baseline (traces = original schema, traces_local_v2 = successor schema, both empty; no stray
     * wrap/rename artifacts) before every test, independent of what the previous test left behind. A green run always
     * ends canonical, but a test that fails mid-cutover can leak any intermediate topology, so rather than assume a clean
     * hand-off this normalizes whatever is present back to canonical. The cutover only ever produces these shapes: the
     * completed EXCHANGE (traces = successor, original parked as traces_pre_cutover_backup) and wrap (traces =
     * Distributed over traces_local), plus the partial states where only the first of a two-statement swap/wrap ran.
     * Every DDL below is guarded on the tables it touches, so no leaked state can make the reset itself throw and
     * cascade into later tests. {@code end_time} being Nullable is the original schema, non-Nullable the successor.
     */
    @BeforeEach
    void resetTables() {
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
        //    unrecoverable partial state still cannot throw here). traces_dist is the temp wrapper the gapless wrap
        //    builds before its atomic rename — a leak only if a test died between the CREATE and the RENAME.
        execute("DROP TABLE IF EXISTS traces_dist ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_local ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_pre_cutover_backup ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("TRUNCATE TABLE IF EXISTS traces", _ -> {
        });
        execute("TRUNCATE TABLE IF EXISTS traces_local_v2", _ -> {
        });
        execute("TRUNCATE TABLE IF EXISTS deletion_events_local", _ -> {
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
        // Deletes the delete-by-ids path could not resolve to a project — captured in the bridge with an empty
        // project_id. The replay must still remove them (matched by (workspace_id, id)) or they leak across the swap.
        var unresolvedDeleted = mintIds(USER_DELETED_PER_WEEK);
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
        allSeeded.addAll(unresolvedDeleted);
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
        // Unresolved-project deletes: captured with an empty project_id (delete-by-ids couldn't resolve the project).
        recordDeletionEvents(idStrings(unresolvedDeleted), workspaceId, "", "user_request");
        lightweightDelete(idStrings(unresolvedDeleted), workspaceId);
        // Reused-id delete scoped to projectId only — the copy under otherProjectId must survive.
        recordDeletionEvents(Set.of(reusedId.toString()), workspaceId, projectId.toString(), "user_request");
        lightweightDeleteScoped(Set.of(reusedId.toString()), workspaceId, projectId);
        // Concurrent upserts: a newer version of a subset of survivors — caught by the delta's last_updated_at arm.
        var deltaUpserted = survivors.subList(0, DELTA_UPSERTS);
        insertRows(deltaUpserted, workspaceId, projectId, deltaName(), _ -> Instant.now());
        // Rows created during the window with a client-backdated last_updated_at — caught ONLY by the created_at arm.
        var deltaLateCreated = mintNowIds(DELTA_LATE_CREATED);
        insertRows(deltaLateCreated, workspaceId, projectId, "late", _ -> BACKDATED);

        // Delta-insert: created_at OR last_updated_at since backfill_start (see class Javadoc).
        deltaInsert(backfillStart);

        // Negative control — before replay, the during-backfill deletes have leaked onto the destination: still fully
        // alive there, because the delta-insert cannot see a lightweight delete. This is what the bridge exists to fix.
        // Includes the unresolved (empty-project) deletes, which only the replay's (workspace_id, id) branch catches.
        var leakedIds = union(union(idStrings(retentionDeleted), idStrings(userDeleted)), idStrings(unresolvedDeleted));
        assertThat(liveCount("traces_local_v2", leakedIds, workspaceId))
                .as("negative control: without replay, during-backfill deletes leak across the copy")
                .isEqualTo(leakedIds.size());
        // Both delta arms worked: the backdated-last_updated_at rows were caught via created_at.
        assertThat(liveCount("traces_local_v2", idStrings(deltaLateCreated), workspaceId))
                .as("delta created_at arm caught rows written during the window with a backdated last_updated_at")
                .isEqualTo(deltaLateCreated.size());

        // Deletion replay: read the bridge for the window and re-issue the deletes against the destination, matched on
        // the full key so a reused id in another project is untouched.
        var replayMillis = replayDeletions(backfillStart);
        log.info("Deletion replay covered {} ids in {} ms", leakedIds.size() + 1, replayMillis);
        assertThat(replayMillis)
                .as("replay wall time smoke bound — the runbook compares this against the buffer window")
                .isLessThan(REPLAY_BUDGET_SECONDS * 1_000L);

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
        assertThat(liveCount("traces", idStrings(unresolvedDeleted), workspaceId))
                .as("unresolved (empty-project) deletions do not leak across the EXCHANGE")
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

        // Rollback (Stage C) — the wrap is reversible without resurrecting post-cutover deletes. A sharding-aware app
        // deletes on the local table, so simulate a post-wrap delete on `traces_local` and record it in the bridge with
        // an empty project (the unresolved case), then roll back: drop the Distributed wrapper, promote the parked old
        // data back to `traces`, and reverse-replay from cutover_start.
        var postWrapDeleted = Set.of(survivors.getFirst().id().toString());
        recordDeletionEvents(postWrapDeleted, workspaceId, "", "user_request");
        execute("DELETE FROM traces_local WHERE workspace_id = :workspace_id AND id IN :ids",
                statement -> statement.bind("workspace_id", workspaceId).bind("ids", postWrapDeleted));
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
        assertThat(tableExists("traces_local_v2"))
                .as("rollback ends in the canonical state: successor data parked as traces_local_v2")
                .isTrue();
        assertThat(tableExists("traces_local"))
                .as("no leftover sharding table after rollback")
                .isFalse();
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
     * resurrect on the restored original. Exercises the reverse-replay's FULL-KEY branch (the comprehensive test covers
     * the empty-project branch in stage C).
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
        assertThat(tableExists("traces_local_v2"))
                .as("canonical state: successor parked as traces_local_v2")
                .isTrue();
        assertThat(tableExists("traces_local"))
                .as("no leftover sharding table after stage B")
                .isFalse();
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

        // Restore the canonical baseline (traces = original, traces_local_v2 = successor) so @BeforeEach's reset — which
        // assumes a regular `traces` — works for the next test. The stage-C reverse-replay still runs here; this test
        // bridged no deletes in the (cutoverStart, ∞) window, so it matches zero ids and deletes nothing.
        rollbackAfterWrap(cutoverStart);
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
     * <p>Parameterized over the bridge capture shape so <b>both</b> replay branches carry the guard: a delete captured
     * WITH its project exercises the full-key branch, one captured with an empty project (the workspace-scoped delete
     * fallback) exercises the {@code (workspace_id, id)} branch. Both branches must spare a resurrected id.
     */
    @ParameterizedTest(name = "resurrection guard holds on the {0}-project replay branch")
    @ValueSource(booleans = {true, false})
    void deleteThenResurrectSurvivesTheReplay(boolean resolvedProject) {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = ID_GENERATOR.generateId();
        // Capture shape selects the replay branch: the resolved project (full key) or an empty project (workspace-scoped).
        var captureProject = resolvedProject ? projectId.toString() : "";
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
        recordDeletionEvents(idStrings(resurrected), workspaceId, captureProject, "user_request");
        lightweightDelete(idStrings(resurrected), workspaceId);
        recordDeletionEvents(idStrings(stayDeleted), workspaceId, captureProject, "user_request");
        lightweightDelete(idStrings(stayDeleted), workspaceId);
        insertRows(resurrected, workspaceId, projectId, "resurrected", _ -> Instant.now());

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
                WHERE created_at >= parseDateTime64BestEffort(:week_lo, 9)
                  AND created_at < parseDateTime64BestEffort(:week_hi, 9)
                SETTINGS max_insert_block_size = 100000
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
                WHERE created_at >= parseDateTime64BestEffort(:backfill_start, 6)
                   OR last_updated_at >= parseDateTime64BestEffort(:backfill_start, 6)
                SETTINGS max_insert_block_size = 100000
                """.formatted(COPIED_COLUMNS, COPIED_SELECT),
                statement -> statement.bind("backfill_start", backfillStart));
    }

    /**
     * Reads the bridge for the cutover window and removes the captured deletes from the destination in a single
     * mutation (mirrors 000002). Two branches, because the delete-by-ids path does not always resolve a trace's project:
     * events WITH a project match the full key {@code (workspace_id, project_id, id)} (exact; a reused id in another
     * project is untouched); events captured WITHOUT a project match {@code (workspace_id, id)} — otherwise those
     * deletions silently leak across the swap. Each branch also requires the id is NOT currently live on the source
     * (the resurrection guard), so a deleted-then-recreated id is not dropped. Returns the wall time so the runbook can
     * size it against the buffer window.
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
                          AND event_time >= parseDateTime64BestEffort(:backfill_start, 6)
                          AND project_id != ''
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
                              AND event_time >= parseDateTime64BestEffort(:backfill_start, 6)
                        )
                    )
                )
                OR (
                    (workspace_id, id) IN (
                        SELECT
                            workspace_id,
                            toFixedString(deleted_id, 36)
                        FROM deletion_events_local
                        WHERE source_table = 'traces'
                          AND event_time >= parseDateTime64BestEffort(:backfill_start, 6)
                          AND project_id = ''
                    )
                    AND (workspace_id, id) NOT IN (
                        SELECT
                            workspace_id,
                            id
                        FROM traces
                        WHERE id IN (
                            SELECT toFixedString(deleted_id, 36)
                            FROM deletion_events_local
                            WHERE source_table = 'traces'
                              AND event_time >= parseDateTime64BestEffort(:backfill_start, 6)
                        )
                    )
                )
                SETTINGS allow_nondeterministic_mutations = 1,
                         lightweight_deletes_sync = 2
                """, statement -> statement.bind("backfill_start", backfillStart));
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
        execute(("CREATE TABLE traces_dist ON CLUSTER '{cluster}' AS traces "
                + "ENGINE = Distributed('{cluster}', '" + DATABASE_NAME + "', 'traces_local', sipHash64(project_id))"),
                _ -> {
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
     * names back — the successor ({@code traces}) returns to {@code traces_local_v2} and the original
     * ({@code traces_pre_cutover_backup}) returns to {@code traces} (the name freed by the first clause) — so there is no
     * window where a partial failure strands the successor under the backup name. Then reverse-replay so a delete on the
     * successor since {@code cutoverStart} does not resurrect on the restored original.
     */
    private void rollbackExchangeBack(String cutoverStart) {
        execute("""
                RENAME TABLE
                    traces TO traces_local_v2,
                    traces_pre_cutover_backup TO traces
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
        reverseReplay(cutoverStart);
    }

    /**
     * Rollback stage C (000004_rollback_stage_c + reverse_replay): promote the parked original back to {@code traces}
     * GAPLESSLY — EXCHANGE swaps it in atomically (no window where {@code traces} is absent), then the now-data-less
     * Distributed wrapper (parked under the backup name) is dropped and the successor shard is parked back under
     * {@code traces_local_v2}, ending in the canonical state with no leftover names. Then reverse-replay.
     */
    private void rollbackAfterWrap(String cutoverStart) {
        execute("EXCHANGE TABLES traces AND traces_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
        });
        execute("DROP TABLE IF EXISTS traces_pre_cutover_backup ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute("RENAME TABLE traces_local TO traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        reverseReplay(cutoverStart);
    }

    /**
     * The shared reverse-replay (000004_rollback_reverse_replay): re-apply the deletes captured since
     * {@code cutoverStart} onto the restored original, so they do not resurrect. Two branches — full key for events with
     * a project, {@code (workspace_id, id)} for the workspace-scoped (empty-project) fallback. Unlike the forward replay
     * it carries NO resurrection guard by design: rollback abandons post-cutover writes while honoring post-cutover
     * deletes, so a bridged id is masked unconditionally (a guard would undo the user's delete). See the .sql header.
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
                      AND event_time >= parseDateTime64BestEffort(:cutover_start, 6)
                      AND project_id != ''
                )
                OR (workspace_id, id) IN (
                    SELECT
                        workspace_id,
                        toFixedString(deleted_id, 36)
                    FROM deletion_events_local
                    WHERE source_table = 'traces'
                      AND event_time >= parseDateTime64BestEffort(:cutover_start, 6)
                      AND project_id = ''
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
     * Batch INSERT into the bridge, mirroring {@code DeletionEventDAO}'s write shape. {@code projectId} is a string so a
     * caller can pass {@code ""} to reproduce an unresolved delete (the delete-by-ids path records an empty project when
     * it cannot resolve a trace's project).
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

    // --- query helpers -------------------------------------------------------------------------------------------

    /** Distinct live (mask-honored) ids from {@code table} within {@code ids} — collapses ReplacingMergeTree versions. */
    private long liveCount(String table, Set<String> ids, String workspaceId) {
        if (ids.isEmpty()) {
            return 0L;
        }
        var sql = """
                SELECT uniqExact(id) AS c
                FROM %s
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

    private long liveCountScoped(String table, Set<String> ids, String workspaceId, UUID projectId) {
        var sql = """
                SELECT uniqExact(id) AS c
                FROM %s
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
                "SELECT toString(now64(6)) AS n")
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
     * prove the successor's expressions did not drift from the source's. {@code duration} is checked separately
     * ({@link #durationMismatches}) because its value legitimately differs by up to the ns-to-us truncation.
     */
    private Fingerprint derivedFingerprint(String table, String workspaceId) {
        var sql = """
                SELECT
                    count() AS c,
                    sum(cityHash64(
                        id,
                        id_at,
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
    private List<CategorizedId> mintNowIds(int count) {
        var ids = new ArrayList<CategorizedId>();
        for (int i = 0; i < count; i++) {
            var createdAt = Instant.now();
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
    private Instant weekInstant(int weekOffset, int minuteOffset) {
        return ANCHOR_MONDAY.plusWeeks(weekOffset).atTime(1, 0).plusSeconds(minuteOffset).toInstant(ZoneOffset.UTC);
    }

    @Builder(toBuilder = true)
    private record CategorizedId(UUID id, Instant createdAt) {
    }
}
