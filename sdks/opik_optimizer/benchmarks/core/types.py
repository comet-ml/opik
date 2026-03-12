from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

import opik_optimizer
from pydantic import BaseModel, Field, field_validator


@dataclass(frozen=True)
class TaskSpec:
    dataset_name: str
    optimizer_name: str
    model_name: str
    test_mode: bool
    model_parameters: dict[str, Any] | None = field(default=None)
    optimizer_params: dict[str, Any] | None = field(default=None)
    optimizer_prompt_params: dict[str, Any] | None = field(default=None)
    datasets: dict[str, Any] | None = field(default=None)
    metrics: list[str | dict[str, Any]] | None = field(default=None)
    prompt_messages: list[dict[str, Any]] | None = field(default=None)

    @property
    def task_id(self) -> str:
        return f"{self.dataset_name}_{self.optimizer_name}_{self.model_name}"

    def to_dict(self) -> dict[str, Any]:
        return {
            "dataset_name": self.dataset_name,
            "optimizer_name": self.optimizer_name,
            "model_name": self.model_name,
            "test_mode": self.test_mode,
            "model_parameters": self.model_parameters,
            "optimizer_params": self.optimizer_params,
            "optimizer_prompt_params": self.optimizer_prompt_params,
            "datasets": self.datasets,
            "metrics": self.metrics,
            "prompt_messages": self.prompt_messages,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> TaskSpec:
        return cls(
            dataset_name=str(data["dataset_name"]),
            optimizer_name=str(data["optimizer_name"]),
            model_name=str(data["model_name"]),
            test_mode=bool(data.get("test_mode", False)),
            model_parameters=data.get("model_parameters"),
            optimizer_params=data.get("optimizer_params"),
            optimizer_prompt_params=data.get("optimizer_prompt_params"),
            datasets=data.get("datasets"),
            metrics=data.get("metrics"),
            prompt_messages=data.get("prompt_messages"),
        )


TaskStatus = Literal["Pending", "Running", "Success", "Failed"]
TASK_STATUS_PENDING: TaskStatus = "Pending"
TASK_STATUS_RUNNING: TaskStatus = "Running"
TASK_STATUS_SUCCESS: TaskStatus = "Success"
TASK_STATUS_FAILED: TaskStatus = "Failed"


@dataclass(frozen=True)
class RunSummary:
    engine: str
    run_id: str | None
    status: str
    metadata: dict[str, Any] = field(default_factory=dict)


class DatasetMetadata(BaseModel):
    name: str
    id: str | None = None
    split: str | None = None


class TaskEvaluationResult(BaseModel):
    model_config = {"arbitrary_types_allowed": True}
    metrics: list[
        dict[
            Literal[
                "metric_name",
                "score",
                "timestamp",
                "dataset_item_ids",
                "sample_size",
            ],
            Any,
        ]
    ]
    duration_seconds: float
    dataset: DatasetMetadata | None = None
    eval_id: str | None = None
    experiment_id: str | None = None


class EvaluationSet(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    class EvaluationEntry(BaseModel):
        model_config = {"arbitrary_types_allowed": True}
        step_id: str | None = None
        result: TaskEvaluationResult | None = None

    train: EvaluationEntry | None = None
    validation: EvaluationEntry | None = None
    test: EvaluationEntry | None = None


class EvaluationStage(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    stage: str
    split: str | None = None
    evaluation: TaskEvaluationResult | None = None
    prompt_snapshot: (
        opik_optimizer.ChatPrompt | dict[str, opik_optimizer.ChatPrompt] | None
    ) = None
    step_ref: str | None = None
    notes: str | None = None


class TaskResult(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    id: str
    dataset_name: str
    optimizer_name: str
    model_name: str
    model_parameters: dict[str, Any] | None = None
    timestamp_start: float
    status: TaskStatus
    initial_prompt: (
        opik_optimizer.ChatPrompt | dict[str, opik_optimizer.ChatPrompt] | None
    ) = None
    optimized_prompt: (
        opik_optimizer.ChatPrompt | dict[str, opik_optimizer.ChatPrompt] | None
    ) = None
    evaluations: dict[str, EvaluationSet] = Field(default_factory=dict)
    stages: list[EvaluationStage] = Field(default_factory=list)
    optimization_history: dict[str, Any] = Field(default_factory=lambda: {"rounds": []})
    error_message: str | None = None
    timestamp_end: float | None = None
    llm_calls_total_optimization: int | None = None
    optimization_raw_result: Any | None = None
    optimization_summary: Any | None = None
    dataset_metadata: dict[str, DatasetMetadata] = Field(default_factory=dict)
    evaluation_split: str | None = None
    requested_split: str | None = None
    optimizer_prompt_params_used: dict[str, Any] | None = None
    optimizer_params_used: dict[str, Any] | None = None

    @field_validator("optimization_history", mode="before")
    @classmethod
    def _validate_optimization_history(cls, value: Any) -> Any:
        if isinstance(value, dict):
            rounds = value.get("rounds")
            if rounds is None:
                raise ValueError("optimization_history must contain 'rounds'")
            if not isinstance(rounds, list):
                raise TypeError("optimization_history['rounds'] must be a list")
            for entry in rounds:
                if not isinstance(entry, dict):
                    raise TypeError(
                        "optimization_history['rounds'] entries must be dicts"
                    )
        return value

    @classmethod
    def model_validate(
        cls,
        obj: Any,
        *,
        strict: bool | None = None,
        from_attributes: bool | None = None,
        context: Any | None = None,
        by_alias: bool | None = None,
        by_name: bool | None = None,
    ) -> TaskResult:
        def _deserialize_prompt(
            prompt_data: Any,
        ) -> opik_optimizer.ChatPrompt | dict[str, opik_optimizer.ChatPrompt] | None:
            if prompt_data is None:
                return None
            if isinstance(prompt_data, opik_optimizer.ChatPrompt):
                return prompt_data
            if isinstance(prompt_data, dict):
                if "messages" in prompt_data or "system" in prompt_data:
                    return opik_optimizer.ChatPrompt.model_validate(prompt_data)
                result: dict[str, opik_optimizer.ChatPrompt] = {}
                for key, value in prompt_data.items():
                    if isinstance(value, opik_optimizer.ChatPrompt):
                        result[key] = value
                    elif isinstance(value, dict):
                        result[key] = opik_optimizer.ChatPrompt.model_validate(value)
                return result
            return prompt_data

        if obj.get("initial_prompt"):
            obj["initial_prompt"] = _deserialize_prompt(obj["initial_prompt"])

        if obj.get("optimized_prompt"):
            obj["optimized_prompt"] = _deserialize_prompt(obj["optimized_prompt"])

        if obj.get("evaluations") and isinstance(obj["evaluations"], dict):
            normalized: dict[str, EvaluationSet] = {}
            for key, value in obj["evaluations"].items():
                normalized[key] = EvaluationSet.model_validate(value)
            obj["evaluations"] = normalized

        if obj.get("stages") and isinstance(obj["stages"], list):
            normalized_stages: list[EvaluationStage] = []
            for entry in obj["stages"]:
                if isinstance(entry, dict):
                    if entry.get("evaluation") and isinstance(
                        entry["evaluation"], dict
                    ):
                        entry["evaluation"] = TaskEvaluationResult.model_validate(
                            entry["evaluation"]
                        )
                    if entry.get("prompt_snapshot"):
                        entry["prompt_snapshot"] = _deserialize_prompt(
                            entry["prompt_snapshot"]
                        )
                    normalized_stages.append(EvaluationStage.model_validate(entry))
                else:
                    normalized_stages.append(entry)
            obj["stages"] = normalized_stages

        if obj.get("dataset_metadata"):
            obj["dataset_metadata"] = {
                key: DatasetMetadata.model_validate(value)
                for key, value in obj["dataset_metadata"].items()
            }

        if isinstance(obj, dict) and "optimization_history" in obj:
            obj["optimization_history"] = cls._validate_optimization_history(
                obj["optimization_history"]
            )

        return super().model_validate(
            obj,
            strict=strict,
            from_attributes=from_attributes,
            context=context,
            by_alias=by_alias,
            by_name=by_name,
        )


class PreflightContext(BaseModel):
    system_time: str
    cwd: str
    manifest_path: str | None = None
    checkpoint_dir: str | None = None
    run_id: str | None = None
    opik_version: str | None = None
    opik_optimizer_version: str | None = None


class PreflightEntry(BaseModel):
    task_id: str
    short_id: str
    dataset_name: str
    evaluation_name: str | None
    optimizer_name: str
    model_name: str
    status: Literal["ok", "error"]
    splits: str | None = None
    error: str | None = None


class PreflightSummary(BaseModel):
    total_tasks: int
    datasets: list[str]
    optimizers: list[str]
    models: list[str]
    errors: list[str] = []


class PreflightReport(BaseModel):
    context: PreflightContext
    summary: PreflightSummary
    entries: list[PreflightEntry]


class BenchmarkRunResult(BaseModel):
    run_id: str
    test_mode: bool
    preflight: PreflightReport | None = None
    tasks: list[TaskSpec]
    task_results: list[TaskResult]
    checkpoint_path: str
    results_path: str | None = None
