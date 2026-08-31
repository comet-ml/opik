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
-- The window bounds pin 'UTC' because they are derived from a UTC calendar date (verify.sh anchors on
-- toMonday(min(created_at)), and created_at is DateTime64(n, 'UTC')), and because 000001 copies the week under the same
-- UTC bounds. Unpinned they would shift with the server timezone on both sides at once -- self-consistent, so no
-- mismatch appears, while the first and last windows silently stop covering what the backfill actually copied.
--
-- ../verify.sh is the single driver: it reads this file and runs the blocks below, never this file by hand. Which block,
-- and when:
--   * `compare`       once per created_at week (optionally sampled), parsing the single verdict row;
--   * `confirm-keys`  on a week that reported ok=0, to separate a real difference from a superseded-version artifact;
--   * `version-ties`  when confirm-keys returned 0, since that verdict is only sound where no version is tied;
--   * `drill-down`    with --drill-down, on any week that reported ok=0, whatever the two blocks above concluded.
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
        WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
          AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
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
        WHERE created_at >= toDateTime64('${WINDOW_LO}', 6, 'UTC')
          AND created_at <  toDateTime64('${WINDOW_HI}', 6, 'UTC')
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
    WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
      AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
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
    WHERE created_at >= toDateTime64('${WINDOW_LO}', 6, 'UTC')
      AND created_at <  toDateTime64('${WINDOW_HI}', 6, 'UTC')
      AND cityHash64(id) % ${SAMPLE_MOD} = 0
) AS d USING (key)
WHERE src_hash != dst_hash
   OR src_hash IS NULL
   OR dst_hash IS NULL
LIMIT 100
SETTINGS join_use_nulls = 1, use_skip_indexes_if_final = 1;
-- >>> END drill-down

-- >>> BEGIN confirm-keys
-- For a window the compare reported ok=0: decide whether the difference is REAL, or an artifact of
-- windowing on created_at under FINAL. Returns one number -- the count of keys that GENUINELY differ.
--
-- Why the artifact exists. FINAL collapses ReplacingMergeTree versions only among the parts a query
-- actually reads, and `created_at` is not in the sorting key, so a created_at predicate can select the
-- part holding a SUPERSEDED version while excluding the part holding the winner. With no winner in the
-- read set there is nothing to collapse against, so the stale row is returned as though it were live.
-- Whether that happens depends on part layout, which differs between the unpartitioned source and the
-- id_at-partitioned successor, so one side can surface a superseded row the other does not -- and the
-- window "mismatches" even though both tables hold byte-identical live data. Any id written more than
-- once, far enough apart to land in different created_at weeks, can trigger it; trace ids are
-- client-supplied, so a re-sent id is ordinary rather than exotic.
--
-- LIMITATION: this cannot resolve a VERSION TIE, so a 0 here is conclusive only where none exists. last_updated_at is
-- the ReplacingMergeTree version column, so when two or more rows for a key carry the same value there is nothing left
-- to rank them by: FINAL picks arbitrarily, and the two tables' part layouts differ, so each side may or may not land
-- on the same row. Arbitrary cuts BOTH ways, and the second is the dangerous one:
--   * the picks differ -> the key is reported in genuinely_differing_keys even where both tables hold the same data;
--   * the picks coincide -> the key is confirmed as matching even if one side is MISSING a version, which is a real
--     copy gap, reading as a pass.
-- The `version-ties` block below answers whether that applies to this window, and the driver runs it exactly where the
-- question arises -- when this block returns 0. Deciding a tied key still needs each side's full version SET, which
-- neither block reads; the runbook's triage section carries that read.
--
-- Why this re-check is trustworthy WHERE VERSIONS DIFFER. It filters ONLY on (workspace_id, project_id, id) -- the sorting key,
-- which IS the dedup key. That predicate cannot hide a version from FINAL: every part contributes the
-- granules holding the key, so FINAL always sees all versions of it and returns the true winner. The
-- window bounds are used only to pick the candidate keys, never to decide the verdict.
--
-- 0  = every differing key has identical live rows on both sides -> a superseded-version artifact rather than a data
--      difference (the live row is still compared, and must match, in the week its winner lands in) -- PROVIDED the
--      `version-ties` block reports none, which is what the driver checks next.
-- >0 = that many keys genuinely differ -> real fidelity failure.
WITH
    diff_keys AS (
        SELECT key
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
            WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
              AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
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
            WHERE created_at >= toDateTime64('${WINDOW_LO}', 6, 'UTC')
              AND created_at <  toDateTime64('${WINDOW_HI}', 6, 'UTC')
              AND cityHash64(id) % ${SAMPLE_MOD} = 0
        ) AS d USING (key)
        WHERE src_hash != dst_hash OR src_hash IS NULL OR dst_hash IS NULL
    ),
    -- Deliberately NOT limited: a verdict drawn from a truncated key set could call a window an artifact
    -- while an unexamined key held a real difference. A genuinely broken window makes this heavy, which is
    -- the right trade -- that is the case you want to stop on anyway.
    src_live AS (
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
        WHERE (workspace_id, project_id, id) IN (SELECT key FROM diff_keys)
    ),
    dst_live AS (
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
        WHERE (workspace_id, project_id, id) IN (SELECT key FROM diff_keys)
    )
SELECT count() AS unresolved
FROM src_live AS s
FULL OUTER JOIN dst_live AS d USING (key)
WHERE src_hash != dst_hash
   OR src_hash IS NULL
   OR dst_hash IS NULL
SETTINGS join_use_nulls = 1, use_skip_indexes_if_final = 1;
-- >>> END confirm-keys

-- >>> BEGIN version-ties
-- For a window `confirm-keys` reported as 0: is that 0 decidable? Returns one row, src_version_ties dst_version_ties --
-- per side, the number of keys in this window whose newest last_updated_at is shared by more than one row. Both 0 means
-- every key had a unique winner, so FINAL's choice was forced and the artifact verdict stands. Non-zero means FINAL
-- chose arbitrarily somewhere in this window, and ../verify.sh reports it as INCONCLUSIVE instead of passing it.
--
-- A SEPARATE statement, and scoped to the WINDOW rather than to confirm-keys' differing keys, which would be the
-- tighter question. Both follow from the same constraint: confirm-keys builds its answer from CTEs that ClickHouse
-- inlines rather than materializes, so referencing them from an aggregate here re-runs its FULL OUTER JOIN per
-- reference, and that plan does not complete. Keeping this independent also means it is only paid for when it is
-- needed, which is when confirm-keys returns 0.
--
-- The counts are therefore an UPPER BOUND: a tie elsewhere in the window counts too, and can make a window
-- undecidable whose differing keys were all decidable. That direction is the safe one, and a tie requires a key
-- written twice with an identical last_updated_at.
--
-- NO FINAL, deliberately: the question is how many physical rows share the newest version, which is precisely what
-- FINAL would have to choose between -- under FINAL they collapse to one and every count reads 0. The deleted-row mask
-- still applies by default, so this sees the same rows FINAL does. Same window and sample predicates as `compare`, so
-- the read prunes the same partitions.
SELECT
    (
        SELECT count()
        FROM (
            SELECT argMax(rows_at_version, version) AS rows_at_newest
            FROM (
                SELECT
                    (workspace_id, project_id, id) AS key,
                    last_updated_at AS version,
                    count() AS rows_at_version
                FROM ${ANALYTICS_DB_DATABASE_NAME}.${OLD_TABLE}
                WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
                  AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
                  AND cityHash64(id) % ${SAMPLE_MOD} = 0
                GROUP BY key, version
            )
            GROUP BY key
        )
        WHERE rows_at_newest > 1
    ) AS src_version_ties,
    (
        SELECT count()
        FROM (
            SELECT argMax(rows_at_version, version) AS rows_at_newest
            FROM (
                SELECT
                    (workspace_id, project_id, id) AS key,
                    last_updated_at AS version,
                    count() AS rows_at_version
                FROM ${ANALYTICS_DB_DATABASE_NAME}.${NEW_TABLE}
                WHERE created_at >= toDateTime64('${WINDOW_LO}', 6, 'UTC')
                  AND created_at <  toDateTime64('${WINDOW_HI}', 6, 'UTC')
                  AND cityHash64(id) % ${SAMPLE_MOD} = 0
                GROUP BY key, version
            )
            GROUP BY key
        )
        WHERE rows_at_newest > 1
    ) AS dst_version_ties;
-- >>> END version-ties
