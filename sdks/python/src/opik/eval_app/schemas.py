"""Pydantic schemas for the eval app API."""

from typing import Any, Dict, List, Optional

import pydantic


class MetricParamDescriptor(pydantic.BaseModel):
    """Descriptor for a metric parameter."""

    name: str
    required: bool
    type: Optional[str] = None
    default: Optional[Any] = None


class MetricDescriptorResponse(pydantic.BaseModel):
    """Response schema for a metric descriptor."""

    name: str
    description: str
    init_params: List[MetricParamDescriptor]
    score_params: List[MetricParamDescriptor]


class MetricsListResponse(pydantic.BaseModel):
    """Response schema for listing all metrics."""

    metrics: List[MetricDescriptorResponse]


class MetricConfig(pydantic.BaseModel):
    """Configuration for a single metric to evaluate."""

    name: str = pydantic.Field(description="Name of the metric class")
    init_args: Dict[str, Any] = pydantic.Field(
        default_factory=dict,
        description="Arguments to pass to metric __init__",
    )


class TraceFieldMapping(pydantic.BaseModel):
    """Mapping from trace fields to metric score arguments.

    Keys are the metric argument names (e.g., 'input', 'output', 'context').
    Values are trace field paths (e.g., 'input', 'output', 'metadata.context').
    """

    mapping: Dict[str, str] = pydantic.Field(
        description=(
            "Mapping from metric argument names to trace field paths. "
            "Supported trace fields: 'input', 'output', 'metadata', 'name', 'tags'. "
            "Use dot notation for nested fields (e.g., 'metadata.context')."
        )
    )


class EvaluationRequest(pydantic.BaseModel):
    """Request schema for running evaluation on a trace."""

    trace_id: str = pydantic.Field(description="ID of the trace to evaluate")
    metrics: List[MetricConfig] = pydantic.Field(
        min_length=1,
        description="List of metric configurations",
    )
    field_mapping: TraceFieldMapping = pydantic.Field(
        description="Mapping from metric arguments to trace fields"
    )
    project_name: Optional[str] = pydantic.Field(
        default=None,
        description="Project name (if not provided, will be inferred from trace)",
    )


class EvaluationAcceptedResponse(pydantic.BaseModel):
    """Response schema for accepted evaluation request."""

    trace_id: str
    metrics_count: int
    message: str = (
        "Evaluation request accepted. Feedback scores will be logged to the trace."
    )
