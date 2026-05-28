"""Scoring strategy selection for LLMJudge.

Decides whether a judge call should go through the single-pass path or
the agentic tool-call loop. Lives next to `metric.py` so the fast path
doesn't import any `agentic/` submodule — `NeverAgentic` never reaches
into the agentic package.
"""

import abc
import enum
import logging
from typing import Any, List, Literal, Optional, Sequence, Union

from . import config as llm_judge_config
from . import model_capabilities as capabilities_registry

LOGGER = logging.getLogger(__name__)


class ScoringToolStrategy(str, enum.Enum):
    ONE_SHOT = "one_shot"
    AGENTIC = "agentic"


ScoringToolStrategyMode = Literal["auto", "always", "never"]


class ScoringToolStrategySelector(abc.ABC):
    """Decides whether one call should be one-shot or agentic."""

    @abc.abstractmethod
    def select(
        self,
        *,
        trace_tool_context: Any,
        model_name: str,
        assertions: List[str],
    ) -> ScoringToolStrategy:
        raise NotImplementedError


class AlwaysAgentic(ScoringToolStrategySelector):
    def select(self, **_: Any) -> ScoringToolStrategy:
        return ScoringToolStrategy.AGENTIC


class NeverAgentic(ScoringToolStrategySelector):
    def select(self, **_: Any) -> ScoringToolStrategy:
        return ScoringToolStrategy.ONE_SHOT


# Rough fixed overhead for the LLM-judge prompt: system prompt +
# assertion list + JSON response schema. Pessimistic on purpose — better
# to over-reserve than to push a trace over the wire that won't fit.
_PROMPT_OVERHEAD_TOKENS = 1_500


# Default headroom: reserve half the context window for the model's own
# reasoning/response. Callers tuning cost vs. quality can override by
# passing `safety_factor` to `compute_budget_tokens`.
_DEFAULT_SAFETY_FACTOR = 0.5


def _capability_for(
    model_name: str,
    capabilities: Optional[Sequence[capabilities_registry.ModelCapability]] = None,
) -> capabilities_registry.ModelCapability:
    """Resolve a model name to its capability entry.

    The exact match on ``model_name_prefix`` wins; falls back to the longest
    matching prefix, so versioned ids like ``"gpt-5-nano-2025-08-07"``
    still resolve. Unknown models get ``DEFAULT_CAPABILITY``.
    """
    table = (
        capabilities
        if capabilities is not None
        else capabilities_registry.MODEL_CAPABILITIES
    )
    best: Optional[capabilities_registry.ModelCapability] = None
    best_len = -1
    for cap in table:
        prefix = cap.model_name_prefix
        if model_name == prefix:
            return cap
        if model_name.startswith(prefix) and len(prefix) > best_len:
            best = cap
            best_len = len(prefix)
    return best if best is not None else capabilities_registry.DEFAULT_CAPABILITY


def compute_budget_tokens(
    model_name: str,
    *,
    safety_factor: float = _DEFAULT_SAFETY_FACTOR,
    prompt_overhead_tokens: int = _PROMPT_OVERHEAD_TOKENS,
    capabilities: Optional[Sequence[capabilities_registry.ModelCapability]] = None,
) -> int:
    """Tokens available for the agentic-judge inline overview.

    `context_window * safety_factor - prompt_overhead_tokens`. The
    agentic overview sizer uses this to decide how rich the inline
    trace summary can be before falling back to compressed views.
    """
    if not 0.0 < safety_factor <= 1.0:
        raise ValueError("safety_factor must be in (0, 1]")
    capability = _capability_for(model_name, capabilities)
    return int(capability.context_window * safety_factor) - prompt_overhead_tokens


class HeuristicSelector(ScoringToolStrategySelector):
    """Pick a strategy from whether a trace context is available.

    Rules:
      1. No context available → ONE_SHOT (no trace to inspect anyway).
      2. Context present → AGENTIC.

    The agentic mode begins with an inline overview that already
    subsumes what one-shot would see (input + output), and additionally
    exposes tools so the model can navigate the span tree when the
    overview isn't enough. Picking AGENTIC whenever a context is
    available lets the model self-select between "answer from overview"
    and "drill in via tools," instead of pre-deciding from a size
    heuristic that becomes meaningless once context windows hit 1M+
    tokens.
    """

    def select(
        self,
        *,
        trace_tool_context: Any,
        model_name: str,
        assertions: List[str],
    ) -> ScoringToolStrategy:
        if trace_tool_context is None:
            return ScoringToolStrategy.ONE_SHOT
        return ScoringToolStrategy.AGENTIC


def make_selector(
    mode_or_selector: Union[ScoringToolStrategyMode, ScoringToolStrategySelector],
) -> ScoringToolStrategySelector:
    """Resolve a user-facing value into a selector instance.

    Accepts either the string mode (`"auto"`, `"always"`, `"never"`) or
    a pre-built selector so power users can inject their own.
    """
    if isinstance(mode_or_selector, ScoringToolStrategySelector):
        return mode_or_selector
    if mode_or_selector == "auto":
        return HeuristicSelector()
    if mode_or_selector == "always":
        return AlwaysAgentic()
    if mode_or_selector == "never":
        return NeverAgentic()
    raise ValueError(
        f"Unknown scoring strategy {mode_or_selector!r}; "
        f"expected one of 'auto', 'always', 'never' or a ScoringToolStrategySelector instance"
    )


# Re-export for callers that want the default model name without
# pulling `config` directly.
DEFAULT_MODEL_NAME = llm_judge_config.DEFAULT_MODEL_NAME
