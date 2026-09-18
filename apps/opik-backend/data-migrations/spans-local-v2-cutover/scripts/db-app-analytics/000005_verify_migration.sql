-- runbook spans-local-v2-cutover — QA: normalized fidelity compare of one created_at window (reference statements)
--
-- Proves the copy altered no data by comparing a NORMALIZED fingerprint of the deduped, live rows on the old-schema and
-- new-schema tables. The rows are not byte-identical (end_time NULL -> epoch sentinel, ttft NULL -> NaN sentinel,
-- timestamps nanosecond -> microsecond, parent_span_id String -> FixedString(36), usage Int32 -> Int64), so each side is
-- canonicalized to the same value for a faithfully-migrated row:
--   * timestamps as their microsecond epoch (source ns truncated to us, matching the copy);
--   * absent end_time -> 0 (source NULL; dest epoch);
--   * absent ttft -> the token 'nan' (source NULL; dest NaN);
--   * enums / ids / LowCardinality / Decimal via toString;
--   * tags joined with a '\x1f' (ASCII Unit Separator) delimiter: the delimiter is what makes a tag-BOUNDARY change
--     detectable — without it ['a','b'] and ['ab'] both concatenate to 'ab' and hash identically. \x1f is a C0 control
--     char purpose-built as a field separator that real (printable) tag text never contains, so it cannot collide with
--     tag content the way ',' or ' ' could;
--   * usage canonicalized as sorted `key \x1e value` pairs joined on '\x1f'. A Map has no guaranteed key order, so the
--     sort is what makes the hash stable; \x1e (Record Separator) inside a pair and \x1f between pairs keeps a key/value
--     boundary shift detectable for the same reason it does for tags. toString of an Int32 and of the Int64 it widens to
--     produce identical text, so the widening is invisible here — which is correct: it is lossless;
--   * parent_span_id canonicalized to a plain String on both sides. On the source that means mapping any value that is
--     not exactly 36 bytes to '' — the SAME normalization 000001's projection applies, because SpanDAO's PARTIAL_INSERT
--     can store a 40-character poison value there and the destination column cannot hold it (see 000001's header). On
--     the destination, CAST(... AS String) trims the FixedString's NUL padding, so the empty (root-span) sentinel reads
--     back as ''. Hashing the source's raw bytes instead would report every such row as a mismatch, when in fact the
--     copy did exactly what it was asked to.
-- Each row hash includes the `id`, so a swap cannot cancel.
--
-- THE DEDUP KEYS DIFFER, AND THAT IS THE ONE STRUCTURAL DIFFERENCE FROM THE TRACES COMPARE. `spans` orders by
-- (workspace_id, project_id, trace_id, parent_span_id, id); spans_local_v2 drops parent_span_id and orders by
-- (workspace_id, project_id, trace_id, id) (migration 000115 explains why). So the source can legitimately hold TWO live
-- rows for one span — SpanDAO's PARTIAL_INSERT writes a changed parent_span_id, and a mutable sort-key column means the
-- two versions sort to different keys and never merge — where the destination holds ONE, the newest last_updated_at
-- winning. A plain FINAL on each side would therefore report a faithful copy as a count mismatch on every such key.
--
-- The compare closes that by reducing the OLD side TWICE: FINAL collapses each source key, then
-- `argMax(<fingerprint>, last_updated_at) GROUP BY (workspace_id, project_id, trace_id, id)` collapses across
-- parent_span_id to the destination's key, picking the same winner ReplacingMergeTree picked. The NEW side needs only
-- FINAL, because its own key IS the comparison key. The asymmetry is deliberate and load-bearing; do not "tidy" it by
-- making both sides symmetric.
--
-- WHERE THE TWO WINNERS CAN STILL DISAGREE: when a key's newest last_updated_at is carried by more than one distinct
-- row, argMax and ReplacingMergeTree each pick arbitrarily and may differ. That is exactly the version tie the
-- `version-ties` block already exists to detect — and on spans it has a second, spans-only cause (the same span at one
-- version under two parents) on top of the one traces had. The block below groups by the destination key on both sides,
-- so it catches both causes with no extra machinery.
--
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
-- max_bytes_before_external_group_by IS CARRIED ON EVERY BLOCK BELOW, which the traces compare did not need. The
-- GROUP BY the old side now performs is over every key in the window, and a busy spans week holds far more keys than a
-- traces week did; letting it spill to disk is what keeps a fidelity gate from failing on memory rather than on data.
--
-- The row fingerprint is repeated in every block below, and again in 000006_verify_reconciliation.sql, so that each
-- block stays a statement that can be read and run on its own. KEEP THEM IN STEP BY HAND: two tools hashing the same
-- rows differently would disagree about which rows match, and nothing would say so. Change one arm, change all of them.
--
-- ../verify.sh is the single driver: it reads this file and runs the blocks below, never this file by hand. Which block,
-- and when:
--   * `compare`       once per created_at week (optionally sampled), parsing the single verdict row;
--   * `confirm-keys`  on a week that reported ok=0, to separate a real difference from a superseded-version artifact;
--   * `version-ties`  when confirm-keys returned 0, since that verdict is only sound where no version is tied;
--   * `drill-down`    with --drill-down, on any week that reported ok=0, whatever confirm-keys and version-ties made of it.
--
-- OLD_TABLE is the old-schema table (Nullable, nanosecond, parent_span_id in the sort key) and NEW_TABLE the new-schema
-- one (sentinels, microsecond, parent_span_id out of the sort key). Before the EXCHANGE: OLD_TABLE=spans,
-- NEW_TABLE=spans_local_v2 (the successor being built). After it, `spans` is the new schema and the old data is parked
-- as `spans_pre_cutover_backup` — set OLD_TABLE=spans_pre_cutover_backup, NEW_TABLE=spans. After a stage B/C rollback
-- the old-schema side is the restored original (`spans`) and the new-schema side the parked successor
-- (`spans_post_rollback_backup`). SAMPLE_MOD=1 compares every row; SAMPLE_MOD=100 compares a deterministic ~1% id sample
-- (same rows on both sides) when a full pass is infeasible — and on a table this size it often is.

-- >>> BEGIN compare
WITH
    src AS (
        SELECT
            count() AS c,
            sum(h)  AS h
        FROM (
            SELECT argMax(cityHash64(
                id,
                workspace_id,
                toString(project_id),
                toString(trace_id),
                if(length(parent_span_id) = 36, parent_span_id, ''),
                name,
                toString(type),
                toUnixTimestamp64Micro(toDateTime64(start_time, 6)),
                coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0)),
                input,
                output,
                metadata,
                arrayStringConcat(tags, '\x1f'),
                arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
                toUnixTimestamp64Micro(toDateTime64(created_at, 6)),
                toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)),
                created_by,
                last_updated_by,
                toString(model),
                toString(provider),
                toString(total_estimated_cost),
                toString(total_estimated_cost_version),
                error_info,
                truncation_threshold,
                input_slim,
                output_slim,
                if(ttft IS NULL, 'nan', toString(ttft)),
                toString(source),
                toString(environment)), last_updated_at) AS h
            FROM ${ANALYTICS_DB_DATABASE_NAME}.${OLD_TABLE} FINAL
            WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
              AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
              AND cityHash64(id) % ${SAMPLE_MOD} = 0
            GROUP BY workspace_id, project_id, trace_id, id
        )
    ),
    dst AS (
        SELECT
            count() AS c,
            sum(cityHash64(
                id,
                workspace_id,
                toString(project_id),
                toString(trace_id),
                CAST(parent_span_id AS String),
                name,
                toString(type),
                toUnixTimestamp64Micro(start_time),
                toUnixTimestamp64Micro(end_time),
                input,
                output,
                metadata,
                arrayStringConcat(tags, '\x1f'),
                arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
                toUnixTimestamp64Micro(created_at),
                toUnixTimestamp64Micro(last_updated_at),
                created_by,
                last_updated_by,
                toString(model),
                toString(provider),
                toString(total_estimated_cost),
                toString(total_estimated_cost_version),
                error_info,
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
SETTINGS use_skip_indexes_if_final = 1, max_bytes_before_external_group_by = 4000000000;
-- >>> END compare

-- >>> BEGIN drill-down
-- Lists up to 100 keys that differ or exist on one side only, for a window the compare reported as ok=0. The key is the
-- DESTINATION's (workspace_id, project_id, trace_id, id), which is also what the source side is reduced to — so a key
-- printed here names one span, not one (span, parent) pair.
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
        (workspace_id, project_id, trace_id, id) AS key,
        argMax(cityHash64(
            id,
            workspace_id,
            toString(project_id),
            toString(trace_id),
            if(length(parent_span_id) = 36, parent_span_id, ''),
            name,
            toString(type),
            toUnixTimestamp64Micro(toDateTime64(start_time, 6)),
            coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0)),
            input,
            output,
            metadata,
            arrayStringConcat(tags, '\x1f'),
            arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
            toUnixTimestamp64Micro(toDateTime64(created_at, 6)),
            toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)),
            created_by,
            last_updated_by,
            toString(model),
            toString(provider),
            toString(total_estimated_cost),
            toString(total_estimated_cost_version),
            error_info,
            truncation_threshold,
            input_slim,
            output_slim,
            if(ttft IS NULL, 'nan', toString(ttft)),
            toString(source),
            toString(environment)), last_updated_at) AS src_hash
    FROM ${ANALYTICS_DB_DATABASE_NAME}.${OLD_TABLE} FINAL
    WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
      AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
      AND cityHash64(id) % ${SAMPLE_MOD} = 0
    GROUP BY key
) AS s
FULL OUTER JOIN (
    SELECT
        (workspace_id, project_id, trace_id, id) AS key,
        cityHash64(
            id,
            workspace_id,
            toString(project_id),
            toString(trace_id),
            CAST(parent_span_id AS String),
            name,
            toString(type),
            toUnixTimestamp64Micro(start_time),
            toUnixTimestamp64Micro(end_time),
            input,
            output,
            metadata,
            arrayStringConcat(tags, '\x1f'),
            arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
            toUnixTimestamp64Micro(created_at),
            toUnixTimestamp64Micro(last_updated_at),
            created_by,
            last_updated_by,
            toString(model),
            toString(provider),
            toString(total_estimated_cost),
            toString(total_estimated_cost_version),
            error_info,
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
SETTINGS join_use_nulls = 1, use_skip_indexes_if_final = 1, max_bytes_before_external_group_by = 4000000000;
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
-- once, far enough apart to land in different created_at weeks, can trigger it; span ids are
-- client-supplied, so a re-sent id is ordinary rather than exotic.
--
-- WHY THE RE-CHECK'S PREDICATE IS SOUND HERE EVEN THOUGH IT IS NOT A SOURCE-KEY PREFIX. It filters on
-- (workspace_id, project_id, trace_id, id). On the destination that IS the sorting key. On the source the sorting key is
-- (workspace_id, project_id, trace_id, parent_span_id, id), so this predicate bounds the leading three columns and
-- filters `id` per row, leaving parent_span_id unbounded — which is precisely what is wanted: every physical version of
-- the span, under whatever parent, is in the read set. FINAL then returns each source key's true winner and the argMax
-- picks between them, exactly as in `compare`. The window bounds are used only to pick the candidate keys, never to
-- decide the verdict. The cost is that the source-side read is granule-filtered rather than key-pruned on `id`; on a
-- broken window that is expensive, which is the right trade -- that is the case you want to stop on anyway.
--
-- LIMITATION: this cannot resolve a VERSION TIE, so a 0 here is conclusive only where none exists. last_updated_at is
-- the ReplacingMergeTree version column, so when two or more rows for a key carry the same value there is nothing left
-- to rank them by: FINAL and argMax each pick arbitrarily, and the two tables' part layouts differ, so each side may or
-- may not land on the same row. On spans there is a second cause with the same consequence: the same span at one version
-- under two parent_span_id values. Arbitrary cuts BOTH ways, and the second is the dangerous one:
--   * the picks differ -> the key is reported in genuinely_differing_keys even where both tables hold the same data;
--   * the picks coincide -> the key is confirmed as matching even if one side is MISSING a version, which is a real
--     copy gap, reading as a pass.
-- The `version-ties` block below answers whether that applies to this window, and the driver runs it exactly where the
-- question arises -- when this block returns 0. Deciding a tied key still needs each side's full version SET, which
-- neither block reads; the runbook's triage section carries that read.
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
                (workspace_id, project_id, trace_id, id) AS key,
                argMax(cityHash64(
            id,
            workspace_id,
            toString(project_id),
            toString(trace_id),
            if(length(parent_span_id) = 36, parent_span_id, ''),
            name,
            toString(type),
            toUnixTimestamp64Micro(toDateTime64(start_time, 6)),
            coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0)),
            input,
            output,
            metadata,
            arrayStringConcat(tags, '\x1f'),
            arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
            toUnixTimestamp64Micro(toDateTime64(created_at, 6)),
            toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)),
            created_by,
            last_updated_by,
            toString(model),
            toString(provider),
            toString(total_estimated_cost),
            toString(total_estimated_cost_version),
            error_info,
            truncation_threshold,
            input_slim,
            output_slim,
            if(ttft IS NULL, 'nan', toString(ttft)),
            toString(source),
            toString(environment)), last_updated_at) AS src_hash
            FROM ${ANALYTICS_DB_DATABASE_NAME}.${OLD_TABLE} FINAL
            WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
              AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
              AND cityHash64(id) % ${SAMPLE_MOD} = 0
            GROUP BY key
        ) AS s
        FULL OUTER JOIN (
            SELECT
                (workspace_id, project_id, trace_id, id) AS key,
                cityHash64(
            id,
            workspace_id,
            toString(project_id),
            toString(trace_id),
            CAST(parent_span_id AS String),
            name,
            toString(type),
            toUnixTimestamp64Micro(start_time),
            toUnixTimestamp64Micro(end_time),
            input,
            output,
            metadata,
            arrayStringConcat(tags, '\x1f'),
            arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
            toUnixTimestamp64Micro(created_at),
            toUnixTimestamp64Micro(last_updated_at),
            created_by,
            last_updated_by,
            toString(model),
            toString(provider),
            toString(total_estimated_cost),
            toString(total_estimated_cost_version),
            error_info,
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
            (workspace_id, project_id, trace_id, id) AS key,
            argMax(cityHash64(
            id,
            workspace_id,
            toString(project_id),
            toString(trace_id),
            if(length(parent_span_id) = 36, parent_span_id, ''),
            name,
            toString(type),
            toUnixTimestamp64Micro(toDateTime64(start_time, 6)),
            coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0)),
            input,
            output,
            metadata,
            arrayStringConcat(tags, '\x1f'),
            arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
            toUnixTimestamp64Micro(toDateTime64(created_at, 6)),
            toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)),
            created_by,
            last_updated_by,
            toString(model),
            toString(provider),
            toString(total_estimated_cost),
            toString(total_estimated_cost_version),
            error_info,
            truncation_threshold,
            input_slim,
            output_slim,
            if(ttft IS NULL, 'nan', toString(ttft)),
            toString(source),
            toString(environment)), last_updated_at) AS src_hash
        FROM ${ANALYTICS_DB_DATABASE_NAME}.${OLD_TABLE} FINAL
        WHERE (workspace_id, project_id, trace_id, id) IN (SELECT key FROM diff_keys)
        GROUP BY key
    ),
    dst_live AS (
        SELECT
            (workspace_id, project_id, trace_id, id) AS key,
            cityHash64(
            id,
            workspace_id,
            toString(project_id),
            toString(trace_id),
            CAST(parent_span_id AS String),
            name,
            toString(type),
            toUnixTimestamp64Micro(start_time),
            toUnixTimestamp64Micro(end_time),
            input,
            output,
            metadata,
            arrayStringConcat(tags, '\x1f'),
            arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
            toUnixTimestamp64Micro(created_at),
            toUnixTimestamp64Micro(last_updated_at),
            created_by,
            last_updated_by,
            toString(model),
            toString(provider),
            toString(total_estimated_cost),
            toString(total_estimated_cost_version),
            error_info,
            truncation_threshold,
            input_slim,
            output_slim,
            if(isNaN(ttft), 'nan', toString(ttft)),
            toString(source),
            toString(environment)) AS dst_hash
        FROM ${ANALYTICS_DB_DATABASE_NAME}.${NEW_TABLE} FINAL
        WHERE (workspace_id, project_id, trace_id, id) IN (SELECT key FROM diff_keys)
    )
SELECT count() AS unresolved
FROM src_live AS s
FULL OUTER JOIN dst_live AS d USING (key)
WHERE src_hash != dst_hash
   OR src_hash IS NULL
   OR dst_hash IS NULL
SETTINGS join_use_nulls = 1, use_skip_indexes_if_final = 1, max_bytes_before_external_group_by = 4000000000;
-- >>> END confirm-keys

-- >>> BEGIN version-ties
-- For a window `confirm-keys` reported as 0: is that 0 decidable? Returns one row, src_version_ties dst_version_ties --
-- per side, the number of candidate keys whose newest last_updated_at is carried by MORE THAN ONE DISTINCT ROW. Both
-- being 0 means every candidate had a forced winner, so the artifact verdict stands; non-zero means the winner-picking
-- chose between rows that actually differ, and ../verify.sh reports the window INCONCLUSIVE instead of passing it.
--
-- BOTH SIDES GROUP BY THE DESTINATION KEY (workspace_id, project_id, trace_id, id), which is what makes this block
-- catch the spans-only tie as well as the traces one. Two distinct causes, one detector:
--   * the same span written twice at one last_updated_at with differing content — the cause traces had;
--   * the same span at one last_updated_at under two parent_span_id values — SpanDAO's PARTIAL_INSERT can write a
--     changed parent, and a mutable sort-key column means those two rows never merge on the source. To the destination
--     they are one key, so ReplacingMergeTree picks arbitrarily between them; to the compare's argMax they are likewise
--     one key. Grouping by the source's own key here would hide this case entirely, which is why the key is the
--     destination's on BOTH sides.
--
-- DISTINCT CONTENT, not row count, and that distinction is load-bearing. The cutover itself puts several physical rows
-- at one version on the destination: 000002's delta re-copies every row written during the backfill window, and for a
-- row that was not modified in between the copy carries the IDENTICAL last_updated_at. Those duplicates collapse only
-- when a merge runs, and verify.sh runs before the EXCHANGE, on the recent partitions where they are least likely to
-- have merged. Counting physical rows would therefore report a tie on a faithful copy and fail the gate on the normal
-- path. Picking either of two byte-identical rows changes no verdict, so only differing content counts here.
--
-- The fingerprint is the same normalization `compare` uses, so "differ" means differ in the sense the gate cares about:
-- sentinel, precision, parent_span_id-width and usage-width differences between the two schemas are not differences.
--
-- TWO LIMITS, both deliberate, and neither is a guarantee this block makes.
--
-- It reads THIS WINDOW's rows, while confirm-keys picks candidates from the window and then ranks each key's versions
-- wherever they landed. So a tie whose differing rows sit in different created_at weeks is not detected here. Following
-- confirm-keys exactly would mean reading every version of every candidate key with no time predicate -- an unpruned
-- read per artifact window, and artifact windows are the common outcome rather than the rare one. Reusing its diff_keys
-- CTE instead is not available: ClickHouse inlines rather than materializes it, and an aggregate over it does not plan.
-- The window scope is therefore a cost decision, and the residual gap is stated rather than papered over.
--
-- Its candidates are every key in the window, not only those that differed, so within the window the counts are an
-- upper bound: a tie on a key that did not differ can still make a window undecidable.
--
-- SCOPE OF THE GUARANTEE, which is narrower than it looks. This runs only where `compare` returned ok=0 and
-- `confirm-keys` returned 0. The direction this block calls dangerous -- the picks coincide and a key is confirmed as
-- matching while one side is missing a version -- can also produce ok=1 directly, and such a window never reaches here.
-- So a plain PASSED does not carry a tie guarantee; only an "OK -- superseded-version artifact" verdict does. Covering
-- ok=1 windows would mean this read on every window, which is why it is not done by default.
--
-- NO FINAL. The question is how many distinct contents share the newest version, which is what the winner-picking would
-- have to choose between; under FINAL they collapse to one and every count reads 0. Lightweight deletes are still
-- excluded, as they are under FINAL, because apply_deleted_mask applies without it. is_deleted tombstones are NOT: that
-- column is honored only under FINAL, so a tombstoned key's rows are still counted on the destination, and the source
-- table has no such column at all. Today the only delete path is the lightweight DELETE in 000002, so the two sides agree.
WITH
    src_ties AS (
            SELECT count() AS n
            FROM (
                SELECT key, argMax(distinct_at_version, version) AS distinct_at_newest
                FROM (
                    SELECT
                        (workspace_id, project_id, trace_id, id) AS key,
                        last_updated_at AS version,
                        uniqExact(cityHash64(
                            id,
                            workspace_id,
                            toString(project_id),
                            toString(trace_id),
                            if(length(parent_span_id) = 36, parent_span_id, ''),
                            name,
                            toString(type),
                            toUnixTimestamp64Micro(toDateTime64(start_time, 6)),
                            coalesce(toUnixTimestamp64Micro(toDateTime64(end_time, 6)), toInt64(0)),
                            input,
                            output,
                            metadata,
                            arrayStringConcat(tags, '\x1f'),
                            arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
                            toUnixTimestamp64Micro(toDateTime64(created_at, 6)),
                            toUnixTimestamp64Micro(toDateTime64(last_updated_at, 6)),
                            created_by,
                            last_updated_by,
                            toString(model),
                            toString(provider),
                            toString(total_estimated_cost),
                            toString(total_estimated_cost_version),
                            error_info,
                            truncation_threshold,
                            input_slim,
                            output_slim,
                            if(ttft IS NULL, 'nan', toString(ttft)),
                            toString(source),
                            toString(environment))) AS distinct_at_version
                    FROM ${ANALYTICS_DB_DATABASE_NAME}.${OLD_TABLE}
                    WHERE created_at >= toDateTime64('${WINDOW_LO}', 9, 'UTC')
                      AND created_at <  toDateTime64('${WINDOW_HI}', 9, 'UTC')
                      AND cityHash64(id) % ${SAMPLE_MOD} = 0
                    GROUP BY key, version
                )
                GROUP BY key
            )
            WHERE distinct_at_newest > 1
    ),
    dst_ties AS (
            SELECT count() AS n
            FROM (
                SELECT key, argMax(distinct_at_version, version) AS distinct_at_newest
                FROM (
                    SELECT
                        (workspace_id, project_id, trace_id, id) AS key,
                        last_updated_at AS version,
                        uniqExact(cityHash64(
                            id,
                            workspace_id,
                            toString(project_id),
                            toString(trace_id),
                            CAST(parent_span_id AS String),
                            name,
                            toString(type),
                            toUnixTimestamp64Micro(start_time),
                            toUnixTimestamp64Micro(end_time),
                            input,
                            output,
                            metadata,
                            arrayStringConcat(tags, '\x1f'),
                            arrayStringConcat(arrayMap(k -> concat(k, '\x1e', toString(usage[k])), arraySort(mapKeys(usage))), '\x1f'),
                            toUnixTimestamp64Micro(created_at),
                            toUnixTimestamp64Micro(last_updated_at),
                            created_by,
                            last_updated_by,
                            toString(model),
                            toString(provider),
                            toString(total_estimated_cost),
                            toString(total_estimated_cost_version),
                            error_info,
                            truncation_threshold,
                            input_slim,
                            output_slim,
                            if(isNaN(ttft), 'nan', toString(ttft)),
                            toString(source),
                            toString(environment))) AS distinct_at_version
                    FROM ${ANALYTICS_DB_DATABASE_NAME}.${NEW_TABLE}
                    WHERE created_at >= toDateTime64('${WINDOW_LO}', 6, 'UTC')
                      AND created_at <  toDateTime64('${WINDOW_HI}', 6, 'UTC')
                      AND cityHash64(id) % ${SAMPLE_MOD} = 0
                    GROUP BY key, version
                )
                GROUP BY key
            )
            WHERE distinct_at_newest > 1
    )
SELECT
    src_ties.n AS src_version_ties,
    dst_ties.n AS dst_version_ties
FROM src_ties, dst_ties
-- Neither setting is a query-level cap. max_rows_to_read = 0 removes any row limit a settings profile imposes: this
-- read is not truncatable -- it either covers the window's physical versions or throws -- and a throw would fail a gate
-- that could otherwise answer. max_bytes_before_external_group_by lets the GROUP BY spill to disk rather than hit the
-- memory limit.
--
-- Know what the override authorises, because NEITHER side prunes partitions on created_at. The source is unpartitioned
-- altogether, and the successor partitions on an id_at-derived expression, which a created_at predicate cannot prune.
-- The window narrows the read through the created_at minmax skip index instead, which drops granules rather than parts,
-- and this block runs without FINAL over every physical version the window still selects, on both sides, in the common
-- case rather than the rare one: an artifact verdict is the normal pre-EXCHANGE outcome. So the cost is a full read of
-- the surviving granules per differing window, and on spans that is a read of very wide rows -- --sample-mod is the
-- lever that bounds it, and on this table it is not optional advice.
SETTINGS max_rows_to_read = 0, max_bytes_before_external_group_by = 4000000000;
-- >>> END version-ties
