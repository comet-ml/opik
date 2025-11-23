import json
from pathlib import Path


from benchmarks.configs.benchmark_manifest import load_manifest, manifest_to_task_specs


def test_manifest_tasks_and_datasets(tmp_path: Path) -> None:
    data = {
        "seed": 42,
        "tasks": [
            {
                "dataset": "hotpot",
                "optimizer": "few_shot",
                "model": "model-a",
            },
            {
                "dataset": {"loader": "tiny_test", "count": 5},
                "optimizer": "few_shot",
                "model": "model-b",
                "datasets": {
                    "train": {"loader": "tiny_test", "count": 5},
                    "validation": {"loader": "tiny_test", "count": 2},
                },
            },
            {
                # Single override object without explicit splits should map to train-only.
                "dataset": {"loader": "tiny_test", "count": 3},
                "optimizer": "few_shot",
                "model": "model-c",
            },
        ],
    }
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(data))

    manifest = load_manifest(str(manifest_path))
    tasks = manifest_to_task_specs(manifest)

    assert len(tasks) == 3
    assert tasks[0].dataset_name == "hotpot"
    assert tasks[0].datasets is None
    assert tasks[1].dataset_name == "tiny_test"
    assert tasks[1].datasets is not None
    assert "train" in tasks[1].datasets
    assert "validation" in tasks[1].datasets
    # Third task uses the loader override applied to train only.
    assert tasks[2].dataset_name == "tiny_test"
    assert tasks[2].datasets is not None
    assert set(tasks[2].datasets.keys()) == {"train"}


def test_manifest_generators_expand(tmp_path: Path) -> None:
    data = {
        "seed": 1,
        "generators": [
            {
                "datasets": [
                    {"dataset": "hotpot"},
                    {"dataset": {"loader": "tiny_test", "count": 2}},
                ],
                "models": [{"name": "model-a"}, {"name": "model-b"}],
                "optimizers": [
                    {"name": "few_shot", "optimizer_prompt_params": {"max_trials": 1}},
                    {"name": "evolutionary_optimizer"},
                ],
                "metrics": ["benchmarks.metrics.hotpot.hotpot_f1"],
                "test_mode": True,
                "prompt": [
                    {
                        "role": "system",
                        "content": "Answer the question briefly and correctly.",
                    },
                    {"role": "user", "content": "{text}"},
                ],
            }
        ],
    }
    manifest_path = tmp_path / "manifest_gen.json"
    manifest_path.write_text(json.dumps(data))

    manifest = load_manifest(str(manifest_path))
    tasks = manifest_to_task_specs(manifest)

    # 2 datasets * 2 models * 2 optimizers = 8 tasks
    assert len(tasks) == 8
    # Ensure metrics propagated
    assert all(
        task.metrics == ["benchmarks.metrics.hotpot.hotpot_f1"] for task in tasks
    )
    # Ensure dataset override expansion
    tiny_tasks = [t for t in tasks if t.dataset_name == "tiny_test"]
    assert tiny_tasks and tiny_tasks[0].datasets is not None
    # Prompt override propagated to all tasks
    assert all(task.prompt_messages for task in tasks)
