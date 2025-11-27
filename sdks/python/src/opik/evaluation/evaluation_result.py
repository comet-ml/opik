from typing import List, Optional, Dict, TYPE_CHECKING
from collections import defaultdict
import logging

import dataclasses

from . import score_statistics, test_result
from .metrics import score_result

if TYPE_CHECKING:
    pass

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class DatasetItemResults:
    """Results for a single dataset item across all trials."""

    test_results: List[test_result.TestResult]
    scores: Dict[str, score_statistics.ScoreStatistics]


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
class EvaluationResultAggregatedScoresView:
    """
    Represents an aggregated view of evaluation results and scores for an
    experiment.

    This class is designed to encapsulate information about an experiment,
    its related dataset, trial counts, test results, and aggregated score
    statistics. It serves as a comprehensive representation useful in
    evaluation pipelines or result summaries.

    Attributes:
        experiment_id: Unique identifier for the experiment.
        dataset_id: Unique identifier for the associated dataset.
        experiment_name: Human-readable name of the experiment.
        experiment_url: URL link to the experiment for easy access.
        trial_count: Number of trials conducted in the experiment.
        test_results: Collection of test results from the experiment.
        aggregated_scores: Aggregated statistical scores for evaluation
            results keyed by score name.
    """

    experiment_id: str
    dataset_id: str
    experiment_name: Optional[str]
    experiment_url: Optional[str]
    trial_count: int
    test_results: List[test_result.TestResult]
    aggregated_scores: Dict[str, score_statistics.ScoreStatistics]


@dataclasses.dataclass
class EvaluationResult:
    experiment_id: str
    dataset_id: str
    experiment_name: Optional[str]
    test_results: List[test_result.TestResult]
    experiment_url: Optional[str]
    trial_count: int
    experiment_scores: List[score_result.ScoreResult] = dataclasses.field(
        default_factory=list
    )

    def aggregate_evaluation_scores(self) -> EvaluationResultAggregatedScoresView:
        """
        Aggregates evaluation scores from test results and returns the aggregated scores view.

        The method calculates aggregated scores from test results and encapsulates the results
        in an EvaluationResultAggregatedScoresView object, which contains information about
        the experiment and computed aggregated scores.

        The aggregated scores dictionary has keys for each found score name and values containing
        the statistics for that score.

        Returns:
            EvaluationResultAggregatedScoresView object containing details about the
            experiment and the aggregated scores calculated from test results.
        """
        aggregated_scores = score_statistics.calculate_aggregated_statistics(
            self.test_results
        )
        return EvaluationResultAggregatedScoresView(
            experiment_id=self.experiment_id,
            dataset_id=self.dataset_id,
            experiment_name=self.experiment_name,
            experiment_url=self.experiment_url,
            trial_count=self.trial_count,
            test_results=self.test_results,
            aggregated_scores=aggregated_scores,
        )

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
            aggregated_scores = score_statistics.calculate_aggregated_statistics(
                dataset_item_results
            )

            dataset_items_results[dataset_item_id] = DatasetItemResults(
                test_results=dataset_item_results, scores=aggregated_scores
            )

        return dataset_items_results


@dataclasses.dataclass
class EvaluationResultOnDictItems:
    """
    Evaluation result for dict items evaluation without experiment tracking.

    This class provides a similar interface to EvaluationResult but is designed
    for lightweight evaluations that don't require experiment or dataset management.
    It can aggregate scores across test results just like the regular evaluation.

    Attributes:
        test_results: Collection of test results from the evaluation.
    """

    test_results: List[test_result.TestResult]

    def aggregate_evaluation_scores(
        self,
    ) -> Dict[str, score_statistics.ScoreStatistics]:
        """
        Aggregates evaluation scores from test results.

        Returns:
            Dictionary mapping score names to their aggregated statistics.
        """
        return score_statistics.calculate_aggregated_statistics(self.test_results)
