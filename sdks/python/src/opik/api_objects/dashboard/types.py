"""Typed models for the Opik dashboard ``config`` blob.

The dashboard ``config`` is stored by the backend as an opaque JSON document; its
real schema is owned by the frontend. These models mirror that schema (schema
``version`` 4) and are used purely as builders/validators — the canonical
representation carried by :class:`opik.api_objects.dashboard.dashboard.Dashboard`
is always the raw ``dict`` read back from the backend, so fields the SDK does not
model survive a read-modify-write round trip untouched.

Source of truth, re-sync these when the frontend schema changes:
  - apps/opik-frontend/src/types/dashboard.ts        (state/section/widget/layout shapes, enums)
  - apps/opik-frontend/src/lib/dashboard/utils.ts     (DASHBOARD_VERSION, experiment limits, widget/type rules)
  - apps/opik-frontend/src/lib/dashboard/layout.ts    (grid constants, per-widget sizes)
  - apps/opik-frontend/src/api/projects/useProjectMetric.ts                         (ProjectMetricType wire values)
  - apps/opik-frontend/src/v2/pages-shared/dashboards/widgets/ProjectStatsCardWidget/metrics.ts  (StatsCardMetric ids)

Enums are permissive: known values are exposed for discoverability/autocomplete,
but the model fields accept arbitrary strings so the SDK keeps working when the
frontend adds new widget types, metrics, or fields.
"""

import enum
import time
from typing import Any, Dict, List, Optional, Union

import pydantic
from pydantic.alias_generators import to_camel

from opik import exceptions, id_helpers

# Mirrors apps/opik-frontend/src/lib/dashboard/utils.ts and layout.ts
DASHBOARD_VERSION = 4
GRID_COLUMNS = 6
MAX_WIDGET_HEIGHT = 12
MIN_WIDGET_WIDTH = 1
MIN_WIDGET_HEIGHT = 1
MIN_MAX_EXPERIMENTS = 1
MAX_MAX_EXPERIMENTS = 100
DEFAULT_MAX_EXPERIMENTS = 10

FEEDBACK_SCORES_PREFIX = "feedback_scores."


def now_ms() -> int:
    """Current epoch time in milliseconds (frontend stores ``lastModified`` as ms)."""
    return int(time.time() * 1000)


class DashboardType(str, enum.Enum):
    MULTI_PROJECT = "multi_project"
    EXPERIMENTS = "experiments"


class WidgetType(str, enum.Enum):
    PROJECT_METRICS = "project_metrics"
    PROJECT_STATS_CARD = "project_stats_card"
    TEXT_MARKDOWN = "text_markdown"
    EXPERIMENTS_FEEDBACK_SCORES = "experiments_feedback_scores"
    EXPERIMENT_LEADERBOARD = "experiment_leaderboard"


class BreakdownField(str, enum.Enum):
    NONE = "none"
    TAGS = "tags"
    METADATA = "metadata"
    NAME = "name"
    ERROR_INFO = "error_info"
    ERROR_TYPE = "error_type"
    MODEL = "model"
    PROVIDER = "provider"
    TYPE = "type"


class ChartType(str, enum.Enum):
    LINE = "line"
    BAR = "bar"
    RADAR = "radar"


class TraceDataType(str, enum.Enum):
    TRACES = "traces"
    SPANS = "spans"


class ProjectMetricType(str, enum.Enum):
    """ALL-CAPS metric ids used by the ``project_metrics`` widget ``metricType`` field."""

    FEEDBACK_SCORES = "FEEDBACK_SCORES"
    TRACE_COUNT = "TRACE_COUNT"
    DURATION = "DURATION"
    TOKEN_USAGE = "TOKEN_USAGE"
    COST = "COST"
    GUARDRAILS_FAILED_COUNT = "GUARDRAILS_FAILED_COUNT"
    THREAD_COUNT = "THREAD_COUNT"
    THREAD_DURATION = "THREAD_DURATION"
    THREAD_FEEDBACK_SCORES = "THREAD_FEEDBACK_SCORES"
    SPAN_COUNT = "SPAN_COUNT"
    SPAN_DURATION = "SPAN_DURATION"
    SPAN_FEEDBACK_SCORES = "SPAN_FEEDBACK_SCORES"
    SPAN_TOKEN_USAGE = "SPAN_TOKEN_USAGE"
    TRACE_AVERAGE_DURATION = "TRACE_AVERAGE_DURATION"
    SPAN_AVERAGE_DURATION = "SPAN_AVERAGE_DURATION"
    THREAD_AVERAGE_DURATION = "THREAD_AVERAGE_DURATION"
    TRACE_ERROR_RATE = "TRACE_ERROR_RATE"
    SPAN_ERROR_RATE = "SPAN_ERROR_RATE"


class StatsCardMetric(str, enum.Enum):
    """Lowercase-dotted metric ids used by the ``project_stats_card`` widget ``metric`` field.

    Dynamic feedback-score metrics use the ``feedback_scores.<score_name>`` form and
    are not enumerated here; any such string is accepted.
    """

    DURATION_P50 = "duration.p50"
    DURATION_P90 = "duration.p90"
    DURATION_P99 = "duration.p99"
    INPUT = "input"
    OUTPUT = "output"
    METADATA = "metadata"
    TAGS = "tags"
    TOTAL_ESTIMATED_COST_SUM = "total_estimated_cost_sum"
    USAGE_COMPLETION_TOKENS = "usage.completion_tokens"
    USAGE_PROMPT_TOKENS = "usage.prompt_tokens"
    USAGE_TOTAL_TOKENS = "usage.total_tokens"
    ERROR_COUNT = "error_count"
    TRACE_COUNT = "trace_count"
    THREAD_COUNT = "thread_count"
    LLM_SPAN_COUNT = "llm_span_count"
    SPAN_COUNT = "span_count"
    TOTAL_ESTIMATED_COST = "total_estimated_cost"
    GUARDRAILS_FAILED_COUNT = "guardrails_failed_count"


class _DashboardModel(pydantic.BaseModel):
    """Base for every dashboard model: snake_case in Python, camelCase on the wire."""

    model_config = pydantic.ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="allow",
    )

    def to_jsonable(self) -> Dict[str, Any]:
        """Serialize to a pure-JSON dict with camelCase keys (drops unset optionals)."""
        return self.model_dump(by_alias=True, exclude_none=True, mode="json")


class BreakdownConfig(_DashboardModel):
    field: str
    metadata_key: Optional[str] = None
    sub_metric: Optional[str] = None
    aggregate_total: Optional[bool] = None

    @pydantic.model_validator(mode="after")
    def _check_metadata_key(self) -> "BreakdownConfig":
        if self.field == BreakdownField.METADATA and not self.metadata_key:
            raise exceptions.DashboardValidationError(
                "breakdown.metadata_key is required when breakdown.field is 'metadata'"
            )
        return self


class ProjectMetricsConfig(_DashboardModel):
    metric_type: str = ProjectMetricType.TRACE_COUNT.value
    chart_type: Optional[str] = ChartType.LINE.value
    trace_filters: Optional[List[Dict[str, Any]]] = None
    thread_filters: Optional[List[Dict[str, Any]]] = None
    span_filters: Optional[List[Dict[str, Any]]] = None
    feedback_scores: Optional[List[str]] = None
    duration_metrics: Optional[List[str]] = None
    usage_metrics: Optional[List[str]] = None
    breakdown: Optional[BreakdownConfig] = None


class ProjectStatsCardConfig(_DashboardModel):
    source: str = TraceDataType.TRACES.value
    metric: str = StatsCardMetric.TRACE_COUNT.value
    trace_filters: Optional[List[Dict[str, Any]]] = None
    span_filters: Optional[List[Dict[str, Any]]] = None


class TextMarkdownConfig(_DashboardModel):
    content: Optional[str] = None


class ExperimentsFeedbackScoresConfig(_DashboardModel):
    filters: Optional[List[Dict[str, Any]]] = None
    groups: Optional[List[Dict[str, Any]]] = None
    chart_type: Optional[str] = ChartType.BAR.value
    feedback_scores: Optional[List[str]] = None
    max_experiments_count: Optional[Union[int, str]] = DEFAULT_MAX_EXPERIMENTS

    @pydantic.model_validator(mode="after")
    def _check_max_experiments(self) -> "ExperimentsFeedbackScoresConfig":
        _check_experiment_range(self.max_experiments_count, "max_experiments_count")
        return self


class ExperimentLeaderboardConfig(_DashboardModel):
    selected_columns: List[str] = pydantic.Field(
        default_factory=lambda: [
            "dataset_id",
            "created_at",
            "duration.p50",
            "pass_rate",
        ]
    )
    enable_ranking: bool = False
    filters: Optional[List[Dict[str, Any]]] = None
    ranking_metric: Optional[str] = None
    ranking_direction: Optional[bool] = None
    columns_order: Optional[List[str]] = None
    scores_columns_order: Optional[List[str]] = None
    metadata_columns_order: Optional[List[str]] = None
    columns_width: Optional[Dict[str, int]] = None
    # Frontend stores this as either an int (default config) or a string (after editing).
    max_rows: Optional[Union[int, str]] = None
    sorting: Optional[List[Dict[str, Any]]] = None

    @pydantic.model_validator(mode="after")
    def _check_ranking_and_rows(self) -> "ExperimentLeaderboardConfig":
        if self.enable_ranking and not self.ranking_metric:
            raise exceptions.DashboardValidationError(
                "ranking_metric is required when enable_ranking is True"
            )
        if self.max_rows is not None:
            _check_experiment_range(self.max_rows, "max_rows")
        return self


def _check_experiment_range(value: Union[int, str, None], field_name: str) -> None:
    if value is None:
        return
    try:
        numeric = int(value)
    except (TypeError, ValueError):
        raise exceptions.DashboardValidationError(
            f"{field_name} must be an integer between {MIN_MAX_EXPERIMENTS} and {MAX_MAX_EXPERIMENTS}"
        )
    if not MIN_MAX_EXPERIMENTS <= numeric <= MAX_MAX_EXPERIMENTS:
        raise exceptions.DashboardValidationError(
            f"{field_name} must be between {MIN_MAX_EXPERIMENTS} and {MAX_MAX_EXPERIMENTS}, got {numeric}"
        )


WidgetConfig = Union[
    ProjectMetricsConfig,
    ProjectStatsCardConfig,
    TextMarkdownConfig,
    ExperimentsFeedbackScoresConfig,
    ExperimentLeaderboardConfig,
]


class DashboardWidget(_DashboardModel):
    type: str
    id: str = pydantic.Field(default_factory=id_helpers.generate_id)
    title: str = ""
    generated_title: Optional[str] = None
    subtitle: Optional[str] = None
    config: Dict[str, Any] = pydantic.Field(default_factory=dict)

    @pydantic.field_validator("config", mode="before")
    @classmethod
    def _normalize_config(cls, value: Any) -> Dict[str, Any]:
        if isinstance(value, _DashboardModel):
            return value.to_jsonable()
        if value is None:
            return {}
        return value


class DashboardLayoutItem(_DashboardModel):
    id: str = pydantic.Field(alias="i")
    x: int
    y: int
    w: int
    h: int
    min_w: Optional[int] = None
    max_w: Optional[int] = None
    min_h: Optional[int] = None
    max_h: Optional[int] = None


class DashboardSection(_DashboardModel):
    title: str
    id: str = pydantic.Field(default_factory=id_helpers.generate_id)
    widgets: List[DashboardWidget] = pydantic.Field(default_factory=list)
    layout: List[DashboardLayoutItem] = pydantic.Field(default_factory=list)


class DashboardState(_DashboardModel):
    version: int = DASHBOARD_VERSION
    sections: List[DashboardSection] = pydantic.Field(default_factory=list)
    last_modified: int = pydantic.Field(default_factory=now_ms)
