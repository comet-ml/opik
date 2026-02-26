import pytest

from benchmarks.core.planning import TaskPlan
from benchmarks.core.runtime import run_plan
from benchmarks.engines.base import EngineRunResult


def _plan() -> TaskPlan:
    return TaskPlan(
        tasks=[],
        demo_datasets=[],
        optimizers=[],
        models=[],
        seed=42,
        test_mode=False,
        max_concurrent=1,
        checkpoint_dir="/tmp",
    )


def test_run_plan_propagates_engine_status(monkeypatch: pytest.MonkeyPatch) -> None:
    class _Engine:
        capabilities = object()

        def run(self, _plan: TaskPlan) -> EngineRunResult:
            return EngineRunResult(
                engine="local",
                run_id="run-1",
                status="failed",
                metadata={"failed_tasks": 1},
            )

    monkeypatch.setattr("benchmarks.core.runtime.get_engine", lambda _name: _Engine())
    summary = run_plan("local", _plan())

    assert summary.status == "failed"
    assert summary.metadata.get("failed_tasks") == 1
