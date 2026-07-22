"""Seed historical `traces` rows spread across several weeks, so the cutover backfill has multi-week data to iterate.

Writes straight to ClickHouse: the ingestion API stamps `created_at` server-side (read-only), so it cannot produce
back-dated rows, and the backfill slices the source by `created_at`. Each row gets a `created_at` in its week and a
UUIDv7 `id` minted at the same instant, so `id_at` (the destination weekly partition) matches `created_at` — the shape
real accumulated history has. `--bad-ids` optionally adds rows whose `id` is minted in the far future (year ~2201, the
litellm-bug shape) while `created_at` stays real, to exercise the far-future-partition path. (The successor's `id_at` is
a 32-bit `DateTime` capped at 2106, so a 2201 id wraps to ~2065 — that is where those rows actually partition.)

Every migrated column is populated with VARIED, realistic values so the fidelity compare (verify.sh) actually exercises
each column — an empty column would match on both sides even if the copy dropped it. Timestamps are written at true
NANOSECOND precision (the source columns are DateTime64(9), like real `now64(9)` rows), so the ns->us truncation the
successor performs is exercised, not skipped. A share of rows leave `end_time` / `ttft` NULL to exercise the
NULL->sentinel (epoch / NaN) normalization.

Prerequisites: `./opik.sh --port-mapping` (ClickHouse on localhost:8123) and `OPIK_URL_OVERRIDE` pointing at the same
install. Run `python seed_history.py --help` for options.
"""

import json
import random
import string
from datetime import datetime, timedelta, timezone

import click

from _common import LOGGER, DEFAULT_PROJECT, discover_workspace_and_project, make_ch_client, make_opik_client, mint_uuid7, utcnow

# Every base column the backfill copies (MATERIALIZED columns like id_at are recomputed by CH and excluded). Order
# matches the tuple built in _row().
COLUMNS = [
    "id",
    "workspace_id",
    "project_id",
    "name",
    "start_time",
    "end_time",
    "input",
    "output",
    "metadata",
    "tags",
    "created_at",
    "last_updated_at",
    "created_by",
    "last_updated_by",
    "error_info",
    "thread_id",
    "visibility_mode",
    "truncation_threshold",
    "input_slim",
    "output_slim",
    "ttft",
    "source",
    "environment",
]

# A far-future instant matching the litellm UUIDv7 bug (ids whose embedded timestamp lands around the year 2201).
BAD_ID_INSTANT = utcnow().replace(year=2201)

_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
_TAG_POOL = ["prod", "llm", "rag", "eval", "v1", "v2", "canary", "batch", "stream", "agent"]
_SOURCES = ["sdk", "experiment", "playground", "optimization", "evaluator"]
_ENVIRONMENTS = ["production", "staging", "dev", ""]
_USERS = ["alice", "bob", "carol", "service-account", "ci-runner"]


def _text(lo: int, hi: int) -> str:
    return "".join(random.choices(string.ascii_letters + string.digits + " ", k=random.randint(lo, hi)))


def _payload(kind: str) -> str:
    return json.dumps({kind: _text(80, 240)})


def _ns(dt: datetime) -> int:
    """DateTime64(9) tick value (ns since epoch) with a random sub-microsecond remainder, so ns->us truncation runs."""
    whole_us = int((dt - _EPOCH).total_seconds() * 1_000_000)  # microseconds (integer, no float-precision loss at 2^53)
    return whole_us * 1_000 + random.randint(1, 999)


def _us(dt: datetime) -> int:
    """DateTime64(6) tick value (us since epoch)."""
    return int((dt - _EPOCH).total_seconds() * 1_000_000)


def _row(created_at_dt: datetime, id_instant: datetime, workspace_id: str, project_id: str) -> tuple:
    trace_id = mint_uuid7(id_instant)
    created_ns = _ns(created_at_dt)
    # 30% leave end_time NULL (the "not ended" case -> epoch sentinel on the successor); else a real duration.
    end_ns = None if random.random() < 0.3 else created_ns + random.randint(5_000_000, 3_000_000_000)
    # 40% leave ttft NULL (-> NaN sentinel); else a plausible time-to-first-token in seconds.
    ttft = None if random.random() < 0.4 else round(random.uniform(0.005, 5.0), 6)
    payload_in, payload_out = _payload("prompt"), _payload("completion")
    return (
        trace_id,
        workspace_id,
        project_id,
        "seed-trace",
        created_ns,  # start_time ~ created_at
        end_ns,
        payload_in,
        payload_out,
        json.dumps({"model": random.choice(["gpt-4", "claude", "llama"]),
                    "temperature": round(random.random(), 3), "max_tokens": random.randint(16, 4000)}),
        random.sample(_TAG_POOL, random.randint(0, 4)),
        created_ns,  # created_at — the backfill slice column, at ns precision
        _us(created_at_dt),  # last_updated_at (us) ~= created_at, so the delta never re-copies these historical rows
        random.choice(_USERS),
        random.choice(_USERS),
        "" if random.random() < 0.85 else json.dumps(
            {"exception_type": "ValueError", "message": _text(10, 60), "traceback": _text(20, 80)}),
        "" if random.random() < 0.5 else "".join(random.choices("0123456789abcdef", k=16)),
        "hidden" if random.random() < 0.1 else "default",
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
@click.option("--per-week", default=2000, help="Traces per week.")
@click.option("--bad-ids", default=0, help="Extra rows with a far-future (year ~2201) UUIDv7 id but a real created_at.")
@click.option("--batch", default=5000, help="Rows per ClickHouse INSERT.")
@click.option("--workspace-id", default=None, help="Override workspace_id (default: auto-discovered via the SDK).")
@click.option("--project-id", default=None, help="Override project_id (default: auto-discovered via the SDK).")
def main(project, weeks, per_week, bad_ids, batch, workspace_id, project_id):
    ch = make_ch_client()

    if workspace_id is None or project_id is None:
        # Fill only the value(s) not supplied, so a single --workspace-id or --project-id override is honored.
        discovered_workspace_id, discovered_project_id = discover_workspace_and_project(make_opik_client(), ch, project)
        workspace_id = workspace_id or discovered_workspace_id
        project_id = project_id or discovered_project_id

    now = utcnow()
    # Each week's rows land uniformly within the week. week 0 is the current week (ending at "now"); the loop walks
    # backward to older weeks. Generation order doesn't matter — rows are shuffled before insert (below).
    rows: list[tuple] = []
    per_week_counts: dict[str, int] = {}
    for week in range(weeks):
        week_end = now - timedelta(weeks=week)
        week_start = week_end - timedelta(weeks=1)
        label = week_start.date().isoformat()
        for _ in range(per_week):
            span = (week_end - week_start).total_seconds()
            created_at = week_start + timedelta(seconds=random.uniform(0, span))
            rows.append(_row(created_at, created_at, workspace_id, project_id))
        per_week_counts[label] = per_week

    for _ in range(bad_ids):
        created_at = now - timedelta(weeks=random.uniform(0, max(weeks - 1, 1)))
        rows.append(_row(created_at, BAD_ID_INSTANT, workspace_id, project_id))

    random.shuffle(rows)  # interleave weeks so inserts look like real ingestion, not one week at a time
    LOGGER.info("Inserting %d traces (%d weeks x %d + %d bad-id) into project_id=%s", len(rows), weeks, per_week,
                bad_ids, project_id)
    for start in range(0, len(rows), batch):
        chunk = rows[start:start + batch]
        ch.insert("traces", chunk, column_names=COLUMNS)
        LOGGER.info("  inserted %d/%d", min(start + batch, len(rows)), len(rows))

    total = ch.query(
        "SELECT count() FROM traces WHERE project_id = {p:String}", parameters={"p": project_id}
    ).result_rows[0][0]
    LOGGER.info("Done. project '%s' now has %s live traces in ClickHouse.", project, total)
    LOGGER.info("Per-week seeded (created_at week -> count): %s",
                {k: per_week_counts[k] for k in sorted(per_week_counts)})
    if bad_ids:
        LOGGER.info("Plus %d far-future-id rows (id_at ~2201) to exercise the bad-id partition path.", bad_ids)


if __name__ == "__main__":
    main()
