"""Modal Volume storage operations (pure functions)."""

import hashlib
import json
from pathlib import Path
from typing import Any

from benchmarks.core.benchmark_task import TaskResult
from benchmarks.utils.serialization import make_serializable


def save_result_to_volume(result: TaskResult, run_id: str, volume: Any) -> dict:
    """
    Save TaskResult to Modal Volume as JSON.

    Results are saved to: /results/{run_id}/tasks/{task_id}.json

    Args:
        result: TaskResult object to save
        run_id: Unique identifier for this benchmark run
        volume: Modal Volume object

    Returns:
        Dictionary representation of the result
    """
    # Create directory structure
    results_dir = Path("/results") / run_id / "tasks"
    results_dir.mkdir(parents=True, exist_ok=True)

    # Convert TaskResult to dict (handle Pydantic serialization)
    result_dict = (
        result.model_dump() if hasattr(result, "model_dump") else result.dict()
    )  # type: ignore

    # Convert non-serializable objects to strings
    result_dict = make_serializable(result_dict)

    # Save to JSON file (sanitize filename and add short hash to avoid collisions)
    safe_task_id = result.id.replace("/", "_")
    short_hash = hashlib.sha256(result.id.encode()).hexdigest()[:6]
    result_file = results_dir / f"{safe_task_id}_{short_hash}.json"
    with open(result_file, "w") as f:
        json.dump(result_dict, f, indent=2)

    # Commit changes to Volume (makes them visible to other functions)
    volume.commit()

    return result_dict


def save_metadata_to_volume(run_id: str, metadata: dict, volume: Any) -> None:
    """
    Save run metadata to Modal Volume.

    Args:
        run_id: Unique identifier for this benchmark run
        metadata: Metadata dictionary to save
        volume: Modal Volume object
    """
    run_dir = Path("/results") / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    metadata_file = run_dir / "metadata.json"
    with open(metadata_file, "w") as f:
        json.dump(metadata, f, indent=2)

    volume.commit()


def save_call_ids_to_volume(run_id: str, call_ids: list[dict], volume: Any) -> None:
    """
    Save function call IDs to Modal Volume.

    Args:
        run_id: Unique identifier for this benchmark run
        call_ids: List of function call ID dictionaries
        volume: Modal Volume object
    """
    run_dir = Path("/results") / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    call_ids_file = run_dir / "call_ids.json"
    with open(call_ids_file, "w") as f:
        json.dump(call_ids, f, indent=2)

    volume.commit()


def load_run_results_from_volume(run_id: str) -> dict:
    """
    Load all results for a specific run from Modal Volume.

    Args:
        run_id: Unique identifier for the benchmark run

    Returns:
        Dictionary containing:
        - metadata: Run configuration
        - tasks: List of task results
        - call_ids: List of function call IDs
        - summary: Summary statistics
    """
    run_dir = Path("/results") / run_id

    if not run_dir.exists():
        return {"error": f"Run {run_id} not found"}

    # Load metadata
    metadata_file = run_dir / "metadata.json"
    metadata = {}
    if metadata_file.exists():
        with open(metadata_file) as f:
            metadata = json.load(f)

    # Load call IDs
    call_ids_file = run_dir / "call_ids.json"
    call_ids = []
    if call_ids_file.exists():
        with open(call_ids_file) as f:
            call_ids = json.load(f)

    # Load task results
    tasks_dir = run_dir / "tasks"
    tasks = []
    if tasks_dir.exists():
        for task_file in tasks_dir.glob("*.json"):
            try:
                with open(task_file) as f:
                    task_data = json.load(f)
                    tasks.append(task_data)
            except Exception as e:
                print(f"⚠️  Error loading {task_file.name}: {e}")

    return {
        "metadata": metadata,
        "tasks": tasks,
        "call_ids": call_ids,
    }


def list_available_runs_from_volume() -> list[dict]:
    """
    List all available benchmark runs in the Modal Volume.

    Returns:
        List of run metadata dictionaries
    """
    results_dir = Path("/results")

    if not results_dir.exists():
        return []

    runs = []
    for run_dir in results_dir.iterdir():
        if run_dir.is_dir() and run_dir.name.startswith("run_"):
            metadata_file = run_dir / "metadata.json"
            if metadata_file.exists():
                with open(metadata_file) as f:
                    metadata = json.load(f)
                    runs.append(metadata)
            else:
                # No metadata, just include basic info
                runs.append(
                    {
                        "run_id": run_dir.name,
                        "timestamp": "unknown",
                    }
                )

    return sorted(runs, key=lambda x: x.get("timestamp", ""), reverse=True)
