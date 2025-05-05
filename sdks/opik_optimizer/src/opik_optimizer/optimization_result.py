"""Module containing the OptimizationResult class."""

from typing import Dict, List, Any, Optional, Union, Literal
import pydantic
from opik.evaluation.metrics import BaseMetric


class OptimizationResult(pydantic.BaseModel):
    """Result of an optimization run."""
    prompt: Union[str, List[Dict[Literal["role", "content"], str]]]
    score: float
    metric_name: str
    metadata: Dict[str, Any] = pydantic.Field(default_factory=dict)  # Default empty dict
    details: Dict[str, Any] = pydantic.Field(default_factory=dict)  # Default empty dict
    best_prompt: Optional[str] = None
    best_score: Optional[float] = None
    best_metric_name: Optional[str] = None
    best_details: Optional[Dict[str, Any]] = None
    all_results: Optional[List[Dict[str, Any]]] = None
    history: List[Dict[str, Any]] = []
    metric: Optional[BaseMetric] = None
    demonstrations: Optional[List[Dict[str, Any]]] = None
    optimizer: str = "Optimizer"
    tool_prompts: Optional[Dict[str, str]] = None

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

    def __str__(self) -> str:
        """Return a string representation of the optimization result."""
        prompt = self.best_prompt if self.best_prompt is not None else self.prompt
        score = self.best_score if self.best_score is not None else self.score
        metric_name = self.best_metric_name if self.best_metric_name is not None else self.metric_name
        details = self.best_details if self.best_details is not None else self.details
        
        result = f"{self.optimizer} Results:\n    Best prompt: {prompt}\n    Score: {score:.3f} ({metric_name})"
        if details:
            result += f"\nDetails: {details}"
        return result

    def model_dump(self) -> Dict[str, Any]:
        return super().model_dump()
