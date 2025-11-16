from typing import Any, Literal

from pydantic import BaseModel

import opik_optimizer


class TaskEvaluationResult(BaseModel):
    metrics: list[dict[Literal["metric_name", "score", "timestamp"], Any]]
    duration_seconds: float


class TaskResult(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    id: str
    dataset_name: str
    optimizer_name: str
    model_name: str
    timestamp_start: float
    status: Literal["Pending", "Running", "Success", "Failed"]
    initial_prompt: opik_optimizer.ChatPrompt | None = None
    initial_evaluation: TaskEvaluationResult | None = None
    optimized_prompt: opik_optimizer.ChatPrompt | None = None
    optimized_evaluation: TaskEvaluationResult | None = None
    error_message: str | None = None
    timestamp_end: float | None = None
    llm_calls_total_optimization: int | None = None
    optimization_raw_result: Any | None = None

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

        # Handle TaskEvaluationResult objects
        if obj.get("initial_evaluation") and isinstance(
            obj["initial_evaluation"], dict
        ):
            obj["initial_evaluation"] = TaskEvaluationResult.model_validate(
                obj["initial_evaluation"]
            )

        if obj.get("optimized_evaluation") and isinstance(
            obj["optimized_evaluation"], dict
        ):
            obj["optimized_evaluation"] = TaskEvaluationResult.model_validate(
                obj["optimized_evaluation"]
            )

        # Use the parent class's model_validate method to create the instance
        return super().model_validate(
            obj,
            strict=strict,
            from_attributes=from_attributes,
            context=context,
            by_alias=by_alias,
            by_name=by_name,
        )
