from typing import Any, Optional

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
        self, output: str, expected_output: Optional[str] = None, reference: Optional[str] = None, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the Levenshtein ratio between the output and reference strings.

        Args:
            output: The output string to compare.
            expected_output: The expected output string to compare against.
            reference: This parameter is deprecated and will be removed in a future version. Please use expected_output instead.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value between 0.0 and 1.0,
                representing the Levenshtein ratio between the output and reference strings.
        """
        value = output if self._case_sensitive else output.lower()
        
        if expected_output is not None:
            value_right = expected_output if self._case_sensitive else expected_output.lower()
        elif expected_output is None and reference is not None:
            value_right = reference if self._case_sensitive else reference.lower()
        else:
            raise TypeError("score() missing 1 required argument: 'expected_output'")

        score = Levenshtein.ratio(value, value_right)

        return score_result.ScoreResult(value=score, name=self.name)
