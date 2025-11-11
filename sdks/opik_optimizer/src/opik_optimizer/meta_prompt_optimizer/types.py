"""Type definitions for the Meta Prompt Optimizer."""

from pydantic import BaseModel

from ..optimization_config.chat_prompt import MessageDict


class PromptCandidate(BaseModel):
    """Model for a single prompt candidate."""

    prompt: list[MessageDict]
    improvement_focus: str
    reasoning: str


class CandidatePromptsResponse(BaseModel):
    """Model for the response containing multiple prompt candidates."""

    prompts: list[PromptCandidate]


class ToolDescriptionCandidate(BaseModel):
    """Model for a single tool description candidate."""

    tool_description: str
    improvement_focus: str
    reasoning: str


class ToolDescriptionsResponse(BaseModel):
    """Model for the response containing multiple tool description candidates."""

    prompts: list[ToolDescriptionCandidate]
