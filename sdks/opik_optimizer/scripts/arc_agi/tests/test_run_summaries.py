import json
from pathlib import Path

import pytest

from scripts.arc_agi.utils.run_summaries import persist_run_summary


class DummyScoreResult:
    def __init__(
        self,
        name: str,
        value: float,
        reason: str | None = None,
        metadata: dict[str, object] | None = None,
    ) -> None:
        self.name = name
        self.value = value
        self.reason = reason
        self.metadata = metadata or {}


class DummyTestResult:
    def __init__(self, score_results: list[DummyScoreResult]) -> None:
        self.score_results = score_results


class DummyEval:
    def __init__(
        self, score_results: list[DummyScoreResult], cost: float | None = None
    ) -> None:
        self.test_results = [DummyTestResult(score_results)]
        self.cost = cost


@pytest.mark.parametrize("final_cost", [None, 12.34])
def test_persist_run_summary_writes_jsonl(
    tmp_path: Path, final_cost: float | None
) -> None:
    composite_name = "arc_agi2_multi"
    raw_sr = DummyScoreResult(
        name="arc_agi2_exact",
        value=0.5,
        metadata={"best_code": "def transform(grid): return grid"},
    )
    composite_sr = DummyScoreResult(
        name=composite_name,
        value=0.7,
        reason="composite reason",
        metadata={"raw_score_results": [raw_sr]},
    )
    baseline_eval = DummyEval([composite_sr], cost=1.23)

    persist_run_summary(
        task_id="task123",
        composite_name=composite_name,
        baseline_score=0.7,
        baseline_eval=baseline_eval,
        final_score=0.9,
        trials_used=2,
        llm_calls=5,
        output_dir=tmp_path,
        model="eval-model",
        reasoning_model="reason-model",
        pass_at_k=2,
        n_samples=6,
        include_images=False,
        include_images_hrpo_eval=False,
        final_cost=final_cost,
    )

    out_file = tmp_path / "task123.jsonl"
    assert out_file.exists()
    lines = out_file.read_text().strip().splitlines()
    assert len(lines) == 1
    payload = json.loads(lines[0])
    assert payload["task_id"] == "task123"
    assert payload["baseline"]["metrics"]["arc_agi2_exact"] == 0.5
    assert payload["baseline"]["reason"] == "composite reason"
    assert payload["baseline"]["best_code"] == "def transform(grid): return grid"
    assert payload["baseline"]["cost"] == 1.23
    assert payload["final"]["score"] == 0.9
    assert payload["final"]["trials_used"] == 2
    assert payload["final"]["llm_calls"] == 5
    assert payload["final"]["cost"] == final_cost
