package com.comet.opik.domain;

import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.FilterUtils;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.ErrorInfo.ERROR_INFO_TYPE;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.ExperimentDAO.getFeedbackScores;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.JsonUtils.getJsonNodeOrDefault;
import static com.comet.opik.utils.JsonUtils.getStringOrDefault;

@ImplementedBy(OptimizationDAOImpl.class)
public interface OptimizationDAO {

    record OptimizationSummary(UUID datasetId, long optimizationCount, Instant mostRecentOptimizationAt) {
        public static OptimizationSummary empty(UUID datasetId) {
            return new OptimizationSummary(datasetId, 0, null);
        }
    }

    /**
     * A studio optimization whose latest status is non-terminal and older than the reaper threshold,
     * i.e. stuck because the worker never advanced it. Carries {@code workspaceId} so the reconciler
     * can seed the workspace context required to update the row and finalize its logs (OPIK-7159).
     */
    @Builder(toBuilder = true)
    record StalledOptimization(@NonNull UUID id, @NonNull String workspaceId, @NonNull OptimizationStatus status) {
    }

    Mono<Void> upsert(Optimization optimization);

    Mono<Optimization> getById(UUID id);

    Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids);

    Mono<Long> delete(Set<UUID> ids);

    Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds);

    /**
     * @param clearErrorInfo blanks the {@code error_info} column instead of carrying it forward. True only
     *                       when a worker report supersedes a failure the platform detected rather than the
     *                       worker reporting it: the recorded reason described a run that turned out to be
     *                       alive, and nothing else ever clears that column. Deliberately not an overload —
     *                       a two-argument convenience form would let a caller (or a test stub) miss this
     *                       decision silently.
     */
    Mono<Long> update(UUID id, OptimizationUpdate update, boolean clearErrorInfo);

    Mono<Long> updateDatasetDeleted(Set<UUID> datasetIds);

    Mono<Optimization.OptimizationPage> find(int page, int size, @NonNull OptimizationSearchCriteria searchCriteria);

    Flux<OptimizationSummary> findOptimizationSummaryByDatasetIds(Set<UUID> datasetIds);

    Flux<StalledOptimization> findStalledStudioOptimizations(Duration initializedTimeout, Duration runningTimeout,
            Duration runningHardTimeout, Duration lookbackMargin, int limit, int candidateScanFactor);

    Mono<Boolean> hasRecentStudioActivity(UUID optimizationId, Duration window);

    /**
     * Latest status + row timestamp of a run, straight off the {@code optimizations} table. The reaper's
     * pre-update re-read MUST use this instead of {@link #getById} (the full {@code FIND} with its
     * experiment/trace/score joins): the reaper only needs these two fields, and its liveness decision
     * must stay decoupled from {@code FIND}'s mapping of related data — {@code FIND} used to silently
     * drop a run whose trial item referenced a still-unfinished trace (exactly the state a worker killed
     * mid-trial leaves behind; found by OPIK-7459 e2e, fixed in {@code FIND}'s NaN guards), and an empty
     * re-read made the reaper skip that run on every cycle, resurrecting the eternal spinner this job
     * exists to prevent. The bare read keeps any future {@code FIND} regression from ever re-breaking
     * the reaper.
     */
    @Builder(toBuilder = true)
    record OptimizationStatusSnapshot(@NonNull OptimizationStatus status, @NonNull Instant lastUpdatedAt,
            @NonNull Instant startedAt) {
    }

    Mono<OptimizationStatusSnapshot> getStatusSnapshotById(UUID id);

    /**
     * The optimization row alone — no experiment/trace/score joins, so the aggregate fields
     * ({@code numTrials}, scores, durations, costs) are left null. Fallback for write paths in case
     * {@link #getById}'s full {@code FIND} ever fails to map the run again (see
     * {@link #getStatusSnapshotById}): a status update must never be blocked by related data.
     */
    Mono<Optimization> getRowById(UUID id);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationDAOImpl implements OptimizationDAO {

    /**
     * Studio runs whose latest row version is stuck in a non-terminal status past the reaper threshold
     * (OPIK-7159 / OPIK-7459). Selects on either "no liveness" or "past the hard ceiling"; see
     * {@code OptimizationStalledReaperJob} for what those two mean and why both exist.
     *
     * <p>Liveness is the newest of the row's {@code last_updated_at}, the latest trial experiment's
     * {@code created_at} and the latest experiment item's {@code created_at}. {@code last_updated_at}
     * advances only on a status change, so the other two are what keep a healthy long run alive: one trial
     * evaluates up to {@code OPTSTUDIO_DATASET_SAMPLES} items and can run for hours, so trial-creation
     * alone would false-positive mid-trial. Because the {@code HAVING} already requires the row timestamp
     * to be past the threshold, liveness reduces to the {@code active_optimizations} anti-join.
     *
     * <p>Things the SQL will not tell you, and that break the query if changed:
     * <ul>
     * <li>The status/timeout predicates must stay in {@code HAVING} — above the dedup, out of the
     * {@code WHERE}. In the {@code WHERE} they reference an aggregate and ClickHouse raises
     * {@code ILLEGAL_AGGREGATION}; above the dedup is also what stops a run being selected off a stale
     * version after it reached a terminal status.</li>
     * <li>The nested {@code (workspace_id, experiment_id) IN (SELECT ... FROM candidate_trials)} is
     * load-bearing. The outer {@code IN} already makes it redundant for correctness, but it is the only
     * thing keeping the item probe from scanning every recent {@code experiment_items} row in the
     * deployment. Do not simplify it away.</li>
     * <li>ClickHouse inlines {@code WITH} subqueries rather than materialising them, so a CTE referenced
     * N times is evaluated N times. One tick aggregates {@code optimizations} 3x, scans
     * {@code experiments} 2x and {@code experiment_items} 1x. Kept deliberately: bounded by the id sets
     * below, the duplicated work is a rounding error against a 5-minute cadence.</li>
     * <li>Both probes are scoped by <em>id sets</em>, not by a time floor: {@code experiments} by
     * {@code (workspace_id, optimization_id) IN candidates} (resolved through
     * {@code idx_experiments_optimization_id}, migration 000069) and {@code experiment_items} by
     * {@code (workspace_id, experiment_id) IN candidate_trials}, which is the primary-key prefix. The
     * {@code created_at} comparisons are residual predicates — they are the liveness semantics, and cost
     * nothing once the read is bounded by the key. The tuple form is also what keeps both probes
     * workspace-precise.</li>
     * <li>No {@code experiments.type} filter, unlike {@link #FIND}'s {@code experiment_candidates}, which
     * excludes {@code 'mini-batch'} / {@code 'mutation'}. That exclusion is presentational; here the only
     * question is whether the worker is still writing anything. GEPA spends much of a run recording
     * {@code 'mini-batch'} evaluations, so filtering them would make a healthy run look silent — and drop
     * the item-level signal with them, since items are reached through this scan's ids.</li>
     * <li>The ceiling reads {@code created_at}, not {@code last_updated_at}: every write to the row
     * refreshes the latter, so a metadata PATCH or an SDK re-upsert would postpone the backstop forever.
     * It is {@code argMax(created_at, last_updated_at)} rather than {@code min(created_at)} because old
     * versions live on in a {@code ReplacingMergeTree} — {@code min} would keep returning the first
     * attempt's start forever, so a run restarted under an existing id would be born past the ceiling.
     * See the upsert path in {@code OptimizationService} for the restart reset this enables. Residual
     * exposure, accepted: for a run created before this branch shipped, the winning version carries a
     * {@code created_at} that an earlier re-upsert re-stamped forward, so its ceiling starts later than
     * the real start. That only ever postpones a reap, and it cannot recur once re-upserts preserve the
     * column.</li>
     * <li>{@code dataset_id} is out of the {@code GROUP BY} even though it is in the sorting key:
     * {@code getOrCreateDataset} resolves by dataset <em>name</em>, so a re-upsert naming a different
     * dataset writes a row the dedup never merges, and grouping by the full key would emit the run twice
     * with independent statuses — letting the reaper ERROR a live run off a stale half.</li>
     * <li>The hard-ceiling branch is guarded by {@code latest_status IN ('initialized', 'running')} and
     * not hoisted to a bare top-level {@code OR}: without the guard every run that merely finished longer
     * ago than the ceiling becomes a candidate and crowds genuine stalls out of the {@code LIMIT}.</li>
     * <li>{@code latest_status} and {@code started_at} come out of ONE {@code argMax} over a tuple, not two
     * over separate columns. {@code last_updated_at} is {@code DateTime64(6)}, so two versions can tie on
     * it, and two independent {@code argMax} calls are then each free to pick a different physical row —
     * combining a status from one version with a start instant from another. One aggregate cannot
     * disagree with itself.</li>
     * <li>Both {@code ORDER BY}s end in {@code id ASC}. Without a unique final key the sort is
     * unstable across ties, so the bounded prefix can differ between passes — and a set of tied healthy
     * rows can keep filling it, get dropped by the activity veto, and starve the stalled runs behind
     * them indefinitely.</li>
     * <li>Both {@code ORDER BY}s put hard-capped runs first and only then sort by {@code latest_updated_at}.
     * Ordering by the timestamp alone looks natural but inverts the priority for exactly the branch that
     * carries the never-stuck-indefinitely guarantee: a metadata PATCH or an SDK re-upsert refreshes
     * {@code last_updated_at}, so a zombie run still receiving writes sorts LAST and — unlike a
     * soft-timeout candidate, which ages into position — never advances. It could then be truncated out
     * of every pass by the bounds below.</li>
     * </ul>
     */
    private static final String FIND_STALLED_STUDIO_OPTIMIZATIONS = """
            WITH candidates AS (
                SELECT
                    workspace_id,
                    id,
                    argMax(tuple(status, created_at), last_updated_at).1 AS latest_status,
                    argMax(tuple(status, created_at), last_updated_at).2 AS started_at,
                    max(last_updated_at) AS latest_updated_at
                FROM optimizations
                WHERE studio_config != ''
                  AND greaterOrEquals(last_updated_at, subtractSeconds(now64(6), :lookback_seconds))
                GROUP BY workspace_id, id
                HAVING (latest_status IN ('initialized', 'running')
                        AND less(started_at, subtractSeconds(now64(6), :running_hard_timeout_seconds)))
                    OR (latest_status = 'initialized'
                        AND less(latest_updated_at, subtractSeconds(now64(6), :initialized_timeout_seconds)))
                    OR (latest_status = 'running'
                        AND less(latest_updated_at, subtractSeconds(now64(6), :running_timeout_seconds)))
                ORDER BY less(started_at, subtractSeconds(now64(6), :running_hard_timeout_seconds)) DESC,
                         latest_updated_at ASC,
                         id ASC
                LIMIT :candidate_limit
            ), candidate_trials AS (
                SELECT
                    workspace_id,
                    id,
                    optimization_id,
                    created_at
                FROM experiments
                WHERE (workspace_id, optimization_id) IN (SELECT workspace_id, toString(id) FROM candidates)
            ), active_optimizations AS (
                SELECT
                    workspace_id,
                    optimization_id
                FROM candidate_trials
                WHERE greaterOrEquals(created_at, subtractSeconds(now64(6), :running_timeout_seconds))
                   OR (workspace_id, id) IN (
                       SELECT workspace_id, experiment_id
                       FROM experiment_items
                       WHERE (workspace_id, experiment_id) IN (SELECT workspace_id, id FROM candidate_trials)
                         AND greaterOrEquals(created_at, subtractSeconds(now64(6), :running_timeout_seconds))
                   )
            )
            SELECT
                id,
                workspace_id,
                latest_status AS status
            FROM candidates
            WHERE less(started_at, subtractSeconds(now64(6), :running_hard_timeout_seconds))
               OR (workspace_id, toString(id)) NOT IN (
                   SELECT workspace_id, optimization_id FROM active_optimizations
               )
            ORDER BY less(started_at, subtractSeconds(now64(6), :running_hard_timeout_seconds)) DESC,
                     latest_updated_at ASC,
                     id ASC
            LIMIT :limit
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * Latest row version by id, no joins — see {@link #getRowById}. Columns are exactly the set
     * {@link #mapRowColumns} reads, so a future heavyweight column cannot silently widen this read.
     */
    private static final String GET_RAW_BY_ID = """
            SELECT
                id,
                name,
                dataset_id,
                project_id,
                objective_name,
                status,
                metadata,
                studio_config,
                error_info,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by
            FROM optimizations
            WHERE workspace_id = :workspace_id
              AND id = :id
            ORDER BY last_updated_at DESC
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * Bare status/timestamp re-read for the reaper — see {@link #getStatusSnapshotById}. The aliases
     * deliberately differ from the source column names: {@code max(last_updated_at) AS last_updated_at}
     * would make the CH 26.3 analyzer resolve the {@code argMax} ordering argument to the alias (an
     * aggregate inside an aggregate, ILLEGAL_AGGREGATION).
     *
     * <p>{@code latest_status} and {@code started_at} must resolve to the same values here as in
     * {@link #FIND_STALLED_STUDIO_OPTIMIZATIONS}, or {@code isPastHardCap} could fire on a run the fleet
     * query selected on a soft timeout — short-circuiting the activity veto and reporting "exceeded the
     * maximum running time" for a run that had not. Two things make that hold, and both are load-bearing:
     * <ul>
     * <li>Reading off the winning version. The fleet query aggregates over versions inside its lookback
     * floor and this one over all of them, but a {@code >=} floor cannot drop the version carrying the
     * maximum {@code last_updated_at}, so both {@code argMax} calls pick the same one. No floor here
     * deliberately — it would buy nothing and could return an empty result for a run whose row aged past
     * the window, which the caller cannot distinguish from "no longer stalled".</li>
     * <li>The same {@code studio_config != ''} predicate. Without it the two aggregate over different
     * version SETS, not just different windows: prod ClickHouse has no read-your-own-writes, so an SDK
     * re-upsert that saw an empty {@code existing} writes a newest version with an empty
     * {@code studio_config}. The fleet query excludes that version and picks an older one; an unfiltered
     * snapshot would pick it, disagreeing on both fields.</li>
     * </ul>
     */
    private static final String GET_STATUS_SNAPSHOT = """
            SELECT
                argMax(tuple(status, created_at), last_updated_at).1 AS latest_status,
                argMax(tuple(status, created_at), last_updated_at).2 AS started_at,
                max(last_updated_at) AS latest_updated_at
            FROM optimizations
            WHERE workspace_id = :workspace_id
              AND id = :id
              AND studio_config != ''
            GROUP BY id
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * Single-run, workspace-scoped mirror of the reaper query's liveness probe: did this optimization
     * write a trial experiment or an experiment item within the window? Used as the pre-update re-read
     * guard (OPIK-7459) — the fleet-wide reaper query and the ERROR update are not atomic, so a trial or
     * item landing in between must veto the transition, exactly like the status re-read vetoes a
     * terminal-status race. Same id-set scoping as the fleet query, one run wide: the {@code trials} CTE
     * sits behind {@code (workspace_id, optimization_id)} — the workspace prefix of the primary key plus
     * the {@code minmax} index on {@code optimization_id} (migration 000069) — and the item probe behind
     * {@code (workspace_id, experiment_id) IN trials}, which is the {@code experiment_items} primary-key
     * prefix. Neither needs a {@code created_at} index; the timestamps are residual predicates. Scoping
     * the items by this run's trials, rather than by the workspace alone, is what keeps a busy workspace's
     * unrelated item traffic out of the scan. As in the fleet query, {@code trials} is inlined twice (its
     * own {@code FROM} plus the nested item {@code IN}), so {@code experiments} is scanned twice per call;
     * the call only happens for candidates that are not already past the hard ceiling.
     */
    private static final String HAS_RECENT_STUDIO_ACTIVITY = """
            WITH trials AS (
                SELECT
                    workspace_id,
                    id,
                    created_at
                FROM experiments
                WHERE workspace_id = :workspace_id
                  AND optimization_id = :optimization_id
            )
            SELECT 1
            FROM trials
            WHERE greaterOrEquals(created_at, subtractSeconds(now64(6), :window_seconds))
               OR (workspace_id, id) IN (
                   SELECT workspace_id, experiment_id
                   FROM experiment_items
                   WHERE (workspace_id, experiment_id) IN (SELECT workspace_id, id FROM trials)
                     AND greaterOrEquals(created_at, subtractSeconds(now64(6), :window_seconds))
               )
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * Every cell must stay a plain bound placeholder: this is a {@code FORMAT Values} insert, and any
     * function expression in a tuple cell ({@code COALESCE}, {@code parseDateTime64BestEffortOrNull},
     * {@code now64}) trips ClickHouse's fast-path parser — the insert still succeeds, but every row
     * silently increments {@code system.errors} codes 26 / 27 / 43 / 70 and writes to pod stderr
     * (OPIK-5694, see {@link ClickHouseDateTimeFormat}). Both {@code DateTime64(9, 'UTC')} timestamps are
     * therefore formatted in Java via {@link ClickHouseDateTimeFormat#formatNanos}, and the column
     * DEFAULT that {@code now64()} used to supply is substituted in Java too — {@code Instant.toString()}
     * would not do, since its {@code T}/{@code Z} form is exactly what the fast path rejects.
     */
    private static final String UPSERT = """
            INSERT INTO optimizations (
                id,
                dataset_id,
                name,
                workspace_id,
                project_id,
                objective_name,
                status,
                metadata,
                studio_config,
                error_info,
                created_by,
                last_updated_by,
                last_updated_at,
                created_at
            )
            VALUES (
                :id,
                :dataset_id,
                :name,
                :workspace_id,
                :project_id,
                :objective_name,
                :status,
                :metadata,
                :studio_config,
                :error_info,
                :created_by,
                :last_updated_by,
                :last_updated_at,
                :created_at
            )
            ;
            """;

    /**
     * The queries in this file carry no SQL comments; the reasoning lives here, keyed by CTE name, as it
     * does in the other DAOs. Keep it that way, and not only for consistency: a line containing only
     * {@code --} inside a query breaks the r2dbc driver's placeholder scanner
     * ({@code ClickHouseParameterizedQuery}), which from that line on stops recognising {@code :params}, so
     * every later one reaches ClickHouse literally and the statement fails with
     * {@code Code: 62 Syntax error ... :workspace_id} - a 500 on every request, from a comment-only edit.
     * {@code -- } with any character after the dashes is safe, but a trailing space is one formatter away
     * from vanishing. Documenting outside the query removes the hazard rather than tiptoeing around it.
     * <p>
     * The tagged-cost CTEs ({@code optimization_tagged_trace_ids} onwards) are deliberately duplicated in
     * {@link #FIND_WITHOUT_EXPERIMENTS} rather than shared through one templated constant: sharing would need a
     * flag every call site must remember to set, and forgetting it silently double-counts trial spend. The two
     * copies are held in step by the {@code findAndGetById__*} tests in
     * {@code OptimizationsResourceTest.GetOptimizerById}, each of which asserts the list and {@code getById}
     * report the same figure - change one copy and they fail.
     * <p>
     * The spans read inside those CTEs dedup on the logical span key {@code (workspace_id, project_id, id)},
     * reading rows in storage sort-key order so the newest write per key wins. {@code parent_span_id} is
     * deliberately excluded from both the sort tuple and the {@code LIMIT 1 BY}, matching every other spans
     * read since OPIK-7750 (#7764). That column is mutable across writes, so ahead of
     * {@code last_updated_at} in the sort it picks the winner by largest parent rather than newest write, and
     * inside the grouping a span re-ingested under a different parent survives as two rows and its cost
     * enters this sum twice. Both halves are pinned by
     * {@code findAndGetById__whenTaggedSpanIsRewrittenUnderAnotherParent__spendIsChargedOnce}.
     * <p>
     * Note the scope in that key: because {@code project_id} is part of it while the aggregation above keys on
     * {@code trace_id} alone, one span id written under two projects survives the dedup twice and is summed
     * twice. That exposure is pre-existing and shared with {@code experiment_durations} here and with
     * {@code ExperimentDAO}; it is deliberately not fixed in this query alone, because deduping only the
     * tagged half would leave the trial half double-charging and make the two halves of one figure disagree
     * about what a canonical per-trace cost is. OPIK-7691 covers it across all sites.
     * <p>
     * No numeric column this query returns may ever be NaN/Inf: the row mapper reads them as
     * {@code BigDecimal}, {@code BigDecimal.valueOf(NaN)} throws, and the clickhouse-r2dbc driver
     * swallows mapper exceptions and silently drops the row — the run then 404s in getById and
     * vanishes from find. The two float sources are guarded where non-finite values can enter:
     * {@code duration_p50} (quantiles over zero finished traces yields NaN — the state a worker
     * killed mid-trial leaves behind, OPIK-7459) and {@code experiment_scores_parsed.value}
     * (JSON-parsed, so unbounded input). Costs are Decimal and cannot be non-finite.
     *
     * <p>The score value is parsed with {@code toFloat64OrNull} rather than {@code CAST(... AS Float64)}:
     * the column holds raw JSON that older or foreign writers may have shaped differently, and a
     * non-numeric value in a <em>named</em> entry made {@code CAST} throw {@code CANNOT_PARSE_TEXT},
     * 500-ing the whole endpoint. {@code toFloat64OrNull} never throws, and
     * {@code isFinite(NULL)} is NULL — falsy in the {@code WHERE} — so unparseable and non-finite entries
     * are dropped alike. The result is re-wrapped in {@code assumeNotNull} so the aggregated map stays
     * {@code Map(String, Float64)}: {@code getScoresAggregation} calls {@code doubleValue()} on each
     * value, and a nullable map value would reintroduce exactly the swallowed-mapper-exception row loss
     * this javadoc is about. The {@code WHERE} already guarantees the value is non-null.
     *
     * <p><b>{@code optimization_tagged_trace_ids}</b> is the candidate scan: every trace that has ever
     * carried one of these optimization ids as a tag. Deliberately a superset, because the authoritative
     * check runs in {@code optimization_tagged_traces} on the latest version of each trace, so a tag
     * removed by a later update stops counting. There is deliberately no {@code created_at} bound: that
     * column is not stable across re-writes of an optimization row, and a reset would silently drop
     * optimizer-internal traces from the total. An optimization with no {@code project_id} predates that
     * column, so its cost stays trial-only.
     *
     * <p>Two things it deliberately does <em>not</em> do, both measured on production rather than reasoned
     * about. It does not {@code DISTINCT}: the CTE is consumed only as an {@code IN} set, which dedups on
     * its own, so the distinct pass was pure overhead (list p50 1338 -> 1145 ms, CPU 2198 -> 1910 ms, peak
     * memory flat). And it no longer bounds {@code project_id} to the optimizations in scope: at
     * production shape that prune is free either way (534 vs 539 ms, same peak memory), so the
     * {@code arrayExists} tag test is left as the single condition. Do not replace that test with
     * {@code hasAny} against a {@code groupArray} of the ids, which measured 22x the latency and 38x the
     * CPU.
     *
     * <p>Note for anyone reasoning about the cost of naming a CTE more than once: ClickHouse evaluates it
     * <em>per reference</em>, and {@code EXPLAIN} cannot show that, because an {@code IN (SELECT ... FROM
     * cte)} set is built eagerly and appears only as {@code trace_id in N-element set}. Counting plan
     * nodes therefore understates the repeats. An isolated probe differing only in reference count went
     * 2.45 M -> 4.90 M rows and 214 -> 398 MiB.
     *
     * <p><b>{@code optimization_tagged_traces}</b> selects traces tagged with the optimization id but
     * linked to no experiment item: the optimizer-internal LLM calls (GEPA reflection, candidate
     * generation) whose spend belongs to the run's total even though it belongs to no trial (OPIK-7521).
     * A trial trace whose {@code experiment_item} row is not visible yet is counted through this branch
     * rather than through {@code experiment_durations}, and moves over once the link lands; either way it
     * is counted exactly once, so the ingestion race cannot double-charge a run.
     *
     * <p>Its experiment-item exclusion is keyed on {@code (trace, owning optimization)}, not on the trace
     * alone. It exists to stop a trial trace that also carries its run's id as a tag from being charged
     * twice, once through {@code experiment_durations} and once here, so it must only fire for the run
     * that owns the trial. Excluding on {@code trace_id} alone would drop a trace tagged with run X
     * because it happens to be a trial of run Y, and since the set of experiments in scope differs
     * between the list (every optimization matching the filters) and {@code getById} (one), the two would
     * report different totals for the same run. That is why the tuple test sits after the
     * {@code ARRAY JOIN}, where {@code tag} is available, rather than as a cheaper {@code trace_id}
     * prefilter in the subquery.
     *
     * <p>{@code project_id} is deliberately not projected out of that CTE: it would split one trace into
     * one row per project it was ever written to, and the cost join keys on {@code trace_id} alone, so
     * that would charge the same spend twice. One scope-dependence is left in the candidate CTE as a
     * result: it prunes to the projects of the optimizations in scope, so a trace tagged with run X but
     * stored in another run's project is found by the list and not by {@code getById}. The optimizer does
     * not produce that shape, and dropping the project bound would turn the candidate scan
     * workspace-wide. A dedicated attribution column (OPIK-7691) is what settles it.
     *
     * <p><b>{@code optimization_tagged_costs}</b> prunes the spans scan on {@code project_id} because
     * {@code trace_id} is only the third primary-key column. That project set comes from
     * {@code optimization_final} rather than from either trace CTE: ClickHouse substitutes CTEs
     * textually, so naming a trace CTE there would re-run its tags scan. Reading {@code optimizations} is
     * cheap by comparison, and a superset of the candidate projects is all a prefix prune needs; the
     * authoritative filter is the {@code trace_id IN} beside it.
     *
     * <p>In {@link #FIND_WITHOUT_EXPERIMENTS} the same two trace CTEs appear without the experiment-item
     * exclusion, which is unnecessary there because that projection is only chosen when nothing in scope
     * has an experiment, and its final {@code ifNull} yields a non-nullable zero when nothing is
     * attributed, matching {@code FIND}, where {@code sum()} over an empty group returns 0 rather than
     * NULL. The {@code ifNull} covers both {@code join_use_nulls} modes.
     */
    private static final String FIND = """
            WITH optimization_final AS (
                SELECT
                    *
                FROM (
                    SELECT *
                    FROM optimizations
                    WHERE workspace_id = :workspace_id
                    <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                    <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                    <if(id)>AND id = :id <endif>
                    <if(project_id)>AND project_id = :project_id <endif>
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, dataset_id, id
                )
                WHERE 1=1
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            ), experiments_final AS (
                SELECT
                    id,
                    optimization_id,
                    experiment_scores,
                    metadata AS experiment_metadata,
                    created_at AS experiment_created_at,
                    type AS experiment_type
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND optimization_id IN (SELECT id FROM optimization_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_final AS (
                SELECT
                    DISTINCT
                        experiment_id,
                        trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = :entity_type
                      AND workspace_id = :workspace_id
                      AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                    UNION ALL
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           author,
                           source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = :entity_type
                      AND workspace_id = :workspace_id
                      AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(fs_avg.name),
                        groupArray(fs_avg.avg_value)
                    ) AS feedback_scores
                FROM (
                    SELECT
                        et.experiment_id,
                        fs.name,
                        avg(fs.value) AS avg_value
                    FROM experiment_items_final as et
                    LEFT JOIN (
                        SELECT
                            name,
                            entity_id AS trace_id,
                            value
                        FROM feedback_scores_final
                    ) fs ON fs.trace_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            ), experiment_scores_parsed AS (
                SELECT
                    e.id AS experiment_id,
                    JSON_VALUE(score, '$.name') AS name,
                    assumeNotNull(toFloat64OrNull(JSON_VALUE(score, '$.value'))) AS value
                FROM experiments_final AS e
                ARRAY JOIN JSONExtractArrayRaw(e.experiment_scores) AS score
                WHERE e.experiment_scores != '' AND e.experiment_scores != '[]'
                  AND length(JSON_VALUE(score, '$.name')) > 0
                  AND isFinite(toFloat64OrNull(JSON_VALUE(score, '$.value')))
            ), experiment_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) AS experiment_scores
                FROM experiment_scores_parsed
                GROUP BY experiment_id
            ), experiment_durations AS (
                SELECT
                    ei.experiment_id,
                    count(DISTINCT ei.trace_id) AS trace_count,
                    if(
                        isFinite(arrayElement(quantiles(0.5)(t.duration), 1)),
                        arrayElement(quantiles(0.5)(t.duration), 1),
                        NULL
                    ) AS duration_p50,
                    sum(s.total_estimated_cost) AS total_estimated_cost
                FROM experiment_items_final ei
                LEFT JOIN (
                    SELECT id, if(isNaN(duration), NULL, duration) AS duration
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    AND project_id IN (SELECT DISTINCT project_id FROM traces WHERE workspace_id = :workspace_id AND id IN (SELECT trace_id FROM experiment_items_final))
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, id
                ) AS t ON ei.trace_id = t.id
                LEFT JOIN (
                    SELECT trace_id, sum(total_estimated_cost) AS total_estimated_cost
                    FROM (
                        SELECT workspace_id, project_id, trace_id, id, total_estimated_cost, last_updated_at
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND trace_id IN (SELECT trace_id FROM experiment_items_final)
                        AND project_id IN (SELECT DISTINCT project_id FROM traces WHERE workspace_id = :workspace_id AND id IN (SELECT trace_id FROM experiment_items_final))
                        ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY workspace_id, project_id, id
                    )
                    GROUP BY trace_id
                ) AS s ON t.id = s.trace_id
                GROUP BY ei.experiment_id
            ), experiment_candidates AS (
                SELECT
                    ef.id AS experiment_id,
                    ef.optimization_id,
                    ef.experiment_created_at,
                    if(
                        JSONHas(ef.experiment_metadata, 'candidate_id') AND JSONExtractString(ef.experiment_metadata, 'candidate_id') != '',
                        JSONExtractString(ef.experiment_metadata, 'candidate_id'),
                        toString(ef.id)
                    ) AS candidate_id
                FROM experiments_final ef
                WHERE ef.experiment_type NOT IN ('mini-batch', 'mutation')
            ), objective_scores_per_experiment AS (
                SELECT
                    optimization_id,
                    experiment_id,
                    /*
                     * Mirrors the run page's precedence (getObjectiveScoreValue): the trace-derived feedback
                     * score wins, the experiment-level score is the fallback.
                     *
                     * toNullable is load-bearing, not decoration. candidate_metrics LEFT JOINs this CTE and
                     * guards the weighted average with isNotNull(objective_score); under join_use_nulls = 0
                     * an unmatched non-Nullable Float64 arrives as 0, which makes that guard always true and
                     * scores every unscored candidate a real 0. Every candidate then ties, the best_* rollups
                     * fall through to their earliest-created tie-break, and "best" collapses onto the
                     * baseline - so the runs list reported the baseline's latency and cost with a 0% delta
                     * while the run page reported the genuine best trial (OPIK-8060). duration_p50 in
                     * experiment_durations is Nullable for the same reason.
                     */
                    toNullable(argMin(objective_score, source_rank)) AS objective_score
                FROM (
                    /*
                     * Dataset runs - Studio runs and every SDK optimizer. The objective is a per-trace
                     * feedback score averaged per experiment, and leaving it out was the OPIK-8060 defect:
                     * these runs populate no experiment_scores at all, so the CTE matched nothing for them.
                     */
                    SELECT
                        ef.optimization_id AS optimization_id,
                        ef.id AS experiment_id,
                        fs.feedback_scores[o.objective_name] AS objective_score,
                        0 AS source_rank
                    FROM experiments_final ef
                    INNER JOIN optimization_final o ON ef.optimization_id = o.id
                    INNER JOIN feedback_scores_agg fs ON ef.id = fs.experiment_id
                    WHERE mapContains(fs.feedback_scores, o.objective_name)
                    UNION ALL
                    -- Test-suite runs, where the objective is scored at the experiment level.
                    SELECT
                        ef.optimization_id AS optimization_id,
                        esp.experiment_id AS experiment_id,
                        esp.value AS objective_score,
                        1 AS source_rank
                    FROM experiment_scores_parsed esp
                    INNER JOIN experiments_final ef ON esp.experiment_id = ef.id
                    INNER JOIN optimization_final o ON ef.optimization_id = o.id
                    WHERE esp.name = o.objective_name
                )
                GROUP BY optimization_id, experiment_id
            ), candidate_metrics AS (
                SELECT
                    ec.optimization_id AS optim_id,
                    ec.candidate_id,
                    sum(ospe.objective_score * ed.trace_count)
                        / nullIf(sumIf(ed.trace_count, isNotNull(ospe.objective_score)), 0)
                        AS weighted_score,
                    sum(ed.duration_p50 / 1000.0 * ed.trace_count)
                        / nullIf(sumIf(ed.trace_count, isNotNull(ed.duration_p50)), 0)
                        AS weighted_duration,
                    sum(ed.total_estimated_cost)
                        / nullIf(sum(ed.trace_count), 0)
                        AS per_trace_cost,
                    sum(ed.trace_count) AS evaluated_count,
                    min(ec.experiment_created_at) AS earliest_created_at
                FROM experiment_candidates ec
                LEFT JOIN objective_scores_per_experiment ospe
                    ON ec.experiment_id = ospe.experiment_id
                    AND ec.optimization_id = ospe.optimization_id
                LEFT JOIN experiment_durations ed ON ec.experiment_id = ed.experiment_id
                GROUP BY ec.optimization_id, ec.candidate_id
            /*
             * How many items a full evaluation covers in this run, taken from the baseline. Deliberately
             * baseline-derived rather than a max() over all candidates, matching the run page's
             * getExpectedItemCount: a candidate groups all of its experiments and sums their counts, so one
             * double-counted candidate would inflate the threshold and prune every genuinely-complete trial.
             */
            ), candidate_expectations AS (
                SELECT
                    optim_id,
                    argMin(evaluated_count, earliest_created_at) AS expected_count
                FROM candidate_metrics
                GROUP BY optim_id
            /*
             * Which candidates may win "best". A candidate that scored fewer items than a full evaluation
             * covers holds a partial average, which is not a result and must not win - the same gate the run
             * page applies (OPIK-7460, isStillEvaluating). Without it the two views disagree even once both
             * read the same scores: an optimizer that evaluates most trials on a subset (GEPA and friends)
             * leaves the runs list crowning a 5-of-12 trial while the run page reports the best fully
             * evaluated one (OPIK-8060).
             *
             * A zero expectation means "unknown" and fails open, and when nothing is complete the whole set
             * stays eligible, so a run never loses its best marker.
             */
            ), candidate_eligibility AS (
                SELECT
                    cm.*,
                    ce.expected_count = 0 OR cm.evaluated_count >= ce.expected_count AS is_complete
                FROM candidate_metrics cm
                INNER JOIN candidate_expectations ce ON cm.optim_id = ce.optim_id
            ), candidate_pools AS (
                SELECT optim_id, max(is_complete) AS has_complete
                FROM candidate_eligibility
                GROUP BY optim_id
            ), candidate_rollup AS (
                SELECT
                    cel.optim_id AS optimization_id,
                    maxIf(weighted_score, in_pool AND isNotNull(weighted_score)) AS best_score,
                    argMinIf(weighted_duration, tuple(-weighted_score, earliest_created_at),
                        in_pool AND isNotNull(weighted_score)) AS best_duration,
                    argMinIf(per_trace_cost, tuple(-weighted_score, earliest_created_at),
                        in_pool AND isNotNull(weighted_score)) AS best_cost,
                    argMin(weighted_score, earliest_created_at) AS baseline_score,
                    argMin(weighted_duration, earliest_created_at) AS baseline_duration,
                    argMin(per_trace_cost, earliest_created_at) AS baseline_cost
                FROM (
                    -- The baseline defines the threshold, so it is complete by construction and the
                    -- baseline_* rollups below stay an unfiltered argMin over every candidate.
                    SELECT cel.*, if(cp.has_complete, cel.is_complete, 1) AS in_pool
                    FROM candidate_eligibility cel
                    INNER JOIN candidate_pools cp ON cel.optim_id = cp.optim_id
                ) AS cel
                GROUP BY cel.optim_id
            ), optimization_tagged_trace_ids AS (
                SELECT id, project_id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND arrayExists(x -> x IN (SELECT toString(id) FROM optimization_final), tags)
            ), optimization_tagged_traces AS (
                SELECT DISTINCT
                    tag AS optimization_id_str,
                    trace_id
                FROM (
                    SELECT id AS trace_id, project_id, tags
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT id FROM optimization_tagged_trace_ids)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, id
                )
                ARRAY JOIN tags AS tag
                WHERE tag IN (SELECT toString(id) FROM optimization_final)
                AND (toString(trace_id), tag) NOT IN (
                    SELECT toString(ei.trace_id), ef.optimization_id
                    FROM experiment_items_final ei
                    INNER JOIN experiments_final ef ON ei.experiment_id = ef.id
                )
            ), optimization_tagged_costs AS (
                SELECT
                    ott.optimization_id_str AS optimization_id_str,
                    sum(s.total_estimated_cost) AS total_estimated_cost
                FROM optimization_tagged_traces ott
                INNER JOIN (
                    SELECT trace_id, sum(total_estimated_cost) AS total_estimated_cost
                    FROM (
                        SELECT workspace_id, project_id, trace_id, id, total_estimated_cost, last_updated_at
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND project_id IN (SELECT project_id FROM optimization_final WHERE notEmpty(project_id))
                        AND trace_id IN (SELECT trace_id FROM optimization_tagged_traces)
                        ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY workspace_id, project_id, id
                    )
                    GROUP BY trace_id
                ) AS s ON s.trace_id = ott.trace_id
                GROUP BY ott.optimization_id_str
            ), optimization_costs AS (
                SELECT
                    optimization_id,
                    sum(cost) AS total_optimization_cost
                FROM (
                    SELECT
                        ef2.optimization_id AS optimization_id,
                        ed2.total_estimated_cost AS cost
                    FROM experiments_final ef2
                    LEFT JOIN experiment_durations ed2 ON ef2.id = ed2.experiment_id
                    UNION ALL
                    SELECT
                        otc.optimization_id_str AS optimization_id,
                        otc.total_estimated_cost AS cost
                    FROM optimization_tagged_costs otc
                )
                GROUP BY optimization_id
            )
            SELECT
                o.*,
                o.id as id,
                COUNT(DISTINCT e.id) FILTER (WHERE e.id != '') AS num_trials,
                maxMap(fs.feedback_scores) AS feedback_scores,
                maxMap(es.experiment_scores) AS experiment_scores,
                any(bc.best_score) AS best_objective_score,
                any(bc.baseline_score) AS baseline_objective_score,
                any(bc.best_duration) AS best_duration,
                any(bc.best_cost) AS best_cost,
                any(bc.baseline_duration) AS baseline_duration,
                any(bc.baseline_cost) AS baseline_cost,
                any(oc.total_optimization_cost) AS total_optimization_cost
            FROM optimization_final AS o
            LEFT JOIN experiments_final AS e ON o.id = e.optimization_id
            LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            LEFT JOIN experiment_scores_agg AS es ON e.id = es.experiment_id
            LEFT JOIN candidate_rollup AS bc ON o.id = bc.optimization_id
            LEFT JOIN optimization_costs AS oc ON o.id = oc.optimization_id
            GROUP BY o.*
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            ;
            """;

    /**
     * Does any optimization in scope have an experiment? Deliberately applies only the direct column filters
     * and omits the narrowing ones ({@code name}, {@code dataset_deleted}, {@code studio_only}, {@code filters}),
     * so the optimization set considered here is a superset of {@code optimization_final}. That makes a negative
     * answer conservative: if this finds nothing, the narrowed set has nothing either.
     */
    private static final String HAS_EXPERIMENTS_FOR_DIRECT_FILTERS = """
            SELECT 1 AS has_experiments
            FROM experiments
            WHERE workspace_id = :workspace_id
            AND optimization_id IN (
                SELECT id
                FROM optimizations
                WHERE workspace_id = :workspace_id
                <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                <if(project_id)>AND project_id = :project_id <endif>
            )
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * The check that selects this projection runs as its own statement, so an experiment inserted between the
     * two reads leaves the aggregates at these empty-input values for that one response. Reads in this system are
     * already eventually consistent through replica lag, so this sits inside existing behaviour and self-corrects
     * on the next request rather than needing a shared read boundary.
     * <p>
     * The {@link #FIND} projection for the case where no optimization in scope has an experiment. Every
     * aggregate in {@link #FIND} except {@code total_optimization_cost} is derived from
     * {@code experiments_final}, so with no experiments they all collapse to their empty-input values and the
     * whole CTE pipeline reads nothing useful. The literals below reproduce those values and their exact
     * declared types.
     * <p>
     * {@code total_optimization_cost} is the exception and must be computed for real (OPIK-7521): it also sums
     * optimizer-internal traces attributed by tag, which exist without any experiment. A run that died during
     * candidate generation has zero experiments and non-zero spend, and hardcoding a zero here would make the
     * runs list disagree with the run page - {@link #getById(UUID)} always takes the {@link #FIND} path. The
     * three CTEs below are {@link #FIND}'s tagged-cost pipeline minus the experiment-item exclusion, which is
     * unnecessary here because this projection is only chosen when no experiment exists to link a trace to.
     * Keep them in step with {@link #FIND} - see that field's note on why they are duplicated and which test
     * fails when they drift.
     * They read nothing when no optimization in scope carries a {@code project_id}, which is every row written
     * before that column existed.
     */
    private static final String FIND_WITHOUT_EXPERIMENTS = """
            WITH optimization_final AS (
                SELECT
                    *
                FROM (
                    SELECT *
                    FROM optimizations
                    WHERE workspace_id = :workspace_id
                    <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                    <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                    <if(id)>AND id = :id <endif>
                    <if(project_id)>AND project_id = :project_id <endif>
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, dataset_id, id
                )
                WHERE 1=1
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            ), optimization_tagged_trace_ids AS (
                SELECT id, project_id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND arrayExists(x -> x IN (SELECT toString(id) FROM optimization_final), tags)
            ), optimization_tagged_traces AS (
                SELECT DISTINCT
                    tag AS optimization_id_str,
                    trace_id
                FROM (
                    SELECT id AS trace_id, project_id, tags
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT id FROM optimization_tagged_trace_ids)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, id
                )
                ARRAY JOIN tags AS tag
                WHERE tag IN (SELECT toString(id) FROM optimization_final)
            ), optimization_costs AS (
                SELECT
                    ott.optimization_id_str AS optimization_id,
                    sum(s.total_estimated_cost) AS total_optimization_cost
                FROM optimization_tagged_traces ott
                INNER JOIN (
                    SELECT trace_id, sum(total_estimated_cost) AS total_estimated_cost
                    FROM (
                        SELECT workspace_id, project_id, trace_id, id, total_estimated_cost, last_updated_at
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND project_id IN (SELECT project_id FROM optimization_final WHERE notEmpty(project_id))
                        AND trace_id IN (SELECT trace_id FROM optimization_tagged_traces)
                        ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY workspace_id, project_id, id
                    )
                    GROUP BY trace_id
                ) AS s ON s.trace_id = ott.trace_id
                GROUP BY ott.optimization_id_str
            )
            SELECT
                o.*,
                o.id as id,
                toUInt64(0) AS num_trials,
                CAST(map(), 'Map(String, Float64)') AS feedback_scores,
                CAST(map(), 'Map(String, Float64)') AS experiment_scores,
                CAST(NULL, 'Nullable(Float64)') AS best_objective_score,
                CAST(NULL, 'Nullable(Float64)') AS baseline_objective_score,
                CAST(NULL, 'Nullable(Float64)') AS best_duration,
                CAST(NULL, 'Nullable(Decimal(38, 12))') AS best_cost,
                CAST(NULL, 'Nullable(Float64)') AS baseline_duration,
                CAST(NULL, 'Nullable(Decimal(38, 12))') AS baseline_cost,
                CAST(ifNull(oc.total_optimization_cost, toDecimal128(0, 12)), 'Decimal(38, 12)') AS total_optimization_cost
            FROM optimization_final AS o
            LEFT JOIN optimization_costs AS oc ON o.id = oc.optimization_id
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String COUNT = """
            SELECT
                COUNT(id) as count
            FROM (
                SELECT
                    id
                FROM (
                    SELECT *
                    FROM optimizations
                    WHERE workspace_id = :workspace_id
                    <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                    <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                    <if(id)>AND id = :id <endif>
                    <if(project_id)>AND project_id = :project_id <endif>
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, dataset_id, id
                )
                WHERE 1=1
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            )
            ;
            """;

    private static final String FIND_OPTIMIZATIONS_DATASET_IDS = """
            SELECT
                distinct dataset_id
            FROM optimizations
            WHERE workspace_id = :workspace_id
            <if(experiment_ids)> AND id IN :experiment_ids <endif>
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            ;
            """;

    private static final String DELETE_BY_IDS = """
            DELETE FROM optimizations
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String UPDATE_BY_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, project_id, objective_name, status, metadata, created_at, created_by, last_updated_by, studio_config, error_info
            )
            SELECT
                id,
                dataset_id,
                <if(name)> :name <else> name <endif> as name,
                workspace_id,
                project_id,
                objective_name,
                <if(status)> :status <else> status <endif> as status,
                <if(metadata)> :metadata <else> metadata <endif> as metadata,
                created_at,
                created_by,
                :user_name as last_updated_by,
                studio_config,
                <if(clear_error_info)> '' <elseif(error_info)> :error_info <else> error_info <endif> as error_info
            FROM optimizations
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SET_DATASET_DELETED_TO_TRUE_BY_DATASET_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, project_id, objective_name, status, metadata, created_at, created_by, last_updated_at, last_updated_by, dataset_deleted, studio_config, error_info
            )
            SELECT
                id,
                dataset_id,
                name as name,
                workspace_id,
                project_id,
                objective_name,
                status as status,
                metadata,
                created_at,
                created_by,
                last_updated_at,
                last_updated_by,
                true as dataset_deleted,
                studio_config,
                error_info
            FROM optimizations
            WHERE workspace_id = :workspace_id
            AND dataset_id IN :dataset_ids
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 by id
            ;
            """;

    private static final String FIND_MOST_RECENT_CREATED_OPTIMIZATION_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	max(created_at) as created_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    created_at
                FROM optimizations
                WHERE dataset_id IN :dataset_ids
            	AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY dataset_id
            ;
            """;

    private static final String FIND_OPTIMIZATION_SUMMARY_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	count(distinct id) as optimization_count,
            	max(last_updated_at) as most_recent_optimization_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    last_updated_at
                FROM optimizations
                WHERE dataset_id IN :dataset_ids
            	AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY dataset_id
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

    @Override
    public Mono<Void> upsert(@NonNull Optimization optimization) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> upsert(optimization, connection))
                .then();
    }

    @Override
    public Mono<Optimization> getById(@NonNull UUID id) {
        var template = TemplateUtils.newST(FIND);
        template.add("id", id.toString());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(
                        template.render(), connection,
                        statement -> statement.bind("id", id)))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    @Override
    public Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = TemplateUtils.newST(FIND_OPTIMIZATIONS_DATASET_IDS);
                    template.add("experiment_ids", ids);
                    var statement = connection.createStatement(template.render());
                    statement.bind("experiment_ids", ids);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(this::mapDatasetId)
                .collectList();
    }

    @Override
    public Mono<Long> delete(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("Deleting optimizations by ids, size '{}'", ids.size());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Deleted optimizations by ids, size '{}'", ids.size());
                    }
                });
    }

    @Override
    public Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(FIND_MOST_RECENT_CREATED_OPTIMIZATION_BY_DATASET_IDS);
                    statement.bind("dataset_ids", datasetIds);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new DatasetLastOptimizationCreated(
                        row.get("dataset_id", UUID.class),
                        row.get("created_at", Instant.class))));
    }

    @Override
    public Mono<Long> update(@NonNull UUID id, @NonNull OptimizationUpdate update, boolean clearErrorInfo) {
        log.info("Update optimization by id '{}'", id);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> update(id, update, clearErrorInfo, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Updated optimization by id '{}'", id);
                    }
                });
    }

    @Override
    public Mono<Long> updateDatasetDeleted(@NonNull Set<UUID> datasetIds) {
        log.info("Set to true optimization dataset_deleted for datasetIds '{}'", datasetIds);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> updateDatasetDeleted(datasetIds, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Set to true optimization dataset_deleted is done for datasetIds '{}'", datasetIds);
                    }
                });
    }

    @Override
    public Mono<Optimization.OptimizationPage> find(int page, int size,
            @NonNull OptimizationSearchCriteria searchCriteria) {
        return getCount(searchCriteria)
                .filter(totalCount -> totalCount > 0)
                .flatMap(totalCount -> hasExperimentsForDirectFilters(searchCriteria)
                        .flatMap(hasExperiments -> find(page, size, totalCount, searchCriteria, hasExperiments)))
                .defaultIfEmpty(Optimization.OptimizationPage.empty(page, List.of()));
    }

    private Mono<Boolean> hasExperimentsForDirectFilters(OptimizationSearchCriteria searchCriteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = FilterUtils.getSTWithLogComment(HAS_EXPERIMENTS_FOR_DIRECT_FILTERS,
                            "has_optimization_experiments", workspaceId, userName, "");

                    bindScopeTemplateParams(template, searchCriteria);

                    Statement statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId);

                    bindScopeQueryParams(searchCriteria, statement);

                    return Flux.from(statement.execute());
                }))
                .flatMap(result -> result.map(row -> row.get("has_experiments", Integer.class)))
                .hasElements();
    }

    @Override
    public Flux<OptimizationSummary> findOptimizationSummaryByDatasetIds(@NonNull Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Flux.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(FIND_OPTIMIZATION_SUMMARY_BY_DATASET_IDS);

                    statement.bind("dataset_ids", datasetIds);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new OptimizationSummary(
                        row.get("dataset_id", UUID.class),
                        row.get("optimization_count", Long.class),
                        row.get("most_recent_optimization_at", Instant.class))));
    }

    private Mono<Long> getCount(OptimizationSearchCriteria searchCriteria) {
        var template = TemplateUtils.newST(COUNT);

        bindTemplateParams(template, searchCriteria);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(template.render());

                    bindQueryParams(searchCriteria, statement, false);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map(row -> row.get("count", Long.class)))
                .reduce(Long::sum);
    }

    private Mono<Optimization.OptimizationPage> find(int page, int size, long total,
            OptimizationSearchCriteria searchCriteria, boolean hasExperiments) {
        var offset = (page - 1) * size;

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = hasExperiments
                            ? TemplateUtils.newST(FIND)
                            : FilterUtils.getSTWithLogComment(FIND_WITHOUT_EXPERIMENTS,
                                    "find_optimizations_without_experiments", workspaceId, userName, "");

                    bindTemplateParams(template, searchCriteria);

                    template.add("limit", size);
                    template.add("offset", offset);

                    Statement statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("limit", size)
                            .bind("offset", offset);

                    // entity_type is only declared by FIND; the fast path omits the feedback-score CTEs that use it,
                    // and binding a parameter the rendered query does not contain fails the statement.
                    bindQueryParams(searchCriteria, statement, hasExperiments);

                    return Flux.from(statement.execute());
                }))
                .flatMap(this::mapToDto)
                .collectList()
                .map(optimizations -> new Optimization.OptimizationPage(page, optimizations.size(), total,
                        optimizations, List.of()));
    }

    /**
     * The subset of criteria that select which optimizations are in scope by identity rather than by attribute.
     * Shared with {@link #HAS_EXPERIMENTS_FOR_DIRECT_FILTERS}, which declares only these placeholders - binding a
     * parameter the rendered query does not contain fails the statement, so the two must stay in step.
     */
    private void bindScopeTemplateParams(ST template, OptimizationSearchCriteria searchCriteria) {

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> template.add("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.datasetIds())
                .filter(ids -> !ids.isEmpty())
                .ifPresent(datasetIds -> template.add("dataset_ids", datasetIds));

        Optional.ofNullable(searchCriteria.projectId())
                .ifPresent(projectId -> template.add("project_id", projectId));
    }

    private void bindTemplateParams(ST template, OptimizationSearchCriteria searchCriteria) {

        bindScopeTemplateParams(template, searchCriteria);

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> template.add("dataset_deleted", datasetDeleted.toString()));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> template.add("name", name));

        Optional.ofNullable(searchCriteria.studioOnly())
                .filter(Boolean.TRUE::equals)
                .ifPresent(studioOnly -> template.add("studio_only", "true"));

        Optional.ofNullable(searchCriteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.OPTIMIZATION))
                .ifPresent(optimizationFilters -> template.add("filters", optimizationFilters));

        Optional.ofNullable(searchCriteria.entityType())
                .ifPresent(entityType -> template.add("entity_type", EntityType.TRACE.getType()));
    }

    private void bindScopeQueryParams(OptimizationSearchCriteria searchCriteria, Statement statement) {

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.datasetIds())
                .filter(ids -> !ids.isEmpty())
                .ifPresent(datasetIds -> statement.bind("dataset_ids", datasetIds));

        Optional.ofNullable(searchCriteria.projectId())
                .ifPresent(projectId -> statement.bind("project_id", projectId.toString()));
    }

    private void bindQueryParams(OptimizationSearchCriteria searchCriteria, Statement statement, boolean isFindQuery) {

        bindScopeQueryParams(searchCriteria, statement);

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> statement.bind("dataset_deleted", datasetDeleted));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(searchCriteria.filters())
                .ifPresent(filters -> filterQueryBuilder.bind(statement, filters, FilterStrategy.OPTIMIZATION));

        if (isFindQuery) {
            Optional.ofNullable(searchCriteria.entityType())
                    .ifPresent(entityType -> statement.bind("entity_type", EntityType.TRACE.getType()));
        }
    }

    private Publisher<? extends Result> upsert(Optimization optimization, Connection connection) {

        var statement = connection.createStatement(UPSERT)
                .bind("id", optimization.id())
                .bind("dataset_id", optimization.datasetId())
                .bind("name", optimization.name())
                .bind("project_id", optimization.projectId() != null ? optimization.projectId().toString() : "")
                .bind("objective_name", optimization.objectiveName())
                .bind("status", optimization.status().getValue())
                .bind("metadata", getStringOrDefault(optimization.metadata()))
                .bind("error_info",
                        optimization.errorInfo() != null ? JsonUtils.writeValueAsString(optimization.errorInfo()) : "");

        if (optimization.studioConfig() != null) {
            try {
                String studioConfigJson = JsonUtils.writeValueAsString(optimization.studioConfig());
                statement.bind("studio_config", studioConfigJson);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to serialize studio_config for optimization: '%s'".formatted(optimization.id()), e);
            }
        } else {
            statement.bindNull("studio_config", String.class);
        }

        // Both timestamps are bound as canonical ClickHouse literals, with the column DEFAULT that
        // now64() used to supply substituted here — see the UPSERT javadoc (OPIK-5694). The two columns
        // have DIFFERENT precision and must be formatted accordingly: migration 000026 narrowed
        // last_updated_at to DateTime64(6) while created_at stayed at (9). Writing a 9-digit literal into
        // the (6) column re-trips the FORMAT Values parse path that javadoc exists to avoid; SpanDAO's
        // last_updated_at binding is the precedent for the micros form.
        statement.bind("last_updated_at",
                ClickHouseDateTimeFormat.formatMicros(
                        optimization.lastUpdatedAt() != null ? optimization.lastUpdatedAt() : Instant.now()));

        // created_at used to be absent from the INSERT, so the column DEFAULT re-stamped it on every
        // re-upsert: a run's creation time drifted forward, and the stalled-run reaper's hard ceiling
        // (which is measured from it) could be postponed indefinitely by writes that are not status
        // changes. The service carries the existing row's value in on re-upsert (OPIK-7459).
        statement.bind("created_at",
                ClickHouseDateTimeFormat.formatNanos(
                        optimization.createdAt() != null ? optimization.createdAt() : Instant.now()));

        return makeFluxContextAware((userName, workspaceId) -> {
            log.info("Inserting optimization with id '{}', datasetId '{}', datasetName '{}', workspaceId '{}'",
                    optimization.id(), optimization.datasetId(), optimization.datasetName(), workspaceId);
            statement.bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        });
    }

    private Publisher<? extends Result> get(String query, Connection connection, Function<Statement, Statement> bind) {
        var statement = connection.createStatement(query)
                .bind("entity_type", EntityType.TRACE.getType());
        return makeFluxContextAware(bindWorkspaceIdToFlux(bind.apply(statement)));
    }

    private Publisher<Optimization> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> mapRowColumns(row).toBuilder()
                .feedbackScores(getFeedbackScores(row, "feedback_scores"))
                .experimentScores(getFeedbackScores(row, "experiment_scores"))
                .numTrials(row.get("num_trials", Long.class))
                .baselineObjectiveScore(getFiniteBigDecimal(row, "baseline_objective_score"))
                .bestObjectiveScore(getFiniteBigDecimal(row, "best_objective_score"))
                .baselineDuration(getFiniteBigDecimal(row, "baseline_duration"))
                .bestDuration(getFiniteBigDecimal(row, "best_duration"))
                .baselineCost(row.get("baseline_cost", BigDecimal.class))
                .bestCost(row.get("best_cost", BigDecimal.class))
                .totalOptimizationCost(row.get("total_optimization_cost", BigDecimal.class))
                .build());
    }

    /**
     * Reads a {@code Nullable(Float64)} aggregate as a {@code BigDecimal}, mapping any non-finite value to
     * {@code null} rather than letting it reach the driver's {@code BigDecimal} conversion.
     *
     * <p>This is the mapper-side half of the same defence {@link #FIND} applies in SQL, and it is here
     * because the mapper is where the failure actually happens and how badly it fails is out of all
     * proportion to the cause: {@code BigDecimal.valueOf(NaN)} throws {@code NumberFormatException},
     * clickhouse-r2dbc rethrows it as a misleading {@code NoSuchElementException}, and
     * {@code ClickHouseResult.map} catches every mapper exception, logs it, and <em>silently drops the
     * row</em> — so one non-finite cell 404s a whole run and erases it from the paginated list
     * (OPIK-7459). {@code FIND} guards the two places non-finite values can <em>enter</em>
     * ({@code duration_p50}, the JSON-parsed score), but the columns read here are <em>derived</em> from
     * those by the divisions and sums in {@code candidate_metrics}, so any future arithmetic added there
     * that can overflow to +/-Inf would reopen the same class of bug in the same invisible way. Guarding at
     * the boundary makes the row-loss mode unreachable regardless of what the query does upstream.
     *
     * <p>The finite path deliberately re-reads through {@code BigDecimal.class} instead of converting the
     * {@code Double} itself, so the value's scale and representation stay byte-identical to what the driver
     * produced before this guard existed. Both reads hit an already-decoded in-memory cell. Costs are
     * {@code Decimal} and cannot be non-finite, so they keep the direct read.
     */
    private static BigDecimal getFiniteBigDecimal(Row row, String column) {
        Double value = row.get(column, Double.class);
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return row.get(column, BigDecimal.class);
    }

    /** Maps the plain {@code optimizations} table columns — everything except FIND's computed aggregates. */
    private Optimization mapRowColumns(Row row) {
        OptimizationStudioConfig studioConfig = null;
        String studioConfigJson = row.get("studio_config", String.class);
        if (StringUtils.isNotEmpty(studioConfigJson)) {
            try {
                studioConfig = JsonUtils.readValue(studioConfigJson, OptimizationStudioConfig.class);
            } catch (UncheckedIOException e) {
                log.error("Failed to deserialize studio_config for optimization: '{}'",
                        row.get("id", UUID.class), e);
            }
        }

        ErrorInfo errorInfo = null;
        String errorInfoJson = row.get("error_info", String.class);
        if (StringUtils.isNotBlank(errorInfoJson)) {
            try {
                errorInfo = JsonUtils.readValue(errorInfoJson, ERROR_INFO_TYPE);
            } catch (UncheckedIOException e) {
                log.error("Failed to deserialize error_info for optimization: '{}'",
                        row.get("id", UUID.class), e);
            }
        }

        String projectIdStr = row.get("project_id", String.class);
        UUID projectId = StringUtils.isNotBlank(projectIdStr) ? UUID.fromString(projectIdStr) : null;

        return Optimization.builder()
                .id(row.get("id", UUID.class))
                .name(row.get("name", String.class))
                .datasetId(row.get("dataset_id", UUID.class))
                .projectId(projectId)
                .objectiveName(row.get("objective_name", String.class))
                .status(OptimizationStatus.fromString(row.get("status", String.class)))
                .metadata(getJsonNodeOrDefault(row.get("metadata", String.class)))
                .studioConfig(studioConfig)
                .errorInfo(errorInfo)
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }

    private Publisher<DatasetEventInfoHolder> mapDatasetId(Result result) {
        return result.map((row, rowMetadata) -> new DatasetEventInfoHolder(row.get("dataset_id", UUID.class), null));
    }

    private Flux<? extends Result> delete(Set<UUID> ids, Connection connection) {

        var statement = connection.createStatement(DELETE_BY_IDS)
                .bind("ids", ids.toArray(UUID[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Flux<? extends Result> update(UUID id, OptimizationUpdate update, boolean clearErrorInfo,
            Connection connection) {
        var template = buildUpdateTemplate(update, clearErrorInfo);

        var statement = createUpdateStatement(id, update, clearErrorInfo, connection, template.render());

        return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement));
    }

    private Flux<? extends Result> updateDatasetDeleted(Set<UUID> datasetIds, Connection connection) {
        Statement statement = connection.createStatement(SET_DATASET_DELETED_TO_TRUE_BY_DATASET_ID);
        statement.bind("dataset_ids", datasetIds);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private ST buildUpdateTemplate(OptimizationUpdate update, boolean clearErrorInfo) {
        var template = TemplateUtils.newST(UPDATE_BY_ID);

        Optional.ofNullable(update.name())
                .ifPresent(name -> template.add("name", name));

        Optional.ofNullable(update.status())
                .ifPresent(status -> template.add("status", status.getValue()));

        if (clearErrorInfo) {
            template.add("clear_error_info", true);
        } else {
            Optional.ofNullable(update.errorInfo())
                    .ifPresent(errorInfo -> template.add("error_info", errorInfo));
        }

        // When absent, the SELECT carries the existing metadata column forward untouched. When present,
        // the update.metadata() is already the FULL merged object (see OptimizationService.update) — a
        // new ReplacingMergeTree version must carry the complete metadata, never a delta.
        Optional.ofNullable(update.metadata())
                .ifPresent(metadata -> template.add("metadata", true));

        return template;
    }

    private Statement createUpdateStatement(UUID id, OptimizationUpdate update, boolean clearErrorInfo,
            Connection connection, String sql) {
        Statement statement = connection.createStatement(sql);

        Optional.ofNullable(update.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(update.status())
                .ifPresent(status -> statement.bind("status", status.getValue()));

        if (!clearErrorInfo) {
            Optional.ofNullable(update.errorInfo())
                    .ifPresent(errorInfo -> statement.bind("error_info", JsonUtils.writeValueAsString(errorInfo)));
        }

        Optional.ofNullable(update.metadata())
                .ifPresent(metadata -> statement.bind("metadata", getStringOrDefault(metadata)));

        statement.bind("id", id);

        return statement;
    }

    @Override
    public Flux<StalledOptimization> findStalledStudioOptimizations(@NonNull Duration initializedTimeout,
            @NonNull Duration runningTimeout, @NonNull Duration runningHardTimeout, @NonNull Duration lookbackMargin,
            int limit, int candidateScanFactor) {
        // How far back the query scans (the last_updated_at FLOOR that lets the minmax skip index prune
        // granules): the largest timeout plus the configured reaper-downtime margin, so in normal operation
        // the floor is purely a scan bound and never a coverage gap — a run's last status change is only
        // older than this if the reaper was down longer than the margin, in which case that run is not
        // reaped (documented tradeoff, review: thiagohora).
        long lookbackSeconds = Math.max(Math.max(initializedTimeout.toSeconds(), runningTimeout.toSeconds()),
                runningHardTimeout.toSeconds()) + lookbackMargin.toSeconds();
        // Bound on the CTE the two liveness probes fan out from. Without it `candidates` is "every
        // non-terminal studio run whose row has not changed in runningTimeout" — and because
        // last_updated_at only advances on a status change, that includes every HEALTHY in-flight run
        // older than the timeout, so the probes' cost would scale with fleet size rather than with
        // configuration. Deliberately a multiple of the batch size rather than the batch size itself:
        // the ordering puts the stalest first, and a healthy long run sorts alongside a dead one (that
        // is the premise of this whole feature), so a bound of exactly `limit` could let live runs
        // crowd dead ones out of every pass. With the multiplier, starving a dead run needs that many
        // simultaneously-alive stale runs ahead of it, and alive runs eventually turn terminal and drop
        // out of the CTE entirely. The multiplier is operator-tunable
        // (OPTIMIZATION_STALLED_REAPER_CANDIDATE_SCAN_FACTOR) so a deployment can trade probe cost against
        // the query's reach without a release (review: thiagohora).
        int candidateLimit = limit * candidateScanFactor;
        var details = "initializedTimeoutSeconds=%d, runningTimeoutSeconds=%d, runningHardTimeoutSeconds=%d, lookbackSeconds=%d, limit=%d, candidateLimit=%d"
                .formatted(initializedTimeout.toSeconds(), runningTimeout.toSeconds(),
                        runningHardTimeout.toSeconds(), lookbackSeconds, limit, candidateLimit);
        var template = FilterUtils.getSTWithLogComment(FIND_STALLED_STUDIO_OPTIMIZATIONS,
                "find_stalled_studio_optimizations", "", "", details);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("initialized_timeout_seconds", initializedTimeout.toSeconds())
                            .bind("running_timeout_seconds", runningTimeout.toSeconds())
                            .bind("running_hard_timeout_seconds", runningHardTimeout.toSeconds())
                            .bind("lookback_seconds", lookbackSeconds)
                            .bind("candidate_limit", candidateLimit)
                            .bind("limit", limit);
                    return Flux.from(statement.execute());
                })
                .flatMap(result -> result.map((row, metadata) -> StalledOptimization.builder()
                        .id(row.get("id", UUID.class))
                        .workspaceId(row.get("workspace_id", String.class))
                        .status(OptimizationStatus.fromString(row.get("status", String.class)))
                        .build()));
    }

    @Override
    public Mono<OptimizationStatusSnapshot> getStatusSnapshotById(@NonNull UUID id) {
        var template = FilterUtils.getSTWithLogComment(GET_STATUS_SNAPSHOT,
                "get_optimization_status_snapshot", "", "", "id=%s".formatted(id));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("id", id);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, metadata) -> OptimizationStatusSnapshot.builder()
                        .status(OptimizationStatus.fromString(row.get("latest_status", String.class)))
                        .lastUpdatedAt(row.get("latest_updated_at", Instant.class))
                        .startedAt(row.get("started_at", Instant.class))
                        .build()))
                .singleOrEmpty();
    }

    @Override
    public Mono<Optimization> getRowById(@NonNull UUID id) {
        var template = FilterUtils.getSTWithLogComment(GET_RAW_BY_ID,
                "get_optimization_row_by_id", "", "", "id=%s".formatted(id));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("id", id);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, metadata) -> mapRowColumns(row)))
                .singleOrEmpty();
    }

    @Override
    public Mono<Boolean> hasRecentStudioActivity(@NonNull UUID optimizationId, @NonNull Duration window) {
        var details = "optimizationId=%s, windowSeconds=%d".formatted(optimizationId, window.toSeconds());
        var template = FilterUtils.getSTWithLogComment(HAS_RECENT_STUDIO_ACTIVITY,
                "has_recent_studio_activity", "", "", details);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("optimization_id", optimizationId)
                            .bind("window_seconds", window.toSeconds());
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> Flux.from(result.map((row, metadata) -> true)))
                .hasElements();
    }
}
