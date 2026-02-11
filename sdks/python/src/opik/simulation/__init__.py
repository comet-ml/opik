"""Multi-turn simulation module for Opik."""

from .simulated_user import SimulatedUser
from .simulator import run_simulation
from .episode import (
    EpisodeAssertion,
    EpisodeScore,
    EpisodeBudgetMetric,
    EpisodeBudgets,
    EpisodeResult,
    build_trajectory_summary,
    make_max_turns_assertion,
    make_tool_call_budget,
)

__all__ = [
    "SimulatedUser",
    "run_simulation",
    "EpisodeAssertion",
    "EpisodeScore",
    "EpisodeBudgetMetric",
    "EpisodeBudgets",
    "EpisodeResult",
    "build_trajectory_summary",
    "make_max_turns_assertion",
    "make_tool_call_budget",
]
