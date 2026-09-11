import pytest

from benchmarks.core.planning import PlanInput, compile_task_plan


def test_compile_task_plan_rejects_config_with_cli_filters() -> None:
    data = PlanInput(
        demo_datasets=["tiny_test"],
        optimizers=None,
        models=None,
        seed=42,
        test_mode=False,
        max_concurrent=1,
        checkpoint_dir="/tmp",
        config_path="manifest.json",
        retry_failed_run_id=None,
        resume_run_id=None,
    )

    with pytest.raises(ValueError, match="Cannot combine --config"):
        compile_task_plan(data)
