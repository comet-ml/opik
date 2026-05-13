"""Log thread traces in various shapes and verify online evaluators score them.

Generates one thread per shape, logs traces + spans, closes the thread (which is
what fires the online-scoring event), then polls thread feedback scores until
both expected scores show up — or the wait budget expires.

Shapes:
    small           2 traces · ~50 char input/output · 1 span each.
                    Stays well below the agentic-tools size threshold; LLM-as-judge
                    runs in the inline path.
    medium          6 traces · ~500 chars · 3 spans each.
    huge            8 traces · ~10K chars each. Designed to push the rendered
                    context past the default 50K-token threshold so the
                    LLM-as-judge routes through the read / jq / search /
                    get_trace_spans loop. Requires TOGGLE_AGENTIC_TOOLS_ENABLED=true
                    on the backend, otherwise the scorer falls back to inline.
    large-messages  4 traces · 30K-char single field per trace. Tests
                    PathAwareTruncator behavior in the agentic-tools path.
    large-spans     3 traces · 30 spans/trace · medium content. Stresses the
                    get_trace_spans tool's serializer and the SpanService fetch.

Each thread is tagged with a freshly generated `thread_id`. The Python rule's
metric returns score=count(spans), so we can verify spans flowed end-to-end by
comparing the recorded score to the actual span count.

Usage:
    python scripts/run_thread_traces.py --project-name thread-scoring-smoke

Prereqs: run `setup_thread_evaluators.py` first against the same project.
"""

from __future__ import annotations

import argparse
import dataclasses
import random
import string
import sys
import time
import uuid
from typing import Any, Dict, List, Optional

import opik


@dataclasses.dataclass
class ShapeSpec:
    name: str
    trace_count: int
    text_chars: int
    spans_per_trace: int
    span_text_chars: int


SHAPES: Dict[str, ShapeSpec] = {
    "small": ShapeSpec("small", trace_count=2, text_chars=50, spans_per_trace=1, span_text_chars=80),
    "medium": ShapeSpec("medium", trace_count=6, text_chars=500, spans_per_trace=3, span_text_chars=300),
    # Huge: ~ 8 × 10K = 80K chars of trace input/output before counting spans, which the
    # 4-chars/token estimator turns into ~20K tokens — bump trace count or text_chars if
    # the backend's agenticToolsThresholdTokens is left at the 50K default and you want
    # to see the routing decision flip. Keeping conservative defaults so the test still
    # completes within a reasonable poll budget on slower machines.
    "huge": ShapeSpec("huge", trace_count=8, text_chars=10_000, spans_per_trace=2, span_text_chars=1_000),
    "large-messages": ShapeSpec(
        "large-messages", trace_count=4, text_chars=30_000, spans_per_trace=2, span_text_chars=500
    ),
    "large-spans": ShapeSpec(
        "large-spans", trace_count=3, text_chars=500, spans_per_trace=30, span_text_chars=400
    ),
}

LLM_SCORE_NAME = "relevance"
PYTHON_SCORE_NAME = "thread_spans_probe"


def _rand_text(n_chars: int, seed: int) -> str:
    """Deterministic-ish filler. Mixed letters + spaces so it tokenizes like prose."""
    rng = random.Random(seed)
    alphabet = string.ascii_letters + "      "  # 6 spaces — keeps word boundaries
    return "".join(rng.choice(alphabet) for _ in range(n_chars))


def log_thread(client: opik.Opik, shape: ShapeSpec, project_name: str) -> Dict[str, Any]:
    """Logs trace_count traces (each with spans_per_trace spans) under a fresh thread_id.

    Returns a dict with thread_id + counters used for assertions later. Each trace has
    a user-style input and an assistant-style output so the {{context}} substitution in
    the LLM-as-judge template renders as a conversation, not a flat blob.
    """
    thread_id = f"smoke-{shape.name}-{uuid.uuid4().hex[:10]}"
    trace_ids: List[str] = []
    expected_spans = shape.trace_count * shape.spans_per_trace

    for trace_idx in range(shape.trace_count):
        # Seed off (shape.name, trace_idx) so a second run with the same shape produces
        # different-but-deterministic text — useful for spot-checking serialized output
        # in the Opik UI.
        seed_base = hash((shape.name, trace_idx, thread_id)) & 0xFFFFFFFF

        trace = client.trace(
            project_name=project_name,
            thread_id=thread_id,
            name=f"{shape.name}-turn-{trace_idx}",
            input={"role": "user", "message": _rand_text(shape.text_chars, seed_base)},
            output={"role": "assistant", "message": _rand_text(shape.text_chars, seed_base + 1)},
            metadata={"shape": shape.name, "turn": trace_idx},
        )
        trace_ids.append(trace.id)

        for span_idx in range(shape.spans_per_trace):
            span_seed = seed_base + 100 + span_idx
            trace.span(
                name=f"step-{span_idx}",
                input={"q": _rand_text(shape.span_text_chars, span_seed)},
                output={"a": _rand_text(shape.span_text_chars, span_seed + 1)},
                type="llm" if span_idx == 0 else "general",
            )

        trace.end()

    return {
        "thread_id": thread_id,
        "trace_ids": trace_ids,
        "expected_span_count": expected_spans,
        "shape": shape.name,
    }


def close_thread(client: opik.Opik, project_name: str, thread_id: str) -> None:
    """Closing the thread is what fires the trace-thread online-scoring event."""
    client.rest_client.traces.close_trace_thread(project_name=project_name, thread_id=thread_id)


def poll_scores(
    client: opik.Opik,
    project_name: str,
    thread_id: str,
    expected_score_names: List[str],
    timeout_s: int,
    interval_s: float,
) -> Dict[str, Any]:
    """Polls get_trace_thread until every expected score name shows up or the
    timeout expires. Returns the last observed thread payload so the caller can
    inspect missing-score cases instead of just getting a None back."""
    deadline = time.monotonic() + timeout_s
    last: Optional[Any] = None
    while True:
        try:
            last = client.rest_client.traces.get_trace_thread(
                thread_id=thread_id, project_name=project_name
            )
        except Exception as exc:  # noqa: BLE001 — best-effort polling
            # 404 is expected for a tick or two before the thread is materialized; keep going.
            if time.monotonic() >= deadline:
                return {"thread_id": thread_id, "error": str(exc), "scores": [], "timed_out": True}
            time.sleep(interval_s)
            continue

        scores = list(last.feedback_scores or [])
        names = {s.name for s in scores}
        if all(name in names for name in expected_score_names):
            return {"thread_id": thread_id, "scores": scores, "timed_out": False}
        if time.monotonic() >= deadline:
            return {"thread_id": thread_id, "scores": scores, "timed_out": True}
        time.sleep(interval_s)


def summarize_run(result: Dict[str, Any], threads_meta: Dict[str, Dict[str, Any]]) -> str:
    """Renders a single-line per-shape verdict.

    Verdicts cover three independent properties:
        - whether the LLM-as-judge score landed (proves the agentic-tools loop closed
          successfully when the shape was large enough to route through it)
        - whether the Python score landed
        - whether the Python score's value matches `expected_span_count` (proves the
          opt-in spans kwarg flowed end-to-end — not just any value would do)
    """
    meta = threads_meta[result["thread_id"]]
    scores = result.get("scores") or []
    by_name = {s.name: s for s in scores}

    llm = by_name.get(LLM_SCORE_NAME)
    py = by_name.get(PYTHON_SCORE_NAME)
    expected = meta["expected_span_count"]

    llm_str = f"{LLM_SCORE_NAME}={llm.value}" if llm else f"{LLM_SCORE_NAME}=MISSING"
    if py:
        actual = int(py.value)
        py_str = f"{PYTHON_SCORE_NAME}={actual}" + ("" if actual == expected else f" (expected {expected})")
    else:
        py_str = f"{PYTHON_SCORE_NAME}=MISSING"

    flag = " [TIMED OUT]" if result.get("timed_out") else ""
    return f"  {meta['shape']:<14} thread={meta['thread_id']}  {llm_str}  |  {py_str}{flag}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project-name", required=True)
    parser.add_argument(
        "--shapes",
        default=",".join(SHAPES.keys()),
        help=f"Comma-separated subset of: {', '.join(SHAPES.keys())}. Defaults to all.",
    )
    parser.add_argument(
        "--wait",
        type=int,
        default=120,
        help="Per-thread score polling timeout in seconds (default 120). Bump this on slower "
        "boxes — LLM-as-judge on the huge shape can take a minute on first run while the "
        "provider warms.",
    )
    parser.add_argument(
        "--poll-interval",
        type=float,
        default=3.0,
        help="Seconds between score-polling attempts (default 3).",
    )
    parser.add_argument(
        "--skip-llm",
        action="store_true",
        help="Don't expect the LLM-as-judge score. Use this when only the Python rule is wired up "
        "(e.g. no LLM provider configured).",
    )
    parser.add_argument(
        "--skip-python",
        action="store_true",
        help="Don't expect the Python score.",
    )
    args = parser.parse_args()

    if args.skip_llm and args.skip_python:
        parser.error("Refusing to run: both --skip-llm and --skip-python set, nothing to verify.")

    requested = [s.strip() for s in args.shapes.split(",") if s.strip()]
    unknown = [s for s in requested if s not in SHAPES]
    if unknown:
        parser.error(f"Unknown shapes: {unknown}. Valid: {sorted(SHAPES)}")

    expected_score_names: List[str] = []
    if not args.skip_llm:
        expected_score_names.append(LLM_SCORE_NAME)
    if not args.skip_python:
        expected_score_names.append(PYTHON_SCORE_NAME)

    client = opik.Opik(project_name=args.project_name)

    # Log every requested thread, then flush ONCE and close them — flushing before close
    # ensures the backend has the traces+spans by the time the close-thread event fires.
    threads_meta: Dict[str, Dict[str, Any]] = {}
    for shape_name in requested:
        spec = SHAPES[shape_name]
        meta = log_thread(client, spec, args.project_name)
        threads_meta[meta["thread_id"]] = meta
        print(
            f"logged {shape_name}: thread={meta['thread_id']}  traces={spec.trace_count}  "
            f"spans/trace={spec.spans_per_trace}  text_chars={spec.text_chars}",
            flush=True,
        )

    print("flushing trace batch...", flush=True)
    client.flush()

    print("closing threads (fires online-scoring events)...", flush=True)
    for thread_id in threads_meta:
        close_thread(client, args.project_name, thread_id)

    print(
        f"\npolling for scores [{', '.join(expected_score_names)}] — "
        f"per-thread timeout {args.wait}s, interval {args.poll_interval}s\n",
        flush=True,
    )

    results: List[Dict[str, Any]] = []
    for thread_id in threads_meta:
        result = poll_scores(
            client,
            args.project_name,
            thread_id,
            expected_score_names,
            timeout_s=args.wait,
            interval_s=args.poll_interval,
        )
        results.append(result)
        print(summarize_run(result, threads_meta), flush=True)

    failed = [r for r in results if r.get("timed_out")]
    print("\n" + ("=" * 60))
    if failed:
        print(f"FAIL: {len(failed)} of {len(results)} thread(s) did not receive all expected scores in time.")
        return 1
    print(f"PASS: all {len(results)} threads scored within {args.wait}s.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
