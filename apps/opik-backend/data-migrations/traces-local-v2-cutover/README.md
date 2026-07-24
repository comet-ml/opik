# Buffered cutover runbook — `traces` → partitioned + sharding-ready

Operator runbook for the buffered cutover of the ClickHouse `traces` table: it migrates the live, unpartitioned
`traces` table to `traces_local_v2` (weekly-partitioned, denullified, `is_deleted`-ready) with **near-zero downtime**
and **zero deletion loss**, then wraps it in a sharding-ready `Distributed` table.

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
> deletion all funnel through `TraceService.delete(...)`, which calls `captureDeletions` on both the resolved-project
> and the unresolved (empty-project) branch — so enabling the flag covers every one. The **only** uncaptured
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
   weeks. This is not tied to a retention cycle (retention never runs — prereq 8). Pre-validation bad-id rows already in
   the table are *not* blocked by this: they are copied by the `created_at` slice and surfaced by the far-future audit
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
   again. **This is operator-verified, not script-enforced:** the ceiling is a backend per-query setting applied on the
   backend's own ClickHouse client, so it is not visible to the migration scripts' direct `clickhouse-client` session —
   they cannot gate on it. Confirm it took effect on the prod-clone/staging load test (this is the Go/No-Go "Async-insert
   ceiling confirmed" item), before the write-facing stages run in production. **Also confirm client/SDK insert timeouts
   exceed the widened buffer** (~10s) — with `wait_for_async_insert=1` a raised ceiling blocks each insert until it
   flushes, so a shorter client timeout would surface as ingestion errors during the window.
7. **Schema-state flag wired, with a rollout plan** — `databaseAnalyticsDataModel.traceColumnsNonNullable` (env
   `ANALYTICS_DB_DATA_MODEL_TRACE_COLUMNS_NON_NULLABLE`, default `false`). The successor's `end_time`/`ttft` are
   **non-nullable sentinel** columns, so the app must bind epoch/NaN instead of `null` once they are live — a `null`
   bind is rejected. This flag switches that (writes, reads, filters, sorts); it **must be flipped in lockstep with the
   EXCHANGE** (see "The final cutover window"). Confirm it is deployable on the target (env passthrough present) and that
   you have a fleet-wide rollout mechanism (config push or rolling restart) ready.
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
12. **Freeze concurrent DDL on `traces` for the window.** Hold any deploy or Liquibase changeset that would `ALTER`,
    `RENAME`, or otherwise touch `traces` / `traces_local_v2` for the whole backfill→EXCHANGE window — a schema change
    landing mid-cutover races the swap and can corrupt it. The revamp's own migrations (000096/000101) are already
    applied; this is about *unrelated* migrations or ad-hoc DDL during the window.
13. Schedule during off-peak hours.

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
2. **Raise the buffer ceiling** (config, see below), then **[`scripts/delta_replay.sh`](scripts/delta_replay.sh)**
   (reference SQL [`000002_delta_and_deletion_replay.sql`](scripts/db-app-analytics/000002_delta_and_deletion_replay.sql))
   — delta-insert (anchored at `backfill_start`), then **deletion replay**. The replay runs with
   `lightweight_deletes_sync = 2`, so it returns only once the delete mutation has applied on **every** replica.
   clickhouse-client prints each statement's wall time; record the replay's wall time.
   ```bash
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/delta_replay.sh --database opik --backfill-start '<ts>'
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
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/exchange_and_wrap.sh --database opik
   ```

> **HARD PREREQUISITE for the wrap (step 4, part 2): the delete/mutation DAO must target `traces_local` first.** A
> `Distributed` table supports `SELECT` and `INSERT` but **not** mutations. Verified on ClickHouse 26.3:
> - `DELETE FROM <distributed>` → `Code 36 BAD_ARGUMENTS: DELETE query is not supported`
> - `ALTER TABLE <distributed> DELETE` → `Code 48 NOT_IMPLEMENTED: Distributed doesn't support mutations`
>
> So the moment the wrap is applied, **both** the product's delete-by-id (`TraceDAO.DELETE_BY_ID`) **and** the retention
> sweep (`DELETE_FOR_RETENTION`) start returning 500 against `traces`. This is prep work that must ship **before** the
> wrap: point those DAO paths at `traces_local` (reads and inserts can stay on the Distributed `traces`). The `EXCHANGE`
> alone is the data cutover and leaves `traces` a `MergeTree` where deletes still work — which is why the wrap is
> **opt-in** (`--with-wrap`) and the default stops after the EXCHANGE. Defer the wrap until the sharding-aware DAO
> ships. The wrap is the sharding-readiness layer, not the cutover.
>
> **Applying the deferred wrap later:** once the sharding-aware DAO has shipped, run
> `exchange_and_wrap.sh --database opik --wrap-only --confirm-maintenance` — it runs the settle gate and applies **only**
> the wrap on the already-swapped `traces` (no second EXCHANGE, no new `cutover_start`). To roll the wrap back, use
> `rollback.sh --stage C`.
>
> The wrap is **gapless per node**: it pre-builds the `Distributed` wrapper under a temp name, then one atomic
> multi-target `RENAME` rotates the data to `traces_local` and the wrapper into `traces`, so `traces` is never absent on
> a node. A brief **cross-node** `ON CLUSTER` propagation skew still exists (as for any `ON CLUSTER` DDL), during which a
> Distributed query could route to a not-yet-created `traces_local` on a lagging node — so the deferred `--wrap-only`
> path still **requires `--confirm-maintenance`** (re-raise `asyncInsertBusyTimeoutMaxMs` / quiesce ingestion / take a
> maintenance window). The same-run `--with-wrap` path is already covered by the still-raised EXCHANGE buffer.
>
> **Wrap flags** (`exchange_and_wrap.sh`, mutually exclusive; default is EXCHANGE-only): omit them (or pass
> `--skip-wrap`, an explicit alias) to run the EXCHANGE and stop; `--with-wrap` to also apply the wrap in the same run;
> `--wrap-only` to apply just the deferred wrap later.

**Dedup note.** After the delta, a row can have two physical versions on `traces_local_v2` (the backfilled one and the
delta one). This is normal — `ReplacingMergeTree` collapses them on merge / under `FINAL` / `LIMIT 1 BY id`, highest
`last_updated_at` winning. Do not "fix" it.

### The final cutover window (the zero-loss invariant)

The buffer widening (prereq 6) is what makes the flip lossless, but the guarantee rests on a timing invariant worth
stating precisely. Writes use `async_insert=1, wait_for_async_insert=1`, so a raised `asyncInsertBusyTimeoutMaxMs` parks
each insert (the client blocks) until it flushes — and after the `EXCHANGE` a parked insert flushes into whatever table
is now named `traces`, i.e. the successor. **But the adaptive buffer also flushes on size**, so under load a flush can
still land in the *old* `traces` in the gap between the last delta read and the `EXCHANGE` — and the delta has already
run. The binding constraint is therefore **not** "replay < buffer window"; it is that the **gap between the final delta
and the `EXCHANGE` completing must stay within the buffer hold**. So run the tail as tightly as possible:

1. Widen the buffer, and **roll out `traceColumnsNonNullable = true` to every backend instance** (see below).
2. Do the QA verify on an **earlier** pass (it can take minutes on a large table — do not let it be the last thing
   before the swap).
3. Run a **final** `delta_replay.sh` as the last write-facing step.
4. Run `exchange_and_wrap.sh` **immediately** after it (the settle gate + `EXCHANGE` are fast and metadata-only).
5. Restore the buffer ceiling; parked inserts flush into the successor.

Keep step 3→4 short. If load is high enough that a size-triggered flush is plausible in that gap, re-run the final delta
once more right before the swap. Everything up to `cutover_start` is covered by the delta+replay; the buffer covers the
instant of the flip.

**The `traceColumnsNonNullable` flip (mandatory, and why it goes first).** The successor stores `end_time`/`ttft` as
non-nullable epoch/NaN sentinels; the app must bind those sentinels — not `null` — the moment that schema is live under
the name `traces`, or every write of an in-progress trace (no `end_time` yet) is **rejected** by the non-nullable
column. The flag switches the app to sentinel binds (and sentinel→`null` on read). It is a **config** change rolled out
across the fleet (not atomic), unlike the metadata-only `EXCHANGE`, so it cannot be flipped at the same instant. Roll it
out to `true` on **all** instances **before** the `EXCHANGE`: binding the epoch/NaN sentinel into the *still-Nullable*
source column is valid, and reads translate epoch/`null`→`null` either way, so `true` is write-safe on both schemas —
this removes any write-rejection window. The copy machinery already tolerates the resulting NULL/epoch mix (backfill
`coalesce`, verify normalizes both to `0`). The one transient caveat is that "`end_time` is empty"-style **filters** use
sentinel logic against the still-Nullable table during that short pre-swap window; keep the window short and off-peak.
On rollback, after swapping the Nullable original back, revert the flag to `false`.

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
statement (with the same block-size bound); `000002` documents how to split it into two batched passes if a long backfill
made it large. The **deletion replay** is a lightweight `DELETE`, and with retention disabled it is user-scale — a single
mutation; `000002` / `000004` note how to bound it by partition if it is ever large.

## Why slice by `created_at` (and not `id` or workspace)

The backfill reads 100% of the table regardless of the slice column — the slice only decides how the work is *batched*,
and it does **not** decide where a row lands on the destination: that is always `toMonday(id_at)`, derived from the row's
`id`, independent of the slice. Three forces pick the slice column, and `created_at` is the only one that satisfies all:

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
instead scatter each insert across every weekly partition that workspace spans → a small-part explosion on a 4 TB table.

**Known issue — far-future partitions from bad ids.** Because the destination partitions by `toMonday(id_at)`, the
bad-`id` rows create far-future weekly partitions on `traces_local_v2` (inherent to the DDL + the bad data, not to the
slice choice). Note the year: the ids' embedded UUIDv7 timestamp is ~2201, but `id_at` is a **32-bit `DateTime`** (max
year 2106), so `UUIDv7ToDateTime` overflows and **wraps ~2201 to ~2065** — that is where the partitions actually land.
Either way it is bounded (few distinct bad timestamps → few extra partitions) and mostly harmless (those partitions
never tier to cold and are skipped by
time-bounded reads); the audit query below finds them regardless of the exact year (it keys on `id_at` being in the
future, not on a specific year). Quantify it before
the cutover and decide whether to remediate:

```sql
-- rows / distinct far-future partitions the bad ids would create
SELECT count() AS bad_rows, uniqExact(toMonday(id_at)) AS bad_partitions, min(id_at) AS earliest, max(id_at) AS latest
FROM ${ANALYTICS_DB_DATABASE_NAME}.traces
WHERE id_at > now() + INTERVAL 1 DAY;   -- outside the 24h validation window
```

If the count is material, remediate the source ids (or exclude/quarantine those rows) first; otherwise accept the few
far-future partitions.

**No explicit `ORDER BY` on the `INSERT ... SELECT`.** Not needed for correctness or reproducibility: the final table
state is a `ReplacingMergeTree` reduction keyed on `(workspace_id, project_id, id)` with `last_updated_at` as the version
— **independent of insert order** — so any run converges to the same live rows; ClickHouse already sorts each insert
block by the destination `ORDER BY`, and since the source shares that key the rows arrive in order anyway; and
reconciliation uses order-independent `uniqExact` of the dedup key. An explicit `ORDER BY` would only add sort cost/memory
on a 4 TB backfill for no gain.

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

**Replay matches on two branches — full key, or `(workspace_id, id)` — mirroring the product's two delete paths.**
`TraceService.delete(ids, projectId)` resolves each id's owning project and deletes per project (full key); ids it can't
resolve fall back to a **workspace-scoped** delete — `TraceDAO.DELETE_BY_ID` with the project filter omitted, i.e.
`DELETE … WHERE id IN … AND workspace_id = …` across every project. The bridge records the first with the project and the
second with an **empty `project_id`** (`DeletionEventDAO`: "project_id is empty for workspace-scoped source tables"). The
replay mirrors both: full-key events delete by `(workspace_id, project_id, id)` (exact; prunes on the destination primary
key — correct even though trace ids are not globally unique), and empty-project events delete by `(workspace_id, id)`.
The second branch is a **faithful mirror, not an over-delete**: the workspace-scoped fallback fires only for ids the
resolver found no live row for in any project, and the source delete it replays already removed every `(workspace_id,
id)` row — so the destination ends matching the source. Without it, those deletions would **silently leak** across the
swap.

**Resurrection guard.** A trace can be deleted and then re-created/updated under the **same id** during the window
(ids are client-supplied; the delete is a mask, and a newer insert wins under `FINAL`). Such an id is bridged as deleted
but is **live again** on the source, and the backfill/delta already copied its live version. So each replay branch also
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

**Every SQL operation — happy path and every rollback stage — is run by a driver script; no SQL or `.sql` file is ever
run by hand.** Each `.sql` file is the single source a driver reads:

| Step | Reference SQL | Driver |
|------|---------------|--------|
| plan — backfill ETA | — | `estimate.sh` |
| 1 — backfill | `000001_backfill_traces_local_v2.sql` | `backfill.sh` |
| 2 — delta + replay | `000002_delta_and_deletion_replay.sql` | `delta_replay.sh` |
| 3 — EXCHANGE + wrap | `000003_exchange_and_wrap.sql` | `exchange_and_wrap.sh` |
| QA — fidelity compare (+ `--drill-down`) | `000005_verify_migration.sql` | `verify.sh` |
| rollback | `000004_rollback_stage_{a,b,c}_*.sql` + `000004_rollback_reverse_replay.sql` | `rollback.sh` |
| finalize — drop parked backup | — | `finalize.sh` |

Each driver takes the connection from the standard `clickhouse-client` env vars (`CLICKHOUSE_HOST`, `CLICKHOUSE_PORT`,
`CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD`) and `--database`.

**The only manual actions are not SQL:** (1) raising/restoring the async-insert buffer ceiling
(`databaseAnalytics.asyncInsertBusyTimeoutMaxMs`) around steps 2–3; (2) flipping
`databaseAnalyticsDataModel.traceColumnsNonNullable` to `true` in lockstep with the EXCHANGE (and back on rollback) —
see "The final cutover window"; and (3) the go/no-go judgement between steps. All three are *backend config* / judgement changes (env + rolling
restart, or a config push) that these DB-facing scripts cannot and should not make. They are deliberately operator-owned;
none involves typing SQL.

## Naming and the parked backup

Table names are the primary signal an operator acts on, so they encode which table is which — the safeguard against an
accidental `DROP` of the wrong (irreplaceable) table:

- **`traces`** — always the live table the app reads/writes (the original before the cutover; the successor after it;
  the `Distributed` wrapper after the wrap).
- **`traces_local_v2`** — always **the successor**: created empty by migration 000101, filled by backfill/delta, and —
  after a rollback — re-parked as the abandoned successor. It only ever holds "the v2 data," so the `_v2` name is always
  truthful.
- **`traces_local`** — the successor's live shard after the wrap (standard `Distributed`-over-`_local` idiom).
- **`traces_pre_cutover_backup`** — **the displaced old original**, produced by renaming it immediately after the
  EXCHANGE. This rename is the whole point: leaving the old data under `traces_local_v2` would label the *oldest*,
  *sole-backup* copy with a `_v2` suffix that reads as "the newer table" — and, post-wrap, sitting next to the live
  `traces_local` it would invite dropping the wrong one. `traces_pre_cutover_backup` says exactly what it is and shares
  no stem with the live shard, so neither confusion is possible.

The one irreversible drop (`finalize.sh`) targets only the parked backup — `traces_pre_cutover_backup` after a
successful cutover, `traces_local_v2` after a rollback — and never the live `traces` or `traces_local`.

## Rollback

The full, ready-to-run rollback — including the **reverse deletion replay** so deletes don't resurrect — is pre-written
as one file per stage (`000004_rollback_stage_a_discard_shadow.sql`, `…_stage_b_exchange_back.sql`,
`…_stage_c_promote_original.sql`, and the shared `000004_rollback_reverse_replay.sql`) and driven by
[`scripts/rollback.sh`](scripts/rollback.sh), so no one authors it under pressure.

**No data loss by construction.** The stages are mutually exclusive, so each lives in its **own file** — no single file
mixes the `TRUNCATE` (stage A only) with the `EXCHANGE`/`DROP` of the others, and running any file does exactly one
stage. No statement drops a data-bearing table: swaps are atomic `EXCHANGE`/`RENAME`, and the only `DROP` targets the
`Distributed` wrapper, which stores no data (it is a routing definition over `traces_local`). Before running, `rollback.sh`
**asserts the live `traces` topology matches the requested stage and aborts otherwise** — so a wrong-stage run (the only
way a `TRUNCATE`/`DROP` could hit the wrong table) makes no change. Every stage lands in the same **canonical state**:
`traces` = the original data (live), `traces_local_v2` = the successor data (parked backup). No leftover `*_new` names.
The parked backup is dropped only later, by `finalize.sh`, after the soak.

Pick the stage by how far the cutover got (`cutover_start` is the value `exchange_and_wrap.sh` printed):

- **Stage A — before EXCHANGE:** `./scripts/rollback.sh --database opik --stage A`. Discards the disposable shadow
  `traces_local_v2`; the live `traces` was never touched. (Guarded: aborts unless `traces` is still the original schema.)
- **Stage B — after EXCHANGE, before wrap:** `./scripts/rollback.sh --database opik --stage B --cutover-start '<ts>'`.
  `EXCHANGE` `traces_pre_cutover_backup` back to live `traces`, rename the now-parked successor back to
  `traces_local_v2`, then the reverse replay. (Guarded: aborts if `traces` is `Distributed` — use C.)
- **Stage C — after wrap:** `./scripts/rollback.sh --database opik --stage C --cutover-start '<ts>'`. Drops the
  `Distributed` wrapper, then one atomic `RENAME` promotes the original (`traces_pre_cutover_backup`) back to `traces`
  and parks the successor under `traces_local_v2`, then the reverse replay. (Guarded: aborts unless `traces` is
  `Distributed`.)

After a stage B or C rollback, `traces` is the Nullable original again — **revert `traceColumnsNonNullable` to `false`**
(config, operator-owned) so the app resumes binding `null` for absent values and filters use Nullable logic. Leaving it
`true` is write-safe but would make absent-value filters/sorts use sentinel logic against a Nullable column.

**Point of no return.** The `EXCHANGE` is reversible for as long as the parked backup exists (stage B/C). Dropping that
backup with `finalize.sh` is the one irreversible step, so gate it on an explicit soak:

- **Soak duration** — keep the parked backup (`traces_pre_cutover_backup` after a successful cutover; `traces_local_v2`
  after a rollback) for a defined window (recommend ~2 weeks; it fits well inside the bridge's 2-year TTL) so any latent
  read/query regression surfaces while rollback is still an option.
- **Finalize exit criteria** — before dropping: `verify.sh` clean, query p99 within budget over the soak, no
  cutover-related incidents open, and (if the wrap was applied) the sharding-aware DAO healthy in production.

Once those hold, drop the parked backup with [`scripts/finalize.sh`](scripts/finalize.sh) — it auto-detects whichever
parked table is present (`traces_pre_cutover_backup` or `traces_local_v2`), never the live `traces`/`traces_local`. It
is dry-run by default, `--confirm` to drop, refuses if the live `traces` looks empty while the backup does not, and
refuses if both parked names somehow exist (ambiguous — resolve by hand).

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

> **The pre-EXCHANGE compare is the gate; the post-EXCHANGE compare has a caveat.** `traces_pre_cutover_backup` is a
> **frozen** snapshot as of `cutover_start`, but live `traces` keeps taking writes the instant the buffer drains — so
> the **current (live) week will legitimately show a mismatch** (the live table is a superset of the frozen backup by
> exactly the post-cutover writes). That is expected, not a leak. To use the post-EXCHANGE compare as a real check,
> either run it **immediately after the swap before writes resume**, or bound it to the **sealed historical weeks**
> (`--to-week <last-full-week>`), where a mismatch *would* be a genuine problem. A leak shows up as rows present in the
> backup but absent from `traces`; post-cutover writes are the harmless opposite direction.

**Feasibility at scale.** A full pass reads every partition (heavy but bounded per week — run off-peak). When that is
infeasible, sample and still get high confidence:
- `--sample-mod N` compares a deterministic 1/N `id` sample — the *same* rows on both sides, so like-for-like.
- `--weeks-stride S` compares every S-th weekly partition (partition-pruned, so genuinely cheaper).
- `--from-week` / `--to-week` bound the range (e.g. verify the most recent weeks fully, older weeks sampled).

`verify.sh` exits non-zero if any window mismatches and prints the window bounds; re-run with `--drill-down` to list the
keys that differ or exist on one side only (it runs the `drill-down` block of `000005_verify_migration.sql` for each
mismatched window).

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
- the replay wall time is measured and bounded.

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
  delete), so watch the backend logs for `captureDeletions` failures. A silently-dropped capture would leak a delete;
  `verify.sh` catches that as a pre-EXCHANGE week mismatch (the row is live on the destination but gone on the source),
  so it is an early-warning signal, not a silent hole — but treat repeated failures as an abort signal until capture is
  healthy.

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
- [ ] **Far-future partitions quantified** — run the bad-`id` audit query above; remediated or explicitly accepted.
- [ ] **`EXCHANGE TABLES ... ON CLUSTER` works end-to-end** — or the fallback `RENAME` sequence is documented for the
      variant that needs it.
- [ ] **Async-insert ceiling confirmed** — raising `asyncInsertBusyTimeoutMaxMs` demonstrably widens the adaptive buffer
      under load, not just the cap.
- [ ] **Data Retention confirmed disabled** for the cutover window (`RETENTION_ENABLED=false`).
- [ ] **Reconciliation clean** — per-window source/dest counts within 0.01% across the whole backfill.
- [ ] **Replication settled before the EXCHANGE** — `replication_queue` empty and the deletion-replay mutation
      `is_done` on **all** replicas (`exchange_and_wrap.sh` gates on this; do not `--force` past it in production).
- [ ] **`traceColumnsNonNullable = true` rolled out to every backend instance before the EXCHANGE** — confirmed live on
      the whole fleet (else in-progress-trace writes are rejected the instant the non-nullable schema goes live); revert
      plan to `false` ready for rollback.
- [ ] **Schema-parity guards green** — `cutoverCopiesEveryBaseColumn` and `successorMaterializedColumnsMatchSource` pass
      on the release, so the cutover copies every base column of `traces` and the two tables' base and materialized
      columns match.
- [ ] **Fidelity verified** — `verify.sh` passes (full, or a documented representative sample) between source and
      destination before the EXCHANGE. Re-run `delta_replay.sh` then `verify.sh` until it PASSES: while the buffer holds
      writes (or, on a rehearsal without it, once traffic is quiescent) the last delta must catch every in-flight write.
- [ ] **`Distributed` wrap gated on app-readiness** — apply the wrap (step 4, part 2) only when the delete/read DAOs are
      sharding-aware; otherwise stop after the `EXCHANGE`, since a lightweight `DELETE` against a `Distributed` `traces`
      is unsupported and breaks the trace-delete path.
- [ ] **No query-semantics regression** — FINAL / `LIMIT 1 BY` dedup verified; p99 on the project traces listing page within
      ±10% of the pre-migration baseline.
- [ ] **Rollback rehearsed at every stage** (before EXCHANGE, after EXCHANGE/before wrap, after wrap) — deletes during
      the post-cutover window do not resurrect after the reverse-replay; the parked table is retained for the soak.
- [ ] **Go/No-Go decision recorded** with the staging evidence attached.
