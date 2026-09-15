# Collecting the Evidence

## Make runs comparable first

Tag every run so runs of different variants can be aggregated afterwards from `system.query_log`
(`log_comment` is the hook). Disable result caching. Compare warm to warm — you can only drop caches
in a container. Pin the thread count when comparing CPU efficiency, since wall time alone cannot
separate "less work" from "more parallelism". Aggregate across replicas if the endpoint is
load-balanced, or runs land on different hosts and the spread swamps the effect.

## What to read, and what each answers

**`EXPLAIN indexes = 1`** — the pruning verdict, and the first thing to look at. Per read it gives
parts and granules selected against the table total (the ratio is how well pruning worked), which
conditions reached the index, the search algorithm — `binary search` means a primary-key prefix was
hit, `generic exclusion search` means it was not — and which skip indexes ran, where one that
eliminates no granules is dead weight rather than protection.

**`EXPLAIN PLAN actions = 1`** — what each step actually does, and where filters, aggregation and
joins sit relative to each other. Use it when the cost is not in reading but in what happens after.

**`EXPLAIN PIPELINE`** — the executing shape: node multiplicity and concurrency, and where a stream
narrows to a single thread.

**`EXPLAIN ESTIMATE`** — the engine's own row/mark estimate, worth comparing against what actually
happened.

**`EXPLAIN QUERY TREE` / `SYNTAX`** — how the analyzer rewrote the query, including what it did with
your CTEs and predicates. Use it when a rewrite is not what you assumed.

**`system.query_log`** — the cost record per run: duration (aggregate it as p50, p90 and p95 across
runs, plus min, since the tail is what a polled endpoint feels), peak memory (first-class, not a
footnote), rows read, and `ProfileEvents`. Worth knowing by name: `SelectedParts`/`SelectedRanges`/`SelectedMarks`
for pruning, user and system CPU time, and any non-zero `External*` event — spilling means memory
pressure changed the algorithm, so the fix is memory, not wall time. Explore the rest of the event map
when a number needs explaining.

**Executor logs** (`send_logs_level = 'trace'`) — what ran, in order: key conditions, parts and marks
selected, set creation, aggregation method, rows in and out per stage. This is what exposes eagerly
built sets and repeated evaluation, which the plan hides. `system.text_log` holds the same lines but is
often disabled outside containers.

**`system.trace_log`** — where CPU goes when the cost is per-row expression work (array lambdas, JSON,
string and UUID conversion, hashing). Needs the query profiler and introspection enabled, so usually
container-only.

**`system.parts`, `parts_columns`, `data_skipping_indices`** — the storage side: part counts behind
parts scanned, column sizes behind read cost, and which indexes exist, which is not the same as whether
they are used.

## Probes

Each isolates one variable and yields a number rather than an opinion.

- **Equivalence gate** — before quoting any variant's cost, ensure it returns the same result as the
  baseline: same rows and same content, compared order-independently across all columns, on every
  shape you measure.
- **Isolate the suspect** — measure the suspect subquery alone and end-to-end. A large gap between the
  two means something structural: repeated evaluation, a set built more than once, an unexpected join
  order.
- **Reference-count probe** — identical query except a subquery is referenced once versus twice.
  Doubled marks, rows and peak memory mean it is evaluated twice, whatever the plan shows.
- **Ablation** — delete one clause. If results are unchanged it is a prune, and the measurement says
  whether it earns its keep *on this shape*. If results change it is load-bearing and cannot be traded.
- **Shape and call-site sweeps** — the same variant across data shapes and across rendered call sites.
  Rankings that flip are the finding, not an inconvenience.

## Symptom to suspect

| Observation | Look at |
| --- | --- |
| Granules read ≈ table total | Condition not on a primary-key prefix, non-monotonic expression around the key, or no prune at all |
| Many parts scanned | Partition pruning, or part count itself (merges, insert batching) |
| Marks high, rows returned low | Missing or ineffective skip index; predicate applied too late |
| Rows fine, memory high | Set or join build side, wide `GROUP BY`/`DISTINCT` projection, sort before limit |
| Non-zero `External*` | Spilling under memory pressure |
| CPU ≫ wall time, rows modest | Per-row expression cost |
| Same rows, different wall time | Set building, join build order, thread contention — pin threads and re-measure |
| Isolated subquery cheap, end-to-end expensive | Repeated evaluation or per-reference materialisation |
| Dedup/aggregation dominates | Rows per logical key |
| Runs vary more than variants | No result yet — more runs, report spread |
