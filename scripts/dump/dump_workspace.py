#!/usr/bin/env python3
"""
Dump N traces from a ClickHouse workspace plus all corresponding spans and
feedback scores. Outputs three JSONEachRow files (re-ingestible).

Queries are issued in small chunks (--chunk-size, default 10 ids per query)
so each individual response stays under prod-CH read/timeout limits even
when total payload is many GiB. Each chunk retries up to 3 times with
exponential backoff on transient failures.

Usage:
    python dump_workspace.py --workspace <id> [--limit 1000] [--out-dir tmp/dump/<bucket>]
                             [--order-by "start_time DESC"]
                             [--filter "1=1"]
                             [--chunk-size 10]

Env vars (read from shell or .env.local):
    CH_HOST, CH_PORT, CH_DATABASE, CH_USER, CH_PASSWORD
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

# Retry policy for chunk queries: 3 attempts, backoff after attempt 1, 2.
RETRY_BACKOFFS_SECONDS = (5, 15, 45)


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def ch_env() -> dict:
    required = ["CH_HOST", "CH_PORT", "CH_DATABASE", "CH_USER", "CH_PASSWORD"]
    missing = [k for k in required if not os.environ.get(k)]
    if missing:
        sys.exit(f"Missing env vars: {missing}. Source .env.local or export them.")
    return {k: os.environ[k] for k in required}


def ch_cmd(env: dict, query: str) -> list[str]:
    return [
        "clickhouse", "client",
        "--host", env["CH_HOST"], "--port", env["CH_PORT"],
        "--database", env["CH_DATABASE"], "--user", env["CH_USER"],
        "--password", env["CH_PASSWORD"],
        "--query", query,
    ]


def run_with_retry(query: str, env: dict, label: str) -> bytes:
    """Run a CH query; retry up to 3 times with backoff on non-zero exit. Return stdout bytes."""
    last_err = ""
    for attempt in range(len(RETRY_BACKOFFS_SECONDS) + 1):
        proc = subprocess.run(ch_cmd(env, query), capture_output=True)
        if proc.returncode == 0:
            return proc.stdout
        last_err = proc.stderr.decode("utf-8", errors="replace")
        if attempt < len(RETRY_BACKOFFS_SECONDS):
            wait = RETRY_BACKOFFS_SECONDS[attempt]
            print(f"  [retry] {label} attempt {attempt + 1} failed, sleeping {wait}s: {last_err.splitlines()[0][:200]}",
                  file=sys.stderr)
            time.sleep(wait)
    sys.exit(f"{label} failed after {len(RETRY_BACKOFFS_SECONDS) + 1} attempts:\n{last_err}")


def in_clause(ids: list[str]) -> str:
    return ",".join(f"'{x}'" for x in ids)


def chunked(items: list, size: int):
    for i in range(0, len(items), size):
        yield i, items[i:i + size]


def fetch_trace_ids(workspace: str, filter_expr: str, order_by: str, limit: int, env: dict) -> list[str]:
    """Step 1: pick the N trace ids matching the bucket rule. Single query, only ids returned."""
    # Filter+sort first, then LIMIT 1 BY id to dedup ReplacingMergeTree, then LIMIT N.
    # This avoids a wide inner dedup over the whole workspace (which exploded the read cap when
    # there's no date window). For our buckets the column being filtered/ordered (error_info,
    # duration, input_length, output_length, start_time) is stable across trace versions, so
    # filter-before-dedup is correct.
    q = f"""
        SELECT id FROM traces
        WHERE workspace_id = '{workspace}'
          AND ({filter_expr})
        ORDER BY {order_by}, last_updated_at DESC
        LIMIT 1 BY id
        LIMIT {limit}
        FORMAT TabSeparated
    """
    out = run_with_retry(q, env, label="select-trace-ids")
    return [t for t in out.decode("utf-8").splitlines() if t]


def dump_traces(workspace: str, trace_ids: list[str], chunk_size: int, out_path: Path, env: dict) -> int:
    """Step 2: dump full trace rows in chunks of trace_ids."""
    total = 0
    n_chunks = (len(trace_ids) + chunk_size - 1) // chunk_size
    with out_path.open("ab") as out_fh:
        for chunk_i, (_, ids) in enumerate(chunked(trace_ids, chunk_size), start=1):
            q = f"""
                SELECT * FROM traces
                WHERE workspace_id = '{workspace}'
                  AND id IN ({in_clause(ids)})
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
                FORMAT JSONEachRow
            """
            data = run_with_retry(q, env, label=f"traces chunk {chunk_i}/{n_chunks}")
            out_fh.write(data)
            total += data.count(b"\n")
            if chunk_i % 10 == 0 or chunk_i == n_chunks:
                print(f"[traces]   chunk {chunk_i}/{n_chunks} wrote {total} rows so far",
                      file=sys.stderr)
    return total


def dump_spans(workspace: str, trace_ids: list[str], chunk_size: int, out_path: Path, env: dict) -> tuple[int, list[str]]:
    """Step 3: dump spans for trace_ids in chunks. Also collect span ids while iterating."""
    total = 0
    span_ids: list[str] = []
    n_chunks = (len(trace_ids) + chunk_size - 1) // chunk_size
    with out_path.open("ab") as out_fh:
        for chunk_i, (_, ids) in enumerate(chunked(trace_ids, chunk_size), start=1):
            q = f"""
                SELECT * FROM spans
                WHERE workspace_id = '{workspace}'
                  AND trace_id IN ({in_clause(ids)})
                ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
                FORMAT JSONEachRow
            """
            data = run_with_retry(q, env, label=f"spans chunk {chunk_i}/{n_chunks}")
            out_fh.write(data)
            for line in data.splitlines():
                if not line:
                    continue
                # extract id without full json parse for speed; falls back to json.loads on failure
                try:
                    obj = json.loads(line)
                    span_ids.append(obj["id"])
                except (json.JSONDecodeError, KeyError):
                    pass
            total = len(span_ids)
            if chunk_i % 10 == 0 or chunk_i == n_chunks:
                print(f"[spans]    chunk {chunk_i}/{n_chunks} wrote {total} spans so far",
                      file=sys.stderr)
    return total, span_ids


def dump_feedback(workspace: str, entity_ids: list[str], chunk_size: int, out_path: Path, env: dict) -> int:
    """Step 4: dump authored_feedback_scores for trace + span entity ids in chunks."""
    if not entity_ids:
        return 0
    total = 0
    n_chunks = (len(entity_ids) + chunk_size - 1) // chunk_size
    with out_path.open("ab") as out_fh:
        for chunk_i, (_, ids) in enumerate(chunked(entity_ids, chunk_size), start=1):
            q = f"""
                SELECT * FROM authored_feedback_scores
                WHERE workspace_id = '{workspace}'
                  AND entity_id IN ({in_clause(ids)})
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author
                FORMAT JSONEachRow
            """
            data = run_with_retry(q, env, label=f"feedback chunk {chunk_i}/{n_chunks}")
            out_fh.write(data)
            total += data.count(b"\n")
            if chunk_i % 20 == 0 or chunk_i == n_chunks:
                print(f"[feedback] chunk {chunk_i}/{n_chunks} wrote {total} rows so far",
                      file=sys.stderr)
    return total


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--workspace", required=True, help="workspace_id to dump")
    p.add_argument("--limit", type=int, default=1000, help="number of traces (default 1000)")
    p.add_argument("--order-by", default="start_time DESC",
                   help="trace ORDER BY clause (default 'start_time DESC'). No time-window filter is applied.")
    p.add_argument("--filter", default="1=1", help="extra WHERE clause for traces (default '1=1')")
    p.add_argument("--chunk-size", type=int, default=10,
                   help="ids per CH query for traces/spans (default 10). Small because trace/span payloads can be huge.")
    p.add_argument("--feedback-chunk-size", type=int, default=500,
                   help="entity ids per CH query for feedback scores (default 500). Larger than --chunk-size because feedback rows are tiny.")
    p.add_argument("--trace-ids-file", default=None,
                   help="path to a file with one trace id per line; if given, skips step 1 and uses these ids "
                        "(--filter/--order-by/--limit are ignored)")
    p.add_argument("--out-dir", default=None, help="output directory (default tmp/dump/<workspace>)")
    p.add_argument("--label", default=None, help="optional label for manifest")
    p.add_argument("--append", action="store_true", help="append to existing files instead of overwriting")
    args = p.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    load_dotenv(repo_root / ".env.local")
    env = ch_env()

    out_dir = Path(args.out_dir) if args.out_dir else (repo_root / "tmp/dump" / args.workspace)
    out_dir.mkdir(parents=True, exist_ok=True)
    traces_path = out_dir / "traces.jsonl"
    spans_path = out_dir / "spans.jsonl"
    fb_path = out_dir / "feedback_scores.jsonl"

    if args.append:
        print(f"[mode]     append to existing files in {out_dir}", file=sys.stderr)
    else:
        removed = [f.name for f in (traces_path, spans_path, fb_path) if f.exists()]
        for f in (traces_path, spans_path, fb_path):
            if f.exists():
                f.unlink()
        if removed:
            print(f"[mode]     truncating existing files: {removed}", file=sys.stderr)
        else:
            print(f"[mode]     fresh dump to {out_dir}", file=sys.stderr)

    # 1. Pick trace ids
    if args.trace_ids_file:
        trace_ids = [t.strip() for t in Path(args.trace_ids_file).read_text().splitlines() if t.strip()]
        print(f"[ids]      loaded {len(trace_ids)} trace ids from {args.trace_ids_file}", file=sys.stderr)
    else:
        print(f"[ids]      selecting up to {args.limit} trace ids for workspace={args.workspace} "
              f"order_by='{args.order_by}' filter='{args.filter}'", file=sys.stderr)
        trace_ids = fetch_trace_ids(args.workspace, args.filter, args.order_by, args.limit, env)
        print(f"[ids]      got {len(trace_ids)} trace ids", file=sys.stderr)
    if not trace_ids:
        print(f"[warn]     no traces matched", file=sys.stderr)
        return

    # 2. Trace rows (chunked)
    n_traces = dump_traces(args.workspace, trace_ids, args.chunk_size, traces_path, env)
    print(f"[traces]   wrote {n_traces} rows -> {traces_path}", file=sys.stderr)

    # 3. Spans (chunked); also collects span ids inline
    n_spans, span_ids = dump_spans(args.workspace, trace_ids, args.chunk_size, spans_path, env)
    print(f"[spans]    wrote {n_spans} rows -> {spans_path}", file=sys.stderr)

    # 4. Feedback scores for traces + spans (chunked with bigger chunk size — feedback rows are small)
    all_entity_ids = trace_ids + span_ids
    n_fb = dump_feedback(args.workspace, all_entity_ids, args.feedback_chunk_size, fb_path, env)
    print(f"[feedback] wrote {n_fb} rows -> {fb_path}", file=sys.stderr)

    # 5. Manifest
    manifest_path = out_dir / "manifest.json"
    if manifest_path.exists() and args.append:
        manifest = json.loads(manifest_path.read_text())
    else:
        manifest = {
            "workspace_id": args.workspace,
            "out_dir": str(out_dir),
            "source": f"{env['CH_HOST']}:{env['CH_PORT']}/{env['CH_DATABASE']}",
            "passes": [],
        }
    manifest["passes"].append({
        "label": args.label,
        "limit": args.limit,
        "order_by": args.order_by,
        "filter": args.filter,
        "chunk_size": args.chunk_size,
        "feedback_chunk_size": args.feedback_chunk_size,
        "trace_ids_file": args.trace_ids_file,
        "trace_count": len(trace_ids),
        "span_count": len(span_ids),
        "feedback_count": n_fb,
    })
    manifest["total_traces"] = sum(p["trace_count"] for p in manifest["passes"])
    manifest["total_spans"] = sum(p["span_count"] for p in manifest["passes"])
    manifest["total_feedback"] = sum(p["feedback_count"] for p in manifest["passes"])
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"[manifest] {manifest_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
