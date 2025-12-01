import dataclasses
import logging
from typing import Any

import litellm

import opik.exceptions
from opik.message_processing.emulation import models
from opik.evaluation.metrics import base_metric, score_result

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class _CostAccumulator:
    """Internal class to accumulate cost data across span tree traversal."""

    total_cost: float = 0.0
    total_prompt_tokens: int = 0
    total_completion_tokens: int = 0
    processed_span_count: int = 0
    failed_span_count: int = 0


def _calculate_llm_call_cost(
    model: str, prompt_tokens: int, completion_tokens: int
) -> float | None:
    """
    Calculate cost for a single llm call using LiteLLM.

    Args:
        model: The model name used by the span.
        prompt_tokens: Number of input tokens.
        completion_tokens: Number of output tokens.

    Returns:
        Total cost in USD, or None if calculation fails.
    """
    try:
        prompt_cost, completion_cost = litellm.cost_per_token(
            model=model,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
        )
        return (prompt_cost or 0.0) + (completion_cost or 0.0)
    except Exception as e:
        LOGGER.debug(
            "Failed to calculate cost for model %s: %s",
            model,
            str(e),
        )
        return None


def _process_span(span: models.SpanModel, accumulator: _CostAccumulator) -> None:
    """
    Process a single span and update the cost accumulator.

    If the span already has a total_cost (e.g., from LLM integrations), use that.
    Otherwise, calculate the cost using LiteLLM based on usage and model.

    Args:
        span: The span to process.
        accumulator: The accumulator to update with cost data.
    """
    # Check if span already has a cost calculated (e.g., from integration tracking)
    if span.total_cost is not None and span.total_cost > 0:
        accumulator.total_cost += span.total_cost
        accumulator.processed_span_count += 1

        # Still track token counts if available
        if span.usage is not None:
            accumulator.total_prompt_tokens += span.usage.get("prompt_tokens", 0)
            accumulator.total_completion_tokens += span.usage.get(
                "completion_tokens", 0
            )

        return

    # Otherwise, calculate cost from usage data
    if span.usage is None or span.model is None:
        return

    prompt_tokens = span.usage.get("prompt_tokens", 0)
    completion_tokens = span.usage.get("completion_tokens", 0)

    if prompt_tokens == 0 and completion_tokens == 0:
        return

    span_cost = _calculate_llm_call_cost(span.model, prompt_tokens, completion_tokens)

    if span_cost is not None:
        accumulator.total_cost += span_cost
        accumulator.total_prompt_tokens += prompt_tokens
        accumulator.total_completion_tokens += completion_tokens
        accumulator.processed_span_count += 1
    else:
        accumulator.failed_span_count += 1


def _traverse_span_tree(span: models.SpanModel, accumulator: _CostAccumulator) -> None:
    """
    Recursively traverse the span tree and collect cost data.

    Args:
        span: The current span to process.
        accumulator: The accumulator to update with cost data.
    """
    _process_span(span, accumulator)

    if span.spans:
        for nested_span in span.spans:
            _traverse_span_tree(nested_span, accumulator)


def _build_result_metadata(accumulator: _CostAccumulator) -> dict:
    """
    Build metadata dict from the accumulated data.

    Args:
        accumulator: The cost accumulator with collected data.

    Returns:
        A dict containing cost accumulator data.
    """
    return {
        "total_cost": accumulator.total_cost,
        "total_prompt_tokens": accumulator.total_prompt_tokens,
        "total_completion_tokens": accumulator.total_completion_tokens,
        "processed_span_count": accumulator.processed_span_count,
        "failed_span_count": accumulator.failed_span_count,
    }


class TotalSpanCost(base_metric.BaseMetric):
    """
    A metric that calculates the total cost of a span tree based on token usage.

    This metric recursively traverses the span tree and calculates costs. For spans
    that already have a `total_cost` field (e.g., from LLM integration tracking),
    it uses that value. Otherwise, it uses LiteLLM's cost calculation based on the
    span's model, prompt_tokens, and completion_tokens.

    Args:
        name: The name of the metric. Defaults to "total_span_cost".
        track: Whether to track the metric. Defaults to True.
        project_name: Optional project name to track the metric in for the cases when
            there is no parent span/trace to inherit project name from.

    Example:
        >>> from opik.evaluation.metrics.task_span import TotalSpanCost
        >>> cost_metric = TotalSpanCost()
        >>> result = cost_metric.score(task_span)
        >>> print(result.value)  # Total cost calculated from usage across span tree
        >>> print(result.reason)  # Detailed breakdown

    Note:
        - Prioritizes existing `total_cost` from spans over calculated costs
        - Uses LiteLLM's built-in cost calculation for spans without `total_cost`
        - Supports a wide range of models and providers with up-to-date pricing
        - Spans without usage data or without a recognized model will be skipped
    """

    def __init__(
        self,
        name: str = "total_span_cost",
        track: bool = True,
        project_name: str | None = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)

    def score(self, task_span: models.SpanModel, **_: Any) -> score_result.ScoreResult:
        """
        Calculate the total cost based on the span's token usage, recursively traversing the span tree.

        Args:
            task_span: The span model containing usage information and nested spans.
            **_: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object with the calculated cost value.

        Raises:
            MetricComputationError: If all spans with usage data failed cost calculation,
                indicating a critical error in the metric computation.
        """
        accumulator = _CostAccumulator()
        _traverse_span_tree(task_span, accumulator)

        # Critical error: spans with usage data exist but all failed cost calculation
        if accumulator.failed_span_count > 0 and accumulator.processed_span_count == 0:
            raise opik.exceptions.MetricComputationError(
                f"Failed to calculate cost for all {accumulator.failed_span_count} span(s) "
                f"with usage data. Check that model names are recognized by LiteLLM "
                f"and that the litellm package is properly installed."
            )

        return score_result.ScoreResult(
            value=accumulator.total_cost,
            name=self.name,
            metadata=_build_result_metadata(accumulator),
        )
