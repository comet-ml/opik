"""Module containing configuration classes for optimization."""

import pydantic
import opik
from typing import Dict, Callable, Union, List, Literal, Any, Optional
from opik.evaluation.metrics import BaseMetric


class MetricConfig(pydantic.BaseModel):
    """Configuration for a metric used in optimization."""
    metric: BaseMetric
    inputs: Dict[str, Union[str, Callable[[Any], Any]]]

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)


class TaskConfig(pydantic.BaseModel):
    """Configuration for a prompt task."""
    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)
    
    instruction_prompt: Union[str, List[Dict[Literal["role", "content"], str]]]
    use_chat_prompt: bool = False
    input_dataset_fields: List[str]
    output_dataset_field: str
    tools: List[Any] = []


class OptimizationConfig(pydantic.BaseModel):
    """Configuration for optimization."""
    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

    dataset: opik.Dataset
    objective: MetricConfig
    optimization_direction: Literal["maximize", "minimize"] = "maximize"
    task: TaskConfig
