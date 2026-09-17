# Cutover runbook — `spans` → partitioned + sharding-ready

Operator runbook for the cutover of the ClickHouse `spans` table: it migrates the live, unpartitioned
`spans` table to `spans_local_v2` (weekly-partitioned, denullified, `is_deleted`-ready) with **near-zero downtime**
and **no write or deletion loss** — the deletion bridge replays every captured delete before the swap, and the
post-swap reconciliation sweeps back every write that landed in the gap between the last delta and the `EXCHANGE` and
proves it did.

The mechanism is **backfill + delta + deletion replay + EXCHANGE + post-swap reconciliation**, with no dual-write path
and **no ingestion-path config change**. Writes are never held: the `EXCHANGE` is atomic per node, so a concurrent
insert always commits to a valid table. Writes that land in the *old* one — in the final-delta→`EXCHANGE` gap and
during the cross-node `ON CLUSTER` skew — stay in the parked backup until step 5 sweeps them into the live table. See
["The final cutover window"](#the-final-cutover-window) for what the swap does and does not guarantee.

> **The cutover is not complete when the `EXCHANGE` returns.** Those parked writes stay orphaned until `reconcile.sh`
> sweeps them back and proves it did. `exchange_and_wrap.sh` ends with a **CUTOVER INCOMPLETE** banner naming the exact
> command, and `finalize.sh` refuses to retire the backup without `--confirm-gap-reconciled`. This is not a theoretical
> residual — it has been observed on a real cutover (OPIK-8238), with traces left in `traces_pre_cutover_backup` and
> absent from the live table, and nothing about spans makes it less likely.

> **The `Distributed` wrap is reachable but deliberately deferred: this window ends at the `EXCHANGE`.** A
> `Distributed` table rejects mutations, so wrapping `spans` breaks every span delete unless `SpanDAO` already targets
> the shard. **OPIK-7799 has shipped that prerequisite**: `databaseAnalyticsDataModel.spansDistributedWrapEnabled`
> (default `false`) exists, and every span mutation routes through `SpanDAO#selectSpansMutationTable`. So
> `exchange_and_wrap.sh --with-wrap` is no longer impossible — it is gated on `--confirm-daos-retargeted`, which the
> operator can now truthfully assert once the flag is live fleet-wide.
>
> **It is still not part of this window, for two reasons.** The wrap is sharding-readiness, not the cutover: the
> post-`EXCHANGE` estate is complete and supported on its own. And **no `ClickHouseSpansTopologyHealthCheck` shipped
> with OPIK-7799** — `clickhouse-traces-topology` asserts the trace flag only — so a spans flag/topology mismatch has
> **no symptom until the first span delete fails**. Read
> ["the readiness gap"](#the-readiness-gap-that-opik-7799-left-open) before planning a wrap.

This runbook is the human-facing artifact; its SQL is validated end-to-end by
[`SpansLocalV2CutoverTest`](../../src/test/java/com/comet/opik/infrastructure/SpansLocalV2CutoverTest.java). Treat
that test as the executable specification of these scripts: if you change the cutover SQL, change it there first and keep
this runbook in sync.

> **This is not a Liquibase migration.** The backfill / delta / replay / EXCHANGE steps are runbook-driven and paced by
> an operator — they produce sustained I/O and must not run as blocking changesets. `spans_local_v2` (migration 000115,
> codecs refined by 000116) and `deletion_events_local` (migration 000096) are already created by Liquibase; everything
> here operates on those.

## Scale: spans is not traces with a bigger number

**`spans` is the largest table in an Opik estate**, by a wide margin in every dimension that matters here: rows, bytes
on disk, uncompressed bytes per row, part size, and the number of weekly partitions its ids resolve to. Every threshold
below is derived from a *measured* quantity rather than scaled from the traces runbook by intuition, so the first step
is to take the measurements. **All five are read-only, and `estimate.sh` prints them**; record them in the Go/No-Go.

| measure | where from | what it sizes |
|---|---|---|
| source size, rows, bytes/row | `estimate.sh` (or `system.parts` for `spans`) | `--max-rows-per-insert`, `--probe-rows`, the headroom gate |
| node volume total and free | `estimate.sh` headroom verdict | `--min-free-factor`, and whether the window can open at all |
| largest active part | `system.parts` | the replication-settle gate's timeout and stuck-age |
| projected destination weekly partitions | `estimate.sh` audit 1 | `--max-partitions-per-insert-block` |

Three properties of the table follow from its size and drive most of what differs from the traces runbook:

- **Very large active parts.** A table at this scale merges parts up to ClickHouse's
  `max_bytes_to_merge_at_max_space_in_pool` ceiling (150 GiB by default), orders of magnitude above `traces_local`'s.
  That is what makes the replication-settle gate's 60-second "stuck" threshold wrong here: one *legitimate* `GET_PART`
  of such a part takes minutes, so the traces threshold would fail a healthy cluster.
- **Large uncompressed rows** — `input` / `output` / `metadata` JSON dominate, and the on-disk figure understates them
  severalfold. `min_insert_block_size_bytes` (256 MiB by default) therefore binds long before `max_insert_block_size`,
  so the row-count cap is not the lever it looks like. Read `uncompressed_bytes / rows` from `system.parts`.
- **34 KiB of per-partition buffer inside every insert block**, independent of row size. Under the traces cap of 2,000
  partitions that is ~68 MB and invisible; at spans' partition count it is the dominant memory term. See
  ["Partition spread"](#partition-spread-and-the-one-setting-that-matters).

**Every threshold in this runbook is derived from one of those, and the table below says which.** A value copied from
the traces runbook without a derivation is a defect; a value that is *the same as* the traces one after derivation is
recorded as such, with the reason, because silently changing a number to look different would be worse.

> **Defaults are starting points, not measurements.** Where a default encodes an estate-dependent quantity — the
> partition cap, the compression ratio, the margin days — the driver says so and `estimate.sh` gives you the number to
> replace it with. Re-measure on your own estate before the production window.

## Threshold derivation — every number, and where it came from

| knob | traces | spans | derived from |
|---|---|---|---|
| `backfill.sh --min-free-factor` | 2.0 of the **source** | **1.35 of the projected destination** | On a table whose source size is a large fraction of the volume, twice the source is unsatisfiable — and can be unsatisfiable even at factor 1.0. Sized instead from an explicit destination projection, with the factor covering merge scratch rather than a second whole copy. See ["Blocker 1"](#blocker-1--disk-headroom). |
| `backfill.sh --dest-compression-ratio` | — (no counterpart) | **0.381** | Destination bytes per source byte under the `000116` codec set, as measured during OPIK-7400 on representative spans data. It is a flag precisely so the projection is an argument rather than an assumption: **re-measure it** — `estimate.sh` prints the projection it implies against your measured free space. |
| `backfill.sh --max-partitions-per-insert-block` (and the delta's / sweep's) | 2,000 | **20,000** | A block cannot span more partitions than the table has, so the destination's TOTAL distinct partition count is a hard upper bound on a block's spread. Same sizing rule as traces, different input. 20,000 is above the SCHEMA's own ceiling — `UUIDv7ToDateTime` is honest from the epoch to 2299, ~17,000 weekly partitions — so it cannot be too low for any estate. It is a limit check rather than an allocation, so a generous value costs nothing; confirm against `estimate.sh` audit 1 and move on. |
| `backfill.sh --min-insert-block-size-bytes` | — (stock 256 MiB, implicit) | **256 MiB, carried explicitly** | Same value as traces, made explicit because on spans it is the ONE dial over peak insert memory and it trades against part count. Measured on spans-shaped rows: 256 MiB → ~1.1 GiB peak and the fewest parts; 32 MiB → ~315 MiB peak and ~5× the parts. Lower it only if the data nodes are memory-constrained. See ["Partition spread"](#partition-spread-and-the-one-setting-that-matters). |
| `backfill.sh --max-insert-block-size` | 1,048,576 (stock) | **1,048,576 (unchanged, and re-derived)** | Re-derived rather than inherited, and it lands on the stock value: at spans' uncompressed row width the BYTE bound above fires long before this row count, so lowering it would be cosmetic on the backfill. It is carried explicitly only so the delta's and the sweep's lower value reads as a deliberate difference rather than drift. |
| `delta_replay.sh` / `reconcile.sh --min-insert-block-size-bytes` | — (stock, implicit) | **32 MiB** | Deliberately an eighth of the backfill's, and the one place in this runbook where the memory/part-count trade is resolved the other way. Both statements run inside or immediately around the cutover window, where peak memory is the scarce resource; and both copy a small fraction of the table, so the extra parts their smaller blocks produce are bounded by that volume rather than by the table's. |
| `delta_replay.sh` / `reconcile.sh --max-insert-block-size` | — (stock, implicit) | **65,536** | Follows the byte bound above as a second, coarser cap on the same statements. As on the backfill it rarely fires first; it is set so a pathologically narrow row cannot defeat the byte bound. |
| `backfill.sh --max-rows-per-insert` | 2,000,000 | **1,500,000** | Traces' 2,000,000 was sized to bound the bytes read per statement. Spans' rows are larger on disk, so the same byte budget implies fewer rows. **Re-derive from your measured bytes/row** — `estimate.sh` prints it. |
| `backfill.sh --pause-seconds` | 0, "30–60 at peak" | 0, **"60–120 at peak"** | The destination accumulates an order of magnitude more weekly partitions than the traces one did, so a window's merge backlog is spread over far more part sets. A starting point only — size it from the observed `system.merges` backlog on prod-test. |
| `backfill.sh --divergence` | 0.0001 | **0.0001 (unchanged, and re-derived)** | 0.01% of this driver's 1,500,000-row window bound is 150 rows of in-flight slack, the same order as traces' 200 at 2,000,000. Crucially the counts compared are `uniqExact` of the **destination** dedup key on both sides, so the source's wider sort key cannot inflate the divergence. |
| `exchange_and_wrap.sh --settle-timeout` | 120 (cap 3600) | **1800 (cap 7200)** | The pre-swap queue scope includes the source `spans`, whose parts approach ClickHouse's `max_bytes_to_merge_at_max_space_in_pool` ceiling (150 GiB by default). One fetch of a part that size, at a conservative 150 MiB/s, is ~17 min — so the budget must exceed it or the age verdict is unreachable. |
| `SETTLE_STUCK_AGE_SECONDS` (both drivers) | 60 | **1800** | Same derivation: 60s would fail the gate on a healthy fetch of a part that size. 1800 is ~1.8× it. |
| `SETTLE_POLL_SECONDS` (both drivers) | 5 | **15** | A 1800s budget at 5s issues 361 cluster-wide reads; 121 is ample resolution for a 30-minute verdict, at a third of the read load against a large estate. |
| `reconcile.sh --settle-timeout` | 120 | **600** | Post-swap the primary signal is an unfinished **mutation** on the frozen parked table, judged unconditionally and reachable well inside 600s because such mutations drain rather than recur. The queue's age verdict is consequently unreachable at this budget — stated in the driver rather than hidden. Worst case is `(1 + --max-passes) × 600`. |
| `reconcile.sh --slack-seconds` | 300 | **300 (unchanged, and re-derived)** | It absorbs cross-replica clock skew on server-side timestamp defaults, which is a property of the cluster's clocks, not of the table's size. Widening the gap window is free (the sweep is mask-honored and idempotent), so there is no cost to keeping it. |
| `reconcile.sh --max-passes` | 3 | **3 (unchanged, and re-derived)** | The parked table is frozen, so convergence is by construction; passes exist only to absorb concurrent traffic during the sweep. That is a property of the procedure, not of the row count. |
| `estimate.sh --probe-rows` | 200,000 | **100,000** | Halved, because the probe reads real rows out of a live table and spans' are larger: the same row count costs more bytes and evicts proportionally more page cache. Halving keeps the probe's byte cost in the same band as the traces one's. |
| `estimate.sh --write-cost-factor` | 2.5 | **2.5 (unchanged, and flagged as unvalidated)** | Two effects move it in opposite directions and neither has been measured on spans: the destination drops traces' `output_keys` JSON parse (there is no such column — migration 000115 says so) but adds `ZSTD(3)` on `usage` and `error_info`, over considerably more uncompressed bytes per row. **Acceptance criterion 3 requires measuring it on prod-test** and passing the result through `--rows-per-sec` instead. |
| `verify.sh --receive-timeout` | 1800 | **1800 (unchanged)** | It bounds the gap between packets, not total query time. The quantity it protects against — a `confirm-keys` re-check going quiet while the server works — is longer on spans, which argues for keeping the generous value, not for changing it. |

## Blocker 1 — disk headroom

**The traces gate can be unsatisfiable for spans.** `backfill.sh` aborts at startup unless node free disk clears
`--min-free-factor` × the **source** table's on-disk size, defaulting to 2.0. On an estate where `spans` already
occupies a large fraction of the volume, twice its size can exceed the whole volume — and the gate can fail even at
factor 1.0, where it would demand as much free space as the source occupies. Run `estimate.sh` first; it prints the
verdict and the arithmetic behind it.

The traces gate was deliberately conservative: it sized from the **source**, because a driver cannot know what the
destination will compress to. For spans the destination is substantially smaller — migration `000116`'s codec set was
measured during OPIK-7400 at well under half the source's bytes per row. But "it should fit" is not a headroom plan for
a multi-day copy that also has to absorb merges.

**The answer this runbook commits to: re-derive the factor from an explicit destination projection, and prove the
projection before the production window.** Concretely:

1. `backfill.sh --dest-compression-ratio` (default `0.381`) makes the projection an argument rather than an assumption,
   and `--min-free-factor` (default `1.35`) multiplies the **projection**, covering merge scratch on top of the finished
   copy rather than a second whole copy of the source. `estimate.sh` reports source size, projection, free space and the
   resulting margin as four numbers, so the decision is made on arithmetic rather than on a pass/fail flag.
2. **A thin margin is not a plan, and the drivers say so.** `estimate.sh` and `backfill.sh` both warn when free space
   exceeds the requirement by less than 3 TiB, naming the lever below. Do not open the production window on a passing
   verdict alone — read the margin.
3. **The lever, if the margin is thin: retire `traces_pre_cutover_backup`.** It is already owned — `finalize.sh` in the
   traces directory retires it, and **OPIK-8263** governs closing out the traces write exception. **Sequencing note: it
   is irreversible — it retires the only traces rollback safety net — so it needs its own authorisation and must not be
   treated as a side effect of the spans cutover.** `estimate.sh` sizes what it would free and never suggests running
   it.
4. **Growing the volume is the alternative and is not mutually exclusive.** Whichever is chosen, the Go/No-Go records a
   number, not an intention.

**What is explicitly NOT the answer: passing a lower `--min-free-factor`.** The gate is the only thing standing between
a multi-day copy and a full volume, and lowering it makes the same copy run with less margin, not more room. The driver
says this in its abort message, because the flag is right there.

**On tiered storage the whole-node figure is necessary and not sufficient.** New parts land on the hot volume before
they tier, so validate per-volume headroom out of band; `backfill.sh` refuses a tiered or mismatched `storage_policy`
without `--confirm-tiered-headroom`.

## Partition spread, and the one setting that matters

The backfill slices the source by `created_at`, **not** by `id` or `id_at`, for three reasons documented at length in
["Why slice by `created_at`"](#why-slice-by-created_at-and-not-id-or-workspace) and which all still hold for spans.
**The consequence is the problem.** A `created_at` window contains rows whose `id_at` spans many weeks, so one insert
block spans many destination partitions. ClickHouse defaults `max_partitions_per_insert_block` to 100 with
`throw_on_max_partitions_per_insert_block = 1`, and the traces cutover recorded hitting it for real
(`Code: 252 … TOO_MANY_PARTS`, *"reproduced, not projected"*). Traces raised the setting to 2,000 under the rule
its own driver states: *"a block's spread is NOT just the far-future count, so size from the table's TOTAL distinct
partition count."*

**Spans needs the same fix with a bigger number, and nothing else.** The schema bounds how big:
`spans_local_v2` partitions on the honest weekly Monday of `id_at`, and `UUIDv7ToDateTime` is honest from the epoch to
2299 — about **17,000 distinct weeks**, the hard ceiling. What fills that range is the far-future population: the traces
cutover measured its far-future ids spreading over **a thousand-plus distinct weeks**, not clustering into one. (Non-v7
ids do not add to the spread — they all resolve to `1970-01-01` and collapse into a single epoch week.) The driver's
default of `--max-partitions-per-insert-block 20000` clears the ceiling, so it cannot be too low for any estate.
`estimate.sh` audit 1 measures your actual count; confirm against it rather than assuming.

> **The cap is a limit check, not an allocation.** ClickHouse buffers ~34 KiB per partition a block **actually
> touches**, not per partition permitted. Setting the cap generously therefore costs nothing, which is why there is no
> tuning exercise here — set it above the measured count and move on.

### `min_insert_block_size_bytes` is the dial, and it trades memory against parts

The one knob worth choosing deliberately bounds the block in **bytes**. That matters twice over: it sets peak insert
memory, and — because a block can never span more partitions than it holds **rows** — it also bounds the partition term
by construction. At spans' row width a 256 MiB block holds a few thousand rows, so the partition buffers are a rounding
error next to the row data.

Measured on a local throwaway `clickhouse-server:26.3`, 200,000 spans-shaped rows (~50 KB/row uncompressed) with merges
disabled so `part_log` reports what the INSERT itself created:

| `min_insert_block_size_bytes` | peak insert memory | parts created (0.5% far-future) | parts created (5%) |
|---|---|---|---|
| **256 MiB** (default, and what traces ran) | ~1.1 GiB | **87** | **635** |
| 32 MiB | **~315 MiB** | 412 | 1,809 |

Read it as a single trade: **lower the bound to cut peak memory, raise it to cut part count and the merge work that
follows.** Nothing else moves either number much. The driver defaults to the stock 256 MiB — the value the traces
backfill ran at — and `--min-insert-block-size-bytes` is there to move it if your data nodes are memory-constrained.
Measure yours from `system.query_log`'s `memory_usage`, filtered by the `log_comment` the driver sets:

```sql
SELECT count()                                AS statements,
       formatReadableSize(max(memory_usage))  AS peak_memory,
       formatReadableSize(sum(read_bytes))    AS read_total,
       formatReadableSize(sum(written_bytes)) AS written_total,
       round(sum(query_duration_ms) / 1000)   AS seconds
FROM system.query_log
WHERE type = 'QueryFinish' AND log_comment LIKE 'spans_local_v2_backfill:%';
```

### What was tried and retired: a two-pass split

An earlier revision of this runbook copied each window with **two** complementary statements — one for rows whose
honest `id_at` fell inside the window ± a margin, one for the exact complement — so that the 99%+ ordinary rows could
run at a small partition cap while only the sparse tail paid the large one. It was correct, and it is recorded here
because the reasoning that motivated it is a trap worth naming.

**The premise was wrong.** It rested on "a block spanning ~17,000 partitions costs ~570 MiB of buffers", which is true
only if the block holds ~17,000 rows — one row per partition. `min_insert_block_size_bytes` caps the block in bytes, so
at spans' row width a block holds a few thousand rows and, at a realistic far-future share, touches a few dozen
partitions: **on the order of 1 MiB of buffers, not 570.**

Measured against the same 200,000 rows, the split was **dominated on both axes** — it inherited the ordinary arm's
memory and the outlier arm's part count:

| mode | peak memory | parts created (0.5%) |
|---|---|---|
| single statement @ 256 MiB | 1.12 GiB | **87** |
| single statement @ 32 MiB | **315 MiB** | 412 |
| two-pass (256 MiB + 32 MiB) | 957 MiB | 405 |

Two further observations, both of which point the same way:

- **It cost a second statement per window for ~0.5% extra read.** ClickHouse moves the id predicate into `PREWHERE`, so
  each arm read only `id` and `created_at` across the window and materialized the wide columns solely for its own rows.
  Cheap — but buying nothing.
- **Concentrating the tail into its own small blocks made part count worse, not better.** Parts ≈ blocks × partitions
  per block, so more blocks over the same spread is strictly more parts. The arm assignment was backwards.

**And the traces cutover is the clinching argument.** It faced the same litellm bug, the same weekly partitioning and a
comparable share of affected rows; the only material difference is total volume, which changes the *number* of blocks,
not how many partitions any one of them spans. Traces shipped with a raised cap and stock block bounds, and it worked.
Spans rows are *wider*, so a byte-bounded block holds *fewer* rows and therefore reaches *fewer* partitions — spans is
less exposed per block than traces was, not more.

**So there is one mode, one statement per window, and no decision for the operator to get wrong.**

## The dedup keys differ, and it changes every comparison in this runbook

`spans` orders by `(workspace_id, project_id, trace_id, parent_span_id, id)`. `spans_local_v2` **drops
`parent_span_id`** and orders by `(workspace_id, project_id, trace_id, id)` — migration 000115 explains why at length
(nothing filters on it; dedup identity is unchanged since `id` alone is unique per span; and a mutable sort-key column
breaks `ReplacingMergeTree` dedup, which is the hazard the drop removes).

**The consequence for the cutover: the source can legitimately hold TWO live rows for one span where the successor
holds one.** `SpanDAO`'s `PARTIAL_INSERT` writes a changed `parent_span_id`, and because that column is in the source's
sort key the two versions sort to different keys and **never merge**. On the destination they are one key and
`ReplacingMergeTree` keeps the highest `last_updated_at`.

Three places had to be built differently because of it, and all three are the same fix:

- **`backfill.sh`'s per-window reconciliation** counts `uniqExact(workspace_id, project_id, trace_id, id)` on **both**
  sides — the destination's key, not the source's. Counting the source's own key would report a faithful copy as short
  on every such span.
- **`verify.sh` / `000005`** reduce the **old-schema** side twice: `FINAL` collapses each source key, then
  `argMax(<fingerprint>, last_updated_at) GROUP BY` the destination key collapses across `parent_span_id`, picking the
  same winner `ReplacingMergeTree` picked. The new-schema side needs `FINAL` alone. The asymmetry is load-bearing.
- **`reconcile.sh` / `000006`** apply the same reduction to whichever side carries the old schema — the parked backup
  forward, the restored original in reverse.

**Where the two winners can still disagree: a version tie.** If a key's newest `last_updated_at` is carried by more than
one distinct row, `argMax` and `ReplacingMergeTree` each pick arbitrarily and may differ. That is exactly the case
`version-ties` already detects — and on spans it has this **second cause** on top of the one traces had. Both sides of
that block group by the destination key, so one detector covers both. A window with `version_ties=src:N/dst:0` is the
signature of the spans-only cause; `verify.sh` says so in its INCONCLUSIVE message.

## What else spans has that traces did not

Four column-level differences the copy has to handle, all of them in the projection and all of them covered by
`SpansLocalV2CutoverTest`:

- **`parent_span_id`: `String` → `FixedString(36)`, and the source column can hold a value the destination cannot.**
  `SpanDAO`'s `PARTIAL_INSERT` writes `leftPad('', 40, '*')` — a **forty**-character poison value — into it when a
  span's parent changes. On `spans` that column is a plain `String`, so the write **succeeds and is stored**; an
  implicit conversion of 40 bytes into `FixedString(36)` **throws** (`TOO_LARGE_STRING_SIZE`) and would abort the whole
  window. Every projection in this cutover therefore maps any value that is not exactly 36 bytes to the empty
  (root-span) sentinel, which the destination stores as 36 NUL bytes. **That is a deliberate, recorded normalization of
  a value that was never a real span id** — `estimate.sh` audit 2 counts the affected rows before the window opens, the
  Go/No-Go records the number, and the original bytes stay in `spans_pre_cutover_backup` until `finalize.sh`.
  The fidelity fingerprint applies the **same** normalization to the source side, so the copy cannot read as a mismatch.
- **`parent_span_id` on the way back.** The reverse sweep casts `FixedString(36)` → `String`, which trims the NUL
  padding. This is not cosmetic: migration 000115's header records that the driver surfaces the padded form to Java as
  36 NUL characters, which is **not blank**, so a `!isBlank()` guard lets it through and `UUID.fromString` throws — and
  that `SpanDAO`'s SQL presence checks would read 36 rather than 0, making every re-imported root span look like a
  child.
- **`usage`: `Map(String, Int32)` → `Map(String, Int64)`**, a lossless widening forward. **Backward it is a narrowing**,
  and it is the only one anywhere in this procedure. ClickHouse converts rather than refusing, so an out-of-range value
  would land wrapped and silently wrong in a column the product reads as a token count. `reconcile.sh` runs
  `reverse-usage-range-check` before the reverse sweep and **refuses** on a non-zero result. Expected to be 0 on any
  real estate — 2,147,483,647 tokens for one span is not a number a model produces — so it normally proves the
  narrowing is a no-op.
- **There is no `output_keys`, `thread_id` or `visibility_mode`.** `output_keys` is traces-only (migration 000044), and
  000115 records that spans has no counterpart. That removes the one expensive per-row materialization the traces
  backfill profiled — a JSON parse — so **do not carry traces' `--max-insert-threads` conclusion over**; measure it.
  What spans materializes instead is `truncated_input`/`truncated_output`, the three `*_length` counters, `duration` and
  `id_at`.

And one path-level difference:

- **Spans have no standalone delete.** Every user-facing span delete is the **cascade** of a trace delete, through
  `SpanService.deleteByTraceIds`, which resolves the span ids and removes them under `(workspace_id, project_id, id)`.
  `spanDeletionEventsCaptureEnabled` captures exactly that cascade into the bridge with `source_table = 'spans'` and
  reason `CASCADE`. Two things follow: **the project_id is always present** (the listener passes the `TracesDeleted`
  event's, which since OPIK-7483 is only ever emitted per resolved project), so the replay is full-key with no
  workspace-scoped branch; and **"quiesce deletes across the swap" means quiesce TRACE deletes**, because that is the
  only path that reaches a span.
- **The replay's key is not a primary-key prefix here.** The bridge records no `trace_id`, so
  `(workspace_id, project_id, id)` prunes on the two-column prefix and then relies on the `id` skip indexes 000115 added
  for exactly this shape. On the **source** it is worse: `spans` has no `id` skip index at all (000088 indexes only
  `created_at`/`last_updated_at`; the `id` minmax/bloom pair exists on `spans_local_v2` and, for traces, on `traces` via
  000113), so the replay's resurrection guard reads an unindexed `id` column over the whole source. The set is
  tiny, the scan is not. `delta_replay.sh` prints the wall time; treat it as a first-class component of the tail rather than a rounding
  error. Adding the 000113 equivalent to `spans` is deliberately **not** part of this cutover — materializing a bloom
  filter over a table this size is a heavy mutation that would have to run inside the very window this procedure
  asks to keep short.

## Why this is not a plain `INSERT ... SELECT`

A lightweight `DELETE` in ClickHouse flips a hidden row mask; it does **not** bump `last_updated_at` (the
`ReplacingMergeTree` version column). The cutover's delta step re-copies rows that changed during the backfill, but a
lightweight delete leaves no "changed" signal, so the delta is blind to every delete that fires during the
backfill/delta window — those rows stay alive on the new table and the deletion would silently leak across the swap.

The **deletion-events bridge** closes it: with `spanDeletionEventsCaptureEnabled=true`, every span delete records its
`(workspace_id, project_id, id)` in `deletion_events_local`; the cutover **replays** those keys as deletes against the
new table before the EXCHANGE. The replay matches the **full key**, not `id` alone — see "Delta and replay correctness".

> **Capture goes first (OPIK-8141).** The bridge insert is issued **before** the lightweight delete, not after. A delete
> can fail its client while the server-side mutation still applies — the observed case being a client timeout on a
> mutation that then completed — and capturing afterwards let exactly those deletes go unrecorded, which neither replay
> direction can then re-apply. Capturing first over-records instead when the delete does fail, and the forward replay
> already handles that: its resurrection guard skips any id still live on the source. Capture remains **best-effort** —
> a failed insert is logged and swallowed, never failing a user's delete — so the bridge can still miss a delete, but
> only when the capture itself fails, no longer when the delete does.

> **Spans have no standalone delete, so there is exactly one captured path and it is a cascade.** Every user-facing
> span delete is the consequence of a *trace* delete: `TraceDeletedListener` receives the `TracesDeleted` event and
> calls `SpanService.deleteByTraceIds(traceIds, projectId)`, which resolves the span ids of those traces, calls
> `captureDeletions` and then `SpanDAO.deleteByIds`. There is no span delete endpoint, no batch span delete-by-project
> and no thread path that reaches a span directly — so enabling `spanDeletionEventsCaptureEnabled` covers every one.
> **The project_id is always present**, because the listener passes the event's and, since OPIK-7483, `TracesDeleted` is
> only ever emitted per resolved project; so the replay carries a single full-key branch with no workspace-scoped
> fallback, exactly as the traces one does, for a different reason. The **only** uncaptured `DELETE FROM spans` is the
> retention sweep, which is disabled (see the retention note). Any **new** span-delete path introduced during the
> migration window must likewise capture, or its deletes would leak across the swap.
>
> **Two operational consequences of the cascade being the only path.** First, wherever this runbook says "quiesce user
> deletes across the swap", it means quiesce **trace** deletes — nothing else reaches a span. Second, a span delete is
> never a single id: one trace delete bridges every span of that trace at once, so the bridged set per user action is
> larger and burstier here than the trace cutover's was. That does not change the replay's shape, but it does change how
> much a moment of un-quiesced delete traffic across the swap can cost.

> **Retention sweeps do not run during the cutover.** Data Retention is disabled in every deployment
> (`retention.enabled` defaults to `false`, env `RETENTION_ENABLED`, and has never been enabled), so the retention
> delete path (`SpanDAO.DELETE_FOR_RETENTION`) does not fire. The only deletes during the cutover window are the
> **user-initiated trace-delete cascade**, and those are captured by the bridge (`SpanService.deleteByTraceIds`, reason
> `CASCADE`). The retention path is intentionally **not** wired to the bridge. If Data Retention is ever enabled, either
> pause the retention job for the whole backfill→**reconciliation** window, or first wire retention deletes into the
> bridge (a `RETENTION` reason recorded before each retention delete).
>
> **On spans a stray retention sweep is worse than it was on traces, in two ways.** It filters on `trace_id` only and
> applies **no partition-pruning predicate at all** — `SpanDAO.DELETE_FOR_RETENTION`'s own Javadoc records why: a span's
> `id_at` derives from the span's own UUIDv7 and can land in a later week than its `trace_id`, so a week bound derived
> from a trace-id range would wrongly exclude valid candidates. So it is planned against every part of the whole
> table. And it reaches spans whose *traces* fall in the retention range, which is a different and wider set than "old
> spans".
>
> **The window outlasts the `EXCHANGE`, and step 5 is the reason.** A retention delete that fires after the parked
> backup froze leaves its span masked on the live table, still **live** in the frozen backup, and absent from the
> bridge — so the sweep re-inserts it and the post-swap replay, which re-applies only *bridged* keys, leaves it live.
> The delete is undone. Do not dismiss this as old-data-only: retention selects by `trace_id` range (UUIDv7) while the
> gap window matches `created_at` **or** `last_updated_at`, and the merge path stamps a fresh `last_updated_at` while
> preserving `created_at` — so an old span updated during the gap window is inside both at once. `reconcile.sh`
> asserts `--confirm-retention-paused` for this, exactly as `exchange_and_wrap.sh` and `rollback.sh` do. The test still
> exercises a synthetic large (retention-shape) delete batch, so the replay is proven to handle both batch sizes if
> retention is enabled later.

## Deletion scenarios and how each is handled

| Delete timing | Fate | Handling |
|---|---|---|
| Before the backfill | Row masked on the source | `INSERT SELECT` honors `apply_deleted_mask=1` → never copied. No replay. |
| During the backfill, after its row was copied | Delta can't see the mask flip | Captured in the bridge → **replayed** before EXCHANGE. |
| During the delta and the final cutover window | Same as above | Same bridge, same replay step. |

## Prerequisites (do not start without these)

1. **24h UUIDv7 ingestion validation** live long enough that no un-validated future-dated ids land in newly ingested
   weeks. This is not tied to a retention cycle (retention never runs — prereq 7). Pre-validation far-future-timestamp
   rows already in the table are *not* blocked by this: they are copied by the `created_at` slice and surfaced by the far-future audit
   query below — this prereq only ensures no *new* out-of-range partitions are created mid-cutover.
2. **`spans_local_v2` exists and is empty** (migration 000115, with its codecs refined by 000116). **000116 must have
   run**: it is metadata-only on an empty table, and running it after any backfill degrades into a full re-compress of
   whatever has been copied — on this table, TiB of it.
3. **Successor storage/TTL parity.** `spans_local_v2` must resolve the **same `storage_policy` and TTL-to-cold rules**
   as `spans` (tiering is configured per environment, not in the base DDL). If `spans` tiers hot→cold but the
   successor does not, the entire backfill lands on the hot volume. `backfill.sh` warns on a `storage_policy` mismatch;
   compare TTLs with `SHOW CREATE TABLE spans` vs `spans_local_v2`.
4. **`deletion_events_local` exists** (migration 000096).
5. **`databaseAnalyticsDataModel.spanDeletionEventsCaptureEnabled = true`** deployed and live before the backfill
   begins, and kept on for the entire backfill→EXCHANGE window. On docker-compose set
   `ANALYTICS_DB_DATA_MODEL_SPAN_DELETION_EVENTS_CAPTURE_ENABLED=true` (the backend service forwards it) and restart the
   backend. `backfill.sh` captures the `backfill_start` anchor (a `now64(6, 'UTC')` taken just before the first INSERT)
   and prints it — the delta and the replay both key off it.
6. **Schema-state flag wired, with a rollout plan** — `databaseAnalyticsDataModel.spanColumnsNonNullable` (env
   `ANALYTICS_DB_DATA_MODEL_SPAN_COLUMNS_NON_NULLABLE`, default `false`). The successor's `end_time`/`ttft` are
   **non-nullable sentinel** columns, so the app must represent an absent value as the epoch/NaN sentinel — not `null` —
   once they are live. This flag switches that on **both** sides: the write bind, and the read/filter/sort translation
   back (`epochToNull` on read, `nullIf(end_time, epoch)` in sorts, sentinel logic in the filter builder). It **must be
   flipped in lockstep with the EXCHANGE** (see "The final cutover window"). Confirm it is deployable on the target (env
   passthrough present) and that you have a fleet-wide rollout mechanism (config push or rolling restart) ready.
   > **The failure mode is silent, so plan a positive check — not error-watching.** A `null` bind into the non-nullable
   > successor is **not** rejected: ClickHouse's `input_format_null_as_default` (default `1`) converts it to the column
   > DEFAULT, which is exactly the epoch/NaN sentinel. So a stale-`false` instance keeps **writing correctly** and emits
   > no ingestion error — but it **reads back** an absent `end_time` as `1970-01-01` instead of `null`, and filters/sorts
   > on absent values use the wrong semantics. Unlike `spansDistributedWrapEnabled` (fail-loud, see the wrap
   > prerequisite), a missed or partial rollout here shows up only as wrong data. Verify it positively, per instance —
   > and note the check is **not the same on both sides of the swap**. *Before* the EXCHANGE the live table is still
   > Nullable, so an in-progress span reads back `null` whether the flag is on or off; what discriminates there is what
   > got **written** — the epoch/NaN sentinel means the flag is live, a stored `NULL` means it is not. *After* the
   > EXCHANGE the read-back becomes the discriminator: an absent `end_time` must return `null`, not `1970-01-01`. Assert
   > `ttft` alongside it — same flag, other arm, and one span written without either covers both. Do both sides of the
   > swap, or a stale instance passes the only check you ran.
7. **Confirm Data Retention is disabled** (`RETENTION_ENABLED=false`, the default). If it is ever enabled, see the
   retention note above first.
8. **Sufficient free disk, sized from the DESTINATION** — see ["Blocker 1"](#blocker-1--disk-headroom), which is the
   single hardest prerequisite here and the one the traces runbook cannot lend us. Run `estimate.sh` and read its
   headroom verdict; `backfill.sh` aborts below `--min-free-factor` × `--dest-compression-ratio` × the source size
   (defaults 1.35 and 0.381). **A pass with under 3 TiB of margin is
   a warning, not a green light** — both drivers say so, and the Go/No-Go asks for the headroom decision as a number.
   On tiered storage this whole-node floor is necessary but not sufficient — validate per-volume (hot) headroom too,
   since new parts land hot before they tier, and `backfill.sh` refuses a tiered policy without
   `--confirm-tiered-headroom`.
9. **The pre-write audits, run and recorded** — `estimate.sh` (without `--skip-audits`) reports four numbers the
   Go/No-Go gates on and that nothing later in the procedure can recover: the table's total id-derived weekly partition
   count (which **sizes `--max-partitions-per-insert-block`** and is a hard upper bound, not an estimate), and the
   count of `parent_span_id` values the copy
   will normalize to the root sentinel. They read the whole `id` column, so schedule them rather than running them
   casually — but run them **before the first write**, because two of them decide settings the first INSERT uses.
10. **Schema parity of source and successor** — `spans` and `spans_local_v2` must stay equivalent for as long as both
   exist: the same base (stored) columns (which the cutover must copy) and the same materialized columns (which each
   table recomputes). Guarded in CI by `SpansLocalV2CutoverTest` — `cutoverCopiesEveryBaseColumn` (a new base column
   fails the build until it is in the cutover column list) and `successorMaterializedColumnsMatchSource` (a materialized
   column added to one table but not the other fails the build). Re-confirm both are green on the release being
   deployed.
11. **Fresh backup / snapshot** of the ClickHouse data node.
12. **Freeze concurrent DDL on `spans` for the window — and through the rollback-eligible soak.** Hold any deploy or
    Liquibase changeset that would `ALTER`, `RENAME`, or otherwise touch `spans` / `spans_local_v2` for the whole
    backfill→EXCHANGE window — a schema change landing mid-cutover races the swap and can corrupt it — and keep it frozen
    until `finalize.sh` commits (see "Point of no return"): a `spans` schema change made *after* the EXCHANGE is lost
    from the live table on a rollback + finalize. The revamp's own migrations (000096/000115/000116) are already applied;
    this is about *unrelated* migrations or ad-hoc DDL during the window.
13. **Deletion bridge holds no empty-`project_id` span events, and OPIK-7483 is live fleet-wide.** Span deletion events
    are written by the trace-delete cascade, which takes its `projectId` from the `TracesDeleted` event — and since
    OPIK-7483 that event is only ever emitted per **resolved** project, never project-less. So the cutover replay is
    full-key only (no workspace-scoped branch). Confirm OPIK-7483 is deployed across the **whole** backend fleet before
    the window (a straggler pre-7483 backend could emit a project-less trace delete whose cascade then bridges an
    empty-`project_id` span event the replay would miss), then assert the bridge holds none.
    `deletion_events_local` is a per-shard local table, so query it cluster-wide:
    ```sql
    SELECT count() FROM clusterAllReplicas('{cluster}', <database>.deletion_events_local)
    WHERE source_table = 'spans' AND project_id = '';
    ```
    If non-zero, do NOT proceed: an unexpected row means the replay would miss those deletes — investigate/drain them first.
14. **Privilege smoke test — run `delta_replay.sh` once BEFORE the backfill, anchored at the current instant.** The
    driver takes a literal timestamp carrying the ` UTC` marker, so read the clock first and pass what it printed:
    `SELECT toString(now64(6, 'UTC'))`, then `--backfill-start '<that value> UTC'`. Both statements execute but match
    nothing (no row has `created_at`/`last_updated_at` in the future, and the bridge holds no events after that instant),
    so it is a functional no-op against the data — while still proving the migration user can actually perform every kind
    of statement the cutover needs. Do this on a least-privilege user and you catch grant gaps in seconds instead of
    mid-window.
    > This is not hypothetical. On the first real-cluster run the deletion replay failed with
    > `Code: 497 … necessary to have the grant ALTER UPDATE(_row_exists)`: ClickHouse implements a lightweight `DELETE`
    > as `ALTER UPDATE _row_exists = 0`, so it authorises it as **`ALTER UPDATE` on that hidden column, not
    > `ALTER DELETE`**. The read-only drivers (`estimate.sh`, `verify.sh`) cannot surface this — only executing a
    > mutation can. Grant it **column-scoped** (`ALTER UPDATE(_row_exists)`) so the user can flip the delete mask
    > without being able to modify any real data column.
    >
    > **The smoke test covers the pre-swap shape only.** The post-swap reconciliation mutates the LIVE name, so it also
    > needs `ALTER UPDATE(_row_exists)` and `INSERT` on `spans` (or `spans_local` on a wrapped estate) plus `SELECT` on
    > `spans_pre_cutover_backup` — grants this no-op run cannot exercise, because those objects do not hold those roles
    > yet. Provision them from the privileges table before the window and confirm them on the prod-clone rehearsal: a
    > grant gap found there lands with the cutover already committed and the write gap still open.
15. **A dedicated, spans-scoped migration user, provisioned and revocable** — see
    ["Required privileges"](#required-privileges-provision-these-before-the-window). The traces cutover ran under a
    write exception that **OPIK-8263** is closing out; do not reuse it, and do not widen it. What is reusable is its
    *shape*: the same least-privilege split, the same column-scoped `ALTER UPDATE(_row_exists)`, the same
    "all four privileges per `RENAME`/`EXCHANGE` name" rule. **Plan the revocation with the grant**, not after the
    window: OPIK-8263 exists because that half was left to be done later last time.
16. **Decide, and record, that the `Distributed` wrap is not part of this window.** It is now reachable — OPIK-7799
    shipped `spansDistributedWrapEnabled` (default `false`) and the `SpanDAO` routing — so this is a choice rather
    than a constraint. Stopping at the `EXCHANGE` is the supported resting state, and it is the recommended one while
    the readiness gap below is open. If a wrap IS planned, it needs its own window, its own grants (stage C's and the
    wrap's), and an answer to
    ["the readiness gap"](#the-readiness-gap-that-opik-7799-left-open).
17. **Know that the span delete path is unpruned on both sides** — OPIK-8364 is open, so `SpanDAO.DELETE_BY_IDS` emits
    no partition predicate at all (the "Span-delete partition pruning does not exist yet" note at the end of
    ["The final cutover window"](#the-final-cutover-window) carries the detail). Nothing in the cutover changes it and
    nothing in the cutover depends on it, but delete latency through the window is worth watching as a throttle signal
    on the backfill.
18. Schedule during off-peak hours — and budget **days**, not hours, for the backfill. `estimate.sh` will say how many.

## The sequence

> **Capture BOTH streams, and keep the log.** Every driver writes its narrative to **stdout** and every statement's
> wall time — `clickhouse-client --time` — to **stderr**. Several of the numbers this runbook later asks you to record
> are the stderr ones: the deletion replay's time (step 2), the rollback's per-statement times, the reconciliation's.
> A plain `> run.log` keeps the narrative and silently drops all of them. Run each step as
> `./scripts/<driver>.sh ... 2>&1 | tee -a cutover.log` — and detached (`nohup` / `screen`) for the backfill, which
> runs for days.
>
> **Every driver takes `--help`**, which prints that driver's own option documentation — the same text as its file
> header, so the two cannot drift. Use it rather than guessing a flag: an unrecognised argument is refused, not
> ignored, but only after the driver has already been invoked.

0. **Survey and audit — run [`scripts/estimate.sh`](scripts/estimate.sh)** before anything writes. It is read-only, and
   it produces three things nothing later can: the **headroom verdict** against the projected destination size (the
   gate `backfill.sh` will enforce), the **two pre-write audits** — one sizing `--max-partitions-per-insert-block`,
   one counting the `parent_span_id` values the copy normalizes — and the ETA. Record all of it; those numbers set
   settings the first INSERT uses. See ["Blocker 1"](#blocker-1--disk-headroom) and
   ["Partition spread"](#partition-spread-and-the-one-setting-that-matters).
1. **Backfill — run [`scripts/backfill.sh`](scripts/backfill.sh)** (preferred). It iterates by week oldest→newest,
   splits each week adaptively into `created_at` sub-windows so no single window exceeds `--max-rows-per-insert`, and —
   copies each window with ONE statement (see "Batching and throttling"). It reconciles each window
   on a **dedup-aware** count — `uniqExact` of the **DESTINATION's** key `(workspace_id, project_id, trace_id, id)` on
   both sides, since raw `count()` differs between an un-merged source and a destination that deduped versions on
   insert, *and* since the source's own key additionally carries `parent_span_id` (see
   ["The dedup keys differ"](#the-dedup-keys-differ-and-it-changes-every-comparison-in-this-runbook)) — and aborts only
   on a genuine shortfall in a **settled** window (> 0.01%); a still-live window (its end in the future) legitimately
   diverges from concurrent writes/deletes — the delta and replay reconcile it — so that is logged, not fatal. The
   reconciliation counts source and destination **together after** each copy (a consistent snapshot): a delete is not
   bounded by `created_at`, so it can shrink even a settled window mid-copy, and comparing a stale pre-copy source count
   against a fresh post-copy destination count would abort falsely. It is idempotent and resumable (an already-copied
   window is skipped), and prints the `backfill_start` anchor for step 2 — captured once and persisted to `--state-file`,
   so a resumed run keeps the original anchor rather than minting a later one that would miss early-window deletes.
   `--pause-seconds` throttles between windows; `--dry-run` prints the window plan and each window's source row
   count. Preview then run:
   ```bash
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/backfill.sh --database opik --dry-run
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/backfill.sh --database opik \
       --pause-seconds 90 \
       --max-partitions-per-insert-block <estimate.sh audit 1 total_partitions, rounded up>
   ```
   **Expect days, not hours** — `estimate.sh` says how many — so run it detached (`nohup` / `screen`) and pass an
   ABSOLUTE `--state-file`. The default is CWD-relative, and on a multi-session copy that is how the anchor gets lost;
   the driver refuses to mint a fresh one onto a non-empty destination, so losing it costs a restart of the whole copy.
   It executes the reference statements in
   [`000001_backfill_spans_local_v2.sql`](scripts/db-app-analytics/000001_backfill_spans_local_v2.sql) — the script
   reads the marked block it needs and substitutes the window bounds, so the two never drift.
2. **[`scripts/delta_replay.sh`](scripts/delta_replay.sh)**
   (reference SQL [`000002_delta_and_deletion_replay.sql`](scripts/db-app-analytics/000002_delta_and_deletion_replay.sql))
   — delta-insert (anchored at `backfill_start`), then **deletion replay**. The replay runs with
   `lightweight_deletes_sync = 2`, so it returns only once the delete mutation has applied on **every** replica.
   No config change precedes this step — the procedure takes no hold on writes.
   The driver passes `--time`, so clickhouse-client prints each statement's wall time in seconds (delta-insert first,
   deletion replay second) — **record the second value**: it is the *first half* of the final-delta→`EXCHANGE` gap,
   which is the window step 5 sweeps back. The second half is `exchange_and_wrap.sh`'s own run through the swap, which
   that driver reports (step 4). Without `--time` a bare `--query` prints no timing at all.
   ```bash
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/delta_replay.sh --database opik --backfill-start '<ts> UTC'
   ```
   It prints two things step 5 needs. **`RECORD delta_start=`** is the instant the pass began reading, and it is the
   gap anchor `reconcile.sh` sweeps from — record it with the ` UTC` marker, exactly like `backfill_start`. **The
   pending-delta size** is how many rows the source took *while the pass ran*, i.e. what an `EXCHANGE` issued now would
   strand in the parked backup. Re-run the driver to watch it shrink; it will **not reach 0** while the source is live,
   which is precisely why reconciliation happens after the swap and not before it. Losing `delta_start` is not an
   escalation — widening the gap window is free, so `backfill_start` is always a valid fallback.
3. **QA — run [`scripts/verify.sh`](scripts/verify.sh)** (see "Verifying the migration"): confirm the copy altered no
   data before committing the swap. Run it after step 2; it can be re-run after the swap, bounded as
   ["Verifying the migration"](#verifying-the-migration-qa) describes.
4. **[`scripts/exchange_and_wrap.sh`](scripts/exchange_and_wrap.sh)** (reference SQL
   [`000003_exchange_and_wrap.sql`](scripts/db-app-analytics/000003_exchange_and_wrap.sql)) — first **gates on a settled
   replication state** (see ["The replication-settle gate"](#the-replication-settle-gate) — it polls rather than
   demanding an instantaneous zero, so live ingest churn does not abort it while a genuinely lagging replica still
   does; `--force` overrides, and the Go/No-Go forbids that in production); then records and
   prints `cutover_start`, runs `EXCHANGE TABLES ... ON CLUSTER`, renames the displaced old data to
   `spans_pre_cutover_backup` (see "Naming and the parked backup") and prints `RECORD exchange_done=`. It **stops there
   by default** (EXCHANGE only, leaving `spans` a `MergeTree` where deletes still work); the `RENAME` + `Distributed`
   wrap runs only with `--with-wrap`. It ends with a **CUTOVER INCOMPLETE** banner naming the step-5 command, because
   the swap leaves the tail writes parked (["The final cutover window"](#the-final-cutover-window)); reconcile, then
   verify (["Verifying the migration"](#verifying-the-migration-qa)).

   **Check the per-host `ON CLUSTER` rows before you go further.** Each `ON CLUSTER` DDL prints one row per host
   (`host, port, status, error, hosts_remaining, hosts_active`); status 0 with an empty error means that host applied
   it. This is the only place a *partial* application surfaces: the driver's topology guards read `system.tables` on
   the **connected node only**, so a host that missed the swap is invisible to every later step, and both the deferred
   wrap and `finalize.sh` assume the cluster is uniform. If any host reports non-zero, stop and reconcile it before
   running anything else.
   ```bash
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/exchange_and_wrap.sh --database opik \
       --backfill-start '<anchor from backfill.sh> UTC' --confirm-retention-paused \
       --confirm-columns-non-nullable
   ```
   Every EXCHANGE path requires three things: `--backfill-start` (for the final deletion replay),
   `--confirm-retention-paused` (retention deletes bypass the bridge, so a retention sweep in the window would leak
   across the swap) and `--confirm-columns-non-nullable` (the read side must already speak the successor's sentinel
   representation the instant the successor is live — see
   ["The one rolling restart"](#the-one-rolling-restart-spancolumnsnonnullable)).

   > **On spans, stop here by default.** `--with-wrap --confirm-maintenance --confirm-daos-retargeted` now works —
   > OPIK-7799 shipped `spansDistributedWrapEnabled` and the `SpanDAO` routing behind it — but the flag defaults to
   > `false`, the post-`EXCHANGE` estate is complete and supported on its own, and the wrap carries a readiness gap
   > OPIK-7799 did not close (below). Treat the wrap as a separate, later change with its own window, not as the tail
   > of this one.

> **The passage below describes the traces readiness behaviour, which spans does NOT yet have.** It is retained
> because it is what a spans probe would do, and because the traces analogue (OPIK-7773) is the model to copy. Worth
> knowing before you plan a wrap:
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

> **HARD PREREQUISITE for the wrap (step 4, part 2): enable `spansDistributedWrapEnabled` so span mutations target `spans_local` first (OPIK-7799).** A
> `Distributed` table supports `SELECT` and `INSERT` but **not** mutations. Verified on ClickHouse 26.3:
> - `DELETE FROM <distributed>` → `Code 36 BAD_ARGUMENTS: DELETE query is not supported`
> - `ALTER TABLE <distributed> DELETE` → `Code 48 NOT_IMPLEMENTED: Distributed doesn't support mutations`
>
> So the moment the wrap is applied, **both** the product's delete-by-id path **and** the retention
> sweep (`DELETE_FOR_RETENTION` / `deleteForRetentionBounded`) start returning 500 against `spans`. This is prep work
> that shipped **before** the wrap (OPIK-7799): `SpanDAO` renders its mutation table through a single toggle,
> `databaseAnalyticsDataModel.spansDistributedWrapEnabled`. Set it **`true` in lockstep with applying the wrap** so those
> deletes run against `spans_local`; reads and inserts stay on the Distributed `spans`. The flag is **startup-bound**
> (read once at boot; no hot-reload), so making it "live across the fleet" means a **completed rolling restart of every
> backend instance** — and, on spans, one you **cannot observe per instance**: OPIK-7799 shipped the flag and the
> routing but **no** `clickhouse-spans-topology` readiness check, so nothing reports which side of the cutover an
> instance believes it is on. See ["the readiness gap"](#the-readiness-gap-that-opik-7799-left-open). A mismatch is
> still **fail-loud at the point of use**: a stale-`false` instance issues `DELETE` against the `Distributed` `spans`
> (code 36/48), a stale-`true` instance against an absent `spans_local` — both 500 the delete path. What is missing is
> the earlier signal, not the eventual one. While it is `false` (the deploy
> default, and correct while `spans` is still a `MergeTree`) the deletes target `spans` directly. **General rule (splits
> by kind of change):** row mutations (`DELETE`, `ALTER … DELETE`) and `MATERIALIZE COLUMN` / `ADD INDEX` / `MODIFY TTL`
> target **`spans_local` only** — the `Distributed` `spans` rejects them (code 36/48), so a slip fails loudly; but
> `ADD` / `DROP` / `MODIFY COLUMN` (the shape of every span schema migration) must be applied to **both** `spans_local`
> **and** the `Distributed` `spans` — the wrapper accepts them as metadata-only, and targeting only `spans_local` leaves
> the wrapper without the column so reads fail with code 47. The `EXCHANGE` alone is the data cutover and leaves `spans` a
> `MergeTree` where deletes still work — which is why the wrap is **opt-in** (`--with-wrap`) and the default stops after
> the EXCHANGE. Defer the
> wrap until the retarget flag is wired into the deploy. The wrap is the sharding-readiness layer, not the cutover.
>
#### The readiness gap that OPIK-7799 left open

OPIK-7799 shipped the two things that make the wrap *possible* — `spansDistributedWrapEnabled` and the `SpanDAO`
routing behind it — and left one thing out: **there is no `ClickHouseSpansTopologyHealthCheck`.**
`clickhouse-traces-topology` asserts the trace flag only, as `config.yml`'s own comment on the spans flag says.

**It is filed as OPIK-8376**, the spans counterpart of OPIK-7773 — and it is already **a prerequisite of OPIK-8382,
the production execution on spans**. So deferring the wrap here is not a workaround for a missing piece; it is the
sequence the epic already plans. OPIK-8377 is the CI half of the same problem.

**What that costs.** On traces, a flag/topology mismatch makes the pod unready, so a half-completed rollout stalls the
deploy and is visible before any user is affected. On spans there is no such probe, so the same mismatch is **invisible
until the first span delete fails** — a trace delete cascade or a retention sweep returning 500. The failure is loud
when it arrives; the problem is that it arrives from a user's action rather than from a readiness check, and only on
the instances that are wrong.

**This runbook's position: do not wrap until that is resolved.** The recommendation is not "the wrap is unsafe" — it is
that the wrap's one risky interval (the unavoidable mismatch window between the config rollout and the DDL) is the
exact interval the missing probe would have covered, and the cutover gains nothing by taking it. The post-`EXCHANGE`
estate is complete; the wrap is sharding-readiness and can land whenever its own prerequisites are met.

**Three ways to close it, in preference order.** OPIK-7799's hand-off asked this runbook to make the call; whoever
plans the wrap owns the execution:

1. **Land OPIK-8376** — the `ClickHouseSpansTopologyHealthCheck`, mirroring `ClickHouseTracesTopologyHealthCheck`
   (OPIK-7773, PR #7948). This is the recommendation: it makes the spans wrap as safe as the traces one, it is already
   scoped and sequenced ahead of OPIK-8382, and its hard prerequisite (OPIK-7799) is now met. Its acceptance criteria
   include documenting the rollout-ordering behaviour alongside this runbook — which is what the passage above is.
2. **Wrap inside a maintenance window** where span deletes are paused, so the mismatch interval cannot be observed by a
   user. Cheaper, and adequate if the wrap is a one-off.
3. **Accept the exposure** with a deliberate, recorded decision and a monitoring plan for span-delete 500s across the
   rollout. Only reasonable on an estate with little delete traffic.

Whichever is chosen, `exchange_and_wrap.sh` does not change: it already gates on `--confirm-daos-retargeted`, which the
operator can now truthfully assert. The gate is the flag's state, not the probe's existence.

> **The cutover's own mutations already handle a wrapped estate, so this gap is about the PRODUCT's deletes, not the
> tooling's.** `reconcile.sh` detects a `Distributed` `spans` and routes its sweep's delete at `spans_local` — the same
> resolution `SpanDAO#selectSpansMutationTable` makes from the flag — and `rollback.sh --sentinel-repair-only` refuses
> outright on a `Distributed` `spans` rather than mutating a routing table. Verified against `SpanDAO`: the three
> mutation paths it routes (the cascade delete and both retention sweeps) are the only ones, and it has no
> ALTER/OPTIMIZE path at all, so the DDL split below is Liquibase's contract rather than the DAO's.

> **"In lockstep" cannot mean simultaneous — plan for a short mismatch window.** The toggle is a
> config push plus a rolling restart; the wrap is a DDL statement. They cannot land at the same instant, so
> one of two windows is unavoidable:
> - **toggle first** (recommended): from the moment the last backend comes up with `true` until the wrap
>   completes, every span delete targets a `spans_local` that does not exist yet →
>   `Code: 60 UNKNOWN_TABLE`. Reads and writes are unaffected.
> - **wrap first**: from the swap until the rolling restart finishes, deletes hit the `Distributed` `spans`
>   → `Code: 36`. Same blast radius, but it also exposes the cross-node `ON CLUSTER` skew to reads.
>
> **With that check in place the mismatch window is also a readiness window.** `clickhouse-spans-topology` would be a
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
> **Monitoring consequence of the flip:** `system.parts` only knows `spans_local` post-wrap, so the
> `opik.clickhouse.partition.*` parts gauges relabel from `table="spans"` to `table="spans_local"`, while the
> lightweight-delete-mask gauge (read through the wrapper) stays labelled `spans`. Any dashboard/alert keyed on
> `table="spans"` goes blank when the wrap lands — update them in the same window, or point
> `PARTITION_METRICS_LWD_TABLES` (default `spans,spans`) at `spans_local` for label consistency.
>
> **Applying the deferred wrap later:** once the retarget flag (`spansDistributedWrapEnabled=true`) is live across the
> backend fleet, run
> `exchange_and_wrap.sh --database opik --wrap-only --confirm-maintenance --confirm-daos-retargeted` — it validates the
> post-EXCHANGE topology and applies **only** the wrap on the already-swapped `spans` (no second EXCHANGE, no new
> `cutover_start`, and no replication-settle gate — neither of its signals describes this path, see
> ["The replication-settle gate"](#the-replication-settle-gate)). Its topology guard reads the **connected node**, as
> the EXCHANGE path's does, so it assumes the earlier `EXCHANGE` applied on every host — which is what the per-host
> rows in step 4 are for. Confirm those before deferring the wrap, not after.
> `--confirm-daos-retargeted` is required for **any** wrap (same-run or deferred), since the wrap makes `spans`
> `Distributed` and breaks the delete/mutation DAOs until `spansDistributedWrapEnabled=true` routes them at `spans_local`. To roll the wrap back, use
> `rollback.sh --stage C`, then set `spansDistributedWrapEnabled` back to `false` with the same rolling restart so
> post-rollback deletes target the `MergeTree` `spans` again — a stale-`true` instance would `DELETE` against the
> now-absent `spans_local` and 500.
>
> The wrap is **gapless per node**: it pre-builds the `Distributed` wrapper under a temp name, then one atomic
> multi-target `RENAME` rotates the data to `spans_local` and the wrapper into `spans`, so `spans` is never absent on
> a node. A brief **cross-node** `ON CLUSTER` propagation skew still exists (as for any `ON CLUSTER` DDL), during which a
> Distributed query could route to a not-yet-created `spans_local` on a lagging node — so **both** wrap paths
> **require `--confirm-maintenance`** (quiesce ingestion / take a maintenance window). The exposure is identical on the
> two paths: the failing query is a `SELECT`, so nothing done on the ingestion side reduces it, and being in the same
> run as the `EXCHANGE` buys `--with-wrap` nothing here (OPIK-8239).
>
> **Wrap flags** (`exchange_and_wrap.sh`, mutually exclusive; default is EXCHANGE-only): omit them (or pass
> `--skip-wrap`, an explicit alias) to run the EXCHANGE and stop; `--with-wrap` to also apply the wrap in the same run;
> `--wrap-only` to apply just the deferred wrap later.

5. **Reconcile the gap — run [`scripts/reconcile.sh`](scripts/reconcile.sh) immediately after step 4** (reference SQL
   [`000006_post_swap_reconciliation.sql`](scripts/db-app-analytics/000006_post_swap_reconciliation.sql) and its
   postcondition [`000006_verify_reconciliation.sql`](scripts/db-app-analytics/000006_verify_reconciliation.sql)).
   **This step is not optional and it is not a check** — it is the second half of the data cutover. Everything written
   to the old table between step 2's last delta pass and the `EXCHANGE` is sitting in `spans_pre_cutover_backup`,
   absent from live `spans`.
   ```bash
   CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/reconcile.sh --database opik \
       --gap-start '<delta_start from step 2> UTC' --swap-done '<exchange_done from step 4> UTC' \
       --confirm-retention-paused
   ```
   It derives the direction from the live topology (no `--direction` flag to get wrong), gates on the cluster-wide
   settle (the same gate as step 4, with a post-swap scope — see
   ["The replication-settle gate"](#the-replication-settle-gate)), then **sweeps → replays deletes → asserts a
   four-count postcondition**, repeating up to `--max-passes` (default 3) and failing loudly rather than reporting
   progress. `--report-only` returns the counts and issues no mutation. It reads the postcondition before mutating
   anything, so a second run on a reconciled estate is a no-op that exits 0.
   > **Why this cannot be done before the swap.** The source is live, so every pre-swap pass opens a new gap — iterating
   > the delta shrinks it and then stops improving, which is what was observed on the cutover that motivated this step.
   > After the swap the parked table is **frozen**, so the sweep converges by construction and its postcondition is a
   > gate rather than a snapshot.
   >
   > **It reconciles ONE SHARD, and says which.** Every statement it issues is shard-local, and so is the forward
   > postcondition — a per-shard run is therefore correct, but a single `RECONCILED` certifies only the shard it
   > connected to, while `finalize.sh` drops the parked backup `ON CLUSTER`. So on a cluster reporting more than one
   > shard the driver refuses without `--confirm-single-shard`, and with it labels the verdict `SCOPE: …`; an unreadable
   > shard count fails closed, the same way `rollback.sh`'s guard does. On the single-shard estate this procedure
   > targets today, neither path triggers.
   >
   > **It also reports `leaked_delete_keys`, which is NOT part of the gate.** Non-zero means captured deletes are still
   > live on the successor — the residual the replay's staleness scope cannot prevent, because `last_updated_at` is
   > client-supplied (see the residuals in ["The final cutover window"](#the-final-cutover-window)). Those keys are
   > still in the bridge, so they can be re-applied by hand; the reconciliation itself is complete either way.
6. **QA over the reconciled range — `verify.sh --window-from/--window-to`** for the payload-level picture over exactly
   what step 5 swept, alongside the usual bounded weekly compare (see "Verifying the migration"):
   ```bash
   ./scripts/verify.sh --database opik --old-table spans_pre_cutover_backup --new-table spans \
       --window-from '<delta_start>' --window-to '<exchange_done, UTC>'
   ```
   > **The upper bound is the swap, not `now`.** `spans_pre_cutover_backup` froze at the `EXCHANGE`; live `spans` keeps
   > taking writes. A span **created** after the swap therefore exists on the live side only, and `000005` bounds
   > `created_at` identically on both sides and passes a window only when `src_rows = dst_rows` — so a window extending
   > past the swap reports a mismatch for every post-swap span. Under production write rates that is a guaranteed
   > failure within seconds of the swap, and it would say nothing about fidelity. Use the same `exchange_done` that
   > bounded step 5's sweep, so this compare covers exactly what was swept and nothing that could not have been.
   This compares the spans **created** in that range — `000005` bounds on `created_at`, which the weekly mode's
   partitioning and its superseded-version logic require — so a span created earlier and merely *updated* in the gap is
   not in it. That set is not left uncovered: it is exactly what step 5 reports as `stale_keys` and
   `payload_mismatch_keys`, which compare by version rather than by window. The `PASSED` line states the distinction, so
   the pass cannot be quoted as broader than it is.
   Then work the ["When the cutover is done"](#when-the-cutover-is-done) checklist before the soak.

**Dedup note.** After the delta, a row can have two physical versions on `spans_local_v2` (the backfilled one and the
delta one). This is normal — `ReplacingMergeTree` collapses them on merge / under `FINAL` / `LIMIT 1 BY id`, highest
`last_updated_at` winning. Do not "fix" it.

### The one rolling restart (`spanColumnsNonNullable`)

Getting to the `EXCHANGE` needs **one** rolling restart, and it carries no *steady-state* latency cost: rolling out
`databaseAnalyticsDataModel.spanColumnsNonNullable = true` (prereq 6) to every backend instance beforehand. Nothing is
restarted afterwards to undo it. The optional `Distributed` wrap spends a restart of its own for
`spansDistributedWrapEnabled` (see the wrap prerequisite) — that one belongs to the wrap, is only paid if you apply
it, and likewise leaves no standing latency behind. What each roll *does* cost while it is in flight is ingestion
capacity, so plan for that (see the end of this section).

> **The async-insert knob is untouched, and OPIK-7686's decision stands.** The three
> `ANALYTICS_DB_ASYNC_INSERT_*` knobs remain valid production tuning, deliberately absent from the chart's `values.yaml`
> (OPIK-6880, #7675) and settable as a deployment-level env var — see the
> [self-host troubleshooting guide](../../../opik-documentation/documentation/fern/docs-v2/self-host/troubleshooting.mdx)
> for how and when to use them. What ended is only the **cutover's dependence** on raising one of them for the window
> (OPIK-8239) — a dependence that never delivered what it promised; see "The final cutover window".

`exchange_and_wrap.sh` will not run an `EXCHANGE` without `--confirm-columns-non-nullable`, which is the operator
asserting this roll has landed everywhere. Like `--confirm-retention-paused` it is an assertion, not a check: ClickHouse
holds no record of what a backend instance believes, so nothing the driver can query distinguishes a fleet that has
rolled from one that has not.

**It takes effect only on a backend restart — so confirm the restart finished before continuing.** The backend receives
the flag through the container environment (`envFrom.configMapRef` under Helm), which Kubernetes injects at container
start only: editing the ConfigMap does not reach a running pod. **How that restart is triggered is deployment-specific**
— the chart ships no automation for it, so some deployments run a ConfigMap watcher that rolls the workload on its own
while others need an explicit `kubectl rollout restart deployment/opik-backend`. Know which one yours is *before* the
window. Either way the flag has to be live on **every** instance before the `EXCHANGE` (see "The final cutover window"),
so verify rather than assume:

```bash
kubectl rollout status deployment/opik-backend -n <namespace>
kubectl get cm opik-backend -n <namespace> \
    -o jsonpath='{.data.ANALYTICS_DB_DATA_MODEL_SPAN_COLUMNS_NON_NULLABLE}{"\n"}'
# and confirm no surviving pod predates the roll:
kubectl get pods -n <namespace> -l component=opik-backend \
    -o custom-columns=NAME:.metadata.name,START:.status.startTime
```

These names are what the chart renders by default — Deployment and ConfigMap `opik-backend`, label
`component=opik-backend`. They are derived from `opik.name`, so a `nameOverride` (or a parent chart supplying one) moves
all three; substitute your release's actual names. The ConfigMap read proves the *value* is there; it does not prove
every pod picked it up, which is what the rollout status and the pod start times are for — and neither proves the
**behaviour**, which only the positive per-instance check in prereq 6 does.

**The restart itself costs ingestion capacity** (rolling-update `maxUnavailable`, plus any PodDisruptionBudget), so do
it while there is slack — not between the final delta and the `EXCHANGE`.

### The replication-settle gate

`exchange_and_wrap.sh` gates the swap on replication having settled, because the `EXCHANGE` is metadata-only and
near-instant but each replica reads its own local parts afterwards: a replica that does not yet hold every part would
serve an incomplete table. Both signals are read across every replica via `clusterAllReplicas`, so one connection sees
the whole cluster. The queries are the `settle-sample`, `settle-queue-detail` and `settle-mutation-detail` blocks of
[`000003_exchange_and_wrap.sql`](scripts/db-app-analytics/000003_exchange_and_wrap.sql); the driver renders all three
before it starts polling, so a mis-marked block fails the run up front rather than while reporting a failure. The gate
does not run for `--wrap-only`, which performs no swap.

**It polls, and it judges the two signals differently.** On a multi-replica cluster under live ingestion the
`replication_queue` count is intermittently non-zero by construction — a `GET_PART` entry exists for every part a
replica has not yet fetched — so demanding an instantaneous 0 would abort on ordinary churn and push the operator
toward `--force`, which the Go/No-Go forbids. Hence:

| Signal | Judgement |
|---|---|
| unfinished **mutations** on `spans_local_v2` | **Unconditional** — none, within `--settle-timeout` (default 120s). What this catches is a mutation left behind by an earlier step or by manual intervention. It does **not** cover the final deletion replay, which the driver issues *after* this sample: that one is covered by `lightweight_deletes_sync = 2` in its own block, which returns only once every replica has applied the mask, and the driver asserts the setting is still present before running it. |
| the **replication queue** on `spans` / `spans_local_v2` | **Stuck-ness, not depth.** Counts only `GET_PART`/`ATTACH_PART` — the entries that mean a replica lacks data. Merges and mutations also sit in this queue and say nothing about completeness; counting them would fail the gate on the large merges that follow a backfill. It passes the moment the queue drains to 0. If it has not drained by the deadline, the gate reports the oldest entry's age, the highest `num_tries` and whether any entry carries a `last_exception` — an entry older than 60s, more than 3 retries, or any recorded exception means a replica is genuinely lagging and the gate **fails loudly, printing the offending entries per replica**. A queue that is busy but not stuck is accepted, with the numbers printed so the operator sees what was accepted. |

**Budget for the wait.** The queue verdict is a **snapshot** over the last sample read — nothing compares consecutive
samples, in either the polled or the single-sample case. Polling buys exactly two things: time for the queue to drain,
and time for a genuinely stuck entry to age past the thresholds. So the gate returns early only on a drained queue;
otherwise it spends the whole budget before accepting, and because it sits between the final delta and the `EXCHANGE`,
that wait lands in the tail write-gap. It is not wasted — aging is the only detection this gate has, and accepting the
first not-stuck sample would make the default no stronger than `--settle-timeout 0`. Restricting the count to
`GET_PART`/`ATTACH_PART` is what makes the early exit reachable in practice: those entries clear continuously, whereas
the merge backlog that follows a backfill does not.

`--settle-timeout` accepts 0–7200s (raised from the traces runbook's 3600 — see the threshold table); raise it for a slow-but-progressing cluster, at the price of a longer tail. The
driver prints the wait alongside its elapsed-through-`EXCHANGE`, so the gap can be sized with it included. `--force`
skips the gate and is a production No-Go: the gate is permissive enough that reaching its failure path means something
is genuinely wrong.

**`reconcile.sh` runs the same gate with a post-swap scope.** It reads the same three blocks — the table sets are
placeholders, so each driver renders its own scope — and needs the settle for two reasons of its own: its sweep reads
the parked table **mask-honored**, and its postcondition joins two tables and so reads **one** replica (`clusterAllReplicas`
would return a copy of each side per replica and multiply both). What the swap changes is *which* table is quiet:

| Signal | Scope after the swap | Judgement |
|---|---|---|
| unfinished **mutations** | the **parked backup** only | **Unconditional.** That table is frozen, so anything still applying to it is a user delete that fired before the swap and has not landed on this replica yet — and the mask-honored sweep would read the row as live and copy a deleted span back. It drains rather than recurring, so demanding zero is reachable. |
| unfinished **mutations** | the **live** table — **deliberately not gated** | After the swap the live table takes user deletes continuously, each an ordinary asynchronous mutation, so requiring zero would abort on healthy traffic. Nor is it a hazard: a delete still applying leaves the row visible, which the postcondition reads as *present*, never as missing; and deletes bridged across the swap are re-applied by the sweep's own replay, which carries `lightweight_deletes_sync = 2`. |
| the **replication queue** | both tables **and `deletion_events_local`** | **Stuck-ness, not depth** — same thresholds, same reasoning as above. Either span table short of a part on this replica skews the postcondition's join. The **bridge** is in scope for a reason of its own: it is a `ReplicatedMergeTree`, and every read the reconciler makes of it resolves on the one replica it is connected to — the sweep's exclusion, the replay's bridge match and resurrection guard, the postcondition's exclusion and the leak-check advisory. A bridge part this replica has not fetched is invisible to all of them at once, so a genuinely deleted span is swept back while the gate reports clean. Stuck-ness is still the verdict, so a busy-but-not-stuck bridge queue is accepted once the timeout expires: that narrows the window to the drain time rather than closing it. |

The wait costs no cutover tail here, since the swap has already committed — but every second of it is a second the
gap-window spans are still absent from live reads, so it is not free either. And it is spent **per gate**: once before
the first postcondition read and once per pass, so on a queue that stays busy-but-not-stuck the worst case is
`(1 + --max-passes) × --settle-timeout`. A drained queue returns immediately, which is the normal case off-peak.

### The final cutover window

**What the swap guarantees.** Nothing in this procedure parks an insert, and it does not need to: the `EXCHANGE` is
**atomic per node** (it requires an Atomic database, the default), so `spans` is never absent anywhere and every
concurrent insert commits to a valid table — the old storage if it reaches a node pre-swap, the new one if post-swap.
Neither errors, and no insert is rejected. **Deletes** are covered up to `cutover_start` by step 4's final replay.

**Writes in the tail are not held by the swap.** Two sources leave them in the table that becomes the parked backup:

- the **final-delta→`EXCHANGE` gap** — writes after the last delta read and before the swap;
- the **cross-node `ON CLUSTER` skew** — `EXCHANGE` is atomic per node but not across nodes, so while it propagates,
  inserts routed at a not-yet-swapped node still land in the old table.

Those rows are **not destroyed** — they sit in `spans_pre_cutover_backup` and stay recoverable until `finalize.sh`
retires it. **Step 5, `reconcile.sh`, is what carries them into the live table and proves it did** (OPIK-8238). Two
properties make it more than a copy: it is driven by *version* rather than key presence — a span merely *updated* in
the tail is already on the successor at an older `last_updated_at`, so a presence check would find nothing to do — and
it does not resurrect a delete that fired after the swap.

**Nothing before the swap can do that job.** Pre-swap reconciliation cannot converge, because the source is live: every
pass reads a snapshot and opens a new gap behind itself. Iterating the delta shrinks the gap and then stops improving —
that floor is what the pending-delta count in step 3 shows you. **Post-swap it converges by construction**, because the
parked table is frozen. That is the whole reason the reconciliation step sits after the `EXCHANGE` rather than before
it.

The tail is still worth running tightly, because its length is what decides how many writes the sweep has to carry:

1. **Roll out `spanColumnsNonNullable = true` to every backend instance**
   (["The one rolling restart"](#the-one-rolling-restart-spancolumnsnonnullable), and the flip's own note below). Do it
   while there is slack, not between the final delta and the `EXCHANGE`.
2. Do the QA verify on an **earlier** pass (it can take minutes on a large table — do not let it be the last thing
   before the swap).
3. Run a **final** `delta_replay.sh` as the last write-facing step before the swap, and **record the
   `RECORD delta_start=` it prints**: that is the gap anchor step 5 sweeps from. The pending-delta size printed
   alongside is how large the gap is right now — re-run to shrink it, and stop when it stops improving rather than
   chasing a zero that cannot exist while the source is live.
4. Run `exchange_and_wrap.sh --backfill-start '<anchor> UTC' …` **immediately** after it. It captures `cutover_start`,
   then runs a **final deletion replay** from `backfill_start` right before the swap — so deletes bridged in the
   `[final delta_replay, cutover_start)` gap are masked on the successor rather than leaking (that gap is covered by
   neither the earlier forward replay nor the rollback reverse-replay, which starts at `cutover_start`). Deletions only.
   The `EXCHANGE` itself is metadata-only, but this driver is **not** instantaneous: the settle gate ahead of it polls
   for as long as the cluster needs, to `--settle-timeout`, and every second of that is tail. The driver prints its
   elapsed-through-`EXCHANGE` with the gate wait itemised — that figure plus step 3's replay time is the gap. It ends by
   recording `exchange_done` and printing the **CUTOVER INCOMPLETE** banner.
5. Run `reconcile.sh --gap-start '<delta_start> UTC' --swap-done '<exchange_done> UTC'`, **immediately** again. It
   sweeps the parked writes into the live successor, re-applies the deletes bridged across the swap, and does not exit 0
   until its four-count postcondition says the gap is closed. Pass the **recorded** `exchange_done`, never an estimate:
   the sweep and the gate apply the same `--swap-done` exclusion, so a value earlier than the real swap drops a key
   deleted and re-created in between from *both*, leaving that span missing under a clean gate. The driver's header
   states both directions of error; only `--gap-start` is free to widen.

Keep step 3→4 short, and step 4→5 shorter:

| In the `[last delta, EXCHANGE]` gap | Covered by |
|---|---|
| **Deletes** bridged before `cutover_start` | step 4's final deletion replay |
| **Writes** — all of them, since nothing holds any of them across the swap | **step 5's sweep** |
| **Deletes** bridged after step 4's replay read but before the swap | **step 5's post-swap replay**, whose resurrection guard reads the frozen backup and is therefore race-free |

**Five residuals remain, all narrow. The first three are on the delete side and share one mitigation — so state it
once: quiesce user DELETES across the swap, not merely reads.**

- A delete whose bridge row lands *after* step 5's read is invisible to it.
- **A delete bridged below `exchange_done` but effective after the swap is undone by the sweep**, because the key is
  live in the frozen backup and the bound does not exclude it — the sweep re-inserts it and the post-swap replay's
  resurrection guard spares it. Two things put a delete there:
  - *Capture ordering.* The bridge row is written **before** the `DELETE` executes, so a delete can be bridged below
    the bound while its `DELETE` lands on the successor after the swap. One statement wide.
  - *The bound trails the swap.* `exchange_done` is read after the `exchange` block returns, and that block holds the
    `EXCHANGE` **and** the `RENAME` that parks the backup, both `ON CLUSTER`, so the RENAME and its distributed-DDL
    wait sit inside the window. Stamping the clock between the two would shrink this and **not** close it: the
    `EXCHANGE` is itself `ON CLUSTER`, so the hosts' commit skew stays inside the window wherever it is read. The
    Go/No-Go records that interval; tighten the capture point only if it measures in seconds.

  Either way the span has to be written *and* deleted inside the gap window, with the swap falling between the
  delete's capture and its effect — and quiescing deletes across the swap empties the window, which is why this shares
  the mitigation above rather than getting its own.
- **A pre-swap span carrying a client-supplied future `last_updated_at` keeps its captured delete.** `lastUpdatedAt`
  is writable through the API and validated only as "before 2300", and `SpanDAO` binds it verbatim on the batch-ingest
  path — so such a row falls outside the post-swap replay's `created_at AND last_updated_at < exchange_done` staleness
  scope and is spared. The predicate stays that way deliberately: scoping on `created_at` alone would instead mask a
  post-swap *patch* of a pre-existing span (the merge path preserves `created_at`), destroying a write that exists only
  on the successor. Over-sparing leaves a deleted span visible with its key still in the bridge, so it can be
  re-applied; over-masking cannot be undone. **Step 5 reports this one as `leaked_delete_keys`**, comparing the live
  row's version against the versions the frozen backup held — which separates a leak from a legitimate post-swap
  re-creation and needs no timestamp at all. That comparison is only possible as a *read*: it needs an unmasked read of
  the backup, and a lightweight `DELETE` **accepts `apply_deleted_mask = 0` and then ignores it**, so folding the check
  into the replay would produce a statement that reports success having masked nothing. Clamping future client
  timestamps at ingestion is the durable fix and is not this procedure's to make.
- **A post-swap write that REGRESSES `last_updated_at` is overwritten by the sweep, and the gate reports it clean.**
  The same writable column as above, in the other direction. The sweep re-inserts the parked payload,
  `ReplacingMergeTree` keeps the higher version, and a client-supplied `last_updated_at` below the parked row's loses
  the live write; the postcondition then compares the parked payload against itself and returns zeros, so `newer_keys` does not
  see it either. It needs a gap-window key *and* a post-swap write that moves `last_updated_at` backwards. Nothing in
  the reconciliation can fix it: skipping keys already live would abandon exactly the stale and partial rows the sweep
  exists to repair, and re-stamping the version would clobber legitimate newer writes. Clamping client timestamps at
  ingestion is the durable fix here too.
- Between the `EXCHANGE` and the sweep, gap-window spans are briefly absent from live reads — and `SpanDAO`'s merge
  path reads the old row to preserve `created_at`, so an update landing in that hole re-stamps it.

The first and last are reasons to run step 5 *immediately*; the middle two are reasons to quiesce deletes rather than to
skip the sweep, since skipping it loses every write in the gap instead.

*Rejected alternatives, recorded so they are not re-proposed (OPIK-8239).* **Widening the async-insert buffer**
(`asyncInsertBusyTimeoutMaxMs`) to hold writes across the swap does not work here. The backend serves reads and writes
through one shared R2DBC `ConnectionFactory` (`DatabaseAnalyticsModule`), and writes run `wait_for_async_insert=1`, so
a parked insert holds a request thread and a connection for its whole wait and reads contend with it on the same
transport: the entire ClickHouse-backed surface slows, not just ingestion. It also covers only part of the gap, because
the adaptive buffer flushes on whichever of the busy timeout, `async_insert_max_data_size` or
`async_insert_max_query_number` fires first, and at real span row widths the size and count limits bind well before
the timeout. And because the ceiling arrives through the container environment, applying
it and reverting it are two fleet-wide restarts, which brackets the degraded period and puts a floor under its length.
**`wait_for_async_insert=0`** removes the latency but discards delivery confirmation exactly when it matters most, and
still needs both restarts. **A smaller ceiling** keeps every one of those costs for proportionally less of an already
partial benefit.

**The `spanColumnsNonNullable` flip (mandatory, and why it goes first).** The successor stores `end_time`/`ttft` as
non-nullable epoch/NaN sentinels, and the flag is what makes the app agree with that representation — sentinel binds on
write, and sentinel→`null` translation on read, filter and sort. It is a **config** change rolled out across the fleet
(not atomic), unlike the metadata-only `EXCHANGE`, so it cannot be flipped at the same instant; roll it out to `true` on
**all** instances **before** the `EXCHANGE`.

*Why before, not after.* Not because writes would break — they would not (see prereq #6: a `null` bind is silently
converted to the column DEFAULT, which is the sentinel, so writes succeed on either setting). It goes first because the
**read** side must already speak sentinel the instant the successor is live under the name `spans`: while the flag is
`false` against the successor, an absent `end_time` reads back as `1970-01-01` rather than `null`, and absent-value
filters/sorts are wrong. Doing it first is safe because `true` is write-compatible with **both** schemas — binding the
epoch/NaN sentinel into the *still-Nullable* source column is valid — and the copy machinery tolerates the resulting
NULL/epoch mix (backfill `coalesce`, verify normalizes both to `0`).

*Two caveats for the pre-swap window, so keep it short and off-peak.* Both affect rows written while the flag is `true`
and `spans` is still the Nullable original — and note the window does not close at the `EXCHANGE`: on a rollback it
**reopens** until the flag-revert restart lands on every instance, so the same rows keep accruing then (see "Rolling back
the `spanColumnsNonNullable` flip").

- "`end_time` is empty"-style **filters** use sentinel logic against the still-Nullable table.
- **The sentinels persist in the original, and `duration` is computed wrong from them.** An absent value is written as
  the epoch / `NaN` sentinel instead of `NULL`, against a column whose convention is `NULL`. The blast radius is wider
  than in-progress spans: the `end_time` arm needs a span with no `end_time` yet, but the **`ttft` arm hits any span
  written without a `ttft`** — the common case. Worse, the original's **`duration`** (a stored `MATERIALIZED` column)
  guards only `end_time IS NOT NULL` and does not know the epoch sentinel, so a span with no `end_time` gets a large
  **negative** duration (≈ `-1.79e12` ms) instead of `NULL`. The successor's expression *does* guard the sentinel, so the
  copy recomputes it as `NaN` and the **forward path is self-healing**; `verify.sh` is unaffected (it excludes
  materialized columns by design). But a **stage B/C rollback promotes the frozen original**, making those sentinels and
  negative durations live again while the healed successor copy is parked and then discarded by `finalize.sh` — so the
  rollback path must repair them.

On rollback, after swapping the Nullable original back, revert the flag to `false` **and** run that repair.

**Span-delete partition pruning does not exist yet, and that is a difference in kind from traces, not a smaller version
of it.** By the time the traces cutover ran, OPIK-6901 had already made a trace `DELETE` bind itself to the weekly
partitions its own ids resolve to, so the runbook could say the pruning "needs no flip at all" and move on. On spans
there is nothing to flip because there is nothing to prune with: **OPIK-8364 is open**, and `SpanDAO.DELETE_BY_IDS`
emits `WHERE id IN :ids AND workspace_id = :workspace_id AND project_id = :project_id` with no partition predicate at
all.

Three consequences, all of which the window has to absorb rather than fix:

- **The span delete path is unpruned on BOTH sides of the swap**, so the swap neither improves nor degrades it. That is
  the one piece of good news here: there is no flag, no ordering constraint and nothing to revert on rollback, for the
  opposite reason to traces' — not because the predicate is universally correct, but because it is universally absent.
- **A delete during the window is planned against every part of the successor**, which after the backfill means every
  one of many thousands of weekly partitions rather than the source's single unpartitioned part set. The rows it
  touches are the same; the planning is not. Watch delete latency through the window and treat a regression as a throttle signal on the
  backfill, not as a cutover fault.
- **The retention sweep is worse still, and that is why pausing it is not a formality here.**
  `SpanDAO.DELETE_FOR_RETENTION` filters on `trace_id` only, and its own Javadoc records why no partition predicate is
  applied: a span's `id_at` derives from the span's own UUIDv7, which can land in a later week than its `trace_id`, so
  a week bound derived from a trace-id range would wrongly exclude valid candidates. Retention is disabled in every
  deployment and the Go/No-Go asserts it — but on traces a stray sweep would have been a pruned mutation, and here it
  would be a full-table one over the whole source, on top of leaking across the swap.

`WeeklyPartitions`, the derivation OPIK-6901 built for traces, is reusable when OPIK-8364 lands: `spans_local_v2`'s
`id_at` is `DateTime64(0,'UTC')` and the original's is a 32-bit `DateTime` (migration 000105), exactly the pair that
derivation handles by naming the week under each type. Nothing in this cutover blocks it, and nothing in it depends on
it. Coverage for the traces equivalents sits in `TracesPartitionPruningMutationTest` and
`TracesLegacyTablePruningMutationTest`; the spans counterparts belong to OPIK-8364, not here.

## Batching and throttling

On a table this size a single week is enormous, so the backfill does **not** run one INSERT per week. Four independent
controls keep each statement safe:

- **Per-window row bound (`--max-rows-per-insert`, default 1,500,000).** `backfill.sh` counts each week and, if it
  exceeds the bound, halves it in `created_at` time — adaptively, so busy periods split more and quiet ones stay whole —
  until every leaf window fits, then inserts each. This bounds each statement's **duration**, its **blast radius** on
  failure (only that window re-runs), the **part count** it adds to the destination, and gives per-window resume. It is
  *not* a memory bound. Smaller values are safer per statement but create more parts (more merge pressure); larger
  values create fewer parts but a bigger blast radius. Note the ClickHouse "batch 1k–100k rows" guidance targets
  client-side row-by-row inserts; a server-side `INSERT … SELECT` streams and is efficient at far larger sizes, so the
  default is millions, not thousands.
- **Per-block row bound (`--max-insert-block-size`, default 1,048,576 → `SETTINGS max_insert_block_size`).** An
  `INSERT … SELECT` streams; ClickHouse forms part-writing blocks capped at the smaller of this row count and
  `min_insert_block_size_bytes`. **On spans this cap is rarely the one that fires**: rows are large uncompressed, so
  the byte bound below binds first, which is why it is left at the ClickHouse default.
- **Per-block byte bound (`--min-insert-block-size-bytes`, default 268,435,456 → `SETTINGS
  min_insert_block_size_bytes`).** **This is the bound that binds**, and carrying it explicitly is new for spans even
  though the value matches what traces ran at: it is the one dial over peak insert memory, and it trades against part
  count. Lower it on a memory-constrained data node and accept more, smaller parts. Measured pairs:
  ["Partition spread"](#partition-spread-and-the-one-setting-that-matters).
- **Per-block partition bound**, a correctness gate — see the entry further down this section.

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

  **Why the insert side MIGHT be the constraint, and why the traces answer does not transfer.** On traces the
  destination materialised `output_keys` by parsing the `output` JSON per row, and profiling put the insert
  side at the bottleneck largely because of it. **There is no `output_keys` on spans** — migration 000115
  records that it is a traces-only column (added by 000044) with no spans counterpart — so that cost is simply
  absent here. What spans materialises is `truncated_input`/`truncated_output` (substring copies of documents
  that can be very large), the three `*_length` counters, `duration` and `id_at`. Upstream says materialized
  values are calculated *"when rows are inserted"* but not by which stage, so blaming the insert side was an
  **inference from profiling** on traces and is not even that here. Confirm it on your own data: effective
  cores near 1 while the machine is idle and `OSIOWaitMicroseconds` is
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

- **Per-block partition bound (`--max-partitions-per-insert-block`, default 20000 → `SETTINGS
  max_partitions_per_insert_block`).** Not a throughput knob — a **correctness gate**. The destination is
  weekly-partitioned, so a block spans as many partitions as the ids in it imply; ClickHouse's default of 100 aborts
  the INSERT (`throw_on_max_partitions_per_insert_block = 1`) rather than degrading, and far-future UUIDv7 ids reach
  that on real data. Neither of the bounds above prevents it. **Size it from `estimate.sh` audit 1's
  `total_partitions`**, a hard upper bound: a block cannot span more partitions than the table has. It is a limit
  check rather than an allocation, so a generous value is free. `delta_replay.sh` and `reconcile.sh` carry the same
  setting, and need it at least as high: neither is bounded to one `created_at` window, so a single statement can
  touch far-future, epoch and ordinary partitions together. Full argument:
  ["Partition spread"](#partition-spread-and-the-one-setting-that-matters).

**Throttle** with `--pause-seconds` (recommended 60–120s at peak, against traces' 30–60): it sleeps after each inserted
window so background merges consolidate the new parts before the next window piles on more. The higher recommendation
is not caution — the destination accumulates an order of magnitude more weekly partitions than the traces one did, so
a window's merge backlog is spread over far more part sets. Size it from the observed `system.merges` backlog on
prod-test.

**Estimate and audit first.** [`scripts/estimate.sh`](scripts/estimate.sh) does three jobs (its header lists them): the
**headroom verdict** against the projected destination size, the **two pre-write audits** the Go/No-Go gates on, and
the backfill ETA. It reads the live row/byte counts of `spans`, estimates copy throughput with an **on-the-fly read
probe** (`SELECT … FORMAT Null` — it creates no table), derates it by `--write-cost-factor`, and reports the projected
window count, copy time, throttle idle, and total. Run it with the same `--max-rows-per-insert` and
`--pause-seconds` you plan to use:
```bash
CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/estimate.sh --database opik \
    --max-rows-per-insert 1500000 --pause-seconds 90
```
For an exact figure, time one real window with `backfill.sh` and feed its rows/sec back via `--rows-per-sec`.
It is a planning ballpark, and the parts it does not model are on the write side — the probe measures reads and
derates them by a fixed `--write-cost-factor`, and it ignores `--max-insert-threads` entirely. Real throughput varies
with concurrent load, merges and cold-tier reads.

> **On a table this size the throttle is usually the bigger half of the ETA.** `--pause-seconds` is applied once per
> window, and there are thousands of windows: even 60s each is measured in days. Read the two lines of the report
> separately, and size the throttle against the observed `system.merges` backlog rather than against the ETA — it is
> the single largest lever in the configuration.

The **delta-insert** (step 2) covers only writes during the backfill window, not the whole table, so it is normally one
statement (at its own tightened block bounds — see `delta_replay.sh`'s option docs for why it is not split);
`000002` documents how to split it into two batched passes
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

- **Source read efficiency.** The source `spans` has a **minmax skip index on `created_at`** (migration 000088), so each
  week prunes granules cheaply. It has **no `id` skip index**, and `id` is the *trailing* primary-key column
  `(workspace_id, project_id, trace_id, parent_span_id, id)` — a bare `id`-range predicate cannot prune the primary
  index (leading key columns are free), so `id`-range slicing would **full-scan the table once per week**. At this size
  that is not a tuning consideration; it is the difference between a copy that finishes and one that does not.
- **Bounded, complete iteration — where the bad-`id` data matters.** A known litellm bug
  ([BerriAI/litellm#31294](https://github.com/BerriAI/litellm/pull/31294)) minted some existing spans with UUIDv7 ids
  whose embedded timestamp is in the far future (year ~2201), so `id_at` ≠ `created_at` for those rows. 24h UUIDv7
  validation stops *new* offenders but does not fix rows already in the table. This makes `id` **unreliable and
  unbounded** as a slice key: an `id`/`id_at`-range loop would have to span from today all the way to ~2201 (thousands of
  empty weeks) to cover them. `created_at` is server-stamped and bounded to the real ingestion window, so `backfill.sh`
  iterates `toMonday(min/max(created_at))` — a finite, gap-free range — and the bad-`id` rows are still copied (in their
  `created_at` week) and still land in their (far-future) `id_at` partition. The bug is an argument *for* `created_at`.
- **Stable membership (data safety).** `created_at` is **immutable across upserts** on `spans` (the merge templates keep
  the original), so a row never migrates between weekly slices mid-backfill — none is copied twice or skipped.
  `last_updated_at` would *not* be safe here (it moves on every upsert, and is client-settable).

**On adding an `id` index to the source, which is a closer call here than it was on traces.** The delta uses the
`created_at`/`last_updated_at` skip indexes, and the replay's outer `DELETE` matches `(workspace_id, project_id, id)`.
But the replay's **resurrection-guard subquery** reads the source `spans` by bare `id`, and `spans` has **no** id skip
index — 000088 indexes only `created_at`/`last_updated_at`; the `id` minmax/bloom pair exists on `spans_local_v2`
(000115) and, for traces, on `traces` (000113), but nobody added the equivalent here. The
`id IN (deleted-ids since anchor)` set is tiny (retention off → user-scale cascade deletes), so it is a bounded
id-filtered read rather than a value-indexed prune — **but it scans every `id` in the table, and that read
sits inside the final-delta→`EXCHANGE` gap.** `delta_replay.sh` prints its wall time; treat it as a first-class
component of the tail rather than a rounding error, and size the tail with it measured rather than assumed.

**Adding the 000113 equivalent to `spans` is deliberately NOT part of this cutover.** Materializing a bloom filter over
a table this size is a heavy mutation that would have to run inside the very window this procedure asks to keep
short, and it
would compete with the backfill for exactly the I/O the backfill needs. If the measured replay time turns out to
dominate the tail, that index is a **separate, pre-window change with its own soak** — not something to reach for once
the anchor has been minted.

An index still would not rescue `id`-slicing: the ~2201 span is a *data* problem, not an index one. Destination write
locality is naturally good with `created_at` slicing (`id_at ≈ created_at` once validation holds); slicing by
*workspace* would instead scatter each insert across every weekly partition that workspace spans → a small-part
explosion, which at this partition count would be considerably worse than the traces version of the same mistake.

**Far-future partitions from far-future-timestamp ids.** Some `id`s carry an embedded UUIDv7 timestamp in the far future
(litellm [BerriAI/litellm#31294](https://github.com/BerriAI/litellm/issues/31294) mints ~2201). The rows are legitimate
customer data — a valid UUIDv7 that merely carries a future timestamp — so they are copied and kept like any other.
`spans_local_v2` partitions by the honest `Date32` weekly Monday of `id_at`
(`toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))`) — the expression migration 000115 adopted at
creation, having learned it from OPIK-7456's recreate of `traces_local_v2`, which is why spans needed no equivalent of
that recreate. Its `id_at` is a `DateTime64(0)` (honest to 2299), so each such row lands in its **own honest ~2201
(`22010601`-shaped) weekly partition**, isolated from real recent weeks — a per-week `DROP PARTITION` / retention /
tiering operation never touches them by accident, and vice versa. The **same expression saturates rather than wrapping
at the other end**, so a non-v7 id's 1970-01-01 lands in the epoch week instead of folding into ~2149, which a
`toMonday` would have done. Once written, the extra partitions are benign at rest: they never tier to cold and are
skipped by time-bounded reads.

> **They are NOT few, and they break the backfill unless `max_partitions_per_insert_block` is raised.** Do not trust the
> reading that they are "bounded (few distinct far-future timestamps → few extra weeks) and harmless": the first half is
> wrong on real data, and the second is only true *after* the copy succeeds. Measured on a production-shape
> environment (2026-08-17):
>
> | Measure | Value |
> |---|---|
> | Far-future rows | a low single-digit **percentage** of the table, not a handful |
> | Distinct far-future weekly partitions | **over a thousand**, spanning roughly 2194 → 2299 |
> | Result of running `backfill.sh` unmodified | **`Code: 252 … TOO_MANY_PARTS`** on week `2025-06-16` |
>
> This is reproduced, not projected: the driver was run against the real cluster and aborted with
> `Too many partitions for single INSERT block (more than 100)`.
>
> **What drives it is the tail, not the volume.** In the failing window:
>
> | Measure | Value |
> |---|---|
> | Far-future partitions in the window | a few hundred |
> | …holding ≤ 5 rows each | **nearly all of them**, a negligible share of the rows between them |
> | Head partitions | a handful, holding nearly every far-future row in the window |
> | Primary-key footprint of that rare tail | **a handful of projects** |
> | Worst single block: total destination partitions | **several hundred** (mostly far-future, the rest ordinary weeks it touched) |
>
> So the mechanism is: the byte cap `min_insert_block_size_bytes` (256 MiB) binds long before
> `max_insert_block_size`, because span rows are large, so a block holds far fewer rows than the row cap allows; and
> because the rare tail occupies a narrow primary-key range, one such block picks up most of those tiny partitions at
> once. ClickHouse caps partitions per block at **100** by default and, with
> `throw_on_max_partitions_per_insert_block = 1`, **aborts the INSERT** instead of degrading.
>
> **This survives parallelism, which is the counter-intuitive part.** The statement has no `ORDER BY` and the read is
> parallel (`max_insert_threads = 0`, `max_threads = auto`), so it is tempting to assume the interleaved streams
> scatter the tail across many blocks and keep every block under the limit. They do not — the abort above happened
> under exactly that configuration. Do not reason your way past this one; measure it.
>
> **The abort is not all-or-nothing.** In the run above a substantial share of the window had already committed, as
> parts, before the offending block threw. The destination is a `ReplacingMergeTree` keyed on
> `(workspace_id, project_id, id)`, so re-running the window converges rather than duplicating — but a failed window
> leaves partial data behind, and
> prerequisite #2 ("`spans_local_v2` is empty") no longer holds until it is cleared with `rollback.sh --stage A`.
>
> **No batching flag avoids this.** `backfill.sh` splits a week only by `created_at`, to respect
> `--max-rows-per-insert`; a week already under that bound is one unsplit INSERT however many partitions it spans (two
> such weeks failed in the traces measurement above). Lowering the row cap does not help either, since the byte cap
> already binds. So the fix has to be in the SETTING, exactly as it was on traces —
> ["Partition spread"](#partition-spread-and-the-one-setting-that-matters) works through why nothing more elaborate
> than raising it is warranted.
>
> **How to size it, and why the far-future count alone is the wrong input.** A block cannot span more
> partitions than the table has, so **the destination's total distinct partition count is a hard upper bound** on
> partitions per block. Size the setting above that total and it can never be exceeded, whatever the read order or
> thread count turns out to be. `estimate.sh` audit 1 reports it directly (`total_partitions`), alongside the
> far-future and far-past breakdowns — measure it rather than deriving it by hand. The default of 20000 clears the
> schema's own ceiling (the honest `UUIDv7ToDateTime` range is the epoch to 2299, ~17,000 weeks), so it cannot be too
> low for any estate; it is a limit check rather than an allocation, so confirming it is the whole exercise. The traces
> measurement is instructive on why the far-future count is not enough: its
> worst observed block spanned 333 destination partitions of which only 269 were far-future, so a block's spread mixes
> both and sizing from `far_future_weeks` alone would have undercounted it.
>
> **`delta_replay.sh` and `reconcile.sh` need it at least as high as the backfill does.** The delta's
> `last_updated_at` arm exists precisely to re-copy updates to old rows, which is where the far-future ids live; and a
> gap-window row's id can be any age. Neither is bounded to one `created_at` window, so a single statement can touch
> far-future, epoch and ordinary partitions together. Both also tighten `--min-insert-block-size-bytes`, because they
> run inside or near the cutover window where peak memory matters more than part count. Where the migration user has a
> settings profile, set the partition value there too, so it does not depend on the invocation.
>
> **There is a second population, and it goes the other way.** `UUIDv7ToDateTime` returns `1970-01-01` for a non-v7 id
> — a v4 or nil UUID, no throw — and migration 000115's header records that **non-v7 ids are commoner than the litellm
> ones**. Those collapse into the epoch week, which is why any audit or predicate written as `id_at > now()` misses
> them, and why `estimate.sh` audit 1 counts `far_past_partitions` beside `far_future_partitions`.
>
> The cost of the spread is a larger part count per insert — one part per partition touched — which background merges
> then compact. Raising the SETTING costs nothing by itself: it permits blocks to span the partitions their rows
> already imply.

**Quantify it with `estimate.sh`, not by hand.** The traces runbook left this as a query for the operator to paste;
on spans the same number decides a setting the first INSERT uses, so it belongs in a driver that records it.
`estimate.sh` (without `--skip-audits`) reports, in one read-only pass:

| audit | what it answers | what it sizes |
|---|---|---|
| 1 | total / far-future / far-past id-derived weekly partitions, and their row counts | `--max-partitions-per-insert-block` |
| 2 | `parent_span_id` values outside {0, 36} bytes | the normalization the Go/No-Go records |

Every one derives its timestamp from `id` via `UUIDv7ToDateTime` rather than reading the stored `spans.id_at`, which
migration 000105 typed as a 32-bit `DateTime` that **wraps** mod-2^32 past 2106 — so a wrapped `toMonday(id_at)` would
collapse several weeks into one and undercount the partitions the copy will actually create. The
partition expression the audit counts with is the destination's own, verbatim from 000115, so `total_partitions` **is**
the number of weekly partitions `spans_local_v2` will hold.

Remediating the source `id`s at their origin is the only thing that removes the extra partitions; short of that they
partition honestly on their own and the settings are what let the copy through. Prevention for new ones is already live
(OPIK-7402 Done, OPIK-7403 live since 2026-09-11), which is what prerequisite #1 asserts.

**No explicit `ORDER BY` on the `INSERT ... SELECT`.** Not needed for correctness or reproducibility: the final table
state is a `ReplacingMergeTree` reduction keyed on `(workspace_id, project_id, trace_id, id)` with `last_updated_at` as
the version — **independent of insert order** — so any run converges to the same live rows; ClickHouse already sorts
each insert block by the destination `ORDER BY`, and the source's key is that one plus a trailing `parent_span_id`, so
the rows arrive in a compatible order anyway; and reconciliation uses order-independent `uniqExact` of the
**destination's** dedup key on both sides (see
["The dedup keys differ"](#the-dedup-keys-differ-and-it-changes-every-comparison-in-this-runbook)). An explicit
`ORDER BY` would only add sort cost/memory on a large backfill for no gain — and on a copy this size that cost would
not be small.

## Delta and replay correctness

**Delta anchor — `created_at OR last_updated_at ≥ backfill_start`.** The delta must re-copy everything written during the
(possibly multi-day) backfill:

- `last_updated_at` is **client-supplied** on the batch-ingest path (`SpanDAO.BATCH_INSERT` binds the request's value,
  server time only as a fallback), so `last_updated_at` alone can miss a row whose client stamped it in the past. But
  every write path sets **either** a fresh server `created_at` (the batch-ingest path leaves `created_at` to its
  `now64()` default) **or** a fresh server `last_updated_at` (the create/update merge paths preserve `created_at` but let
  `last_updated_at` default to `now64()`). The **union** therefore catches every physical write, whatever the client sends.
- The anchor is captured **before** the backfill, not at its end — a cutoff taken at the end would miss writes that
  landed during the backfill itself. The same `backfill_start` bounds the replay window.

**Replay matches on the full bridge key `(workspace_id, project_id, id)` — which is NOT a primary-key prefix here, and
that is the one structural difference from the traces replay.** On traces that triple *was* the whole primary key.
`spans_local_v2` orders by `(workspace_id, project_id, trace_id, id)` and the bridge records no `trace_id` — a
`DeletionEvent` carries `(source_table, workspace_id, project_id, deleted_id)` and nothing else — so the predicate
prunes on the `(workspace_id, project_id)` prefix and then relies on the `id` skip indexes migration 000115 added for
exactly this shape: `idx_spans_id_minmax` for ranges and `idx_spans_id_bf`, the bloom filter, for the equality/`IN` set
a replay actually issues. It is the same predicate `SpanDAO.DELETE_BY_IDS` issues against the live table, so the cutover
is not asking for a read shape the product does not already make.

**Where the project_id comes from, since spans have no standalone delete.** The bridge row is written by the trace-delete
cascade (`SpanService.deleteByTraceIds`), which takes its `projectId` from the `TracesDeleted` event — and since
**OPIK-7483** that event is only ever emitted per resolved project, never project-less. So **no deletion event is ever
bridged with an empty `project_id`** for `source_table='spans'` (a pre-cutover check asserts the bridge holds none —
Prerequisites #13), and the replay carries a single branch. It is exact, and correct even though span ids are not
guaranteed globally unique: a reused id deleted in one project leaves its copies in other projects untouched. Without
this replay, those during-window deletions would **silently leak** across the swap.

**Resurrection guard.** A span can be deleted and then re-created/updated under the **same id** during the window
(ids are client-supplied; the delete is a mask, and a newer insert wins under `FINAL`). Such an id is bridged as deleted
but is **live again** on the source, and the backfill/delta already copied its live version. So the replay also
requires the id is **not currently live on the source** (`AND (…) NOT IN (SELECT … FROM spans WHERE id IN <deleted ids
since anchor>)`, mask-honored) before deleting it — otherwise the replay would drop a row that is live on the source,
silent data loss. This also makes the replay idempotent (it never masks a live-on-source id), so re-running to
convergence is safe.

> **The guard's subquery is the expensive statement in the tail, and on spans that is a measurement rather than a
> footnote.** It reads the source by bare `id`, and `spans` carries **no** `id` skip index — 000088 indexes only
> `created_at`/`last_updated_at`; the `id` minmax/bloom pair exists on `spans_local_v2` (000115) and, for traces, on
> `traces` (000113), but not here. The `id IN (deleted ids since anchor)` set is tiny (retention off → user-scale
> cascade deletes), so this is a bounded id-filtered read — but over the whole `id` column of the source
> table. `delta_replay.sh` passes `--time` so the figure is recorded; size the final-delta→`EXCHANGE` gap with it
> measured. See ["On adding an `id` index"](#why-slice-by-created_at-and-not-id-or-workspace) for why adding that index
> is a separate, pre-window change rather than something to reach for inside this one.

The replay runs with `allow_nondeterministic_mutations = 1` because it reads subqueries from `deletion_events_local` and
`spans`; those tables are replicated and identical on every node and the window is fixed, so the subqueries resolve to
the same set on every replica. It also sets `lightweight_deletes_sync = 2` so the statement returns only after the delete
mutation has applied on **every** replica — otherwise the async mutation could still be pending on a replica when the
verify or the EXCHANGE runs, giving a false mismatch or an incomplete cutover.

## How `backfill.sh` and `000001_backfill_spans_local_v2.sql` relate

They are **complementary, not alternatives**, and there is **no copy-paste drift**: the script *reads* the `.sql` file.

- **`000001_...sql` is the single source of the backfill INSERT (the "what"):** one marked `backfill` block, a
  complete statement with `${...}` placeholders for the database, the window bounds and the block/partition bounds. It
  is read by the driver, never run by hand.
- **`backfill.sh` is the driver (the "how"):** it derives the week range, extracts the block, substitutes the
  placeholders, runs it, reconciles, throttles, and is resumable. It embeds no copy of the INSERT, and it validates the
  extracted block before sending it — the same marker/identity/placeholder contract `exchange_and_wrap.sh` and
  `reconcile.sh` apply.
- **Keep the explicit column list in sync.** `000001`'s `INSERT` names each copied column explicitly (parallel `SELECT`,
  no `SELECT *`), so a column added to `spans` before a cutover is carried across **only if it is also added to this
  list and to the `spans_local_v2` shadow** (migration 000115, codecs refined by 000116). This is an incidental per-column
  edit that rides with the feature DDL; a missing column is caught in CI by the schema-parity guard —
  `cutoverCopiesEveryBaseColumn` pins the cutover's column list to the live `spans` base columns (OPIK-7772 extends it
  to a topology-aware CI check).
  > **That guard fails on the test's copy of the list, not on the reference files — treat it as the prompt to update
  > them.** **Four** statements spell the list out independently here, matching the traces cutover's four:
  > `000001`'s backfill, `000002`'s delta and both `000006` sweeps. They are duplicated rather than shared
  > because the drivers read whole statements, so each block has to stay readable and runnable on its own. Keeping them
  > in step is a manual step, and an `INSERT ... SELECT` is positional, so each must **list and project** the same
  > columns in the same order. The same applies to the fidelity fingerprint repeated across `000005`/`000006` — and on
  > spans that fingerprint carries four arms with no traces counterpart (`parent_span_id`'s length guard and its
  > `CAST`-to-`String`, `usage`'s sorted-pair encoding, `trace_id`, `type`), so a new column is not the only way to
  > drift it.

**Every SQL operation — happy path and every rollback stage — is run by a driver script; no SQL or `.sql` file is ever
run by hand.** Each `.sql` file is the single source its driver reads those statements from:

| Step | Reference SQL | Driver |
|------|---------------|--------|
| plan — headroom verdict, the two pre-write audits, backfill ETA | — | `estimate.sh` |
| 1 — backfill | `000001_backfill_spans_local_v2.sql` (`backfill`) | `backfill.sh` |
| 2 — delta + replay | `000002_delta_and_deletion_replay.sql` | `delta_replay.sh` |
| 3 — settle gate, EXCHANGE + wrap (+ the final pre-swap replay, from `000002`'s `deletion-replay` block) | `000003_exchange_and_wrap.sql` | `exchange_and_wrap.sh` |
| 4 — **post-swap reconciliation**, forward and reverse | `000006_post_swap_reconciliation.sql` (`forward-sweep` + `forward-deletion-replay`, or `reverse-usage-range-check` then `reverse-sweep` followed by the unchanged `000004_rollback_reverse_replay.sql` + `000004_rollback_verify_replay.sql`) + its postcondition `000006_verify_reconciliation.sql` (`verify-forward` / `verify-reverse`, plus the advisory `leak-check-forward`), and `000003`'s three `settle-*` blocks for its own gate | `reconcile.sh` |
| QA — fidelity compare, weekly or over one window (+ `--drill-down`) | `000005_verify_migration.sql` | `verify.sh` |
| rollback | `000004_rollback_stage_{a,b,c}_*.sql`, `000004_rollback_unwrap.sql`, `000004_rollback_reverse_replay.sql` + its postcondition `000004_rollback_verify_replay.sql`, `000004_rollback_sentinel_repair.sql` + its postcondition `000004_rollback_verify_sentinels.sql` | `rollback.sh` |
| finalize — retire the parked backup (drop after cutover / recycle to empty shadow after rollback) | — | `finalize.sh` |

**Where the line falls, for anyone adding SQL here.** Two rows show `—` because not every query a driver issues comes
from a file, and the split is deliberate:

- **In a versioned `.sql` file, extracted by marker:** every statement that changes data or schema, and every read
  whose result *is a verdict* the operator acts on. That second half is why `000005`'s `compare` / `confirm-keys` /
  `version-ties` blocks, `000006`'s `verify-forward` / `verify-reverse` / `leak-check-forward` blocks and
  `000003`'s three `settle-*` blocks
  live in files despite being read-only — a fidelity gate, a reconciliation gate or a swap gate deciding wrongly is the
  failure this procedure exists to prevent, so its SQL is reviewed and versioned like a statement. The `settle-*` blocks
  are read by **two** drivers: `exchange_and_wrap.sh` before the swap and `reconcile.sh` after it, each rendering its own
  table scope into the blocks' `${SETTLE_QUEUE_TABLES}` / `${SETTLE_MUTATION_TABLES}` placeholders. Same hazard, same
  SQL, different scope — which is why these are parameterised where the two deletion replays are duplicated (below).
- **Inline in the driver:** the short scalar probes that steer control flow — "what engine is `spans`?", "does this
  table exist?", "how many replicas?". They are one-liners against `system.*`, they change nothing, and putting them
  behind a marker would add indirection without adding review value.

> **There are two deletion replays, and they are separate files on purpose.** `000002`'s runs **pre-swap** against a
> **live** source (`delta_replay.sh`, and again inside `exchange_and_wrap.sh`); `000006`'s `forward-deletion-replay`
> runs **post-swap** against the **frozen** backup. They share a shape — the full-key bridge match, the length guards,
> the resurrection guard — but not their semantics, and the post-swap one carries a third arm the pre-swap one has no
> counterpart for. Parameterising one block to serve both would mean a placeholder whose only job is to switch that arm
> off, inside the statement whose silent failure leaks deletions; `000004_rollback_reverse_replay.sql` is a separate
> file for the same reason. Each carries a `KEEP IN STEP WITH` header naming the other.

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
>
> **On the three drivers that issue `ON CLUSTER` DDL it also sets `distributed_ddl_task_timeout`, and there that is the
> binding limit.** `exchange_and_wrap.sh`, `rollback.sh` and `finalize.sh` wait on the distributed-DDL queue, which is
> capped server-side (180s by default, `distributed_ddl_output_mode = 'throw'`) rather than by the client socket, so
> raising the client timeout alone would leave those statements bounded at the default. That matters most for the
> `EXCHANGE` and its post-swap `RENAME`, which are one call: a timeout between them leaves the split state
> `exchange_and_wrap.sh` diagnoses, while the DDL keeps running in the background.

### Timezones: every window bound pins `'UTC'`

The `spans` timestamp columns are `DateTime64(n, 'UTC')`, but a literal written without a timezone is parsed in the
**server** timezone — so on a non-UTC server the same statement means something different. Every window bound in the
reference SQL therefore pins `'UTC'`, and where a bound is a value a driver captured, **the capture pins it too**:
`backfill.sh` mints `backfill_start` with `now64(6, 'UTC')` and `000002` reads it back as `'UTC'`; `exchange_and_wrap.sh`
does the same for `cutover_start`.

The epoch sentinel the projection writes for an absent `end_time` is the one literal left unpinned, deliberately. It is
read back by the destination table's own `DEFAULT` and `duration` expression and by every `end_time` comparison in the
application, all of which are unpinned; a sentinel that disagrees with its readers is worse than one that is uniformly
offset. Correcting it means moving the schema and the application together, which is not this runbook's change to make.

Both halves have to agree. Pinning only the literal reinterprets a server-local wall clock as UTC and moves the anchor
by the server's offset — and a *later* anchor silently drops the rows written in the gap, which the delta and the
deletion replay both miss because they share that bound.

Because that failure is silent, the persisted anchor carries the claim rather than relying on it: `backfill.sh` writes
`--state-file` with an explicit ` UTC` marker and **refuses a file without one**, since a bare timestamp cannot be
attributed to a timezone and step 2 would read it as UTC regardless. An anchor written by an older revision is therefore
rejected, with the three ways out the driver prints: delete the file if the destination is still empty, since nothing was
copied against the lost anchor and a fresh one is owed; re-record it with the marker if it is known to have been taken on
a UTC server; or restart the copy cleanly. The same reasoning is why both drivers print their anchors labelled `UTC`: the value an
operator pastes into `--backfill-start` or `--cutover-start` says which zone it is in — and those flags **require** the
marker, so the guard cannot be bypassed by supplying the anchor by hand.

### Required privileges (provision these before the window)

The cutover should run as a **dedicated, spans-scoped least-privilege user**, not as the app/admin account, not as a
read-only account, and **not as the traces migration user**. Two of these grants are **not guessable** — they were each
found only by executing the step against a real cluster, because a local rehearsal running as admin exercises no grant
at all and the read-only drivers cannot surface a mutation-privilege gap by construction.

> **Provision a NEW user, and plan its revocation with its grant.** The traces cutover ran under a production write
> exception that **OPIK-8263** is now closing out — the ticket exists because the revocation half was left to be done
> afterwards. Do not reuse that account and do not widen it: its grants name `traces`, `traces_local_v2` and
> `traces_pre_cutover_backup`, and extending them to the spans names would re-open an exception that is being retired
> while creating a second one. What **is** reusable is the shape, and it is reusable verbatim:
>
> - the same least-privilege split (no `ALTER UPDATE` on any real data column in the forward path, no `TRUNCATE`
>   anywhere in it);
> - the same column-scoped `ALTER UPDATE(_row_exists)` for the lightweight deletes;
> - the same **"all four privileges per `RENAME`/`EXCHANGE` name"** rule (see the note below the table — it is the one
>   that bites on names that do not exist yet);
> - the same `readonly = 2` requirement for a read-only assessor, because every driver sets `log_comment`.
>
> Two spans-specific additions to the provisioning plan. **`estimate.sh` is heavier than its traces counterpart**: its
> four audits read the whole `id` column, so a read-only assessor with a `max_rows_to_read` ceiling cannot run them —
> give the assessor `readonly = 2` with no row ceiling, or run the audits as the migration user. And **the wrap's grants
> are not needed in this window**: the wrap is deferred, so withhold them and withhold stage C's with them — the
> narrower the account, the less a slip can do. But **do NOT withhold stage B's**, because the post-`EXCHANGE` state is
> where this window rests and stage B is the rollback that reverses it. That is a change from
> the traces guidance, which treated stage B's grants as optional.
>
> Revocation is a checklist item in the Go/No-Go, not an afterthought: name the date, name who does it, and name what
> it takes away.

| Step | Statement | Privileges ClickHouse actually checks |
|------|-----------|--------------------------------------|
| all drivers | any query | able to set `log_comment` → **not** a `readonly = 1` profile (`readonly = 2` for a read-only assessor) |
| `estimate.sh`, guards, settle gate | `SELECT` on `system.*`, `clusterAllReplicas(...)` | `SELECT ON system.*`, plus `REMOTE` and `CLUSTER` |
| backfill / delta | `INSERT INTO <shadow> SELECT … FROM <source>` | `SELECT` on source, `INSERT` on shadow |
| deletion replay | lightweight `DELETE FROM <shadow>` | **`ALTER UPDATE(_row_exists)`** on the shadow — *not* `ALTER DELETE`. A lightweight delete is implemented as `ALTER UPDATE _row_exists = 0`. Grant it **column-scoped** so the user can flip the delete mask without being able to rewrite any real column. |
| `EXCHANGE` | `EXCHANGE TABLES <source> AND <shadow> ON CLUSTER` | **`INSERT` + `CREATE TABLE` + `DROP TABLE` on BOTH names** — `INSERT` is required even though the swap is metadata-only and moves no rows. |
| post-swap `RENAME` | `RENAME TABLE <shadow> TO <backup>` | `CREATE TABLE` + `DROP TABLE` (grant `INSERT` on the backup name too, so the rename cannot trip the same check) |
| **post-swap reconciliation** (`reconcile.sh`, forward — **required on every cutover**) | `INSERT INTO spans SELECT … FROM spans_pre_cutover_backup`, then the lightweight `DELETE FROM spans` | **`INSERT` and `ALTER UPDATE(_row_exists)` on `spans`** (or `spans_local` on a wrapped estate), plus **`SELECT` on `spans_pre_cutover_backup`**. This is the grant set that widens the forward path's blast radius — see the boundary note below, which it deliberately rewrites. `INSERT` on `spans` is already required by the `EXCHANGE`; the mutation grant is new. Column-scoped `ALTER UPDATE(_row_exists)`, as for the shadow, so the user can flip the delete mask without rewriting any real column. |
| **post-swap reconciliation** (`reconcile.sh`, reverse — only after a rollback) | `INSERT INTO spans SELECT … FROM spans_post_rollback_backup`, then the reverse replay | `INSERT` and `ALTER UPDATE(_row_exists)` on `spans` (the latter already in the rollback set), plus `SELECT` on `spans_post_rollback_backup`. |
| **wrap** (sharding) | `CREATE TABLE spans_dist … ENGINE = Distributed(…)`, then `RENAME spans → spans_local, spans_dist → spans` | `CREATE TABLE` + `DROP TABLE` on **`spans_dist`** and **`spans_local`** — two names that **do not exist yet**, so a grant set scoped to the cutover's three names will NOT cover the wrap. Plus `SELECT` on `spans_local` (post-wrap reads route through the wrapper to it) and `REMOTE` for the `Distributed` engine. |
| rollback stage A/B (if in scope) | stage A `TRUNCATE`; stage B 2-way `RENAME` + reverse replay | `TRUNCATE` on the shadow, and `ALTER UPDATE(_row_exists)` on the **source** (the reverse replay masks rows on the restored original). **Stage B also renames**, so it needs **`INSERT` + `CREATE TABLE`** on **`spans_post_rollback_backup`** — a destination that **does not exist yet**, so a set without `INSERT` fails `Code: 497` at the rename (see the four-privileges note below) — and **`SELECT` + `DROP TABLE`** on **`spans_pre_cutover_backup`**, its source. Note stage B is the *likelier* rollback, not the exotic one: the wrap is deferred by default, so the post-`EXCHANGE` resting state is the one stage B reverses, and reaching it needs no extra step. Withhold unless a rollback is actually planned. |
| rollback stage C (if the wrap is applied) | 3-way `RENAME` + `DROP` of the ex-wrapper | **`INSERT` + `CREATE TABLE`** on **`spans_dist_old`** and **`spans_post_rollback_backup`** — both `RENAME` destinations, so a set without `INSERT` fails `Code: 497` at the rename (see the four-privileges note below) — plus `DROP TABLE` on `spans_dist_old`, which is dropped after the rotation. `DROP TABLE` on `spans_local`, plus `ALTER UPDATE(_row_exists)` on the restored `spans`. **Decide this before applying the wrap:** without these grants there is no way back to the pre-cutover table until another grant change lands. (The *wrap itself* stays reversible via the un-wrap row below, which needs no extra grants — but that returns to the successor, not to the original.) |
| **un-wrap** (`--unwrap-only`, if the wrap is applied) | 2-way `RENAME` + `DROP` of the ex-wrapper | `CREATE TABLE`/`DROP TABLE` on **`spans`**, **`spans_local`** and **`spans_dist_old`**, plus **`INSERT` + `CREATE TABLE`** on **`spans_dist_old`** as the `RENAME` destination — a **subset of what stage C's statements require** (same source and destination names, minus `spans_pre_cutover_backup` and `spans_post_rollback_backup`), so a grant set that genuinely covers stage C covers this with nothing added. No `ALTER UPDATE` and no `TRUNCATE`: it promotes no backup and replays nothing. Grant it even when stage C is out of scope — it is the only wrap recovery once `finalize.sh` has dropped the parked original. |
| sentinel repair (`--sentinel-repair-only`, after a stage B/C promote **or** a cutover abandoned pre-`EXCHANGE`) | one `ALTER TABLE spans` carrying `UPDATE end_time = NULL …` and `UPDATE ttft = NULL …` | `ALTER UPDATE(end_time)` and `ALTER UPDATE(ttft)` on **`spans`** — **column privileges the rows above do NOT include.** The reverse replay needs only `ALTER UPDATE(_row_exists)`, so a user scoped to the rollback set gets `ACCESS_DENIED` here. Both commands travel in one mutation, so a missing grant on either applies neither. Either grant these two columns with the rollback grants (and revoke them after), or plan to run the repair as a more privileged user. |
| `finalize.sh` (if in scope) | `TRUNCATE` / `DROP TABLE` | `TRUNCATE`, `DROP TABLE`, and `max_table_size_to_drop` override |

> **`RENAME` and `EXCHANGE` check four privileges per name, not two.** Verified against a real server (26.3): a
> `RENAME TABLE a TO b` demands `SELECT` + `DROP TABLE` on the **source** and `INSERT` + `CREATE TABLE` on the
> **destination**; `EXCHANGE TABLES a AND b` demands all four on **both**. The rows above name the `CREATE`/`DROP` half
> because that is the half that is easy to miss on names that do not exist yet — but a user provisioned from those rows
> alone will fail with `Missing permissions: SELECT ON …` at the swap, since `SELECT` is granted above only on the
> *source* and never on the shadow. When provisioning, take each `RENAME`/`EXCHANGE` row as "all four on every name it
> touches", and confirm with a dry run rather than at the cutover moment.

**The boundary worth preserving — and how the post-swap reconciliation widened it (OPIK-8238).** The forward path ends
with `reconcile.sh`, which mutates the live name: it re-applies bridged deletes onto `spans` with a lightweight
`DELETE`. So the forward-only grant set now includes **`ALTER UPDATE(_row_exists)` on `spans`**, where it previously
needed no mutation grant on the live table at all and the worst it could do to live data was add rows. That widening is
deliberate — the delete side of the cutover cannot be completed without it — and is called out here so it is reviewed as
such rather than inherited silently.

What still holds, and is worth keeping:

- **No `ALTER UPDATE` on any real data column** anywhere in the forward path. The grant is column-scoped to the hidden
  `_row_exists`, so the user can flip the delete mask but cannot rewrite a value. (`--sentinel-repair-only` is the only
  step that needs real-column grants, and it is a rollback-tail step, granted and revoked around itself.)
- **No `TRUNCATE` anywhere** in the forward path, so it cannot empty a table.
- So the worst it can do to live data is **add rows, or mask them** — and the masking is bounded by the replay's two
  guards, which delete only keys the frozen backup shows as deleted and only rows written before the swap.

Keep the rest: grant rollback/finalize privileges only when those steps are in scope, as a separate reviewed change.

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

**In the forward sequence, the only manual actions are not SQL:** (1) flipping
`databaseAnalyticsDataModel.spanColumnsNonNullable` to `true` before the EXCHANGE (and back on rollback) — see "The
final cutover window"; (2) flipping `databaseAnalyticsDataModel.spansDistributedWrapEnabled` around the wrap and the
un-wrap — see "Un-wrap"; and (3) the go/no-go judgement between steps. All three are *backend config* / judgement
changes (env + rolling restart, or a config push) that these DB-facing scripts cannot and should not make: the mechanism
is deployment-specific, so the drivers name the flag and the ordering and leave the rollout to the operator. They are
deliberately operator-owned, and none of them involves typing SQL. **No ingestion-path config change appears on any
path** — forward, rollback, wrap or un-wrap.

**Recovery is where hand-run SQL does appear**, so the claim above is about the forward sequence and not about the whole
runbook. Two kinds, both deliberate and documented where they occur:

- **Procedural**, in one place only: the retry's `RENAME` of the parked backup back to `spans_local_v2` (see "Retrying
  the cutover after a stage B/C rollback"). It is unautomated on purpose — it reuses data whose trustworthiness may be
  the reason the rollback happened — so the operator runs it having read the guards.
- **Remediation the drivers print rather than perform**, when a guard refuses and the fix depends on facts the script
  cannot check: clearing a leftover `spans_dist_old`, and completing an interrupted forward `RENAME`.

All of it runs against the same database and connection the drivers use, and each statement is printed in full at the
point it becomes necessary — the drivers never withhold one, and never run it for you.

## Naming and the parked backup

Table names are the primary signal an operator acts on, so they encode which table is which — the safeguard against an
accidental `DROP` of the wrong (irreplaceable) table:

- **`spans`** — always the live table the app reads/writes (the original before the cutover; the successor after it;
  the `Distributed` wrapper after the wrap).
- **`spans_local_v2`** — the **working successor shadow**: created empty by migration 000115 (codecs refined by 000116) and filled by
  backfill/delta before the EXCHANGE. It is disposable — stage A discards it, and it is never a `finalize.sh` target.
- **`spans_local`** — the successor's live shard after the wrap (standard `Distributed`-over-`_local` idiom).
- **`spans_pre_cutover_backup`** — **the displaced old original**, produced by renaming it immediately after the
  EXCHANGE. This rename is the whole point: leaving the old data under `spans_local_v2` would label the *oldest*,
  *sole-backup* copy with a `_v2` suffix that reads as "the newer table" — and, post-wrap, sitting next to the live
  `spans_local` it would invite dropping the wrong one. `spans_pre_cutover_backup` says exactly what it is and shares
  no stem with the live shard, so neither confusion is possible.
- **`spans_post_rollback_backup`** — **the abandoned successor**, parked here by a stage B/C rollback (the original is
  live again as `spans`). A distinct `_backup` name — not `spans_local_v2` — so a rolled-back estate is
  self-describing: it reads as a **retained backup** (kept for the soak, recover post-cutover writes from it), and stage
  A's shadow-discard cannot mistake it for the disposable `spans_local_v2` and truncate it.

The two `*_backup` names are the only retained backups and never co-exist. The one irreversible finalize step
(`finalize.sh`) retires whichever is present — **dropping** `spans_pre_cutover_backup` after a successful cutover, or
**recycling** `spans_post_rollback_backup` back into an empty `spans_local_v2` after a rollback (it is physically the
000115 shadow object, renamed) — and never touches the live `spans`/`spans_local`.

### Can spans afford to park the backup at all?

**Yes — and the reason is worth stating plainly, because the question reads as though it should be no.** The parked
backup costs **no new bytes**. It IS the source table, renamed: the `EXCHANGE` is metadata-only and the `RENAME` after
it moves no data, so the source's bytes are already on disk and stay exactly where they are. Parking is not an
additional allocation; it is the absence of a deletion.

What the arithmetic says, writing `S` for the measured source size, `r` for `--dest-compression-ratio` and `F` for the
free space before the backfill (all four from `estimate.sh` — see ["Blocker 1"](#blocker-1--disk-headroom)):

| moment | `spans` (old) | `spans_local_v2` / successor | free |
|---|---|---|---|
| before the backfill | `S` | 0 | `F` |
| after the backfill, before the swap | `S` | ≈ `S × r` | ≈ `F − S × r` |
| after the swap, through the soak | `S` (parked) | ≈ `S × r` (live) | ≈ `F − S × r` |
| after `finalize.sh` | — | ≈ `S × r` (live) | ≈ `F + S − S × r` |

**The swap changes nothing about disk. The BACKFILL is what consumes it, and the SOAK is what holds it consumed.**
Three consequences to carry:

1. **The thin moment is the whole interval from the end of the backfill to `finalize.sh`**, not the window. `F − S × r`
   — with live ingestion still writing into the successor and merges still churning every weekly partition — is the
   figure to alert on, per volume, for the *entire* soak. It is also why `--min-free-factor` covers merge scratch: the
   copy must not merely fit, it must fit with room to merge.
2. **The `traces_pre_cutover_backup` lever (OPIK-8263) is a SOAK lever as much as a copy lever.** It raises that floor
   for the whole soak, which can be the difference between watching a graph and being able to look away from it. It is
   irreversible and separately authorised (it retires the traces rollback safety net), so decide it *before* the
   backfill starts rather than when the graph turns red.
3. **The soak's length is a disk decision here, not only a confidence decision.** The traces runbook recommends ~2 weeks
   of soak on the grounds that latent read regressions surface in that time. That reasoning is unchanged — but on spans
   the soak also holds `S` of parked data for its whole length, so the trade is explicit: a shorter soak frees the
   volume sooner and forecloses rollback sooner. **Shorten it knowingly if you must; do not skip the backup.** Skipping
   it is the one option that is not on the table: without `spans_pre_cutover_backup` there is no stage B, and on spans
   stage B is the only rollback this window can need (stage C reverses a wrap this window does not apply).

**If the volume genuinely cannot hold both copies**, the answer is to grow the volume before the backfill, not to
change the procedure. Every alternative shape
(copying in halves, dropping old partitions as you go, finalizing early) trades the rollback path for disk, and the
rollback path is what makes a cutover of this size survivable.

## When the cutover is done

The mirror of ["When the rollback is done"](#rollback) below, and it exists for the same reason: the `EXCHANGE` reports
that it swapped two names, not that the estate is correct — and the step that makes it correct comes *after* it. Treat a
cutover as complete only when all of these hold.

- [ ] **The gap is reconciled** — `reconcile.sh` ran and printed `RECONCILED`, with `missing_keys`, `stale_keys` and
      `payload_mismatch_keys` all `0`. A non-zero `newer_keys` beside them is expected, not a failure: it is the count of
      gap-window spans that were written again after the swap, which the sweep deliberately leaves alone.
      **`exchange_and_wrap.sh` ends with a CUTOVER INCOMPLETE banner naming this command**; the banner is the reason this
      box is first.
- [ ] **Fidelity over the reconciled range** — `verify.sh --window-from '<delta_start>' --window-to '<exchange_done>'`
      PASSED on the `spans_pre_cutover_backup` / `spans` pair. **Bound it at the swap, not at `now`**: the backup holds
      nothing created after the `EXCHANGE`, and the compare requires equal row counts on both sides, so a later bound
      fails on live traffic rather than on fidelity. The four counts already cover presence, version and payload, so
      this is the payload-level *picture* rather than a second gate; run it because a `PASSED` line stating the exact
      window is what an incident review will ask for.
- [ ] **Fidelity over sealed history** — the bounded weekly compare passed (see "Verifying the migration" for the bound
      and for which mismatches inside it are benign).
- [ ] **`spanColumnsNonNullable = true` confirmed live on every instance by a POSITIVE read-back** — an absent
      `end_time`/`ttft` must return `null`, not `1970-01-01`/`NaN`. Do it **after** the swap: before it the Nullable
      original answers `null` either way, so a pre-swap check proves nothing.
- [ ] **`spans_pre_cutover_backup` still parked** for the soak. It is the only copy of anything reconciliation did not
      recover, and `finalize.sh` refuses to retire it without `--confirm-gap-reconciled` — which is you asserting the
      first box, not something it can check.

Until the first box is ticked the cutover has lost writes, whether or not anything has noticed: they are in the parked
backup, not in the live table. `finalize.sh` is the point at which that stops being recoverable.

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

`rollback.sh` passes `--time`, so every statement it runs prints its elapsed seconds. Record the figure for the
**reverse replay**: it scales with the number of bridged deletions, so that is the number to compare against the window
below. The promotes and the un-wrap are single `RENAME`s and are effectively constant.

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
`DROP` targets the `Distributed` wrapper, which stores no data (it is a routing definition over `spans_local`). Before
running, `rollback.sh` **asserts the live `spans` topology matches the requested stage or mode and aborts otherwise** — so a
wrong-stage run (the only way a `TRUNCATE`/`DROP` could hit the wrong table) makes no change. Stages B and C land in the
**canonical state**: `spans` = the original data (live), `spans_post_rollback_backup` = the successor data (parked as a
retained backup), retired only later by `finalize.sh` after the soak — which recycles it into an empty `spans_local_v2`,
restoring the pre-cutover, Liquibase-consistent estate. Stage A instead discards the shadow (`spans_local_v2` emptied)
and leaves the untouched live `spans` — there is no backup to soak or finalize. No leftover
`*_new` names.

> **Stages B/C make post-cutover writes non-live — acknowledged at the promote, and then a CHOICE, not a verdict.**
> Promoting the frozen `spans_pre_cutover_backup` means spans the successor accepted **after** `cutover_start` stop
> being served by the live table (the reverse-replay carries post-cutover *deletes* forward, but not *writes*). They are
> **not destroyed**: the successor is parked as `spans_post_rollback_backup` and retained until `finalize.sh`.
>
> Stages B/C require `--accept-post-cutover-write-loss` to acknowledge that the promote makes them non-live. What that
> flag no longer means is that nothing can be done about it. After the promote, `rollback.sh` prints **how many rows are
> in that set** and the two options, and the decision is yours to make before `finalize.sh` forecloses it:
>
> - **Accept the loss.** Correct when the successor's *content* is what is suspect — merging its writes back would
>   re-import the very data the rollback existed to discard. This is the historical default, and it stays the default.
> - **Recover them** with `reconcile.sh --confirm-reimport-successor-writes` (see "Recovering the post-cutover writes"
>   below). Correct when the rollback was motivated by **latency, merge load or a wrap regression** rather than by
>   fidelity — there the discarded writes are good data, and throwing them away was never the point of the rollback.
>
> The flag's name is about the promote's immediate effect. It is not a judgement that the data is unrecoverable, which
> would only ever have been true of the fidelity-motivated case.

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
  `spans_local_v2`; the live `spans` was never touched. (Guarded: aborts unless `spans` is still the original schema.)
  **"Untouched" is about rows, not values:** the flag was rolled out before the `EXCHANGE`, so spans written during
  that window carry sentinels and a negative `duration` in the live table, and stage A does not address them. Abandoning
  the cutover therefore still needs the sentinel repair below; retrying it does not, since the retry's copy heals them.
- **Stage B — after EXCHANGE, before wrap:** `./scripts/rollback.sh --database opik --stage B --cutover-start '<ts> UTC'
  --confirm-retention-paused --accept-post-cutover-write-loss`. `EXCHANGE` `spans_pre_cutover_backup` back to live
  `spans`, park the now-displaced successor as `spans_post_rollback_backup`, then the reverse replay. (Guarded: aborts
  if `spans` is `Distributed` — use C.)
- **Stage C — after wrap:** `./scripts/rollback.sh --database opik --stage C --cutover-start '<ts> UTC'
  --confirm-retention-paused --accept-post-cutover-write-loss`. Drops the `Distributed` wrapper, then one atomic
  `RENAME` promotes the original (`spans_pre_cutover_backup`) back to `spans` and parks the successor as
  `spans_post_rollback_backup`, then the reverse replay. (Guarded: aborts unless `spans` is `Distributed`.)
  **Set `databaseAnalyticsDataModel.spansDistributedWrapEnabled` back to `false` before backends resume** — Stage C
  makes `spans` a `MergeTree` again and parks `spans_local`, so a still-`true` flag would send `SpanDAO` deletes at
  the missing `spans_local`. This is the inverse of the flip that enabled the wrap (see "HARD PREREQUISITE for the
  wrap"); it applies to every deferred `--wrap-only` topology, not the EXCHANGE-only default (where the flag was never
  set). The partition-metrics relabel reverses too: the `opik.clickhouse.partition.*` parts gauges move back from
  `table="spans_local"` to `table="spans"`, so restore any dashboards/alerts adjusted at wrap time.
- **Un-wrap — after wrap, when only the *wrap* is at fault:** `./scripts/rollback.sh --database opik --unwrap-only
  --confirm-maintenance`. Rotates the data-less wrapper out and `spans_local` back into `spans` in one atomic
  `RENAME`, then drops the ex-wrapper — landing in the post-`EXCHANGE`, pre-wrap state. (Guarded: aborts unless `spans`
  is `Distributed` and `spans_local` holds the successor schema.) See "Un-wrap" below for when to prefer it over stage C.
- **Sentinel repair — after a stage B/C promote, or after abandoning the cutover pre-`EXCHANGE`:**
  restores `NULL` on the rows the flag wrote into the still-Nullable original and recomputes their `duration`. **The
  window is mandatory** (see below). Which invocation depends on whether a promote parked the successor, because that is
  the only topological proof a cutover ran on this estate:
  ```bash
  W=(--sentinel-window-from '<flag rolled out, UTC>' --sentinel-window-to '<revert landed everywhere, UTC>')
  # after a stage B/C promote — spans_post_rollback_backup is the proof
  ./scripts/rollback.sh --database opik --sentinel-repair-only --confirm-flag-reverted "${W[@]}"
  # no parked successor: abandoned pre-EXCHANGE (incl. after stage A), or finalize.sh has recycled it
  ./scripts/rollback.sh --database opik --sentinel-repair-only --confirm-flag-reverted --confirm-flag-was-live "${W[@]}"
  ```
  The second asserts the flag was live here, because without the parked successor nothing in the topology or the data
  distinguishes an epoch `end_time` this flag minted from a value a client sent — and the repair rewrites the whole
  table. **Single shard only:** it mutates the shard it connects to while verifying across all of them, so it refuses on
  a multi-shard cluster — and on a per-shard run too, since the count is still above one. There is no driver path there:
  apply the statement from `scripts/db-app-analytics/` by hand, one shard at a time, then check the postcondition once.
  It also refuses when the shard count is **unreadable**: that count is how the driver learns whether a shard-local
  rewrite can be certified, and proceeding on an unknown topology risks a whole-table rewrite that cannot be certified.
  The primary fix is to grant `SELECT ON system.clusters` and `system.macros`. Where that is genuinely unavailable and
  the topology is known, `--confirm-single-shard` unblocks that guard, and does not create an unverified repair. The
  sentinel read runs before the mutation and again after, and it is the same query, resolving `{cluster}` from the
  server's config rather than from `system.macros`: on the usual cause, a missing grant, both run and the repair
  verifies; where the macro genuinely does not resolve, the first read fails and the driver aborts before mutating
  anything. It does **not** override a count that came back greater than 1, and it is accepted only with
  `--sentinel-repair-only`, `--reverse-replay-only` and stages B and C. Separate from the stages by necessity, not
  preference: the config revert has to land on every instance first, and these scripts do not roll out config. **That is
  the only ordering that binds** — repairing while any instance still has the flag `true` lets it mint fresh sentinels
  behind the mutation. Stage A may run before or after, because it `TRUNCATE`s the shadow rather than dropping it, so
  the evidence the guard looks for survives. See step 2 of "Rolling back the `spanColumnsNonNullable` flip".

### Recovering the post-cutover writes after a stage B/C rollback

The reverse direction of `reconcile.sh`. It re-imports into the restored original the spans the successor accepted after
`cutover_start`, which the promote made non-live:

```bash
./scripts/reconcile.sh --database opik --report-only \
    --cutover-start '<ts> UTC' --swap-done '<promote_done from rollback.sh> UTC'   # size it first
./scripts/reconcile.sh --database opik --confirm-retention-paused \
    --cutover-start '<ts> UTC' --swap-done '<promote_done> UTC' --confirm-reimport-successor-writes
```

Three things make it safe to run and worth understanding before you do:

- **`--confirm-reimport-successor-writes` is required**, because this re-imports exactly what
  `--accept-post-cutover-write-loss` acknowledged discarding. Reach for it on a latency / merge-load / wrap rollback; not
  on one where the successor's content is suspect.
- **Sentinels are denormalized back to `NULL`** (`nullIf(end_time, epoch)`, `if(isNaN(ttft), NULL, ttft)`). This is not
  cosmetic: the original's MATERIALIZED `duration` guards `end_time IS NOT NULL` and knows nothing of the epoch, so
  importing the sentinel verbatim would give every unfinished span a duration of about **-1.79e12 ms**. Restoring
  `NULL` is what makes the recomputed duration `NULL`. It is the same damage the sentinel repair fixes, so re-importing
  raw would be undoing that repair one row at a time.
- **Deletes still win.** The driver re-runs `000004_rollback_reverse_replay.sql` *after* the sweep, so a span deleted
  since `cutover_start` is re-imported and then masked, and `000004_rollback_verify_replay.sql` still reports `0`. The
  one case that does not come back is an id **deleted and then re-created** after `cutover_start`: the reverse replay is
  deliberately guard-less, so the delete is honoured and the re-creation is lost with the other discarded writes — the
  same semantics the retry section already documents.

**It changes the post-rollback fidelity compare.** Once the writes are back, the cutover window's week no longer
legitimately mismatches *by writes*, so **drop the `--to-week` bound** and compare unbounded; what remains expected is
post-cutover deletes (masked on the original, still live in the parked successor) and anything written after the promote.
Running the bounded form afterwards is not wrong, only weaker — it stops short of the week the recovery was about.

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
| Needs `spans_pre_cutover_backup` | Yes | Yes | **No** |
| Available after `finalize.sh` | No | No | **Yes** |
| Re-backfill needed to go forward again | Only if the parked copy is not reused (see "Retrying the cutover") | Same | **No — never left the cutover** |

Two properties are worth being explicit about:

- **It is the only wrap recovery left after `finalize.sh`.** Stages B and C both require the parked original, which
  finalize drops. Since the documented order is wrap → soak → finalize, *post-wrap and post-finalize is the expected
  steady state*, and `--unwrap-only` is what covers a wrap fault discovered there.
- **It makes the wrap a switch rather than a one-way door** — `exchange_and_wrap.sh --wrap-only` applies it,
  `rollback.sh --unwrap-only` removes it, repeatably. **With one asymmetry, while the parked original is gone:**
  `--wrap-only` refuses when `spans_pre_cutover_backup` is absent, so on a **finalized** estate the un-wrap still works
  and is still repeatable, but *re-applying* the wrap needs that guard lifted first — a deliberate, separately reviewed
  decision, since it means wrapping an estate with no route back to the pre-cutover table. `rollback.sh` prints this
  instead of a command when it detects the case, rather than handing over an invocation that is certain to be refused.

**Do the DDL first, then the flag** — the inverse of the forward ordering, and for the same reason. Un-wrapping first
leaves span deletes pointed at the now-absent `spans_local` (`Code 60 UNKNOWN_TABLE`) until the roll-restart lands;
reverting `spansDistributedWrapEnabled` first instead would point them at a `spans` that is still `Distributed`, which
rejects mutations (`Code 36`). Both *flag* windows are **delete-path-only** — `SpanDAO` reads the flag only when
choosing its mutation table, so reads and inserts never consult it — which is what makes a short one tolerable.

The **DDL** window is separate and not delete-only. While the `ON CLUSTER` rename propagates, a lagging replica still
resolves the wrapper's `spans_local` target, which the already-renamed replicas no longer have, so a query routed there
can fail with `UNKNOWN_TABLE` — the exact mirror of the wrap's own window, where a `Distributed` query reaches a node
where `spans_local` does not exist *yet*. It is sub-second and fails loudly, but it touches **reads too**, so no
ingestion-side setting covers it: quiesce traffic or take a maintenance window. That is what
`--confirm-maintenance` asserts.

`spanColumnsNonNullable` stays `true`: the live table is still the partitioned, sentinel-schema successor, which is
precisely what that flag asserts. Only stage B/C revert it, because only they restore the unpartitioned original.
Partition pruning needs no attention in either direction — it carries no flag at all; see
"Span-delete partition pruning needs no flip at all".

**Monitoring reverses with it.** The `opik.clickhouse.partition.*` parts gauges relabel back from `table="spans_local"`
to `table="spans"`, so restore anything adjusted at wrap time. And if the wrap-time option to point
`PARTITION_METRICS_LWD_TABLES` at `spans_local` was taken (see "Monitoring consequence of the flip"), **revert it to
`spans`** — that table no longer exists after the un-wrap, so the LWD scan fails with `Code 60` and
`opik.clickhouse.partition.lwd_rows` goes silently empty while every other gauge returns. Installs left at the default
(`spans,spans`) need nothing.

**Scope limit.** This undoes sharding only. A fidelity defect in the successor, a partition-count or merge-load
regression, or a query regression from the new layout are all *cutover* problems — `--unwrap-only` changes none of them.
Use stage B/C while the parked original still exists.

> **Flag-vs-DDL ordering is not the same in both directions. This is the easiest thing here to get
> backwards.** Each step's own section states its order; the table exists so the asymmetry is visible in
> one place:
>
> | step | order | why |
> |---|---|---|
> | forward wrap (`--with-wrap` or `--wrap-only`) | **toggle first**, then DDL | in the gap, deletes target a `spans_local` that does not exist yet → `Code 60`. DDL-first would send them at a `spans` that is already `Distributed` → `Code 36`, and exposes the cross-node skew to reads as well |
> | un-wrap (`--unwrap-only`) | **DDL first**, then toggle | the mirror image: the gap gives `Code 60` again, which is the cheaper failure |
> | stage B / C | **DDL first**, then toggle | same reasoning as the un-wrap; the promote must land before the flags describing the new shape |
>
> Every **flag-transition** gap is delete-path-only — reads and inserts never consult the flag — so there
> the only question is which error it produces. The **DDL** interval is not: an `ON CLUSTER` rename is
> atomic per node, so cross-node skew can route a read at a replica that has already moved and fail it.
> That window is what `--confirm-maintenance` and read quiescence exist for; this table does not replace
> them.

> **Multi-replica note (production is multi-replica).** Stages B and C promote via a single `ON CLUSTER` RENAME of the
> **live** `spans`. It runs synchronously across the shard's replicas — the client blocks until each applies it, or fails
> loudly naming a laggard, which then converges via the DDL queue — so there is no durable mixed topology, only a brief
> sub-second cross-replica skew as it propagates, during which a read on a not-yet-renamed replica sees the pre-rollback
> `spans`. This is the same accepted `ON CLUSTER` skew as the wrap; on a multi-replica cluster run the rollback in a
> maintenance moment / with reads quiesced. `finalize.sh` is **exempt** — it renames only the parked backup / disposable
> shadow, never the live `spans`, so it has no live-read skew and needs no maintenance window.

**What the reverse replay can and cannot re-apply.** It re-applies the deletes the bridge **recorded**. Capture runs
before the delete but is best-effort by design — an auxiliary insert must never fail a user's delete — so a delete still
in flight when this runs, or one whose capture errored, is invisible to the replay *and* to its postcondition check,
which reads the same bridge: that span is live again on the restored original while the check still reports `0`. No
query here can detect it, so the bound is operational — **quiesce span deletes before the promote**, not just reads,
and let in-flight ones land. It takes a delete concurrent with the promote, or a capture failure (which the backend
logs), so the exposure is small — but `0` means "every recorded delete is masked", not "no delete escaped". A delete
that merely *errored* stopped being one of those cases in OPIK-8141: capture goes first, so it is recorded regardless.

The reverse case is a recorded delete that never applied, which this replay masks anyway — it carries no liveness guard,
by design (see `000004_rollback_reverse_replay.sql`). Accepted: the user did ask for that delete. It also stays
recoverable while the window is open, since the replay touches only `spans` and the row is still live on the parked
`spans_post_rollback_backup` until `finalize.sh` drops it.

**Recovering from an interrupted rollback.** Each promote stage runs its table-swap and then the reverse-replay as two
statements. Note what that means even when both succeed: from the moment the promote lands until the replay finishes,
the restored original is live with the post-cutover deletes **not yet re-applied**, so spans a user deleted after the
cutover are readable again. The window is the whole gap — the driver returning from one file and starting the next, plus
the replay's own run time — not just the replay. It is short for a rollback taken hours after the cutover, since only
deletes bridged since `cutover_start` are in scope, but it is a real exposure: keep reads quiesced from the promote
through the replay, not merely across the rename. A failure *between* the two needs a restart path:

- **Reverse-replay interrupted (stage B or C).** The promote already restored the original, so `spans` is back in the
  canonical shape and re-running the stage is (correctly) refused by the topology guard — which would otherwise leave the
  post-cutover deletes unreplayed and let them resurrect. Re-apply just the replay:
  `./scripts/rollback.sh --database opik --reverse-replay-only --cutover-start '<ts> UTC' --confirm-retention-paused`. It runs
  only `000004_rollback_reverse_replay.sql` and is idempotent (safe to run once or repeatedly). It refuses unless `spans`
  is the restored original (Nullable schema) with the successor parked as `spans_post_rollback_backup`, so it cannot be
  aimed at the live successor (post-EXCHANGE, pre-rollback), where the guard-less replay would mask live rows.
- **Forward EXCHANGE half-done (stage B says the backup is missing).** If the forward `EXCHANGE` succeeded but its
  post-swap `RENAME` did not, the parked original is still under `spans_local_v2` and stage B aborts pointing at the
  one-line `RENAME` that finishes it (`spans_local_v2` → `spans_pre_cutover_backup`); run that, then re-run stage B.

> **The repair is only meaningful on the restored original.** The parked successor stores an absent value
> as the epoch/NaN **sentinel** by design, so the same counts pointed there report *every* absent value as
> damage and invite a "repair" that would overwrite correct data. `--sentinel-repair-only` refuses unless
> `spans` is the Nullable original *with the successor parked* — that pairing is also what separates it
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

**Rolling back the `spanColumnsNonNullable` flip.** After a stage B or C rollback, `spans` is the Nullable original
again, so the flip has to be undone in two steps — `rollback.sh` prints both when the stage finishes. The rollback is not
complete until they land. **After stage C specifically, `spansDistributedWrapEnabled` must go back to `false` first**:
the stage removed the wrapper and parked `spans_local`, so a stale `true` aims span deletes at a table that no longer
exists (`Code 60`). That is the second of the two flags the stage comparison table counts for stage C.

1. **Revert `spanColumnsNonNullable` to `false` AND roll-restart every backend instance.** The flag is read from a
   **startup snapshot** of `OpikConfiguration` (bound via `toInstance`), so a config change does **not** take effect until
   each instance restarts — exactly like the forward rollout before the EXCHANGE. Until the restart completes, the app
   keeps binding sentinels (epoch/NaN) and using sentinel-based absent-value logic against the now-Nullable column,
   mixing sentinel and `null` representations: not a hard write failure, but inconsistent absent-value
   reads/filters/sorts.
2. **Repair the sentinels written into the original** (see the caveats under "The `spanColumnsNonNullable` flip").
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
   matched roughly seven times as many keys as the flag window had produced. Take the bounds from when the flag
   rolled out and when its revert finished landing on every instance. Rows are matched on `created_at` **or**
   `last_updated_at`. Both bounds are interpreted as UTC regardless of the server's timezone.

   **One case the window cannot catch, and the gate cannot see.** `SpanDAO.UPDATE` re-inserts a version copying
   `created_at` and — when the patch omits them — `end_time`/`ttft` verbatim, while `last_updated_at` takes
   `DEFAULT now64(6)`. So a span created *before* the window, patched *inside* it under the flag, then patched *again*
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
- [ ] **Flags reverted and the restart landed on every instance** — `spanColumnsNonNullable`, plus
      `spansDistributedWrapEnabled` if the wrap had been applied. Those are the only two — partition pruning is
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
      `TRUNCATE TABLE spans_post_rollback_backup`, which retires the last reference copy. Treat the box as a human
      gate, because that is all it is.
- [ ] **The post-cutover writes decided** — either recovered with
      `reconcile.sh --confirm-reimport-successor-writes` (see "Recovering the post-cutover writes"), or knowingly left
      discarded. `rollback.sh` prints the row count and both commands after the promote, so this is a decision with a
      number attached rather than a shrug. **`finalize.sh` refuses without `--confirm-post-cutover-decision`, which
      asserts the decision was MADE** — not that a recovery ran.
- [ ] **The parked successor still parked** — `spans_post_rollback_backup` retained, not finalized. It is the only copy
      of the post-cutover writes, and the only thing that makes a retry cheap.

Until the last box is ticked, do not run `finalize.sh`: it is what forecloses both going back and retrying cheaply.
`rollback.sh` prints that instruction last, after the steps it depends on, for the same reason.

**Retrying the cutover after a stage B/C rollback — without re-backfilling.** A rollback leaves the successor's data
parked as `spans_post_rollback_backup`, and the documented next step (`finalize.sh`) **truncates** it into an empty
`spans_local_v2`, so a naive retry starts from a full re-backfill. On a large table that is the difference between
minutes and days. The copy can be reused instead, because `spans_post_rollback_backup` **is** the same physical object
Liquibase created as `spans_local_v2`: a `ReplicatedMergeTree`'s replica path is fixed at `CREATE` and is unchanged by
renames — the same property `finalize.sh`'s recycle branch relies on. So renaming it back is a restoration, not a
reconstruction, and the estate stays consistent with the applied changesets.

This is deliberately **not** automated: it reuses data whose trustworthiness may be exactly why the rollback happened, and
it revives writes the rollback chose to discard. Run it only with the guards below understood.

1. **`finalize.sh` must not have run.** It truncates the parked backup — that is the point of no return for this path.
2. Restore the shadow name:
   ```sql
   RENAME TABLE <database>.spans_post_rollback_backup TO <database>.spans_local_v2 ON CLUSTER '{cluster}';
   ```
   It fails if `spans_local_v2` already exists — which is itself the signal that a retry cutover began before this
   rollback was finalized. Resolve that by hand rather than forcing it.
3. **The post-cutover writes the rollback discarded come back as live rows** once the retry's `EXCHANGE` lands. That is
   usually the point, but state it explicitly to whoever authorised the rollback.

   One exception, so nobody counts on the general form: an id that was **deleted and then re-created** after
   `cutover_start` does *not* come back. The rollback's reverse replay masked it on the restored original
   (deliberately guard-less), so on the retry the forward replay's resurrection guard sees it as not-live on the source
   and masks it on the shadow too. The delete is honoured; the re-creation is lost with the other discarded writes. Rare
   by construction, and not worth changing the replay for — the guard is right for the primary cutover path.
4. **Re-apply the `EXCHANGE`-window flags before the retry's `EXCHANGE`, exactly as for the first one.** The rollback told
   you to set `spanColumnsNonNullable` back to `false`, so it *is* false now; the retry puts the sentinel-schema
   successor back under `spans`, which needs it `true` and needs every backend instance restarted to pick it up (it
   comes from a startup snapshot). Skipping this is silent, not loud: absent `end_time` reads back as `1970-01-01` while
   writes keep succeeding. Nothing else needs flipping: no ingestion-path config change is involved, and span-delete
   partition pruning carries no flag, so the retry's `EXCHANGE` needs no pruning step in either direction — see
   "Span-delete partition pruning needs no flip at all". The retry's own final-delta→`EXCHANGE` gap and swap skew
   carry the same write exposure as the first run — see "The final cutover window".
5. Resume the normal sequence: `delta_replay.sh` with the **original** `backfill_start` anchor, marker included (the
   shadow still holds every row copied before it), then `verify.sh` before the `EXCHANGE`. That gate is what makes reuse safe — staleness or
   corruption in the reused shadow is caught exactly as in the first cutover — so do not skip it on the grounds that the
   data "was already verified once".

   **Bound it before the first cutover's window, and expect that week to differ.** The reused shadow is a *superset* of
   the restored original by exactly the revived writes from (3) — and those sit in the week the **original**
   `cutover_start` fell in, so an unbounded run reports them and looks like a fidelity failure on a perfectly good
   retry. Same shape and direction as the post-rollback compare above (the **new-table** side is the superset), so reuse
   the offset `rollback.sh` printed then: the last week wholly before that `cutover_start`. It also covers the live
   current week, which the restored original keeps writing to while the delta catches up.

   `--to-week last-sealed` is the wrong token here — it tracks the calendar, so a retry run in any later week stops
   excluding the window's own week. And the same caveat carries over: a write that touched a pre-existing span during
   the first window diverges it in a *sealed* week, which no weekly bound excludes. Triage it the same way — look the
   differing ids up in the shadow without a week filter, and treat `last_updated_at >= cutover_start` as benign.

   Note the flags from (4) do not change what `verify.sh` compares: it normalizes both sentinel and `NULL`
   absent-values to the same fingerprint, so it passes either way. It cannot catch a missed flag flip — only a positive
   probe can, which is why (4) is a step and not a caveat here. Run that probe **after** the retry's `EXCHANGE`, on every
   instance: write an in-progress span and assert `end_time` and `ttft` read back `null` rather than the epoch/`NaN`
   sentinel. Before the swap the restored original is still Nullable and answers `null` regardless, so a pre-EXCHANGE
   read-back would pass with a stale-`false` instance and leave exactly the silent wrong-reads state the flag exists to
   prevent.

   ```bash
   # N = the offset rollback.sh printed; old=spans, new=spans_local_v2 (the defaults)
   ./scripts/verify.sh --database opik --to-week <N>
   ```

If any of that does not hold, take the supported path: `finalize.sh` to recycle the backup into a clean shadow, then a
fresh backfill.

**Point of no return.** The `EXCHANGE` is reversible for as long as the parked backup exists (stage B/C). Retiring that
backup with `finalize.sh` is the one irreversible step, so gate it on an explicit soak. Note what it does and does not
foreclose: finalize ends the ability to return to the **original, unpartitioned** table, but the **wrap** stays
reversible indefinitely via `--unwrap-only`, which needs only `spans` and `spans_local`.

- **Soak duration** — keep the parked backup (`spans_pre_cutover_backup` after a successful cutover;
  `spans_post_rollback_backup` after a rollback) for a defined window (recommend ~2 weeks; it fits well inside the
  bridge's 2-year TTL) so any latent read/query regression surfaces while rollback is still an option.
- **Freeze `spans` schema DDL through the soak** (extends prereq #12 past the EXCHANGE). Rollback restores the **frozen
  original** `spans`, which carries no post-cutover DDL, so a column/index added to the successor in-window is **lost
  from the live table** on rollback; finalize's recycle then truncates the parked successor to an empty shadow (its data
  gone — the empty shadow keeps the added column, drift the next cutover's `cutoverCopiesEveryBaseColumn` guard flags).
  Do not deploy `spans` schema migrations until the soak ends (finalize committed). Post-finalize the general rule
  resumes: apply `ADD`/`DROP`/`MODIFY COLUMN` to **both** `spans_local` and the `Distributed` `spans` (see the wrap
  prerequisite).
- **Finalize exit criteria** — before retiring the backup: the ["When the cutover is done"](#when-the-cutover-is-done)
  checklist complete (or, after a rollback, "When the rollback is done"), `verify.sh` clean, query p99 within budget over
  the soak, no cutover-related incidents open, and (if the wrap was applied) the retarget flag
  (`spansDistributedWrapEnabled`) live and healthy across the backend fleet.

Once those hold, run [`scripts/finalize.sh`](scripts/finalize.sh) **with `--confirm` plus the branch's own
confirmation flag** — `--confirm-gap-reconciled` after a cutover, `--confirm-post-cutover-decision` after a rollback.
They are separate flags because the two branches assert different facts, and a gate in front of an irreversible drop
should not carry a name that is true on one branch and false on the other; each dry run names the one this estate needs,
so its first appearance is never a surprise, and passing the other branch's flag is refused rather than accepted. They
assert what no query can see: after a cutover, that `reconcile.sh` ran and returned `0` **on every shard** (its
statements are shard-local, this DROP is `ON CLUSTER`); after a rollback, that the accept-or-recover decision on the
post-cutover writes has been made. The script auto-detects whichever
parked table is present
(`spans_pre_cutover_backup` or `spans_post_rollback_backup`), never the live `spans`/`spans_local` or the working
`spans_local_v2` shadow, and picks the action by case: after a **successful cutover** it **drops**
`spans_pre_cutover_backup` (committing to the new layout); after a **rollback** it **recycles**
`spans_post_rollback_backup` into an empty `spans_local_v2` (TRUNCATE + RENAME — discarding the successor data but
restoring the exact 000115 shadow, so the estate matches the applied Liquibase state and a retry starts clean). It is
dry-run by default, `--confirm` to act, refuses if the live `spans` looks empty while the backup does not, and refuses
if both parked names somehow exist (ambiguous — resolve by hand).

## Deletion bridge lifecycle & future migrations

`deletion_events_local` is a **shared, long-lived** table (migration `000096`), not per-cutover. It is designed so
each migration sees only its own deletes, regardless of what else it already holds:

- **Multiplexed by `source_table`** (`traces`, `spans`, …), which is the **leading `ORDER BY` key**. A replay filters
  `source_table = '<table>' AND event_time >= <backfill_start>`, so it prunes — as a prefix scan — past every other
  table's events and every event before its own anchor. **This is the migration where that design earns its keep:** the
  bridge will NOT be empty when the spans backfill starts, because the traces cutover's events are still in it (the TTL
  is two years). The traces runbook noted that starting empty was incidental; here it is simply false, and correctness
  comes from the `source_table` filter rather than from the table's state. Prerequisite #13's check is scoped to
  `source_table = 'spans'` for exactly that reason.
- **Bounded** by monthly partitions (`PARTITION BY toYYYYMM(event_time)`) and a **2-year `TTL`**, so it cannot grow
  without limit. A cutover only needs events spanning its window (hours–days) plus the soak (~2 weeks), so the TTL has
  vast margin; shorten it only if the bridge ever runs hot under heavy delete volume.
- **Captured per source table** by independent knobs — `traceDeletionEventsCaptureEnabled` /
  `spanDeletionEventsCaptureEnabled` — so capture is scoped to the table being migrated. They are independent
  deliberately, which matters here: the traces cutover's capture should be **off** by the time this one starts (step 3
  below), and turning span capture on does not turn trace capture back on.

**Capture is a per-migration, per-table lifecycle** — treat the knob like a valve around each cutover:

1. Turn capture **on** just before that table's backfill starts (so every in-window delete is recorded).
2. **Keep it on through the soak** — the rollback reverse-replay reads the bridge, so capture must stay live until you
   are past the rollback window.
3. Turn it **off after `finalize.sh`** — once the migration is committed and out of rollback range, its capture is an
   extra write per delete with no reader.

Because the knobs are independent, you never need both on at once. The sequence across the two cutovers is: trace
capture on for the traces cutover → soak → finalize → **trace capture off**; then span capture on for this cutover →
soak → finalize → span capture off. **Confirm the first half actually completed before starting here** — a leftover
`traceDeletionEventsCaptureEnabled=true` is not a correctness problem for this replay (the `source_table` filter handles
it) but it is an extra write on every trace delete for a bridge nobody is reading, throughout a multi-day backfill.

**This directory IS the "future migration" the traces runbook anticipated**, and it follows the shape that runbook
prescribed: parallel `spans-local-v2-cutover` artifacts mirroring the traces ones (spans schema/columns,
`source_table = 'spans'`) rather than one generalized tool. That call still looks right in hindsight — the SQL turned
out to be more table-specific than "same statements, different name", not less: the dedup keys differ, the
`parent_span_id` and `usage` projections have no traces counterpart, the backfill is split in two, and half the
thresholds had to be re-derived. A parameterised single tool would have had to carry all of that as conditionals in the
statements whose silent failure loses data. **A third migration should make the same choice for the same reason, and
should expect to re-derive rather than inherit.**

## Per-deployment-variant notes

| Variant | Strategy | Notes |
|---------|----------|-------|
| Comet SaaS | Live cutover (this runbook) | No ingestion-path config change. Tail write-gap per "The final cutover window" (OPIK-8238). Bridge active through the soak. **This is the only variant whose measurements the thresholds here were derived against**; re-derive them for any other. |
| On-premise enterprise | Live cutover | Same runbook; ships in the same Helm push. **Re-derive, do not inherit**: the partition count, the bytes/row and the part sizes that set `--max-partitions-per-insert-block`, `--min-insert-block-size-bytes` and the settle thresholds are all properties of the estate. `estimate.sh` measures them — run it there too. |
| Open-source Docker | Brief read-only window | Little data, downtime acceptable. Bridge still ships; the replay is a no-op when there were no concurrent deletes. At this size the partition count is small and the headroom gate is a formality. If the Liquibase ClickHouse extension cannot run `EXCHANGE ON CLUSTER`, use the fallback `RENAME` sequence. |
| AWS SageMaker | Live cutover | Runs on its own cadence; the bridge ships ahead of the cutover. Same re-derivation caveat as on-premise. |

## Verifying the migration (QA)

Prove the copy altered no data by comparing a **normalized fingerprint** of source and destination with
[`scripts/verify.sh`](scripts/verify.sh) (reference query:
[`000005_verify_migration.sql`](scripts/db-app-analytics/000005_verify_migration.sql)). The rows are not byte-identical
after the copy — `end_time` NULL becomes an epoch sentinel, `ttft` NULL becomes NaN, timestamps drop from nanosecond to
microsecond, `parent_span_id` becomes a `FixedString(36)`, `usage` widens to `Int64` — so both sides are canonicalized
to the same value for a faithfully-migrated row before hashing: timestamps as their microsecond epoch, absent
`end_time` as 0, absent `ttft` as the token `nan`, enums/ids/LowCardinality/Decimal via `toString`, `parent_span_id` as
a plain `String` with the copy's own length guard applied to the source side, and `usage` as sorted `key \x1e value`
pairs joined on `\x1f` (a `Map` has no guaranteed key order, so the sort is what makes the hash stable). Each row
hash includes the `id`; rows are deduped with `FINAL` and the delete mask is honored, so the comparison is of the live,
logical content — **and the old-schema side is reduced a second time, to the destination's dedup key**, which is the
one structural difference from the traces compare (see
["The dedup keys differ"](#the-dedup-keys-differ-and-it-changes-every-comparison-in-this-runbook)). The fingerprint
intentionally covers only the **copied base columns**: the materialized/derived columns
(`*_length`, `truncated_*`, `duration`, `id_at`) are recomputed from those bases by identical pinned expressions,
so they cannot diverge unless an expression itself changes — which the gate test's dedicated derived-column parity check
catches directly. Per week it compares `count()` and an order-independent `sum` of the row hashes — together these catch
any changed, missing or extra row (`sum`, unlike `groupBitXor`, does not cancel a colliding pair within a table). The row
hash is `cityHash64`, not `sipHash64`: both sides are hashed live on the same instance, so a fast non-cryptographic
64-bit hash is enough — `sipHash64`'s adversarial-collision resistance would only add CPU (it is the right choice for the
*sharding key* in the wrap, a different job). **This is the exact normalization the gate test asserts** (see below), so
the tool is proven correct, not just plausible.

```bash
# Full compare, every week, before the EXCHANGE (source=spans, dest=spans_local_v2 successor):
CLICKHOUSE_HOST=<host> CLICKHOUSE_PASSWORD=<pw> ./scripts/verify.sh --database opik
# After the EXCHANGE: `spans` is the successor and the old data is parked as spans_pre_cutover_backup:
./scripts/verify.sh --database opik --old-table spans_pre_cutover_backup --new-table spans
```

> **A version tie makes a window undecidable, and the gate says so rather than guessing.** Where a key's newest
> `last_updated_at` is carried by more than one **distinct** row, `FINAL` has no winner and the comparison for that key
> is arbitrary in both directions. Where the re-check would otherwise call the window an artifact, `verify.sh` counts
> those keys per side with the `version-ties` block and reports the window **INCONCLUSIVE**, exiting non-zero: not a
> mismatch, and explicitly not a pass.
>
> Distinct content, not row count, is what makes this usable before the `EXCHANGE`: the delta re-copies every row the
> backfill already wrote, and an unmodified row keeps its `last_updated_at`, so the successor legitimately holds several
> identical rows at one version until a merge collapses them. Counting rows would report every healthy window as
> undecidable. `FINAL` choosing between byte-identical rows changes no verdict, so only differing content counts.
>
> Resolving a real tie is still manual — see "a version tie" under *Verifying after a rollback* for the version-set
> read, ignoring that section's `cutover_start` test, which has no meaning before the `EXCHANGE`.
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

**Verifying after a rollback.** After a stage B/C rollback the defaults do not apply — `spans_local_v2` no longer
exists (the successor is parked as `spans_post_rollback_backup`), so a bare `verify.sh --database opik` dies with
`Code: 60 … Unknown table … spans_local_v2`. The old-schema side is now the restored original and the new-schema side the
parked successor:

```bash
# rollback.sh prints this command with the bound already computed — prefer that over deriving the offset by hand.
./scripts/verify.sh --database opik --old-table spans --new-table spans_post_rollback_backup --to-week <N>
```

Expect the **cutover window's own week to mismatch**, by exactly the post-cutover writes the rollback discarded (the
parked successor holds them; the restored original never did) — so stop before it. Note the divergence is the
**opposite** direction from the post-EXCHANGE case: here the *new-table* side is the superset.

> **This bound is for the ACCEPT option.** If you took the recover option
> (`reconcile.sh --confirm-reimport-successor-writes`, see "Recovering the post-cutover writes"), those writes are back
> on the restored original, so the week no longer legitimately mismatches by writes: **drop `--to-week` and compare
> unbounded.** What is still expected there is post-cutover *deletes* — masked on the original by the reverse replay,
> still live in the parked successor — plus anything written after the promote. `rollback.sh` prints both readings.

**Bound this one by `cutover_start`, not by the calendar.** `--to-week last-sealed` drops the current calendar week,
which is the window's week only while the verify runs promptly; run it in a later week and the window's week counts as
sealed, so its discarded writes read as a fidelity failure. `rollback.sh` prints the offset of the last week wholly
before `cutover_start`, which does not drift — use it.

**And a mismatch inside the bound is not automatically corruption.** Any write that touches a **pre-existing** span
after `cutover_start` diverges it in a *sealed* week, which no weekly bound can exclude — the divergence sits where the
row was born, not where the write happened. Two mechanisms, opposite in shape:

- the **span-update endpoint** keeps the row's original `created_at`, so the successor holds a newer version in that
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

> **The pre-EXCHANGE compare is the gate; the post-EXCHANGE compare has a caveat — and a direction that is NOT benign.**
> `spans_pre_cutover_backup` is a **frozen** snapshot as of `cutover_start`, but live `spans` never stops taking
> writes — so the **current (live) week will legitimately show a mismatch** (the live table is a superset of the frozen
> backup by exactly the post-cutover writes). That is expected, not a leak.
>
> **The opposite direction — rows present in the backup and absent from live — is the write loss OPIK-8238 fixed**, and
> the bounded weekly compare is a poor instrument for it: the loss sits in the gap window, i.e. in the *current* week,
> which is exactly the week the bound excludes. The instrument for it is `reconcile.sh`'s postcondition, which is scoped
> to the gap and gates on `missing_keys` directly; **run that, not a weekly compare, to decide whether the swap lost
> writes.** Use `verify.sh --window-from/--window-to` over the same range afterwards for the payload-level picture the
> four counts summarise.
>
> To use the post-EXCHANGE weekly compare as a real check, either run it **immediately after the swap before writes
> resume**, or bound it to the **sealed historical weeks** with
> `--to-week N` (a **0-based week offset** from the anchor Monday, not a date — e.g. `--to-week 3` to stop before the
> current partial week), where a mismatch is worth investigating — with one exception in the same class as the
> post-cutover writes: a write touching a **pre-existing** span after `cutover_start` diverges it in that row's own
> week, sealed or not (see "Verifying after a rollback" for the two shapes and the triage). Check
> `last_updated_at >= cutover_start` on the differing ids before calling it a defect.

**Feasibility at scale.** A full pass reads every week in the range (heavy but bounded per week — run off-peak). When
that is infeasible, sample and still get high confidence:
- `--sample-mod N` compares a deterministic 1/N `id` sample — the *same* rows on both sides, so like-for-like.
- `--weeks-stride S` compares every S-th week, so it reads a fraction of the windows and is genuinely cheaper. Note
  that a window is narrowed by the `created_at` minmax skip index, not by partition pruning: the source is
  unpartitioned and the successor's partitions are id_at-derived.
- `--receive-timeout N` raises the client's per-packet wait (default 1800, against ClickHouse's 300). The
  post-mismatch confirm-keys re-check can stall past the stock value and abort the compare at the first mismatch.
- `--from-week` / `--to-week` bound the range by **0-based week offset** (integers from the anchor Monday, not dates;
  `--to-week` is inclusive) — e.g. verify the most recent weeks fully, older weeks sampled.
- `--window-from` / `--window-to` compare **one arbitrary `created_at` window** instead of walking the week grid —
  half-open `[from, to)`, `'YYYY-MM-DD HH:MM:SS[.ffffff]'`, interpreted as UTC (an optional ` UTC` marker is accepted, so
  a value pasted from a driver's `RECORD` line works as-is). This is the shape post-swap reconciliation needs: the range
  of interest is *the gap*, which is not a calendar week and sits inside the one a weekly bound excludes.
  **Mutually exclusive with `--from-week` / `--to-week` / `--weeks-stride`** — those are offsets into a grid this mode
  never builds, so combining them is refused rather than silently resolved. No new SQL is involved: `000005`'s blocks
  were already parameterised by arbitrary `${WINDOW_LO}` / `${WINDOW_HI}`, and only the driver was generating week
  boundaries; `--sample-mod`, `--drill-down`, `--receive-timeout` and the confirm-keys / version-ties resolution all
  behave identically. The anchor scan is skipped (nothing needs a week grid), so unlike the weekly form the run does not
  depend on `min`/`max(created_at)` holding still, and the `PASSED` line states the window it covered.
  ```bash
  ./scripts/verify.sh --database opik --old-table spans_pre_cutover_backup --new-table spans \
      --window-from '<delta_start>' --window-to '<exchange_done, UTC>'
  ```
  Bound the window at the swap, never at `now`: the parked table stops receiving rows there, and the compare passes
  only when both sides hold the same count, so a later bound fails on post-swap traffic rather than on fidelity.

`verify.sh` exits non-zero if any window **mismatches or is INCONCLUSIVE**, and prints the window bounds either way;
re-run with `--drill-down` to list the keys that differ or exist on one side only (it runs the `drill-down` block of
`000005_verify_migration.sql` for every differing window, artifact and inconclusive verdicts included — those are the
ones most often worth reading).

## Verification — the automated test

`SpansLocalV2CutoverTest` rehearses this exact sequence against a fresh ClickHouse and asserts:

- **0 deletion leaks** across the EXCHANGE, for deletes before the backfill (excluded by the mask, never copied) and
  for the trace-delete cascade during it — which on spans is the only delete shape there is;
- a **negative control** proving the bridge is load-bearing (the leak reappears when replay is skipped);
- **full-key replay** — a reused id deleted in one project survives in another (no over-delete by id alone);
- **resurrection guard** — an id deleted and then re-created under the same id during the window stays live on the
  destination (a naive replay-by-key would drop it — silent data loss);
- **delta completeness** — a row written during the window with a client-backdated `last_updated_at` is still caught (via
  the `created_at` arm) and survives the cutover;
- newest-version-wins for concurrent upserts;
- **normalized-fingerprint fidelity** — the deduped, mask-honored, normalized `(count, checksum)` of source and
  destination are equal before the swap (the same normalization `verify.sh` uses, so the QA tool is proven correct);
- **derived-column parity** — the recomputed columns (`id_at`, `*_length`, `truncated_*` exactly; `duration` within the
  intended ns→us precision, `NULL`↔`NaN` normalized) match between source and destination, so a
  divergent MATERIALIZED expression is caught even though the base-column fingerprint excludes them;
- **schema-parity guards** — the cutover copies every base column of `spans`, and both tables expose the same base and
  materialized columns (a future migration that drifts either fails the build);
- **the partition spread one window reaches** — a single window holding ordinary rows, far-future ids and far-past
  (non-v7) ids is copied by one statement into three distinct weekly partitions, each row exactly once. That spread is
  what `max_partitions_per_insert_block` has to permit;
- **the destination does not inherit the source's wrapped `id_at`** — a far-future id whose 32-bit `spans.id_at` wraps
  into a plausible recent instant still partitions by its honest ~2201 week on the successor;
- **the `parent_span_id` normalization** — a 40-character poison value (what `SpanDAO`'s `PARTIAL_INSERT` stores when a
  span's parent changes) is copied as the root sentinel rather than aborting the window with `TOO_LARGE_STRING_SIZE`,
  the fidelity fingerprint treats that as a match rather than a mismatch, and the reverse sweep casts the destination's
  NUL-padded form back to `''` so a re-imported root span does not read as a child;
- **the narrower destination dedup key** — a span held twice on the source under different `parent_span_id` values
  collapses to one row on the destination, newest `last_updated_at` winning, and the fidelity compare's `argMax`
  reduction agrees with that choice rather than reporting a count mismatch; and the same pair at ONE version is reported
  as a version tie rather than silently decided;
- **the `usage` widening and its narrowing** — `Map(String, Int32)` → `Map(String, Int64)` round-trips through the
  fingerprint's canonical encoding, and the reverse direction's out-of-Int32 precheck detects a value the narrowing
  would wrap;
- `EXCHANGE TABLES ... ON CLUSTER` and the single-shard `Distributed` wrapper both work;
- **post-swap reconciliation, both directions** (OPIK-8238) — the forward sweep restores a span written in the
  `[last delta, EXCHANGE]` gap, with a **negative control** proving the write is simply lost when the step is skipped;
  the sweep does not resurrect a gap-window span deleted after the swap; a gap-window span written again after the
  swap keeps the newer version and is reported as `newer_keys` while the gate still passes; the post-swap replay masks a
  delete bridged between the final pre-swap replay and the `EXCHANGE` (with the sweep alone shown to be insufficient, so
  the two steps are not confused); the per-row staleness scope stops that same frozen guard destroying a post-swap
  re-creation, **with its own negative control** showing the write disappear when the scope is inert; the reverse sweep
  re-imports post-cutover writes with sentinel→`NULL` denormalization so their recomputed `duration` is `NULL` rather
  than a large negative, and does not resurrect a span deleted since `cutover_start`; and the four-count postcondition
  classifies each key into exactly one bucket on the destination's full `(workspace_id, project_id, trace_id, id)` key,
  including `payload_mismatch_keys` NON-zero — a key live on both sides at the SAME version with differing content,
  which is the one gating bucket the other cases cannot reach, and the one a sweep cannot fix;
- **the fidelity gate's mismatch resolver** (`confirm-keys`) — the block that decides what a differing window MEANS,
  asserted in BOTH directions: a differing row that LOSES the version comparison is reported as a superseded-version
  artifact (0), and one that WINS it is reported as a real fidelity failure (1). A wrong 0 here turns a broken copy
  into a PASS, which is the most dangerous false negative in the procedure;
- **the rollback's sentinel repair** — `ALTER UPDATE end_time = NULL` / `ttft = NULL` over the flag's window restores
  both columns AND the mutation recomputes the original's MATERIALIZED `duration`, so `stale_duration` reaches 0. That
  last count exists because the behaviour was not obvious; the test settles it, which is what makes the documented
  repair sufficient without a follow-up `MATERIALIZE COLUMN` (which would re-evaluate the same expression);
- **reversibility** — stage A discards the shadow leaving the live table untouched, and stage B restores the original
  and reverse-replays so a post-cutover delete does not resurrect. **Stage C is NOT covered**, and cannot be: it
  reverses a wrap this runbook defers, so no estate reaches the state it asserts while that holds. Its SQL ships and
  is reviewed; the rehearsal's job is to confirm its topology guard REFUSES on an unwrapped estate;
- **the wrap and its un-wrap** — the `Distributed` wrapper reads the shard transparently on one shard, and
  `--unwrap-only` restores the partitioned successor with its post-wrap writes still live (the contrast with stage C,
  which would discard them). The product side of the wrap — that span mutations route to `spans_local` under it — is
  covered by OPIK-7799's own `SpansDistributedWrapMutationTest`, not duplicated here;
- the replay wall time is measured and logged (not asserted — it is environment-sensitive; sizing the tail against a
  real workload is done in the cutover rehearsal, not in CI).

**What it does not cover, and why that is a decision rather than a gap.** The suite drives SQL directly, so nothing in
the `scripts/` drivers is exercised by it. **Unit-testing them is deliberately out of scope, and the reason is lifespan
rather than feasibility**: the repo does run bash suites elsewhere (`test_rebaseline_db_changelog.sh`,
`test_precommit_wrappers.sh`) and stubbing `clickhouse-client` would work. But this is migration tooling with a finite
life — once the cutover and its soak are done it stops changing, a third table would get its own parallel directory
rather than reusing these files, and a harness over every driver in `scripts/` would be permanent maintenance on code
heading for the archive. That trade-off was made for the traces drivers and is inherited here deliberately; the spans
drivers carry MORE argument logic than the traces ones did (the destination-relative headroom gate, the reverse
usage-range precheck), so it is worth re-stating that the decision
was re-examined and kept rather than simply copied.

**The sanctioned validation is performing the procedure** — the forward cutover and the rollback of each stage that is
reachable (A and B; C and `--unwrap-only` need a wrapped estate, which this window does not create, so their guards
should be seen to refuse instead) — on a local or test environment, with the rehearsal harness in
[`tests_load/tests/spans-local-v2-cutover/`](../../../../tests_load/tests/spans-local-v2-cutover/). That exercises the drivers against a real
ClickHouse rather than a stub, which is the stronger check, and it suffices because every guard fails *closed*: a
refused argument, a lagging replica or a mis-marked SQL block aborts before any DDL or mutation is sent, so an untested
guard costs a re-run, not data. Do not add per-driver suites here without revisiting that trade-off explicitly.

**One property the traces suite asserted and this one does not**, so it is not assumed to be inherited: the
**wrong-stage rollback guard**. `rollback.sh` keys on the `spans` engine and `end_time` nullability, which are distinct
in each cutover state, so a mis-targeted stage aborts instead of touching the wrong table. That is driver logic rather
than SQL, and on spans two of the four stages it discriminates between are unreachable — so it belongs in the rehearsal
below, alongside the other driver guards, rather than in a suite that drives SQL directly.

So the cutover rehearsal is what covers the drivers: their argument validation, their topology guards, and the
`verify.sh` behaviours worth separating from the rest, because they decide a *verdict* rather than reject an argument —
and a wrong verdict from a fidelity gate is the failure this whole procedure exists to avoid:

- refusing to report `PASSED` when the bounds selected **no** window (an empty range compares nothing, so a pass would be
  vacuous);
- the `--to-week last-sealed` resolution — the current-calendar-week bound, capped at the last populated week;
- its refusal when that resolution lands before `--from-week`, the all-data-in-the-current-week case;
- `--window-from` / `--window-to`: that they are refused in combination with any of `--from-week` / `--to-week` /
  `--weeks-stride`, that an empty or inverted range is refused rather than passed vacuously — including two bounds that
  name the same instant at different precisions — and that the `PASSED` line states the window it covered.

`reconcile.sh` adds several of the same kind, and they decide whether the estate is reconciled rather than merely
rejecting an argument:

- **direction detection** — forward on `spans_pre_cutover_backup`, reverse on a `spans_post_rollback_backup` that
  carries the successor schema, refusal when both or neither is present, and the split-state diagnosis (EXCHANGE done,
  post-swap `RENAME` not) with the completing `RENAME` printed;
- **`--report-only`** issuing no mutation in either direction, and exiting non-zero when it finds a gap;
- the **idempotent no-op**: a second run on a reconciled estate reads the postcondition, issues nothing and exits 0;
- **failing loudly** rather than reporting progress when the gate is still non-zero after `--max-passes`;
- the **schema proof on the live table**, which requires `end_time` to be present before testing its shape — an
  absent column is not the same as a non-Nullable one, and a bare test waves the first through;
- the **widen-only rule on the reverse `--gap-start`**: a later value is refused, since the reverse sweep and its
  postcondition share that bound and narrowing it would hide exactly what it skipped;
- the **shard-scope guard**: a multi-shard estate refused without `--confirm-single-shard` (and refused outright in the
  reverse direction, whose postcondition spans shards its replay cannot reach), an unreadable shard count failing
  closed, and the `RECONCILED` line carrying its `SCOPE:` qualifier when the scope was asserted rather than proven.
  Worth exercising by hand even on a single-shard estate — the consequence of getting it wrong is `finalize.sh`
  dropping every shard's backup on a one-shard assertion.

And `finalize.sh` adds two: refusing each branch without ITS OWN confirmation flag — `--confirm-gap-reconciled` for
the post-cutover drop, `--confirm-post-cutover-decision` for the post-rollback recycle — with the right diagnosis for
whichever backup is parked; and refusing the OTHER branch's flag outright rather than honoring it.

Each rests on manual verification. Exercise them in the rehearsal alongside the driver guards, and treat a change to any
of them as needing the same.

The **replication-settle gate** belongs in the same category — it decides a verdict rather than reading a single
number — so it needs the rehearsal too, under **live ingestion**, in both directions:

- it does **not** abort on ordinary ingest churn (a busy-but-not-stuck `replication_queue` is accepted, with its
  numbers printed);
- it **does** abort on a genuinely lagging replica, naming the offending entries — stop or throttle a replica, or hold
  a mutation, and confirm the gate fails loudly rather than passing;
- unfinished mutations in the gate's mutation scope are judged unconditionally: one fails the gate however quiet the
  queue is, and `--settle-timeout` bounds how long it waits to find out. Hold a mutation deliberately to see it — but
  note the pre-swap gate samples before the final deletion replay is issued, so that replay is not what this rehearses;
- **and rehearse `reconcile.sh`'s scope separately**, because it is the one that changes: its mutation check covers the
  **parked backup only**, so the rehearsal has to confirm both halves — that a delete still applying to the parked table
  fails the gate, and that live-table deletes flowing throughout do **not**. The second half is the one that matters in
  production: if it were gated, the gate would abort on every healthy post-swap cluster.

The **argument guards** fail fast, before touching ClickHouse, so they are cheap to exercise by hand after any change
here — `--with-wrap` and `--wrap-only` must both refuse without `--confirm-maintenance` **and** without
`--confirm-daos-retargeted` (whose refusal on spans should be read, not skimmed: it says the assertion cannot currently
be made at all), and `--settle-timeout` must refuse a leading zero as well as anything outside 0–7200. Keep that last
check **lexical**, on the digit count, rather than turning it into a numeric comparison: bash arithmetic wraps silently
past 2^63 instead of erroring, and a wrapped value is negative, so it both passes a `<=` test and leaves the gate's poll
count negative — which skips every sample and passes the gate without reading replication at all.

**Four `backfill.sh` guards are new for spans and have no traces counterpart, so they need exercising explicitly:**

- **the destination-relative headroom gate.** Confirm it refuses below the requirement, that its abort message names
  the levers and explicitly rules out lowering the flag, and that the thin-margin WARNING fires between the requirement
  and +3 TiB. Also confirm `--dest-compression-ratio` above 1.0 is refused — that direction would make a conservative
  gate optimistic, which is the one thing this parameter must never do;
- **`reconcile.sh`'s reverse usage-range precheck.** Plant a `usage` value above `2^31` in the parked successor and
  confirm the reverse direction REFUSES rather than importing it wrapped, and that `--report-only` reports it rather
  than staying silent.

Run it with: `mvn -o test -Dtest=SpansLocalV2CutoverTest` from `apps/opik-backend`.

## Monitoring and abort criteria

Watch these for the whole backfill→EXCHANGE window; wire alerts before starting, not during:

- **Free disk per volume** (`system.disks`) — and here, unlike on traces, **keep watching it through the soak**, not
  only through the window: the parked backup holds the whole source for the length of it, so the soak's floor is the
  post-backfill free space unless the `traces_pre_cutover_backup` lever was taken. See
  ["Can spans afford to park the backup at all?"](#can-spans-afford-to-park-the-backup-at-all).
  Alert well before any volume fills, per volume, not on the node total.
- **Active part count / merge backlog** (`system.parts`, `system.merges`) on `spans_local_v2` — a runaway part count
  means merges are not keeping up; increase `--pause-seconds`. One spans-specific reading: a block writes one part per
  partition it touches, and the destination's many partitions mean the backlog is spread thin, so a total that looks
  alarming may be shallow per partition. `parts_to_delay_insert`
  and `parts_to_throw_insert` are **per partition**, so read them that way (from `system.merge_tree_settings`, not from
  memory — ClickHouse has changed these across versions).
- **Peak insert memory** (`system.query_log`'s `memory_usage`, filtered by the `log_comment` the driver sets) — the
  term the traces cutover never had to watch, and the one `--min-insert-block-size-bytes` controls. Compare it against
  the pairs in ["Partition spread"](#partition-spread-and-the-one-setting-that-matters): materially above them means
  the byte bound is not binding, and the fix is that flag, not more memory.
- **Replication backlog** (`system.replication_queue`) and **mutations** (`system.mutations` `is_done = 0`) — must trend
  to zero; a growing queue means a replica is falling behind.
- **Query p99** on the project spans listing — the backfill competes for I/O; a sustained regression is an abort signal.
- **Deletion-capture health** — capture is best-effort and **swallows** errors (so a bridge hiccup never blocks a user's
  delete), so watch the backend logs for `captureDeletions` failures. A silently-dropped capture would leak a delete.
  `verify.sh` catches that as a pre-EXCHANGE week mismatch (the row is live on the destination but gone on the source)
  **for any capture failure up to the last pre-EXCHANGE verify**. A capture that fails *later* is narrower than it used
  to be but is still not covered: the post-swap replay in `reconcile.sh` re-reads the bridge, so a delete whose capture
  simply landed **late** is now caught, while one whose capture **errored** leaves no bridge row for anything to read and
  remains visible only in this log-watch. So it is an early-warning signal, not a silent hole: treat repeated failures as
  an abort signal until capture is healthy, and treat **any** `captureDeletions` failure observed from the last verify
  through the reconciliation as a swap-gating signal.

**Roles.** Name an operator (runs the scripts), an independent observer (watches the dashboards), and the person with
authority to call a rollback. **Abort thresholds** (decide the numbers up front): free disk below the per-volume alarm,
query p99 beyond the agreed budget, or replication backlog that will not drain. Aborting before the `EXCHANGE` is
cheap (stage A); the bridge stays enabled so nothing is lost on a retry.

## Go / No-Go checklist (production cutover)

The six items below are the ones that decide whether this cutover can run **at all**. The first five have **no traces
counterpart**; the sixth is a traces item whose lesson is that it was left until afterwards, which is why OPIK-8263
exists. They come first deliberately: the rest of the checklist is the traces list re-derived, and working it before
these is working the wrong problem.

- [ ] **HEADROOM: a written answer, satisfiable against the real volume, with a number attached.** `estimate.sh`'s
      headroom verdict passes, its margin is stated, and — if that margin is under 3 TiB — the decision that closes it
      is recorded: the `traces_pre_cutover_backup` retirement (OPIK-8263, **irreversible and separately
      authorised**, with the authorisation named here), a volume increase, or both. **Lowering `--min-free-factor` is
      not an answer** and the driver refuses to pretend otherwise. Proven by a dry-run `estimate.sh` on **production**
      and a full backfill on **prod-test**. And record the SOAK's floor too, not only the window's — the parked backup
      holds the whole source for its length (see
      ["Can spans afford to park the backup at all?"](#can-spans-afford-to-park-the-backup-at-all)).
- [ ] **BLOCK BOUNDS: peak insert memory measured on prod-test, at real spans row widths.** Run a few windows and
      read `max(memory_usage)` from `query_log` by `log_comment`. Record it against the data nodes' headroom, and
      record the destination part count the same run produced. `--min-insert-block-size-bytes` is the only dial over
      either, and it moves them in opposite directions — see
      ["Partition spread"](#partition-spread-and-the-one-setting-that-matters). An unmeasured default is not a
      decision.
- [ ] **PRE-WRITE AUDITS run on production and recorded** — `estimate.sh` without `--skip-audits`. Both numbers in the
      Go/No-Go record: `total_partitions` (which sized `--max-partitions-per-insert-block`) and the `parent_span_id`
      normalization count. The first sets a value the FIRST INSERT uses, so "we'll check during the backfill" is not
      available.
- [ ] **EVERY THRESHOLD RE-DERIVED, not copied.** Walk
      ["Threshold derivation"](#threshold-derivation--every-number-and-where-it-came-from) and confirm each row still
      holds against this estate's measurements — several are production-specific (part sizes, bytes/row, partition
      count) and a different estate moves them. Where a derivation lands on the traces value, that is fine and is
      recorded as such; where it lands elsewhere and the flag was not changed, that is a defect.
- [ ] **THE WRAP IS DEFERRED, and that is a recorded decision rather than a constraint.** OPIK-7799 has shipped, so
      `--with-wrap` would now succeed. Confirm the decision is to stop at the `EXCHANGE`: nobody has planned a wrap
      maintenance window, `spansDistributedWrapEnabled` stays `false`, stage B's grants are provisioned (the only
      rollback this window can need), and stage C's and the wrap's are **not**. If the decision goes the other way,
      ["the readiness gap"](#the-readiness-gap-that-opik-7799-left-open) is a blocker to close first, not a caveat.
- [ ] **The spans migration user's grants are the minimum the drivers need, and its REVOCATION is scheduled** — date,
      owner, and what it takes away, decided before the window rather than after. OPIK-8263 exists because that half was
      left until afterwards on traces; do not repeat it. Do not reuse or widen the traces account.

---

- [ ] **Runbook rehearsed on a production-shape staging snapshot** end-to-end; timings recorded. Staging must match
      production **topology**, not just data shape — same replica count and tiered-storage policy — since the
      multi-replica settle gate and the storage/TTL parity are otherwise untested until production. Rehearse with
      **parallel traffic still flowing through the swap** — converging by stopping traffic tests an assumption this
      procedure does not make.
- [ ] **Deletion test green** — `SpansLocalV2CutoverTest` passes; **0 deletion leaks** confirmed on staging.
- [ ] **Reconciliation postcondition returned 0, on every shard** — `reconcile.sh` ran immediately after the
      `EXCHANGE` and reported `missing_keys=0 stale_keys=0 payload_mismatch_keys=0`. A non-zero `newer_keys` alongside
      those three zeros is expected, not a failure. Rehearse it on the prod-clone in **both** directions and record the
      sweep timings. This is the item that closes the tail write-gap; nothing else in this checklist does, and a weekly
      `verify.sh` compare cannot — the gap rows sit in the cutover week, which every weekly bound excludes.
      **Every statement it issues is shard-local while `finalize.sh` drops the backup `ON CLUSTER`**, so on more than one
      shard this box needs a `RECONCILED` from each; the driver refuses a multi-shard run without `--confirm-single-shard`
      and labels the verdict's scope when given it.
      Also **record `leaked_delete_keys`**. It is not part of the gate — the reconciliation is complete either way — but
      non-zero means captured deletes are still live on the successor (the client-timestamp residual in "The final
      cutover window"), and those keys should be re-applied before the estate is called done.
- [ ] **Final-delta→EXCHANGE gap kept short** — record the **whole** interval, not just the final replay's wall time:
      the replay's own `--time` output *plus* the elapsed-through-`EXCHANGE` figure `exchange_and_wrap.sh` prints in its
      tail summary, which itemises the settle-gate wait. This does **not** substitute for the item above: the gap cannot
      be driven to zero, because the procedure places a full deletion replay and a settle gate inside it. Its length
      decides how much the sweep has to carry and how long gap-window spans are transiently absent from live reads, and
      on a busy cluster the gate is the part of it that varies.
      **Also record the `EXCHANGE`→`RENAME` interval inside the `exchange` block** (both `ON CLUSTER`; read it from
      `query_log` by `log_comment`). It sits inside the delete-side resurrection window in "The final cutover window",
      because `exchange_done` is read only after both statements return, and that section says what a large value
      would justify.
- [ ] **Far-future AND far-past partitions quantified — and the partition caps sized from the result.** `estimate.sh`
      audit 1 run and recorded; remediated or explicitly accepted. The count is not just informational: above
      ClickHouse's default of 100 the backfill **aborts** (`throw_on_max_partitions_per_insert_block = 1`). Size
      `--max-partitions-per-insert-block` above the audit's `total_partitions`, which is a HARD upper bound — a block
      cannot span more partitions than the table has — and **pass the same value to `delta_replay.sh` and
      `reconcile.sh`**, both of which write into the same partitioned successor and neither of which is bounded to one
      `created_at` window. Set it on the migration user's settings profile too, so it does not depend on the
      invocation. Confirm the driver's startup line reports the bounds you intended.
- [ ] **`parent_span_id` normalization count recorded** (`estimate.sh` audit 2). Rows whose `parent_span_id` is neither
      empty nor 36 bytes — `SpanDAO`'s 40-character poison value — are copied with the root sentinel instead. The
      original bytes stay in `spans_pre_cutover_backup` until `finalize.sh`, so this is recoverable during the soak and
      not afterwards. Accept it explicitly rather than discovering it in a fidelity report.
- [ ] **`EXCHANGE TABLES ... ON CLUSTER` works end-to-end** — or the fallback `RENAME` sequence is documented for the
      variant that needs it.
- [ ] **No ingestion-path config change on any path** — forward, rollback, wrap or un-wrap. The procedure requires
      one rolling restart to reach the `EXCHANGE`, carrying only `spanColumnsNonNullable = true`, with no
      steady-state latency cost though it does consume ingestion capacity while it rolls — plus the wrap's own
      restart for `spansDistributedWrapEnabled` if the wrap is applied. Confirm you know
      how a backend restart is triggered on the target deployment and that it fits the schedule — see
      ["The one rolling restart"](#the-one-rolling-restart-spancolumnsnonnullable).
- [ ] **Data Retention confirmed disabled** for the cutover window **through reconciliation** (`RETENTION_ENABLED=false`).
      Retention deletes bypass the deletion bridge, so a sweep in the window would leak/resurrect across the swap — and
      one firing *after* the backup freezes is undone by step 5's sweep, which is why the window does not end at the
      `EXCHANGE` (see the retention note). `exchange_and_wrap.sh`, `rollback.sh` (stages B/C) and `reconcile.sh` all
      enforce `--confirm-retention-paused`, but that is an assertion — this item is the real "it is actually paused on
      every backend" verification.
- [ ] **Reconciliation clean** — per-window source/dest counts within 0.01% across the whole backfill.
- [ ] **Replication settled before the EXCHANGE** — no unfinished mutation on the shadow on **any** replica, and the
      replication queue either drained or demonstrably just busy rather than stuck (`exchange_and_wrap.sh` gates on
      this — see ["The replication-settle gate"](#the-replication-settle-gate); do **not** `--force` past it in
      production, and confirm on staging under live ingest that it does not abort on ordinary churn). The **final**
      deletion replay is not covered by this gate, which samples before that statement is issued; what covers it is
      `lightweight_deletes_sync = 2` in its own block, asserted by the driver.
- [ ] **`spanColumnsNonNullable = true` rolled out to every backend instance before the EXCHANGE** —
      `exchange_and_wrap.sh` enforces `--confirm-columns-non-nullable` on every EXCHANGE path, but that is an assertion;
      this item is the real "it is actually true on every instance" verification. Confirm it on the whole fleet by a
      **positive** check, not by the absence of ingestion errors: write an in-progress span (no
      `end_time`, and so no `ttft`) through the API and assert the epoch/NaN **sentinel** was stored for both — then
      repeat it as a read-back (`null`, not the sentinel) **after** the EXCHANGE, because until the swap the Nullable table
      answers `null` either way and the read-back proves nothing. A stale-`false` instance still writes
      correctly (`input_format_null_as_default` converts the `null` bind to the sentinel) and logs nothing — it just
      serves wrong absent-value reads/filters/sorts. Revert plan to `false` ready for rollback, **plus** the pre-swap
      sentinel/`duration` repair (see "Rolling back the `spanColumnsNonNullable` flip").
- [ ] **Schema-parity guards green** — `cutoverCopiesEveryBaseColumn` and `successorMaterializedColumnsMatchSource` pass
      on the release, so the cutover copies every base column of `spans` and the two tables' base and materialized
      columns match.
- [ ] **Fidelity verified** — `verify.sh` passes between source and destination before the EXCHANGE. This gate MUST be a
      **full compare** (`--sample-mod 1 --weeks-stride 1`, no `--from-week`/`--to-week` narrowing): it is the last backstop
      for a single-row deletion leak — an unexpected empty-`project_id` bridge event the single-branch replay would miss,
      or any other single-key divergence — and any sampling (`--sample-mod > 1`), week stride, or week narrowing can hash
      that one row out and still report `ok=1`. Reserve sampling/ranged runs for follow-up confidence *after* the full gate
      passes. Re-run `delta_replay.sh` then `verify.sh` until it PASSES. Read that PASS for what it is: writes never
      stop, so it certifies the copy **as of the last delta**, not that nothing arrived after it. The writes that
      arrive after it land in the tail gap described in "The final cutover window" and are covered by the
      reconciliation item, not by this one — do not quiesce traffic to force a cleaner PASS.
- [ ] **`Distributed` wrap gated on the DAO toggle — and deferred out of this window by decision.**
      `databaseAnalyticsDataModel.spansDistributedWrapEnabled` exists (OPIK-7799) and defaults to `false`, so
      `exchange_and_wrap.sh --with-wrap` would now succeed once it is flipped fleet-wide. Confirm it stays `false` and
      that stopping after the `EXCHANGE` is the intended resting state, not a fallback. A lightweight `DELETE` against
      a `Distributed` `spans` is unsupported (code 36), so any instance still reading `false` when the wrap lands
      breaks the span-delete cascade — and with no spans readiness probe, nothing reports it until a delete fails. To
      reopen this box, answer ["the readiness gap"](#the-readiness-gap-that-opik-7799-left-open) first.
- [ ] **No async-insert buffer change on any path (OPIK-8239).** The three `ANALYTICS_DB_ASYNC_INSERT_*` knobs stay as
      the deployment has them; nothing in this procedure raises one for the window. Confirm explicitly rather than by
      absence — the traces cutover's dependence on that widening degraded the whole service and was removed, and a
      spans cutover reaching for it would be reintroducing a known regression against a much larger table. The
      sanctioned mechanisms are OPIK-6896's `databaseAnalytics.cutoverBufferMaxMs` and OPIK-8255's permanent
      Distributed insert-queue settings, neither of which this runbook touches.
- [ ] **No ingestion-latency or query-p99 regression under representative load on prod-test, measured across the WHOLE
      tail** — not sampled at its start. On spans the tail includes a settle gate budgeted at up to 1800s and a deletion
      replay reading an unindexed `id` column over the whole source, so "the swap was fast" is not the claim being made.
- [ ] **No query-semantics regression** — FINAL / `LIMIT 1 BY` dedup verified; p99 on the project spans listing page within
      ±10% of the pre-migration baseline. **Check the `LIMIT 1 BY` tiebreakers specifically**: `SpanDAO`'s reads order
      by `(workspace_id, project_id, trace_id, parent_span_id, id)`, and migration 000115 records that the cutover has
      to swap them to the successor's key. They still target the live table today, so this is a read-path item to
      confirm rather than a cutover step — but it is the one place the narrower dedup key becomes visible to the
      product, so it belongs in the same window's verification.
- [ ] **Rollback rehearsed with traffic running**, the same way the forward direction is rehearsed: **stage A and stage
      B** end to end, plus `--reverse-replay-only` and `--sentinel-repair-only`, plus the reverse reconciliation
      (`--confirm-reimport-successor-writes`) and its usage-range precheck. Deletes during the post-cutover window do
      not resurrect after the reverse-replay; the parked table is retained for the soak. **Stage C and `--unwrap-only`
      are not rehearsed end to end** while the wrap is deferred — confirm instead that their topology guards refuse
      cleanly on an unwrapped estate, which is the state this window leaves behind.
- [ ] **`--with-wrap` / `--wrap-only` refuse without `--confirm-maintenance` AND without `--confirm-daos-retargeted`** —
      verified, and the second refusal READ: it names the readiness gap, which is the reason to stop rather than to
      go and set the flag.
- [ ] **Go/No-Go decision recorded** with the prod-test evidence attached — and for this cutover that means numbers, not
      ticks: the headroom margin, the chosen insert-block strategy with its measured peak memory, the four audit
      figures, the measured tail, and the revocation date for the migration user.
