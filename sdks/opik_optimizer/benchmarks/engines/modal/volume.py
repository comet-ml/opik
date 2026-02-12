from __future__ import annotations

from typing import Any

from benchmarks.modal_utils.storage import (
    list_available_runs_from_volume,
    load_run_results_from_volume,
    save_call_ids_to_volume,
    save_metadata_to_volume,
    save_result_to_volume,
)

__all__ = [
    "save_result_to_volume",
    "save_metadata_to_volume",
    "save_call_ids_to_volume",
    "load_run_results_from_volume",
    "list_available_runs_from_volume",
]


def volume_name() -> str:
    return "opik-benchmark-results"
