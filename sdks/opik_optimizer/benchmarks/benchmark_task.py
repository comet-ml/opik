from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel

from opik_optimizer.optimization_config import chat_prompt


class TaskEvaluationResult(BaseModel):
    metrics: List[Dict[Literal["metric_name", "score", "timestamp"], Any]]
    duration_seconds: float

class TaskResult(BaseModel):
    model_config = {"arbitrary_types_allowed":True}

    id: str
    dataset_name: str
    optimizer_name: str
    model_name: str
    timestamp_start: float
    status: Literal["Pending", "Running", "Success", "Failed"]
    initial_prompt: Optional[chat_prompt.ChatPrompt] = None
    initial_evaluation: Optional[TaskEvaluationResult] = None
    optimized_prompt: Optional[chat_prompt.ChatPrompt] = None
    optimized_evaluation: Optional[TaskEvaluationResult] = None
    error_message: Optional[str] = None
    timestamp_end: Optional[float] = None
    llm_calls_total_optimization: Optional[int] = None
    optimization_raw_result: Optional[Any] = None
