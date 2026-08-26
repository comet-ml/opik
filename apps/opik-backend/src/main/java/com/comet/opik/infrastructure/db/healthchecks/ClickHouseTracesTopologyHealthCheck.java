package com.comet.opik.infrastructure.db.healthchecks;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.Records;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.HashMap;
import java.util.Map;

import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.CLICKHOUSE_HEALTH_CHECK_TIMEOUT;

/**
 * Asserts that {@code databaseAnalyticsDataModel.tracesDistributedWrapEnabled} agrees with the actual {@code traces}
 * topology in ClickHouse, so a flag↔topology mismatch is caught at readiness instead of as errors on the first
 * mutation.
 *
 * <p>Post-cutover {@code traces} is a {@code Distributed} table, which supports {@code SELECT}/{@code INSERT} but not
 * mutations, so {@code TraceDAO} routes deletes at {@code traces_local} only while the flag is on (OPIK-7455). The two
 * mismatch directions both break trace deletion, each in its own way, and neither is visible until a delete runs:
 * flag off over a wrapped {@code traces} fails with {@code BAD_ARGUMENTS} (36) / {@code NOT_IMPLEMENTED} (48), flag on
 * over an unwrapped one with {@code UNKNOWN_TABLE} (60). Since the answer depends on each environment's config plus its
 * live database, this is inherently a runtime check and cannot be a CI guard.
 *
 * <p>One {@code system.tables} lookup covers both tables and both directions:
 * <ul>
 *     <li>flag on → {@code traces} must be {@code Distributed} <b>and</b> {@code traces_local} must exist;</li>
 *     <li>flag off → {@code traces} must be an engine of the {@code MergeTree} family — the suffix match
 *     accepts {@code MergeTree}, {@code ReplicatedMergeTree} and ClickHouse Cloud's {@code SharedMergeTree}
 *     variants alike — i.e. anything that takes mutations directly, and not {@code Distributed}.</li>
 * </ul>
 *
 * <p>The flag stays the source of truth: a mismatch is reported, never repaired, and nothing here influences routing.
 * The probe is not toggle-gated — flag off over a {@code MergeTree} {@code traces} is the default everywhere
 * (OSS Docker, self-hosted, pre-cutover SaaS), so the assertion holds for every install as shipped and only a genuine
 * misconfiguration trips it. Being a readiness check, it is re-evaluated continuously and clears itself once the
 * operator brings the two sides back in step, which is what makes the lockstep cutover transition (apply the wrap, flip
 * the flag, restart) tolerable rather than a deadlock.
 */
@Singleton
public class ClickHouseTracesTopologyHealthCheck extends AbstractClickHouseHealthCheck {

    private static final String NAME = "clickhouse-traces-topology";

    private static final String TRACES_TABLE = "traces";
    private static final String TRACES_LOCAL_TABLE = "traces_local";

    private static final String DISTRIBUTED_ENGINE = "Distributed";
    private static final String MERGE_TREE_ENGINE_SUFFIX = "MergeTree";

    private static final String FLAG = "databaseAnalyticsDataModel.tracesDistributedWrapEnabled";

    private static final String NAME_COLUMN = "name";
    private static final String ENGINE_COLUMN = "engine";

    /**
     * Both tables in one lookup, declared once as a literal text block — SQL is never assembled with Java string
     * operations, so the table names are spelled out here rather than interpolated from the constants above (which
     * remain the single source for the map lookups and the messages). {@code ClickHouseTracesTopologyHealthCheckTest}
     * stubs this exact query text, so the two cannot drift apart unnoticed. {@code currentDatabase()} resolves to the
     * database the v2 client was built with ({@code Client.Builder#setDefaultDatabase}), so the probe follows
     * {@code databaseAnalytics.databaseName} without naming it in SQL at all.
     *
     * <p><b>The scope is the node that answers, deliberately.</b> {@code system.tables} is node-local and this probe
     * does not fan out with {@code clusterAllReplicas}, unlike the cutover scripts' settle and finalize gates. The
     * backend reaches ClickHouse through the load-balanced CHI service ({@code ANALYTICS_DB_HOST}), so the probe reads
     * the same population its own {@code DELETE}s are routed to. Three reasons not to widen it:
     * <ul>
     *     <li>{@code clusterAllReplicas} needs {@code REMOTE} and {@code CLUSTER} grants, which the application user
     *     is not guaranteed to hold — the runbook's privileges table grants them explicitly to the cutover's dedicated
     *     least-privilege user, not to the app. A critical check that is deliberately <em>not</em> toggle-gated must
     *     never fail on an install as shipped, and a missing grant would fail it on every install at once.</li>
     *     <li>A single unreachable replica makes the fan-out throw, which would pull <em>every</em> backend pod out of
     *     rotation over a condition that does not break trace deletes at all — strictly worse availability than the
     *     mismatch being guarded.</li>
     *     <li>On shared-catalog deployments (ClickHouse Cloud / {@code SharedMergeTree}) every replica reads one
     *     catalog, so node-local already <em>is</em> cluster-wide there.</li>
     * </ul>
     *
     * <p>The accepted limit that follows: a divergence confined to some replicas — an {@code ON CLUSTER} DDL still
     * propagating, or one that failed on a host — is seen only by the probes that happen to land there, so it degrades
     * into sporadic unhealthy reports instead of taking the fleet dark. That is the right trade for the transient
     * cross-node skew the wrap is expected to produce inside its maintenance window, and the cluster-wide question
     * ("has this DDL reached every replica?") is already answered where it belongs: the fail-loud, one-shot,
     * operator-run {@code clusterAllReplicas} gates in {@code exchange_and_wrap.sh} and {@code finalize.sh}. The
     * self-host troubleshooting page carries that query for an operator chasing an intermittent failure here.
     */
    private static final String TOPOLOGY_QUERY = """
            SELECT name, engine FROM system.tables \
            WHERE database = currentDatabase() AND name IN ('traces', 'traces_local')\
            """;

    private static final String HEALTHY_WRAPPED = "'%s' is %s over '%s', matching %s=true"
            .formatted(TRACES_TABLE, DISTRIBUTED_ENGINE, TRACES_LOCAL_TABLE, FLAG);
    private static final String HEALTHY_UNWRAPPED_TEMPLATE = "'%s' is a %%s, matching %s=false"
            .formatted(TRACES_TABLE, FLAG);

    private static final String MISSING_TRACES_TEMPLATE = ("%s=%%b, but table '%s' does not exist in the analytics "
            + "database. Trace reads and writes cannot work at all; check that the analytics migrations ran.")
            .formatted(FLAG, TRACES_TABLE);
    /**
     * Deliberately does not name an error code. This branch fires whenever the flag is on and {@code traces} is not
     * {@code Distributed}, and the consequence depends on whether {@code traces_local} happens to exist: absent, deletes
     * fail loudly with {@code UNKNOWN_TABLE (60)}; present but stale — the state a rollback leaves, having promoted the
     * original {@code traces} back while the old shard lingers — the delete <b>succeeds against the wrong table</b> and
     * the live rows are never touched. Naming only the loud outcome would understate the quiet one, which is worse.
     */
    private static final String NOT_WRAPPED_TEMPLATE = ("%s=true routes trace mutations at '%s', but '%s' is a %%s, "
            + "not %s: the Distributed wrap has not been applied (or has been rolled back). Apply it "
            + "(exchange_and_wrap.sh --wrap-only) or set the flag back to false — otherwise trace deletes either fail "
            + "with UNKNOWN_TABLE (60) when '%s' is absent, or silently delete from a stale '%s' while the live rows in "
            + "'%s' are left untouched.")
            .formatted(FLAG, TRACES_LOCAL_TABLE, TRACES_TABLE, DISTRIBUTED_ENGINE, TRACES_LOCAL_TABLE,
                    TRACES_LOCAL_TABLE, TRACES_TABLE);
    private static final String MISSING_TRACES_LOCAL = ("%s=true routes trace mutations at '%s' and '%s' is %s as "
            + "expected, but table '%s' does not exist. Trace deletes fail with UNKNOWN_TABLE (60); the Distributed "
            + "wrap points at a shard table that is absent from this node.")
            .formatted(FLAG, TRACES_LOCAL_TABLE, TRACES_TABLE, DISTRIBUTED_ENGINE, TRACES_LOCAL_TABLE);
    private static final String TRACES_LOCAL_NOT_MERGE_TREE_TEMPLATE = ("%s=true routes trace mutations at '%s', which "
            + "exists but is a %%s rather than a (Replicated)%s. Trace deletes cannot run against that engine, so the "
            + "wrap is pointing at the wrong table.")
            .formatted(FLAG, TRACES_LOCAL_TABLE, MERGE_TREE_ENGINE_SUFFIX);
    private static final String WRAPPED_MESSAGE = ("%s=false routes trace mutations directly at '%s', but '%s' is a "
            + "%s table, which rejects mutations: the Distributed wrap has been applied. Set the flag to true and "
            + "restart — otherwise trace deletes fail with BAD_ARGUMENTS (36) / NOT_IMPLEMENTED (48).")
            .formatted(FLAG, TRACES_TABLE, TRACES_TABLE, DISTRIBUTED_ENGINE);
    private static final String NOT_MERGE_TREE_TEMPLATE = ("%s=false expects '%s' to be a (Replicated)%s that takes "
            + "mutations directly, but it is a %%s. Trace deletes are not guaranteed to work against this engine.")
            .formatted(FLAG, TRACES_TABLE, MERGE_TREE_ENGINE_SUFFIX);

    private final boolean wrapEnabled;

    @Inject
    public ClickHouseTracesTopologyHealthCheck(@NonNull Client clickHouseClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("databaseAnalyticsDataModel") DatabaseAnalyticsDataModelConfig dataModel) {
        super(clickHouseClient, healthCheckTimeout, NAME);
        this.wrapEnabled = dataModel.tracesDistributedWrapEnabled();
    }

    @Override
    protected Result check() {
        return executeProbe(clickHouseClient.queryRecords(TOPOLOGY_QUERY, newQuerySettings()), this::evaluate);
    }

    private Result evaluate(Records records) {
        var engines = readEngines(records);
        var tracesEngine = engines.get(TRACES_TABLE);

        if (tracesEngine == null) {
            return Result.unhealthy(MISSING_TRACES_TEMPLATE.formatted(wrapEnabled));
        }
        return wrapEnabled
                ? checkWrapExpected(tracesEngine, engines.get(TRACES_LOCAL_TABLE))
                : checkWrapNotExpected(tracesEngine);
    }

    private static Result checkWrapExpected(String tracesEngine, String tracesLocalEngine) {
        if (!DISTRIBUTED_ENGINE.equals(tracesEngine)) {
            return Result.unhealthy(NOT_WRAPPED_TEMPLATE.formatted(tracesEngine));
        }
        if (tracesLocalEngine == null) {
            return Result.unhealthy(MISSING_TRACES_LOCAL);
        }
        // Presence alone is not enough: the wrap's target is where TraceDAO sends its DELETEs, so a same-named View,
        // Log or nested Distributed there fails mutations exactly like an absent table. The engine is already in the
        // one row this probe reads, so holding it to the mutation-capable family costs nothing.
        if (!isMergeTreeFamily(tracesLocalEngine)) {
            return Result.unhealthy(TRACES_LOCAL_NOT_MERGE_TREE_TEMPLATE.formatted(tracesLocalEngine));
        }
        return Result.healthy(HEALTHY_WRAPPED);
    }

    private static Result checkWrapNotExpected(String tracesEngine) {
        if (DISTRIBUTED_ENGINE.equals(tracesEngine)) {
            return Result.unhealthy(WRAPPED_MESSAGE);
        }
        if (!isMergeTreeFamily(tracesEngine)) {
            return Result.unhealthy(NOT_MERGE_TREE_TEMPLATE.formatted(tracesEngine));
        }
        return Result.healthy(HEALTHY_UNWRAPPED_TEMPLATE.formatted(tracesEngine));
    }

    /**
     * Accepts every MergeTree family member — {@code MergeTree}, {@code ReplicatedReplacingMergeTree},
     * {@code SharedMergeTree} and the rest. What the probe cares about is whether the engine takes mutations directly,
     * and the family suffix is exactly what marks that; enumerating the variants would only go stale.
     */
    private static boolean isMergeTreeFamily(String engine) {
        return engine.endsWith(MERGE_TREE_ENGINE_SUFFIX);
    }

    private static Map<String, String> readEngines(Records records) {
        var engines = new HashMap<String, String>();
        // Iterating (rather than Iterable.forEach) keeps the read on Records.iterator(), the one method the sibling
        // probes' tests stub — a mocked default method would silently swallow the rows.
        for (var row : records) {
            engines.put(row.getString(NAME_COLUMN), row.getString(ENGINE_COLUMN));
        }
        return engines;
    }
}
