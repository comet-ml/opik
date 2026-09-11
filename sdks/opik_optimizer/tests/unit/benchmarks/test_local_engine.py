import pytest

from benchmarks.core.planning import TaskPlan
from benchmarks.engines.local.engine import LocalEngine


def _plan(*, test_mode: bool = False, auto_confirm: bool = False) -> TaskPlan:
    return TaskPlan(
        tasks=[],
        demo_datasets=["tiny_test"],
        optimizers=["few_shot"],
        models=["openai/gpt-4o-mini"],
        seed=42,
        test_mode=test_mode,
        max_concurrent=1,
        checkpoint_dir="/tmp",
        auto_confirm=auto_confirm,
    )


def test_local_engine_aborts_when_user_declines_confirmation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def _decline(*_args: object, **_kwargs: object) -> None:
        raise SystemExit(0)

    monkeypatch.setattr(
        "benchmarks.engines.local.engine.ask_for_input_confirmation", _decline
    )
    monkeypatch.setattr(
        "benchmarks.engines.local.engine.BenchmarkRunner",
        lambda *_args, **_kwargs: pytest.fail(
            "BenchmarkRunner should not be constructed"
        ),
    )

    result = LocalEngine().run(_plan(test_mode=False, auto_confirm=False))
    assert result.status == "aborted"
    assert result.metadata == {"reason": "user_declined_confirmation"}
