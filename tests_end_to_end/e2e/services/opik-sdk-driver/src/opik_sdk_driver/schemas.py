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
    workspace: str | None = None


class TraceResponse(BaseModel):
    id: str
    name: str
    project_id: str


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
    task_output: str
    experiment_name: str
    judge_model: str | None = None
    workspace: str | None = None


class TestSuiteRunResponse(BaseModel):
    experiment_id: str | None
    experiment_name: str | None
    pass_rate: float | None
    items_passed: int
    items_failed: int
    items_total: int
