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

        if rouge_scorer is None and rouge_type != "rougeW":
            raise ImportError(
                "`rouge-score` libraries are required for ROUGE score calculation. "
                "Install via `pip install rouge-score`."
            )

        valid_rouge_types = {"rouge1", "rouge2", "rougeL", "rougeLsum", "rougeW"}
        if rouge_type not in valid_rouge_types:
            raise MetricComputationError(
                f"Invalid rouge_type '{rouge_type}'. Must be one of {valid_rouge_types}."
            )

        self._rouge_type = rouge_type
        if rouge_type != "rougeW":
            self._rouge = rouge_scorer.RougeScorer(
                [rouge_type],
                use_stemmer=use_stemmer,
                split_summaries=split_summaries,
                tokenizer=tokenizer,
            )
        else:
            self._rouge = None

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
        if rouge_score_type == "rougeW":
            rouge_f1_value = max(
                self._compute_rouge_w(output, ref) for ref in reference
            )
        else:
            assert self._rouge is not None
            results = self._rouge.score_multi(reference, output)
            rouge_f1_value = results[rouge_score_type].fmeasure

        return score_result.ScoreResult(
            value=rouge_f1_value,
            name=self.name,
            reason=f"{rouge_score_type} score: {rouge_f1_value:.4f}",
        )

    def _compute_rouge_w(self, output: str, reference: str) -> float:
        candidate_tokens = output.split()
        reference_tokens = reference.split()

        if not candidate_tokens or not reference_tokens:
            raise MetricComputationError(
                "Empty tokens encountered for ROUGE-W computation."
            )

        wlcs = _weighted_lcs(candidate_tokens, reference_tokens)
        cand_norm = _weighted_lcs(candidate_tokens, candidate_tokens)
        ref_norm = _weighted_lcs(reference_tokens, reference_tokens)

        precision = wlcs / cand_norm if cand_norm > 0 else 0.0
        recall = wlcs / ref_norm if ref_norm > 0 else 0.0
        beta = 1.2
        denom = recall + beta**2 * precision
        if denom == 0:
            return 0.0
        return (1 + beta**2) * precision * recall / denom


def _weighted_lcs(tokens_a: List[str], tokens_b: List[str]) -> float:
    len_a, len_b = len(tokens_a), len(tokens_b)
    dp = [[0] * (len_b + 1) for _ in range(len_a + 1)]
    score = 0.0
    for i in range(1, len_a + 1):
        for j in range(1, len_b + 1):
            if tokens_a[i - 1] == tokens_b[j - 1]:
                dp[i][j] = dp[i - 1][j - 1] + 1
                score += dp[i][j]
            else:
                dp[i][j] = 0
    return score
