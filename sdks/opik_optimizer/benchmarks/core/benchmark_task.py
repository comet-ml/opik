from typing import Any, Literal

from pydantic import BaseModel, Field

import opik_optimizer

TaskStatus = Literal["Pending", "Running", "Success", "Failed"]
TASK_STATUS_PENDING: TaskStatus = "Pending"
TASK_STATUS_RUNNING: TaskStatus = "Running"
TASK_STATUS_SUCCESS: TaskStatus = "Success"
TASK_STATUS_FAILED: TaskStatus = "Failed"


class DatasetMetadata(BaseModel):
    name: str
    id: str | None = None
    split: str | None = None


class TaskEvaluationResult(BaseModel):
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
    """Grouped evaluations by split for a single stage (initial/final)."""

    class EvaluationEntry(BaseModel):
        step_id: str | None = None
        result: TaskEvaluationResult | None = None

    train: EvaluationEntry | None = None
    validation: EvaluationEntry | None = None
    test: EvaluationEntry | None = None


class EvaluationStage(BaseModel):
    """Stage-aware evaluation entry (e.g., initial baseline, post-optimization)."""

    stage: str
    split: str | None = None
    evaluation: TaskEvaluationResult | None = None
    prompt_snapshot: opik_optimizer.ChatPrompt | None = None
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
    initial_prompt: opik_optimizer.ChatPrompt | None = None
    optimized_prompt: opik_optimizer.ChatPrompt | None = None
    evaluations: dict[str, EvaluationSet] = Field(default_factory=dict)
    stages: list[EvaluationStage] = Field(default_factory=list)
    optimization_history: dict[str, Any] = Field(default_factory=dict)
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
    ) -> "TaskResult":
        """Custom validation method to handle nested objects during deserialization."""
        # Handle ChatPrompt objects
        if obj.get("initial_prompt") and isinstance(obj["initial_prompt"], dict):
            obj["initial_prompt"] = opik_optimizer.ChatPrompt.model_validate(
                obj["initial_prompt"]
            )

        if obj.get("optimized_prompt") and isinstance(obj["optimized_prompt"], dict):
            obj["optimized_prompt"] = opik_optimizer.ChatPrompt.model_validate(
                obj["optimized_prompt"]
            )

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
                    if entry.get("prompt_snapshot") and isinstance(
                        entry["prompt_snapshot"], dict
                    ):
                        entry["prompt_snapshot"] = (
                            opik_optimizer.ChatPrompt.model_validate(  # type: ignore[attr-defined]
                                entry["prompt_snapshot"]
                            )
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

        # Use the parent class's model_validate method to create the instance
        return super().model_validate(
            obj,
            strict=strict,
            from_attributes=from_attributes,
            context=context,
            by_alias=by_alias,
            by_name=by_name,
        )
