# Getting an Environment That Can Answer the Question

A query's cost is a property of the data it runs on. Prefer read-only access to a real environment
(real part counts, skew, cardinalities); otherwise revive Testcontainers, which is the only way to
measure a scale that does not exist yet. Record which one you used with every number.

## Option 1 — a real environment, read-only

Read-only means read-only: no `INSERT`, no `OPTIMIZE`, no `SYSTEM`, no cache clearing. If the
measurement needs those, it belongs in a container.

Choose shapes deliberately — the largest instance of the entity the query is driven by, plus one that
differs in density — and watch for row-read or memory quotas: a query that aborts at a cap has not
been measured, and if the baseline aborts too, that is itself the finding. Keep identifiers (ids,
hostnames, database and role names) out of the writeup; report shapes as sizes.

## Option 2 — revive the Testcontainers instance

The backend's integration tests already build a schema-correct ClickHouse with real fixture data, but
it dies with the JVM. The flow:

1. **Keep the container alive.** `apps/opik-backend/src/test/java/com/comet/opik/api/resources/utils/ClickHouseContainerUtils.java`
   registers a shutdown hook in its static block that stops every container and closes the network —
   comment it out. Container reuse is already requested in code (`withReuse(true)`), but also needs
   `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`. Both are local-only; revert
   the Java file before committing.
2. **Run the narrowest test that exercises the query** — one ClickHouse-migrating test class per `mvn`
   invocation, or the second migration fails with `REPLICA_ALREADY_EXISTS` (see
   `opik-backend/testing.md`). Its fixtures are your distribution sample; if no test covers the query,
   write the smallest one that does.
3. **Attach to the surviving container** on its mapped native port (it changes per run) as `default`,
   database `opik` (`ClickHouseContainerUtils.DATABASE_NAME`). Being admin here unlocks what a real
   environment refuses: log flushing, cache drops, stopping merges, and `INSERT`. The container also
   provisions the production-shape read-only user from `src/test/resources/users.xml`, so quota and
   `readonly` behaviour can be reproduced.
4. **Extrapolate to 20k / 500k / 1M** driving entities, each scale in **its own workspace id** so the
   ladders coexist and one rendering can be pointed at any of them. Derive the parameters from the
   fixtures rather than inventing them.
5. **Validate the shape before trusting a number**: compare the container's `EXPLAIN indexes = 1`
   against a real environment's — search algorithm, which conditions reached the index,
   granule-to-total ratios. Timings will differ; the plan shape must not. If it does, fix the seeding.

**What the extrapolation must preserve**, because each of these moves the plan: fanout (child rows per
entity); rows per logical key, which is what dedup steps pay for; part count; cardinality of the
columns used as prunes; skew, so the worst-case entity exists; array and string density; and insert
order relative to the primary key. Ids must be unique and, ideally, reproducible across re-seeds.
