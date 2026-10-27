"""Dependency-free value contracts for optimizer evidence and prompt edits."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, TYPE_CHECKING

from pydantic import BaseModel, ConfigDict, Field

if TYPE_CHECKING:
    from .chat_prompt import ChatPrompt


@dataclass(frozen=True)
class EvidenceCase:
    case_id: str
    inputs: Mapping[str, Any]
    output: Any = None
    scores: Any = None
    observations: Any = None


@dataclass(frozen=True)
class ReasoningEvidence:
    prompts: Mapping[str, ChatPrompt] | None = None
    cases: Sequence[EvidenceCase] | None = None
    sections: Mapping[str, Any] | None = None
    editable: bool = True


class TextEdit(BaseModel):
    """Replacement text for one existing string or text content block."""

    model_config = ConfigDict(extra="forbid")
    segment_id: str = Field(
        description="Exact segment_id from the editable prompt segments"
    )
    text: str = Field(
        description="Replacement text; retain all existing dataset placeholders"
    )
