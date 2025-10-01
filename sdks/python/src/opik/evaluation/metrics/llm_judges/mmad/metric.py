"""M-MAD: Random forest of juries aggregating heterogeneous judges."""

from __future__ import annotations

from typing import Any, Dict, Iterable, List, Optional

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
import opik.exceptions as exceptions


class MMADJudge(BaseMetric):
    """Aggregate multiple judge metrics (the 'juries') into a consensus score."""

    def __init__(
        self,
        judges: Iterable[BaseMetric],
        name: str = "mmad_judge",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._judges = list(judges)
        if not self._judges:
            raise ValueError("MMADJudge requires at least one judge metric.")

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
