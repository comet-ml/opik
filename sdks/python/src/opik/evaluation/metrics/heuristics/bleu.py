from typing import Any, List, Optional, Tuple, Union

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    from nltk.translate import bleu_score as nltk_bleu_score
except ImportError:
    nltk_bleu_score = None


class BaseBLEU(base_metric.BaseMetric):
    """
    Base class containing shared BLEU logic, such as handling n-grams, smoothing,
    and weights initialization. This class is not intended to be used directly.

    References:
      - NLTK BLEU smoothing:
        https://www.nltk.org/api/nltk.translate.bleu_score.html#nltk.translate.bleu_score.SmoothingFunction

    Args:
        name: The name of the metric (e.g. "sentence_bleu_metric" or "corpus_bleu_metric").
        track: Whether to track the metric (depends on your system).
        n_grams: Up to which n-gram order to use (1 through n_grams).
        smoothing_method: One of NLTK's SmoothingFunction methods (e.g. "method0", "method1", etc.).
        weights: Optional custom weights for n-gram orders. Must sum to 1.0. If None,
                 defaults to uniform distribution across `n_grams`.
        project_name: Optional project name to track the metric in for the cases when
            there are no parent span/trace to inherit project name from.
    """

    def __init__(
        self,
        name: str,
        track: bool,
        n_grams: int,
        smoothing_method: str,
        weights: Optional[List[float]],
        project_name: Optional[str],
    ):
        super().__init__(name=name, track=track, project_name=project_name)

        if nltk_bleu_score is None:
            raise ImportError(
                "`nltk` library is required for BLEU score calculation. "
                "Install via `pip install nltk`."
            )

        self.n_grams = n_grams
        self.smoothing_method = smoothing_method

        if weights is None:
            self.weights = [1.0 / n_grams] * n_grams
        else:
            if len(weights) != n_grams:
                raise ValueError(
                    f"Length of weights ({len(weights)}) != n_grams ({n_grams})."
                )
            if abs(sum(weights) - 1.0) > 1e-6:
                raise ValueError("Weights must sum to 1.0")
            self.weights = weights

        self._smoother = nltk_bleu_score.SmoothingFunction()

    def _get_smoothing_func(self) -> "nltk_bleu_score.SmoothingFunction":
        return getattr(self._smoother, self.smoothing_method, self._smoother.method0)

    def _truncate_weights(self, max_len: int) -> Tuple[float, ...]:
        used_order = min(self.n_grams, max_len)
        used_weights = self.weights[:used_order]
        total = sum(used_weights) or 1.0
        normalized = [w / total for w in used_weights]
        return tuple(normalized)


class SentenceBLEU(BaseBLEU):
    """
    Computes sentence-level BLEU for a single candidate string vs. one or more references.

    Example:
        >>> from opik.evaluation.metrics.heuristics.bleu import SentenceBLEU
        >>> metric = SentenceBLEU(n_grams=4, smoothing_method="method1")
        >>> result = metric.score("the cat is on the mat", "the cat is on the mat")
        >>> print(result.value)
        1.0
    """

    def __init__(
        self,
        name: str = "sentence_bleu_metric",
        track: bool = True,
        n_grams: int = 4,
        smoothing_method: str = "method1",
        weights: Optional[List[float]] = None,
        project_name: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            n_grams=n_grams,
            smoothing_method=smoothing_method,
            weights=weights,
            project_name=project_name,
        )

    def score(
        self,
        output: str,
        reference: Union[str, List[str]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Calculate sentence-level BLEU for one candidate vs. one or more references.

        Args:
            output: A single candidate string.
            reference: Either a single reference string or a list of reference strings.

        Returns:
            A `ScoreResult` with:
              - `value`: The sentence-level BLEU score (float).
              - `name`: The metric name.
              - `reason`: A short explanation (e.g. "Sentence-level BLEU...").

        Raises:
            MetricComputationError:
                - If the candidate or any reference is empty.
        """
        if not output.strip():
            raise MetricComputationError("Candidate is empty (single-sentence BLEU).")

        # Convert references to tokenized lists
        if isinstance(reference, str):
            if not reference.strip():
                raise MetricComputationError(
                    "Reference is empty (single-sentence BLEU)."
                )
            ref_lists = [reference.lower().split()]
        else:
            # List of reference strings
            ref_lists = []
            for ref_str in reference:
                if not ref_str.strip():
                    raise MetricComputationError(
                        "Encountered empty reference (single-sentence BLEU)."
                    )
                ref_lists.append(ref_str.lower().split())

        candidate_tokens = output.lower().split()
        used_weights = self._truncate_weights(len(candidate_tokens))
        smoothing_func = self._get_smoothing_func()

        try:
            bleu_val = nltk_bleu_score.sentence_bleu(
                ref_lists,
                candidate_tokens,
                weights=used_weights,
                smoothing_function=smoothing_func,
            )
        except ZeroDivisionError:
            bleu_val = 0.0

        return score_result.ScoreResult(
            value=bleu_val,
            name=self.name,
            reason=(
                f"Sentence-level BLEU (nltk, method={self.smoothing_method}): {bleu_val:.4f}"
            ),
        )


class CorpusBLEU(BaseBLEU):
    """
    Computes corpus-level BLEU for multiple candidate strings vs. matching references.

    Each element in `output` corresponds to one candidate. The parallel `reference`
    element can be either a single string or a list of reference strings for that candidate.

    Example:
        >>> from opik.evaluation.metrics.heuristics.bleu import CorpusBLEU
        >>> metric = CorpusBLEU(n_grams=4, smoothing_method="method1")
        >>> outputs = ["the cat is on the mat", "there is a cat here"]
        >>> references = [
        ...     "the cat is on the mat",
        ...     ["there is a cat here", "there is cat here"]
        ... ]
        >>> result = metric.score(outputs, references)
        >>> print(result.value)
    """

    def __init__(
        self,
        name: str = "corpus_bleu_metric",
        track: bool = True,
        n_grams: int = 4,
        smoothing_method: str = "method1",
        weights: Optional[List[float]] = None,
        project_name: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            n_grams=n_grams,
            smoothing_method=smoothing_method,
            weights=weights,
            project_name=project_name,
        )

    def score(
        self,
        output: List[str],
        reference: List[Union[str, List[str]]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Calculate corpus-level BLEU for multiple candidates.

        Args:
            output: A list of candidate strings (one per sample).
            reference: A list of references, each parallel to `output`. Each reference
                       item can be a single string or a list of strings.

        Returns:
            A `ScoreResult` with:
              - `value`: The corpus-level BLEU score (float).
              - `name`: The metric name.
              - `reason`: A short explanation (e.g. "Corpus-level BLEU...").

        Raises:
            MetricComputationError:
                - If a candidate or reference is empty.
                - If the number of candidates does not match the number of references.
        """
        if len(output) != len(reference):
            raise MetricComputationError(
                "Mismatch: number of candidates != number of references (corpus BLEU)."
            )

        all_candidates: List[List[str]] = []
        all_references: List[List[List[str]]] = []

        for candidate_str, ref_item in zip(output, reference):
            if not candidate_str.strip():
                raise MetricComputationError("Candidate is empty (corpus BLEU).")

            candidate_tokens = candidate_str.lower().split()

            if isinstance(ref_item, str):
                # single reference
                if not ref_item.strip():
                    raise MetricComputationError("Reference is empty (corpus BLEU).")
                ref_lists = [ref_item.lower().split()]
            else:
                # multiple references
                ref_lists = []
                for r_line in ref_item:
                    if not r_line.strip():
                        raise MetricComputationError(
                            "Encountered empty reference (corpus BLEU)."
                        )
                    ref_lists.append(r_line.lower().split())

            all_candidates.append(candidate_tokens)
            all_references.append(ref_lists)

        max_candidate_len = max(len(cand) for cand in all_candidates)
        used_weights = self._truncate_weights(max_candidate_len)
        smoothing_func = self._get_smoothing_func()

        try:
            bleu_val = nltk_bleu_score.corpus_bleu(
                all_references,
                all_candidates,
                weights=used_weights,
                smoothing_function=smoothing_func,
            )
        except ZeroDivisionError:
            bleu_val = 0.0

        return score_result.ScoreResult(
            value=bleu_val,
            name=self.name,
            reason=(
                f"Corpus-level BLEU (nltk, method={self.smoothing_method}): {bleu_val:.4f}"
            ),
        )
