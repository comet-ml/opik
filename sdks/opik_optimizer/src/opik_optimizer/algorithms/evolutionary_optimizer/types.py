"""Type definitions for Evolutionary Optimizer."""

from ...utils.prompt_editing import TextEdit


from pydantic import BaseModel, Field


class CrossoverResponse(BaseModel):
    """Each child is a targeted edit list applied to its respective parent.

    Example: {"child_1": [{"segment_id": "message:0", "text": "..."}],
              "child_2": [{"segment_id": "message:0", "text": "..."}]}
    """

    child_1: list[TextEdit]
    child_2: list[TextEdit]


class StyleInferenceResponse(BaseModel):
    """Response model for style inference from dataset."""

    style: str


class MutationResponse(BaseModel):
    """Response model for mutation operations returning targeted text edits."""

    edits: list[TextEdit] = Field(..., description="Targeted text edits")


class PopulationResponse(BaseModel):
    """Fresh starts and variations are edits of the same immutable template."""

    prompts: list[MutationResponse]
