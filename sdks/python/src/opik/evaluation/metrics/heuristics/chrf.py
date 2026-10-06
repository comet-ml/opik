"""Character n-gram F-score (chrF/chrF++) metric wrapper."""

from __future__ import annotations

import numbers
from typing import Any, Callable, Optional, Sequence, Union

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.exceptions import MetricComputationError

try:  # pragma: no cover - optional dependency
    from nltk.translate import chrf_score as nltk_chrf_score
except ImportError:  # pragma: no cover - optional dependency
    nltk_chrf_score = None

ChrFFn = Callable[[Sequence[str], Sequence[str]], float]


class ChrF(BaseMetric):
    """
    Compute chrF / chrF++ scores between a candidate string and references.

    By default the implementation delegates to ``nltk.translate.chrf_score``, which
    computes chrF (character n-gram overlap). chrF++ (word n-grams via ``word_order``)
    is not supported by the NLTK backend; provide a custom ``chrf_fn`` to compute it.
    Scores range from `0.0` (no overlap) to `1.0` (perfect match).

    References:
      - Popović, "chrF: character n-gram F-score for automatic MT evaluation" (WMT 2015)
        https://aclanthology.org/W15-3049/
      - NLTK chrf_score module documentation
        https://www.nltk.org/api/nltk.translate.chrf_score.html
      - Hugging Face Evaluate: chrF metric overview
        https://huggingface.co/spaces/evaluate-metric/chrf

    Args:
        name: Display name for the metric result. Defaults to ``"chrf_metric"``.
        track: Whether to automatically track metric results. Defaults to ``True``.
        project_name: Optional tracking project name. Defaults to ``None``.
        beta: Weighting between precision and recall (``beta = 2`` is standard).
        ignore_whitespace: Whether whitespace is ignored before scoring. Defaults
            to ``True`` to match chrF's historical (implicit) behaviour.
        char_order: Maximum character n-gram order.
        word_order: Maximum word n-gram order for chrF++. Not supported by the
            default NLTK backend; provide ``chrf_fn`` to use it.
        lowercase: Whether to lowercase candidate and references prior to scoring.
        chrf_fn: Optional custom scoring callable for testing or offline usage.

    Raises:
        ValueError: If ``beta`` is not a number, or if ``char_order`` is not an
            integer of at least 1. Two caller-visible details:

            - ``bool`` is rejected for both, even though Python treats it as an
              integer -- ``beta=True`` would otherwise score as ``beta=1``.
            - a numeric ``beta`` is accepted whatever its sign, and the sign is
              ignored: chrF combines precision and recall through ``beta ** 2``,
              so ``beta=-2`` scores identically to ``beta=2`` and ``beta=0``
              scores on precision alone. Only ``char_order`` is range-checked.

    Example:
        >>> from opik.evaluation.metrics import ChrF
        >>> metric = ChrF(beta=2.0, char_order=6, lowercase=True)
        >>> result = metric.score(
        ...     output="The quick brown fox",
        ...     reference="The quick brown fox jumps",
        ... )
        >>> round(result.value, 4)  # doctest: +SKIP
        0.8795
    """

    def __init__(
        self,
        name: str = "chrf_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        beta: float = 2.0,
        ignore_whitespace: bool = True,
        char_order: int = 6,
        word_order: int = 0,
        lowercase: bool = False,
        chrf_fn: Optional[ChrFFn] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        # Validate the numeric arguments before the environment, for the same
        # reason BaseBLEU validates n_grams and weights: a bad beta is a bad beta
        # whether or not NLTK happens to be installed. Without this, a non-numeric
        # beta (a string from a config file, say) raises TypeError inside NLTK and
        # the fallback below silently scores it with NLTK's own beta=3.0 instead of
        # the beta=2.0 the caller asked for. `bool` is a subclass of `int`, and
        # True would otherwise quietly mean 1.
        # `numbers.Real` / `numbers.Integral` rather than `float` / `int`, so
        # numpy scalars from a DataFrame or a config file are accepted. `bool` is
        # registered as `numbers.Integral`, so the guard has to stay explicit.
        if not isinstance(beta, (int, float, numbers.Real)) or isinstance(beta, bool):
            raise ValueError(f"beta must be a number, got {type(beta).__name__}.")

        if not isinstance(char_order, (int, numbers.Integral)) or isinstance(
            char_order, bool
        ):
            raise ValueError(
                f"char_order must be an integer, got {type(char_order).__name__}."
            )

        if char_order < 1:
            raise ValueError(f"char_order must be at least 1, got {char_order}.")

        # Numpy scalars are `numbers.Real`, but NLTK's chrF divides by
        # `beta ** 2 * precision + recall` with numpy semantics, so a numpy beta
        # turns a legitimately zero denominator into NaN instead of raising. Pass
        # NLTK a plain float. `char_order` needs no such conversion: it reaches
        # NLTK as `max_len`, where numpy integers already behave.
        self._beta = float(beta)
        self._ignore_whitespace = ignore_whitespace
        self._char_order = char_order
        self._word_order = word_order
        self._lowercase = lowercase

        if chrf_fn is not None:
            self._chrf_fn = chrf_fn
        else:
            if nltk_chrf_score is None:  # pragma: no cover - optional dependency
                raise ImportError(
                    "chrF metric requires the optional 'nltk' package. Install via"
                    " `pip install nltk` or provide `chrf_fn`."
                )

            def _score_single(candidate: Sequence[str], reference: str) -> float:
                try:
                    return float(
                        nltk_chrf_score.sentence_chrf(
                            reference,
                            candidate,
                            max_len=self._char_order,
                            beta=self._beta,
                            ignore_whitespace=self._ignore_whitespace,
                        )
                    )
                except TypeError:
                    # Older NLTK versions expose the helper with fewer keyword
                    # arguments. The constructor rejects a non-numeric beta and a
                    # non-positive char_order, so the only TypeError left to reach
                    # here is the call signature itself.
                    return float(nltk_chrf_score.sentence_chrf(reference, candidate))

            def _compute(candidate: Sequence[str], references: Sequence[str]) -> float:
                # NLTK's ``sentence_chrf`` supports only a single reference; passing
                # a list of references makes it misinterpret the input and return a
                # wrong score. For multiple references, score against each one and
                # take the best match (standard multi-reference chrF semantics).
                return max(_score_single(candidate, ref) for ref in references)

            self._chrf_fn = _compute

    def score(
        self,
        output: str,
        reference: Union[str, Sequence[str]],
        **ignored_kwargs: Any,
    ) -> ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (chrF metric).")
        if isinstance(reference, str):
            references = [reference]
        else:
            references = list(reference)
        if not references or any(not ref.strip() for ref in references):
            raise MetricComputationError("Reference is empty (chrF metric).")

        if self._lowercase:
            output_text = output.lower()
            references = [ref.lower() for ref in references]
        else:
            output_text = output

        value = self._chrf_fn(output_text, references)

        return ScoreResult(
            value=float(value),
            name=self.name,
            reason=f"chrF score: {float(value):.4f}",
        )
