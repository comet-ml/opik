from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from benchmarks.core.results import TaskResult
from benchmarks.utils.helpers import make_serializable


def volume_name() -> str:
    return "opik-benchmark-results"


def save_result_to_volume(
    result: TaskResult, run_id: str, volume: Any
) -> dict[str, Any]:
    results_dir = Path("/results") / run_id / "tasks"
    results_dir.mkdir(parents=True, exist_ok=True)

    result_dict = (
        result.model_dump() if hasattr(result, "model_dump") else result.dict()
    )  # type: ignore[attr-defined]
    result_dict = make_serializable(result_dict)

    safe_task_id = result.id.replace("/", "_")
    short_hash = hashlib.sha256(result.id.encode()).hexdigest()[:6]
    result_file = results_dir / f"{safe_task_id}_{short_hash}.json"
    with open(result_file, "w") as f:
        json.dump(result_dict, f, indent=2)

    volume.commit()
    return result_dict


def save_metadata_to_volume(run_id: str, metadata: dict[str, Any], volume: Any) -> None:
    run_dir = Path("/results") / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    metadata_file = run_dir / "metadata.json"
    with open(metadata_file, "w") as f:
        json.dump(metadata, f, indent=2)

    volume.commit()


def save_call_ids_to_volume(
    run_id: str, call_ids: list[dict[str, Any]], volume: Any
) -> None:
    run_dir = Path("/results") / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    call_ids_file = run_dir / "call_ids.json"
    with open(call_ids_file, "w") as f:
        json.dump(call_ids, f, indent=2)

    volume.commit()


def load_run_results_from_volume(run_id: str) -> dict[str, Any]:
    run_dir = Path("/results") / run_id

    if not run_dir.exists():
        return {"error": f"Run {run_id} not found"}

    metadata_file = run_dir / "metadata.json"
    metadata: dict[str, Any] = {}
    if metadata_file.exists():
        with open(metadata_file) as f:
            metadata = json.load(f)

    call_ids_file = run_dir / "call_ids.json"
    call_ids: list[dict[str, Any]] = []
    if call_ids_file.exists():
        with open(call_ids_file) as f:
            call_ids = json.load(f)

    tasks_dir = run_dir / "tasks"
    tasks: list[dict[str, Any]] = []
    if tasks_dir.exists():
        for task_file in tasks_dir.glob("*.json"):
            try:
                with open(task_file) as f:
                    tasks.append(json.load(f))
            except Exception as e:
                print(f"Warning: Error loading {task_file.name}: {e}")

    return {
        "metadata": metadata,
        "tasks": tasks,
        "call_ids": call_ids,
    }


def list_available_runs_from_volume() -> list[dict[str, Any]]:
    results_dir = Path("/results")
    if not results_dir.exists():
        return []

    runs: list[dict[str, Any]] = []
    for run_dir in results_dir.iterdir():
        if run_dir.is_dir() and run_dir.name.startswith("run_"):
            metadata_file = run_dir / "metadata.json"
            if metadata_file.exists():
                with open(metadata_file) as f:
                    metadata = json.load(f)
                    runs.append(metadata)
            else:
                runs.append({"run_id": run_dir.name, "timestamp": "unknown"})

    return sorted(runs, key=lambda x: x.get("timestamp", ""), reverse=True)
