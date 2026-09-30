-- runbook spans-local-v2-cutover — the partition scope every deletion replay is expanded with (reference statement)
-- The gate test SpansLocalV2CutoverTest reimplements this inline; keep the two in step (see its Javadoc).
--
-- WHY THIS EXISTS (OPIK-8607). To run a mutation on a ReplicatedMergeTree, ClickHouse allocates a block number in every
-- AFFECTED partition, as ephemeral znodes written in a SINGLE atomic ZooKeeper `tryMulti`. A `DELETE` carrying no
-- partition bound affects every partition, so that request grows with the table's partition COUNT rather than with the
-- rows it removes; past ZooKeeper's `jute.maxbuffer` (1 MB by default) ZK rejects the packet, drops the connection and
-- the session expires. The replay is a mandatory step, so the cutover cannot proceed at all. A weekly partition key
-- grows that count every week, forever, which is why raising `jute.maxbuffer` is not the fix: any fixed ceiling is
-- crossed again, and the next crossing would be mid-window. The replays delete a handful of user-cascade ids, which
-- resolve to very few partitions — so scoping turns the request into one proportional to the work.
--
-- WHAT IT RETURNS. One row, four columns, TabSeparated, which is the whole interface the drivers consume:
--   bridged      how many distinct deleted ids the replay's bridge window holds. ZERO MEANS THE REPLAY IS A PROVABLE
--                NO-OP and the drivers emit no statement at all — every replay's predicate is an AND over a match
--                against this set, so an empty set matches nothing. Not merely an optimisation: an unbounded DELETE
--                that deletes nothing still locks every partition, so "nothing to replay" would otherwise be the
--                cheapest way to hit the failure above.
--   underivable  how many of them this cannot derive a partition for. ANY non-zero value sends the driver to its
--                unbounded form — all-or-nothing, never per id: a partially derived set is a set the remaining ids'
--                rows are not in, i.e. a delete that reports success and silently skips rows. Slower is recoverable;
--                skipped is not.
--   partitions   the derivable ids' distinct partition values, ascending, space-separated. The driver emits one
--                `DELETE ... IN PARTITION <p>` per value.
--   window_end   the instant this read bounded itself at, which the driver renders into the replay's own bridge match
--                as `${BRIDGE_WINDOW_END}`. THIS IS WHAT MAKES THE SCOPE SOUND. The derivation is a snapshot; the
--                replay's predicate is re-evaluated when the statement runs, and it carries only a LOWER bound — so
--                without a shared upper bound an id bridged between the two would be matched by the predicate while
--                its partition was absent from the scope, and a scoped statement cannot mask a row outside the
--                partitions it names. That is a silently skipped delete. Sharing this bound makes
--                "scope ⊇ everything the statement can match" true by construction rather than by timing. What falls
--                after it is not lost: it is simply this pass's tail, which the next pass or stage picks up on its own
--                wider window, exactly as the runbook already assumes for every replay.
--
-- WHY AN ID CAN CONTRIBUTE TWO WEEKS. `id_at` is MATERIALIZED as `UUIDv7ToDateTime(toUUID(id))`, and the two shapes a
-- replay may run against declare it with different widths: the legacy `spans` as a 32-bit `DateTime('UTC')`, which
-- stores `epochSecond % 2^32`, and `spans_local_v2` as `DateTime64(0, 'UTC')`, which stores the honest value. A
-- far-future id (litellm, BerriAI/litellm#31294, mints ~2201) therefore partitions differently on each. Naming only one
-- of the two is correct on one shape and, on the other, a predicate that matches nothing. So each id contributes the
-- week it resolves to under BOTH; for every id before 2106-02-07 the two coincide and nothing is widened, so only a
-- far-future id costs the extra statement. That statement is a real cost — it is a whole mutation that deletes nothing
-- — and it is the price of the direction that is safe: a statement scoped to a partition the id's row is not in is a
-- no-op there, never a wrong deletion, since the row-matching predicate inside each statement is unchanged, while
-- OMITTING a week is a delete that reports success and removes nothing.
--
-- THE ONE REJECTION: an id at or past 2300-01-01, the end of `DateTime64`'s range. Past it the column SATURATES, so
-- every such id lands in the same final partition whatever its real week and the stored value stops being a function
-- of the id. Naming that partition anyway would make correctness depend on reproducing ClickHouse's saturation
-- exactly, through a conversion chain that is not the column's, to buy pruning for ids no clock — however broken —
-- produces; the batch falls back to one unbounded statement instead. `toUnixTimestamp64Milli` is what makes such an
-- id recognisable: it returns the RAW embedded millisecond count rather than the saturated rendering. Same ceiling,
-- same reasoning, as WeeklyPartitions applies on the application's own delete path (OPIK-8364).
--
-- Nothing checks that the id is a UUIDv7, deliberately. `SpanService` validates every client-supplied span id through
-- `IdGenerator.validateId`, which rejects any other version at ingestion; and this reproduces the column's own
-- `UUIDv7ToDateTime`, so even an id that somehow was not a v7 would be derived to exactly the epoch partition
-- ClickHouse actually stored it in. `toUUIDOrZero` covers the remaining case for the same reason the length guards
-- below exist: a malformed 36-character bridge row must not abort the derivation mid-cutover.
--
-- ALL FOUR DRIVERS READ THIS ONE BLOCK — delta_replay.sh, exchange_and_wrap.sh (which re-runs 000002's replay right
-- before the swap), rollback.sh and reconcile.sh — each rendering its own scope. The same arrangement 000003's
-- settle-* blocks have, and for the same reason: the question is identical wherever it is asked, and a second copy of
-- a derivation whose silent failure is skipped deletions is what this file exists to prevent. Two placeholders:
--   ${ANALYTICS_DB_DATABASE_NAME}  the analytics database
--   ${PARTITION_SCOPE_ANCHOR}      the event_time floor of the replay this scopes. It MUST be the same anchor that
--                                  replay's own bridge match uses, or wider: 000002 passes backfill_start, 000004
--                                  cutover_start, 000006 gap_start. Wider is safe — it can only add partitions, and a
--                                  statement scoped to a partition holding none of the ids deletes nothing. NARROWER
--                                  IS NOT: it would omit the partition some of the replay's own rows are in. The
--                                  bridge guards below are those files' verbatim, for the same reason.
--
-- This is a SELECT, so it allocates no block numbers and takes no ZooKeeper lock; `deletion_events_local` is a small
-- event-time-ordered bridge table, so the read is bounded by the window rather than by the table.
-- >>> BEGIN delete-partition-scope
WITH now64(6, 'UTC') AS bridge_window_end,  -- evaluated once per query, and returned so the replay shares it
     10413792000000 AS id_at_ceiling_ms,   -- 2300-01-01T00:00:00Z: the first instant id_at cannot represent
     4294967296 AS id_at_legacy_modulus    -- 2^32 seconds: the wrap of the legacy 32-bit DateTime id_at
SELECT
    count() AS bridged,
    countIf(NOT derivable) AS underivable,
    -- Ascending and distinct so the rendered statements are byte-identical for a given bridge window whatever order
    -- the rows come back in: the values are interpolated into SQL, and an unstable order would make one mutation
    -- shape read as many in query_log. Underivable rows contribute an empty array, so they cannot contaminate this
    -- list; the driver ignores it entirely when `underivable` is non-zero.
    arrayStringConcat(arrayMap(p -> toString(p),
        arraySort(arrayDistinct(arrayFlatten(groupArray(weeks))))), ' ') AS partitions,
    toString(bridge_window_end) AS window_end
FROM (
    SELECT
        derivable,
        -- toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) — the partition expression of
        -- spans_local_v2 (migration 000115) applied to each representation the id resolves to. Mode 1 makes Monday 0,
        -- so the subtraction lands on the Monday of the id's UTC week.
        arrayMap(s -> toYYYYMMDD(toDate32(toDateTime64(s, 0, 'UTC'))
                                 - toIntervalDay(toDayOfWeek(toDateTime64(s, 0, 'UTC'), 1))),
                 if(NOT derivable,
                    [],
                    -- Only an id past the 32-bit range contributes a second week; below it the modulo is the identity,
                    -- so this branch is the invariant written as code rather than an optimisation.
                    if(id_at_seconds >= id_at_legacy_modulus,
                       [id_at_seconds, modulo(id_at_seconds, id_at_legacy_modulus)],
                       [id_at_seconds]))) AS weeks
    FROM (
        SELECT
            toUnixTimestamp64Milli(UUIDv7ToDateTime(toUUIDOrZero(deleted_id))) < id_at_ceiling_ms AS derivable,
            -- Truncating to whole seconds is the column's own conversion (both DateTime64(0) and DateTime store
            -- seconds) and cannot move a value into an earlier day, so it never changes the week.
            intDiv(toUnixTimestamp64Milli(UUIDv7ToDateTime(toUUIDOrZero(deleted_id))), 1000) AS id_at_seconds
        FROM (
            -- DISTINCT because the scope is a set of partitions, not a count of events: the same id bridged twice (a
            -- re-created span deleted again) names the same week either way, and counting it twice would only inflate
            -- the figures the driver reports to the operator.
            SELECT DISTINCT deleted_id
            FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
            WHERE source_table = 'spans'
              AND event_time >= toDateTime64('${PARTITION_SCOPE_ANCHOR}', 6, 'UTC')
              AND event_time < bridge_window_end
              AND project_id != ''
              AND length(project_id) = 36
              AND length(deleted_id) = 36
        )
    )
)
SETTINGS log_comment = 'spans_local_v2_cutover:delete_partition_scope';
-- >>> END delete-partition-scope
