from typing import Any, Callable, Optional, Sequence, Union

try:
    import nltk  # type: ignore
    from nltk.corpus import wordnet  # type: ignore
except ImportError:  # pragma: no cover - optional dependency
    nltk = None
    wordnet = None

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    from nltk.translate import meteor_score as nltk_meteor_score
except ImportError:  # pragma: no cover - optional dependency
    nltk_meteor_score = None


MeteorFn = Callable[[Sequence[str], str], float]


class METEOR(base_metric.BaseMetric):
    """Computes the METEOR score between output and reference text.

    This implementation wraps ``nltk.translate.meteor_score.meteor_score`` while
    allowing a custom scoring function to be injected (useful for testing).

    References:
      - Banerjee & Lavie, "METEOR: An Automatic Metric for MT Evaluation with Improved
        Correlation with Human Judgments" (ACL Workshop 2005)
        https://aclanthology.org/W05-0909/
      - Hugging Face Evaluate: METEOR metric overview
        https://huggingface.co/spaces/evaluate-metric/meteor

    Args:
        meteor_fn: Optional callable with the same interface as
            ``nltk.translate.meteor_score.meteor_score``. When omitted the
            function from NLTK is used.
        alpha: Precision weight.
        beta: Penalty exponent.
        gamma: Fragmentation penalty weight.
        name: Optional metric name.
        track: Whether Opik should track the metric automatically.
        project_name: Optional project name used when tracking.
    """

    def __init__(
        self,
        meteor_fn: Optional[MeteorFn] = None,
        alpha: float = 0.9,
        beta: float = 3.0,
        gamma: float = 0.5,
        name: str = "meteor_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        if meteor_fn is not None:
            self._meteor_fn = meteor_fn
        else:
            if nltk_meteor_score is None:  # pragma: no cover - optional dependency
                raise ImportError(
                    "METEOR metric requires the optional 'nltk' package. Install via"
                    " `pip install nltk` or provide `meteor_fn`."
                )

            if nltk is not None and wordnet is not None:
                try:
                    wordnet.ensure_loaded()  # type: ignore[attr-defined]
                except (
                    LookupError
                ):  # pragma: no cover - download path relies on network access
                    try:
                        nltk.download("wordnet", quiet=True)
                        nltk.download("omw-1.4", quiet=True)
                        wordnet.ensure_loaded()  # type: ignore[attr-defined]
                    except Exception as download_error:
                        raise ImportError(
                            "METEOR metric requires the NLTK corpora 'wordnet' and 'omw-1.4'. "
                            "Install manually via `python -m nltk.downloader wordnet omw-1.4`."
                        ) from download_error

            def _scorer(references: Sequence[str], hypothesis: str) -> float:
                try:
                    return float(
                        nltk_meteor_score.meteor_score(
                            references, hypothesis, alpha=alpha, beta=beta, gamma=gamma
                        )
                    )
                except LookupError as error:
                    raise MetricComputationError(
                        "NLTK resource requirement for METEOR not satisfied. "
                        "Download WordNet via `nltk.download('wordnet')`."
                    ) from error

            self._meteor_fn = _scorer

    def score(
        self,
        output: str,
        reference: Union[str, Sequence[str]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (METEOR metric).")
        if isinstance(reference, str):
            references: Sequence[str] = [reference]
        else:
            references = list(reference)
        if not references or any(not ref.strip() for ref in references):
            raise MetricComputationError("Reference is empty (METEOR metric).")

        score = self._meteor_fn(references, output)
        return score_result.ScoreResult(
            value=float(score),
            name=self.name,
            reason=f"METEOR score: {float(score):.4f}",
        )
