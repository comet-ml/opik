from typing import Any, List, Union, Optional, Tuple, cast

from opik.evaluation.metrics.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    import nltk
    from nltk.translate import bleu_score as nltk_bleu_score
except ImportError:
    nltk = None


class BLEU(base_metric.BaseMetric):
    """
    A metric that calculates the BLEU (Bilingual Evaluation Understudy) score
    for translation-like tasks.

    This class can compute either **single-sentence** BLEU or **corpus-level** BLEU
    depending on the type of `output` and `reference` passed to the `score()` method:

    - **Single-sentence BLEU**:
      - `output`: a single string (the candidate translation).
      - `reference`: either a single string or a list of reference strings.
      - If there is any empty string in either `output` or `reference`, a
        `MetricComputationError` is raised.

    - **Corpus-level BLEU**:
      - `output`: a list of candidate strings (one per sample).
      - `reference`: a list of references, where each element is either a single string
        or a list of reference strings, corresponding to each candidate.
      - The number of candidates must match the number of reference items. Any empty
        candidate or reference triggers a `MetricComputationError`.

    This metric uses the NLTK library under the hood and supports various
    smoothing methods from NLTK's BLEU implementation:
    https://www.nltk.org/api/nltk.translate.bleu_score.html#nltk.translate.bleu_score.SmoothingFunction

    Args:
        name: The name of the metric. Defaults to "bleu_metric".
        track: Whether or not to track the metric. Defaults to True.
        n_grams: Up to which n-gram order to use (1 through n_grams). Defaults to 4.
        smoothing_method: One of NLTK's SmoothingFunction methods (e.g., "method0",
            "method1", "method2", etc.). Defaults to "method1".
        weights: Optional custom weights for n-gram orders. Must sum to 1.0. If None,
            defaults to uniform weights across `n_grams`.
    """

    def __init__(
        self,
        name: str = "bleu_metric",
        track: bool = True,
        n_grams: int = 4,
        smoothing_method: str = "method1",
        weights: Optional[List[float]] = None,
    ):
        super().__init__(name=name, track=track)

        if nltk is None:
            raise ImportError(
                "`nltk` library is required for BLEU score calculation. "
                "Please install it via `pip install nltk`."
            )

        self.n_grams = n_grams
        self.smoothing_method = smoothing_method

        if weights is None:
            self.weights = [1.0 / n_grams] * n_grams
        else:
            if len(weights) != n_grams:
                raise ValueError(
                    f"Length of weights ({len(weights)}) != n_grams ({n_grams})"
                )
            if abs(sum(weights) - 1.0) > 1e-6:
                raise ValueError("Weights must sum to 1.0")
            self.weights = weights

        self._smoother = nltk_bleu_score.SmoothingFunction()

    def _get_smoothing_func(self) -> nltk_bleu_score.SmoothingFunction:
        return getattr(self._smoother, self.smoothing_method, self._smoother.method0)

    def _truncate_weights(self, max_len: int) -> Tuple[float, ...]:
        used_order = min(self.n_grams, max_len)
        used_weights = self.weights[:used_order]
        total = sum(used_weights) or 1.0
        normalized = [w / total for w in used_weights]
        return tuple(normalized)

    def score(
        self,
        output: Union[str, List[str]],
        reference: Union[str, List[str], List[Union[str, List[str]]]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Computes the BLEU score (single-sentence or corpus-level) based on the
        types of `output` and `reference`.

        If `output` is a single string, we compute **single-sentence** BLEU.
        If `output` is a list of strings, we compute **corpus-level** BLEU.

        Args:
            output: A single candidate string or a list of candidate strings.
            reference:
                - For single-sentence BLEU: either a single reference string or
                  a list of reference strings.
                - For corpus-level BLEU: a list of references, each of which can be
                  a single string or a list of strings (one list per candidate).
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            A `ScoreResult` object containing:
            - `value`: The BLEU score (float).
            - `name`: The name of this metric (e.g. "bleu_metric").
            - `reason`: A string explaining whether it's sentence-level or corpus-level BLEU.

        Raises:
            MetricComputationError:
                - If an empty candidate or reference is found.
                - If lengths of output and reference lists do not match in corpus mode.
        """
        smoothing_func = self._get_smoothing_func()

        if isinstance(output, str):
            candidate_str = output
            if not candidate_str.strip():
                raise MetricComputationError(
                    "Candidate is empty (single-sentence BLEU)."
                )

            if isinstance(reference, str):
                reference_str: str = reference
                if not reference_str.strip():
                    raise MetricComputationError(
                        "Reference is empty (single-sentence BLEU)."
                    )

                ref_lists = [reference_str.lower().split()]

            elif isinstance(reference, list):
                references_list_of_str: List[str] = cast(List[str], reference)
                ref_lists = []
                for ref_str in references_list_of_str:
                    if not ref_str.strip():
                        raise MetricComputationError(
                            "Encountered empty reference (single-sentence BLEU)."
                        )
                    ref_lists.append(ref_str.lower().split())
            else:
                raise MetricComputationError(
                    "Reference must be a string or list of strings for single-sentence BLEU."
                )

            candidate_tokens = candidate_str.lower().split()
            used_weights = self._truncate_weights(len(candidate_tokens))

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

        else:
            output_list_of_str: List[str] = cast(List[str], output)

            if not isinstance(reference, list):
                raise MetricComputationError(
                    "For corpus-level BLEU, `reference` must be a list "
                    "parallel to `output`."
                )

            if len(output_list_of_str) != len(reference):
                raise MetricComputationError(
                    "Mismatch: number of candidates != number of references (corpus BLEU)."
                )

            all_candidates: List[List[str]] = []
            all_references: List[List[List[str]]] = []

            for candidate_str, ref_item in zip(output_list_of_str, reference):
                if not candidate_str.strip():
                    raise MetricComputationError("Candidate is empty (corpus BLEU).")

                candidate_tokens = candidate_str.lower().split()

                if isinstance(ref_item, str):
                    if not ref_str.strip():
                        raise MetricComputationError(
                            "Reference is empty (corpus BLEU)."
                        )
                    ref_lists = [ref_str.lower().split()]

                elif isinstance(ref_item, list):
                    ref_item_list: List[str] = cast(List[str], ref_item)
                    ref_lists = []
                    for r_line in ref_item_list:
                        if not r_line.strip():
                            raise MetricComputationError(
                                "Encountered empty reference (corpus BLEU)."
                            )
                        ref_lists.append(r_line.lower().split())
                else:
                    raise MetricComputationError(
                        "Reference in corpus BLEU must be either a string or a list of strings."
                    )

                all_candidates.append(candidate_tokens)
                all_references.append(ref_lists)

            max_candidate_len = max(len(cand) for cand in all_candidates)
            used_weights = self._truncate_weights(max_candidate_len)

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
