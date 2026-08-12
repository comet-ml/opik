---
name: query-performance
description: Validate what a ClickHouse query actually costs before merging it — at production scale through read-only environment access, or on a revived Testcontainers dataset extrapolated to 20k/500k/1M entities. Use when a DAO query changes, when an endpoint is slow, or when a reviewer asks "what does this cost at scale".
---

# Query Performance Validation

Turn "this query looks expensive" into numbers a reviewer can act on. The output is a verdict per
clause — **change**, **keep**, or **caller's call** — each attached to a measurement, plus the
negative results so nobody retries them.

## Ground rules

1. **Measure, never infer.** Every claim needs a number that would differ if the claim were false.
2. **Equivalence gate first.** No variant's cost is quotable until you have ensured it returns the
   same result as the query it varies — same rows, same content, compared order-independently — on
   every shape you measure. A faster query that answers a different question is not an optimization.
   (Equivalence holds between the candidate and *its* variants. A candidate is often meant to change
   results versus `main`; `main` is the cost reference, not a result reference.)
3. **Collect the whole picture per run**: latency, peak memory, CPU time, and what was scanned (parts,
   granules, marks, rows read). The first three are what the system pays; the scan numbers are the
   evidence that explains *why* they moved. A variant can read fewer rows and still cost more memory,
   more CPU and the same wall time — so no single number decides anything on its own.
4. **Two data shapes minimum**, since rankings flip with density and skew. A win on one shape is a
   hypothesis.
5. **One variable per variant**, including deleting a clause outright to see if it earns its keep.
6. **≥5 runs; report p50, min and spread.** Differences smaller than the spread are not differences.
7. **Write down what you could not measure** — quotas, unreachable shapes, cache state. Those
   caveats bound the finding.

## Assumptions to verify rather than trust

The failure mode of this work is a confident mechanism the engine does not implement.

- A CTE referenced N times **may be evaluated N times**. Named CTEs are not materialized. Probe it:
  same query, one reference vs two, compare marks and peak memory.
- **Plan node count is not evaluation count.** Sets for `IN (SELECT …)` are often built eagerly and
  appear only as a literal (`x in 35297-element set`) with no read node — so counting
  `ReadFromMergeTree` nodes *undercounts* work, and a dominant subquery can be invisible.
- **`EXPLAIN` executes** scalar and set subqueries to resolve index conditions: it is neither free nor
  a timing proxy.
- Fewer rows read is not faster, and prune sets themselves cost memory.
- An index existing is not an index used — read granule counts, not the schema.
- A prune that pays on one shape can be dead weight on another, including one justified by a
  benchmark on synthetic data.
- Container data is not production shape (parts, versions per key, cardinality, array density).
- A read-only role can change the experiment: row-read caps abort long queries, and a restricted
  profile may refuse the settings your measurement needs.

## The loop

1. **Frame it.** Which endpoint, which call sites (one constant serving a list page and a by-id
   lookup behaves differently), how often called, what it is constrained on.
2. **Render the SQL** for each call site, from the candidate *and* from `main` — see `rendering.md`.
3. **Inventory the query before measuring it.** Read it and write down: the entity that drives it;
   every CTE and subquery with **how many times each is referenced**; which clauses are prunes (a
   clause you could delete without changing results) versus load-bearing; every dedup, set build,
   aggregation and join. This inventory is what tells you which probes are worth running — without
   it you will measure the query you assumed rather than the one you have.
4. **Get an environment**: read-only access to a real one, else revive Testcontainers and extrapolate
   — see `environments.md`.
5. **Baseline both**: the candidate and `main`, ≥5 runs each, plan captured per call site, shape
   recorded alongside. The delta between them is the cost of the change, and it is a finding in its
   own right — often the one that matters most.
6. **Locate the cost** — see `instrumentation.md`. Isolate the suspect subquery *and* measure
   end-to-end; a gap between them is itself a finding.
7. **Work the inventory, one variable at a time.** Take each clause from step 3 and ask what it costs
   and whether it earns its keep: prunes get ablated, repeated references get a reference-count probe,
   dedup and aggregation steps get an alternative formulation, expensive predicates get a cheaper one.
   For each: state the metric you expect to move, gate it, measure on both shapes and every call site,
   then keep or discard fast — and keep the number either way, including for what failed.

## Deciding: does the change stay or drop

Put the four measured dimensions side by side — **latency** (p50, min, spread), **peak memory**,
**CPU time**, **scanned/read** (parts, granules, marks, rows) — for every shape and every call site,
and decide from the whole set:

- **Stays** if it improves at least one dimension the call site is constrained on and regresses none of
  the others materially, on every shape and call site measured.
- **Drops** if any dimension regresses materially and nothing the call site cares about improves —
  including when the scan shrinks. Less scanned work bought with more memory or CPU is a trade, and on
  a shared cluster memory is usually the scarcer resource.
- **Caller's call** when dimensions genuinely conflict (better latency, worse memory) or when the
  ranking flips between shapes or call sites. Present both sets of numbers and name the trade rather
  than picking silently.
- **No change** if every difference is inside the run-to-run spread: keep the simpler form, and say the
  variant was measured and made no difference.

Make the scan numbers do their job: they should explain the latency, CPU and memory you measured. If
they do not — scan is flat but memory doubled, or rows fell but CPU rose — you have not found the
mechanism yet, and the verdict is not ready.

## Reporting

Whether you are reviewing a PR or opening one, justify every claim with a **before/after table** —
one row per variant (or per revision), one column per measured dimension:

| variant | p50 ms | CPU ms | peak MiB | rows read |
| --- | --- | --- | --- | --- |
| `main` | … | … | … | … |
| candidate (before) | … | … | … | … |
| with the change (after) | … | … | … | … |

One table per call site and per shape, with the shape named (entity counts, run count). A verdict
without its table is an opinion, and prose alone hides exactly the trade a reviewer needs to see.

Lead with what the change itself costs against `main`, per call site — a tuning delta of a few percent
does not outrank the endpoint getting materially more expensive, and if that cost is not acceptable,
say so and name the lever that would actually move it.

Then per clause: **change** (the replacement, why, before → after table), **keep** (what you tried and
the number that killed it), or **caller's call** (both options, trade named). Then the equivalence
evidence and the caveats. Do not include optimizations you did not measure, or a mechanism you did not
verify.

## Related

`opik-backend` (`clickhouse.md`, `testing.md`) — DAO and ClickHouse conventions, plus the
Testcontainers rules including "one ClickHouse-migrating test class per `mvn` invocation".
