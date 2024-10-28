from typing import Any

import Levenshtein

from .. import base_metric, score_result


class LevenshteinRatio(base_metric.BaseMetric):
    """
    A metric that calculates the Levenshtein ratio between two strings.

    This metric returns a score between 0.0 and 1.0, representing the similarity
    between the output and reference strings. A score of 1.0 indicates identical strings,
    while 0.0 indicates completely different strings. The comparison can be made
    case-sensitive or case-insensitive.

    For more information on Levenshtein distance, see:
    https://en.wikipedia.org/wiki/Levenshtein_distance

    Args:
        case_sensitive: Whether the comparison should be case-sensitive. Defaults to False.
        name: The name of the metric. Defaults to "levenshtein_ratio_metric".

    Example:
        >>> from opik.evaluation.metrics import LevenshteinRatio
        >>> levenshtein_metric = LevenshteinRatio(case_sensitive=True)
        >>> result = levenshtein_metric.score("Hello, World!", "Hello, World")
        >>> print(result.value)
        0.96
    """

    def __init__(
        self,
        case_sensitive: bool = False,
        name: str = "levenshtein_ratio_metric",
    ):
        super().__init__(name=name)

        self._case_sensitive = case_sensitive

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the Levenshtein ratio between the output and reference strings.

        Args:
            output: The output string to compare.
            reference: The reference string to compare against.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value between 0.0 and 1.0,
                representing the Levenshtein ratio between the output and reference strings.
        """
        value = output if self._case_sensitive else output.lower()
        reference = reference if self._case_sensitive else reference.lower()

        score = Levenshtein.ratio(value, reference)

        return score_result.ScoreResult(value=score, name=self.name)
