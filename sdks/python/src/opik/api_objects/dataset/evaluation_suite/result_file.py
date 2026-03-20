"""Generate structured JSON report files from EvaluationSuiteResult."""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from . import types as suite_types

LOGGER = logging.getLogger(__name__)

DEFAULT_REPORT_DIR = "opik_evaluation_suite_reports"


def suite_result_to_dict(
    suite_result: suite_types.EvaluationSuiteResult,
) -> Dict[str, Any]:
    """Convert an EvaluationSuiteResult to a structured dictionary."""
    items: List[Dict[str, Any]] = []

    for item_id, item_result in suite_result.item_results.items():
        runs: List[Dict[str, Any]] = []

        for test_result in item_result.test_results:
            assertions: List[Dict[str, Any]] = []
            for score in test_result.score_results:
                assertion: Dict[str, Any] = {
                    "name": score.name,
                    "passed": (
                        not score.scoring_failed
                        and (
                            (isinstance(score.value, bool) and score.value)
                            or score.value == 1
                        )
                    ),
                    "value": score.value,
                    "scoring_failed": score.scoring_failed,
                }
                if score.reason is not None:
                    assertion["reason"] = score.reason
                if score.metadata is not None:
                    assertion["metadata"] = score.metadata
                assertions.append(assertion)

            run_passed = all(a["passed"] for a in assertions) if assertions else True

            run: Dict[str, Any] = {
                "trial_id": test_result.trial_id,
                "passed": run_passed,
                "input": test_result.test_case.task_output.get("input"),
                "output": test_result.test_case.task_output.get("output"),
                "assertions": assertions,
            }
            if test_result.test_case.trace_id:
                run["trace_id"] = test_result.test_case.trace_id
            if test_result.task_execution_time is not None:
                run["task_execution_time_seconds"] = round(
                    test_result.task_execution_time, 3
                )
            if test_result.scoring_time is not None:
                run["scoring_time_seconds"] = round(test_result.scoring_time, 3)
            runs.append(run)

        items.append(
            {
                "dataset_item_id": item_id,
                "passed": item_result.passed,
                "runs_passed": item_result.runs_passed,
                "execution_policy": {
                    "runs_per_item": item_result.runs_total,
                    "pass_threshold": item_result.pass_threshold,
                },
                "runs": runs,
            }
        )

    report: Dict[str, Any] = {
        "suite_passed": suite_result.all_items_passed,
        "items_passed": suite_result.items_passed,
        "items_total": suite_result.items_total,
        "pass_rate": suite_result.pass_rate,
        "experiment_id": suite_result.experiment_id,
    }

    if suite_result.suite_name is not None:
        report["suite_name"] = suite_result.suite_name

    if suite_result.experiment_name is not None:
        report["experiment_name"] = suite_result.experiment_name

    if suite_result.experiment_url is not None:
        report["experiment_url"] = suite_result.experiment_url

    if suite_result.total_time is not None:
        report["total_time_seconds"] = round(suite_result.total_time, 3)

    report["generated_at"] = datetime.now(timezone.utc).isoformat()
    report["items"] = items

    return report


def save_report(
    suite_result: suite_types.EvaluationSuiteResult,
    output_path: Optional[str] = None,
) -> str:
    """Save an EvaluationSuiteResult as a structured JSON report.

    Args:
        suite_result: The evaluation suite result to serialize.
        output_path: Optional file path. If not provided, a default path
            is generated under the ``opik_evaluation_suite_reports/`` directory.

    Returns:
        The absolute path to the written report file.
    """
    report = suite_result_to_dict(suite_result)

    if output_path is None:
        os.makedirs(DEFAULT_REPORT_DIR, exist_ok=True)
        experiment_name = suite_result.experiment_name or suite_result.experiment_id
        safe_name = _sanitize_filename(experiment_name)
        output_path = os.path.join(DEFAULT_REPORT_DIR, f"{safe_name}.json")

    output_path = os.path.abspath(output_path)
    parent_dir = os.path.dirname(output_path)
    if parent_dir:
        os.makedirs(parent_dir, exist_ok=True)

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, default=str, ensure_ascii=False)

    LOGGER.info("Evaluation suite report saved to %s", output_path)
    return output_path


def _sanitize_filename(name: str) -> str:
    """Replace characters that are unsafe in file names."""
    return "".join(c if c.isalnum() or c in "-_." else "_" for c in name)
