"""
Phase 1 of a clean ``opik.evaluate_resume`` demo (no mock metrics).

Builds a small classification dataset and runs ``opik.evaluate`` with
built-in scoring metrics (``Equals`` + ``Contains``) and a task that
intentionally crashes part-way through to simulate a real outage. The
experiment ends up half-finished; phase 2 (``resume_demo_continue.py``)
picks it up and replays just the missing runs.

Run::

    OPIK_URL_OVERRIDE=http://localhost:8080/ \\
    OPIK_WORKSPACE=default OPIK_API_KEY="" OPIK_CONFIG_PATH=/dev/null \\
    sdks/python/venv/bin/python scripts/resume_demo_start.py

When it exits, the printed experiment id can be pasted into the phase-2
script. Re-runs are idempotent: the dataset is deleted-and-recreated, and
the experiment name carries a unique per-process suffix.
"""

from __future__ import annotations

import sys
import time
import uuid
from pathlib import Path
from typing import Any, Dict

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "sdks" / "python" / "src"))

import opik  # noqa: E402
from opik import id_helpers  # noqa: E402
from opik.evaluation import metrics  # noqa: E402


DATASET_NAME = "resume-demo-real-metrics-dataset"
# Unique-per-process suffix — ``get_experiments_by_name`` is a
# case-insensitive substring search, so a fixed name would also match
# experiments from prior demo runs and confuse the phase-2 lookup.
EXPERIMENT_NAME = f"resume-demo-real-metrics-{uuid.uuid4().hex[:8]}"

# Index where the task will simulate an outage. With 10 items, 5 leaves
# a meaningful chunk pending for resume to pick up.
CRASH_ON_INDEX = 5
SLEEP_PER_TASK = 1.0

# Five-class classification toy dataset. Each item's ``input.text`` is
# the user-facing review; ``expected_output`` is the ground-truth class.
ITEMS = [
    ("This is the best movie I have ever seen!", "positive"),
    ("Truly the worst customer support I've experienced.", "negative"),
    ("It was alright, nothing memorable either way.", "neutral"),
    ("I'd buy ten more of these tomorrow.", "positive"),
    ("Will never order from them again.", "negative"),
    ("Acceptable for the price; gets the job done.", "neutral"),
    ("Genuinely loved every moment of this.", "positive"),
    ("Quality dropped through the floor this year.", "negative"),
    ("Pretty average — neither great nor terrible.", "neutral"),
    ("Stellar build quality and exceptional support.", "positive"),
]


def _classify(text: str) -> str:
    """Deterministic stand-in for a real classifier. No LLM needed."""
    lowered = text.lower()
    positive_terms = {
        "best",
        "loved",
        "buy",
        "ten",
        "stellar",
        "exceptional",
        "great",
    }
    negative_terms = {"worst", "never", "dropped", "terrible"}
    if any(term in lowered for term in positive_terms):
        return "positive"
    if any(term in lowered for term in negative_terms):
        return "negative"
    return "neutral"


def _make_dataset(client: opik.Opik) -> opik.Dataset:
    """Recreate the dataset from scratch so re-runs are deterministic."""
    try:
        client.delete_dataset(DATASET_NAME)
    except Exception:
        pass
    dataset = client.create_dataset(DATASET_NAME)
    dataset.insert(
        [
            {
                "id": id_helpers.generate_id(),
                "input": {"text": text},
                "expected_output": label,
            }
            for text, label in ITEMS
        ]
    )
    return dataset


_call_count = {"n": 0}


def flaky_task(item: Dict[str, Any]) -> Dict[str, str]:
    """Real classifier work + an outage on the configured item index."""
    time.sleep(SLEEP_PER_TASK)
    _call_count["n"] += 1
    if _call_count["n"] == CRASH_ON_INDEX + 1:
        raise RuntimeError(
            f"Simulated outage on item #{_call_count['n']} "
            "(imagine an LLM rate limit or a network blip)"
        )
    return {"output": _classify(item["input"]["text"])}


def main() -> int:
    client = opik.Opik()
    base_url = (client.config.url_override or "").rstrip("/")
    print(f"Backend       : {base_url}")
    print(f"Dataset       : {DATASET_NAME} ({len(ITEMS)} items)")
    print(f"Experiment    : {EXPERIMENT_NAME}")
    print(f"Crash on item : #{CRASH_ON_INDEX + 1}")
    print()

    dataset = _make_dataset(client)

    try:
        opik.evaluate(
            dataset=dataset,
            task=flaky_task,
            # Two real, built-in heuristic metrics — no custom mocks.
            # Resume MUST be invoked with the same metric set (and the
            # same name, if you subclass), so phase 2 mirrors this list.
            scoring_metrics=[metrics.Equals(), metrics.Contains()],
            scoring_key_mapping={"reference": "expected_output"},
            experiment_name=EXPERIMENT_NAME,
            task_threads=1,
        )
    except KeyboardInterrupt:
        print("\n[Ctrl+C] Run interrupted — flushing in-flight traces.")
    except BaseException as exc:
        print(f"\n[run raised] {type(exc).__name__}: {exc}")

    # ``_evaluate_task`` re-raises before reaching its own flush.
    # Without this drain, the experiment-id lookup below can race with
    # the still-emptying queue and either miss the experiment or
    # under-count items.
    client.flush()

    experiments = client.get_experiments_by_name(EXPERIMENT_NAME)
    matching = [e for e in experiments if e.dataset_name == DATASET_NAME]
    assert len(matching) == 1, (
        f"Expected exactly one experiment named {EXPERIMENT_NAME!r}; "
        f"got {len(matching)}."
    )
    experiment_id = matching[0].id

    print()
    print("=" * 64)
    print(f"Experiment id  : {experiment_id}")
    if base_url:
        print(
            f"Experiment URL : {base_url}/v1/session/redirect/experiments/"
            f"?experiment_id={experiment_id}"
        )
    print("=" * 64)
    print()
    print("To resume:")
    print(f"  python scripts/resume_demo_continue.py {experiment_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
