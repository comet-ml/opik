"""Seed historical `spans` rows spread across several weeks, so the cutover backfill has multi-week data to iterate.

Writes straight to ClickHouse: the ingestion API stamps `created_at` server-side (read-only), so it cannot produce
back-dated rows, and the backfill slices the source by `created_at`. Each row gets a `created_at` in its week and a
UUIDv7 `id` minted at the same instant, so `id_at` (the destination weekly partition) matches `created_at` — the shape
real accumulated history has.

FOUR POPULATIONS THIS SEEDER CAN PRODUCE ON DEMAND, AND WHY EACH MATTERS. None of them appears by chance in a short
rehearsal, and each leaves a distinct part of the spans cutover unexercised if omitted:

  --bad-ids N        ids minted in the far future (year ~2201, the litellm-bug shape) while `created_at` stays real.
                     These are what spread the destination's weekly partitions across centuries, and so what
                     `max_partitions_per_insert_block` has to permit. Without them that cap is never exercised.
  --non-v7-ids N     ids that are NOT UUIDv7 (a v4). `UUIDv7ToDateTime` returns 1970-01-01 for those — no throw — so
                     they land in the EPOCH week, at the opposite end from the far-future ones. Migration 000115's
                     header records that non-v7 ids are COMMONER than the litellm ones in production, and they are why
                     any audit or predicate written as `id_at > now()` would miss them entirely. Without
                     them, a bug that only handled the far-future direction would pass.
  --parent-poison N  spans whose `parent_span_id` is the 40-character `leftPad('', 40, '*')` value SpanDAO's
                     PARTIAL_INSERT writes when a span's parent changes. `spans.parent_span_id` is a String so the
                     value is storable; `spans_local_v2.parent_span_id` is FixedString(36) and CANNOT hold it. Without
                     these rows the copy's length guard is never exercised, and the failure it prevents
                     (TOO_LARGE_STRING_SIZE aborting a whole window) is not one to discover in production.
  --split-parents N  the same span id written TWICE under two different `parent_span_id` values at the SAME
                     `last_updated_at`. The source keeps both (parent_span_id is in its sort key and is mutable); the
                     destination collapses them to one and picks arbitrarily. This is the spans-only version tie, and
                     it is what `verify.sh` must report as INCONCLUSIVE rather than silently deciding.

Every migrated column is populated with VARIED, realistic values so the fidelity compare (verify.sh) actually exercises
each column — an empty column would match on both sides even if the copy dropped it. Timestamps are written at true
NANOSECOND precision for `start_time`/`created_at` (those source columns are DateTime64(9), like real `now64(9)` rows),
so the ns->us truncation the successor performs is exercised, not skipped; `last_updated_at` is written at microsecond
precision because migration 000025 already narrowed that column to DateTime64(6). A share of rows leave `end_time` /
`ttft` NULL to exercise the NULL->sentinel (epoch / NaN) normalization, and `usage` carries real token-shaped Int32
values so the Int32->Int64 widening and its canonical fingerprint encoding are covered.

Spans are seeded under real `trace_id`s that the delete generator can actually delete — a span delete is only ever the
cascade of a trace delete — so `--traces-per-week` controls how many distinct traces the week's spans hang off.

Prerequisites: `./opik.sh --port-mapping` (ClickHouse on localhost:8123) and `OPIK_URL_OVERRIDE` pointing at the same
install. Run `python seed_history.py --help` for options.
"""

import json
import random
import uuid
from datetime import datetime, timedelta

import click

from _common import (BAD_ID_INSTANT, DEFAULT_PROJECT, LOGGER, discover_workspace_and_project, json_payload,
                     make_ch_client, make_opik_client, mint_uuid7, ns_ticks, random_text, us_ticks, utcnow)

# Every base column the backfill copies (MATERIALIZED columns like id_at, duration and the *_length counters are
# recomputed by ClickHouse and excluded). Order matches the tuple built in _row(), and the list matches 000001's
# INSERT column list — if one changes, change both.
COLUMNS = [
    "id",
    "workspace_id",
    "project_id",
    "trace_id",
    "parent_span_id",
    "name",
    "type",
    "start_time",
    "end_time",
    "input",
    "output",
    "metadata",
    "tags",
    "usage",
    "created_at",
    "last_updated_at",
    "created_by",
    "last_updated_by",
    "model",
    "provider",
    "total_estimated_cost",
    "total_estimated_cost_version",
    "error_info",
    "truncation_threshold",
    "input_slim",
    "output_slim",
    "ttft",
    "source",
    "environment",
]

# What SpanDAO's PARTIAL_INSERT writes into parent_span_id when a span's parent changes: leftPad('', 40, '*').
# FORTY characters, which spans_local_v2's FixedString(36) cannot hold.
PARENT_POISON = "*" * 40

_TAG_POOL = ["prod", "llm", "rag", "eval", "v1", "v2", "canary", "batch", "stream", "agent"]
_SOURCES = ["sdk", "experiment", "playground", "optimization", "evaluator"]
_TYPES = ["general", "tool", "llm", "guardrail"]
_ENVIRONMENTS = ["production", "staging", "dev", ""]
_USERS = ["alice", "bob", "carol", "service-account", "ci-runner"]
_MODELS = ["gpt-4o", "claude-sonnet-4", "llama-3.1-70b", ""]
_PROVIDERS = ["openai", "anthropic", "bedrock", ""]


def _usage() -> dict:
    """Token counts, as Map(String, Int32) on the source. 30% empty, so the empty-map arm of the fingerprint runs."""
    if random.random() < 0.3:
        return {}
    prompt = random.randint(1, 40_000)
    completion = random.randint(1, 8_000)
    return {
        "prompt_tokens": prompt,
        "completion_tokens": completion,
        "total_tokens": prompt + completion,
    }


def _row(
    created_at_dt: datetime,
    span_id: str,
    trace_id: str,
    workspace_id: str,
    project_id: str,
    parent_span_id: str,
    last_updated_at_dt: datetime = None,
) -> tuple:
    created_ns = ns_ticks(created_at_dt)
    # 30% leave end_time NULL (the "not ended" case -> epoch sentinel on the successor); else a real duration.
    end_ns = None if random.random() < 0.3 else created_ns + random.randint(5_000_000, 3_000_000_000)
    # 70% leave ttft NULL (-> NaN sentinel). Higher than the traces seeder's 40% on purpose: ttft is a per-LLM-call
    # measurement and most spans are not LLM calls, which is the shape the rollback's sentinel repair has to face.
    ttft = None if random.random() < 0.7 else round(random.uniform(0.005, 5.0), 6)
    payload_in, payload_out = json_payload("prompt"), json_payload("completion")
    return (
        span_id,
        workspace_id,
        project_id,
        trace_id,
        parent_span_id,
        "seed-span",
        random.choice(_TYPES),
        created_ns,  # start_time ~ created_at
        end_ns,
        payload_in,
        payload_out,
        json.dumps({"model": random.choice(_MODELS),
                    "temperature": round(random.random(), 3), "max_tokens": random.randint(16, 4000)}),
        random.sample(_TAG_POOL, random.randint(0, 4)),
        _usage(),
        created_ns,  # created_at — the backfill slice column, at ns precision
        # last_updated_at (us; migration 000025 narrowed this column to DateTime64(6)) ~= created_at, so the delta never
        # re-copies these historical rows. --split-parents overrides it to a SHARED value, which is the tie.
        us_ticks(last_updated_at_dt or created_at_dt),
        random.choice(_USERS),
        random.choice(_USERS),
        random.choice(_MODELS),
        random.choice(_PROVIDERS),
        round(random.uniform(0, 0.25), 12),
        random.choice(["", "1.0", "2.0"]),
        "" if random.random() < 0.85 else json.dumps(
            {"exception_type": "ValueError", "message": random_text(10, 60), "traceback": random_text(20, 80)}),
        random.choice([10001, 20001]),
        payload_in[:200],
        payload_out[:200],
        ttft,
        random.choice(_SOURCES),
        random.choice(_ENVIRONMENTS),
    )


@click.command()
@click.option("--project", default=DEFAULT_PROJECT, help="Project name to seed into.")
@click.option("--weeks", default=8, help="Number of consecutive weeks of history, ending at the current week.")
@click.option("--traces-per-week", default=250, help="Distinct traces per week (what the delete generator deletes).")
@click.option("--spans-per-trace", default=8, help="Spans per trace. Well above a typical trace's, to exercise nesting.")
@click.option("--bad-ids", default=0,
              help="Extra spans with a far-future (year ~2201) UUIDv7 id but a real created_at. Spreads the "
                   "destination's weekly partitions, which is what max_partitions_per_insert_block must permit.")
@click.option("--non-v7-ids", default=0,
              help="Extra spans whose id is a UUIDv4. UUIDv7ToDateTime returns 1970-01-01 for those, so they land in "
                   "the EPOCH week — the far-PAST end of the partition spread.")
@click.option("--parent-poison", default=0,
              help="Extra spans whose parent_span_id is the 40-character leftPad('', 40, '*') poison value SpanDAO's "
                   "PARTIAL_INSERT writes. Exercises the copy's length guard; without them it is untested.")
@click.option("--split-parents", default=0,
              help="Span ids written TWICE under two parents at the SAME last_updated_at — the spans-only version tie "
                   "verify.sh must report as INCONCLUSIVE.")
@click.option("--batch", default=5000, help="Rows per ClickHouse INSERT.")
@click.option("--workspace-id", default=None, help="Override workspace_id (default: auto-discovered via the SDK).")
@click.option("--project-id", default=None, help="Override project_id (default: auto-discovered via the SDK).")
def main(project, weeks, traces_per_week, spans_per_trace, bad_ids, non_v7_ids, parent_poison, split_parents, batch,
         workspace_id, project_id):
    ch = make_ch_client()

    if workspace_id is None or project_id is None:
        # Fill only the value(s) not supplied, so a single --workspace-id or --project-id override is honored.
        discovered_workspace_id, discovered_project_id = discover_workspace_and_project(make_opik_client(), ch, project)
        workspace_id = workspace_id or discovered_workspace_id
        project_id = project_id or discovered_project_id

    now = utcnow()
    rows: list[tuple] = []
    per_week_counts: dict[str, int] = {}
    # Kept so the extra populations below hang off real traces the delete generator can reach.
    seeded_trace_ids: list[tuple[str, datetime]] = []

    for week in range(weeks):
        week_end = now - timedelta(weeks=week)
        week_start = week_end - timedelta(weeks=1)
        label = week_start.date().isoformat()
        span_span_seconds = (week_end - week_start).total_seconds()
        for _ in range(traces_per_week):
            trace_at = week_start + timedelta(seconds=random.uniform(0, span_span_seconds))
            trace_id = mint_uuid7(trace_at)
            seeded_trace_ids.append((trace_id, trace_at))
            # One root span (empty parent) plus children pointing at it, which is the real shape and is what makes the
            # empty-sentinel arm of the parent_span_id normalization non-trivial on both sides.
            root_id = mint_uuid7(trace_at)
            rows.append(_row(trace_at, root_id, trace_id, workspace_id, project_id, ""))
            for _child in range(max(spans_per_trace - 1, 0)):
                child_at = trace_at + timedelta(milliseconds=random.uniform(1, 5_000))
                rows.append(_row(child_at, mint_uuid7(child_at), trace_id, workspace_id, project_id, root_id))
        per_week_counts[label] = traces_per_week * spans_per_trace

    def _pick_trace() -> tuple[str, datetime]:
        return random.choice(seeded_trace_ids) if seeded_trace_ids else (mint_uuid7(now), now)

    for _ in range(bad_ids):
        trace_id, trace_at = _pick_trace()
        created_at = now - timedelta(weeks=random.uniform(0, max(weeks - 1, 1)))
        rows.append(_row(created_at, mint_uuid7(BAD_ID_INSTANT), trace_id, workspace_id, project_id, ""))

    for _ in range(non_v7_ids):
        trace_id, trace_at = _pick_trace()
        created_at = now - timedelta(weeks=random.uniform(0, max(weeks - 1, 1)))
        # A v4 UUID: UUIDv7ToDateTime returns 1970-01-01 for it rather than throwing, so it lands in the epoch week.
        rows.append(_row(created_at, str(uuid.uuid4()), trace_id, workspace_id, project_id, ""))

    for _ in range(parent_poison):
        trace_id, trace_at = _pick_trace()
        created_at = now - timedelta(weeks=random.uniform(0, max(weeks - 1, 1)))
        rows.append(_row(created_at, mint_uuid7(created_at), trace_id, workspace_id, project_id, PARENT_POISON))

    for _ in range(split_parents):
        trace_id, trace_at = _pick_trace()
        created_at = now - timedelta(weeks=random.uniform(0, max(weeks - 1, 1)))
        span_id = mint_uuid7(created_at)
        # SAME id, SAME last_updated_at, DIFFERENT parents: two live rows on the source (parent_span_id is in its sort
        # key), one on the destination, and nothing to break the tie. Note both rows also differ in payload, because a
        # tie between byte-identical rows is deliberately NOT reported — see 000005's version-ties block.
        rows.append(_row(created_at, span_id, trace_id, workspace_id, project_id, "",
                         last_updated_at_dt=created_at))
        rows.append(_row(created_at, span_id, trace_id, workspace_id, project_id, mint_uuid7(created_at),
                         last_updated_at_dt=created_at))

    random.shuffle(rows)  # interleave weeks so inserts look like real ingestion, not one week at a time
    LOGGER.info(
        "Inserting %d spans (%d weeks x %d traces x %d spans) + %d bad-id + %d non-v7 + %d parent-poison + %d "
        "split-parent into project_id=%s",
        len(rows), weeks, traces_per_week, spans_per_trace, bad_ids, non_v7_ids, parent_poison, split_parents * 2,
        project_id)
    for start in range(0, len(rows), batch):
        chunk = rows[start:start + batch]
        ch.insert("spans", chunk, column_names=COLUMNS)
        LOGGER.info("  inserted %d/%d", min(start + batch, len(rows)), len(rows))

    total = ch.query(
        "SELECT count() FROM spans WHERE project_id = {p:String}", parameters={"p": project_id}
    ).result_rows[0][0]
    LOGGER.info("Done. project '%s' now has %s live spans in ClickHouse.", project, total)
    LOGGER.info("Per-week seeded (created_at week -> span count): %s",
                {k: per_week_counts[k] for k in sorted(per_week_counts)})
    if bad_ids:
        LOGGER.info(
            "Plus %d far-future-id spans (litellm UUIDv7 ~2201). The successor's DateTime64 id_at partitions by the "
            "honest Date32 weekly Monday, so look for them in their own ~2201 weekly partition — and expect the "
            "backfill's OUTLIER pass to be the one that copies them.", bad_ids)
    if non_v7_ids:
        LOGGER.info(
            "Plus %d non-v7-id spans. UUIDv7ToDateTime returns 1970-01-01 for those, so they land in the EPOCH week — "
            "the far-PAST end of the partition spread. Anything that looked only for far-future ids would miss them.",
            non_v7_ids)
    if parent_poison:
        LOGGER.info(
            "Plus %d spans carrying the 40-character parent_span_id poison value. The copy maps anything that is not "
            "exactly 36 bytes to the root sentinel; estimate.sh audit 4 should now report %d, and verify.sh should "
            "still PASS (the fingerprint applies the same normalization to the source side).", parent_poison,
            parent_poison)
    if split_parents:
        LOGGER.info(
            "Plus %d span ids written twice under different parents at one last_updated_at. verify.sh should report "
            "those windows INCONCLUSIVE with version_ties=src:N/dst:0 — NOT a mismatch, and NOT a pass.", split_parents)
    LOGGER.info(
        "NOTE: these spans hang off %d seeded trace_ids, but no `traces` rows were created for them. delete_traffic.py "
        "deletes traces it finds through the API, so run live_traffic.py (which creates real traces AND spans) if you "
        "want the cascade to reach seeded history too.", len(seeded_trace_ids))


if __name__ == "__main__":
    main()
