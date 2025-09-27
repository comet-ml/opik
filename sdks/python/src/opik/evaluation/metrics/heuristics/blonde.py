from __future__ import annotations

from typing import Any, Callable, Dict, Optional, Sequence, Union

from opik.exceptions import MetricComputationError
from opik.evaluation.metrics import base_metric, score_result

try:  # pragma: no cover - optional dependency
    import evaluate
except ImportError:  # pragma: no cover - optional dependency
    evaluate = None

BlondeResult = Union[float, Dict[str, Any]]
BlondeFn = Callable[[str, Sequence[str]], BlondeResult]


class BLONDE(base_metric.BaseMetric):
    """Compute the BLONDE factual consistency score via the evaluate implementation."""

    def __init__(
        self,
        scorer_fn: Optional[BlondeFn] = None,
        language: str = "en",
        name: str = "blonde_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._language = language
        self._scorer_fn = scorer_fn
        self._evaluate_metric = None

    def score(
        self,
        output: str,
        reference: Union[str, Sequence[str]],
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        if not output.strip():
            raise MetricComputationError("Candidate is empty (BLONDE metric).")

        references = self._normalize_references(reference)
        raw_result = self._compute_score(output, references)
        value, metadata = self._parse_result(raw_result)

        return score_result.ScoreResult(
            value=value,
            name=self.name,
            reason=f"BLONDE score: {value:.4f}",
            metadata=metadata,
        )

    def _normalize_references(self, reference: Union[str, Sequence[str]]) -> Sequence[str]:
        if isinstance(reference, str):
            references = [reference]
        else:
            references = list(reference)
        if not references or any(not ref.strip() for ref in references):
            raise MetricComputationError("Reference is empty (BLONDE metric).")
        return references

    def _compute_score(self, prediction: str, references: Sequence[str]) -> BlondeResult:
        if self._scorer_fn is not None:
            return self._scorer_fn(prediction, references)

        if evaluate is None:  # pragma: no cover - optional dependency
            raise ImportError(
                "BLONDE metric requires the optional 'evaluate' package. Install via "
                "`pip install evaluate[blonde]` or supply `scorer_fn`."
            )

        if self._evaluate_metric is None:  # pragma: no cover - optional dependency
            self._evaluate_metric = evaluate.load("blonde")

        return self._evaluate_metric.compute(
            predictions=[prediction],
            references=[references],
            language=self._language,
        )

    def _parse_result(self, result: BlondeResult) -> tuple[float, Optional[Dict[str, float]]]:
        if isinstance(result, dict):
            metadata: Dict[str, float] = {}
            for key, value in result.items():
                try:
                    metadata[key] = float(value)
                except (TypeError, ValueError):
                    continue
            if "blonde" in metadata:
                score_value = metadata["blonde"]
            elif "score" in metadata:
                score_value = metadata["score"]
            else:
                score_value = next(iter(metadata.values())) if metadata else 0.0
            return score_value, metadata

        return float(result), None
