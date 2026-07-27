from typing import Any

from pydantic import BaseModel, ConfigDict


class ProjectCreate(BaseModel):
    # Reject unknown fields so a typo like `wokspace` fails 422 instead of
    # silently defaulting to the bridge's env workspace.
    model_config = ConfigDict(extra="forbid")

    name: str
    workspace: str | None = None


class ProjectResponse(BaseModel):
    id: str
    name: str


class TraceCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str
    name: str
    input: str
    output: str
    # Groups this trace with others sharing the same value into a conversation
    # thread, the unit the Logs Threads view renders.
    thread_id: str | None = None
    workspace: str | None = None


class TraceResponse(BaseModel):
    id: str
    name: str
    project_id: str


class SpanSeed(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    type: str = "general"
    input: dict[str, Any] | None = None
    output: dict[str, Any] | None = None
    metadata: dict[str, Any] | None = None
    # LLM-span fields. usage keys follow the OpenAI shape (prompt_tokens,
    # completion_tokens, total_tokens) so the UI renders token counts; model +
    # provider + total_cost drive the cost cell.
    model: str | None = None
    provider: str | None = None
    usage: dict[str, int] | None = None
    total_cost: float | None = None
    # Index into the same request's spans list identifying this span's parent.
    # None means the span is a direct child of the trace (a root span).
    parent_index: int | None = None


class ErrorInfoSeed(BaseModel):
    model_config = ConfigDict(extra="forbid")

    exception_type: str
    message: str
    traceback: str | None = None


class NestedTraceCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str
    name: str
    input: dict[str, Any] | None = None
    output: dict[str, Any] | None = None
    metadata: dict[str, Any] | None = None
    tags: list[str] | None = None
    thread_id: str | None = None
    feedback_scores: list[dict[str, Any]] | None = None
    spans: list[SpanSeed]
    workspace: str | None = None
    # Sets the trace's own error_info (as opposed to a span's), driving the
    # Traces table's Errors column/explain target. None means no trace-level error.
    error_info: ErrorInfoSeed | None = None
    # Backdates start_time by this many seconds and sets end_time to now, so the
    # trace renders a specific Duration cell value. None leaves both start_time
    # and end_time unset, which the UI renders as Duration "NA" — the same shape
    # as the SDK's own not-yet-ended traces.
    duration_seconds: float | None = None


class NestedTraceResponse(BaseModel):
    id: str
    name: str
    project_id: str
    span_count: int


class FeedbackDefinitionCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    # Numerical definition bounds. The UI's manual-score editor only renders a
    # named-score control once a matching feedback definition exists in the
    # workspace, so tests seed one before annotating through the panel.
    min: float = 0.0
    max: float = 1.0
    workspace: str | None = None


class FeedbackDefinitionResponse(BaseModel):
    id: str
    name: str


class DatasetCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    project_name: str
    description: str | None = None
    items: list[dict[str, Any]] | None = None
    workspace: str | None = None


class DatasetResponse(BaseModel):
    id: str
    name: str


class ExperimentItemSeed(BaseModel):
    model_config = ConfigDict(extra="forbid")

    input: str
    expected_output: str
    task_output: str


class ExperimentEvaluateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str
    dataset_name: str
    experiment_name: str
    items: list[ExperimentItemSeed]
    dataset_description: str | None = None
    workspace: str | None = None


class ExperimentItemScore(BaseModel):
    dataset_item_id: str
    input: str
    expected_output: str
    task_output: str
    score_name: str
    score_value: float


class ExperimentEvaluateResponse(BaseModel):
    experiment_id: str
    experiment_name: str
    dataset_id: str
    item_count: int
    scored_item_count: int
    scores: list[ExperimentItemScore]


class CompareDatasetItemSeed(BaseModel):
    model_config = ConfigDict(extra="forbid")

    input: str
    expected_output: str


class CompareExperimentSeed(BaseModel):
    model_config = ConfigDict(extra="forbid")

    experiment_name: str
    # Per-experiment task outputs, aligned by index with the shared dataset
    # items. Keeping task_output off the dataset item is what lets two
    # experiments share the same items (same content hash) yet score
    # differently under Equals(output, expected_output).
    task_outputs: list[str]


class ExperimentCompareSeedRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str
    dataset_name: str
    items: list[CompareDatasetItemSeed]
    experiments: list[CompareExperimentSeed]
    dataset_description: str | None = None
    workspace: str | None = None


class CompareExperimentResult(BaseModel):
    experiment_id: str
    experiment_name: str
    scores: list[ExperimentItemScore]


class ExperimentCompareSeedResponse(BaseModel):
    dataset_id: str
    dataset_name: str
    item_count: int
    experiments: list[CompareExperimentResult]


class TestSuiteItemSeed(BaseModel):
    model_config = ConfigDict(extra="forbid")

    data: dict[str, Any]
    assertions: list[str] | None = None
    description: str | None = None


class TestSuiteCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    project_name: str
    description: str | None = None
    global_assertions: list[str] = []
    runs_per_item: int | None = None
    pass_threshold: int | None = None
    items: list[TestSuiteItemSeed] | None = None
    workspace: str | None = None


class TestSuiteResponse(BaseModel):
    id: str
    name: str


class TestSuiteRunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    suite_name: str
    project_name: str
    task_output: str
    experiment_name: str
    judge_model: str | None = None
    workspace: str | None = None


class TestSuiteInsertItemsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    suite_name: str
    project_name: str
    items: list[TestSuiteItemSeed]
    workspace: str | None = None


class TestSuiteInsertItemsResponse(BaseModel):
    suite_id: str
    inserted: int


class TestSuiteRunResponse(BaseModel):
    experiment_id: str | None
    experiment_name: str | None
    pass_rate: float | None
    items_passed: int
    items_failed: int
    items_total: int


class TextPromptCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    prompt: str
    description: str | None = None
    project_name: str | None = None
    workspace: str | None = None


class ChatPromptCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str
    messages: list[dict[str, str]]
    description: str | None = None
    project_name: str | None = None
    workspace: str | None = None


class PromptResponse(BaseModel):
    id: str
    name: str


class AnnotationQueueCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str
    name: str
    trace_ids: list[str]
    feedback_definition_names: list[str] | None = None
    workspace: str | None = None


class AnnotationQueueResponse(BaseModel):
    id: str
    name: str
