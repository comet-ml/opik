"""Episode contracts and helpers for multi-turn simulation testing."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Sequence, Literal

import pydantic


class EpisodeAssertion(pydantic.BaseModel):
    name: str
    passed: bool
    reason: Optional[str] = None
    severity: Literal["error", "warning"] = "error"


class EpisodeScore(pydantic.BaseModel):
    name: str
    value: float
    reason: Optional[str] = None


class EpisodeBudgetMetric(pydantic.BaseModel):
    used: float
    limit: float
    unit: Optional[str] = None

    @property
    def passed(self) -> bool:
        return self.used <= self.limit


class EpisodeBudgets(pydantic.BaseModel):
    max_turns: Optional[EpisodeBudgetMetric] = None
    tool_calls: Optional[EpisodeBudgetMetric] = None
    latency_ms: Optional[EpisodeBudgetMetric] = None
    token_usage: Optional[EpisodeBudgetMetric] = None
    custom: Dict[str, EpisodeBudgetMetric] = pydantic.Field(default_factory=dict)

    def all_metrics(self) -> Dict[str, EpisodeBudgetMetric]:
        result: Dict[str, EpisodeBudgetMetric] = {}
        if self.max_turns is not None:
            result["max_turns"] = self.max_turns
        if self.tool_calls is not None:
            result["tool_calls"] = self.tool_calls
        if self.latency_ms is not None:
            result["latency_ms"] = self.latency_ms
        if self.token_usage is not None:
            result["token_usage"] = self.token_usage
        result.update(self.custom)
        return result


class EpisodeResult(pydantic.BaseModel):
    scenario_id: str
    thread_id: Optional[str] = None
    assertions: List[EpisodeAssertion] = pydantic.Field(default_factory=list)
    scores: List[EpisodeScore] = pydantic.Field(default_factory=list)
    budgets: Optional[EpisodeBudgets] = None
    trajectory_summary: Dict[str, Any] = pydantic.Field(default_factory=dict)
    metadata: Dict[str, Any] = pydantic.Field(default_factory=dict)

    @classmethod
    def from_any(
        cls,
        value: Any,
        *,
        fallback_scenario_id: str,
        fallback_thread_id: Optional[str] = None,
    ) -> Optional["EpisodeResult"]:
        if value is None:
            return None
        if isinstance(value, EpisodeResult):
            return value
        if isinstance(value, dict):
            payload = {**value}
            payload.setdefault("scenario_id", fallback_scenario_id)
            if fallback_thread_id is not None:
                payload.setdefault("thread_id", fallback_thread_id)
            return cls.model_validate(payload)
        return None

    def is_passing(self) -> bool:
        hard_assertions = [
            assertion for assertion in self.assertions if assertion.severity == "error"
        ]
        if any(not assertion.passed for assertion in hard_assertions):
            return False

        if self.budgets is not None:
            if any(not metric.passed for metric in self.budgets.all_metrics().values()):
                return False

        return True


def build_trajectory_summary(trajectory: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """Create a lightweight trajectory summary suitable for CI artifacts."""
    tool_calls = 0
    for step in trajectory:
        action = step.get("action")
        if isinstance(action, str) and action.strip():
            tool_calls += 1

    return {
        "steps_count": len(trajectory),
        "tool_calls_count": tool_calls,
    }


def make_max_turns_assertion(
    conversation_history: Sequence[Dict[str, Any]],
    max_turns: int,
    *,
    name: str = "max_turns",
) -> EpisodeAssertion:
    # Conversation history alternates user/assistant; each turn contributes two messages.
    turns_used = len(conversation_history) // 2
    passed = turns_used <= max_turns
    reason = f"turns_used={turns_used}, limit={max_turns}"
    return EpisodeAssertion(name=name, passed=passed, reason=reason)


def make_tool_call_budget(
    trajectory: Sequence[Dict[str, Any]], limit: int
) -> EpisodeBudgetMetric:
    used = float(build_trajectory_summary(trajectory)["tool_calls_count"])
    return EpisodeBudgetMetric(used=used, limit=float(limit), unit="count")
