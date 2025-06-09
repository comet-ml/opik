"""Module containing configuration classes for optimization."""

from typing import Any, Dict, List, Literal, Union

import pydantic


class TaskConfig(pydantic.BaseModel):
    """Configuration for a prompt task."""
    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)
    
    instruction_prompt: Union[str, List[Dict[Literal["role", "content"], str]]]
    use_chat_prompt: bool = False
    input_dataset_fields: List[str]
    output_dataset_field: str
    tools: List[Any] = []
