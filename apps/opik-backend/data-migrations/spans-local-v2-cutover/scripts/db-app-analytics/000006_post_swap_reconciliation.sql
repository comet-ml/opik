-- runbook spans-local-v2-cutover — POST-SWAP reconciliation sweep (reference statements)
-- The gate test SpansLocalV2CutoverTest reimplements these statements inline; keep the two in step (see its Javadoc).
--
-- WHY THIS EXISTS (OPIK-8238). The last write-copying statement of the forward cutover is the delta INSERT in 000002.
-- Between it and the EXCHANGE completing the procedure interposes a deletion replay, the operator's go/no-go gap, the
-- topology guards, the cluster-wide settle gate and a SECOND full deletion replay. NOTHING holds writes across that
-- window — the procedure takes no ingestion-path hold (OPIK-8239 removed the async-insert widening that was believed
-- to, and largely did not) — so every span written to the old `spans` in it is orphaned when that table is parked as
-- `spans_pre_cutover_backup`. That loss has been observed on a real cutover, on the traces slice, and nothing about
-- spans makes it less likely: if anything the gap is LONGER here, because the settle gate ahead of the swap has to
-- tolerate the source's very large parts (README, threshold table) and the final deletion replay reads an unindexed id
-- column over the whole table (000002, step 3).
--
-- WHY POST-SWAP AND NOT PRE-SWAP. Reconciling before the swap cannot converge: the source is live, so every pass opens a
-- new gap — iterating the delta shrinks it and then stops improving. After the swap the parked table is FROZEN, so a
-- sweep against it converges by construction — which is also what makes the postcondition in
-- 000006_verify_reconciliation.sql a gate rather than a snapshot.
--
-- ../reconcile.sh is the single driver: it reads this file and runs the blocks its detected direction needs, after
-- gating on the cluster-wide settle. Never run this file by hand. Which block, and when:
--   * `forward-sweep`              after a cutover: copy the gap window out of the frozen pre-cutover backup;
--   * `forward-deletion-replay`    right after it, so bridged deletes win over what the sweep just re-inserted;
--   * `reverse-usage-range-check`  before the reverse sweep: refuse a narrowing that would silently corrupt (see below);
--   * `reverse-sweep`              after a rollback: re-import the post-cutover writes the promote made non-live. The
--                                  reverse direction needs no replay block here — it re-runs
--                                  000004_rollback_reverse_replay.sql unchanged, which already masks every key bridged
--                                  since cutover_start.
--
-- ALL SIX placeholders the driver substitutes, so a new one is never missed here (an unsubstituted ${...} reaches the
-- server as a literal and the statement fails):
--   ${ANALYTICS_DB_DATABASE_NAME}          the analytics database
--   ${LIVE_TABLE}                          the live table the sweep writes into: `spans`, or `spans_local` on a
--                                          wrapped estate (a Distributed table accepts INSERT, but the deletion replay
--                                          that follows the sweep is a mutation and a Distributed table rejects those,
--                                          so the driver resolves ONE name and uses it for every statement). Writing the
--                                          shard directly is right, not a bypass of the sharding key: the parked backup
--                                          is itself a per-shard table, so its rows already belong to the shard the
--                                          sweep is connected to.
--   ${GAP_START}                           what to COPY: the lower bound of the gap window, on created_at OR
--                                          last_updated_at (see the anchor note below)
--   ${SWAP_DONE}                           what NOT to RESURRECT: bridged deletes at or after this instant are excluded
--                                          from the sweep (see the anchor note below)
--   ${MAX_PARTITIONS_PER_INSERT_BLOCK}     partitions one block may span — a correctness gate, exactly as in 000001/000002
--   ${MIN_INSERT_BLOCK_SIZE_BYTES}         bytes per part-forming block. Carried here and not in the traces equivalent
--                                          for the reason 000001's header gives: at spans' partition count the
--                                          per-partition buffers are hundreds of MiB, so the row-data term has to be
--                                          bounded explicitly. The sweep runs at the delta's tightened byte bound
--                                          and for the same reason: a gap window's ids can be any age, so there is no
--                                          band to split it by.
--
-- THE TWO ANCHORS HAVE DISTINCT JOBS, and confusing them loses data in opposite directions.
--   * ${GAP_START} bounds what to copy. Forward it is the start of the last delta pass (`RECORD delta_start=`, printed by
--     ../delta_replay.sh); reverse it is `cutover_start`. WIDENING IT IS FREE: the sweep is mask-honored and idempotent
--     (ReplacingMergeTree, older versions lose), so re-copying rows that were already copied changes nothing. That makes
--     `backfill_start` an always-valid fallback, and means a lost delta_start never forces an escalation — unlike
--     `cutover_start` or the sentinel-repair window, where guessing destroys or resurrects data.
--   * ${SWAP_DONE} bounds what not to resurrect, and is captured AFTER the swap statement returns
--     (`RECORD exchange_done=` / `RECORD promote_done=`). Using `cutover_start` here instead would wrongly exclude a
--     span deleted and then re-created between `cutover_start` and the EXCHANGE: that span is legitimately live in the
--     frozen backup and must be swept back.
--
-- WHY `created_at OR last_updated_at` IS COMPLETE. Every write path stamps a fresh SERVER timestamp in at least one of
-- them: SpanDAO's batch-insert omits created_at (so the column DEFAULT now64() applies) while binding a CLIENT-supplied
-- last_updated_at, and the create/update merge paths preserve created_at while letting last_updated_at default to
-- now64(). Neither column alone is therefore a reliable "written since" signal; their union is. This is the same argument
-- 000002's delta anchor rests on. ../reconcile.sh's --slack-seconds widens ${GAP_START} downward to absorb cross-replica
-- clock skew on those server defaults, which is free per the widening note above.
--
-- BOUNDED COST. Both bounds prune on the created_at / last_updated_at minmax skip indexes (migration 000088 on the
-- original, and the equivalent indexes on the successor), so the sweep scales with the GAP, not with the table. That
-- matters more here than it did on traces: an unbounded read of this pair would be the whole table.
--
-- EVERY WINDOW BOUND PINS 'UTC', for the reason 000001's header gives at length: these columns are DateTime64(n, 'UTC')
-- while an unpinned literal is parsed in the SERVER timezone, and the drivers capture their anchors with now64(6, 'UTC')
-- so both halves of the pair agree.
--
-- THE COLUMN LISTS ARE SPELLED OUT, NOT SHARED, because each block has to stay a complete statement a driver can read
-- and an operator can review. KEEP THEM IN STEP BY HAND with 000001's backfill and 000002's delta: an INSERT ... SELECT
-- is positional, so every one of these statements must list AND project exactly the base columns of `spans`, in the
-- same order. A base column added by a future migration and missed here is silently never copied — which is why
-- SpansLocalV2CutoverTest#cutoverCopiesEveryBaseColumn fails the build until the cutover's column list is updated:
-- treat that failure as the prompt to update these statements too.

-- >>> BEGIN forward-sweep
-- Forward: copy the gap window out of the FROZEN pre-cutover backup into the live successor.
--
-- Same projection as 000001/000002 — the source here is the old original, so end_time / ttft are still Nullable and are
-- coalesced to the successor's epoch / NaN sentinels, and parent_span_id carries the same length guard (a 40-character
-- poison value from SpanDAO's PARTIAL_INSERT would otherwise throw on the FixedString(36) destination and abort the
-- sweep — immediately after the swap, with the gap still open, which is the worst moment this procedure has). The epoch
-- literal stays unpinned, matching 000001 (it must agree with the successor's own DEFAULT and duration expression,
-- which are unpinned too).
--
-- Mask-honored: apply_deleted_mask stays at its default 1, so a row already lightweight-deleted on the old table when it
-- was parked is never copied back. Idempotent: the target is a ReplacingMergeTree keyed on
-- (workspace_id, project_id, trace_id, id) with last_updated_at as the version, so re-running the sweep re-inserts rows
-- that lose to anything newer. A key whose live row is NEWER keeps the newer row; the postcondition reports that as
-- `newer_keys` and tolerates it.
--
-- A SPANS-ONLY CONSEQUENCE OF THE NARROWER DESTINATION KEY: if the frozen backup holds two live rows for one span under
-- different parent_span_id values, both are swept and they collapse to one on the destination, newest version winning —
-- which is exactly what the backfill did with the same pair, so the sweep introduces no new behaviour. It does mean the
-- sweep can insert two rows and the postcondition then find one key; that is correct, and the postcondition reduces the
-- parked side the same way (see 000006_verify_reconciliation.sql).
--
-- That protects live traffic only as far as last_updated_at is MONOTONIC, and it is not: the column is client-writable
-- and bound verbatim on the batch-ingest path, so a post-swap write can carry a last_updated_at BELOW the parked row's.
-- The parked payload then wins the version comparison and that write is lost, while the gate compares parked against
-- parked and reports zeros. Nothing inside this statement fixes it: the version column is the successor's, and both
-- alternatives defeat the sweep's purpose — skipping keys already live would abandon exactly the stale and partial rows
-- it exists to repair, and re-stamping last_updated_at would clobber legitimate newer writes. Same root cause and the
-- same durable fix as the runbook's client-timestamp residual: clamp client timestamps at ingestion.
--
-- The NOT IN arm is what stops the sweep resurrecting a gap-window span the user deleted AFTER the swap: such a span is
-- still live in the frozen backup (it was live when the backup froze), so without this arm the sweep would insert a fresh
-- version and undo the delete. Deletes bridged BEFORE ${SWAP_DONE} are deliberately not excluded here — one of those may
-- have been deleted and re-created before the freeze, in which case the backup's live row is the re-created one and must
-- be swept back. Those are handled instead by the deletion replay ../reconcile.sh runs AFTER this sweep, so deletes win.
--
-- The length(...) = 36 guards on the BRIDGE columns match 000002 and 000004 for the same reason: toFixedString THROWS on
-- a longer value, and a non-36-char bridge row cannot match a real span id anyway, so skipping it turns a hard abort
-- into a benign no-op. (That is a different guard from the parent_span_id one in the projection, which protects the
-- DESTINATION column rather than the bridge join.)
--
-- ONE RESIDUAL THIS SWEEP INTRODUCES, stated because it is a delete that can come back. Capture writes the bridge row
-- BEFORE the delete executes, so for the width of the EXCHANGE a delete can be bridged at t < ${SWAP_DONE} while its
-- DELETE lands on the successor AFTER the swap. That key is then live in the frozen backup (the old table never saw the
-- delete), bridged before ${SWAP_DONE} so the arm above does not exclude it, and spared by the following replay's guard
-- for the same reason — so the sweep resurrects a span the user deleted. It needs the span to be written AND deleted
-- inside the gap window with the swap falling between its capture and its delete, which is why the bound stays
-- ${SWAP_DONE}: moving it to ${GAP_START} would trade this for a strictly likelier loss, dropping every span that was
-- deleted and re-created before the freeze. It is the same class as the residual the runbook already carries, and the
-- same mitigation covers it: QUIESCE USER TRACE DELETES ACROSS THE SWAP, not merely reads — trace deletes, because the
-- cascade is the only path that deletes a span.
INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.${LIVE_TABLE} (
    id,
    workspace_id,
    project_id,
    trace_id,
    parent_span_id,
    name,
    type,
    start_time,
    end_time,
    input,
    output,
    metadata,
    tags,
    usage,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    model,
    provider,
    total_estimated_cost,
    total_estimated_cost_version,
    error_info,
    truncation_threshold,
    input_slim,
    output_slim,
    ttft,
    source,
    environment
)
SELECT
    id,
    workspace_id,
    project_id,
    trace_id,
    if(length(parent_span_id) = 36, toFixedString(parent_span_id, 36), toFixedString('', 36)) AS parent_span_id,
    name,
    type,
    start_time,
    coalesce(end_time, toDateTime64('1970-01-01 00:00:00', 6)) AS end_time,
    input,
    output,
    metadata,
    tags,
    usage,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    model,
    provider,
    total_estimated_cost,
    total_estimated_cost_version,
    error_info,
    truncation_threshold,
    input_slim,
    output_slim,
    coalesce(ttft, toFloat64('nan')) AS ttft,
    source,
    environment
FROM ${ANALYTICS_DB_DATABASE_NAME}.spans_pre_cutover_backup
WHERE (created_at >= toDateTime64('${GAP_START}', 6, 'UTC')
    OR last_updated_at >= toDateTime64('${GAP_START}', 6, 'UTC'))
  AND (workspace_id, project_id, id) NOT IN (
      SELECT
          workspace_id,
          toFixedString(project_id, 36),
          toFixedString(deleted_id, 36)
      FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
      WHERE source_table = 'spans'
        AND event_time >= toDateTime64('${SWAP_DONE}', 6, 'UTC')
        AND project_id != ''
        AND length(project_id) = 36
        AND length(deleted_id) = 36
  )
SETTINGS max_partitions_per_insert_block = ${MAX_PARTITIONS_PER_INSERT_BLOCK},
         min_insert_block_size_bytes = ${MIN_INSERT_BLOCK_SIZE_BYTES},
         log_comment = 'spans_local_v2_cutover:reconcile:forward_sweep';
-- >>> END forward-sweep

-- >>> BEGIN forward-deletion-replay
-- Forward, immediately after the sweep: re-apply onto the live successor every delete the bridge recorded since
-- ${GAP_START} that the frozen backup still shows as deleted. Run AFTER the sweep so deletes win over anything it
-- re-inserted.
--
-- KEEP IN STEP WITH 000002's `deletion-replay` block, which this deliberately does NOT reuse. The two share a shape —
-- the full-key match against the bridge, the toFixedString(36) casts, the length guards, the resurrection guard, both
-- SETTINGS — but not their semantics, and the difference is the whole reason this file exists. 000002 runs PRE-swap
-- against a LIVE source; this runs POST-swap against a FROZEN one. Parameterising one block to do both would mean a
-- placeholder whose only job is to switch off the third arm below wherever the replay runs pre-swap, inside the
-- statement whose silent failure leaks deletions. The same argument keeps 000004_rollback_reverse_replay.sql separate.
--
-- ARM 1 (the bridge match) is 000002's, with ${GAP_START} as the event_time floor rather than backfill_start. Widening
-- that floor is safe — the two guards below decide what is masked, not this bound.
--
-- ARM 2 (the resurrection guard) reads the FROZEN backup, and that is what makes it race-free where 000002's cannot be:
-- the source cannot change under the read. It is also what closes the delete-side residual the runbook otherwise accepts
-- as inherent — a delete whose bridge row commits after the final pre-swap replay has read the bridge but before the
-- EXCHANGE, which is covered by neither the forward replay nor the rollback reverse-replay. Note the frozen table is
-- the whole table with no id skip index, so this arm's cost is the same unindexed id read 000002 step 3 sizes — but it is paid
-- AFTER the swap, where it costs reconciliation time rather than cutover tail.
--
-- ARM 3 (the staleness scope) is what keeps that frozen guard from destroying a post-cutover write, and it has no
-- counterpart in 000002 because pre-swap there is nothing to protect: the shadow receives no writes except the copy. A
-- frozen source cannot see a row written AFTER the swap, so a key deleted before the swap and then re-created or patched
-- after it looks "still deleted" to arm 2 — and without this arm the replay would mask a live post-cutover write,
-- turning the fix for write loss into a cause of it. Not exotic: span ids are client-supplied and SpanDAO's update path
-- re-inserts a version, so an in-flight patch to a just-deleted span does exactly this.
--
-- The scope is per ROW, not per key, which is stronger than sparing the key wholesale: a leaked stale copy is masked
-- while a newer version of the same key survives — which is what ReplacingMergeTree would have served anyway. The union
-- of the two columns is complete for the same reason the gap window's is (see the header).
--
-- ARM 3's RESIDUAL, AND WHY THE PREDICATE STAYS AS IT IS. `last_updated_at` is CLIENT-SUPPLIED on the batch-ingest path:
-- SpanDAO binds the request's value, and the API accepts any value before 2300. So a genuinely PRE-swap row
-- can carry a future timestamp, fall outside this scope, and keep its captured delete unmasked — a leaked delete. The
-- two ways out are both worse:
--   * Dropping the last_updated_at conjunct (scoping on created_at alone) inverts the failure. The merge path PRESERVES
--     created_at, so a post-swap PATCH of a pre-existing span would then be masked. Over-sparing leaves a deleted
--     span visible while its key is still in the bridge, so it can be re-masked; over-masking destroys a post-swap
--     write that exists on the successor and nowhere else. This predicate is the recoverable side of that trade.
--   * Comparing the row's VERSION against the versions the frozen backup held needs neither timestamp and would close
--     both directions — but it is impossible in a MUTATION. Those backup rows were lightweight-deleted before the table
--     was parked, so matching them needs an unmasked read, and a lightweight DELETE ACCEPTS apply_deleted_mask = 0 in
--     SETTINGS and then IGNORES it (verified on 26.3): the subquery reads the backup mask-honored, matches nothing, and
--     the statement reports success having masked NOTHING. Exactly the silent success this file is written to avoid.
-- So the residual is DETECTED rather than prevented. That version comparison works in a READ, and ships as
-- 000006_verify_reconciliation.sql's `leak-check-forward`, which ../reconcile.sh reports beside the four counts. The
-- mitigation is the one the runbook already carries for the other delete-side residuals — quiesce user trace deletes
-- across the swap, which empties the window this needs. Clamping a future client last_updated_at at ingestion is the
-- durable fix and is not this procedure's to make.
DELETE FROM ${ANALYTICS_DB_DATABASE_NAME}.${LIVE_TABLE}
WHERE created_at      <  toDateTime64('${SWAP_DONE}', 6, 'UTC')
  AND last_updated_at <  toDateTime64('${SWAP_DONE}', 6, 'UTC')
  AND (workspace_id, project_id, id) IN (
      SELECT
          workspace_id,
          toFixedString(project_id, 36),
          toFixedString(deleted_id, 36)
      FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
      WHERE source_table = 'spans'
        AND event_time >= toDateTime64('${GAP_START}', 6, 'UTC')
        AND project_id != ''
        AND length(project_id) = 36
        AND length(deleted_id) = 36
  )
  AND (workspace_id, project_id, id) NOT IN (
      SELECT
          workspace_id,
          project_id,
          id
      FROM ${ANALYTICS_DB_DATABASE_NAME}.spans_pre_cutover_backup
      WHERE id IN (
          SELECT toFixedString(deleted_id, 36)
          FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
          WHERE source_table = 'spans'
            AND event_time >= toDateTime64('${GAP_START}', 6, 'UTC')
            AND length(deleted_id) = 36
      )
  )
-- allow_nondeterministic_mutations: a lightweight DELETE with cross-table subqueries is flagged nondeterministic, but
-- deletion_events_local and the parked backup are replicated and identical on every node and the window predicate is
-- fixed, so the subqueries resolve to the same set on every replica.
-- lightweight_deletes_sync = 2: return only once the mask has applied on EVERY replica, so the postcondition in
-- 000006_verify_reconciliation.sql — which reads one replica — cannot race an unapplied mutation.
SETTINGS allow_nondeterministic_mutations = 1,
         lightweight_deletes_sync = 2,
         log_comment = 'spans_local_v2_cutover:reconcile:deletion_replay';
-- >>> END forward-deletion-replay

-- >>> BEGIN reverse-usage-range-check
-- REVERSE ONLY, and a REFUSAL rather than a report: how many rows the reverse sweep would import carry a `usage` value
-- outside Int32. It has no traces counterpart because traces has no usage column at all.
--
-- The reverse sweep writes the successor's Map(String, Int64) back into the original's Map(String, Int32) — the only
-- NARROWING anywhere in this procedure, and the one projection that cannot be made lossless by normalization. ClickHouse
-- converts on insert rather than refusing, so an out-of-range value would land as a wrapped, silently wrong number, in a
-- column the product reads as a token count. Everything else the reverse sweep does is reversible while the parked
-- successor exists; this would not be.
--
-- It is expected to return 0 on any real estate: `usage` holds LLM token counts, and 2,147,483,647 tokens for one span
-- is not a number a model produces. So the check costs a bounded read of the gap window and normally proves the
-- narrowing is a no-op. ../reconcile.sh refuses the reverse direction on a non-zero result rather than importing —
-- resolve those rows by hand (they can be read out of the parked successor, which is retained until finalize.sh).
SELECT count() AS out_of_int32_range
FROM ${ANALYTICS_DB_DATABASE_NAME}.spans_post_rollback_backup
WHERE (created_at >= toDateTime64('${GAP_START}', 6, 'UTC')
    OR last_updated_at >= toDateTime64('${GAP_START}', 6, 'UTC'))
  AND arrayExists(v -> v > 2147483647 OR v < -2147483648, mapValues(usage))
SETTINGS log_comment = 'spans_local_v2_cutover:reconcile:reverse_usage_range_check';
-- >>> END reverse-usage-range-check

-- >>> BEGIN reverse-sweep
-- Reverse: re-import the post-cutover writes a stage B/C promote made non-live, out of the parked successor
-- (`spans_post_rollback_backup`) and back into the restored original.
--
-- THIS IS OPT-IN AND DELIBERATE. Those writes are exactly what --accept-post-cutover-write-loss acknowledged discarding,
-- so ../reconcile.sh refuses this direction without --confirm-reimport-successor-writes. It is the right choice when the
-- rollback was motivated by latency, merge load or the wrap rather than by data fidelity — there the discarded writes are
-- good data — and the wrong one when the successor's CONTENT is what is suspect.
--
-- SENTINEL -> NULL DENORMALIZATION, the mirror of the forward projection. The successor stores an absent end_time as the
-- epoch and an absent ttft as NaN; the original's convention is NULL, and its MATERIALIZED `duration` guards only
-- `end_time IS NOT NULL` — it does not know the epoch sentinel — so importing the sentinel verbatim would give every
-- unfinished span a duration of roughly -1.79e12 ms instead of NULL. Restoring NULL is what makes the recomputed
-- duration NULL, and the mutation-free INSERT path recomputes it on write.
--
-- parent_span_id IS DENORMALIZED TOO, AND IT IS NOT COSMETIC. The successor's FixedString(36) stores an absent parent as
-- 36 NUL bytes; the original's String stores it as ''. CAST(... AS String) trims that padding, which is the ONLY reason
-- the re-imported rows read correctly afterwards: migration 000115's header records that the driver surfaces the padded
-- form to Java as 36 NUL characters, which is not blank, so a !isBlank() guard lets it through and UUID.fromString then
-- throws — and that SpanDAO's SQL presence checks (LENGTH(CAST(parent_span_id AS Nullable(String))) > 0) would read 36
-- rather than 0, making every re-imported root span look like a child. Importing the padding verbatim would therefore
-- corrupt the restored original in a way no count in this procedure reports.
--
-- usage IS NARROWED BACK to Map(String, Int32) by the implicit insert conversion, which is lossy in principle. The
-- `reverse-usage-range-check` block above is run first and the driver refuses on a non-zero result, so no wrapped value
-- reaches this statement.
--
-- THE EPOCH LITERAL PINS 'UTC' HERE, unlike the forward projection, and the asymmetry is intended. Forward, the sentinel
-- being written must agree with the successor's own unpinned DEFAULT and duration expression. Reverse, the sentinel being
-- READ was written by the BACKEND (spanColumnsNonNullable binds Instant.EPOCH, an absolute instant 0) for every row in
-- this window, since the window starts at cutover_start and only post-cutover traffic wrote here. Same reasoning, and the
-- same conclusion, as 000004_rollback_sentinel_repair.sql. On a UTC server the two spellings coincide.
--
-- max_partitions_per_insert_block IS INERT ON THIS PATH and is carried anyway, so the driver renders one placeholder set
-- for both directions. The reverse target is the restored ORIGINAL `spans`, which has no PARTITION BY at all, so every
-- block spans exactly one partition whatever the setting says. min_insert_block_size_bytes is NOT inert here — spans
-- rows are wide uncompressed either way.
--
-- ORDER MATTERS: ../reconcile.sh runs 000004_rollback_reverse_replay.sql AFTER this sweep, so a span deleted since
-- cutover_start is re-imported here and then masked there — deletes win, and 000004_rollback_verify_replay.sql still
-- reports 0. The reverse replay carries no resurrection guard by design (see its header), so an id deleted and then
-- re-created after cutover_start stays masked: the delete is honoured and the re-creation is lost with the other
-- discarded writes, which is the rollback semantics the runbook already documents.
INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.${LIVE_TABLE} (
    id,
    workspace_id,
    project_id,
    trace_id,
    parent_span_id,
    name,
    type,
    start_time,
    end_time,
    input,
    output,
    metadata,
    tags,
    usage,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    model,
    provider,
    total_estimated_cost,
    total_estimated_cost_version,
    error_info,
    truncation_threshold,
    input_slim,
    output_slim,
    ttft,
    source,
    environment
)
SELECT
    id,
    workspace_id,
    project_id,
    trace_id,
    CAST(parent_span_id AS String) AS parent_span_id,
    name,
    type,
    start_time,
    nullIf(end_time, toDateTime64('1970-01-01 00:00:00', 6, 'UTC')) AS end_time,
    input,
    output,
    metadata,
    tags,
    usage,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    model,
    provider,
    total_estimated_cost,
    total_estimated_cost_version,
    error_info,
    truncation_threshold,
    input_slim,
    output_slim,
    if(isNaN(ttft), NULL, ttft) AS ttft,
    source,
    environment
FROM ${ANALYTICS_DB_DATABASE_NAME}.spans_post_rollback_backup
WHERE (created_at >= toDateTime64('${GAP_START}', 6, 'UTC')
    OR last_updated_at >= toDateTime64('${GAP_START}', 6, 'UTC'))
  AND (workspace_id, project_id, id) NOT IN (
      SELECT
          workspace_id,
          toFixedString(project_id, 36),
          toFixedString(deleted_id, 36)
      FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
      WHERE source_table = 'spans'
        AND event_time >= toDateTime64('${SWAP_DONE}', 6, 'UTC')
        AND project_id != ''
        AND length(project_id) = 36
        AND length(deleted_id) = 36
  )
SETTINGS max_partitions_per_insert_block = ${MAX_PARTITIONS_PER_INSERT_BLOCK},
         min_insert_block_size_bytes = ${MIN_INSERT_BLOCK_SIZE_BYTES},
         log_comment = 'spans_local_v2_cutover:reconcile:reverse_sweep';
-- >>> END reverse-sweep
