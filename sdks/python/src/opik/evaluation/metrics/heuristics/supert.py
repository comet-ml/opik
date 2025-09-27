from typing import Any, Optional

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:
    from supert import Supert  # type: ignore
except ImportError:  # pragma: no cover - optional dependency
    Supert = None


class SUPERT(base_metric.BaseMetric):
    """Wrapper around the SUPerT summarization metric.

    SUPerT evaluates the factual consistency of a summary with respect to its
    reference document by combining sentence-level entailment and token overlap
    heuristics. This class provides a lightweight bridge to the reference
    implementation from the `supert` package while allowing dependency injection
    of any compatible scorer.

    Args:
        supert_model: Optional pre-initialised SUPerT model. When omitted, the
            constructor attempts to instantiate ``supert.Supert`` with the supplied
            ``model_kwargs``. Providing the model explicitly is recommended for tests
            and scenarios where model construction is expensive.
        name: Optional custom metric name.
        track: Whether to track the metric.
        project_name: Optional project name for tracking.
        model_kwargs: Extra keyword arguments forwarded to ``supert.Supert`` when
            the metric instantiates it internally (ignored if ``supert_model`` is
            supplied).
    """

    def __init__(
        self,
        supert_model: Optional[Any] = None,
        name: str = "supert_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        **model_kwargs: Any,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        if supert_model is not None:
            self._supert = supert_model
            return

        if Supert is None:  # pragma: no cover - optional dep
            raise ImportError(
                "SUPERT metric requires the optional 'supert' package. Install via"
                " `pip install supert` or provide `supert_model`."
            )

        self._supert = Supert(**model_kwargs)

    def score(
        self,
        output: str,
        reference: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (SUPERT metric).")
        if not reference.strip():
            raise MetricComputationError("Reference is empty (SUPERT metric).")

        backend = self._supert
        if hasattr(backend, "score"):
            value = backend.score(summary=output, document=reference)
        elif callable(backend):
            value = backend(summary=output, document=reference)
        else:
            raise MetricComputationError(
                "SUPERT backend must expose a 'score' method or be callable."
            )

        return score_result.ScoreResult(
            value=float(value),
            name=self.name,
            reason=f"SUPERT score: {float(value):.4f}",
        )
