"""Module containing configuration classes for optimization."""

from typing import Any

import pydantic


class TaskConfig(pydantic.BaseModel):
    """Configuration for a prompt task."""

    model_config = pydantic.ConfigDict(arbitrary_types_allowed=True)

    instruction_prompt: str
    use_chat_prompt: bool = False
    input_dataset_fields: list[str]
    output_dataset_field: str
    tools: list[Any] = []
