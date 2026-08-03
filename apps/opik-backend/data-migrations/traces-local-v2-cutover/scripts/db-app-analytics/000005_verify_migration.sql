-- runbook traces-local-v2-cutover — QA: normalized fidelity compare of one created_at window (reference statements)
--
-- Proves the copy altered no data by comparing a NORMALIZED fingerprint of the deduped, live rows on the old-schema and
-- new-schema tables. The rows are not byte-identical (end_time NULL -> epoch sentinel, ttft NULL -> NaN sentinel,
-- timestamps nanosecond -> microsecond), so each side is canonicalized to the same value for a faithfully-migrated row:
--   * timestamps as their microsecond epoch (source ns truncated to us, matching the copy);
--   * absent end_time -> 0 (source NULL; dest epoch);
--   * absent ttft -> the token 'nan' (source NULL; dest NaN);
--   * enums / project_id via toString; id in every row hash so a swap can't cancel;
--   * tags joined with a '\x1f' (ASCII Unit Separator) delimiter: the delimiter is what makes a tag-BOUNDARY change
--     detectable — without it ['a','b'] and ['ab'] both concatenate to 'ab' and hash identically. \x1f is a C0 control
--     char purpose-built as a field separator that real (printable) tag text never contains, so it cannot collide with
--     tag content the way ',' or ' ' could.
-- FINAL collapses ReplacingMergeTree versions to the winner; the default apply_deleted_mask excludes deleted rows.
-- sum() is order-independent (no sort) and, unlike groupBitXor, does not cancel a colliding pair within a table; with
-- count() it detects any changed / missing / extra row. An empty window sums to NULL on the Nullable-typed old side but 0
-- on the new, so the verdict uses ifNull(_, 0) and a count guard — empty vs empty is a match, empty vs non-empty is not.
-- cityHash64 (not sipHash64): both sides are hashed live on the same instance, so a fast non-cryptographic 64-bit hash is
-- enough — sipHash64's adversarial-collision resistance would only add CPU here, and cross-build portability does not
-- matter because we never compare a stored hash against a later build. Summed 64-bit hashes miss a real difference with
-- probability ~2^-64 per window. Materialized/derived columns and is_deleted are excluded — recomputed, not migrated.
--
-- ../verify.sh is the single driver: it reads this file and runs the `compare` block once per created_at week (optionally
-- sampled), parsing the single verdict row; with --drill-down it runs the `drill-down` block for a week that reported
-- ok=0. Run QA through verify.sh, never this file by hand.
--
-- OLD_TABLE is the old-schema table (Nullable, nanosecond) and NEW_TABLE the new-schema one (sentinels, microsecond).
-- Before the EXCHANGE: OLD_TABLE=traces, NEW_TABLE=traces_local_v2 (the successor being built). After it, `traces` is
-- the new schema and the old data is parked as `traces_pre_cutover_backup` — set OLD_TABLE=traces_pre_cutover_backup,
-- NEW_TABLE=traces. SAMPLE_MOD=1 compares every row; SAMPLE_MOD=100 compares a deterministic ~1% id sample (same rows
-- on both sides) when a full pass is infeasible.

-- >>> BEGIN compare
WITH
    src AS (
        SELECT
            count() AS c,
            sum(cityHash64(
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
                toString(environment))) AS h
        FROM ${ANALYTICS_DB_DATABASE_NAME}.${OLD_TABLE} FINAL
        WHERE created_at >= toDateTime64('${WINDOW_LO}', 9)
          AND created_at <  toDateTime64('${WINDOW_HI}', 9)
          AND cityHash64(id) % ${SAMPLE_MOD} = 0
    ),
    dst AS (
        SELECT
            count() AS c,
            sum(cityHash64(
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
                toString(environment))) AS h
        FROM ${ANALYTICS_DB_DATABASE_NAME}.${NEW_TABLE} FINAL
        WHERE created_at >= toDateTime64('${WINDOW_LO}', 6)
          AND created_at <  toDateTime64('${WINDOW_HI}', 6)
          AND cityHash64(id) % ${SAMPLE_MOD} = 0
    )
SELECT
    src.c AS src_rows,
    dst.c AS dst_rows,
    ifNull(src.h, 0) AS src_checksum,
    ifNull(dst.h, 0) AS dst_checksum,
    (src.c = dst.c AND ifNull(src.h, 0) = ifNull(dst.h, 0)) AS ok
FROM src, dst
SETTINGS use_skip_indexes_if_final = 1;
-- >>> END compare

-- >>> BEGIN drill-down
-- Lists up to 100 keys that differ or exist on one side only, for a window the compare reported as ok=0.
-- join_use_nulls = 1 is required for correctness: by default ClickHouse fills an unmatched FULL OUTER JOIN side with the
-- column's DEFAULT (0 for the UInt64 hash), not NULL — which would make a row missing on one side indistinguishable from
-- a real hash of 0 and leave the `IS NULL` predicates below dead. With it, the absent side is NULL, so `src_hash IS NULL
-- OR dst_hash IS NULL` correctly flags a missing row and prints it as NULL.
SELECT
    key,
    src_hash,
    dst_hash
FROM (
    SELECT
        (workspace_id, project_id, id) AS key,
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
            toString(environment)) AS src_hash
    FROM ${ANALYTICS_DB_DATABASE_NAME}.${OLD_TABLE} FINAL
    WHERE created_at >= toDateTime64('${WINDOW_LO}', 9)
      AND created_at <  toDateTime64('${WINDOW_HI}', 9)
      AND cityHash64(id) % ${SAMPLE_MOD} = 0
) AS s
FULL OUTER JOIN (
    SELECT
        (workspace_id, project_id, id) AS key,
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
            toString(environment)) AS dst_hash
    FROM ${ANALYTICS_DB_DATABASE_NAME}.${NEW_TABLE} FINAL
    WHERE created_at >= toDateTime64('${WINDOW_LO}', 6)
      AND created_at <  toDateTime64('${WINDOW_HI}', 6)
      AND cityHash64(id) % ${SAMPLE_MOD} = 0
) AS d USING (key)
WHERE src_hash != dst_hash
   OR src_hash IS NULL
   OR dst_hash IS NULL
LIMIT 100
SETTINGS join_use_nulls = 1, use_skip_indexes_if_final = 1;
-- >>> END drill-down
