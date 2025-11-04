from typing import Any, Callable, Optional, Sequence, Union

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    from nltk.translate import gleu_score as nltk_gleu_score
except ImportError:  # pragma: no cover - optional dependency
    nltk_gleu_score = None


GleuFn = Callable[[Sequence[Sequence[str]], Sequence[str]], float]


class GLEU(base_metric.BaseMetric):
    """
    Sentence-level GLEU metric powered by ``nltk.translate.gleu_score``.

    References:
      - NLTK Reference Documentation on GLEU
        https://www.nltk.org/api/nltk.translate.gleu_score.html
      - OECD Catalogue of Tools & Metrics for Trustworthy AI
        https://oecd.ai/en/catalogue/metrics/google-bleu-gleu
      - Hugging Face Evaluate: Google BLEU (GLEU) metric overview
        https://huggingface.co/spaces/evaluate-metric/google_bleu

    Args:
        gleu_fn: Optional custom scoring callable compatible with
            ``nltk.translate.gleu_score.sentence_gleu``. Useful for testing.
        min_len: Minimum n-gram size considered.
        max_len: Maximum n-gram size considered.
        name: Display name for the metric result.
        track: Whether to automatically track metric results.
        project_name: Optional tracking project name.

    Example:
        >>> from opik.evaluation.metrics import GLEU
        >>> metric = GLEU(min_len=1, max_len=4)
        >>> result = metric.score(
        ...     output="The cat sat on the mat",
        ...     reference="The cat is on the mat",
        ... )
        >>> round(result.value, 3)  # doctest: +SKIP
        0.816
    """

    def __init__(
        self,
        gleu_fn: Optional[GleuFn] = None,
        min_len: int = 1,
        max_len: int = 4,
        name: str = "gleu_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        if min_len <= 0 or max_len <= 0:
            raise ValueError("min_len and max_len must be positive integers.")
        if min_len > max_len:
            raise ValueError("min_len cannot exceed max_len.")

        super().__init__(name=name, track=track, project_name=project_name)

        if gleu_fn is not None:
            self._gleu_fn = gleu_fn
        else:
            if nltk_gleu_score is None:  # pragma: no cover - optional dependency
                raise ImportError(
                    "GLEU metric requires the optional 'nltk' package. Install via"
                    " `pip install nltk` or provide `gleu_fn`."
                )

            def _scorer(
                references: Sequence[Sequence[str]], hypothesis: Sequence[str]
            ) -> float:
                return float(
                    nltk_gleu_score.sentence_gleu(
                        references,
                        hypothesis,
                        min_len=min_len,
                        max_len=max_len,
                    )
                )

            self._gleu_fn = _scorer

    def score(
        self,
        output: str,
        reference: Union[str, Sequence[str]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (GLEU metric).")
        hypothesis_tokens = output.split()
        if isinstance(reference, str):
            references = [reference.split()]
        else:
            ref_list = list(reference)
            if not ref_list:
                raise MetricComputationError("Reference is empty (GLEU metric).")
            references = [ref.split() for ref in ref_list]

        if any(len(ref) == 0 for ref in references):
            raise MetricComputationError(
                "Reference contains empty segment (GLEU metric)."
            )

        score = self._gleu_fn(references, hypothesis_tokens)
        return score_result.ScoreResult(
            value=float(score),
            name=self.name,
            reason=f"GLEU score: {float(score):.4f}",
        )
