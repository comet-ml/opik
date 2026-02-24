"""Type definitions for evaluation suite results."""

from __future__ import annotations

import dataclasses
from typing import Dict, List, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from opik.evaluation import evaluation_result, test_result


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
    """Total number of runs for this item."""

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
    ) -> None:
        self._items_passed = items_passed
        self._items_total = items_total
        self._item_results = item_results
        self._evaluation_result = evaluation_result_

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
    def pass_rate(self) -> float:
        """Pass rate: items_passed / items_total (0.0 to 1.0)."""
        if self._items_total == 0:
            return 1.0
        return self._items_passed / self._items_total

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
