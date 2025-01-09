from typing import Any, List, Union, Optional

try:
    import nltk
    from nltk.translate.bleu_score import sentence_bleu, corpus_bleu, SmoothingFunction
except ImportError:
    nltk = None

from opik.evaluation.metrics import base_metric, score_result


class BLEU(base_metric.BaseMetric):
    """
    BLEU metric relying on the `nltk.translate.bleu_score` implementation.
    If `nltk` is not installed, this class will raise an ImportError upon instantiation.
    """

    def __init__(
        self,
        name: str = "bleu_metric",
        track: bool = True,
        n_grams: int = 4,
        smoothing_method: str = "method1",
        weights: Optional[List[float]] = None,
    ):
        """
        :param name: Name for this metric instance.
        :param track: Whether or not this metric is tracked (depends on your system).
        :param n_grams: Up to which n-gram order to use (1 through n_grams).
        :param smoothing_method: One of NLTK's SmoothingFunction methods (e.g., "method0", "method1", "method2", etc.).
        :param weights: Optional manual weighting for n-gram orders. If None, defaults to uniform across n_grams.
        """
        super().__init__(name=name, track=track)

        # Ensure nltk is installed; if not, raise an ImportError now.
        if nltk is None:
            raise ImportError(
                "`nltk` library is required for BLEU score calculation. "
                "Please install it via `pip install nltk`."
            )

        self.n_grams = n_grams
        self.smoothing_method = smoothing_method

        # Set up weights: if not provided, default to uniform among the up to n_grams orders
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

        self._nltk_smoother = SmoothingFunction()

    def _get_smoothing_func(self):
        """
        Retrieve the corresponding smoothing function from nltk's SmoothingFunction
        based on the self.smoothing_method name, e.g. "method0", "method1", etc.
        Fallback to method0 if not found.
        """
        return getattr(self._nltk_smoother, self.smoothing_method, self._nltk_smoother.method0)

    def _truncate_weights(self, candidate_len: int) -> tuple:
        """
        Truncate the n-gram weights to min(self.n_grams, candidate_len),
        then re-normalize them so that they sum to 1.0.
        """
        max_order = min(self.n_grams, candidate_len)
        used_weights = self.weights[:max_order]
        w_sum = sum(used_weights) or 1.0
        # Re-normalize to sum to 1.0
        normalized = [w / w_sum for w in used_weights]
        return tuple(normalized)

    ###########################################################################
    # SINGLE-SENTENCE BLEU
    ###########################################################################
    def score(
        self, output: str, reference: Union[str, List[str]], **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Computes a single-sentence BLEU score using nltk.translate.bleu_score.sentence_bleu.
        If reference is a single string, it will be treated as one reference.
        If reference is a list of strings, multiple references are used.
        """
        # 1) Handle empty candidate
        if not output.strip():
            return score_result.ScoreResult(
                value=0.0,
                name=self.name,
                reason="Candidate is empty"
            )

        # 2) Process references
        if isinstance(reference, str):
            if not reference.strip():
                return score_result.ScoreResult(
                    value=0.0,
                    name=self.name,
                    reason="Reference is empty"
                )
            references = [reference.lower().split()]
        else:
            references = []
            for ref in reference:
                if not ref.strip():
                    return score_result.ScoreResult(
                        value=0.0,
                        name=self.name,
                        reason="Reference is empty"
                    )
                references.append(ref.lower().split())

        candidate = output.lower().split()

        # Truncate & normalize weights to the candidate length
        used_weights = self._truncate_weights(len(candidate))

        smoothing_func = self._get_smoothing_func()

        try:
            bleu_value = sentence_bleu(
                references,
                candidate,
                weights=used_weights,
                smoothing_function=smoothing_func
            )
        except ZeroDivisionError:
            # edge case if references or candidate is basically empty after splitting
            bleu_value = 0.0

        return score_result.ScoreResult(
            value=bleu_value,
            name=self.name,
            reason=f"Sentence-level BLEU (nltk, method={self.smoothing_method}): {bleu_value:.4f}",
        )

    ###########################################################################
    # CORPUS-LEVEL BLEU
    ###########################################################################
    def score_corpus(
        self,
        outputs: List[str],
        references_list: List[Union[str, List[str]]],
        **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Computes a corpus-level BLEU score using nltk.translate.bleu_score.corpus_bleu.
        """

        if len(outputs) != len(references_list):
            return score_result.ScoreResult(
                value=0.0,
                name=self.name,
                reason="Mismatch: number of candidates != number of references.",
            )

        all_candidates = []
        all_references = []

        for output, ref_item in zip(outputs, references_list):
            if not output.strip():
                # If candidate is empty, skip it (leading to zero or ignoring).
                continue

            candidate_tokens = output.lower().split()

            if isinstance(ref_item, str):
                if not ref_item.strip():
                    continue
                refs = [ref_item.lower().split()]
            else:
                refs = []
                skip_this = False
                for r in ref_item:
                    if not r.strip():
                        skip_this = True
                        break
                    refs.append(r.lower().split())
                if skip_this or not refs:
                    continue

            all_candidates.append(candidate_tokens)
            all_references.append(refs)

        if not all_candidates:
            return score_result.ScoreResult(
                value=0.0,
                name=self.name,
                reason="No valid candidate/reference pairs"
            )

        # Determine the largest candidate length
        max_len = max(len(c) for c in all_candidates)
        # Truncate & normalize weights to this largest order
        used_weights = self._truncate_weights(max_len)

        smoothing_func = self._get_smoothing_func()

        try:
            bleu_value = corpus_bleu(
                all_references,
                all_candidates,
                weights=used_weights,
                smoothing_function=smoothing_func
            )
        except ZeroDivisionError:
            bleu_value = 0.0

        return score_result.ScoreResult(
            value=bleu_value,
            name=self.name,
            reason=f"Corpus-level BLEU (nltk, method={self.smoothing_method}): {bleu_value:.4f}",
        )
