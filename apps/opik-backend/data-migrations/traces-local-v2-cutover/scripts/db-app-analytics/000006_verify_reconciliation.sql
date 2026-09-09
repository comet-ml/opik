-- runbook traces-local-v2-cutover — POST-SWAP reconciliation POSTCONDITION (driven by ../reconcile.sh)
-- The gate test TracesLocalV2CutoverTest reimplements these statements inline; keep the two in step (see its Javadoc).
--
-- Read-only, and read repeatedly by the driver: once before any mutation (so a reconciled estate skips the sweep
-- entirely and the mode is a true no-op), and once after each pass, as the gate. --report-only runs nothing else.
--
-- ONE ROW, FOUR COUNTS, read with `read -r` exactly as ../rollback.sh reads 000004_rollback_verify_sentinels.sql —
-- including that file's idiom of carrying one deliberately-INFORMATIONAL count alongside the gates. For every key that is
-- live in the PARKED table inside the gap window, the live table's version of that key is classified:
--   * absent from the live table                              -> missing_keys          (GATE)
--   * live version STRICTLY OLDER than the parked one         -> stale_keys            (GATE)
--   * SAME version, differing normalized fingerprint          -> payload_mismatch_keys (GATE)
--   * live version NEWER than the parked one                  -> newer_keys            (informational)
-- THE GATE IS THE FIRST THREE AT 0. That is one number covering presence, version AND payload, which is why the
-- reconciler needs no second fidelity query to decide whether it is done.
--
-- WHY IT IS RACE-FREE, which is the property that makes it a gate rather than a snapshot. The parked side is frozen, so
-- its row set cannot move under the read. Post-swap traffic can only ever move a key from a gating bucket into the
-- tolerated one: a write bumps last_updated_at, so the key becomes `newer_keys`; it cannot turn a matching key into a
-- missing or stale one. So a 0 stays true, and a non-zero is a real finding rather than a race.
--
-- WHAT `newer_keys` MEANS, and why it is not a gate. It is EXPECTED to be non-zero on a busy estate: any gap-window trace
-- that was written again after the swap is newer on the live side, and the sweep deliberately leaves it that way (its
-- INSERT loses the ReplacingMergeTree version comparison). Gating on it would fail every healthy reconciliation. It is
-- reported because an operator sizing the window wants the number.
--
-- WHY NOT clusterAllReplicas, unlike 000004_rollback_verify_replay.sql. A Replicated table returns one full copy per
-- replica through that function, so the JOIN below would multiply both sides and the counts would be meaningless. The
-- driver therefore runs the cluster-wide settle gate (empty replication_queue, mutations done) BEFORE reading this, which
-- is what makes a single-replica read representative; that ordering is a requirement of this file, not an optimisation.
--
-- NORMALIZATION ARMS COME FROM 000005_verify_migration.sql VERBATIM — KEEP IN STEP WITH IT. Same microsecond-epoch
-- timestamps, same absent-end_time -> 0 and absent-ttft -> 'nan' canonicalization, same toString on enums/project_id,
-- same '\x1f' tag delimiter, same cityHash64. A fingerprint that diverged from the one verify.sh computes would make
-- these two tools disagree about the same rows, and would classify keys here by a fingerprint the fidelity compare does
-- not use. The VERSION is compared as a microsecond epoch for the same reason it is hashed as one: the copy truncates
-- nanoseconds to microseconds, so comparing the raw columns would report every faithfully-copied row with a
-- sub-microsecond last_updated_at as `stale_keys`.
--
-- FINAL on both sides, so the comparison is of the live, logical row; the default apply_deleted_mask = 1 keeps it to
-- rows that are not lightweight-deleted. The `parked` CTE is referenced twice (once to pick the live side's candidate
-- keys, once as the join's left side) and ClickHouse inlines rather than materializes a CTE, so it is evaluated twice —
-- acceptable because it is bounded by the gap window and its skip indexes, not by the table.
--
-- ALL FOUR placeholders the driver substitutes, so a new one is never missed here:
--   ${ANALYTICS_DB_DATABASE_NAME}   the analytics database
--   ${LIVE_TABLE}                   `traces`, or `traces_local` on a wrapped estate (see 000006_post_swap_reconciliation.sql)
--   ${GAP_START}                    the gap window's lower bound, the same value the sweep used
--   ${SWAP_DONE}                    the instant the swap returned (forward block only)

-- >>> BEGIN verify-forward
-- Forward: parked = traces_pre_cutover_backup (OLD schema — Nullable, nanosecond); live = the successor (sentinels,
-- microsecond). The exclusion arm mirrors the forward sweep's: a gap-window key deleted AFTER the swap is legitimately
-- absent from the live table, so counting it as `missing_keys` would make the gate unreachable. Keys deleted BEFORE the
-- swap are NOT excluded here, deliberately — one that is live in the frozen backup was re-created before the freeze and
-- must be present live, and one that is not live in the backup never enters this set at all (FINAL + the delete mask).
WITH
    parked AS (
        SELECT
            (workspace_id, project_id, id) AS key,
            toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)) AS parked_version,
            cityHash64(
                id,
                workspace_id,
                toString(project_id),
                name,
                toUnixTimestamp64Micro(toDateTime64(start_time, 6)),
                coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0)),
                input,
                output,
                metadata,
                arrayStringConcat(tags, '\x1f'),
                toUnixTimestamp64Micro(toDateTime64(created_at, 6)),
                toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)),
                created_by,
                last_updated_by,
                error_info,
                thread_id,
                toString(visibility_mode),
                truncation_threshold,
                input_slim,
                output_slim,
                if(ttft IS NULL, 'nan', toString(ttft)),
                toString(source),
                toString(environment)) AS parked_fingerprint
        FROM ${ANALYTICS_DB_DATABASE_NAME}.traces_pre_cutover_backup FINAL
        WHERE (created_at >= toDateTime64('${GAP_START}', 6, 'UTC')
            OR last_updated_at >= toDateTime64('${GAP_START}', 6, 'UTC'))
          AND (workspace_id, project_id, id) NOT IN (
              SELECT
                  workspace_id,
                  toFixedString(project_id, 36),
                  toFixedString(deleted_id, 36)
              FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
              WHERE source_table = 'traces'
                AND event_time >= toDateTime64('${SWAP_DONE}', 6, 'UTC')
                AND project_id != ''
                AND length(project_id) = 36
                AND length(deleted_id) = 36
          )
    ),
    live AS (
        SELECT
            (workspace_id, project_id, id) AS key,
            toUnixTimestamp64Micro(last_updated_at) AS live_version,
            cityHash64(
                id,
                workspace_id,
                toString(project_id),
                name,
                toUnixTimestamp64Micro(start_time),
                toUnixTimestamp64Micro(end_time),
                input,
                output,
                metadata,
                arrayStringConcat(tags, '\x1f'),
                toUnixTimestamp64Micro(created_at),
                toUnixTimestamp64Micro(last_updated_at),
                created_by,
                last_updated_by,
                error_info,
                thread_id,
                toString(visibility_mode),
                truncation_threshold,
                input_slim,
                output_slim,
                if(isNaN(ttft), 'nan', toString(ttft)),
                toString(source),
                toString(environment)) AS live_fingerprint
        FROM ${ANALYTICS_DB_DATABASE_NAME}.${LIVE_TABLE} FINAL
        -- Keys only, with no window predicate: the live row for a gap-window key may carry any created_at (the merge path
        -- preserves the original, batch ingestion re-stamps it), so bounding this side by the window would report a
        -- re-stamped row as missing.
        WHERE (workspace_id, project_id, id) IN (SELECT key FROM parked)
    )
SELECT
    countIf(live_version IS NULL) AS missing_keys,
    countIf(live_version IS NOT NULL AND live_version < parked_version) AS stale_keys,
    countIf(live_version IS NOT NULL AND live_version = parked_version
            AND live_fingerprint != parked_fingerprint) AS payload_mismatch_keys,
    countIf(live_version IS NOT NULL AND live_version > parked_version) AS newer_keys
FROM parked
LEFT JOIN live USING (key)
-- join_use_nulls = 1 is required for correctness, for the same reason 000005's drill-down needs it: by default ClickHouse
-- fills an unmatched side with the column's DEFAULT (0 for the hash, 0 for the version), which would make an absent key
-- indistinguishable from a real 0 and leave the IS NULL predicate dead — so every missing key would be silently counted
-- as `stale_keys` or, worse, as nothing at all.
SETTINGS join_use_nulls = 1,
         use_skip_indexes_if_final = 1,
         log_comment = 'traces_local_v2_cutover:reconcile:verify_forward';
-- >>> END verify-forward

-- >>> BEGIN verify-reverse
-- Reverse: parked = traces_post_rollback_backup (NEW schema — sentinels, microsecond); live = the restored original
-- (Nullable, nanosecond). The shapes are the mirror image of the forward block, so the two normalization arms swap sides.
--
-- The exclusion arm is bounded by ${GAP_START} here, NOT by ${SWAP_DONE}, and that difference is load-bearing: the
-- reverse replay ../reconcile.sh runs after the sweep is 000004_rollback_reverse_replay.sql, which masks every key
-- bridged since cutover_start — so a key deleted anywhere in [cutover_start, now) is legitimately absent from the live
-- table and must not be counted as missing. Forward, only post-swap deletes are legitimately absent, because the forward
-- replay's resurrection guard spares anything that is live in the parked table.
WITH
    parked AS (
        SELECT
            (workspace_id, project_id, id) AS key,
            toUnixTimestamp64Micro(last_updated_at) AS parked_version,
            cityHash64(
                id,
                workspace_id,
                toString(project_id),
                name,
                toUnixTimestamp64Micro(start_time),
                toUnixTimestamp64Micro(end_time),
                input,
                output,
                metadata,
                arrayStringConcat(tags, '\x1f'),
                toUnixTimestamp64Micro(created_at),
                toUnixTimestamp64Micro(last_updated_at),
                created_by,
                last_updated_by,
                error_info,
                thread_id,
                toString(visibility_mode),
                truncation_threshold,
                input_slim,
                output_slim,
                if(isNaN(ttft), 'nan', toString(ttft)),
                toString(source),
                toString(environment)) AS parked_fingerprint
        FROM ${ANALYTICS_DB_DATABASE_NAME}.traces_post_rollback_backup FINAL
        WHERE (created_at >= toDateTime64('${GAP_START}', 6, 'UTC')
            OR last_updated_at >= toDateTime64('${GAP_START}', 6, 'UTC'))
          AND (workspace_id, project_id, id) NOT IN (
              SELECT
                  workspace_id,
                  toFixedString(project_id, 36),
                  toFixedString(deleted_id, 36)
              FROM ${ANALYTICS_DB_DATABASE_NAME}.deletion_events_local
              WHERE source_table = 'traces'
                AND event_time >= toDateTime64('${GAP_START}', 6, 'UTC')
                AND project_id != ''
                AND length(project_id) = 36
                AND length(deleted_id) = 36
          )
    ),
    live AS (
        SELECT
            (workspace_id, project_id, id) AS key,
            toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)) AS live_version,
            cityHash64(
                id,
                workspace_id,
                toString(project_id),
                name,
                toUnixTimestamp64Micro(toDateTime64(start_time, 6)),
                coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0)),
                input,
                output,
                metadata,
                arrayStringConcat(tags, '\x1f'),
                toUnixTimestamp64Micro(toDateTime64(created_at, 6)),
                toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)),
                created_by,
                last_updated_by,
                error_info,
                thread_id,
                toString(visibility_mode),
                truncation_threshold,
                input_slim,
                output_slim,
                if(ttft IS NULL, 'nan', toString(ttft)),
                toString(source),
                toString(environment)) AS live_fingerprint
        FROM ${ANALYTICS_DB_DATABASE_NAME}.${LIVE_TABLE} FINAL
        WHERE (workspace_id, project_id, id) IN (SELECT key FROM parked)
    )
SELECT
    countIf(live_version IS NULL) AS missing_keys,
    countIf(live_version IS NOT NULL AND live_version < parked_version) AS stale_keys,
    countIf(live_version IS NOT NULL AND live_version = parked_version
            AND live_fingerprint != parked_fingerprint) AS payload_mismatch_keys,
    countIf(live_version IS NOT NULL AND live_version > parked_version) AS newer_keys
FROM parked
LEFT JOIN live USING (key)
SETTINGS join_use_nulls = 1,
         use_skip_indexes_if_final = 1,
         log_comment = 'traces_local_v2_cutover:reconcile:verify_reverse';
-- >>> END verify-reverse
