from typing import Any
from collections.abc import Callable
from opik.evaluation.metrics.score_result import ScoreResult


class MultiMetricObjective:
    def __init__(
        self,
        metrics: list[Callable[[dict[str, Any], str], ScoreResult]],
        weights: list[float] | None = None,
        name: str = "multi_metric_objective",
    ):
        self.metrics = metrics
        self.weights = weights if weights else [1 / len(metrics)] * len(metrics)
        self.__name__ = name

    def __call__(self, dataset_item: dict[str, Any], llm_output: str) -> ScoreResult:
        raw_score_results: list[ScoreResult] = []
        serialized_subscores: list[dict[str, Any]] = []
        weighted_score_value = 0.0
        scoring_failed = False
        reason_parts = []
        flattened_metadata: dict[str, Any] = {}

        for metric, weight in zip(self.metrics, self.weights):
            score_result = metric(dataset_item, llm_output)
            weighted_score_value += score_result.value * weight
            scoring_failed = scoring_failed or score_result.scoring_failed
            reason_parts.append(f"{score_result.name}={score_result.value:.3f} (w={weight:.2f})")

            metric_metadata = score_result.metadata or {}
            raw_score_results.append(score_result)
            serialized_subscores.append(
                {
                    "name": score_result.name,
                    "value": score_result.value,
                    "weight": weight,
                    "reason": score_result.reason,
                    "metadata": metric_metadata,
                    "scoring_failed": score_result.scoring_failed,
                }
            )

            flattened_metadata[f"{score_result.name}"] = score_result.value
            for key, value in metric_metadata.items():
                flattened_metadata[f"{score_result.name}.{key}"] = value

        if not reason_parts:
            reason_parts.append(f"{self.__name__} composite evaluation")
        aggregated_reason = " | ".join(reason_parts)
        def _extract_reason(entry: Any) -> str | None:
            if isinstance(entry, ScoreResult):
                return entry.reason
            if isinstance(entry, dict):
                return entry.get("reason")
            return None

        detailed_reason = next(
            (r for entry in raw_score_results if (r := _extract_reason(entry))),
            None,
        )
        if detailed_reason:
            aggregated_reason = f"{aggregated_reason} || {detailed_reason}"
        if not aggregated_reason.strip():
            aggregated_reason = detailed_reason or f"{self.__name__} evaluation"

        aggregated_metadata = {
            "raw_score_results": raw_score_results,
            "raw_score_results_serialized": serialized_subscores,
            **flattened_metadata,
        }

        return ScoreResult(
            name=self.__name__,
            value=weighted_score_value,
            reason=aggregated_reason,
            metadata=aggregated_metadata,
            scoring_failed=scoring_failed,
        )
