"""Type definitions for the Reflective Optimizer."""

from pydantic import BaseModel

from ...api_objects import types


class FailureMode(BaseModel):
    """Model for a single failure mode identified in evaluation."""

    name: str
    description: str
    root_cause: str


class RootCauseAnalysis(BaseModel):
    """Model for root cause analysis response."""

    failure_modes: list[FailureMode]


class BatchAnalysis(BaseModel):
    """Model for a single batch analysis result."""

    batch_number: int
    start_index: int
    end_index: int
    failure_modes: list[FailureMode]


class HierarchicalRootCauseAnalysis(BaseModel):
    """Model for the final hierarchical root cause analysis."""

    total_test_cases: int
    num_batches: int
    unified_failure_modes: list[FailureMode]
    synthesis_notes: str


class ImprovedPrompt(BaseModel):
    """Model for improved prompt response."""

    reasoning: str
    messages: list[types.Message]
