# Changing a cutover table's schema during the cutover window

**Read this before writing any migration that touches `traces` or `spans`.**

Both physical layers are mid-migration to partitioned, sharding-ready successors. Until every install has cut over, one
migration file has to be correct against **two different physical layouts**, and the ways of getting it wrong are
silent — they raise nothing at migration time and surface as broken reads or lost data later.

This page was written for `traces` (OPIK-7772) and generalised when `spans` became the second family (OPIK-8377). The
rules are the same for both, because the two cutovers are the same cutover; where a family differs, it is called out.

CI enforces the mechanical rules on this page — schema parity across both topologies, and the shape of a guarded
migration. Get one of those wrong and `TracesSchemaParity*Test` / `SpansSchemaParity*Test` or the precondition lint will
fail your PR with a message pointing at the rule you missed.

**Two rules here are not machine-checked, and are yours to keep.** The
[freeze rule](#freeze-rule-no-cutover-table-schema-ddl-during-that-tables-cutover-soak) during a cutover soak, and the
[single-node precondition limitation](#known-limitation-the-guard-is-evaluated-on-one-node). Nothing in CI will stop you
shipping against either: the gates read a schema, and neither of those is visible in one.

---

## The two topologies

Read `<x>` as `traces` or `spans` throughout.

| | pre-cutover (fresh installs, and any not yet migrated) | post-cutover (installs that have run the runbook) |
|---|---|---|
| `<x>` | the live `ReplicatedReplacingMergeTree` | a **`Distributed`** wrapper — stores nothing |
| `<x>_local` | *does not exist* | the `MergeTree` shard that holds the data |
| `<x>_local_v2` | the empty successor the cutover will promote (the "shadow") | *renamed away by the cutover* |
| `<x>_pre_cutover_backup` | *does not exist* | the parked pre-cutover data, kept through the soak |

The cutover is performed by the operator runbook in
[`data-migrations/traces-local-v2-cutover`](../data-migrations/traces-local-v2-cutover/README.md) and
[`data-migrations/spans-local-v2-cutover`](../data-migrations/spans-local-v2-cutover/README.md), **not** by Liquibase. So the changelog has no idea which topology it is running against, and
the two states coexist across the fleet for months: installs cut over on their own cadence, and fresh ones still
start pre-cutover. The two families cut over **independently**, so an install can be
post-cutover for traces and pre-cutover for spans; a guard must interrogate its own family's shard and no other.

## The invariant

> **Every physical table of a cutover family stays schema-consistent, for any change.**

Concretely:

* **pre-cutover** — `<x>` and the `<x>_local_v2` shadow carry the same read-facing columns and the same
  storage-only attributes, and the cutover backfill's column list carries every column that must survive the copy;
* **post-cutover** — the `Distributed` `<x>` wrapper exposes exactly the columns its `<x>_local` shard holds.

## Why the failure modes are silent

Two facts, both measured rather than assumed (they are pinned by the gates, and were originally established by the
OPIK-7772 spike):

1. **A shard-only `ADD COLUMN` is not readable through the `Distributed` wrapper.** The `ALTER` succeeds. Nothing logs a
   warning. The column is then unresolvable on any read through `<x>` (ClickHouse code 47), so the feature that added
   it is broken on every cut-over install while the migration and all its tests stay green.
2. **A migration that alters `<x>` but forgets the shadow also passes.** The shadow is empty and nothing reads it, so
   the mismatch stays invisible until the cutover copies into it — at which point the column is missing from the
   successor, or the backfill fails in the operator's hands.

Neither is caught by "the migration applied without error". That is why the guard exists.

**The asymmetry is the point.** Row mutations and `MATERIALIZE COLUMN` / `ADD INDEX` / `MODIFY TTL` target the local
table only — the wrapper rejects them with code 36/48, so a slip **fails loudly**. But `ADD` / `DROP` / `MODIFY COLUMN`
must be applied to **both**: the wrapper accepts them as metadata-only, and targeting only the local table leaves the
wrapper without the column. CI is what catches the silent class.

---

## Where a change lands

**The general rule, from which the specific cases follow:**

| kind of change | pre-cutover | post-cutover |
|---|---|---|
| changes the **read-facing column list** (a column, including `MATERIALIZED` / `ALIAS`) | `<x>` **and** `<x>_local_v2` | `<x>_local` **and** the `<x>` wrapper |
| **storage-only** (skip index, codec, TTL, projection) | `<x>` **and** `<x>_local_v2` | `<x>_local` **only** |

The asymmetry is the whole point: the `Distributed` wrapper *resolves column names* but *stores no data*. It therefore
needs every column and can accept none of the storage attributes.

Plus one obligation no table-to-table comparison can infer:

> A **preserved** (non-derived) column must also be added to the cutover backfill's explicit column list
> ([traces](../data-migrations/traces-local-v2-cutover/scripts/db-app-analytics/000001_backfill_traces_local_v2.sql),
> [spans](../data-migrations/spans-local-v2-cutover/scripts/db-app-analytics/000001_backfill_spans_local_v2.sql)).
> Otherwise the cutover copies the column as its default and the data is silently lost.
>
> A **derived** (`MATERIALIZED` **or** `ALIAS`) column must **not** be added there — the destination computes it, and
> naming either kind in an `INSERT` column list is an error. This is the same pair the read-facing rule above names, and
> the parity gate classifies them together: both are excluded from the insertable column set.

**Adding the name to the backfill list is not always enough.** `INSERT ... SELECT` copies by position and converts by
assignment, so a name-for-name copy only works when the source value is *representable* in the destination column. CI
checks that the names line up; it cannot tell you the values survive. Two cases need an explicit conversion in the
`SELECT`, not just an entry in the column list:

| Source → destination | What a bare copy does | What to write |
|---|---|---|
| `Nullable(T)` → non-nullable `T` | fails on the first NULL row | `coalesce(col, <sentinel>) AS col` — as `end_time` and `ttft` already do |
| narrowing precision or width (`DateTime64(9)` → `DateTime64(6)`, `Int64` → `Int32`, a shorter `FixedString`) | silently truncates or overflows | convert deliberately, and confirm the loss is intended |

Each successor is *already* narrower than its live table in these ways — microsecond rather than nanosecond timestamps,
sentinels rather than `Nullable`, and on spans a `FixedString(36)` `parent_span_id` in place of a `String` — which is
exactly why the shipped backfill carries `coalesce(...)` wrappers instead of bare column names. A new preserved column
whose type differs between the two tables needs the same treatment.

If a change would need a conversion that loses data, that is a design decision rather than a migration detail: raise it
instead of encoding it in a `SELECT`.

## The pattern

Ship the change as **two complementary changesets** guarded on the same runtime fact — whether `<x>_local` exists —
so exactly one branch executes and the other is recorded `MARK_RAN`.

The working reference for each family, with both branches and both playbook cases, is
[`traces-ddl-reference`](../src/test/resources/liquibase/traces-ddl-reference/migrations/reference_topology_aware_change.sql)
and
[`spans-ddl-reference`](../src/test/resources/liquibase/spans-ddl-reference/migrations/reference_topology_aware_change.sql).
They are test fixtures rather than shipped migrations, so proving the pattern does not add DDL every install must run;
copy the **shape** into `src/main/resources/liquibase/db-app-analytics/migrations/` under the usual `NNNNNN_` name.

```sql
--changeset opik:000123_add_foo_to_spans_pre_cutover
--comment: Pre-cutover branch — spans is the live MergeTree and spans_local_v2 is the shadow; apply to both
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'spans_local'
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans          ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local_v2 ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

--changeset opik:000123_add_foo_to_spans_post_cutover
--comment: Post-cutover branch — spans is the Distributed wrapper over spans_local
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'spans_local'
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans_local    ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans          ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
```

Every one of these details is load-bearing:

* **`sqlCheck` against `system.tables`, not `tableExists`.** The guard has to read the *runtime* topology. Liquibase's
  own bookkeeping cannot tell you whether the operator ran the cutover.
* **The check names your own family's shard.** The two families cut over independently, so a spans migration keyed on
  `traces_local` selects its branch from an unrelated fact. The lint rejects it.
* **`onFail:MARK_RAN`.** The skipped branch is recorded as applied without executing, so a later startup never retries it
  against the wrong topology. (`liquibase-clickhouse` 0.7.2 honours this; the gates assert it, so a version bump that
  broke it would fail CI rather than production.)
* **`onError:HALT`.** If the precondition itself cannot be evaluated, stop — do not guess a topology.
* **`ON CLUSTER '{cluster}'` on every statement.** Without it the DDL reaches only the node Liquibase connected to,
  leaving the other replicas short while the changeset is recorded as applied. It compounds here: the guard is
  evaluated from a *local* `system.tables` read (see the known limitation below), so a cluster left divergent by
  non-cluster DDL can have one node record `MARK_RAN` for a topology the others are not in.
* **`IF [NOT] EXISTS` everywhere.** Makes a re-run, a partially-applied branch, or an install arriving from either side
  idempotent.

### One family per changeset

**A changeset may mutate `traces` tables or `spans` tables, never both.** Historically they were combined —
`000008_add_error_info_to_spans_and_traces`, `000055_add_duration_column_to_traces_and_spans` and
`000084_add_environment_to_traces_spans_threads` each alter both in a single changeset — and that was correct before the
cutover existed. It cannot be now.

The two families cut over independently, so there are **four** topology combinations, not two, and a changeset carries
one branch condition. Writing one `sqlCheck` per family on the same changeset does not solve it: Liquibase **ANDs**
multiple preconditions, so on any install where the families are at different stages *both* branches evaluate false,
both are recorded `MARK_RAN`, and the change is silently never applied while the ledger says it was.

Split it instead into a guarded pre/post pair **per family**, each keyed on its own shard — four changesets, each
correct on its own two topologies:

```sql
--changeset opik:000123_add_foo_to_traces_pre_cutover      -- expectedResult:0 ... name = 'traces_local'
--changeset opik:000123_add_foo_to_traces_post_cutover     -- expectedResult:1 ... name = 'traces_local'
--changeset opik:000123_add_foo_to_spans_pre_cutover       -- expectedResult:0 ... name = 'spans_local'
--changeset opik:000123_add_foo_to_spans_post_cutover      -- expectedResult:1 ... name = 'spans_local'
```

Both families' lints reject a changeset that mutates both, and both accept the split above.

### Case 1 — a field

Read-facing. Both branches; both tables in each branch. If it is preserved rather than derived, add it to the backfill
column list too.

### Case 2 — an index

Storage-only. Pre-cutover both tables; post-cutover **the shard only** — do not attempt it on the wrapper, which has no
data to index.

### Rare: structural changes

`ORDER BY`, `PRIMARY KEY` and `PARTITION BY` are **immutable** on `MergeTree`. They cannot be `ALTER`ed at all; changing
one requires recreating the table and copying the data.

**Do not attempt a structural change during the mixed-fleet window.** It rides the successor table's definition (as the
weekly partition key did in `000114`, and as dropping `parent_span_id` from the spans sort key did in `000115`), not an
in-window `ALTER`. If you believe you need one, that is a design conversation, not a migration.

The lint enforces this rather than leaving it to discipline: a changeset that `DROP`s, `RENAME`s, `TRUNCATE`s,
`EXCHANGE`s, `OPTIMIZE`s or `DELETE`s from one of these tables is rejected outright, guarded or not. A guard would not
save it — post-cutover the `Distributed` wrapper rejects row mutations anyway, so the post-cutover branch would fail on
every cut-over install.

The invariant above still holds for structural changes, and the gates still enforce it — they compare the sorting and
primary keys regardless of how a change was made.

### Known limitation: the guard is evaluated on one node

Liquibase evaluates the `sqlCheck` on the single JDBC connection it holds, against that server's own
`system.tables`, and only then submits the `ALTER ... ON CLUSTER`. So the branch is selected from **one host's** view of
the topology. If replicas are transiently skewed — mid-cutover, or with a replica catching up — one host can select a
branch and have the complementary changeset recorded `MARK_RAN`, leaving the other hosts permanently short of the change
with a ledger that says otherwise.

Three things bound this in practice, and none of them eliminate it:

* the cutover's `EXCHANGE` + wrap is itself `ON CLUSTER`, so `<x>_local` appears cluster-wide rather than per node;
* `exchange_and_wrap.sh` gates on replication settling before it proceeds;
* the freeze rule below keeps schema DDL out of the window where skew is most likely.

The candidate hardening is to evaluate the precondition over `clusterAllReplicas` instead of the local `system.tables`
and fail on a partial answer. That is **not decided** — it changes the shipped pattern, and the failure semantics of a
precondition that errors mid-cluster need thinking through before it becomes the rule. Until then: do not ship schema
DDL for a cutover table against a cluster you have not confirmed is settled.

### Freeze rule: no cutover-table schema DDL during that table's cutover soak

While an install is between the `EXCHANGE` and the end of its soak (the window in which
`<x>_pre_cutover_backup` is still retained and a rollback is still on the table), **do not ship schema DDL for that
table**.

A rollback promotes the parked pre-cutover table back to `<x>`. Any DDL applied only to the successor during the soak
is lost by that rollback, while its changeset stays recorded as applied — so the ledger claims a column exists that does
not, and no later migration will add it. Land such changes before the cutover starts or after the soak closes.

The freeze is **per family**: a traces soak does not freeze spans DDL, and vice versa.

---

## What CI checks, and how to read a failure

| gate | what it asserts |
|---|---|
| `TracesSchemaParityPreCutoverTest` / `SpansSchemaParityPreCutoverTest` | applies the real changelog as a fresh install does, then asserts three-way parity: `<x>` ≅ the `<x>_local_v2` shadow ≅ the backfill column list |
| `TracesSchemaParityPostCutoverTest` / `SpansSchemaParityPostCutoverTest` | stops the changelog after the family's splice point, splices in the runbook's `EXCHANGE` + wrap, resumes — so **your** migration runs on the post-cutover topology — then asserts the wrapper exposes exactly the shard's columns |
| `TracesMigrationPreconditionLintTest` / `SpansMigrationPreconditionLintTest` | a fast, container-free check that a migration mutating the family's tables **strictly after** its splice point carries the guard **on the mutating changeset itself**, keyed on **its own** shard, ships **both** complementary branches applying the **same** change, does not mutate the other family in the same changeset, and uses no destructive statement at all |
| `Trace/SpanMutationRoutingArchTest` and `Trace/SpanMutationSqlRoutingTest` | runtime DAO mutations resolve their table through `TraceDAOImpl#tracesMutationTable()` / `SpanDAO#selectSpansMutationTable` and never name a physical table directly |

The splice point is the last migration that shapes the family's shadow table, and is declared once, on
`CutoverMigrationPreconditionLint`: `000114_recreate_traces_local_v2_id_at_datetime64.sql` for traces,
`000116_apply_spans_local_v2_real_data_codec_refinements.sql` for spans. Both gates and the lint read it from there, so
the boundary cannot drift between them.

Every gate above is **one implementation with one constant per family** — `CutoverSchemaParity`,
`CutoverDdlReferenceFixture`, `CutoverMigrationPreconditionLint`, and `MutationSql` for the routing pair. A third
cutover family adds a constant, not a copy.

Each gate also carries negative tests that inject the drift a careless migration produces, so no assertion can quietly
stop firing.

**What CI does not check.** All of the above compares *schema* — names, types, and the select/expression definitions
built on them. None of it moves a row, so none of it can tell you a conversion is lossless. In particular, adding a
column to a family's `baselineTypeDifferences` exempts it from type parity and nothing then validates that the cutover's
conversion preserves its values. That is deliberate — value fidelity is the `*LocalV2CutoverTest` suites' job — but it
means an allowlist entry is a **decision, not a formality**: it asserts that you have checked the conversion is safe,
or that the loss is intended. Say which, in the entry's reason.

Common failures:

* *"read-facing column parity"* — you altered one table and not the other. Add the missing `ALTER`.
* *"cutover backfill parity"* — you added a preserved column without adding it to the backfill column list.
* *"wrapper column parity"* / a column that is not readable — your post-cutover branch altered the shard but not the
  wrapper.
* *"skip-index parity"* — you added an index to `<x>` but not to the shadow.
* *"allowlisted column ... must still be exactly the documented type"* — a column the baseline excuses has drifted on one
  side, or the two have converged and the entry is now dead. Either fix the change or delete the entry.
* *"mutates both ... tables"* — one changeset touches both families. Split it into a guarded pair per family, as
  [One family per changeset](#one-family-per-changeset) shows.
* *"No migration may do that during the mixed-fleet window"* — your changeset drops, renames, truncates, exchanges,
  optimizes or deletes from a cutover table. A guard would not save it; see
  [Rare: structural changes](#rare-structural-changes).
* *"must apply the same change"* — your two branches name different columns or indices. Which tables each branch targets
  differs by design; which objects they name must not, or installs end up split by the order in which they migrated and
  cut over. No parity gate can catch this, because each only ever runs one branch.
* The **lint** failing otherwise means your new migration mutates a cutover table with no precondition guard at all, or
  with one keyed on the wrong family's shard — start from the pattern above.

### Per-family baselines, and why spans has more of them

The successors were designed rather than copied, so each differs from its live table in ways a schema-change guard must
not flag. Those differences are **enumerated one by one** on `CutoverSchemaParity`, with a reason each, and pinned on
*both* sides — so a column that drifts away from its documented type fails even though it still "differs".

Spans carries three kinds traces does not:

* **more type differences** — eleven of thirty-eight shared columns rather than six of thirty-one. The extra five are
  `parent_span_id` (`String` → `FixedString(36)`), `usage` (`Map(String, Int32)` → `Map(String, Int64)`), and the three
  `LowCardinality(String)` conversions (`model`, `provider`, `total_estimated_cost_version`).
* **a sorting-key difference** — the successor drops `parent_span_id` from the key (OPIK-7750). Pinned on both sides as
  `baselineKeyDifference` rather than compared for equality.
* **a live-only index** — `idx_spans_id` has no same-named counterpart, having become the
  `idx_spans_id_minmax` / `idx_spans_id_bf` pair on the successor. Pinned as `liveOnlySkipIndices`, and asserted to stay
  current in both directions so the exemption cannot rot into a blanket one.

Adding to any of these lists is a decision. Each entry carries the reason it exists, and the gate deletes nothing for
you — an entry whose difference has gone must be removed by hand, which is what stops the lists growing into blanket
tolerance.

## Append-only

Shipped migrations are **never edited** — not to fix them, not to add a precondition to one that predates the cutover.
Every change is a new, appended migration. The migrations that mutate `traces` or `spans` unguarded (`000091`,
`000097`, `000105`, `000113`, …) predate the cutover and are correct for the installs that ran them; each family's lint
deliberately applies only from its splice point onward for exactly this reason.

## Open decision (deferred)

**Do fresh and open-source installs converge on the post-cutover topology?** Today a fresh install starts pre-cutover
and stays there until an operator runs the runbook, which means the pre-cutover branch of every guarded migration is
load-bearing indefinitely and the mixed fleet never fully closes. The alternative — having fresh installs create the
end-state (`<x>_local` + wrapper) directly — would let the pre-cutover branches eventually be retired, at the cost of
a greenfield path that differs from the migrated one.

This is **not decided**. Until it is, assume both topologies are permanent and write every migration on these tables
with both branches. Related: greenfield end-state creation and cutover-time shadow derivation are tracked separately
from OPIK-7772.

## References

* Cutover runbooks and their reference SQL:
  [traces](../data-migrations/traces-local-v2-cutover/README.md),
  [spans](../data-migrations/spans-local-v2-cutover/README.md)
* Reference migrations (both branches):
  [traces](../src/test/resources/liquibase/traces-ddl-reference/migrations/reference_topology_aware_change.sql),
  [spans](../src/test/resources/liquibase/spans-ddl-reference/migrations/reference_topology_aware_change.sql)
* Negative controls (the mistake this prevents):
  [traces](../src/test/resources/liquibase/traces-ddl-unguarded/migrations/unguarded_traces_change.sql),
  [spans](../src/test/resources/liquibase/spans-ddl-unguarded/migrations/unguarded_spans_change.sql)
* Runtime mutation routing: `TraceDAOImpl#tracesMutationTable()`, `SpanDAO#selectSpansMutationTable` and
  `DatabaseAnalyticsDataModelConfig`
* Cutover data-correctness gate (a different concern from this page): `TracesLocalV2CutoverTest`
