"""Type definitions for Meta Prompt Optimizer."""

from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel, Field

from opik_optimizer.api_objects import chat_prompt, types


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

    name: str = Field(..., description="The name of the agent to update")
    messages: list[types.Message] = Field(
        ..., description="The updated messages for this agent"
    )
    improvement_focus: str | None = Field(
        None, description="What aspect of the agent's performance is being improved"
    )
    reasoning: str | None = Field(
        None, description="Explanation of why these changes were made"
    )


class AgentBundleCandidateResponse(BaseModel):
    """Response model for agent bundle candidate generation."""

    agents: list[AgentPromptUpdate] = Field(
        ..., description="List of agent prompt updates"
    )
    bundle_improvement_focus: str | None = Field(
        None, description="Overall focus for this bundle of improvements"
    )


class AgentBundleCandidatesResponse(BaseModel):
    """Response model for multiple agent bundle candidates."""

    candidates: list[AgentBundleCandidateResponse] = Field(
        ..., description="List of candidate bundles"
    )


@dataclass
class AgentMetadata:
    """Metadata for a single agent's prompt optimization."""

    improvement_focus: str | None = None
    """What aspect of the agent's performance is being targeted for improvement"""

    reasoning: str | None = None
    """Explanation of why the prompt changes were made"""


@dataclass
class AgentBundleCandidate:
    """Represents a single candidate bundle of agent prompts with metadata."""

    prompts: dict[str, chat_prompt.ChatPrompt]
    """Dictionary mapping agent names to their updated ChatPrompt objects"""

    metadata: dict[str, AgentMetadata]
    """Dictionary mapping agent names to their improvement metadata"""

    def get_agent_names(self) -> list[str]:
        """Get all agent names in this bundle."""
        return list(self.prompts.keys())

    def get_agent_reasoning(self, agent_name: str) -> str | None:
        """Get the reasoning for a specific agent's prompt changes."""
        agent_meta = self.metadata.get(agent_name)
        return agent_meta.reasoning if agent_meta else None

    def get_agent_improvement_focus(self, agent_name: str) -> str | None:
        """Get the improvement focus for a specific agent."""
        agent_meta = self.metadata.get(agent_name)
        return agent_meta.improvement_focus if agent_meta else None
