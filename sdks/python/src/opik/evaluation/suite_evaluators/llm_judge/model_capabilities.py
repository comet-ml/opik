"""Registry of known-model capabilities for LLMJudge strategy selection.

Kept separate from `strategy_selector.py` because this table grows over
time as new models are released and gets touched far more often than
the selection logic itself. Editing one shouldn't pull the other into
review.

Conservative defaults: models not matched fall back to
`DEFAULT_CAPABILITY`, so unknown models stay on the one-shot path in
`auto` mode (cheaper, lower variance). Callers who need a different
table can pass `capabilities=` to `compute_budget_tokens`.
"""

import dataclasses
from typing import List


@dataclasses.dataclass(frozen=True)
class ModelCapability:
    """Judge-specific view of a model used by the strategy selector.

    `model_name_prefix` is matched against the resolved model name —
    exact match wins, otherwise the longest matching prefix wins, so
    versioned ids like ``"gpt-5-nano-2025-08-07"`` resolve to the
    ``"gpt-5-nano"`` entry.

    `context_window` is the raw model context, consumed by the agentic
    overview sizer via `compute_budget_tokens` to decide how rich the
    inline trace summary can be.

    `agentic_in_auto` gates whether the `auto` scoring strategy routes
    this model to the agentic tool loop when a trace context is
    available. Models that engage with tool affordances cleanly get
    `True` — they'll answer from the inline overview when the trace is
    trivial and reach for `read` / `scan` / `search` when it isn't.
    Models known to ignore tools (e.g. gpt-5-nano) or that don't
    benefit from drill-in get `False` — `auto` keeps them on the
    cheaper one-shot path. `scoring_tool_strategy="always"` overrides
    this flag and forces agentic regardless.
    """

    model_name_prefix: str
    context_window: int
    agentic_in_auto: bool


MODEL_CAPABILITIES: List[ModelCapability] = [
    ModelCapability("gpt-5.5", context_window=1_000_000, agentic_in_auto=True),
    ModelCapability("gpt-5.4", context_window=1_000_000, agentic_in_auto=True),
    ModelCapability("gpt-5.3", context_window=400_000, agentic_in_auto=True),
    ModelCapability("gpt-5.2", context_window=400_000, agentic_in_auto=True),
    ModelCapability("gpt-5.1", context_window=400_000, agentic_in_auto=True),
    ModelCapability("gpt-5", context_window=400_000, agentic_in_auto=True),
    ModelCapability("gpt-5-mini", context_window=400_000, agentic_in_auto=True),
    # Nano-class models tend to skip tool calls per backend
    # `SupportedJudgeProvider.java`; keep them on one-shot under `auto`.
    ModelCapability("gpt-5-nano", context_window=400_000, agentic_in_auto=False),
    ModelCapability("gpt-4o", context_window=128_000, agentic_in_auto=True),
    ModelCapability("gpt-4o-mini", context_window=128_000, agentic_in_auto=True),
    ModelCapability("claude-opus-4-7", context_window=1_000_000, agentic_in_auto=True),
    ModelCapability("claude-opus-4-6", context_window=1_000_000, agentic_in_auto=True),
    ModelCapability(
        "claude-sonnet-4-6", context_window=1_000_000, agentic_in_auto=True
    ),
    ModelCapability("claude-haiku-4-5", context_window=200_000, agentic_in_auto=True),
]

# Unknown models default to one-shot under `auto`: we haven't profiled
# their tool-use behavior, and the one-shot path is the safer default.
DEFAULT_CAPABILITY = ModelCapability(
    model_name_prefix="", context_window=16_000, agentic_in_auto=False
)
