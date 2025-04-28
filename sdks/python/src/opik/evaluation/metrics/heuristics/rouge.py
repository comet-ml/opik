from typing import Any, List, Union, Optional
from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    from rouge_score import rouge_scorer
except ImportError:
    rouge_scorer = None


class ROUGE(base_metric.BaseMetric):
    """
    A metric that computes the ROUGE, or Recall-Oriented Understudy for Gisting Evaluation score between an output and reference string mainly used for evaluating text summarization.
    ROUGE is case insensitive, meaning that upper case letters are treated the same way as lower case letters.
    This metrics is a wrapper around the Google Research reimplementation of ROUGE, which is based on the `rouge-score` library.

    References:
        - https://github.com/google-research/google-research/tree/master/rouge
        - https://huggingface.co/spaces/evaluate-metric/rouge

    Args:
        name: The name of the metric. Defaults to "rouge_metric".
        track: Whether to track the metric. Defaults to True.
        rouge_type: Type of ROUGE score to compute. Defaults to "rouge1". Must be one of the following:
                    - "rouge1": unigram (1-gram) based scoring
                    - "rouge2": bigram (2-gram) based scoring
                    - "rougeL": Longest common subsequence based scoring
                    - "rougeLSum": splits text using '\\n'"
        use_stemmer: Whether to use stemming when computing ROUGE. Defaults to False.
        split_summaries: Whether to split summaries into sentences. Defaults to False.
        tokenizer: A tokenizer to use when splitting summaries into sentences. Defaults to None.
        project_name: Optional project name to track the metric in for the cases when there are no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics import ROUGE
        >>> rouge_metric = ROUGE()
        >>> result = rouge_metric.score(
        ...     output="The quick brown fox jumps over the lazy dog.",
        ...     reference="The quick brown fox jumps over the lazy dog."
        ... )
        >>> print(result.value)
        1.0
    """

    def __init__(
        self,
        name: str = "rouge_metric",
        track: bool = True,
        rouge_type: str = "rouge1",
        use_stemmer: bool = False,
        split_summaries: bool = False,
        tokenizer: Optional[Any] = None,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)

        if rouge_scorer is None:
            raise ImportError(
                "`rouge-score` libraries are required for ROUGE score calculation. "
                "Install via `pip install rouge-score`."
            )

        valid_rouge_types = {"rouge1", "rouge2", "rougeL", "rougeLsum"}
        if rouge_type not in valid_rouge_types:
            raise MetricComputationError(
                f"Invalid rouge_type '{rouge_type}'. Must be one of {valid_rouge_types}."
            )

        self._rouge_type = rouge_type
        self._rouge = rouge_scorer.RougeScorer(
            [rouge_type],
            use_stemmer=use_stemmer,
            split_summaries=split_summaries,
            tokenizer=tokenizer,
        )

    def score(
        self,
        output: str,
        reference: Union[str, List[str]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Compute the ROUGE score based on the given rouge_type between the output and reference strings.

        Args:
            output: The output string to score.
            reference: The reference string or list of reference strings.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult with:
              - `value`: The ROUGE score (float).
              - `name`: The metric name.
              - `reason`: A short explanation (e.g. "rouge1 score: 0.91").

        Raises:
            MetricComputationError:
                - If the candidate or any reference is empty.
                - If the reference is not a string or a list of strings.
        """
        if not output.strip():
            raise MetricComputationError("Candidate is empty.")

        if isinstance(reference, str):
            if not reference.strip():
                raise MetricComputationError("Reference is empty.")

            reference = [reference]
        elif isinstance(reference, list):
            if len(reference) == 0:
                raise MetricComputationError("Reference is empty.")

            if not all(isinstance(item, str) for item in reference):
                raise MetricComputationError(
                    "Reference must be a string or a list of strings."
                )

            for ref_str in reference:
                if not ref_str.strip():
                    raise MetricComputationError("Encountered empty reference.")

        rouge_score_type = self._rouge_type
        results = self._rouge.score_multi(reference, output)
        rouge_f1_value = results[rouge_score_type].fmeasure

        return score_result.ScoreResult(
            value=rouge_f1_value,
            name=self.name,
            reason=f"{rouge_score_type} score: {rouge_f1_value:.4f}",
        )
