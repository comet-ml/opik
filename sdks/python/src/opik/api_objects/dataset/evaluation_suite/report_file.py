"""Save structured JSON report files for evaluation suite results."""

from __future__ import annotations

import json
import logging
import os
from typing import Any, Dict

LOGGER = logging.getLogger(__name__)

DEFAULT_REPORT_DIR = "opik_evaluation_suite_reports"


def build_default_report_path(experiment_name: str) -> str:
    """Build a default report file path from an experiment name."""
    safe_name = _sanitize_filename(experiment_name)
    return os.path.abspath(
        os.path.join(DEFAULT_REPORT_DIR, f"{safe_name}.json")
    )


def save_report_file(
    report_dict: Dict[str, Any],
    output_path: str,
) -> str:
    """Save a report dictionary as a JSON file.

    Args:
        report_dict: The report data to write.
        output_path: The file path to write to.

    Returns:
        The absolute path to the written report file.
    """
    output_path = os.path.abspath(output_path)
    parent_dir = os.path.dirname(output_path)
    if parent_dir:
        os.makedirs(parent_dir, exist_ok=True)

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report_dict, f, indent=2, default=str, ensure_ascii=False)

    LOGGER.debug("Evaluation suite report saved to %s", output_path)
    return output_path


def _sanitize_filename(name: str) -> str:
    """Replace characters that are unsafe in file names."""
    return "".join(c if c.isalnum() or c in "-_." else "_" for c in name)
