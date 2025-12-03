"""Pydantic models for the eval app API."""

from typing import Any, Dict, List, Optional

import pydantic


class MetricParamDescriptor(pydantic.BaseModel):
    """Describes a single parameter of a metric."""

    name: str
    required: bool
    type: Optional[str] = None
    default: Optional[Any] = None
    description: Optional[str] = None


class MetricDescriptorResponse(pydantic.BaseModel):
    """Describes a metric with its parameters."""

    name: str
    description: str
    init_params: List[MetricParamDescriptor]
    score_params: List[MetricParamDescriptor]


class MetricsListResponse(pydantic.BaseModel):
    """Response containing list of available metrics."""

    metrics: List[MetricDescriptorResponse]


class MetricEvaluationConfig(pydantic.BaseModel):
    """Configuration for a single metric evaluation."""

    metric_name: str = pydantic.Field(description="Name of the metric class")
    init_args: Dict[str, Any] = pydantic.Field(
        default_factory=dict,
        description="Arguments to pass to metric __init__",
    )
    arguments: Dict[str, str] = pydantic.Field(
        description=(
            "Mapping from metric score() argument names to trace field paths. "
            "Supported trace fields: 'input', 'output', 'metadata'. "
            "Use dot notation for nested fields (e.g., 'metadata.context')."
        )
    )


# Keep alias for backward compatibility
LocalEvaluationRuleConfig = MetricEvaluationConfig


class EvaluationRequest(pydantic.BaseModel):
    """Request to evaluate a trace with specified metrics."""

    rules: List[MetricEvaluationConfig] = pydantic.Field(
        min_length=1,
        description="List of metric evaluation configurations",
    )


class EvaluationAcceptedResponse(pydantic.BaseModel):
    """Response indicating evaluation request was accepted."""

    trace_id: str
    rules_count: int
    message: str = (
        "Evaluation request accepted. Feedback scores will be logged to the trace."
    )
