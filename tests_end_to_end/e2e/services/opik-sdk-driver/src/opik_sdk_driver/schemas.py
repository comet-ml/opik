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
    # Ages the whole trace by this many days: it gets a client-supplied UUIDv7
    # id stamped at that instant, and its timestamps move back with it. Time
    # windows on the read paths (notably GET /v1/private/projects/stats) are
    # applied to the timestamp embedded in the id, not to start_time, so this
    # is what places a trace deterministically inside or outside a rolling
    # window. None keeps the SDK's own behaviour: a server-fresh id stamped now.
    age_days: float | None = None


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


class DatasetInsertItemsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dataset_name: str
    project_name: str
    items: list[dict[str, Any]]
    # Worker threads Dataset.insert uses to upload this call's batches. 1 (the
    # SDK default) uploads them sequentially; >1 uploads them in parallel, and
    # both paths must land in ONE dataset version with identical counters.
    # Parallel upload needs a backend >= MIN_BACKEND_VERSION_FOR_PARALLEL_INSERT
    # (2.2.8); against an older one the SDK silently falls back to sequential.
    num_threads: int = 1
    workspace: str | None = None


class DatasetInsertItemsResponse(BaseModel):
    dataset_id: str
    inserted: int


class DatasetReadItemsRequest(BaseModel):
    """One `Dataset.get_items(...)` call, with its read knobs exposed verbatim.

    `num_threads`/`chunk_size`/`nb_samples` are plain ints rather than
    constrained ones on purpose: the SDK's own validation of them (0, negative,
    over the chunk cap) is part of what a caller reads this route to assert, so
    pydantic must not reject those values before the SDK sees them.
    """

    model_config = ConfigDict(extra="forbid")

    dataset_name: str
    project_name: str
    # Omitted keys are left to the SDK's defaults rather than restated here, so
    # a caller asking for "the defaults" really gets them.
    nb_samples: int | None = None
    num_threads: int | None = None
    chunk_size: int | None = None
    filter_string: str | None = None
    workspace: str | None = None


class DatasetReadItemsResponse(BaseModel):
    """What one read returned, or why the SDK refused to start it.

    Items are reduced to their ids in dataset order: a caller comparing two
    reads is asserting which items came back and in what order, and shipping
    whole payloads back over the bridge for a few-thousand-item dataset is a
    cost with no assertion behind it.
    """

    item_ids: list[str]
    # The ValueError message when the SDK rejected the arguments, else None. The
    # route answers 200 either way so the caller can assert on the message; a
    # rejected read has no items, never an empty result that looks like one.
    value_error: str | None = None


class DatasetReadWithMidReadInsertRequest(BaseModel):
    """A `stream_items()` read with an insert committed in the middle of it.

    The interleaving is driven here rather than by racing two HTTP calls from
    the caller: the reader consumes `pause_after_chunks` chunks, runs the insert
    to completion, and only then consumes the rest. That makes the overlap
    structural — every remaining page is fetched against a backend that already
    holds the new items — where a timing race would leave the test asserting
    whatever the network happened to order.
    """

    model_config = ConfigDict(extra="forbid")

    dataset_name: str
    project_name: str
    items: list[dict[str, Any]]
    chunk_size: int
    num_threads: int = 1
    # Must be >= 1 (so the read is genuinely in progress) and low enough that
    # pages remain unfetched at the pause — see the route's docstring for the
    # look-ahead the reader keeps in flight.
    pause_after_chunks: int
    workspace: str | None = None


class DatasetReadWithMidReadInsertResponse(BaseModel):
    item_ids: list[str]
    chunk_sizes: list[int]
    # Chunks consumed before the insert ran, echoed back so the caller can
    # assert the read really was mid-flight and not already finished.
    chunks_before_insert: int
    inserted: int


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


class ThreadsEvaluateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    project_name: str
    # Always distinct from project_name in the specs that drive this: the whole
    # point of the flow is that the evaluation_task trace lands in a SEPARATE
    # project from the conversation it scored.
    eval_project_name: str
    thread_id: str
    # Keys the transforms read off each trace's input/output dict. The SDK takes
    # callables; the wire cannot carry one, so the route builds the two lambdas
    # from these and the shape stays the caller's choice.
    trace_input_key: str
    trace_output_key: str
    # When set, evaluate_threads is called with a trace_context_transform that
    # reads this key off trace.metadata. When None the argument is omitted
    # entirely, which is the pre-existing caller shape.
    context_metadata_key: str | None = None
    metric_name: str
    # Fixed score the metric returns. Deterministic on purpose: this flow must
    # be assertable without a provider key or an LLM verdict.
    score_value: float
    score_reason: str
    workspace: str | None = None


class ThreadsEvaluateScore(BaseModel):
    name: str
    value: float
    reason: str | None = None


class ThreadsEvaluateResponse(BaseModel):
    thread_id: str
    eval_project_name: str
    scores: list[ThreadsEvaluateScore]
    # The conversation EXACTLY as the metric's score() received it, as raw
    # dicts. Deliberately not a typed model: the fact under test is whether the
    # `context` KEY is present at all, and any pydantic model with an optional
    # `context` field would serialize an absent key as `"context": null` and
    # destroy the distinction the caller is asserting on.
    conversation: list[dict[str, Any]]
