"""Save structured JSON report files for evaluation suite results."""

from __future__ import annotations

import json
import logging
import os
from typing import Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from .. import evaluation_suite_result as _result_mod

LOGGER = logging.getLogger(__name__)

DEFAULT_REPORT_DIR = "opik_evaluation_suite_reports"


def save_report(
    suite_result: _result_mod.EvaluationSuiteResult,
    output_path: Optional[str] = None,
) -> str:
    """Save an evaluation suite result as a structured JSON report file.

    Args:
        suite_result: The evaluation suite result to serialize.
        output_path: Optional file path. If not provided, a default path
            is generated under the ``opik_evaluation_suite_reports/`` directory.

    Returns:
        The absolute path to the written report file.
    """
    report_dict = suite_result.to_report_dict()

    if output_path is None:
        output_path = build_default_report_path(
            suite_result.experiment_name or suite_result.experiment_id
        )

    output_path = os.path.abspath(output_path)
    parent_dir = os.path.dirname(output_path)
    if parent_dir:
        os.makedirs(parent_dir, exist_ok=True)

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report_dict, f, indent=2, default=str, ensure_ascii=False)

    LOGGER.debug("Evaluation suite report saved to %s", output_path)
    return output_path


def build_default_report_path(experiment_name: str) -> str:
    """Build a default report file path from an experiment name."""
    safe_name = _sanitize_filename(experiment_name)
    return os.path.abspath(os.path.join(DEFAULT_REPORT_DIR, f"{safe_name}.json"))


def _sanitize_filename(name: str) -> str:
    """Replace characters that are unsafe in file names."""
    return "".join(c if c.isalnum() or c in "-_." else "_" for c in name)
