"""Resume must preserve independent model settings or reject incompatible checkpoints."""

import json
from pathlib import Path

import pytest

from benchmarks.core.state import BenchmarkCheckpointManager
from benchmarks.core.types import TaskSpec


def checkpoint(folder: Path) -> BenchmarkCheckpointManager:
    return BenchmarkCheckpointManager(
        checkpoint_folder=str(folder),
        run_id="run-1",
        test_mode=True,
        demo_datasets=["toy"],
        optimizers=["meta_prompt"],
        models=["agent-model"],
        task_specs=[
            TaskSpec(
                dataset_name="toy",
                optimizer_name="meta_prompt",
                model_name="agent-model",
                test_mode=True,
                model_parameters={},
                optimizer_params={
                    "model": "reasoning-model",
                    "model_parameters": {"temperature": 0.8},
                },
            )
        ],
    )


def test_checkpoint_roundtrip_preserves_independent_model_settings(
    tmp_path: Path,
) -> None:
    writer = checkpoint(tmp_path)
    writer.save()
    reader = checkpoint(tmp_path)
    reader.task_specs = []
    reader.load()
    assert reader.task_specs == writer.task_specs
    assert reader.task_specs[0].model_parameters == {}
    assert reader.task_specs[0].optimizer_params == {
        "model": "reasoning-model",
        "model_parameters": {"temperature": 0.8},
    }


@pytest.mark.parametrize("version", [None, 0, 99])
def test_checkpoint_rejects_legacy_or_unknown_model_configuration(
    tmp_path: Path,
    version: int | None,
) -> None:
    manager = checkpoint(tmp_path)
    manager.save()
    path = Path(manager.checkpoint_file)
    payload = json.loads(path.read_text())
    if version is None:
        payload.pop("format_version", None)
    else:
        payload["format_version"] = version
    path.write_text(json.dumps(payload))
    with pytest.raises(ValueError, match="checkpoint.*version.*start a new benchmark"):
        manager.load()
