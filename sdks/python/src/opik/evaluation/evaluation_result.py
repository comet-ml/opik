from typing import List, Optional, Dict
from collections import defaultdict
import statistics
import logging
import math

import dataclasses

from . import test_result

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class ScoreStatistics:
    """Statistics for a single score metric across multiple trials."""

    mean: float
    max: float
    min: float
    values: List[float]
    std: Optional[float] = None  # Standard deviation (None if count < 2)


@dataclasses.dataclass
class DatasetItemResults:
    """Results for a single dataset item across all trials."""

    test_results: List[test_result.TestResult]
    scores: Dict[str, ScoreStatistics]


@dataclasses.dataclass
class EvaluationResultGroupByDatasetItemsView:
    """View of evaluation results grouped by dataset items."""

    experiment_id: str
    dataset_id: str
    experiment_name: Optional[str]
    experiment_url: Optional[str]
    trial_count: int
    dataset_items: Dict[str, DatasetItemResults]


@dataclasses.dataclass
class EvaluationResult:
    experiment_id: str
    dataset_id: str
    experiment_name: Optional[str]
    test_results: List[test_result.TestResult]
    experiment_url: Optional[str]
    trial_count: int

    def group_by_dataset_item_view(self) -> EvaluationResultGroupByDatasetItemsView:
        """
        Create a view of evaluation results grouped by dataset items.

        Returns:
            EvaluationResultGroupByDatasetItemsView containing organized results with aggregated score statistics
        """
        dataset_items = self._build_results_per_dataset_item()

        return EvaluationResultGroupByDatasetItemsView(
            experiment_id=self.experiment_id,
            dataset_id=self.dataset_id,
            experiment_name=self.experiment_name,
            experiment_url=self.experiment_url,
            trial_count=self.trial_count,
            dataset_items=dataset_items,
        )

    def _build_results_per_dataset_item(self) -> Dict[str, DatasetItemResults]:
        """
        Build dataset item results with aggregated score statistics.

        Returns:
            Dict mapping dataset item IDs to their results and aggregated score statistics
        """
        if not self.test_results:
            LOGGER.debug("No test results available for aggregation")
            return {}

        results_by_dataset_item = defaultdict(list)
        for test_result_ in self.test_results:
            dataset_item_id = test_result_.test_case.dataset_item_id
            results_by_dataset_item[dataset_item_id].append(test_result_)

        dataset_items_results = {}
        for dataset_item_id, dataset_item_results in results_by_dataset_item.items():
            dataset_item_results.sort(key=lambda x: x.trial_id)
            aggregated_scores = self._calculate_aggregated_scores(dataset_item_results)

            dataset_items_results[dataset_item_id] = DatasetItemResults(
                test_results=dataset_item_results, scores=aggregated_scores
            )

        return dataset_items_results

    def _calculate_aggregated_scores(
        self, dataset_item_results: List[test_result.TestResult]
    ) -> Dict[str, ScoreStatistics]:
        """
        Calculate mean, max, and min scores for each score name across all trials for a dataset item.

        Args:
            dataset_item_results: List of TestResult objects for a specific dataset item across trials

        Returns:
            Dict mapping score names to their aggregated statistics
        """
        if not dataset_item_results:
            return {}

        # Group scores by name across all trials
        scores_by_name = defaultdict(list)

        for test_result_ in dataset_item_results:
            for score_result in test_result_.score_results:
                # Only include successful scores with valid values
                if not score_result.scoring_failed and _is_valid_score_value(
                    score_result.value
                ):
                    scores_by_name[score_result.name].append(score_result.value)

        # Calculate aggregated statistics for each score name
        aggregated_scores = {}
        for score_name, values in scores_by_name.items():
            if values:
                std = statistics.stdev(values) if len(values) >= 2 else None

                aggregated_scores[score_name] = ScoreStatistics(
                    mean=statistics.mean(values),
                    max=max(values),
                    min=min(values),
                    values=values.copy(),  # Store the actual values used
                    std=std,
                )

        return aggregated_scores


def _is_valid_score_value(value: float) -> bool:
    """Check if a score value is valid for statistical calculations."""
    return isinstance(value, (int, float)) and math.isfinite(value)
