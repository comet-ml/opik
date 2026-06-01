#!/usr/bin/env python3
"""
Re-ingest a dump produced by dump_workspace.py into a local Opik deployment
via the backend's batch endpoints.

Reads traces.jsonl, spans.jsonl, feedback_scores.jsonl from --dump-dir
LINE-BY-LINE (streaming, no full-file load) and POSTs in chunks of
--batch-size (default 100). This keeps memory bounded regardless of the
total dump size (e.g. ~6 GiB B4 input bucket).

All ingested data goes into a single workspace (Comet-Workspace header,
default 'default' for OSS local) under the project name you provide.
Run once per bucket with a distinct --project-name to end up with one
project per bucket in the local workspace.

Usage:
    python ingest_dump.py --dump-dir tmp/dump/b1_errors --project-name dump-b1-errors
                         [--batch-size 100]
                         [--base-url http://localhost:5174/api]
                         [--workspace default]
                         [--api-key ...]    # for cloud/auth-protected deployments
                         [--dry-run]
                         [--limit-batches N]
"""

import argparse
import itertools
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator
from urllib import error, request

# Replace the date portion of every CH timestamp with this when --keep-timestamps is not set.
# Computed once at startup so the entire ingest run is stamped consistently.
_TODAY = datetime.now(timezone.utc).date()
_FORCE_TODAY = True  # toggled from main() based on --keep-timestamps


def parse_ch_datetime(s: Any) -> str | None:
    """ClickHouse DateTime64 looks like '2026-05-29 16:27:20.303207000'; API wants ISO 8601 UTC.

    When _FORCE_TODAY is set, swap the YYYY-MM-DD portion with today's date but keep the
    HH:MM:SS.ffffff exactly (preserves intra-day duration; trace+span pairs stay aligned
    because they get the same date applied independently).
    """
    if not s or not isinstance(s, str) or s.startswith("1970-01-01"):
        return None
    if "." in s:
        date_part, frac = s.split(".", 1)
        frac = frac[:6]  # trim nanos -> micros for python fromisoformat
        s = f"{date_part}.{frac}"
    dt = datetime.fromisoformat(s).replace(tzinfo=timezone.utc)
    if _FORCE_TODAY:
        dt = dt.replace(year=_TODAY.year, month=_TODAY.month, day=_TODAY.day)
    return dt.isoformat().replace("+00:00", "Z")


def parse_json_field(s: Any) -> Any:
    if s is None or s == "":
        return None
    if isinstance(s, str):
        try:
            return json.loads(s)
        except json.JSONDecodeError:
            return s
    return s


def transform_trace(row: dict, project_name: str) -> dict:
    out = {
        "id": row["id"],
        "project_name": project_name,
        "name": row.get("name") or None,
        "start_time": parse_ch_datetime(row.get("start_time")),
        "end_time": parse_ch_datetime(row.get("end_time")),
        "input": parse_json_field(row.get("input")),
        "output": parse_json_field(row.get("output")),
        "metadata": parse_json_field(row.get("metadata")),
        "tags": row.get("tags") or [],
        "error_info": parse_json_field(row.get("error_info")),
        "thread_id": row.get("thread_id") or None,
        "ttft": row.get("ttft"),
    }
    return {k: v for k, v in out.items() if v is not None or k in {"id", "project_name", "start_time"}}


def transform_span(row: dict, project_name: str) -> dict:
    usage = row.get("usage") or {}
    if isinstance(usage, dict):
        usage = {k: int(v) for k, v in usage.items()}
    out = {
        "id": row["id"],
        "project_name": project_name,
        "trace_id": row["trace_id"],
        "parent_span_id": row.get("parent_span_id") or None,
        "name": row.get("name") or None,
        "type": row.get("type") or None,
        "start_time": parse_ch_datetime(row.get("start_time")),
        "end_time": parse_ch_datetime(row.get("end_time")),
        "input": parse_json_field(row.get("input")),
        "output": parse_json_field(row.get("output")),
        "metadata": parse_json_field(row.get("metadata")),
        "tags": row.get("tags") or [],
        "usage": usage if usage else None,
        "model": row.get("model") or None,
        "provider": row.get("provider") or None,
        "total_estimated_cost": row.get("total_estimated_cost") or None,
        "error_info": parse_json_field(row.get("error_info")),
        "ttft": row.get("ttft"),
    }
    return {k: v for k, v in out.items() if v is not None or k in {"id", "project_name", "trace_id", "start_time"}}


def transform_feedback(row: dict, project_name: str) -> dict:
    # FeedbackScoreBatchItem: project_name, name, category_name, value, reason, source, author, id (=entity_id)
    return {
        "id": row["entity_id"],
        "project_name": project_name,
        "name": row["name"],
        "category_name": row.get("category_name") or None,
        "value": float(row["value"]) if row.get("value") is not None else None,
        "reason": row.get("reason") or None,
        "source": row.get("source") or "sdk",
        "author": row.get("author") or row.get("created_by") or None,
    }


def iter_jsonl(path: Path) -> Iterator[dict]:
    """Yield one parsed row at a time without slurping the file."""
    if not path.exists():
        return
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                yield json.loads(line)


def chunked(it: Iterable, size: int) -> Iterator[list]:
    """Stream-friendly chunker: works on any iterable, materializes one chunk at a time."""
    src = iter(it)
    while True:
        batch = list(itertools.islice(src, size))
        if not batch:
            break
        yield batch


def http_request(method: str, url: str, body: dict, headers: dict, dry_run: bool) -> tuple[int, str]:
    payload = json.dumps(body).encode("utf-8")
    if dry_run:
        return 0, f"[dry-run] {method} {url} body_bytes={len(payload)}"
    req = request.Request(url, data=payload, headers=headers, method=method)
    try:
        with request.urlopen(req, timeout=120) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def required_ok(row: dict, required: tuple[str, ...]) -> bool:
    return all(row.get(k) is not None for k in required)


def post_stream(label: str, method: str, url: str, items: Iterable[dict], body_key: str,
                required: tuple[str, ...], headers: dict, args) -> tuple[int, int]:
    """Stream rows -> filter -> chunk -> POST. Returns (posted, dropped)."""
    posted = 0
    dropped = 0

    def gen():
        nonlocal dropped
        for r in items:
            if required_ok(r, required):
                yield r
            else:
                dropped += 1

    for i, batch in enumerate(chunked(gen(), args.batch_size)):
        if args.limit_batches and i >= args.limit_batches:
            print(f"[{label}] stopping after {i} batches (--limit-batches)", file=sys.stderr)
            break
        status, body = http_request(method, url, {body_key: batch}, headers, args.dry_run)
        posted += len(batch)
        ok = "ok" if status == 0 or (200 <= status < 300) else "FAIL"
        # Log every 10 batches to keep output reasonable for large dumps
        if (i + 1) % 10 == 0 or status >= 300:
            print(f"[{label}] batch {i + 1}: status={status} {ok} cumulative={posted}", file=sys.stderr)
        if not args.dry_run and status >= 300:
            sys.exit(f"{label} batch failed: {body[:2000]}")
        time.sleep(0.05)

    if dropped:
        print(f"[{label}] dropped {dropped} rows missing {required}", file=sys.stderr)
    print(f"[{label}] done: posted={posted} dropped={dropped}", file=sys.stderr)
    return posted, dropped


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--dump-dir", required=True, help="directory produced by dump_workspace.py")
    p.add_argument("--project-name", required=True, help="local project name to ingest into (auto-created)")
    p.add_argument("--batch-size", type=int, default=100, help="items per HTTP POST (default 100)")
    p.add_argument("--base-url", default="http://localhost:5174/api", help="Opik backend base URL (default http://localhost:5174/api)")
    p.add_argument("--workspace", default="default", help="Comet-Workspace header (default 'default' for OSS)")
    p.add_argument("--api-key", default=None, help="optional Authorization header for cloud deployments")
    p.add_argument("--dry-run", action="store_true", help="do not POST; print sizes only")
    p.add_argument("--limit-batches", type=int, default=0, help="stop after N batches per kind (smoke testing)")
    p.add_argument("--keep-timestamps", action="store_true",
                   help="keep original timestamps from the dump instead of rewriting the date portion to today")
    args = p.parse_args()

    global _FORCE_TODAY
    _FORCE_TODAY = not args.keep_timestamps
    if _FORCE_TODAY:
        print(f"[date] rewriting start_time / end_time date to today ({_TODAY.isoformat()}); time-of-day preserved",
              file=sys.stderr)

    dump_dir = Path(args.dump_dir)
    traces_path = dump_dir / "traces.jsonl"
    spans_path = dump_dir / "spans.jsonl"
    fb_path = dump_dir / "feedback_scores.jsonl"
    print(f"[target] workspace='{args.workspace}' project='{args.project_name}' base_url={args.base_url} batch_size={args.batch_size}",
          file=sys.stderr)

    headers = {"Content-Type": "application/json", "Comet-Workspace": args.workspace}
    if args.api_key:
        headers["Authorization"] = args.api_key

    # ---------- traces (streamed) ----------
    traces_iter = (transform_trace(r, args.project_name) for r in iter_jsonl(traces_path))
    post_stream("traces", "POST", f"{args.base_url}/v1/private/traces/batch",
                traces_iter, "traces", ("id", "project_name", "start_time"), headers, args)

    # ---------- spans (streamed) ----------
    spans_iter = (transform_span(r, args.project_name) for r in iter_jsonl(spans_path))
    post_stream("spans", "POST", f"{args.base_url}/v1/private/spans/batch",
                spans_iter, "spans", ("id", "project_name", "trace_id", "start_time"), headers, args)

    # ---------- feedback scores: two streamed passes (trace, then span) ----------
    # Feedback rows are small; two passes through the file is fine and avoids buffering both kinds.
    trace_fb_iter = (transform_feedback(r, args.project_name)
                     for r in iter_jsonl(fb_path) if r.get("entity_type") == "trace")
    post_stream("feedback-traces", "PUT", f"{args.base_url}/v1/private/traces/feedback-scores",
                trace_fb_iter, "scores", ("id", "name"), headers, args)

    span_fb_iter = (transform_feedback(r, args.project_name)
                    for r in iter_jsonl(fb_path) if r.get("entity_type") == "span")
    post_stream("feedback-spans", "PUT", f"{args.base_url}/v1/private/spans/feedback-scores",
                span_fb_iter, "scores", ("id", "name"), headers, args)

    print(f"[done]", file=sys.stderr)


if __name__ == "__main__":
    main()
