"""Type definitions for Evolutionary Optimizer."""

from pydantic import BaseModel

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
