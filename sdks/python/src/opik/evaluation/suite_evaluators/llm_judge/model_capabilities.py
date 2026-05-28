"""Registry of known-model capabilities for LLMJudge strategy selection.

Kept separate from `strategy_selector.py` because this table grows over
time as new models are released and gets touched far more often than
the selection logic itself. Editing one shouldn't pull the other into
review.

Conservative defaults: models not matched fall back to
`DEFAULT_CAPABILITY`, so unknown models keep a small window. Override
per-judge via `HeuristicSelector(model_capabilities=...)`.
"""

import dataclasses
from typing import List


@dataclasses.dataclass(frozen=True)
class ModelCapability:
    """Judge-specific view of a model's fitness for single-pass scoring.

    `model_name_prefix` is matched against the resolved model name —
    exact match wins, otherwise the longest matching prefix wins, so
    versioned ids like ``"gpt-5-nano-2025-08-07"`` resolve to the
    ``"gpt-5-nano"`` entry. `context_window` is the raw model context.
    `single_pass_quality_ok` is the judgment-call layer — even when a
    trace fits, weak models score long contexts poorly and should stay
    in the loop.
    """

    model_name_prefix: str
    context_window: int
    single_pass_quality_ok: bool


MODEL_CAPABILITIES: List[ModelCapability] = [
    ModelCapability("gpt-5", context_window=400_000, single_pass_quality_ok=True),
    ModelCapability("gpt-5-mini", context_window=400_000, single_pass_quality_ok=True),
    ModelCapability("gpt-5-nano", context_window=400_000, single_pass_quality_ok=True),
    ModelCapability("gpt-5.1", context_window=400_000, single_pass_quality_ok=True),
    ModelCapability("gpt-5.2", context_window=400_000, single_pass_quality_ok=True),
    ModelCapability("gpt-5.3", context_window=400_000, single_pass_quality_ok=True),
    ModelCapability("gpt-5.4", context_window=1_000_000, single_pass_quality_ok=True),
    ModelCapability("gpt-5.5", context_window=1_000_000, single_pass_quality_ok=True),
    ModelCapability("gpt-4o", context_window=128_000, single_pass_quality_ok=True),
    ModelCapability("gpt-4o-mini", context_window=128_000, single_pass_quality_ok=True),
    ModelCapability(
        "claude-opus-4-6", context_window=1_000_000, single_pass_quality_ok=True
    ),
    ModelCapability(
        "claude-opus-4-7", context_window=1_000_000, single_pass_quality_ok=True
    ),
    ModelCapability(
        "claude-sonnet-4-6", context_window=1_000_000, single_pass_quality_ok=True
    ),
    ModelCapability(
        "claude-haiku-4-5", context_window=200_000, single_pass_quality_ok=True
    ),
]

DEFAULT_CAPABILITY = ModelCapability(
    model_name_prefix="", context_window=32_000, single_pass_quality_ok=True
)
