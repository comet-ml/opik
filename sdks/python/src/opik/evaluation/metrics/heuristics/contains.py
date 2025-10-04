from typing import Any, Optional
from .. import base_metric, score_result 


class Contains(base_metric.BaseMetric):
    """
    A metric that checks if a reference string is contained within an output string.
    ... (rest of docstring)
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

        Args:
            output: The output string to check.
            reference: The reference string to look for in the output. If this is None or
                       an empty string (""), the reference provided during initialization is used.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with a value of 1.0 if the reference
                is found in the output, 0.0 otherwise.
        """
        
        ref_to_use = reference if reference else self._reference

        if ref_to_use is None:
            raise ValueError(
                "Reference string must be provided either during initialization or to the score method."
            )

        value = output if self._case_sensitive else output.lower()
        ref_to_use_processed = ref_to_use if self._case_sensitive else ref_to_use.lower()

        if ref_to_use_processed in value:
            return score_result.ScoreResult(value=1.0, name=self.name)

        return score_result.ScoreResult(value=0.0, name=self.name)
