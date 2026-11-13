"""Type definitions for Meta Prompt Optimizer."""

from ...utils.prompt_editing import TextEdit

from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel, Field, model_validator

from opik_optimizer.api_objects import chat_prompt


@dataclass
class HallOfFameEntry:
    """Represents a high-performing prompt in the hall of fame"""

    prompt_messages: list[dict[str, str]]
    score: float
    trial_number: int
    improvement_over_baseline: float
    metric_name: str
    extracted_patterns: list[str] | None = None  # Filled during pattern extraction
    metadata: dict[str, Any] = field(default_factory=dict)


class AgentPromptUpdate(BaseModel):
    """Represents an update to a single agent's prompt."""

    # Used by AgentBundleCandidatesResponse (see ops/candidate_bundle_ops.py).

    name: str = Field(..., description="The name of the agent to update")
    edits: list[TextEdit] = Field(..., description="Targeted text edits for this agent")
    improvement_focus: str | None = Field(
        None, description="What aspect of the agent's performance is being improved"
    )
    reasoning: str | None = Field(
        None, description="Explanation of why these changes were made"
    )


class AgentBundleCandidateResponse(BaseModel):
    """Response model for agent bundle candidate generation."""

    # Returned as part of AgentBundleCandidatesResponse.

    agents: list[AgentPromptUpdate] = Field(
        ..., description="List of agent prompt updates"
    )
    bundle_improvement_focus: str | None = Field(
        None, description="Overall focus for this bundle of improvements"
    )


class AgentBundleCandidatesResponse(BaseModel):
    """Response model for multiple agent bundle candidates."""

    # Passed as response_model to LLM calls in ops/candidate_bundle_ops.py.

    candidates: list[AgentBundleCandidateResponse] = Field(
        ..., description="List of candidate bundles"
    )


class PromptCandidateResponse(BaseModel):
    """Response model for single-prompt candidate generation."""

    edits: list[TextEdit] = Field(
        ..., description="Targeted text edits for this candidate"
    )
    improvement_focus: str | None = Field(
        None, description="What aspect of the prompt is being improved"
    )
    reasoning: str | None = Field(
        None, description="Explanation of why these changes were made"
    )


class PromptCandidatesResponse(BaseModel):
    """Response model for candidate prompt lists."""

    prompts: list[PromptCandidateResponse] = Field(
        ..., description="List of candidate prompts"
    )

    @model_validator(mode="before")
    @classmethod
    def _wrap_list(cls, value: Any) -> Any:
        if isinstance(value, list):
            return {"prompts": value}
        return value


class PatternItem(BaseModel):
    """Pattern extraction item with optional example."""

    pattern: str
    example: str | None = None


class PatternExtractionResponse(BaseModel):
    """Response model for Hall-of-Fame pattern extraction."""

    patterns: list[PatternItem | str] = Field(
        default_factory=list, description="Extracted prompt patterns"
    )

    @model_validator(mode="before")
    @classmethod
    def _wrap_patterns(cls, value: Any) -> Any:
        if isinstance(value, list):
            return {"patterns": value}
        return value


@dataclass
class AgentMetadata:
    """Metadata for a single agent's prompt optimization."""

    # Stored on AgentBundleCandidate entries built in ops/candidate_bundle_ops.py.

    improvement_focus: str | None = None
    """What aspect of the agent's performance is being targeted for improvement"""

    reasoning: str | None = None
    """Explanation of why the prompt changes were made"""


@dataclass
class AgentBundleCandidate:
    """Represents a single candidate bundle of agent prompts with metadata."""

    # Constructed in ops/candidate_bundle_ops.py and used by candidate_ops.generate_round_candidates.

    prompts: dict[str, chat_prompt.ChatPrompt]
    """Dictionary mapping agent names to their updated ChatPrompt objects"""

    metadata: dict[str, AgentMetadata]
    """Dictionary mapping agent names to their improvement metadata"""
