from typing import Any, Optional

from .. import base_metric, score_result


class Contains(base_metric.BaseMetric):
    """
    A metric that checks if a reference string is contained within an output string.

    This metric returns a score of 1.0 if the reference string is found within the output string,
    and 0.0 otherwise. The comparison can be made case-sensitive or case-insensitive.

    Args:
        case_sensitive: Whether the comparison should be case-sensitive. Defaults to False.
        reference: Optional default reference string. If provided, it will be used unless
            a reference is explicitly passed to `score()`.
        name: The name of the metric. Defaults to "contains_metric".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when there are
            no parent span/trace to inherit project name from.

    Examples:
        >>> # Using a default reference at initialization
        >>> contains_metric = Contains(reference="world")
        >>> result = contains_metric.score("Hello, World!")
        >>> print(result.value)
        1.0

        >>> # Overriding the default reference at score time
        >>> result = contains_metric.score("Hello, World!", reference="there")
        >>> print(result.value)
        0.0

        >>> # If no reference is set at all, score() raises an error
        >>> contains_metric = Contains()
        >>> contains_metric.score("Hello")
        Traceback (most recent call last):
            ...
        ValueError: No reference string provided. Either pass `reference` to `score()` or set a default reference when creating the metric.

        >>> # Empty reference string is invalid
        >>> contains_metric = Contains(reference="")
        >>> contains_metric.score("Hello")
        Traceback (most recent call last):
            ...
        ValueError: Invalid reference string provided. Reference must be a non-empty string.
    """

    def __init__(
        self,
        case_sensitive: bool = False,
        reference: Optional[str] = None,
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
        self._default_reference = reference

    def score(
        self, output: str, reference: Optional[str] = None, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the score based on whether the reference string is contained in the output string.

        Args:
            output: The output string to check.
            reference: The reference string to look for in the output. If None, falls back to the
                default reference provided at initialization.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if the reference
                is found in the output, 0.0 otherwise.
        """
        # Use provided reference, else fall back to default
        ref = reference if reference is not None else self._default_reference

        # Handle missing reference (None) separately
        if ref is None:
            raise ValueError(
                "No reference string provided. Either pass `reference` to `score()` or set a default reference when creating the metric."
            )

        # Handle empty string separately
        if ref == "":
            raise ValueError(
                "Invalid reference string provided. Reference must be a non-empty string."
            )

        value = output if self._case_sensitive else output.lower()
        ref = ref if self._case_sensitive else ref.lower()

        if ref in value:
            return score_result.ScoreResult(value=1.0, name=self.name)

        return score_result.ScoreResult(value=0.0, name=self.name)
