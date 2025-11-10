import dataclasses
import math
import statistics
from collections import defaultdict
from typing import List, Optional, Dict

from opik.evaluation import test_result


@dataclasses.dataclass
class ScoreStatistics:
    """Statistics for a single score metric across multiple trials."""

    mean: float
    max: float
    min: float
    values: List[float]
    std: Optional[float] = None  # Standard deviation (None if count < 2)


def calculate_aggregated_statistics(
    evaluation_results: List[test_result.TestResult],
) -> Dict[str, ScoreStatistics]:
    """
    Calculate mean, max, and min scores for each score name in the evaluation test results.

    Args:
        evaluation_results: List of TestResult objects to be aggregated

    Returns:
        Dict mapping score names to their aggregated statistics
    """
    if not evaluation_results:
        return {}

    # Group scores by name across all trials
    scores_by_name = defaultdict(list)

    for test_result_ in evaluation_results:
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
