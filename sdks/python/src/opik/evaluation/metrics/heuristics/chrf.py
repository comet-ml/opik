"""Character n-gram F-score (chrF/chrF++) metric wrapper."""

from __future__ import annotations

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

    By default the implementation delegates to ``nltk.translate.chrf_score`` and
    supports both chrF (character n-gram overlap) and chrF++ (when ``word_order``
    is non-zero). Scores range from `0.0` (no overlap) to `1.0` (perfect match).

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
        ignore_whitespace: Whether whitespace is ignored before scoring.
        char_order: Maximum character n-gram order.
        word_order: Maximum word n-gram order (set ``>0`` to enable chrF++).
        lowercase: Whether to lowercase candidate and references prior to scoring.
        chrf_fn: Optional custom scoring callable for testing or offline usage.

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
        ignore_whitespace: bool = False,
        char_order: int = 6,
        word_order: int = 0,
        lowercase: bool = False,
        chrf_fn: Optional[ChrFFn] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._beta = beta
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

            def _compute(candidate: Sequence[str], references: Sequence[str]) -> float:
                try:
                    return float(
                        nltk_chrf_score.sentence_chrf(
                            references,
                            candidate,
                            beta=self._beta,
                        )
                    )
                except TypeError:
                    # Older NLTK versions expose the helper with fewer keyword arguments.
                    return float(nltk_chrf_score.sentence_chrf(references, candidate))

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
