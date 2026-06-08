"""
Phase 2 of the ``opik.evaluate_resume`` demo (no mock metrics).

Takes an experiment id from ``resume_demo_start.py`` and continues the
interrupted evaluation. Resume reuses the **same** scoring metrics the
original ``evaluate()`` call used — that's not a SDK enforcement, it's
the caller's contract (the SDK has no way to know which metric objects
the original Python process held). Mismatched metrics here would mean
the merged result advertises feedback scores under one name from the
reconstructed runs and under another name from the freshly replayed
runs.

Run::

    OPIK_URL_OVERRIDE=http://localhost:8080/ \\
    OPIK_WORKSPACE=default OPIK_API_KEY="" OPIK_CONFIG_PATH=/dev/null \\
    sdks/python/venv/bin/python scripts/resume_demo_continue.py <EXPERIMENT_ID>
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, Dict, List

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT / "sdks" / "python" / "src"))

import opik  # noqa: E402
from opik.evaluation import metrics  # noqa: E402


# Same deterministic classifier the start script uses; copied verbatim
# so this script is runnable on its own without importing the other.
def _classify(text: str) -> str:
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


def main(argv: List[str]) -> int:
    if len(argv) != 2:
        print("Usage: python scripts/resume_demo_continue.py <EXPERIMENT_ID>")
        return 2
    experiment_id = argv[1].strip()

    client = opik.Opik()
    base_url = (client.config.url_override or "").rstrip("/")
    print(f"Backend  : {base_url}")
    print(f"Resuming : {experiment_id}")
    print()

    invocations: List[str] = []

    def healthy_task(item: Dict[str, Any]) -> Dict[str, str]:
        invocations.append(item["input"]["text"])
        return {"output": _classify(item["input"]["text"])}

    result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=healthy_task,
        # Same metric set the start script used. Resume contractually
        # requires the caller to re-supply these — the SDK persists the
        # iteration knobs (dataset version, filter, nb_samples, trial
        # count) but cannot persist live Python metric objects.
        scoring_metrics=[metrics.Equals(), metrics.Contains()],
        scoring_key_mapping={"reference": "expected_output"},
    )
    client.flush()

    print()
    print("=" * 64)
    print(f"Resume task invocations : {len(invocations)}")
    if invocations:
        print(f"  items replayed        : {invocations}")
    print(f"Merged result rows      : {len(result.test_results)}")
    if base_url:
        print(
            f"Experiment URL          : {base_url}/v1/session/redirect/experiments/"
            f"?experiment_id={experiment_id}"
        )
    print("=" * 64)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
