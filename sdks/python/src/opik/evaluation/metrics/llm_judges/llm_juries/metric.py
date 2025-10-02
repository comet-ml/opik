"""LLM Juries: aggregate heterogeneous judges into a consensus score."""

from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
import opik.exceptions as exceptions


class LLMJuriesJudge(BaseMetric):
    """
    Aggregate multiple judge metrics into a consensus score.

    Args:
        judges: Iterable of judge metrics to execute for consensus.
        name: Display name for the aggregated result. Defaults to
            ``"llm_juries_judge"``.
        track: Whether to automatically track results. Defaults to ``True``.
        project_name: Optional tracking project name. Defaults to ``None``.

    Example:
        >>> from opik.evaluation.metrics import LLMJuriesJudge, ComplianceRiskJudge
        >>> juries = LLMJuriesJudge(judges=[ComplianceRiskJudge(model="gpt-4")])
        >>> result = juries.score(output="Financial guarantees provided.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.6
    """

    def __init__(
        self,
        judges: Iterable[BaseMetric],
        name: str = "llm_juries_judge",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._judges = list(judges)
        if not self._judges:
            raise ValueError("LLMJuriesJudge requires at least one judge metric.")

    def score(self, *args: Any, **kwargs: Any) -> ScoreResult:
        precomputed: Optional[Dict[BaseMetric, ScoreResult]] = kwargs.pop(
            "precomputed", None
        )
        scores: List[ScoreResult] = []
        for judge in self._judges:
            if precomputed is not None and judge in precomputed:
                raw_result: Any = precomputed[judge]
            else:
                raw_result = judge.score(*args, **kwargs)
            judge_results = raw_result if isinstance(raw_result, list) else [raw_result]

            for result in judge_results:
                if not isinstance(result, ScoreResult):
                    raise exceptions.MetricComputationError(
                        f"Judge {judge.name} returned unexpected result type {type(result)!r}"
                    )
                if result.value < 0 or result.value > 1:
                    raise exceptions.MetricComputationError(
                        f"Judge {judge.name} returned out-of-range score {result.value}"
                    )
                scores.append(result)

        if not scores:
            raise exceptions.MetricComputationError("No judge scores produced")

        average = sum(res.value for res in scores) / len(scores)
        metadata = {
            "judge_scores": {res.name: res.value for res in scores},
        }
        reason = f"Averaged {len(scores)} judge scores"
        return ScoreResult(
            value=average, name=self.name, reason=reason, metadata=metadata
        )
