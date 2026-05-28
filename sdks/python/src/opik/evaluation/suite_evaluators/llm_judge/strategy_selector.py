"""Scoring strategy selection for LLMJudge.

Decides whether a judge call should go through the single-pass path or
the agentic tool-call loop. Lives next to `metric.py` so the fast path
doesn't import any `agentic/` submodule — `NeverAgentic` never reaches
into the agentic package, and `HeuristicSelector` only touches the
size-estimation helpers under `agentic/compression/`.
"""

import abc
import dataclasses
import enum
import logging
from typing import Any, Dict, List, Literal, Optional, Union

from . import config as llm_judge_config

LOGGER = logging.getLogger(__name__)


class ScoringToolStrategy(str, enum.Enum):
    ONE_SHOT = "one_shot"
    AGENTIC = "agentic"


ScoringToolStrategyMode = Literal["auto", "always", "never"]


@dataclasses.dataclass(frozen=True)
class ModelCapability:
    """Judge-specific view of a model's fitness for single-pass scoring.

    `context_window` is the raw model context. `single_pass_quality_ok`
    is the judgment call layer — even when a trace fits, weak models
    score long contexts poorly and should stay in the loop.
    """

    context_window: int
    single_pass_quality_ok: bool


# Conservative defaults. Models not listed fall back to
# `_DEFAULT_CAPABILITY` (small window, quality-not-ok), so unknown models
# stay in the agentic loop by default. Override via
# `HeuristicSelector(model_capabilities=...)`.
_MODEL_CAPABILITIES: Dict[str, ModelCapability] = {
    "gpt-5": ModelCapability(context_window=400_000, single_pass_quality_ok=True),
    "gpt-5-mini": ModelCapability(context_window=400_000, single_pass_quality_ok=True),
    "gpt-5-nano": ModelCapability(context_window=400_000, single_pass_quality_ok=True),
    "gpt-4o": ModelCapability(context_window=128_000, single_pass_quality_ok=True),
    "gpt-4o-mini": ModelCapability(context_window=128_000, single_pass_quality_ok=True),
    "claude-opus-4-7": ModelCapability(
        context_window=200_000, single_pass_quality_ok=True
    ),
    "claude-sonnet-4-6": ModelCapability(
        context_window=200_000, single_pass_quality_ok=True
    ),
    "claude-haiku-4-5": ModelCapability(
        context_window=200_000, single_pass_quality_ok=True
    ),
}

_DEFAULT_CAPABILITY = ModelCapability(context_window=8_000, single_pass_quality_ok=True)


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
# reasoning/response. Callers tuning cost vs. quality can override via
# `HeuristicSelector(safety_factor=...)` or by passing `safety_factor`
# to `compute_budget_tokens`.
_DEFAULT_SAFETY_FACTOR = 0.5


def _capability_for(
    model_name: str,
    capabilities: Optional[Dict[str, ModelCapability]] = None,
) -> ModelCapability:
    """Resolve a model name to its capability entry.

    Exact match wins; falls back to the longest matching prefix so
    versioned ids like `"gpt-5-nano-2025-08-07"` still resolve. Unknown
    models get `_DEFAULT_CAPABILITY` — small window, not single-pass-ok.
    """
    table = capabilities or _MODEL_CAPABILITIES
    if model_name in table:
        return table[model_name]
    best: Optional[ModelCapability] = None
    best_len = -1
    for key, cap in table.items():
        if model_name.startswith(key) and len(key) > best_len:
            best = cap
            best_len = len(key)
    return best if best is not None else _DEFAULT_CAPABILITY


def compute_budget_tokens(
    model_name: str,
    *,
    safety_factor: float = _DEFAULT_SAFETY_FACTOR,
    prompt_overhead_tokens: int = _PROMPT_OVERHEAD_TOKENS,
    capabilities: Optional[Dict[str, ModelCapability]] = None,
) -> int:
    """Tokens available for the trace overview / one-shot trace payload.

    `context_window * safety_factor - prompt_overhead_tokens`. The
    agentic judge uses this to size the inline overview; the heuristic
    selector uses the same formula to decide one-shot vs. agentic.
    Single source of truth for "how much of the context can we spend on
    trace data."
    """
    if not 0.0 < safety_factor <= 1.0:
        raise ValueError("safety_factor must be in (0, 1]")
    capability = _capability_for(model_name, capabilities)
    return int(capability.context_window * safety_factor) - prompt_overhead_tokens


class HeuristicSelector(ScoringToolStrategySelector):
    """Pick a strategy from trace size and model capability.

    Rules, in order:
      1. No context available → ONE_SHOT (no trace to inspect anyway).
      2. Model not flagged single-pass-capable → AGENTIC.
      3. Estimated reconstructed-trace tokens > budget → AGENTIC.
      4. Otherwise → ONE_SHOT.

    `safety_factor` reserves headroom for the model's own reasoning /
    response tokens. A value of 0.5 means "use at most half of the
    context window for input."
    """

    def __init__(
        self,
        safety_factor: float = _DEFAULT_SAFETY_FACTOR,
        prompt_overhead_tokens: int = _PROMPT_OVERHEAD_TOKENS,
        model_capabilities: Optional[Dict[str, ModelCapability]] = None,
    ) -> None:
        if not 0.0 < safety_factor <= 1.0:
            raise ValueError("safety_factor must be in (0, 1]")
        self._safety_factor = safety_factor
        self._prompt_overhead_tokens = prompt_overhead_tokens
        self._capabilities = model_capabilities or _MODEL_CAPABILITIES

    def select(
        self,
        *,
        trace_tool_context: Any,
        model_name: str,
        assertions: List[str],
    ) -> ScoringToolStrategy:
        if trace_tool_context is None:
            return ScoringToolStrategy.ONE_SHOT

        capability = _capability_for(model_name, self._capabilities)
        if not capability.single_pass_quality_ok:
            LOGGER.debug(
                "HeuristicSelector: model %s not flagged single-pass-capable; using agentic",
                model_name,
            )
            return ScoringToolStrategy.AGENTIC

        budget = compute_budget_tokens(
            model_name,
            safety_factor=self._safety_factor,
            prompt_overhead_tokens=self._prompt_overhead_tokens,
            capabilities=self._capabilities,
        )
        size = _estimate_context_tokens(trace_tool_context)
        if size > budget:
            LOGGER.debug(
                "HeuristicSelector: trace size %d > budget %d for model %s; using agentic",
                size,
                budget,
                model_name,
            )
            return ScoringToolStrategy.AGENTIC

        LOGGER.debug(
            "HeuristicSelector: trace size %d <= budget %d for model %s; using one-shot",
            size,
            budget,
            model_name,
        )
        return ScoringToolStrategy.ONE_SHOT

    def _capability_for(self, model_name: str) -> ModelCapability:
        # Preserved as a thin shim for backwards compatibility with the
        # one existing test that patches via the private helper.
        return _capability_for(model_name, self._capabilities)


def _estimate_context_tokens(trace_tool_context: Any) -> int:
    """Estimate token count of the composite trace+spans payload.

    Reuses the agentic-compression sizing helper so the SDK and the
    backend agree on the boundary. Import is local: this module must
    not pull `agentic/` into the one-shot path during construction.
    """
    from opik.evaluation.suite_evaluators.agentic import entity_ref
    from opik.evaluation.suite_evaluators.agentic.compression import tokens

    ref = entity_ref.EntityRef(entity_ref.EntityType.TRACE, trace_tool_context.trace.id)
    payload = trace_tool_context.get_cached(ref)
    if payload is None:
        return 0
    return tokens.estimate_tokens(payload)


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
