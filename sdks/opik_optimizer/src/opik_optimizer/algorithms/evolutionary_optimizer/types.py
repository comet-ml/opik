"""Type definitions for Evolutionary Optimizer."""

from typing import Any

from pydantic import BaseModel, Field, model_validator

from ...api_objects.types import Messages


class CrossoverResponse(BaseModel):
    """Response containing two child prompts from crossover operation.

    Each child is a list of messages representing a complete prompt.
    Example:
        {
            "child_1": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}],
            "child_2": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}]
        }
    """

    child_1: Messages
    child_2: Messages


class StyleInferenceResponse(BaseModel):
    """Response model for style inference from dataset."""

    style: str


class MutationResponse(BaseModel):
    """Response model for mutation operations returning prompt messages."""

    messages: Messages = Field(..., description="Mutated prompt messages")

    @model_validator(mode="before")
    @classmethod
    def _wrap_messages(cls, value: Any) -> Any:
        if isinstance(value, list):
            return {"messages": value}
        if isinstance(value, dict):
            if "messages" in value:
                return value
            if "role" in value and "content" in value:
                return {"messages": [value]}
        return value
