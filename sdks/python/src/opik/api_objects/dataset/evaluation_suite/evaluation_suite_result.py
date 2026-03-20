"""EvaluationSuiteResult and ItemResult types."""

from __future__ import annotations

import dataclasses
from typing import Any, Dict, List, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from opik.evaluation import evaluation_result, test_result
    from opik.evaluation.metrics.score_result import ScoreResult


def is_score_passed(score: ScoreResult) -> bool:
    """Determine whether a score result represents a passing assertion."""
    if score.scoring_failed:
        return False
    return (isinstance(score.value, bool) and score.value) or score.value == 1


@dataclasses.dataclass
class ItemResult:
    """Result for a single evaluation suite item."""

    dataset_item_id: str
    """The ID of the dataset item."""

    passed: bool
    """Whether this item passed based on its execution policy."""

    runs_passed: int
    """Number of runs that passed for this item."""

    runs_total: int
    """Total number of runs completed for this item."""

    configured_runs_per_item: int
    """Configured runs_per_item from the execution policy."""

    pass_threshold: int
    """Minimum passing runs required (from execution policy)."""

    test_results: List[test_result.TestResult]
    """Individual test results for each run of this item."""


class EvaluationSuiteResult:
    """
    Result of running an evaluation suite.

    Contains pass/fail status for each item based on execution policy,
    as well as overall suite pass/fail status.
    """

    def __init__(
        self,
        items_passed: int,
        items_total: int,
        item_results: Dict[str, ItemResult],
        evaluation_result_: evaluation_result.EvaluationResult,
        suite_name: Optional[str] = None,
        total_time: Optional[float] = None,
    ) -> None:
        self._items_passed = items_passed
        self._items_total = items_total
        self._item_results = item_results
        self._evaluation_result = evaluation_result_
        self._suite_name = suite_name
        self._total_time = total_time

    @property
    def all_items_passed(self) -> bool:
        """Whether all items in the suite passed."""
        return self._items_passed == self._items_total

    @property
    def items_passed(self) -> int:
        """Number of items that passed."""
        return self._items_passed

    @property
    def items_total(self) -> int:
        """Total number of items evaluated."""
        return self._items_total

    @property
    def item_results(self) -> Dict[str, ItemResult]:
        """Results for each item, keyed by dataset_item_id."""
        return self._item_results

    @property
    def pass_rate(self) -> Optional[float]:
        """Pass rate: items_passed / items_total (0.0 to 1.0), or None if no items."""
        if self._items_total == 0:
            return None
        return self._items_passed / self._items_total

    @property
    def suite_name(self) -> Optional[str]:
        """The name of the evaluation suite."""
        return self._suite_name

    @property
    def total_time(self) -> Optional[float]:
        """Total evaluation time in seconds."""
        return self._total_time

    @property
    def experiment_id(self) -> str:
        """The experiment ID."""
        return self._evaluation_result.experiment_id

    @property
    def experiment_name(self) -> Optional[str]:
        """The experiment name."""
        return self._evaluation_result.experiment_name

    @property
    def experiment_url(self) -> Optional[str]:
        """URL to view the experiment."""
        return self._evaluation_result.experiment_url

    def to_report_dict(self) -> Dict[str, Any]:
        """Convert the result to a structured report dictionary."""
        from datetime import datetime, timezone

        items: List[Dict[str, Any]] = []

        for item_id, item_result in self._item_results.items():
            runs: List[Dict[str, Any]] = []

            for test_result_ in item_result.test_results:
                assertions: List[Dict[str, Any]] = []
                for score in test_result_.score_results:
                    assertion: Dict[str, Any] = {
                        "name": score.name,
                        "passed": is_score_passed(score),
                        "value": score.value,
                        "scoring_failed": score.scoring_failed,
                    }
                    if score.reason is not None:
                        assertion["reason"] = score.reason
                    if score.metadata is not None:
                        assertion["metadata"] = score.metadata
                    assertions.append(assertion)

                run_passed = (
                    all(a["passed"] for a in assertions) if assertions else True
                )

                run: Dict[str, Any] = {
                    "trial_id": test_result_.trial_id,
                    "passed": run_passed,
                    "input": test_result_.test_case.task_output.get("input"),
                    "output": test_result_.test_case.task_output.get("output"),
                    "assertions": assertions,
                }
                if test_result_.test_case.trace_id:
                    run["trace_id"] = test_result_.test_case.trace_id
                if test_result_.task_execution_time is not None:
                    run["task_execution_time_seconds"] = round(
                        test_result_.task_execution_time, 3
                    )
                if test_result_.scoring_time is not None:
                    run["scoring_time_seconds"] = round(test_result_.scoring_time, 3)
                runs.append(run)

            items.append(
                {
                    "dataset_item_id": item_id,
                    "passed": item_result.passed,
                    "runs_passed": item_result.runs_passed,
                    "execution_policy": {
                        "runs_per_item": item_result.configured_runs_per_item,
                        "pass_threshold": item_result.pass_threshold,
                    },
                    "runs": runs,
                }
            )

        report: Dict[str, Any] = {
            "suite_passed": self.all_items_passed,
            "items_passed": self._items_passed,
            "items_total": self._items_total,
            "pass_rate": self.pass_rate,
            "experiment_id": self.experiment_id,
        }

        if self._suite_name is not None:
            report["suite_name"] = self._suite_name

        if self.experiment_name is not None:
            report["experiment_name"] = self.experiment_name

        if self.experiment_url is not None:
            report["experiment_url"] = self.experiment_url

        if self._total_time is not None:
            report["total_time_seconds"] = round(self._total_time, 3)

        report["generated_at"] = datetime.now(timezone.utc).isoformat()
        report["items"] = items

        return report

    def to_dict(self) -> Dict[str, Any]:
        """Alias for to_report_dict()."""
        return self.to_report_dict()
