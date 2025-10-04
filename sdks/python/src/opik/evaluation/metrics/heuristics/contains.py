from typing import Any, Optional
from .. import base_metric, score_result 


class Contains(base_metric.BaseMetric):
    """
    A metric that checks if a reference string is contained within an output string.

    This metric returns a score of 1.0 if the reference string is found within the output string,
    and 0.0 otherwise. The comparison can be made case-sensitive or case-insensitive.

    Args:
        case_sensitive: Whether the comparison should be case-sensitive. Defaults to False.
        name: The name of the metric. Defaults to "contains_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when there are no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import Contains
        >>> contains_metric = Contains(case_sensitive=True)
        >>> result = contains_metric.score("Hello, World!", "World")
        >>> print(result.value)
        1.0
        >>> result = contains_metric.score("Hello, World!", "world")
        >>> print(result.value)
        0.0
    """

    def __init__(
        self,
        reference: Optional[str] = None, # Reference added as optional
        case_sensitive: bool = False,
        name: str = "contains_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )

        self._case_sensitive = case_sensitive
        self._reference = reference

    def score(
        self, output: str, reference: Optional[str] = None, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the score based on whether the reference string is contained in the output string.
        """

        ref_to_use = reference if reference is not None else self._reference

        if ref_to_use is None:
            raise ValueError(
                "Reference string must be provided either during initialization or to the score method."
            )

        if ref_to_use == "":
            raise ValueError("Reference string cannot be empty.")

        # Case handling
        output_to_check = output if self._case_sensitive else output.lower()
        ref_processed = ref_to_use if self._case_sensitive else ref_to_use.lower()

        value = 1.0 if ref_processed in output_to_check else 0.0
        return score_result.ScoreResult(value=value, name=self.name)
