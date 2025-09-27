from typing import Any, Optional

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    from blanc import BlancHelp, BlancTune
except ImportError:  # pragma: no cover - optional dependency
    BlancHelp = None
    BlancTune = None


class BLANC(base_metric.BaseMetric):
    """Wrapper around the BLANC summarization quality metric.

    BLANC measures how informative a summary is with respect to the original
    document by observing the impact of the summary on the masked language model's
    ability to reconstruct the source. This implementation provides a thin
    abstraction over the reference BLANC implementations (`BlancHelp` or
    `BlancTune`) from the `blanc` Python package.

    The metric returns the unmodified BLANC score in the ``[0, 1]`` range where
    higher means better summaries.

    Args:
        variant: Selects the underlying BLANC flavour. One of ``"help"`` or
            ``"tune"``. Defaults to ``"help"``.
        blanc_model: Optional pre-initialised BLANC model instance. When provided,
            it will be used directly (useful for dependency injection in tests or
            when sharing a heavy model across metric instances).
        model_kwargs: Extra keyword arguments forwarded to the BLANC constructor
            when the metric instantiates it internally (ignored if
            ``blanc_model`` is supplied).
        name: Optional custom metric name.
        track: Whether to track the metric.
        project_name: Optional project name for tracking.
    """

    def __init__(
        self,
        variant: str = "help",
        blanc_model: Optional[Any] = None,
        name: str = "blanc_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        **model_kwargs: Any,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        if blanc_model is not None:
            self._blanc = blanc_model
            return

        variant = variant.lower()
        if variant not in {"help", "tune"}:
            raise ValueError("variant must be either 'help' or 'tune'")

        if BlancHelp is None or BlancTune is None:  # pragma: no cover - optional dep
            raise ImportError(
                "BLANC metric requires the optional 'blanc' package. Install via"
                " `pip install blanc` or provide `blanc_model`."
            )

        factory = BlancHelp if variant == "help" else BlancTune
        self._blanc = factory(**model_kwargs)

    def score(
        self,
        output: str,
        reference: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (BLANC metric).")
        if not reference.strip():
            raise MetricComputationError("Reference is empty (BLANC metric).")

        if not hasattr(self._blanc, "eval_once"):
            raise MetricComputationError(
                "BLANC backend must supply an 'eval_once' method returning the score."
            )

        score = self._blanc.eval_once(summary=output, source=reference)
        return score_result.ScoreResult(
            value=float(score),
            name=self.name,
            reason=f"BLANC score: {score:.4f}",
        )
