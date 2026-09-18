import dataclasses
import logging
import math
import statistics
from collections import defaultdict
from typing import List, Optional, Dict

from opik.evaluation import test_result

LOGGER = logging.getLogger(__name__)

# A metric name is user-supplied, so the one written into a log line is capped.
# TruncateFormatter caps a whole message the same way.
MAX_LOGGED_METRIC_NAME_LENGTH = 200


@dataclasses.dataclass
class ScoreStatistics:
    """Statistics for a single score metric across multiple trials."""

    mean: float
    max: float
    min: float
    values: List[float]
    std: Optional[float] = None  # Standard deviation (None if count < 2)
    # Scores left out of the statistics because their metric reported
    # ``scoring_failed``. ``len(values) + failed_count`` is how many times the
    # metric ran.
    failed_count: int = 0


def calculate_aggregated_statistics(
    evaluation_results: List[test_result.TestResult],
) -> Dict[str, ScoreStatistics]:
    """
    Calculate mean, max, and min scores for each score name in the evaluation test results.

    A score whose metric reported ``scoring_failed`` is not part of the statistics.
    The engine records ``0.0`` for such a score, so averaging it in would present a
    metric that crashed as one that deliberately scored zero. What is left out is
    now reported rather than silently shrinking the denominator:
    ``ScoreStatistics.failed_count`` says how many of that metric's scores were
    dropped for that reason, so a mean over 5 survivors can no longer be read as a
    mean over 10 trials.

    A metric whose every score failed has no denominator to average, so it stays
    out of the returned dictionary and is named in a WARNING instead. Scores
    dropped because their value is not a finite number are not counted here; that
    exclusion is unchanged by this function's failure accounting.

    Args:
        evaluation_results: List of TestResult objects to be aggregated

    Returns:
        Dict mapping score names to their aggregated statistics
    """
    if not evaluation_results:
        return {}

    # Group scores by name across all trials
    scores_by_name = defaultdict(list)
    failed_by_name: Dict[str, int] = defaultdict(int)

    for test_result_ in evaluation_results:
        for score_result in test_result_.score_results:
            if score_result.scoring_failed:
                failed_by_name[score_result.name] += 1
                continue

            # Only include successful scores with valid values
            if _is_valid_score_value(score_result.value):
                scores_by_name[score_result.name].append(score_result.value)

    for score_name in sorted(failed_by_name):
        scored_count = len(scores_by_name.get(score_name, []))
        LOGGER.warning(
            f"Excluded {failed_by_name[score_name]} {_metric_name_for_log(score_name)} "
            f"score(s) from the aggregated statistics because the metric reported the "
            f"scoring as failed; the statistics cover the {scored_count} remaining "
            f"score(s)."
        )

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
                failed_count=failed_by_name.get(score_name, 0),
            )

    return aggregated_scores


def _is_valid_score_value(value: float) -> bool:
    """Check if a score value is valid for statistical calculations."""
    return isinstance(value, (int, float)) and math.isfinite(value)


def _metric_name_for_log(name: str) -> str:
    """Represent a metric name so it cannot forge log records.

    Score names come from user code, and this one is written into a log line.
    ``repr`` escapes newlines and control characters, and the result is capped,
    which is the same sanitize-and-cap decision #8233 made for the names the
    backend reports on a rule's log.
    """
    text = repr(name)
    if len(text) > MAX_LOGGED_METRIC_NAME_LENGTH:
        text = text[:MAX_LOGGED_METRIC_NAME_LENGTH] + "... (truncated)."
    return text
