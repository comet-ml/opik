import sys

import pytest

from benchmarks import run_benchmark as benchmark_cli
from benchmarks.core.planning import TaskPlan
from benchmarks.core.types import RunSummary


def _empty_plan() -> TaskPlan:
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


def test_run_benchmark_modal_alias_dispatches_to_modal_engine(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(benchmark_cli, "list_engines", lambda: ["local", "modal"])
    monkeypatch.setattr(benchmark_cli, "compile_task_plan", lambda _: _empty_plan())

    called: dict[str, str] = {}

    def _fake_run_plan(engine_name: str, _plan: TaskPlan) -> RunSummary:
        called["engine"] = engine_name
        return RunSummary(engine=engine_name, run_id="run-1", status="succeeded")

    monkeypatch.setattr(benchmark_cli, "run_plan", _fake_run_plan)
    monkeypatch.setattr(
        sys,
        "argv",
        ["run_benchmark.py", "--modal"],
    )

    benchmark_cli.main()
    assert called["engine"] == "modal"


def test_run_benchmark_deploy_engine_uses_deploy_path(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(benchmark_cli, "list_engines", lambda: ["local", "modal"])
    monkeypatch.setattr(benchmark_cli, "compile_task_plan", lambda _: _empty_plan())

    called: dict[str, str] = {}

    def _fake_deploy_engine(engine_name: str) -> RunSummary:
        called["engine"] = engine_name
        return RunSummary(engine=engine_name, run_id=None, status="deployed")

    monkeypatch.setattr(benchmark_cli, "deploy_engine", _fake_deploy_engine)
    monkeypatch.setattr(
        benchmark_cli,
        "run_plan",
        lambda *_args, **_kwargs: pytest.fail("run_plan should not be called"),
    )
    monkeypatch.setattr(
        sys,
        "argv",
        ["run_benchmark.py", "--engine", "modal", "--deploy-engine"],
    )

    benchmark_cli.main()
    assert called["engine"] == "modal"
