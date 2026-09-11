# Changing the `traces` schema during the cutover window

**Read this before writing any migration that touches `traces`.**

The `traces` physical layer is mid-migration to a partitioned, sharding-ready successor. Until every install has
cut over, one migration file has to be correct against **two different physical layouts**, and the ways of getting it
wrong are silent — they raise nothing at migration time and surface as broken reads or lost data later.

CI enforces everything on this page. If you follow the playbook you will not need to think about it again; if you do not,
`TracesSchemaParityPreCutoverTest` / `TracesSchemaParityPostCutoverTest` will fail your PR with a message pointing at the
specific rule you missed.

---

## The two topologies

| | pre-cutover (fresh installs, most self-hosted) | post-cutover (SaaS, and self-hosted as they migrate) |
|---|---|---|
| `traces` | the live `ReplicatedReplacingMergeTree` | a **`Distributed`** wrapper — stores nothing |
| `traces_local` | *does not exist* | the `MergeTree` shard that holds the data |
| `traces_local_v2` | the empty successor the cutover will promote (the "shadow") | *renamed away by the cutover* |
| `traces_pre_cutover_backup` | *does not exist* | the parked pre-cutover data, kept through the soak |

The cutover is performed by the operator runbook in
[`data-migrations/traces-local-v2-cutover`](../data-migrations/traces-local-v2-cutover/README.md), **not** by Liquibase.
So the changelog has no idea which topology it is running against, and the two states coexist across the fleet for
months: Opik SaaS cuts over first, self-hosted installs on their own cadence, and fresh installs still start
pre-cutover.

## The invariant

> **Every trace physical table stays schema-consistent, for any change.**

Concretely:

* **pre-cutover** — `traces` and the `traces_local_v2` shadow carry the same read-facing columns and the same
  storage-only attributes, and the cutover backfill's column list carries every column that must survive the copy;
* **post-cutover** — the `Distributed` `traces` wrapper exposes exactly the columns its `traces_local` shard holds.

## Why the failure modes are silent

Two facts, both measured rather than assumed (they are pinned by the gates, and were originally established by the
OPIK-7772 spike):

1. **A shard-only `ADD COLUMN` is not readable through the `Distributed` wrapper.** The `ALTER` succeeds. Nothing logs a
   warning. The column is then unresolvable on any read through `traces` (ClickHouse code 47), so the feature that added
   it is broken on every cut-over install while the migration and all its tests stay green.
2. **A migration that alters `traces` but forgets the shadow also passes.** The shadow is empty and nothing reads it, so
   the mismatch stays invisible until the cutover copies into it — at which point the column is missing from the
   successor, or the backfill fails in the operator's hands.

Neither is caught by "the migration applied without error". That is why the guard exists.

---

## Where a change lands

**The general rule, from which the specific cases follow:**

| kind of change | pre-cutover | post-cutover |
|---|---|---|
| changes the **read-facing column list** (a column, including `MATERIALIZED` / `ALIAS`) | `traces` **and** `traces_local_v2` | `traces_local` **and** the `traces` wrapper |
| **storage-only** (skip index, codec, TTL, projection) | `traces` **and** `traces_local_v2` | `traces_local` **only** |

The asymmetry is the whole point: the `Distributed` wrapper *resolves column names* but *stores no data*. It therefore
needs every column and can accept none of the storage attributes.

Plus one obligation no table-to-table comparison can infer:

> A **preserved** (non-derived) column must also be added to the cutover backfill's explicit column list in
> [`000001_backfill_traces_local_v2.sql`](../data-migrations/traces-local-v2-cutover/scripts/db-app-analytics/000001_backfill_traces_local_v2.sql).
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

The successor is *already* narrower than `traces` in both of these ways — microsecond rather than nanosecond timestamps,
sentinels rather than `Nullable` — which is exactly why the shipped backfill carries `coalesce(...)` wrappers instead of
bare column names. A new preserved column whose type differs between the two tables needs the same treatment.

If a change would need a conversion that loses data, that is a design decision rather than a migration detail: raise it
instead of encoding it in a `SELECT`.

## The pattern

Ship the change as **two complementary changesets** guarded on the same runtime fact — whether `traces_local` exists —
so exactly one branch executes and the other is recorded `MARK_RAN`.

The working reference, with both branches and both playbook cases, is
[`reference_topology_aware_change.sql`](../src/test/resources/liquibase/traces-ddl-reference/migrations/reference_topology_aware_change.sql).
It is a test fixture rather than a shipped migration, so proving the pattern does not add DDL every install must run;
copy its **shape** into `src/main/resources/liquibase/db-app-analytics/migrations/` under the usual `NNNNNN_` name.

```sql
--changeset opik:000123_add_foo_to_traces_pre_cutover
--comment: Pre-cutover branch — traces is the live MergeTree and traces_local_v2 is the shadow; apply to both
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces          ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local_v2 ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';

--changeset opik:000123_add_foo_to_traces_post_cutover
--comment: Post-cutover branch — traces is the Distributed wrapper over traces_local
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT count() FROM system.tables WHERE database = '${ANALYTICS_DB_DATABASE_NAME}' AND name = 'traces_local'
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces_local    ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces          ON CLUSTER '{cluster}' ADD COLUMN IF NOT EXISTS foo String DEFAULT '';
```

Four details are load-bearing:

* **`sqlCheck` against `system.tables`, not `tableExists`.** The guard has to read the *runtime* topology. Liquibase's
  own bookkeeping cannot tell you whether the operator ran the cutover.
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
weekly partition key did in `000114`), not an in-window `ALTER`. If you believe you need one, that is a design
conversation, not a migration.

The invariant above still holds for structural changes, and the gates still enforce it — they compare the sorting and
primary keys regardless of how a change was made.

### Known limitation: the guard is evaluated on one node

Liquibase evaluates the `sqlCheck` on the single JDBC connection it holds, against that server's own
`system.tables`, and only then submits the `ALTER ... ON CLUSTER`. So the branch is selected from **one host's** view of
the topology. If replicas are transiently skewed — mid-cutover, or with a replica catching up — one host can select a
branch and have the complementary changeset recorded `MARK_RAN`, leaving the other hosts permanently short of the change
with a ledger that says otherwise.

Three things bound this in practice, and none of them eliminate it:

* the cutover's `EXCHANGE` + wrap is itself `ON CLUSTER`, so `traces_local` appears cluster-wide rather than per node;
* `exchange_and_wrap.sh` gates on replication settling before it proceeds;
* the freeze rule below keeps schema DDL out of the window where skew is most likely.

The candidate hardening is to evaluate the precondition over `clusterAllReplicas` instead of the local `system.tables`
and fail on a partial answer. That is **not decided** — it changes the shipped pattern, and the failure semantics of a
precondition that errors mid-cluster need thinking through before it becomes the rule. Until then: do not ship trace
schema DDL against a cluster you have not confirmed is settled.

### Freeze rule: no trace schema DDL during a cutover soak

While an install is between the `EXCHANGE` and the end of its soak (the window in which
`traces_pre_cutover_backup` is still retained and a rollback is still on the table), **do not ship trace schema DDL**.

A rollback promotes the parked pre-cutover table back to `traces`. Any DDL applied only to the successor during the soak
is lost by that rollback, while its changeset stays recorded as applied — so the ledger claims a column exists that does
not, and no later migration will add it. Land trace schema changes before the cutover starts or after the soak closes.

---

## What CI checks, and how to read a failure

| gate | what it asserts |
|---|---|
| `TracesSchemaParityPreCutoverTest` | applies the real changelog as a fresh install does, then asserts three-way parity: `traces` ≅ the `traces_local_v2` shadow ≅ the backfill column list |
| `TracesSchemaParityPostCutoverTest` | stops the changelog after `000114`, splices in the runbook's `EXCHANGE` + wrap, resumes — so **your** migration runs on the post-cutover topology — then asserts the wrapper exposes exactly the shard's columns |
| `TracesMigrationPreconditionLintTest` | a fast, container-free check that a `traces`-mutating migration added **strictly after** `000114` carries the guard **on the mutating changeset itself**, and ships **both** complementary branches |
| `TraceMutationRoutingArchTest` / `TraceMutationSqlRoutingTest` | runtime DAO mutations resolve their table through `TraceDAOImpl#tracesMutationTable()` and never name `traces` / `traces_local` directly |

Each gate also carries negative tests that inject the drift a careless migration produces, so no assertion can quietly
stop firing.

**What CI does not check.** All of the above compares *schema* — names, types, and the select/expression definitions
built on them. None of it moves a row, so none of it can tell you a conversion is lossless. In particular, adding a
column to `BASELINE_TYPE_DIFFERENCES` exempts it from type parity and nothing then validates that the cutover's
conversion preserves its values. That is deliberate — value fidelity is `TracesLocalV2CutoverTest`'s job, and the
full-volume rehearsal the QA gate's — but it means an allowlist entry is a **decision, not a formality**: it asserts
that you have checked the conversion is safe or that the loss is intended. Say which, in the entry's reason.

Common failures:

* *"read-facing column parity"* — you altered one table and not the other. Add the missing `ALTER`.
* *"cutover backfill parity"* — you added a preserved column without adding it to the backfill column list.
* *"wrapper column parity"* / a column that is not readable — your post-cutover branch altered the shard but not the
  wrapper.
* *"skip-index parity"* — you added an index to `traces` but not to the shadow.
* The **lint** failing means your new migration mutates `traces` with no precondition guard at all — start from the
  pattern above.

## Append-only

Shipped migrations are **never edited** — not to fix them, not to add a precondition to one that predates the cutover.
Every change is a new, appended migration. The migrations that mutate `traces` unguarded (`000091`, `000113`, …) predate
the cutover and are correct for the installs that ran them; the lint deliberately applies only from `000114` onward for
exactly this reason.

## Open decision (deferred)

**Do fresh and open-source installs converge on the post-cutover topology?** Today a fresh install starts pre-cutover
and stays there until an operator runs the runbook, which means the pre-cutover branch of every guarded migration is
load-bearing indefinitely and the mixed fleet never fully closes. The alternative — having fresh installs create the
end-state (`traces_local` + wrapper) directly — would let the pre-cutover branches eventually be retired, at the cost of
a greenfield path that differs from the migrated one.

This is **not decided**. Until it is, assume both topologies are permanent and write every trace migration with both
branches. Related: greenfield end-state creation and cutover-time shadow derivation are tracked separately from
OPIK-7772.

## References

* Cutover runbook and its reference SQL: [`data-migrations/traces-local-v2-cutover`](../data-migrations/traces-local-v2-cutover/README.md)
* Reference migration (both branches): [`reference_topology_aware_change.sql`](../src/test/resources/liquibase/traces-ddl-reference/migrations/reference_topology_aware_change.sql)
* Negative control (the mistake this prevents): [`unguarded_traces_change.sql`](../src/test/resources/liquibase/traces-ddl-unguarded/migrations/unguarded_traces_change.sql)
* Runtime mutation routing: `TraceDAOImpl#tracesMutationTable()` and `DatabaseAnalyticsDataModelConfig`
* Cutover data-correctness gate (a different concern from this page): `TracesLocalV2CutoverTest`
