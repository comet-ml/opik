# Buffered cutover runbook — `traces` → partitioned + sharding-ready

Operator runbook for the buffered cutover of the ClickHouse `traces` table: it migrates the live, unpartitioned
`traces` table to `traces_local_v2` (weekly-partitioned, denullified, `is_deleted`-ready) with **near-zero downtime**
and **near-zero deletion loss** — the deletion bridge replays every captured delete before the swap, leaving only a
bounded residual micro-window (see "The final cutover window" below, which also gives the mitigation) — then wraps it
in a sharding-ready `Distributed` table.

The mechanism is **backfill + delta + deletion replay + EXCHANGE**, using the ingestion async-insert buffer to absorb
the brief cutover window instead of a dual-write path.

This runbook is the human-facing artifact; its SQL is validated end-to-end by
[`TracesLocalV2CutoverTest`](../../src/test/java/com/comet/opik/infrastructure/TracesLocalV2CutoverTest.java). Treat
that test as the executable specification of these scripts: if you change the cutover SQL, change it there first and keep
this runbook in sync.

> **This is not a Liquibase migration.** The backfill / delta / replay / EXCHANGE steps are runbook-driven and paced by
> an operator — they produce sustained I/O and must not run as blocking changesets. `traces_local_v2` (migration 000101)
> and `deletion_events_local` (migration 000096) are already created by Liquibase; everything here operates on those.

## Why this is not a plain `INSERT ... SELECT`

A lightweight `DELETE` in ClickHouse flips a hidden row mask; it does **not** bump `last_updated_at` (the
`ReplacingMergeTree` version column). The cutover's delta step re-copies rows that changed during the backfill, but a
lightweight delete leaves no "changed" signal, so the delta is blind to every delete that fires during the
backfill/delta window — those rows stay alive on the new table and the deletion would silently leak across the swap.

The **deletion-events bridge** closes it: with `traceDeletionEventsCaptureEnabled=true`, every trace delete records its
`(workspace_id, project_id, id)` in `deletion_events_local`; the cutover **replays** those keys as deletes against the
new table before the EXCHANGE. The replay matches the **full key**, not `id` alone — see "Delta and replay correctness".

> **All user-facing trace deletes route through one captured path.** Single delete, batch delete-by-project, and thread
> deletion all funnel through `TraceService.delete(...)`, which calls `captureDeletions` for every resolved-project
> delete — since OPIK-7483 there is no project-less branch (ids that resolve to no project are absent and skipped) — so
> enabling the flag covers every one. The **only** uncaptured
> `DELETE FROM traces` is the retention sweep, which is disabled (see the retention note). Any **new** trace-delete path
> introduced during the migration window must likewise capture, or its deletes would leak across the swap.

> **Retention sweeps do not run during the cutover.** Data Retention is disabled in every deployment
> (`retention.enabled` defaults to `false`, env `RETENTION_ENABLED`, and has never been enabled), so the retention
> delete path (`TraceDAO.deleteForRetention*`) does not fire. The only deletes during the cutover window are
> **user-initiated**, and those are captured by the bridge (`TraceService`, reason `USER_REQUEST`). The retention path is
> intentionally **not** wired to the bridge. If Data Retention is ever enabled, either pause the retention job for the
> whole backfill→EXCHANGE window, or first wire retention deletes into the bridge (a `RETENTION` reason recorded before
> each `deleteForRetention*` delete). The test still exercises a synthetic large (retention-shape) delete batch, so the
> replay is proven to handle both batch sizes if retention is enabled later.

## Deletion scenarios and how each is handled

| Delete timing | Fate | Handling |
|---|---|---|
| Before the backfill | Row masked on the source | `INSERT SELECT` honors `apply_deleted_mask=1` → never copied. No replay. |
| During the backfill, after its row was copied | Delta can't see the mask flip | Captured in the bridge → **replayed** before EXCHANGE. |
| During the delta / buffer window | Same as above | Same bridge, same replay step. |

## Prerequisites (do not start without these)

1. **24h UUIDv7 ingestion validation** live long enough that no un-validated future-dated ids land in newly ingested
   weeks. This is not tied to a retention cycle (retention never runs — prereq 8). Pre-validation far-future-timestamp
   rows already in the table are *not* blocked by this: they are copied by the `created_at` slice and surfaced by the far-future audit
   query below — this prereq only ensures no *new* out-of-range partitions are created mid-cutover.
2. **`traces_local_v2` exists and is empty** (migration 000101).
3. **Successor storage/TTL parity.** `traces_local_v2` must resolve the **same `storage_policy` and TTL-to-cold rules**
   as `traces` (tiering is configured per environment, not in the base DDL). If `traces` tiers hot→cold but the
   successor does not, the entire backfill lands on the hot volume. `backfill.sh` warns on a `storage_policy` mismatch;
   compare TTLs with `SHOW CREATE TABLE traces` vs `traces_local_v2`.
4. **`deletion_events_local` exists** (migration 000096).
5. **`databaseAnalyticsDataModel.traceDeletionEventsCaptureEnabled = true`** deployed and live before the backfill
   begins, and kept on for the entire backfill→EXCHANGE window. On docker-compose set
   `ANALYTICS_DB_DATA_MODEL_TRACE_DELETION_EVENTS_CAPTURE_ENABLED=true` (the backend service forwards it) and restart the
   backend. `backfill.sh` captures the `backfill_start` anchor (a `now64(6)` taken just before the first INSERT) and
   prints it — the delta and the replay both key off it.
6. **Cutover buffer knob ready** — `databaseAnalytics.asyncInsertBusyTimeoutMaxMs` (env
   `ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS`), unset by default so the buffer inherits the
   `async_insert_busy_timeout_max_ms=250` carried by `queryParameters`. Raise it to ~10000 for the cutover, then unset it
   again. **Where it is set, the exact value, the rollout and the revert step are in
   ["Where the buffer bump lives"](#where-the-buffer-bump-lives-and-how-to-revert-it)** — it is a temporary env var on the
   deployment's own backend config, not a chart value (OPIK-7686). Have that config change written and reviewed *before*
   the window, so applying it is a merge, not an edit. The ceiling is a backend per-query setting applied on the backend's own
   ClickHouse client, so the migration scripts' direct `clickhouse-client` session **cannot read or verify it**. It is
   therefore **operator-asserted**: `exchange_and_wrap.sh` refuses the EXCHANGE without `--confirm-buffer-raised` (a
   fail-fast acknowledgment gate — it forces the operator to confirm the step, though it cannot prove the value took
   effect). Confirm it actually took effect on the prod-clone/staging load test (the Go/No-Go "Async-insert ceiling
   confirmed" item) before production. **Also confirm client/SDK insert timeouts exceed the widened buffer** (~10s) —
   with `wait_for_async_insert=1` a raised ceiling blocks each insert until it flushes, so a shorter client timeout would
   surface as ingestion errors during the window.
7. **Schema-state flag wired, with a rollout plan** — `databaseAnalyticsDataModel.traceColumnsNonNullable` (env
   `ANALYTICS_DB_DATA_MODEL_TRACE_COLUMNS_NON_NULLABLE`, default `false`). The successor's `end_time`/`ttft` are
   **non-nullable sentinel** columns, so the app must represent an absent value as the epoch/NaN sentinel — not `null` —
   once they are live. This flag switches that on **both** sides: the write bind, and the read/filter/sort translation
   back (`epochToNull` on read, `nullIf(end_time, epoch)` in sorts, sentinel logic in the filter builder). It **must be
   flipped in lockstep with the EXCHANGE** (see "The final cutover window"). Confirm it is deployable on the target (env
   passthrough present) and that you have a fleet-wide rollout mechanism (config push or rolling restart) ready.
   > **The failure mode is silent, so plan a positive check — not error-watching.** A `null` bind into the non-nullable
   > successor is **not** rejected: ClickHouse's `input_format_null_as_default` (default `1`) converts it to the column
   > DEFAULT, which is exactly the epoch/NaN sentinel. So a stale-`false` instance keeps **writing correctly** and emits
   > no ingestion error — but it **reads back** an absent `end_time` as `1970-01-01` instead of `null`, and filters/sorts
   > on absent values use the wrong semantics. Unlike `tracesDistributedWrapEnabled` (fail-loud, see the wrap
   > prerequisite), a missed or partial rollout here shows up only as wrong data. Verify it positively, per instance —
   > and note the check is **not the same on both sides of the swap**. *Before* the EXCHANGE the live table is still
   > Nullable, so an in-progress trace reads back `null` whether the flag is on or off; what discriminates there is what
   > got **written** — the epoch/NaN sentinel means the flag is live, a stored `NULL` means it is not. *After* the
   > EXCHANGE the read-back becomes the discriminator: an absent `end_time` must return `null`, not `1970-01-01`. Assert
   > `ttft` alongside it — same flag, other arm, and one trace written without either covers both. Do both sides of the
   > swap, or a stale instance passes the only check you ran.
8. **Confirm Data Retention is disabled** (`RETENTION_ENABLED=false`, the default). If it is ever enabled, see the
   retention note above first.
9. **Sufficient free disk** — the backfill writes a full second physical copy of `traces`, so node free space must clear
   **≥ 2× the current `traces` on-disk size** (more counting merge scratch). `estimate.sh` reports headroom and
   `backfill.sh` aborts below `--min-free-factor` (default 2.0). On tiered storage this whole-node floor is necessary but
   not sufficient — validate per-volume (hot) headroom too, since new parts land hot before they tier.
10. **Schema parity of source and successor** — `traces` and `traces_local_v2` must stay equivalent for as long as both
    exist: the same base (stored) columns (which the cutover must copy) and the same materialized columns (which each
    table recomputes). Guarded in CI by `TracesLocalV2CutoverTest` — `cutoverCopiesEveryBaseColumn` (a new base column
    fails the build until it is in the cutover column list) and `successorMaterializedColumnsMatchSource` (a materialized
    column added to one table but not the other fails the build). Re-confirm both are green on the release being
    deployed.
11. **Fresh backup / snapshot** of the ClickHouse data node.
12. **Freeze concurrent DDL on `traces` for the window — and through the rollback-eligible soak.** Hold any deploy or
    Liquibase changeset that would `ALTER`, `RENAME`, or otherwise touch `traces` / `traces_local_v2` for the whole
    backfill→EXCHANGE window — a schema change landing mid-cutover races the swap and can corrupt it — and keep it frozen
    until `finalize.sh` commits (see "Point of no return"): a `traces` schema change made *after* the EXCHANGE is lost
    from the live table on a rollback + finalize. The revamp's own migrations (000096/000101) are already applied; this
    is about *unrelated* migrations or ad-hoc DDL during the window.
13. **Deletion bridge holds no empty-`project_id` trace events, and OPIK-7483 is live fleet-wide.** Since OPIK-7483 every
    trace delete carries its `project_id`, so the cutover replay is full-key only (no workspace-scoped branch). Confirm
    OPIK-7483 is deployed across the **whole** backend fleet before the window (a straggler pre-7483 backend could emit an
    empty-`project_id` event the replay would miss), then assert the bridge holds none. `deletion_events_local` is a
    per-shard local table, so query it cluster-wide:
    ```sql
    SELECT count() FROM clusterAllReplicas('{cluster}', <database>.deletion_events_local)
    WHERE source_table = 'traces' AND project_id = '';
    ```
    If non-zero, do NOT proceed: an unexpected row means the replay would miss those deletes — investigate/drain them first.
14. **Privilege smoke test — run `delta_replay.sh` once BEFORE the backfill, with `--backfill-start` set to
    `now()`.** Both statements execute but match nothing (no row has `created_at`/`last_updated_at` in the future, and
    the bridge holds no events after that instant), so it is a functional no-op against the data — while still proving
    the migration user can actually perform every kind of statement the cutover needs. Do this on a least-privilege user
    and you catch grant gaps in seconds instead of mid-window.
    > This is not hypothetical. On the first real-cluster run the deletion replay failed with
    > `Code: 497 … necessary to have the grant ALTER UPDATE(_row_exists)`: ClickHouse implements a lightweight `DELETE`
    > as `ALTER UPDATE _row_exists = 0`, so it authorises it as **`ALTER UPDATE` on that hidden column, not
    > `ALTER DELETE`**. The read-only drivers (`estimate.sh`, `verify.sh`) cannot surface this — only executing a
    > mutation can. Grant it **column-scoped** (`ALTER UPDATE(_row_exists)`) so the user can flip the delete mask
    > without being able to modify any real data column.
15. Schedule during off-peak hours.

## The sequence

1. **Backfill — run [`scripts/backfill.sh`](scripts/backfill.sh)** (preferred). It iterates by week oldest→newest, but
   splits each week adaptively into `created_at` sub-windows so no single INSERT exceeds `--max-rows-per-insert` (see
   "Batching and throttling"). It reconciles each window on a **dedup-aware** count (`uniqExact` of the dedup key, since
   raw `count()` differs between an un-merged source and a destination that deduped versions on insert) and aborts only
   on a genuine shortfall in a **settled** window (> 0.01%); a still-live window (its end in the future) legitimately
   diverges from concurrent writes/deletes — the delta and replay reconcile it — so that is logged, not fatal. The
   reconciliation counts source and destination **together after** each copy (a consistent snapshot): a delete is not
   bounded by `created_at`, so it can shrink even a settled window mid-copy, and comparing a stale pre-copy source count
   against a fresh post-copy destination count would abort falsely. It is idempotent and resumable (an already-copied
   window is skipped), and prints the `backfill_start` anchor for step 2 — captured once and persisted to `--state-file`,
   so a resumed run keeps the original anchor rather than minting a later one that would miss early-window deletes.
   `--pause-seconds` throttles between windows; `--dry-run` prints the window plan. Preview then run:
   ```bash
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/backfill.sh --database opik --dry-run
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/backfill.sh --database opik --pause-seconds 60
   ```
   It executes the reference statement in
   [`000001_backfill_traces_local_v2.sql`](scripts/db-app-analytics/000001_backfill_traces_local_v2.sql) — the script
   reads that file and substitutes the window bounds, so the two never drift.
2. **Raise the buffer ceiling** (config — see
   ["Where the buffer bump lives"](#where-the-buffer-bump-lives-and-how-to-revert-it) for the key, the value and the
   restart wait), then **[`scripts/delta_replay.sh`](scripts/delta_replay.sh)**
   (reference SQL [`000002_delta_and_deletion_replay.sql`](scripts/db-app-analytics/000002_delta_and_deletion_replay.sql))
   — delta-insert (anchored at `backfill_start`), then **deletion replay**. The replay runs with
   `lightweight_deletes_sync = 2`, so it returns only once the delete mutation has applied on **every** replica.
   The driver passes `--time`, so clickhouse-client prints each statement's wall time in seconds (delta-insert first,
   deletion replay second) — **record the second value**: the final-delta→EXCHANGE gap must fit inside the buffer hold
   (Go/No-Go). Without `--time` a bare `--query` prints no timing at all.
   ```bash
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/delta_replay.sh --database opik --backfill-start '<ts> UTC'
   ```
3. **QA — run [`scripts/verify.sh`](scripts/verify.sh)** (see "Verifying the migration"): confirm the copy altered no
   data before committing the swap. Run it after step 2 (and it can be re-run after step 4).
4. **[`scripts/exchange_and_wrap.sh`](scripts/exchange_and_wrap.sh)** (reference SQL
   [`000003_exchange_and_wrap.sql`](scripts/db-app-analytics/000003_exchange_and_wrap.sql)) — first **gates on a settled
   replication state** (empty `replication_queue` on `traces`/`traces_local_v2` and the deletion-replay mutation finished
   on `traces_local_v2`, across all replicas via `clusterAllReplicas`) so no replica swaps in an incomplete table
   (`--force` overrides); then records and
   prints `cutover_start`, runs `EXCHANGE TABLES ... ON CLUSTER` and renames the displaced old data to
   `traces_pre_cutover_backup` (see "Naming and the parked backup"). It **stops there by default** (EXCHANGE only,
   leaving `traces` a `MergeTree` where deletes still work); the `RENAME` + `Distributed` wrap runs only with
   `--with-wrap`. Restore the buffer ceiling and verify.
   ```bash
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/exchange_and_wrap.sh --database opik \
       --backfill-start '<anchor from backfill.sh> UTC' --confirm-buffer-raised --confirm-retention-paused
   ```
   Every EXCHANGE path requires: `--backfill-start` (for the final deletion replay), `--confirm-buffer-raised` (writes in
   the final window survive the swap), and `--confirm-retention-paused` (retention deletes bypass the bridge, so a
   retention sweep in the window would leak across the swap). Add `--with-wrap --confirm-daos-retargeted` only once
   `databaseAnalyticsDataModel.tracesDistributedWrapEnabled=true` is live across the backend fleet (OPIK-7455), so trace
   mutations target `traces_local`. The wrap is separately reversible at any later point with
   `rollback.sh --unwrap-only` (see "Un-wrap"), which keeps the cutover — so a wrap concern need not become a decision
   about the whole migration.

> **Readiness fails while the flag and the live topology disagree (OPIK-7773), and that stalls the
> rollout rather than breaking the service.** Worth knowing before you watch it happen:
>
> - The check is a **critical READY dependency**, so a pod whose flag disagrees with the table it finds
>   never becomes ready. During a rolling update that means the *new* pod stays unready and Kubernetes
>   keeps the *old* one serving — the deployment looks stuck, traffic does not stop. The flip completes
>   only once the paired DDL lands.
> - It re-reads the **topology** on every probe but holds the **flag** from startup, so the two orderings
>   recover differently. **Toggle-first**: the pod already restarted for the flag, so it clears itself
>   within a probe interval of the DDL landing. **DDL-first**: existing pods keep their old flag and stay
>   unready until the flag rollout restarts them — there the restart *is* the fix, not a workaround.
> - Either way the window announces itself. Keep it short.

> **HARD PREREQUISITE for the wrap (step 4, part 2): enable `tracesDistributedWrapEnabled` so trace mutations target `traces_local` first (OPIK-7455).** A
> `Distributed` table supports `SELECT` and `INSERT` but **not** mutations. Verified on ClickHouse 26.3:
> - `DELETE FROM <distributed>` → `Code 36 BAD_ARGUMENTS: DELETE query is not supported`
> - `ALTER TABLE <distributed> DELETE` → `Code 48 NOT_IMPLEMENTED: Distributed doesn't support mutations`
>
> So the moment the wrap is applied, **both** the product's delete-by-id path **and** the retention
> sweep (`DELETE_FOR_RETENTION` / `deleteForRetentionBounded`) start returning 500 against `traces`. This is prep work
> that shipped **before** the wrap (OPIK-7455): `TraceDAO` renders its mutation table through a single toggle,
> `databaseAnalyticsDataModel.tracesDistributedWrapEnabled`. Set it **`true` in lockstep with applying the wrap** so those
> deletes run against `traces_local`; reads and inserts stay on the Distributed `traces`. The flag is **startup-bound**
> (read once at boot; no hot-reload), so making it "live across the fleet" means a **completed rolling restart of every
> backend instance**. Since OPIK-7773 the flag's value is observable per instance: the `clickhouse-traces-topology`
> readiness check asserts it against the live `traces` engine on every probe, so
> `GET /health-check?name=clickhouse-traces-topology` reports which side of the cutover that instance believes it is on
> and, on a mismatch, names both the flag and the observed engine. A mismatch is
> **fail-loud**, not silent: a stale-`false` instance issues `DELETE` against the `Distributed` `traces` (code 36/48), a
> stale-`true` instance against an absent `traces_local` — both 500 the delete path, so a partial rollout surfaces at
> once and is recoverable. While it is `false` (the deploy
> default, and correct while `traces` is still a `MergeTree`) the deletes target `traces` directly. **General rule (splits
> by kind of change):** row mutations (`DELETE`, `ALTER … DELETE`) and `MATERIALIZE COLUMN` / `ADD INDEX` / `MODIFY TTL`
> target **`traces_local` only** — the `Distributed` `traces` rejects them (code 36/48), so a slip fails loudly; but
> `ADD` / `DROP` / `MODIFY COLUMN` (the shape of every trace schema migration) must be applied to **both** `traces_local`
> **and** the `Distributed` `traces` — the wrapper accepts them as metadata-only, and targeting only `traces_local` leaves
> the wrapper without the column so reads fail with code 47. The `EXCHANGE` alone is the data cutover and leaves `traces` a
> `MergeTree` where deletes still work — which is why the wrap is **opt-in** (`--with-wrap`) and the default stops after
> the EXCHANGE. Defer the
> wrap until the retarget flag is wired into the deploy. The wrap is the sharding-readiness layer, not the cutover.
>
> **"In lockstep" cannot mean simultaneous — plan for a short mismatch window.** The toggle is a
> config push plus a rolling restart; the wrap is a DDL statement. They cannot land at the same instant, so
> one of two windows is unavoidable:
> - **toggle first** (recommended): from the moment the last backend comes up with `true` until the wrap
>   completes, every trace delete targets a `traces_local` that does not exist yet →
>   `Code: 60 UNKNOWN_TABLE`. Reads and writes are unaffected.
> - **wrap first**: from the swap until the rolling restart finishes, deletes hit the `Distributed` `traces`
>   → `Code: 36`. Same blast radius, but it also exposes the cross-node `ON CLUSTER` skew with no buffer.
>
> **Since OPIK-7773 the mismatch window is also a readiness window.** `clickhouse-traces-topology` is a
> `critical`/`ready` check, so for as long as flag and topology disagree — in **either** order — every instance that
> sees the mismatch fails `/health-check?name=all&type=ready` and Kubernetes takes it out of rotation. That is the
> point of the check (an instance whose deletes cannot work should not serve), but it changes the cost of the window
> from "delete-path 500s" to "no backend in rotation", so the window must sit **inside the declared maintenance window**
> that `--confirm-maintenance` already asserts for the wrap. It is self-clearing: the probe re-evaluates continuously,
> so rotation returns on the next successful probe once the two sides are back in step. **Neither ordering needs an
> extra restart** — both spend exactly the one planned rolling restart the toggle already requires; only its position
> differs, and with it what closes the window: on the toggle-first path the restart comes first and the **wrap DDL**
> closes the window, on the wrap-first path the wrap comes first and the **restart completing** closes it.
>
> **The check reads one replica per probe.** It queries the node-local `system.tables` on whichever ClickHouse node the
> load-balanced service hands it, so across the cross-node `ON CLUSTER` skew described below the mismatch is seen only
> by the probes that land on a not-yet-wrapped host: pods flap instead of the fleet going dark in lockstep. That is
> expected inside the window and is why the probe is not the propagation gate — to confirm the wrap actually reached
> every replica, use the cluster-wide `clusterAllReplicas('{cluster}', system.tables)` form that `finalize.sh`
> classifies with (also in the self-host troubleshooting page). Fan-out is deliberately out of the probe: it needs
> `REMOTE` + `CLUSTER` grants the app user is not guaranteed to hold, and one unreachable replica would take the whole
> fleet out of rotation.
>
> Prefer **toggle first**, have the `--wrap-only` command ready to run the moment every backend instance is up, and
> keep the window to seconds. Nothing in either direction corrupts data — that is what makes a short window
> acceptable — but announce it on a shared environment, and do not leave the toggle `true` without the wrap (or vice
> versa) for any length of time: with the readiness check in place that is now an outage, not a degradation.
>
> **Monitoring consequence of the flip:** `system.parts` only knows `traces_local` post-wrap, so the
> `opik.clickhouse.partition.*` parts gauges relabel from `table="traces"` to `table="traces_local"`, while the
> lightweight-delete-mask gauge (read through the wrapper) stays labelled `traces`. Any dashboard/alert keyed on
> `table="traces"` goes blank when the wrap lands — update them in the same window, or point
> `PARTITION_METRICS_LWD_TABLES` (default `traces,spans`) at `traces_local` for label consistency.
>
> **Applying the deferred wrap later:** once the retarget flag (`tracesDistributedWrapEnabled=true`) is live across the
> backend fleet, run
> `exchange_and_wrap.sh --database opik --wrap-only --confirm-maintenance --confirm-daos-retargeted` — it runs the settle
> gate and applies **only** the wrap on the already-swapped `traces` (no second EXCHANGE, no new `cutover_start`).
> `--confirm-daos-retargeted` is required for **any** wrap (same-run or deferred), since the wrap makes `traces`
> `Distributed` and breaks the delete/mutation DAOs until `tracesDistributedWrapEnabled=true` routes them at `traces_local`. To roll the wrap back, use
> `rollback.sh --stage C`, then set `tracesDistributedWrapEnabled` back to `false` with the same rolling restart so
> post-rollback deletes target the `MergeTree` `traces` again — a stale-`true` instance would `DELETE` against the
> now-absent `traces_local` and 500.
>
> The wrap is **gapless per node**: it pre-builds the `Distributed` wrapper under a temp name, then one atomic
> multi-target `RENAME` rotates the data to `traces_local` and the wrapper into `traces`, so `traces` is never absent on
> a node. A brief **cross-node** `ON CLUSTER` propagation skew still exists (as for any `ON CLUSTER` DDL), during which a
> Distributed query could route to a not-yet-created `traces_local` on a lagging node — so the deferred `--wrap-only`
> path still **requires `--confirm-maintenance`** (re-raise `asyncInsertBusyTimeoutMaxMs` / quiesce ingestion / take a
> maintenance window). The same-run `--with-wrap` path **shares that cross-node window** — the still-raised EXCHANGE
> buffer parks INSERTs (reducing, not eliminating, the exposure to a size-triggered flush routed at a not-yet-created
> `traces_local`), and SELECTs are not buffered — so the brief skew is an accepted cost of the cutover window either way,
> not something the buffer fully covers.
>
> **Wrap flags** (`exchange_and_wrap.sh`, mutually exclusive; default is EXCHANGE-only): omit them (or pass
> `--skip-wrap`, an explicit alias) to run the EXCHANGE and stop; `--with-wrap` to also apply the wrap in the same run;
> `--wrap-only` to apply just the deferred wrap later.

**Dedup note.** After the delta, a row can have two physical versions on `traces_local_v2` (the backfilled one and the
delta one). This is normal — `ReplacingMergeTree` collapses them on merge / under `FINAL` / `LIMIT 1 BY id`, highest
`last_updated_at` winning. Do not "fix" it.

### Where the buffer bump lives (and how to revert it)

**Decision (OPIK-7686): a temporary env var on the deployed backend's own configuration — not a chart value.** The three
`ANALYTICS_DB_ASYNC_INSERT_*` knobs are deliberately absent from the chart's `values.yaml` (OPIK-6880, #7675).
Rationale:

- **No chart change is needed.** `component.backend.env` is a free-form map rendered straight into the backend
  ConfigMap, so a deployment-level entry is already sufficient.
- **Removal is a clean one-step rollback.** Unset means "leave `queryParameters` alone" (`DatabaseAnalyticsFactory`), so
  *deleting* the key restores whatever `queryParameters` carries — `async_insert_busy_timeout_max_ms=250` on the shipped
  `config.yml` default. There is no "set it back to 250" edit, and therefore no pinned value that can later drift from
  that default. For a time-boxed window that reversibility is the property worth optimising for.
  > **If your deployment overrides `ANALYTICS_DB_QUERY_PARAMETERS`, `250` is not your baseline.** Deleting the key
  > restores *that* chain's `async_insert_busy_timeout_max_ms` — or, if the chain omits it, the ClickHouse server value.
  > Read your effective `queryParameters` before the window and record the number you are reverting to.
- **The value is deployment- and window-specific** — one environment, for the length of the cutover. Keeping it in that
  deployment's own config leaves it version-controlled and auditable without turning a temporary state into a permanent
  chart default that every install inherits.
- **A chart value would save no work**: you edit the deployment config either way.

> For the record, the reason first given on #7675 for excluding these — that rendering them would send empty strings
> where the backend expects an integer, so it "would not be inert" — was **wrong**. `config.yml` ships
> `${ANALYTICS_DB_ASYNC_INSERT_*:-}` as the default for all three, so the empty case is the normal path in every
> environment today: an empty substitution leaves a bare YAML scalar that parses to `null` on the boxed field behind it
> (`Integer` for the two busy-timeout knobs, `Long` for `asyncInsertMaxDataSize`), and the `@Min(1)` each of them carries
> does not fire on null. Exposing them in the chart with empty defaults *would* be safe. The decision above rests on
> reversibility and scope, not on safety.

> **Never bump it by editing `ANALYTICS_DB_QUERY_PARAMETERS`.** That means re-pasting the entire tuning string
> (`compress`, `failover`, `async_insert`, `wait_for_async_insert`, the skip-index and shard settings, …), which risks
> silently dropping one of the others and drifting from the `config.yml` default. The dedicated
> `ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS` override exists precisely so the cutover states only the one value it
> is changing.

**What to set.** One key on the backend. The two delivery forms are not interchangeable — under Helm it is a YAML entry
in the values map, so `KEY=VALUE` shell syntax there renders nothing:

```yaml
# Helm — under component.backend.env (quote the value; the ConfigMap takes strings)
component:
  backend:
    env:
      ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS: "10000"
```

```bash
# docker-compose — a backend environment variable (the compose file already forwards it)
ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS=10000
```

Only the ceiling changes: leave `…_MIN_MS` and `…_MAX_DATA_SIZE` unset, so the floor stays at the
`async_insert_busy_timeout_min_ms=100` carried by `queryParameters`, and widening the ceiling alone is what parks the
inserts.

**How to revert: delete the key** — do not set `250` (see the caveat above on what your baseline actually is). The revert
is owed on **every** exit path, not just the happy one: after a successful EXCHANGE it is sequence step 5, and after a
**rollback** it is equally required. `rollback.sh` is SQL-only and does not touch backend config, so no stage removes the
override for you — a rolled-back deployment left with the widened ceiling keeps parking every insert for up to ~10s.
Treat the revert (and its restart) as part of finishing either outcome.

**It takes effect only on a backend restart — so confirm the restart finished before continuing.** The backend receives
this through the container environment (`envFrom.configMapRef` under Helm), which Kubernetes injects at container start
only: editing the ConfigMap does not reach a running pod. **How that restart is triggered is deployment-specific** — the
chart ships no automation for it, so some deployments run a ConfigMap watcher that rolls the workload on its own while
others need an explicit `kubectl rollout restart deployment/opik-backend`. Know which one yours is *before* the window.
Either way the operator's obligation is identical, because the ceiling has to be live on **every** instance before step
2 — so verify rather than assume:

```bash
kubectl rollout status deployment/opik-backend -n <namespace>
kubectl get cm opik-backend -n <namespace> \
    -o jsonpath='{.data.ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS}{"\n"}'
# and confirm no surviving pod predates the roll:
kubectl get pods -n <namespace> -l component=opik-backend \
    -o custom-columns=NAME:.metadata.name,START:.status.startTime
```

These names are what the chart renders by default — Deployment and ConfigMap `opik-backend`, label
`component=opik-backend`. They are derived from `opik.name`, so a `nameOverride` (or a parent chart supplying one) moves
all three; substitute your release's actual names.

Three consequences to plan for:

- **One restart, not two, before the tail.** `traceColumnsNonNullable = true` (prereq 7) is another entry in the same
  backend config and the same ConfigMap — and step 1 of "The final cutover window" asks for both. Land them **together**
  so the fleet restarts once.
- **Keep the chosen ceiling below the pod's termination grace period.** The revert is delivered by a *second* restart, and
  at that moment pods are holding inserts parked for up to the widened ceiling. The chart does not set
  `terminationGracePeriodSeconds`, so it is the Kubernetes default **30s** — comfortably above a ~10000ms ceiling, but a
  much larger ceiling would let `SIGKILL` land on parked inserts. Check the two numbers against each other before
  choosing a value.
- **The restart itself costs ingestion capacity** (rolling-update `maxUnavailable`, plus any PodDisruptionBudget), so do
  it while there is slack — not between the final delta and the EXCHANGE.

### The final cutover window (the zero-loss invariant)

The buffer widening (prereq 6) is what makes the flip lossless, but the guarantee rests on a timing invariant worth
stating precisely. Writes use `async_insert=1, wait_for_async_insert=1`, so a raised `asyncInsertBusyTimeoutMaxMs` parks
each insert (the client blocks) until it flushes — and after the `EXCHANGE` a parked insert flushes into whatever table
is now named `traces`, i.e. the successor. **But the adaptive buffer also flushes on size**, so under load a flush can
still land in the *old* `traces` in the gap between the last delta read and the `EXCHANGE` — and the delta has already
run. The binding constraint is therefore **not** "replay < buffer window"; it is that the **gap between the final delta
and the `EXCHANGE` completing must stay within the buffer hold**. So run the tail as tightly as possible:

1. Widen the buffer, and **roll out `traceColumnsNonNullable = true` to every backend instance** (see below). Both are
   entries in the same backend config and the same ConfigMap, so land them **together** and let the single restart carry
   both — see ["Where the buffer bump lives"](#where-the-buffer-bump-lives-and-how-to-revert-it).
2. Do the QA verify on an **earlier** pass (it can take minutes on a large table — do not let it be the last thing
   before the swap).
3. Run a **final** `delta_replay.sh` as the last write-facing step.
4. Run `exchange_and_wrap.sh --backfill-start '<anchor> UTC' …` **immediately** after it (the settle gate + `EXCHANGE` are
   fast and metadata-only). It captures `cutover_start`, then runs a **final deletion replay** from `backfill_start`
   right before the swap — so deletes bridged in the `[final delta_replay, cutover_start)` gap are masked on the
   successor rather than leaking (that gap is covered by neither the earlier forward replay nor the rollback
   reverse-replay, which starts at `cutover_start`). Deletions only; the buffer carries the writes.
5. Restore the buffer ceiling; parked inserts flush into the successor.

Keep step 3→4 short. **Deletes** up to `cutover_start` are covered by step 4's final deletion replay; **writes** in the
gap are covered by the buffer (which flushes into the successor after the flip). The one residual is a delete whose
bridge row commits after that final replay's read but with `event_time < cutover_start` — the same inherent micro-window
as a size-triggered buffer flush; if delete load is high, quiesce user deletes for the final seconds.

**The `traceColumnsNonNullable` flip (mandatory, and why it goes first).** The successor stores `end_time`/`ttft` as
non-nullable epoch/NaN sentinels, and the flag is what makes the app agree with that representation — sentinel binds on
write, and sentinel→`null` translation on read, filter and sort. It is a **config** change rolled out across the fleet
(not atomic), unlike the metadata-only `EXCHANGE`, so it cannot be flipped at the same instant; roll it out to `true` on
**all** instances **before** the `EXCHANGE`.

*Why before, not after.* Not because writes would break — they would not (see prereq #7: a `null` bind is silently
converted to the column DEFAULT, which is the sentinel, so writes succeed on either setting). It goes first because the
**read** side must already speak sentinel the instant the successor is live under the name `traces`: while the flag is
`false` against the successor, an absent `end_time` reads back as `1970-01-01` rather than `null`, and absent-value
filters/sorts are wrong. Doing it first is safe because `true` is write-compatible with **both** schemas — binding the
epoch/NaN sentinel into the *still-Nullable* source column is valid — and the copy machinery tolerates the resulting
NULL/epoch mix (backfill `coalesce`, verify normalizes both to `0`).

*Two caveats for the pre-swap window, so keep it short and off-peak.* Both affect rows written while the flag is `true`
and `traces` is still the Nullable original — and note the window does not close at the `EXCHANGE`: on a rollback it
**reopens** until the flag-revert restart lands on every instance, so the same rows keep accruing then (see "Rolling back
the `traceColumnsNonNullable` flip").

- "`end_time` is empty"-style **filters** use sentinel logic against the still-Nullable table.
- **The sentinels persist in the original, and `duration` is computed wrong from them.** An absent value is written as
  the epoch / `NaN` sentinel instead of `NULL`, against a column whose convention is `NULL`. The blast radius is wider
  than in-progress traces: the `end_time` arm needs a trace with no `end_time` yet, but the **`ttft` arm hits any trace
  written without a `ttft`** — the common case. Worse, the original's **`duration`** (a stored `MATERIALIZED` column)
  guards only `end_time IS NOT NULL` and does not know the epoch sentinel, so a trace with no `end_time` gets a large
  **negative** duration (≈ `-1.79e12` ms) instead of `NULL`. The successor's expression *does* guard the sentinel, so the
  copy recomputes it as `NaN` and the **forward path is self-healing**; `verify.sh` is unaffected (it excludes
  materialized columns by design). But a **stage B/C rollback promotes the frozen original**, making those sentinels and
  negative durations live again while the healed successor copy is parked and then discarded by `finalize.sh` — so the
  rollback path must repair them.

On rollback, after swapping the Nullable original back, revert the flag to `false` **and** run that repair.

**Trace-delete partition pruning needs no flip at all (OPIK-6901).** A trace `DELETE` binds itself to the weekly
partitions its own ids resolve to instead of being planned against every part of the table, so a handful of ids no
longer rewrites the whole table. There is **no flag, no ordering constraint, and nothing to revert on
rollback**: the predicate is emitted unconditionally, and one rendered statement is correct against the original and
against the successor alike.

That works because the derivation (`WeeklyPartitions`) names, for each id, the week it partitions into under **each**
`id_at` type a mutation can meet — the successor's `DateTime64(0,'UTC')` and the original's 32-bit `DateTime`, which
holds `epochSecond % 2^32`. Below 2106 the two coincide and the set is one value per week, which is all real traffic;
only a far-future id (the litellm ~2201 rows) contributes a second, and widening an `IN` set can only select an extra
partition — the `(project_id, id)` predicate it is ANDed with still decides which rows go. Against a 220-partition
table the pruning is identical either way (3/220 parts with or without the extra value).

This is what an earlier revision gated on a `tracesWeeklyPartitionPruningEnabled` flag, which asserted the `EXCHANGE`
had already happened and had to be reverted **before** a stage B/C rollback promoted the original. That flag is gone,
along with its footgun: a stale `true` used to make trace deletes on the original match **zero rows while reporting
success**, because a set carrying only the honest week cannot match a row the 32-bit column filed under a wrapped
timestamp. Both weeks are now in the set, so neither direction of the swap needs an operator step.

Coverage sits in `TracesPartitionPruningMutationTest` (the successor) and `TracesLegacyTablePruningMutationTest` (the
original), which delete the same far-future row shape with the same rendered predicate against each schema.

## Batching and throttling

On a large production table a single week can be enormous, so the backfill does **not** run one INSERT per week. Two
independent controls keep each statement safe:

- **Per-statement row bound (`--max-rows-per-insert`, default 2,000,000).** `backfill.sh` counts each week and, if it
  exceeds the bound, halves it in `created_at` time — adaptively, so busy periods split more and quiet ones stay whole —
  until every leaf window fits, then inserts each. This bounds each INSERT's **duration**, its **blast radius** on
  failure (only that window re-runs), the **part count** it adds to the destination, and gives per-window resume. It is
  *not* a memory bound. Smaller values are safer per statement but create more parts (more merge pressure); larger
  values create fewer parts but a bigger blast radius. Note the ClickHouse "batch 1k–100k rows" guidance targets
  client-side row-by-row inserts; a server-side `INSERT … SELECT` streams and is efficient at far larger sizes, so the
  default is millions, not thousands.
- **Per-block memory bound (`--max-insert-block-size`, default 1,048,576 = the ClickHouse default → `SETTINGS
  max_insert_block_size`).** An `INSERT … SELECT` streams; ClickHouse forms part-writing blocks capped at the smaller of
  this row count and `min_insert_block_size_bytes` (256 MB default). For wide/heavy trace rows the byte cap dominates,
  so peak insert memory is a small multiple of ~256 MB regardless of the window size — the statement does not load the
  window into memory. Lower this (or `min_insert_block_size_bytes`) on a memory-constrained data node.

- **Insert pipeline threads (`--max-insert-threads`, omitted by default → `SETTINGS max_insert_threads`).**
  Often the throughput ceiling: where nothing sets it, ClickHouse's default `0` means *"`INSERT SELECT` no
  parallel execution"*, so the insert side runs serialised. Passing a value controls how much of the machine
  the backfill may use and can speed the copy up substantially.

  **Omitting the flag means *inherit*, not zero.** The drivers strip the setting line from the SQL when the
  flag isn't passed, so the server's own value applies. Rendering an explicit `0` would **override** a profile
  that sets it and force the insert serial — a silent slowdown, not a no-op. Pass `0` only to *force* serial.

  Three caveats, all from upstream: the setting applies to **`INSERT SELECT`** only; **ClickHouse Cloud
  defaults it to `1`/`2`/`4`** by node memory, not `0`; and it helps only if the read side is parallel too
  (*"has effect only if the `SELECT` part is executed in parallel"* — see `max_threads`).

  **Why the insert side, and how sure we are.** The destination materialises `output_keys` by parsing the
  `output` JSON (there is no `input_keys` column), plus `truncated_input`/`truncated_output` and the length,
  `duration` and `id_at` columns. Upstream says materialized values are calculated *"when rows are inserted"*
  but not by which stage, so blaming the insert side is an **inference from profiling**, not a guarantee.
  Confirm it on your own data: effective cores near 1 while the machine is idle and `OSIOWaitMicroseconds` is
  0, then rising towards the thread count once raised. Mind the units — `ProfileEvents` are microseconds,
  `query_duration_ms` is milliseconds:

  ```sql
  (ProfileEvents['UserTimeMicroseconds'] + ProfileEvents['SystemTimeMicroseconds']) / (query_duration_ms * 1000)
  ```

  A result above the node's core count means the arithmetic is wrong. Note it is *query-wide* CPU: `query_log`
  does not separate read from insert threads, so the delta on raising the setting is what carries the argument.

  **Two costs.** Upstream: *"higher values will lead to higher memory usage"* — and on this table a single very
  large `output` document is a **per-row** cost that no block cap bounds, so raise `max_memory_usage` alongside
  or narrow the window. And parts per partition grow; watch them against **this cluster's** `parts_to_throw_insert` and
  `parts_to_delay_insert` — read them from `system.merge_tree_settings`. **Do not work from a
  remembered default**: ClickHouse has changed these across versions (older releases shipped far lower values
  than current ones), and a deployment may tune them further, so a hardcoded ratio can be an order of magnitude
  wrong in either direction.
  Value choice is a capacity decision, not a benchmark: on an idle rehearsal box a large value looks free, but
  on production those threads compete with live query latency. `estimate.sh` does **not** model this setting —
  time a real window at your intended value and feed it back via `--rows-per-sec`.
  Full diagnosis in `backfill.sh`'s `--max-insert-threads` option docs.

  **If you edit the rendering, re-validate it by hand — nothing in this repo checks it for you.** The drivers
  render this setting by requiring exactly one line-anchored `max_insert_threads = ${MAX_INSERT_THREADS},` in
  `000001`/`000002` and then either stripping it (inherit) or substituting it. The trailing comma is **required**: it is
  what makes removing the line safe, so the assignment must not be the last entry in the `SETTINGS` clause. A line
  ending in `;`, or in nothing with the `;` on the next line, carries the clause terminator — stripping it would
  leave a dangling comma and no terminator, so both spellings are refused rather than rendered. Every way that can go wrong is
  silent: a reformatted or shared line, a missing assignment, a duplicate one, or a placeholder that survives
  into an executable line. The drivers' own guards abort on each of those, but there is deliberately **no
  committed harness** here — this directory holds operator drivers only — so after changing either driver or the
  `SETTINGS` clause of `000001`/`000002`, exercise those cases manually against corrupted copies before a
  cutover window. Each driver fences its rendering block with `>>> BEGIN max_insert_threads rendering` /
  `<<< END` so it can be extracted verbatim rather than reimplemented. The
  block reads no files: it operates on a `$sql` variable the caller must populate, and names the SQL path only in
  its two error messages, so whatever you extract it into has to load the file itself. It is otherwise
  CWD-independent, as are the drivers (`SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"`), so it can be
  run from anywhere.

- **Per-block partition bound (`--max-partitions-per-insert-block`, default 2000 → `SETTINGS
  max_partitions_per_insert_block`).** Not a throughput knob — a **correctness gate**. The destination is
  weekly-partitioned, so a block spans as many partitions as the ids in it imply; ClickHouse's default of 100 aborts the
  INSERT (`throw_on_max_partitions_per_insert_block = 1`) rather than degrading, and far-future UUIDv7 ids reach that on
  real data. Neither of the two bounds above can prevent it. **`delta_replay.sh` takes the same flag and needs the same
  value** — the delta INSERT writes into the same partitioned shadow. See "Far-future partitions from
  far-future-timestamp ids".

**Throttle** with `--pause-seconds` (recommended 30–60s at peak): it sleeps after each inserted window so background
merges consolidate the new parts before the next window piles on more.

**Estimate first.** [`scripts/estimate.sh`](scripts/estimate.sh) projects the backfill ETA for a given config: it reads
the live row/byte counts of `traces`, estimates copy throughput with an **on-the-fly read probe** (`SELECT … FORMAT
Null` — it creates no table), derates it by `--write-cost-factor` to account for the copy's unmeasured write/compression
cost, and reports the projected window count, copy time, throttle idle, and total. Run it with the same
`--max-rows-per-insert` / `--pause-seconds` you plan to use:
```bash
CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/estimate.sh --database opik --max-rows-per-insert 2000000 --pause-seconds 60
```
For an exact figure, time one real window with `backfill.sh` and feed its rows/sec back via `--rows-per-sec`.
It is a planning ballpark — real throughput varies with concurrent load, merges and cold-tier reads.

The **delta-insert** (step 2) covers only writes during the backfill window, not the whole table, so it is normally one
statement (with the same block-size **and partition** bounds); `000002` documents how to split it into two batched passes
if a long backfill made it large. If you do split it, **carry the whole `SETTINGS` block onto both passes — but carry
the settings, not the placeholders.** Hand-written statements bypass the driver, so nothing substitutes `${...}` and
none of the driver's guards apply; substitute every placeholder concretely first, and note that
`${MAX_INSERT_THREADS}` has no substitutable "default" — its unset state means *inherit*, which the driver expresses by
removing the line, and `0` is not equivalent (it forces serial execution). Either put the same concrete thread count on
both passes, or delete that one line, comma and all, keeping `max_partitions_per_insert_block` and `log_comment`. Then
check what you are about to run — `grep -n '\${' <your-statements>.sql` must print nothing. The driver
does not implement the split, so those statements are hand-written, and the second arm
(`last_updated_at >= backfill_start AND created_at < backfill_start`) is the updates-to-old-rows arm that carries
far-future ids, so it is the pass that most needs `max_partitions_per_insert_block` and the easiest one to write without
it. The **deletion replay** is a lightweight `DELETE`, and with retention disabled it is user-scale — a single
mutation; `000002` / `000004` note how to bound it by partition if it is ever large.

## Why slice by `created_at` (and not `id` or workspace)

The backfill reads 100% of the table regardless of the slice column — the slice only decides how the work is *batched*,
and it does **not** decide where a row lands on the destination: that is always the honest weekly Monday of `id_at`,
derived from the row's `id`, independent of the slice. Three forces pick the slice column, and `created_at` is the only one that satisfies all:

- **Source read efficiency.** The source `traces` has a **minmax skip index on `created_at`** (migration 000088), so each
  week prunes granules cheaply. It has **no `id` skip index**, and `id` is the *trailing* primary-key column
  `(workspace_id, project_id, id)` — a bare `id`-range predicate cannot prune the primary index (leading key columns are
  free), so `id`-range slicing would **full-scan the table once per week**.
- **Bounded, complete iteration — where the bad-`id` data matters.** A known litellm bug
  ([BerriAI/litellm#31294](https://github.com/BerriAI/litellm/pull/31294)) minted some existing traces with UUIDv7 ids
  whose embedded timestamp is in the far future (year ~2201), so `id_at` ≠ `created_at` for those rows. 24h UUIDv7
  validation stops *new* offenders but does not fix rows already in the table. This makes `id` **unreliable and
  unbounded** as a slice key: an `id`/`id_at`-range loop would have to span from today all the way to ~2201 (thousands of
  empty weeks) to cover them. `created_at` is server-stamped and bounded to the real ingestion window, so `backfill.sh`
  iterates `toMonday(min/max(created_at))` — a finite, gap-free range — and the bad-`id` rows are still copied (in their
  `created_at` week) and still land in their (far-future) `id_at` partition. The bug is an argument *for* `created_at`.
- **Stable membership (data safety).** `created_at` is **immutable across upserts** on `traces` (the merge templates keep
  the original), so a row never migrates between weekly slices mid-backfill — none is copied twice or skipped.
  `last_updated_at` would *not* be safe here (it moves on every upsert, and is client-settable).

**On adding an `id` index (and destination write locality).** An `id` skip index on the source is *not* needed. The
delta uses the `created_at`/`last_updated_at` skip indexes, and the replay's outer DELETE matches the full primary key.
Its resurrection-guard subquery does read the source `traces` by bare `id` (which has **no** skip index — 000088 indexes
only `created_at`/`last_updated_at`; the `id` minmax/bloom indexes exist only on `traces_local_v2` per 000101), but the
`id IN (deleted-ids since anchor)` set is tiny (retention off → user-scale deletes), so it is a bounded id-filtered read,
not a full-table scan. An index still would not rescue `id`-slicing (the ~2201 span is a *data* problem, not an index one). Destination write locality
is naturally good with `created_at` slicing (`id_at ≈ created_at` once validation holds); slicing by *workspace* would
instead scatter each insert across every weekly partition that workspace spans → a small-part explosion on a large table.

**Far-future partitions from far-future-timestamp ids.** Some `id`s carry an embedded UUIDv7 timestamp in the far future
(litellm [BerriAI/litellm#31294](https://github.com/BerriAI/litellm/issues/31294) mints ~2201). The rows are legitimate
customer data — a valid UUIDv7 that merely carries a future timestamp — so they are copied and kept like any other.
`traces_local_v2` partitions by the honest `Date32` weekly Monday of `id_at`
([OPIK-7456](https://comet-ml.atlassian.net/browse/OPIK-7456): `toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))`),
and its `id_at` is a `DateTime64` (honest to 2299), so each such row lands in its **own honest ~2201 (`22010601`-shaped)
weekly partition**, isolated from real recent weeks — a per-week `DROP PARTITION` / retention / tiering operation never
touches them by accident, and vice versa. Once written, the extra partitions are benign at rest: they never tier to cold
and are skipped by time-bounded reads.

> **They are NOT few, and they break the backfill unless `max_partitions_per_insert_block` is raised.** An earlier
> version of this section claimed the extra partitions were "bounded (few distinct far-future timestamps → few extra
> weeks) and harmless". The first half is wrong on real data and the second half is only true *after* the copy
> succeeds. Measured on a production-shape environment (2026-08-17, 269.2 M rows):
>
> | Measure | Value |
> |---|---|
> | Far-future rows | **11,128,875** — 4.1% of the table, not a handful |
> | Distinct far-future weekly partitions | **1,517**, spanning ~2194 → 2299-12-31 |
> | Result of running `backfill.sh` unmodified | **`Code: 252 … TOO_MANY_PARTS`** on week `2025-06-16` |
>
> This is reproduced, not projected: the driver was run against the real cluster and aborted with
> `Too many partitions for single INSERT block (more than 100)`.
>
> **What drives it is the tail, not the volume.** In the failing window:
>
> | Measure | Value |
> |---|---|
> | Far-future partitions in the window | 275 |
> | …holding ≤ 5 rows each | **268** — about 635 rows in total |
> | Head partitions | 7, holding 125,553 of the window's 126,188 far-future rows |
> | Primary-key footprint of that rare tail | **12 projects** |
> | Worst single block: total destination partitions | **333** (269 far-future, the rest ordinary weeks it touched) |
>
> So the mechanism is: the byte cap `min_insert_block_size_bytes` (256 MB) binds long before
> `max_insert_block_size`, so for ~54 KiB trace rows a block holds only ~4,841 rows; and because the rare tail occupies
> a narrow primary-key range, one such block picks up most of those 268 partitions at once. ClickHouse caps partitions
> per block at **100** by default and, with `throw_on_max_partitions_per_insert_block = 1`, **aborts the INSERT**
> instead of degrading.
>
> **This survives parallelism, which is the counter-intuitive part.** The statement has no `ORDER BY` and the read is
> parallel (`max_insert_threads = 0`, `max_threads = auto(48)`), so it is tempting to assume 48 interleaved streams
> scatter the tail across many blocks and keep every block under the limit. They do not — the abort above happened
> under exactly that configuration. Do not reason your way past this one; measure it.
>
> **The abort is not all-or-nothing.** In the run above, 511,328 rows had already committed as 119 parts before the
> offending block threw. The destination is a `ReplacingMergeTree` keyed on `(workspace_id, project_id, id)`, so
> re-running the window converges rather than duplicating — but a failed window leaves partial data behind, and
> prerequisite #2 ("`traces_local_v2` is empty") no longer holds until it is cleared with `rollback.sh --stage A`.
>
> **No batching flag avoids this.** `backfill.sh` splits a week only by `created_at`, to respect
> `--max-rows-per-insert`; a week already under that bound is one unsplit INSERT however many partitions it spans (two
> such weeks failed in the measurement above). Lowering `--max-insert-block-size` does not help either, since the byte
> cap already binds. So the fix belongs in the setting: `backfill.sh --max-partitions-per-insert-block` defaults to
> **2000**. Pass the same value to `delta_replay.sh`, which needs it for the same reason: the delta writes into the same
> partitioned shadow, and its `last_updated_at` arm re-copies updates to old rows, so a far-future-id row touched during
> the window is pulled in. Where the migration user has a settings profile, set it there too, so the value does not
> depend on the invocation.
>
> **Why 2000 is sound, and it is not the simulation below that establishes it.** A block cannot span more partitions
> than the table has, so **the destination's total distinct partition count is a hard upper bound** on partitions per
> block. Size the setting above that total and it can never be exceeded, whatever the read order or thread count turns
> out to be. In the measurement above that total is about 1,616 (1,517 far-future plus roughly 99 real weeks), so 2000
> clears it with margin. Derive your own number the same way, from `far_future_weeks` plus the real week count, rather
> than from any per-block estimate.
>
> The observed worst block is consistent with that bound and shows why the far-future count alone is not the right input:
> its 333 partitions are 269 far-future plus 64 of the 99 real weeks, so a block's spread mixes both and lands well
> under the 1,616 ceiling. Sizing from `far_future_weeks` alone would have undercounted it by 64.
>
> The cost of raising it is a larger part count per insert — one part per partition touched — which background merges
> then compact. That is strictly better than the alternative, which is the backfill not running.

Quantify them in the **source** before the cutover so their scale is known. The source `traces.id_at` is a 32-bit
`DateTime` (migration 000091) that overflows for far-future values, so derive the timestamp from `id` via
`UUIDv7ToDateTime` (honest) rather than reading the stored `id_at`, and count distinct weeks with the same honest
expression the destination partitions by — a wrapped `toMonday(id_at)` would collapse several weeks into one and
undercount:

```sql
-- rows / distinct far-future weeks the far-future-timestamp ids occupy
-- (timestamp derived from id; the stored 32-bit traces.id_at wraps far-future values)
WITH UUIDv7ToDateTime(toUUID(id)) AS ts
SELECT count()                                                                 AS far_future_rows,
       uniqExact(toYYYYMMDD(toDate32(ts) - toIntervalDay(toDayOfWeek(ts, 1)))) AS far_future_weeks,
       min(ts) AS earliest, max(ts) AS latest
FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
WHERE ts > now() + INTERVAL 1 DAY;   -- outside the 24h validation window
```

`far_future_weeks` uses the destination's honest partition expression, so it equals the number of extra weekly partitions
`traces_local_v2` will hold. **This is the number that sizes `--max-partitions-per-insert-block`**, not merely a
curiosity: add it to the real week count and set the limit above the total, which is the hard bound argued above. If
`far_future_weeks` alone exceeds the default 100, the copy needs the raised setting or it will abort. Remediating the
source `id`s at their origin is the only thing that removes the extra partitions; short of that they partition honestly
on their own and the setting is what lets the copy through.

Because the failure is per **block**, not per week, the row count alone does not tell you whether a given window is
anywhere near the limit. The query below is an **approximate locality heuristic, not a preflight gate**: it answers "is
this window's far-future tail concentrated enough to be a risk at all", and nothing stronger. Read it with two
limitations in mind, or it will mislead you:

- It imposes `ORDER BY workspace_id, project_id, id` and chunks on `rowNumberInAllBlocks()`. The real `INSERT ... SELECT`
  has **no `ORDER BY`** and reads in parallel (`max_threads`, with `max_insert_threads` governing the sink), so its block
  composition is not this ordering and the numbers here are not the blocks ClickHouse will actually form.
- It does not reproduce the production INSERT's settings.

So use it to decide whether you are exposed, and use the total-partition-count bound above to decide the value. Do not
read `worst_partitions_per_block` as the minimum safe setting.

```sql
-- APPROXIMATE: is this window's far-future tail concentrated enough to be a risk?
-- Not a safe-value calculation — see the two limitations above.
SELECT max(p) AS worst_partitions_per_block, countIf(p > 100) AS blocks_over_default
FROM (
    SELECT intDiv(rn, 4841) AS b, uniqExact(part) AS p
    FROM (
        SELECT toYYYYMMDD(toDate32(UUIDv7ToDateTime(toUUID(id))) -
                          toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(id)), 1))) AS part,
               rowNumberInAllBlocks() AS rn
        FROM ( SELECT id FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
               WHERE created_at >= toDateTime64('<WINDOW_LO>', 9, 'UTC')
                 AND created_at <  toDateTime64('<WINDOW_HI>', 9, 'UTC')
               ORDER BY workspace_id, project_id, id )
    ) GROUP BY b
);
```

Derive the `4841` from your own data (`min_insert_block_size_bytes` ÷ uncompressed bytes per row, both readable from
`system.parts`) rather than reusing it — it is a property of row width, not a constant.

**No explicit `ORDER BY` on the `INSERT ... SELECT`.** Not needed for correctness or reproducibility: the final table
state is a `ReplacingMergeTree` reduction keyed on `(workspace_id, project_id, id)` with `last_updated_at` as the version
— **independent of insert order** — so any run converges to the same live rows; ClickHouse already sorts each insert
block by the destination `ORDER BY`, and since the source shares that key the rows arrive in order anyway; and
reconciliation uses order-independent `uniqExact` of the dedup key. An explicit `ORDER BY` would only add sort cost/memory
on a large backfill for no gain.

## Delta and replay correctness

**Delta anchor — `created_at OR last_updated_at ≥ backfill_start`.** The delta must re-copy everything written during the
(possibly multi-day) backfill:

- `last_updated_at` is **client-supplied** on the batch-ingest path (`TraceDAO.BATCH_INSERT` binds the request's value,
  server time only as a fallback), so `last_updated_at` alone can miss a row whose client stamped it in the past. But
  every write path sets **either** a fresh server `created_at` (the batch-ingest path leaves `created_at` to its
  `now64()` default) **or** a fresh server `last_updated_at` (the create/update merge paths preserve `created_at` but let
  `last_updated_at` default to `now64()`). The **union** therefore catches every physical write, whatever the client sends.
- The anchor is captured **before** the backfill, not at its end — a cutoff taken at the end would miss writes that
  landed during the backfill itself. The same `backfill_start` bounds the replay window.

**Replay matches on the full key `(workspace_id, project_id, id)`.**
`TraceService.delete(ids, projectId)` resolves each id's owning project(s) and deletes per project under the full key.
Since **OPIK-7483** there is no project-less path: an id that resolves to no owning project is absent (a delete of a
non-existent row) and is skipped, so **no deletion event is ever bridged with an empty `project_id`** for
`source_table='traces'` (a pre-cutover check asserts the bridge holds none — Prerequisites #13). The replay therefore
carries a single branch: full-key events delete by `(workspace_id, project_id, id)` — exact, prunes on the destination
primary key, and correct even though trace ids are not globally unique (a reused id deleted in one project leaves its
copies in other projects untouched). Without this replay, those during-window deletions would **silently leak** across
the swap.

**Resurrection guard.** A trace can be deleted and then re-created/updated under the **same id** during the window
(ids are client-supplied; the delete is a mask, and a newer insert wins under `FINAL`). Such an id is bridged as deleted
but is **live again** on the source, and the backfill/delta already copied its live version. So the replay also
requires the id is **not currently live on the source** (`AND (…) NOT IN (SELECT … FROM traces WHERE id IN <deleted ids
since anchor>)`, mask-honored) before deleting it — otherwise the replay would drop a row that is live on the source,
silent data loss. This also makes the replay idempotent (it never masks a live-on-source id), so re-running to
convergence is safe.

The replay runs with `allow_nondeterministic_mutations = 1` because it reads subqueries from `deletion_events_local` and
`traces`; those tables are replicated and identical on every node and the window is fixed, so the subqueries resolve to
the same set on every replica. It also sets `lightweight_deletes_sync = 2` so the statement returns only after the delete
mutation has applied on **every** replica — otherwise the async mutation could still be pending on a replica when the
verify or the EXCHANGE runs, giving a false mismatch or an incomplete cutover.

## How `backfill.sh` and `000001_backfill_traces_local_v2.sql` relate

They are **complementary, not alternatives**, and there is **no copy-paste drift**: the script *reads* the `.sql` file.

- **`000001_...sql` is the single source of the backfill `INSERT` (the "what"):** the exact statement, with `${...}`
  placeholders for the database, window bounds and block size. It is read by the driver, not run by hand.
- **`backfill.sh` is the driver (the "how"):** it derives the week range, and for each week reads `000001_...sql`,
  substitutes the placeholders, runs it, reconciles, throttles, and is resumable. It embeds no copy of the INSERT.
- **Keep the explicit column list in sync.** `000001`'s `INSERT` names each copied column explicitly (parallel `SELECT`,
  no `SELECT *`), so a column added to `traces` before a cutover is carried across **only if it is also added to this
  list and to the `traces_local_v2` shadow** (migration 000101, recreated by 000114). This is an incidental per-column
  edit that rides with the feature DDL; omissions are caught in CI by the schema-parity guard — `cutoverCopiesEveryBaseColumn`
  pins this list to the live `traces` base columns (OPIK-7772 extends it to a topology-aware CI check).

**Every SQL operation — happy path and every rollback stage — is run by a driver script; no SQL or `.sql` file is ever
run by hand.** Each `.sql` file is the single source a driver reads:

| Step | Reference SQL | Driver |
|------|---------------|--------|
| plan — backfill ETA | — | `estimate.sh` |
| 1 — backfill | `000001_backfill_traces_local_v2.sql` | `backfill.sh` |
| 2 — delta + replay | `000002_delta_and_deletion_replay.sql` | `delta_replay.sh` |
| 3 — EXCHANGE + wrap | `000003_exchange_and_wrap.sql` | `exchange_and_wrap.sh` |
| QA — fidelity compare (+ `--drill-down`) | `000005_verify_migration.sql` | `verify.sh` |
| rollback | `000004_rollback_stage_{a,b,c}_*.sql`, `000004_rollback_unwrap.sql`, `000004_rollback_reverse_replay.sql` + its postcondition `000004_rollback_verify_replay.sql`, `000004_rollback_sentinel_repair.sql` + its postcondition `000004_rollback_verify_sentinels.sql` | `rollback.sh` |
| finalize — retire the parked backup (drop after cutover / recycle to empty shadow after rollback) | — | `finalize.sh` |

Each driver takes the connection from the `clickhouse-client` env vars `CLICKHOUSE_HOST`, `CLICKHOUSE_USER` and
`CLICKHOUSE_PASSWORD`, plus `--database` and — when the native port is not 9000 — `--port`.

> **Pass `--host` and `--port` together; the env vars are not enough.** Verified on `clickhouse-client` 26.3:
> `CLICKHOUSE_PORT` is **not honored at all** (set it to a bogus value and the client still dials 9000), and
> `CLICKHOUSE_HOST` is honored **only while no connection flag is given** — so supplying `--port` alone silently reverts
> the host to `localhost`. Every driver therefore takes `--host` and `--port`; user and password stay in the environment,
> keeping the password out of `argv`. This matters for any real cutover, because a remote cluster is usually reached over
> a forwarded or tunnelled **non-default local port** (9000 is often already taken by a local ClickHouse). Pass the same
> `--host`/`--port` to every driver in the run.
>
> **The connecting user must be able to set `log_comment`.** Every driver tags its queries with `log_comment` for
> cutover attribution in `query_log`. A `readonly = 1` profile rejects that outright — `Cannot modify 'log_comment'
> setting in readonly mode` — so such a user cannot run **even the read-only drivers** (`estimate.sh`, `verify.sh`). Use
> `readonly = 2` for a read-only assessor (it permits `SET` but no writes), and a non-readonly profile for the migration
> user. This is worth checking before the window: an ops account that can happily run ad-hoc `SELECT`s may still fail
> every driver on the first query.
>
> **Every driver takes `--receive-timeout` (default 1800s).** ClickHouse's own `receive_timeout` is 300s and bounds the
> **gap between packets**, not total query time — so a long statement does not trip it on its own, but a step that goes
> quiet while the server works does, and the client then gives up on a healthy statement. That is why the default is
> raised across the board rather than per driver. The cost of a generous value is that a genuinely dead connection takes
> that long to surface; for resumable, idempotent steps that is the better trade.

### Timezones: every datetime literal pins `'UTC'`

The `traces` timestamp columns are `DateTime64(n, 'UTC')`, but a literal written without a timezone is parsed in the
**server** timezone — so on a non-UTC server the same statement means something different. Every literal in the
reference SQL therefore pins `'UTC'`, and where a bound is a value a driver captured, **the capture pins it too**:
`backfill.sh` mints `backfill_start` with `now64(6, 'UTC')` and `000002` reads it back as `'UTC'`; `exchange_and_wrap.sh`
does the same for `cutover_start`.

Both halves have to agree. Pinning only the literal reinterprets a server-local wall clock as UTC and moves the anchor
by the server's offset — and a *later* anchor silently drops the rows written in the gap, which the delta and the
deletion replay both miss because they share that bound.

Because that failure is silent, the persisted anchor carries the claim rather than relying on it: `backfill.sh` writes
`--state-file` with an explicit ` UTC` marker and **refuses a file without one**, since a bare timestamp cannot be
attributed to a timezone and step 2 would read it as UTC regardless. An anchor written by an older revision is therefore
rejected with the two ways out — re-record it with the marker if it is known to have been taken on a UTC server, or
restart the copy cleanly. The same reasoning is why both drivers now print their anchors labelled `UTC`: the value an
operator pastes into `--backfill-start` or `--cutover-start` says which zone it is in — and those flags **require** the
marker, so the guard cannot be bypassed by supplying the anchor by hand.

### Required privileges (provision these before the window)

The cutover should run as a **dedicated least-privilege user**, not as the app/admin account and not as a
read-only account. Two of these grants are **not guessable** — they were each found only by executing the
step against a real cluster, because a local rehearsal running as admin exercises no grant at all and the
read-only drivers cannot surface a mutation-privilege gap by construction.

| Step | Statement | Privileges ClickHouse actually checks |
|------|-----------|--------------------------------------|
| all drivers | any query | able to set `log_comment` → **not** a `readonly = 1` profile (`readonly = 2` for a read-only assessor) |
| `estimate.sh`, guards, settle gate | `SELECT` on `system.*`, `clusterAllReplicas(...)` | `SELECT ON system.*`, plus `REMOTE` and `CLUSTER` |
| backfill / delta | `INSERT INTO <shadow> SELECT … FROM <source>` | `SELECT` on source, `INSERT` on shadow |
| deletion replay | lightweight `DELETE FROM <shadow>` | **`ALTER UPDATE(_row_exists)`** on the shadow — *not* `ALTER DELETE`. A lightweight delete is implemented as `ALTER UPDATE _row_exists = 0`. Grant it **column-scoped** so the user can flip the delete mask without being able to rewrite any real column. |
| `EXCHANGE` | `EXCHANGE TABLES <source> AND <shadow> ON CLUSTER` | **`INSERT` + `CREATE TABLE` + `DROP TABLE` on BOTH names** — `INSERT` is required even though the swap is metadata-only and moves no rows. |
| post-swap `RENAME` | `RENAME TABLE <shadow> TO <backup>` | `CREATE TABLE` + `DROP TABLE` (grant `INSERT` on the backup name too, so the rename cannot trip the same check) |
| **wrap** (sharding) | `CREATE TABLE traces_dist … ENGINE = Distributed(…)`, then `RENAME traces → traces_local, traces_dist → traces` | `CREATE TABLE` + `DROP TABLE` on **`traces_dist`** and **`traces_local`** — two names that **do not exist yet**, so a grant set scoped to the cutover's three names will NOT cover the wrap. Plus `SELECT` on `traces_local` (post-wrap reads route through the wrapper to it) and `REMOTE` for the `Distributed` engine. |
| rollback stage A/B (if in scope) | stage A `TRUNCATE`; stage B 2-way `RENAME` + reverse replay | `TRUNCATE` on the shadow, and `ALTER UPDATE(_row_exists)` on the **source** (the reverse replay masks rows on the restored original). **Stage B also renames**, so it needs **`INSERT` + `CREATE TABLE`** on **`traces_post_rollback_backup`** — a destination that **does not exist yet**, so a set without `INSERT` fails `Code: 497` at the rename (see the four-privileges note below) — and **`SELECT` + `DROP TABLE`** on **`traces_pre_cutover_backup`**, its source. Note stage B is the *likelier* rollback, not the exotic one: the wrap is deferred by default, so the post-`EXCHANGE` resting state is the one stage B reverses, and reaching it needs no extra step. Withhold unless a rollback is actually planned. |
| rollback stage C (if the wrap is applied) | 3-way `RENAME` + `DROP` of the ex-wrapper | **`INSERT` + `CREATE TABLE`** on **`traces_dist_old`** and **`traces_post_rollback_backup`** — both `RENAME` destinations, so a set without `INSERT` fails `Code: 497` at the rename (see the four-privileges note below) — plus `DROP TABLE` on `traces_dist_old`, which is dropped after the rotation. `DROP TABLE` on `traces_local`, plus `ALTER UPDATE(_row_exists)` on the restored `traces`. **Decide this before applying the wrap:** without these grants there is no way back to the pre-cutover table until another grant change lands. (The *wrap itself* stays reversible via the un-wrap row below, which needs no extra grants — but that returns to the successor, not to the original.) |
| **un-wrap** (`--unwrap-only`, if the wrap is applied) | 2-way `RENAME` + `DROP` of the ex-wrapper | `CREATE TABLE`/`DROP TABLE` on **`traces`**, **`traces_local`** and **`traces_dist_old`**, plus **`INSERT` + `CREATE TABLE`** on **`traces_dist_old`** as the `RENAME` destination — a **subset of what stage C's statements require** (same source and destination names, minus `traces_pre_cutover_backup` and `traces_post_rollback_backup`), so a grant set that genuinely covers stage C covers this with nothing added. No `ALTER UPDATE` and no `TRUNCATE`: it promotes no backup and replays nothing. Grant it even when stage C is out of scope — it is the only wrap recovery once `finalize.sh` has dropped the parked original. |
| sentinel repair (`--sentinel-repair-only`, after a stage B/C promote **or** a cutover abandoned pre-`EXCHANGE`) | one `ALTER TABLE traces` carrying `UPDATE end_time = NULL …` and `UPDATE ttft = NULL …` | `ALTER UPDATE(end_time)` and `ALTER UPDATE(ttft)` on **`traces`** — **column privileges the rows above do NOT include.** The reverse replay needs only `ALTER UPDATE(_row_exists)`, so a user scoped to the rollback set gets `ACCESS_DENIED` here. Both commands travel in one mutation, so a missing grant on either applies neither. Either grant these two columns with the rollback grants (and revoke them after), or plan to run the repair as a more privileged user. |
| `finalize.sh` (if in scope) | `TRUNCATE` / `DROP TABLE` | `TRUNCATE`, `DROP TABLE`, and `max_table_size_to_drop` override |

> **`RENAME` and `EXCHANGE` check four privileges per name, not two.** Verified against a real server (26.3): a
> `RENAME TABLE a TO b` demands `SELECT` + `DROP TABLE` on the **source** and `INSERT` + `CREATE TABLE` on the
> **destination**; `EXCHANGE TABLES a AND b` demands all four on **both**. The rows above name the `CREATE`/`DROP` half
> because that is the half that is easy to miss on names that do not exist yet — but a user provisioned from those rows
> alone will fail with `Missing permissions: SELECT ON …` at the swap, since `SELECT` is granted above only on the
> *source* and never on the shadow. When provisioning, take each `RENAME`/`EXCHANGE` row as "all four on every name it
> touches", and confirm with a dry run rather than at the cutover moment.

**The boundary worth preserving.** For a *forward-only* cutover the user needs `INSERT` on the live source
(forced by `EXCHANGE`) but needs **no `ALTER DELETE`/`ALTER UPDATE` on it and no `TRUNCATE` anywhere** — so
it cannot delete or modify existing live rows, nor empty a table. The worst it can do to live data is add
rows. Keep it that way: grant rollback/finalize privileges only when those steps are in scope, as a separate
reviewed change.

**`clickhouse-client` is an operator prerequisite on the machine that runs these scripts.** Every driver invokes it and
reads the env above. It is a **client tool on the operator's host**, separate from ClickHouse itself, which the scripts
reach through `CLICKHOUSE_HOST` (a production cluster; a locally-exposed port in a rehearsal). Provide it, matching the
server's major version, either way:

- **Native (recommended for real migrations):** install the official ClickHouse client on the ops host; the bare
  `clickhouse-client` the scripts call then resolves to it.
- **Official image (host has Docker but no native client):** put a thin wrapper on `PATH` as `clickhouse-client` that
  runs the client from the official image over the network. A ready-made one ships as
  [`scripts/clickhouse-client-docker.sh`](scripts/clickhouse-client-docker.sh) — symlink it onto your `PATH`
  (`ln -s "$PWD/scripts/clickhouse-client-docker.sh" ~/bin/clickhouse-client`, with `~/bin` on `PATH`). It reuses the
  official `clickhouse/clickhouse-server` image (set `CLICKHOUSE_CLIENT_IMAGE` to your server version) and dials out to
  `CLICKHOUSE_HOST`; for a ClickHouse on the host's own loopback, add `--network=host` via `CLICKHOUSE_CLIENT_DOCKER_OPTS`.

**In the forward sequence, the only manual actions are not SQL:** (1) raising/restoring the async-insert buffer ceiling
(`databaseAnalytics.asyncInsertBusyTimeoutMaxMs`) around steps 2–3 — see
["Where the buffer bump lives"](#where-the-buffer-bump-lives-and-how-to-revert-it); (2) flipping
`databaseAnalyticsDataModel.traceColumnsNonNullable` to `true` in lockstep with the EXCHANGE (and back on rollback) —
see "The final cutover window"; (3) flipping `databaseAnalyticsDataModel.tracesDistributedWrapEnabled` around the wrap and
the un-wrap — see "Un-wrap"; and (4) the go/no-go judgement between steps. All four are *backend config* / judgement
changes (env + rolling restart, or a config push) that these DB-facing scripts cannot and should not make: the mechanism
is deployment-specific, so the drivers name the flag and the ordering and leave the rollout to the operator. They are
deliberately operator-owned, and none of them involves typing SQL.

**Recovery is where hand-run SQL does appear**, so the claim above is about the forward sequence and not about the whole
runbook. Two kinds, both deliberate and documented where they occur:

- **Procedural**, in one place only: the retry's `RENAME` of the parked backup back to `traces_local_v2` (see "Retrying
  the cutover after a stage B/C rollback"). It is unautomated on purpose — it reuses data whose trustworthiness may be
  the reason the rollback happened — so the operator runs it having read the guards.
- **Remediation the drivers print rather than perform**, when a guard refuses and the fix depends on facts the script
  cannot check: clearing a leftover `traces_dist_old`, and completing an interrupted forward `RENAME`.

All of it runs against the same database and connection the drivers use, and each statement is printed in full at the
point it becomes necessary — the drivers never withhold one, and never run it for you.

## Naming and the parked backup

Table names are the primary signal an operator acts on, so they encode which table is which — the safeguard against an
accidental `DROP` of the wrong (irreplaceable) table:

- **`traces`** — always the live table the app reads/writes (the original before the cutover; the successor after it;
  the `Distributed` wrapper after the wrap).
- **`traces_local_v2`** — the **working successor shadow**: created empty by migration 000101 and filled by
  backfill/delta before the EXCHANGE. It is disposable — stage A discards it, and it is never a `finalize.sh` target.
- **`traces_local`** — the successor's live shard after the wrap (standard `Distributed`-over-`_local` idiom).
- **`traces_pre_cutover_backup`** — **the displaced old original**, produced by renaming it immediately after the
  EXCHANGE. This rename is the whole point: leaving the old data under `traces_local_v2` would label the *oldest*,
  *sole-backup* copy with a `_v2` suffix that reads as "the newer table" — and, post-wrap, sitting next to the live
  `traces_local` it would invite dropping the wrong one. `traces_pre_cutover_backup` says exactly what it is and shares
  no stem with the live shard, so neither confusion is possible.
- **`traces_post_rollback_backup`** — **the abandoned successor**, parked here by a stage B/C rollback (the original is
  live again as `traces`). A distinct `_backup` name — not `traces_local_v2` — so a rolled-back estate is
  self-describing: it reads as a **retained backup** (kept for the soak, recover post-cutover writes from it), and stage
  A's shadow-discard cannot mistake it for the disposable `traces_local_v2` and truncate it.

The two `*_backup` names are the only retained backups and never co-exist. The one irreversible finalize step
(`finalize.sh`) retires whichever is present — **dropping** `traces_pre_cutover_backup` after a successful cutover, or
**recycling** `traces_post_rollback_backup` back into an empty `traces_local_v2` after a rollback (it is physically the
000101 shadow object, renamed) — and never touches the live `traces`/`traces_local`.

## Rollback

The full, ready-to-run rollback — including the **reverse deletion replay** so deletes don't resurrect — is pre-written
as one file per stage (`000004_rollback_stage_a_discard_shadow.sql`, `…_stage_b_exchange_back.sql`,
`…_stage_c_promote_original.sql`, the narrower `000004_rollback_unwrap.sql`, the shared
`000004_rollback_reverse_replay.sql`, and the tail's `000004_rollback_sentinel_repair.sql`) and driven by
[`scripts/rollback.sh`](scripts/rollback.sh), so no one authors it under pressure.

**Roll back only for a regression you cannot serve through.** The successor is the live table once the `EXCHANGE`
lands, so a fault in it is a production fault and the normal choice applies: fix forward, or reverse. Reverse when the
data or the read path is wrong in a way that harms users now — wrong or missing rows, absent-value semantics breaking
filters and sorts, a latency regression the product cannot absorb. Fix forward for anything you would fix forward in any
other feature: a slow query to tune, a dashboard label, a metric gone quiet, a bug with a known patch. Rolling back is
not the safer default — it discards post-cutover writes, runs the guard-less reverse replay, and returns the estate to
the unpartitioned original, so it costs more than most faults are worth.

Two things bound the decision rather than a stopwatch. The **window** is open only while the parked original exists —
`finalize.sh` closes it, and nothing reopens it (see "Point of no return"). And in practice the decision is made in the
hours after the cutover, while the soak is still fresh: the longer the successor serves traffic well, the less a rollback
buys and the more post-cutover writes it throws away. If the service is progressing, you are past needing this section.

**Reverse the smallest thing that fixes the problem.** The cutover delivers two independent changes — *partitioning* (the
`EXCHANGE`) and *sharding-readiness* (the wrap) — and they roll back separately. If only the **wrap** is at fault, use
`--unwrap-only`: it keeps the partitioned successor live, so there is no write loss, no reverse replay, no sentinel
repair, and no re-backfill to get forward again. Reach for stage B/C only when the **successor itself** is suspect.

**No data-bearing table is dropped by construction.** The stages are mutually exclusive, so each lives in its **own
file** — no single file mixes the `TRUNCATE` (stage A only) with the `EXCHANGE`/`DROP` of the others, and running any
file does exactly one stage. No statement drops a data-bearing table: swaps are atomic `EXCHANGE`/`RENAME`, and the only
`DROP` targets the `Distributed` wrapper, which stores no data (it is a routing definition over `traces_local`). Before
running, `rollback.sh` **asserts the live `traces` topology matches the requested stage or mode and aborts otherwise** — so a
wrong-stage run (the only way a `TRUNCATE`/`DROP` could hit the wrong table) makes no change. Stages B and C land in the
**canonical state**: `traces` = the original data (live), `traces_post_rollback_backup` = the successor data (parked as a
retained backup), retired only later by `finalize.sh` after the soak — which recycles it into an empty `traces_local_v2`,
restoring the pre-cutover, Liquibase-consistent estate. Stage A instead discards the shadow (`traces_local_v2` emptied)
and leaves the untouched live `traces` — there is no backup to soak or finalize. No leftover
`*_new` names.

> **Stages B/C make post-cutover writes non-live — an accepted, acknowledged trade-off.** Promoting the frozen
> `traces_pre_cutover_backup` means traces the successor accepted **after** `cutover_start` stop being served by the live
> table (the reverse-replay carries post-cutover *deletes* forward, but not *writes*). They are **not destroyed**: the
> successor is parked as `traces_post_rollback_backup` and retained until `finalize.sh`, so recover them from there during
> the soak if the rollback is later judged unnecessary. This is inherent to promoting a point-in-time backup and is *not* auto-repaired
> — merging the successor's post-cutover writes back would re-import the very data the rollback exists to discard. Because
> it is irreversible in the moment, stages B/C require `--accept-post-cutover-write-loss`, and `rollback.sh` prints the
> recovery pointer before the promote.

**Use exactly the `cutover_start` that `exchange_and_wrap.sh` printed** (`RECORD cutover_start=…`), and record it with
the run: it is an artifact *of* the forward run, not a value to derive afterwards. The driver captures it deliberately
**before** the final deletion replay and **before** the `EXCHANGE`, so the window covers every delete bridged from that
instant onward — including those bridged during the replay and during the swap itself.

Estimating it loses data in either direction. **Too early** and the guard-less reverse replay masks deletes that were
already applied to the original while it was live — including any id deleted and then re-created before the cutover,
which is legitimately live in the parked original. **Too late** — anchoring to the `EXCHANGE` rather than to the
capture — and every delete bridged between the two is missed, so those rows come back live on the restored original.
That gap is the whole run time of the final deletion replay.

**If the printed value was lost, stop and escalate.** Nothing recovers it in-procedure: statements come only from the
versioned `.sql` files the drivers read, and none of them reads this value back. Rolling back on an estimated boundary
destroys data or resurrects it, and both are worse than pausing.

Pick the stage by how far the cutover got:

- **Stage A — before EXCHANGE:** `./scripts/rollback.sh --database opik --stage A`. Discards the disposable shadow
  `traces_local_v2`; the live `traces` was never touched. (Guarded: aborts unless `traces` is still the original schema.)
  **"Untouched" is about rows, not values:** the flag was rolled out before the `EXCHANGE`, so traces written during
  that window carry sentinels and a negative `duration` in the live table, and stage A does not address them. Abandoning
  the cutover therefore still needs the sentinel repair below; retrying it does not, since the retry's copy heals them.
- **Stage B — after EXCHANGE, before wrap:** `./scripts/rollback.sh --database opik --stage B --cutover-start '<ts> UTC'
  --confirm-retention-paused --accept-post-cutover-write-loss`. `EXCHANGE` `traces_pre_cutover_backup` back to live
  `traces`, park the now-displaced successor as `traces_post_rollback_backup`, then the reverse replay. (Guarded: aborts
  if `traces` is `Distributed` — use C.)
- **Stage C — after wrap:** `./scripts/rollback.sh --database opik --stage C --cutover-start '<ts> UTC'
  --confirm-retention-paused --accept-post-cutover-write-loss`. Drops the `Distributed` wrapper, then one atomic
  `RENAME` promotes the original (`traces_pre_cutover_backup`) back to `traces` and parks the successor as
  `traces_post_rollback_backup`, then the reverse replay. (Guarded: aborts unless `traces` is `Distributed`.)
  **Set `databaseAnalyticsDataModel.tracesDistributedWrapEnabled` back to `false` before backends resume** — Stage C
  makes `traces` a `MergeTree` again and parks `traces_local`, so a still-`true` flag would send `TraceDAO` deletes at
  the missing `traces_local`. This is the inverse of the flip that enabled the wrap (see "HARD PREREQUISITE for the
  wrap"); it applies to every deferred `--wrap-only` topology, not the EXCHANGE-only default (where the flag was never
  set). The partition-metrics relabel reverses too: the `opik.clickhouse.partition.*` parts gauges move back from
  `table="traces_local"` to `table="traces"`, so restore any dashboards/alerts adjusted at wrap time.
- **Un-wrap — after wrap, when only the *wrap* is at fault:** `./scripts/rollback.sh --database opik --unwrap-only
  --confirm-maintenance`. Rotates the data-less wrapper out and `traces_local` back into `traces` in one atomic
  `RENAME`, then drops the ex-wrapper — landing in the post-`EXCHANGE`, pre-wrap state. (Guarded: aborts unless `traces`
  is `Distributed` and `traces_local` holds the successor schema.) See "Un-wrap" below for when to prefer it over stage C.
- **Sentinel repair — after a stage B/C promote, or after abandoning the cutover pre-`EXCHANGE`:**
  restores `NULL` on the rows the flag wrote into the still-Nullable original and recomputes their `duration`. **The
  window is mandatory** (see below). Which invocation depends on whether a promote parked the successor, because that is
  the only topological proof a cutover ran on this estate:
  ```bash
  W=(--sentinel-window-from '<flag rolled out, UTC>' --sentinel-window-to '<revert landed everywhere, UTC>')
  # after a stage B/C promote — traces_post_rollback_backup is the proof
  ./scripts/rollback.sh --database opik --sentinel-repair-only --confirm-flag-reverted "${W[@]}"
  # no parked successor: abandoned pre-EXCHANGE (incl. after stage A), or finalize.sh has recycled it
  ./scripts/rollback.sh --database opik --sentinel-repair-only --confirm-flag-reverted --confirm-flag-was-live "${W[@]}"
  ```
  The second asserts the flag was live here, because without the parked successor nothing in the topology or the data
  distinguishes an epoch `end_time` this flag minted from a value a client sent — and the repair rewrites the whole
  table. **Single shard only:** it mutates the shard it connects to while verifying across all of them, so it refuses on
  a multi-shard cluster and must be run once per shard. It also refuses when the shard count is **unreadable** — the
  postcondition reads `clusterAllReplicas('{cluster}', …)` and needs the same `system.macros` the count does, so a
  session that cannot read one cannot verify with the other, and proceeding would risk a whole-table rewrite that can
  never be certified. Where those reads are genuinely unavailable and the topology is known, `--confirm-single-shard`
  asserts it; it does **not** override a count that came back greater than 1. Separate from the stages by necessity, not
  preference: the config revert has to land on every instance first, and these scripts do not roll out config. **That is
  the only ordering that binds** — repairing while any instance still has the flag `true` lets it mint fresh sentinels
  behind the mutation. Stage A may run before or after, because it `TRUNCATE`s the shadow rather than dropping it, so the
  evidence the guard looks for survives. See step 2 of "Rolling back the `traceColumnsNonNullable` flip".

### Un-wrap: reversing sharding without reversing the cutover

Stage C is the *only* other statement that touches the wrap, and it bundles four actions — drop the wrapper, promote the
parked original, park the successor, reverse-replay. That is right when the successor is suspect and disproportionate
when it isn't: the wrapper holds **no data**, yet stage C abandons a validated backfill, makes post-cutover writes
non-live, runs the guard-less reverse replay, reverts to the unpartitioned original, and leaves the sentinel/`duration`
repair to do. `--unwrap-only` reverses just the sharding half.

| | stage B | stage C | `--unwrap-only` |
|---|---|---|---|
| Keeps the partitioned successor live | No | No | **Yes** |
| Post-cutover writes stay live | No | No | **Yes** |
| Runs the guard-less reverse replay | Yes | Yes | **No — not needed** |
| Sentinel + `duration` repair afterwards | Yes | Yes | **No** |
| Flags to revert | 1 + repair | 2 + repair | **1** |
| Needs `traces_pre_cutover_backup` | Yes | Yes | **No** |
| Available after `finalize.sh` | No | No | **Yes** |
| Re-backfill needed to go forward again | Only if the parked copy is not reused (see "Retrying the cutover") | Same | **No — never left the cutover** |

Two properties are worth being explicit about:

- **It is the only wrap recovery left after `finalize.sh`.** Stages B and C both require the parked original, which
  finalize drops. Since the documented order is wrap → soak → finalize, *post-wrap and post-finalize is the expected
  steady state*, and `--unwrap-only` is what covers a wrap fault discovered there.
- **It makes the wrap a switch rather than a one-way door** — `exchange_and_wrap.sh --wrap-only` applies it,
  `rollback.sh --unwrap-only` removes it, repeatably. **With one asymmetry, while the parked original is gone:**
  `--wrap-only` refuses when `traces_pre_cutover_backup` is absent, so on a **finalized** estate the un-wrap still works
  and is still repeatable, but *re-applying* the wrap needs that guard lifted first — a deliberate, separately reviewed
  decision, since it means wrapping an estate with no route back to the pre-cutover table. `rollback.sh` prints this
  instead of a command when it detects the case, rather than handing over an invocation that is certain to be refused.

**Do the DDL first, then the flag** — the inverse of the forward ordering, and for the same reason. Un-wrapping first
leaves trace deletes pointed at the now-absent `traces_local` (`Code 60 UNKNOWN_TABLE`) until the roll-restart lands;
reverting `tracesDistributedWrapEnabled` first instead would point them at a `traces` that is still `Distributed`, which
rejects mutations (`Code 36`). Both *flag* windows are **delete-path-only** — `TraceDAO` reads the flag only when
choosing its mutation table, so reads and inserts never consult it — which is what makes a short one tolerable.

The **DDL** window is separate and not delete-only. While the `ON CLUSTER` rename propagates, a lagging replica still
resolves the wrapper's `traces_local` target, which the already-renamed replicas no longer have, so a query routed there
can fail with `UNKNOWN_TABLE` — the exact mirror of the wrap's own window, where a `Distributed` query reaches a node
where `traces_local` does not exist *yet*. It is sub-second and fails loudly, but it touches **reads too**, so the
async-insert buffer alone does not cover it: quiesce traffic or take a maintenance window. That is what
`--confirm-maintenance` asserts.

`traceColumnsNonNullable` stays `true`: the live table is still the partitioned, sentinel-schema successor, which is
precisely what that flag asserts. Only stage B/C revert it, because only they restore the unpartitioned original.
Partition pruning needs no attention in either direction — it carries no flag at all; see
"Trace-delete partition pruning needs no flip at all".

**Monitoring reverses with it.** The `opik.clickhouse.partition.*` parts gauges relabel back from `table="traces_local"`
to `table="traces"`, so restore anything adjusted at wrap time. And if the wrap-time option to point
`PARTITION_METRICS_LWD_TABLES` at `traces_local` was taken (see "Monitoring consequence of the flip"), **revert it to
`traces`** — that table no longer exists after the un-wrap, so the LWD scan fails with `Code 60` and
`opik.clickhouse.partition.lwd_rows` goes silently empty while every other gauge returns. Installs left at the default
(`traces,spans`) need nothing.

**Scope limit.** This undoes sharding only. A fidelity defect in the successor, a partition-count or merge-load
regression, or a query regression from the new layout are all *cutover* problems — `--unwrap-only` changes none of them.
Use stage B/C while the parked original still exists.

> **Flag-vs-DDL ordering is not the same in both directions. This is the easiest thing here to get
> backwards.** Each step's own section states its order; the table exists so the asymmetry is visible in
> one place:
>
> | step | order | why |
> |---|---|---|
> | forward wrap (`--wrap-only`) | **toggle first**, then DDL | in the gap, deletes target a `traces_local` that does not exist yet → `Code 60`. DDL-first would send them at a `traces` that is already `Distributed` → `Code 36`, plus unbuffered cross-node skew |
> | un-wrap (`--unwrap-only`) | **DDL first**, then toggle | the mirror image: the gap gives `Code 60` again, which is the cheaper failure |
> | stage B / C | **DDL first**, then toggle | same reasoning as the un-wrap; the promote must land before the flags describing the new shape |
>
> Every **flag-transition** gap is delete-path-only — reads and inserts never consult the flag — so there
> the only question is which error it produces. The **DDL** interval is not: an `ON CLUSTER` rename is
> atomic per node, so cross-node skew can route a read at a replica that has already moved and fail it.
> That window is what `--confirm-maintenance` and read quiescence exist for; this table does not replace
> them.

> **Multi-replica note (production is multi-replica).** Stages B and C promote via a single `ON CLUSTER` RENAME of the
> **live** `traces`. It runs synchronously across the shard's replicas — the client blocks until each applies it, or fails
> loudly naming a laggard, which then converges via the DDL queue — so there is no durable mixed topology, only a brief
> sub-second cross-replica skew as it propagates, during which a read on a not-yet-renamed replica sees the pre-rollback
> `traces`. This is the same accepted `ON CLUSTER` skew as the wrap; on a multi-replica cluster run the rollback in a
> maintenance moment / with reads quiesced. `finalize.sh` is **exempt** — it renames only the parked backup / disposable
> shadow, never the live `traces`, so it has no live-read skew and needs no maintenance window.

**What the reverse replay can and cannot re-apply.** It re-applies the deletes the bridge **recorded**. Capture runs
after the delete succeeds and is best-effort by design — an auxiliary insert must never fail a user's delete — so a
delete whose bridge row has not landed yet, or whose capture errored, is invisible to the replay *and* to its
postcondition check, which reads the same bridge: that trace is live again on the restored original while the check still
reports `0`. No query here can detect it, so the bound is operational — **quiesce trace deletes before the promote**, not
just reads, and let in-flight ones land. It takes a delete concurrent with the promote, or a capture failure (which the
backend logs), so the exposure is small — but `0` means "every recorded delete is masked", not "no delete escaped".

**Recovering from an interrupted rollback.** Each promote stage runs its table-swap and then the reverse-replay as two
statements. Note what that means even when both succeed: from the moment the promote lands until the replay finishes,
the restored original is live with the post-cutover deletes **not yet re-applied**, so traces a user deleted after the
cutover are readable again. The window is the whole gap — the driver returning from one file and starting the next, plus
the replay's own run time — not just the replay. It is short for a rollback taken hours after the cutover, since only
deletes bridged since `cutover_start` are in scope, but it is a real exposure: keep reads quiesced from the promote
through the replay, not merely across the rename. A failure *between* the two needs a restart path:

- **Reverse-replay interrupted (stage B or C).** The promote already restored the original, so `traces` is back in the
  canonical shape and re-running the stage is (correctly) refused by the topology guard — which would otherwise leave the
  post-cutover deletes unreplayed and let them resurrect. Re-apply just the replay:
  `./scripts/rollback.sh --database opik --reverse-replay-only --cutover-start '<ts> UTC' --confirm-retention-paused`. It runs
  only `000004_rollback_reverse_replay.sql` and is idempotent (safe to run once or repeatedly). It refuses unless `traces`
  is the restored original (Nullable schema) with the successor parked as `traces_post_rollback_backup`, so it cannot be
  aimed at the live successor (post-EXCHANGE, pre-rollback), where the guard-less replay would mask live rows.
- **Forward EXCHANGE half-done (stage B says the backup is missing).** If the forward `EXCHANGE` succeeded but its
  post-swap `RENAME` did not, the parked original is still under `traces_local_v2` and stage B aborts pointing at the
  one-line `RENAME` that finishes it (`traces_local_v2` → `traces_pre_cutover_backup`); run that, then re-run stage B.

> **The repair is only meaningful on the restored original.** The parked successor stores an absent value
> as the epoch/NaN **sentinel** by design, so the same counts pointed there report *every* absent value as
> damage and invite a "repair" that would overwrite correct data. `--sentinel-repair-only` refuses unless
> `traces` is the Nullable original *with the successor parked* — that pairing is also what separates it
> from a pre-cutover table, where an epoch `end_time` is simply what a client sent.
>
> **Two windows put sentinels in the original, not one.** Writes land on it with the flag still `true`
> between the flip and the `EXCHANGE`, **and again from a stage B/C promote until the flag reverts on
> every instance** — the promote restores the Nullable original while backends still hold `true`. Count
> after the restart has landed everywhere, or the second window keeps refilling what you just repaired.
>
> **Nothing to repair is still a valid outcome.** If little or no traffic hit either window the counts are
> legitimately `0`; the mode reads them first and issues no mutation, which is why it is safe to run
> speculatively. A `0` is the success condition, not a sign the check is wrong.

**Rolling back the `traceColumnsNonNullable` flip.** After a stage B or C rollback, `traces` is the Nullable original
again, so the flip has to be undone in two steps — `rollback.sh` prints both when the stage finishes. The rollback is not
complete until they land. **After stage C specifically, `tracesDistributedWrapEnabled` must go back to `false` first**:
the stage removed the wrapper and parked `traces_local`, so a stale `true` aims trace deletes at a table that no longer
exists (`Code 60`). That is the second of the two flags the stage comparison table counts for stage C.

1. **Revert `traceColumnsNonNullable` to `false` AND roll-restart every backend instance.** The flag is read from a
   **startup snapshot** of `OpikConfiguration` (bound via `toInstance`), so a config change does **not** take effect until
   each instance restarts — exactly like the forward rollout before the EXCHANGE. Until the restart completes, the app
   keeps binding sentinels (epoch/NaN) and using sentinel-based absent-value logic against the now-Nullable column,
   mixing sentinel and `null` representations: not a hard write failure, but inconsistent absent-value
   reads/filters/sorts.
2. **Repair the sentinels written into the original** (see the caveats under "The `traceColumnsNonNullable` flip").
   Those rows carry `end_time = epoch` / `ttft = NaN` where the original's convention is `NULL`, and — because the
   original's `duration` expression epoch-guards `start_time` but checks `end_time` for NULL alone — a large **negative**
   `duration`. The promote made them live again and `finalize.sh` discards the successor's healed copy, so repair them
   here, **after** step 1 has landed on every instance or in-flight writes keep minting more:
   ```
   ./scripts/rollback.sh --database opik --sentinel-repair-only --confirm-flag-reverted \
     --sentinel-window-from '<flag rolled out, UTC>' --sentinel-window-to '<revert landed everywhere, UTC>'
   ```
   **Both window bounds are required, and there is no safe default.** An epoch `end_time` is not evidence the flag
   produced it: clients send them, and rows predating the flag hold them. Unbounded, the repair would set those to
   `NULL` with no way back — the parked successor encodes an absent `end_time` as that same epoch, so nothing holds the
   original — and the counts would still report success. Measured on an internal environment: the unbounded predicate
   matched 34 keys across 12 workspaces where only 5 came from the flag window. Take the bounds from when the flag
   rolled out and when its revert finished landing on every instance. Rows are matched on `created_at` **or**
   `last_updated_at`. Both bounds are interpreted as UTC regardless of the server's timezone.

   **One case the window cannot catch, and the gate cannot see.** `TraceDAO.UPDATE` re-inserts a version copying
   `created_at` and — when the patch omits them — `end_time`/`ttft` verbatim, while `last_updated_at` takes
   `DEFAULT now64(6)`. So a trace created *before* the window, patched *inside* it under the flag, then patched *again*
   after the revert has a live version carrying a pre-window `created_at` and a post-window `last_updated_at`, matching
   neither arm. It keeps its epoch `end_time`, and because the repair does clear the older in-window version the counts
   still reach `0` and report success.

   Extending `--sentinel-window-to` to the moment the repair runs closes that, at a cost worth stating rather than
   burying: a row holding a **genuine** epoch `end_time` that was merely patched inside the widened range then matches
   too, and is nulled irrecoverably. `end_time` is carried forward verbatim, so nothing in the data separates the two
   cases. Neither bound is safe in both directions — choose knowingly, and use the unbounded counts the driver prints
   alongside to see what a wider window would take in.

   It reads the counts first and issues no mutation when they are `0`, restores `NULL` in a single mutation
   (`000004_rollback_sentinel_repair.sql`) which recomputes `duration` as it rewrites each row, then asserts the counts
   reached `0` (`000004_rollback_verify_sentinels.sql`). It is idempotent. A bare `MATERIALIZE COLUMN duration` does
   **not** fix this — it re-evaluates the same expression against the same sentinel.

   **Success is `sentinel_end_time`, `sentinel_ttft` and `stale_duration` all reaching `0`.** The third is what catches
   a `duration` that was not recomputed when the row was rewritten: a negative duration on a row whose `end_time` is
   `NULL` cannot be produced by the materialized expression, and the other two counts read `0` either way once the
   sentinel is cleared, so nothing else would notice. `negative_from_sentinel` is informational, for sizing the damage
   before repairing. There is deliberately no total of negative durations: rows whose `end_time` genuinely precedes
   `start_time` are a pre-existing source artifact this repair does not address, so such a total never reaches `0` and
   waiting for it would look like a failed repair forever.

   > **It needs column privileges the rollback grant set omits** — `ALTER UPDATE(end_time)` and `ALTER UPDATE(ttft)`,
   > where that set carries only `ALTER UPDATE(_row_exists)`. Both commands travel in one mutation, so a missing grant on
   > either applies neither and nothing is half-repaired; the mode explains the `ACCESS_DENIED` if you hit it. Grant the
   > two columns alongside the rollback grants and revoke them afterwards, or run the repair as a more privileged user.

**When the rollback is done.** The stages leave the estate correct but not self-evidently so — the promote and the
replay report success independently of whether the result is consistent, and two of the steps are config rather than SQL.
Treat a stage B/C rollback as complete only when all of these hold:

- [ ] **Fidelity** — the bounded compare on the post-rollback pair passes, using the `--to-week` offset `rollback.sh`
      printed (see "Verifying after a rollback", including which mismatches inside the bound are benign and how to tell).
      If it printed no offset — every row sits in the cutover window's own week, so there is no earlier week to compare —
      this box is **not applicable**: `verify.sh` has nothing to bound to, and an unbounded run would report the
      cutover week's expected divergence as a failure. Rely on the next box instead, which does not depend on a window.
- [ ] **No deleted row resurrected** — `rollback.sh` printed `Reverse-replay postcondition OK`. It runs
      `000004_rollback_verify_replay.sql` after every replay (stages B/C and `--reverse-replay-only`); that file explains
      why the compare above cannot stand in for it, and what a `0` does and does not prove. A failure prints a `WARNING`
      rather than aborting — the promote has already succeeded and the guidance below still has to print — but the run
      **exits non-zero**, and names the `--reverse-replay-only` command to re-run. The replay is idempotent and the
      check repeats after it.
- [ ] **Flags reverted and the restart landed on every instance** — `traceColumnsNonNullable`, plus
      `tracesDistributedWrapEnabled` if the wrap had been applied. Those are the only two — partition pruning is
      unconditional and has no flag. Verify positively, not by absence of errors: absent `end_time`/`ttft` must read back
      as `null`.
- [ ] **Sentinel repair applied** — `--sentinel-repair-only` printed `Sentinel postcondition OK` and **exited zero**,
      **and the window passed is the one the flag was live in, in UTC**. The gate is `sentinel_end_time`,
      `sentinel_ttft` and `stale_duration` all at `0` *inside that window*; a residual `duration < 0` count elsewhere is
      expected, from rows whose `end_time` genuinely precedes `start_time`.
      **"Nothing to repair" is not interchangeable with a completed repair.** It is equally what a wrong window
      produces — bounds in local time being the common case — so check it against the unbounded counts the driver prints
      beside it before ticking this.
      **`finalize.sh` does not check any of this** — it has no notion of the repair, and reads no marker proving one
      ran with the right window. This checklist is the only control standing between a wrong-window no-op and
      `TRUNCATE TABLE traces_post_rollback_backup`, which retires the last reference copy. Treat the box as a human
      gate, because that is all it is.
- [ ] **The parked successor still parked** — `traces_post_rollback_backup` retained, not finalized. It is the only copy
      of the post-cutover writes the rollback discarded, and the only thing that makes a retry cheap.

Until the last box is ticked, do not run `finalize.sh`: it is what forecloses both going back and retrying cheaply.
`rollback.sh` prints that instruction last, after the steps it depends on, for the same reason.

**Retrying the cutover after a stage B/C rollback — without re-backfilling.** A rollback leaves the successor's data
parked as `traces_post_rollback_backup`, and the documented next step (`finalize.sh`) **truncates** it into an empty
`traces_local_v2`, so a naive retry starts from a full re-backfill. On a large table that is the difference between
minutes and days. The copy can be reused instead, because `traces_post_rollback_backup` **is** the same physical object
Liquibase created as `traces_local_v2`: a `ReplicatedMergeTree`'s replica path is fixed at `CREATE` and is unchanged by
renames — the same property `finalize.sh`'s recycle branch relies on. So renaming it back is a restoration, not a
reconstruction, and the estate stays consistent with the applied changesets.

This is deliberately **not** automated: it reuses data whose trustworthiness may be exactly why the rollback happened, and
it revives writes the rollback chose to discard. Run it only with the guards below understood.

1. **`finalize.sh` must not have run.** It truncates the parked backup — that is the point of no return for this path.
2. Restore the shadow name:
   ```sql
   RENAME TABLE <database>.traces_post_rollback_backup TO <database>.traces_local_v2 ON CLUSTER '{cluster}';
   ```
   It fails if `traces_local_v2` already exists — which is itself the signal that a retry cutover began before this
   rollback was finalized. Resolve that by hand rather than forcing it.
3. **The post-cutover writes the rollback discarded come back as live rows** once the retry's `EXCHANGE` lands. That is
   usually the point, but state it explicitly to whoever authorised the rollback.

   One exception, so nobody counts on the general form: an id that was **deleted and then re-created** after
   `cutover_start` does *not* come back. The rollback's reverse replay masked it on the restored original
   (deliberately guard-less), so on the retry the forward replay's resurrection guard sees it as not-live on the source
   and masks it on the shadow too. The delete is honoured; the re-creation is lost with the other discarded writes. Rare
   by construction, and not worth changing the replay for — the guard is right for the primary cutover path.
4. **Re-apply the `EXCHANGE`-window flags before the retry's `EXCHANGE`, exactly as for the first one.** The rollback told
   you to set `traceColumnsNonNullable` back to `false`, so it *is* false now; the retry puts the sentinel-schema
   successor back under `traces`, which needs it `true` and needs every backend instance restarted to pick it up (it
   comes from a startup snapshot). Skipping this is silent, not loud: absent `end_time` reads back as `1970-01-01` while
   writes keep succeeding. Raise the async-insert buffer for the window too. Nothing else needs flipping: trace-delete
   partition pruning carries no flag, so the retry's `EXCHANGE` needs no pruning step in either direction — see
   "Trace-delete partition pruning needs no flip at all".
5. Resume the normal sequence: `delta_replay.sh` with the **original** `backfill_start` anchor (the shadow still holds
   every row copied before it), then `verify.sh` before the `EXCHANGE`. That gate is what makes reuse safe — staleness or
   corruption in the reused shadow is caught exactly as in the first cutover — so do not skip it on the grounds that the
   data "was already verified once".

   **Bound it before the first cutover's window, and expect that week to differ.** The reused shadow is a *superset* of
   the restored original by exactly the revived writes from (3) — and those sit in the week the **original**
   `cutover_start` fell in, so an unbounded run reports them and looks like a fidelity failure on a perfectly good
   retry. Same shape and direction as the post-rollback compare above (the **new-table** side is the superset), so reuse
   the offset `rollback.sh` printed then: the last week wholly before that `cutover_start`. It also covers the live
   current week, which the restored original keeps writing to while the delta catches up.

   `--to-week last-sealed` is the wrong token here — it tracks the calendar, so a retry run in any later week stops
   excluding the window's own week. And the same caveat carries over: a write that touched a pre-existing trace during
   the first window diverges it in a *sealed* week, which no weekly bound excludes. Triage it the same way — look the
   differing ids up in the shadow without a week filter, and treat `last_updated_at >= cutover_start` as benign.

   Note the flags from (4) do not change what `verify.sh` compares: it normalizes both sentinel and `NULL`
   absent-values to the same fingerprint, so it passes either way. It cannot catch a missed flag flip — only a positive
   probe can, which is why (4) is a step and not a caveat here. Run that probe **after** the retry's `EXCHANGE`, on every
   instance: write an in-progress trace and assert `end_time` and `ttft` read back `null` rather than the epoch/`NaN`
   sentinel. Before the swap the restored original is still Nullable and answers `null` regardless, so a pre-EXCHANGE
   read-back would pass with a stale-`false` instance and leave exactly the silent wrong-reads state the flag exists to
   prevent.

   ```bash
   # N = the offset rollback.sh printed; old=traces, new=traces_local_v2 (the defaults)
   ./scripts/verify.sh --database opik --to-week <N>
   ```

If any of that does not hold, take the supported path: `finalize.sh` to recycle the backup into a clean shadow, then a
fresh backfill.

**Point of no return.** The `EXCHANGE` is reversible for as long as the parked backup exists (stage B/C). Retiring that
backup with `finalize.sh` is the one irreversible step, so gate it on an explicit soak. Note what it does and does not
foreclose: finalize ends the ability to return to the **original, unpartitioned** table, but the **wrap** stays
reversible indefinitely via `--unwrap-only`, which needs only `traces` and `traces_local`.

- **Soak duration** — keep the parked backup (`traces_pre_cutover_backup` after a successful cutover;
  `traces_post_rollback_backup` after a rollback) for a defined window (recommend ~2 weeks; it fits well inside the
  bridge's 2-year TTL) so any latent read/query regression surfaces while rollback is still an option.
- **Freeze `traces` schema DDL through the soak** (extends prereq #12 past the EXCHANGE). Rollback restores the **frozen
  original** `traces`, which carries no post-cutover DDL, so a column/index added to the successor in-window is **lost
  from the live table** on rollback; finalize's recycle then truncates the parked successor to an empty shadow (its data
  gone — the empty shadow keeps the added column, drift the next cutover's `cutoverCopiesEveryBaseColumn` guard flags).
  Do not deploy `traces` schema migrations until the soak ends (finalize committed). Post-finalize the general rule
  resumes: apply `ADD`/`DROP`/`MODIFY COLUMN` to **both** `traces_local` and the `Distributed` `traces` (see the wrap
  prerequisite).
- **Finalize exit criteria** — before retiring the backup: `verify.sh` clean, query p99 within budget over the soak, no
  cutover-related incidents open, and (if the wrap was applied) the retarget flag (`tracesDistributedWrapEnabled`) live
  and healthy across the backend fleet.

Once those hold, run [`scripts/finalize.sh`](scripts/finalize.sh) — it auto-detects whichever parked table is present
(`traces_pre_cutover_backup` or `traces_post_rollback_backup`), never the live `traces`/`traces_local` or the working
`traces_local_v2` shadow, and picks the action by case: after a **successful cutover** it **drops**
`traces_pre_cutover_backup` (committing to the new layout); after a **rollback** it **recycles**
`traces_post_rollback_backup` into an empty `traces_local_v2` (TRUNCATE + RENAME — discarding the successor data but
restoring the exact 000101 shadow, so the estate matches the applied Liquibase state and a retry starts clean). It is
dry-run by default, `--confirm` to act, refuses if the live `traces` looks empty while the backup does not, and refuses
if both parked names somehow exist (ambiguous — resolve by hand).

## Deletion bridge lifecycle & future migrations

`deletion_events_local` is a **shared, long-lived** table (migration `000096`), not per-cutover. It is designed so
each migration sees only its own deletes, regardless of what else it already holds:

- **Multiplexed by `source_table`** (`traces`, `spans`, …), which is the **leading `ORDER BY` key**. A replay filters
  `source_table = '<table>' AND event_time >= <backfill_start>`, so it prunes — as a prefix scan — past every other
  table's events and every event before its own anchor. A **non-empty bridge is the expected state** for the second and
  later migrations; correctness comes from that filter, not from the table being empty (the traces cutover starting
  empty was incidental).
- **Bounded** by monthly partitions (`PARTITION BY toYYYYMM(event_time)`) and a **2-year `TTL`**, so it cannot grow
  without limit. A cutover only needs events spanning its window (hours–days) plus the soak (~2 weeks), so the TTL has
  vast margin; shorten it only if the bridge ever runs hot under heavy delete volume.
- **Captured per source table** by independent knobs — `traceDeletionEventsCaptureEnabled` /
  `spanDeletionEventsCaptureEnabled` — so capture is scoped to the table being migrated.

**Capture is a per-migration, per-table lifecycle** — treat the knob like a valve around each cutover:

1. Turn capture **on** just before that table's backfill starts (so every in-window delete is recorded).
2. **Keep it on through the soak** — the rollback reverse-replay reads the bridge, so capture must stay live until you
   are past the rollback window.
3. Turn it **off after `finalize.sh`** — once the migration is committed and out of rollback range, its capture is an
   extra write per delete with no reader.

Because the knobs are independent, you never need both on at once: e.g. trace capture on for the traces cutover → soak →
finalize → trace capture off; later, span capture on for the spans cutover → soak → finalize → off.

**For a future migration (e.g. `spans`)**: reuse this exact machinery — the bridge and `SpanService`'s
`SourceTable.SPANS` capture already exist. Build parallel `spans-local-v2-cutover` artifacts mirroring these
(spans schema/columns, `source_table = 'spans'`) rather than generalizing the drivers into one tool: the SQL is
genuinely table-specific, and a parallel directory keeps each migration's runbook self-contained and reviewable. The
only discipline is operational — enable span capture before the span backfill, capture the span `backfill_start` once,
and disable capture after finalize.

## Per-deployment-variant notes

| Variant | Strategy | Notes |
|---------|----------|-------|
| Comet SaaS | Buffered cutover (this runbook) | Buffer absorbs the cutover window; bridge active through the soak. |
| On-premise enterprise | Buffered cutover | Same runbook; ships in the same Helm push. |
| Open-source Docker | Brief read-only window | Little data, downtime acceptable. Bridge still ships; the replay is a no-op when there were no concurrent deletes. If the Liquibase ClickHouse extension cannot run `EXCHANGE ON CLUSTER`, use the fallback `RENAME` sequence. |
| AWS SageMaker | Buffered cutover | Runs on its own cadence; the bridge ships ahead of the cutover. |

## Verifying the migration (QA)

Prove the copy altered no data by comparing a **normalized fingerprint** of source and destination with
[`scripts/verify.sh`](scripts/verify.sh) (reference query:
[`000005_verify_migration.sql`](scripts/db-app-analytics/000005_verify_migration.sql)). The rows are not byte-identical
after the copy — `end_time` NULL becomes an epoch sentinel, `ttft` NULL becomes NaN, timestamps drop from nanosecond to
microsecond — so both sides are canonicalized to the same value for a faithfully-migrated row before hashing: timestamps
as their microsecond epoch, absent `end_time` as 0, absent `ttft` as the token `nan`, enums/ids via `toString`. Each row
hash includes the `id`; rows are deduped with `FINAL` and the delete mask is honored, so the comparison is of the live,
logical content. The fingerprint intentionally covers only the **copied base columns**: the materialized/derived columns
(`*_length`, `truncated_*`, `output_keys`, `duration`) are recomputed from those bases by identical pinned expressions,
so they cannot diverge unless an expression itself changes — which the gate test's dedicated derived-column parity check
catches directly. Per week it compares `count()` and an order-independent `sum` of the row hashes — together these catch
any changed, missing or extra row (`sum`, unlike `groupBitXor`, does not cancel a colliding pair within a table). The row
hash is `cityHash64`, not `sipHash64`: both sides are hashed live on the same instance, so a fast non-cryptographic
64-bit hash is enough — `sipHash64`'s adversarial-collision resistance would only add CPU (it is the right choice for the
*sharding key* in the wrap, a different job). **This is the exact normalization the gate test asserts** (see below), so
the tool is proven correct, not just plausible.

```bash
# Full compare, every week, before the EXCHANGE (source=traces, dest=traces_local_v2 successor):
CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/verify.sh --database opik
# After the EXCHANGE: `traces` is the successor and the old data is parked as traces_pre_cutover_backup:
./scripts/verify.sh --database opik --old-table traces_pre_cutover_backup --new-table traces
```

> **A version tie makes a window undecidable, and the gate now says so rather than guessing.** If a key carries two or
> more rows with an identical `last_updated_at`, `FINAL` has no winner and the comparison for that key is arbitrary in
> both directions. Where the re-check would otherwise call the window an artifact, `verify.sh` counts those keys per
> side with the `version-ties` block and reports the window **INCONCLUSIVE**, exiting non-zero: not a mismatch, and
> explicitly not a pass. Resolving one is still manual — see "a version tie" under
> *Verifying after a rollback* for the version-set read, ignoring that section's `cutover_start` test, which has no
> meaning before the `EXCHANGE`.
>
> **Detach it, and expect tens of minutes.** The bounded compare walks one window per week over both
> tables. On a large table that is minutes per window on the busy weeks and well over half an hour in
> total, so run it under `nohup`/`screen` rather than an interactive shell that may be interrupted. It is
> read-only and idempotent, so an interruption cannot damage anything. It does **not** follow that a re-run covers the
> same windows: both bounds are read live from the old-schema table — `toMonday(min(created_at))` for the anchor and
> `max(created_at)` for the last week — so on a table still taking writes, or one that retention is pruning, the offsets
> move under you. Both are read once, at startup: rows written after that are outside the horizon the run computed,
> and are the delta's business rather than the compare's.
>
> **Resume with `--from-week`; do not restart from 0.** Idempotent does not mean free: a restart repeats
> every window already compared. Each window either reports a line or has not run, so the resume point is
> the last reported week plus the **stride** — plus one only at the default `--weeks-stride 1` — and the same
> stride must be passed again, or the resumed run samples different windows than the run it continues. The
> offsets are anchored as above, so confirm a resumed run's first window is the one you expect before treating its
> output as continuous with an earlier log — if the anchor has moved, the logs describe different windows and must not be
> read as one run.
>
> **Never resume the pre-`EXCHANGE` gate run — restart it.** That gate (see the exit checklist) requires one full compare
> with no narrowing, and it is the last backstop before an irreversible step. Two runs whose windows happen to add up are
> only equivalent if the anchor held throughout, which is not something anyone can confirm under pressure; a `PASSED`
> line now states the range it covered, so a stitched-together pass is visible rather than arguable. Resume is for the
> exploratory compares and the long post-rollback one, where the bound is deliberately partial anyway.
>
> **A mismatching week costs a second, slower query.** On `ok=0` the driver re-checks the differing keys on
> the sorting key to separate a real mismatch from a superseded-version artifact. That re-check can stall for
> longer than ClickHouse's 300s `receive_timeout` default even where the window compare did not, which aborts
> the whole compare at the first mismatching week. `verify.sh` therefore defaults to `1800`; raise it with
> `--receive-timeout` if a window still trips it.
>
> **Two steps need privileges the rollback grant set does not give.** Plan for them before the window,
> because both surface at the end when the pressure is highest:
> - the sentinel **count** is a full-table scan, so a read-only account carrying a `max_rows_to_read`
>   ceiling cannot run it;
> - the sentinel **repair** needs `ALTER UPDATE(end_time)` / `(ttft)`, which the rollback set deliberately
>   omits (see the privileges table).

**Verifying after a rollback.** After a stage B/C rollback the defaults do not apply — `traces_local_v2` no longer
exists (the successor is parked as `traces_post_rollback_backup`), so a bare `verify.sh --database opik` dies with
`Code: 60 … Unknown table … traces_local_v2`. The old-schema side is now the restored original and the new-schema side the
parked successor:

```bash
# rollback.sh prints this command with the bound already computed — prefer that over deriving the offset by hand.
./scripts/verify.sh --database opik --old-table traces --new-table traces_post_rollback_backup --to-week <N>
```

Expect the **cutover window's own week to mismatch**, by exactly the post-cutover writes the rollback discarded (the
parked successor holds them; the restored original never did) — so stop before it. Note the divergence is the
**opposite** direction from the post-EXCHANGE case: here the *new-table* side is the superset.

**Bound this one by `cutover_start`, not by the calendar.** `--to-week last-sealed` drops the current calendar week,
which is the window's week only while the verify runs promptly; run it in a later week and the window's week counts as
sealed, so its discarded writes read as a fidelity failure. `rollback.sh` prints the offset of the last week wholly
before `cutover_start`, which does not drift — use it.

**And a mismatch inside the bound is not automatically corruption.** Any write that touches a **pre-existing** trace
after `cutover_start` diverges it in a *sealed* week, which no weekly bound can exclude — the divergence sits where the
row was born, not where the write happened. Two mechanisms, opposite in shape:

- the **trace-update endpoint** keeps the row's original `created_at`, so the successor holds a newer version in that
  row's own week — the key differs **on both sides**;
- **batch ingestion** re-stamps `created_at` to now, so the successor's latest version moves to a later week — the key
  goes **missing from the successor** in its original week (`--drill-down` prints `\N` for that side).

Both are the discarded-write class, not a fidelity defect. Triage with `--drill-down`, then look each differing id up in
the parked successor **without** a week filter: `last_updated_at >= cutover_start` means benign. A key that is absent
from the successor *entirely* is the real signal — that is a copy gap, and it is the one shape worth stopping for. How
often this bites tracks how much pre-existing data the workload rewrites; for many it is none, which is why the weekly
bound is still worth passing.

**A third shape, which is detected but not resolved: a version tie.** This one is not rollback-specific — it
can hit the pre-`EXCHANGE` gate too (see "Verifying the migration"), where the `cutover_start` test below does not apply.

The re-check's premise — that filtering on the sorting key lets `FINAL` return the true winner — holds only while
versions differ. `last_updated_at` is the `ReplacingMergeTree` version column, so when two or more rows for a key carry
the **same** value there is nothing left to rank them by: `FINAL` picks arbitrarily, and because the two tables' part
layouts differ, each side may or may not land on the same row. **Arbitrary cuts both ways, and the second direction is
the dangerous one:**

- the picks differ, and the key is reported in `genuinely_differing_keys` even though both tables hold the same data;
- the picks coincide, and the key is confirmed as matching **even if one side is missing a version** — a real copy gap.

So a `0` from the re-check is conclusive only where no tie exists. `verify.sh` therefore asks exactly there: on a `0` it
runs the `version-ties` block, which counts per side how many keys in the window have a **non-unique newest
`last_updated_at`**, prints them as `version_ties=src:N/dst:N`, and reports the window **INCONCLUSIVE** (exit non-zero)
rather than as an artifact if either is non-zero. The counts are an upper bound — they cover the whole window, not only
the differing keys — which errs toward refusing to certify.

Deciding such a window is still manual, and `--drill-down` will not do it: it reads one `FINAL` row per key, so it shows
the arbitrary pick rather than the tie. Read the key's versions from both tables without `FINAL` — a read-only
diagnostic, not a procedure step:

```sql
SELECT 'src' AS side, created_at, last_updated_at, _part FROM <old-table> WHERE (workspace_id, project_id, id) = (…)
UNION ALL
SELECT 'dst' AS side, created_at, last_updated_at, _part FROM <new-table> WHERE (workspace_id, project_id, id) = (…)
ORDER BY side, created_at;
```

A **non-unique top `last_updated_at` on either side** means the comparison for that key was arbitrary. Then compare the
two version sets: identical sets mean the copy is faithful and only the tie-break differed; a version present on one
side only is the copy gap, whatever the re-check said. If the sets cannot be established, treat the week as unresolved
and escalate rather than passing it — arbitrary is not the same as benign.

> **The pre-EXCHANGE compare is the gate; the post-EXCHANGE compare has a caveat.** `traces_pre_cutover_backup` is a
> **frozen** snapshot as of `cutover_start`, but live `traces` keeps taking writes the instant the buffer drains — so
> the **current (live) week will legitimately show a mismatch** (the live table is a superset of the frozen backup by
> exactly the post-cutover writes). That is expected, not a leak. To use the post-EXCHANGE compare as a real check,
> either run it **immediately after the swap before writes resume**, or bound it to the **sealed historical weeks** with
> `--to-week N` (a **0-based week offset** from the anchor Monday, not a date — e.g. `--to-week 3` to stop before the
> current partial week), where a mismatch is worth investigating — with one exception in the same class as the
> post-cutover writes: a write touching a **pre-existing** trace after `cutover_start` diverges it in that row's own
> week, sealed or not (see "Verifying after a rollback" for the two shapes and the triage). Check
> `last_updated_at >= cutover_start` on the differing ids before calling it a defect. A leak shows up as rows present in
> the backup but absent from `traces` *and* absent from it entirely; post-cutover writes are the harmless direction.

**Feasibility at scale.** A full pass reads every partition (heavy but bounded per week — run off-peak). When that is
infeasible, sample and still get high confidence:
- `--sample-mod N` compares a deterministic 1/N `id` sample — the *same* rows on both sides, so like-for-like.
- `--weeks-stride S` compares every S-th weekly partition (partition-pruned, so genuinely cheaper).
- `--receive-timeout N` raises the client's per-packet wait (default 1800, against ClickHouse's 300). The
  post-mismatch confirm-keys re-check can stall past the stock value and abort the compare at the first mismatch.
- `--from-week` / `--to-week` bound the range by **0-based week offset** (integers from the anchor Monday, not dates;
  `--to-week` is inclusive) — e.g. verify the most recent weeks fully, older weeks sampled.

`verify.sh` exits non-zero if any window **mismatches or is INCONCLUSIVE**, and prints the window bounds either way;
re-run with `--drill-down` to list the keys that differ or exist on one side only (it runs the `drill-down` block of
`000005_verify_migration.sql` for every differing window, artifact and inconclusive verdicts included — those are the
ones most often worth reading).

## Verification — the automated test

`TracesLocalV2CutoverTest` rehearses this exact sequence against a fresh ClickHouse and asserts:

- **0 deletion leaks** across the EXCHANGE for deletes before backfill, a large retention-shape batch, and single
  user-shape ids;
- a **negative control** proving the bridge is load-bearing (the leak reappears when replay is skipped);
- **full-key replay** — a reused id deleted in one project survives in another (no over-delete by id alone);
- **resurrection guard** — an id deleted and then re-created under the same id during the window stays live on the
  destination (a naive replay-by-key would drop it — silent data loss);
- **delta completeness** — a row written during the window with a client-backdated `last_updated_at` is still caught (via
  the `created_at` arm) and survives the cutover;
- newest-version-wins for concurrent upserts;
- **normalized-fingerprint fidelity** — the deduped, mask-honored, normalized `(count, checksum)` of source and
  destination are equal before the swap (the same normalization `verify.sh` uses, so the QA tool is proven correct);
- **derived-column parity** — the recomputed columns (`id_at`, `*_length`, `truncated_*`, `output_keys` exactly;
  `duration` within the intended ns→us precision, `NULL`↔`NaN` normalized) match between source and destination, so a
  divergent MATERIALIZED expression is caught even though the base-column fingerprint excludes them;
- **schema-parity guards** — the cutover copies every base column of `traces`, and both tables expose the same base and
  materialized columns (a future migration that drifts either fails the build);
- `EXCHANGE TABLES ... ON CLUSTER` and the single-shard `Distributed` wrapper both work;
- **reversibility** — rollback at each stage (before EXCHANGE, after EXCHANGE, after wrap) restores the original and
  reverse-replays so a post-cutover delete does not resurrect;
- **wrong-stage rollback guard** — the topology signals `rollback.sh` keys on (the `traces` engine and `end_time`
  nullability) are distinct in each cutover state, so a mis-targeted stage aborts instead of touching the wrong table;
- the replay wall time is measured and logged (not asserted — it is environment-sensitive; the buffer-window sizing is
  done in the cutover rehearsal, not in CI).

**What it does not cover.** The suite drives SQL directly, so nothing in the `scripts/` drivers is exercised by it — they
need a `clickhouse-client` binary the backend test job does not install, and the repo has no bash harness. The cutover
rehearsal covers them instead: their argument validation, their topology guards, and three `verify.sh` behaviours worth
separating from the rest, because they decide a *verdict* rather than reject an argument — and a wrong verdict from a
fidelity gate is the failure this whole procedure exists to avoid:

- refusing to report `PASSED` when the bounds selected **no** window (an empty range compares nothing, so a pass would be
  vacuous);
- the `--to-week last-sealed` resolution — the current-calendar-week bound, capped at the last populated week;
- its refusal when that resolution lands before `--from-week`, the all-data-in-the-current-week case.

Each rests on manual verification. Exercise them in the rehearsal alongside the driver guards, and treat a change to any
of the three as needing the same.

Run it with: `mvn -o test -Dtest=TracesLocalV2CutoverTest` from `apps/opik-backend`.

## Monitoring and abort criteria

Watch these for the whole backfill→EXCHANGE window; wire alerts before starting, not during:

- **Free disk per volume** (`system.disks`) — the backfill is a full second copy; alert well before any volume fills.
- **Active part count / merge backlog** (`system.parts`, `system.merges`) on `traces_local_v2` — a runaway part count
  means merges are not keeping up; increase `--pause-seconds`.
- **Replication backlog** (`system.replication_queue`) and **mutations** (`system.mutations` `is_done = 0`) — must trend
  to zero; a growing queue means a replica is falling behind.
- **Ingestion latency / error rate** — with the widened buffer, insert latency rises by design (up to the buffer window);
  alert on client-side timeouts or ingestion errors, which mean the client timeout is below the buffer.
- **Query p99** on the project traces listing — the backfill competes for I/O; a sustained regression is an abort signal.
- **Deletion-capture health** — capture is best-effort and **swallows** errors (so a bridge hiccup never blocks a user's
  delete), so watch the backend logs for `captureDeletions` failures. A silently-dropped capture would leak a delete.
  `verify.sh` catches that as a pre-EXCHANGE week mismatch (the row is live on the destination but gone on the source)
  **for any capture failure up to the last pre-EXCHANGE verify** — but a capture that fails in the final
  `exchange_and_wrap.sh` window (after the last verify, through the swap) is caught by neither `verify.sh` nor the final
  deletion replay, only by this log-watch. So it is an early-warning signal, not a silent hole: treat repeated failures as
  an abort signal until capture is healthy, and treat **any** `captureDeletions` failure observed from the last verify
  through the EXCHANGE as a swap-gating signal.

**Roles.** Name an operator (runs the scripts), an independent observer (watches the dashboards), and the person with
authority to call a rollback. **Abort thresholds** (decide the numbers up front): free disk below the per-volume alarm,
query p99 beyond the agreed budget, or replication backlog that will not drain. Aborting before the `EXCHANGE` is
cheap (stage A); the bridge stays enabled so nothing is lost on a retry.

## Go / No-Go checklist (production cutover)

- [ ] **Runbook rehearsed on a production-shape staging snapshot** end-to-end; timings recorded. Staging must match
      production **topology**, not just data shape — same replica count and tiered-storage policy — since the
      multi-replica settle gates, storage/TTL parity, and buffer-flush timing are otherwise untested until production.
- [ ] **Deletion test green** — `TracesLocalV2CutoverTest` passes; **0 deletion leaks** confirmed on staging.
- [ ] **Final-delta→EXCHANGE gap fits inside the buffer hold with margin** — the binding invariant is the gap between
      the final delta and the EXCHANGE completing (≈ replay wall time + EXCHANGE), staying within the buffer hold and
      accounting for size-triggered flushes — **not** "replay < buffer window" alone (see "The final cutover window").
- [ ] **Far-future partitions quantified — and `max_partitions_per_insert_block` sized from the result.** Run the
      bad-`id` audit query above; remediated or explicitly accepted. The count is not just informational: if
      `far_future_weeks` exceeds the ClickHouse default of 100, the backfill **aborts** without a raised
      `--max-partitions-per-insert-block` (driver default 2000), because the far-future rows cluster into a single insert
      block. Size the value from `far_future_weeks` plus the real week count, which is a hard upper bound on partitions
      per block, and pass the same value to **both `backfill.sh` and `delta_replay.sh`** — the delta writes into the same
      partitioned shadow and aborts the same way, immediately before the EXCHANGE. Set it on the migration user's
      settings profile too, so it does not depend on the invocation.
- [ ] **`EXCHANGE TABLES ... ON CLUSTER` works end-to-end** — or the fallback `RENAME` sequence is documented for the
      variant that needs it.
- [ ] **Async-insert ceiling confirmed** — raising `asyncInsertBusyTimeoutMaxMs` demonstrably widens the adaptive buffer
      under load, not just the cap. `exchange_and_wrap.sh` enforces the acknowledgment via `--confirm-buffer-raised`, but
      that is an assertion only — this checklist item is the actual "it took effect under load" verification.
- [ ] **The buffer-bump and revert changes are pre-written and reviewed** — the config entry raising
      `ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS` (with `traceColumnsNonNullable = true` alongside it) and the revert
      that *deletes* the key, both prepared before the window so each is a merge rather than an edit under pressure.
      Confirm the chosen ceiling is below `terminationGracePeriodSeconds`, and that you know how a backend restart is
      triggered on the target deployment and that it fits the schedule — see
      ["Where the buffer bump lives"](#where-the-buffer-bump-lives-and-how-to-revert-it).
- [ ] **Data Retention confirmed disabled** for the cutover window (`RETENTION_ENABLED=false`). Retention deletes bypass
      the deletion bridge, so a sweep in the window would leak/resurrect across the swap; `exchange_and_wrap.sh` and
      `rollback.sh` (stages B/C) enforce `--confirm-retention-paused`, but that is an assertion — this item is the real
      "it is actually paused on every backend" verification.
- [ ] **Reconciliation clean** — per-window source/dest counts within 0.01% across the whole backfill.
- [ ] **Replication settled before the EXCHANGE** — `replication_queue` empty and the deletion-replay mutation
      `is_done` on **all** replicas (`exchange_and_wrap.sh` gates on this; do not `--force` past it in production).
- [ ] **`traceColumnsNonNullable = true` rolled out to every backend instance before the EXCHANGE** — confirmed live on
      the whole fleet by a **positive** check, not by the absence of ingestion errors: write an in-progress trace (no
      `end_time`, and so no `ttft`) through the API and assert the epoch/NaN **sentinel** was stored for both — then
      repeat it as a read-back (`null`, not the sentinel) **after** the EXCHANGE, because until the swap the Nullable table
      answers `null` either way and the read-back proves nothing. A stale-`false` instance still writes
      correctly (`input_format_null_as_default` converts the `null` bind to the sentinel) and logs nothing — it just
      serves wrong absent-value reads/filters/sorts. Revert plan to `false` ready for rollback, **plus** the pre-swap
      sentinel/`duration` repair (see "Rolling back the `traceColumnsNonNullable` flip").
- [ ] **Schema-parity guards green** — `cutoverCopiesEveryBaseColumn` and `successorMaterializedColumnsMatchSource` pass
      on the release, so the cutover copies every base column of `traces` and the two tables' base and materialized
      columns match.
- [ ] **Fidelity verified** — `verify.sh` passes between source and destination before the EXCHANGE. This gate MUST be a
      **full compare** (`--sample-mod 1 --weeks-stride 1`, no `--from-week`/`--to-week` narrowing): it is the last backstop
      for a single-row deletion leak — an unexpected empty-`project_id` bridge event the single-branch replay would miss,
      or any other single-key divergence — and any sampling (`--sample-mod > 1`), week stride, or week narrowing can hash
      that one row out and still report `ok=1`. Reserve sampling/ranged runs for follow-up confidence *after* the full gate
      passes. Re-run `delta_replay.sh` then `verify.sh` until it PASSES: while the buffer holds writes (or, on a rehearsal
      without it, once traffic is quiescent) the last delta must catch every in-flight write.
- [ ] **`Distributed` wrap gated on the DAO toggle** — apply the wrap (step 4, part 2) only once
      `databaseAnalyticsDataModel.tracesDistributedWrapEnabled=true` is live across the backend fleet (OPIK-7455), set in
      lockstep with the wrap so trace mutations target `traces_local`; otherwise stop after the `EXCHANGE`, since a
      lightweight `DELETE` against a `Distributed` `traces`
      is unsupported and breaks the trace-delete path.
- [ ] **No query-semantics regression** — FINAL / `LIMIT 1 BY` dedup verified; p99 on the project traces listing page within
      ±10% of the pre-migration baseline.
- [ ] **Rollback rehearsed at every stage** (before EXCHANGE, after EXCHANGE/before wrap, after wrap) — deletes during
      the post-cutover window do not resurrect after the reverse-replay; the parked table is retained for the soak.
- [ ] **Go/No-Go decision recorded** with the staging evidence attached.
