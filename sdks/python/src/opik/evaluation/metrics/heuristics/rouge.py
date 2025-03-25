from typing import Any, List, Union
from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    import evaluate
    import rouge_score
except ImportError:
    evaluate = None
    rouge_score = None


class ROUGE(base_metric.BaseMetric):
    """
    A metric that computes the ROUGE score between an output and reference string.

    This metric uses the `evaluate` and `rouge-score` libraries to compute the ROUGE score.

    Args:
        name: The name of the metric. Defaults to "rouge_1_metric".
        track: Whether to track the metric. Defaults to True.

    Example:
        >>> from opik.evaluation.metrics import ROUGE
        >>> rouge_metric = ROUGE()
        >>> result = rouge_metric.score(
        ...     output="The quick brown fox jumps over the lazy dog.",
        ...     reference="The quick brown fox jumps over the lazy dog."
        ...     rouge_type="rouge1"
        ... )
        >>> print(result.value)
        1.0
    """

    def __init__(self, name: str = "rouge_metric", track: bool = True):
        super().__init__(name=name, track=track)

        if evaluate is None or rouge_score is None:
            raise ImportError(
                "`evaluate and rouge-score` libraries are required for ROUGE score calculation. "
                "Install via `pip install evaluate rouge-score`."
            )

        self._rouge = evaluate.load('rouge')

    def score(
        self,
        output: str,
        reference: Union[str, List[str]],
        rouge_type: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Compute the ROUGE score between the output and reference strings.

        Args:
            output: The output string to score.
            reference: The reference string or list of reference strings.
            rouge_type: Type of ROUGE score to compute. Must be one of the following:
                    - "rouge1": unigram (1-gram) based scoring
                    - "rouge2": bigram (2-gram) based scoring
                    - "rougeL": Longest common subsequence based scoring
                    - "rougeLSum": splits text using "\n"
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with the ROUGE score.

        Raises:
            MetricComputationError:
                - If the candidate or any reference is empty.
                - If the reference strings are in multiple lists.
                - If the rouge_type is invalid.
        """
        valid_rouge_types = {'rouge1', 'rouge2', 'rougeL', 'rougeLsum'}

        if rouge_type not in valid_rouge_types:
            raise MetricComputationError(
                f"Invalid rouge_type '{rouge_type}'. Must be one of {valid_rouge_types}.")

        if not output.strip():
            raise MetricComputationError("Candidate is empty.")

        if isinstance(reference, str):
            if not reference.strip():
                raise MetricComputationError("Reference is empty.")
        else:
            if len(reference) != 1:
                raise MetricComputationError(
                    "Reference strings are in multiple lists."
                )

            for ref_str in reference:
                if not ref_str.strip():
                    raise MetricComputationError(
                        "Encountered empty reference.")

        results = self._rouge.compute(
            predictions=[output], references=[reference], rouge_type=[rouge_type])
        rouge_f1_value = results[rouge_type].item()

        return score_result.ScoreResult(
            value=rouge_f1_value,
            name=self.name,
            reason=f"ROUGE score of {rouge_type}: {rouge_f1_value:.4f}",
        )
