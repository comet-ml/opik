"""Shared helpers for meta-prompt optimizer unit tests."""

from __future__ import annotations

from typing import Any

from opik_optimizer.algorithms.meta_prompt_optimizer.ops.halloffame_ops import HallOfFameEntry


def make_system_messages(content: str) -> list[dict[str, str]]:
    return [{"role": "system", "content": content}]


def make_prompt_json(prompts: list[list[dict[str, Any]]]) -> dict[str, Any]:
    """Build the serialized prompt candidates payload shape used by meta-prompt ops."""
    return {"prompts": [{"prompt": prompt_messages} for prompt_messages in prompts]}


def make_system_prompt_json(contents: list[str]) -> dict[str, Any]:
    """Convenience builder for simple system-only prompt candidates."""
    return make_prompt_json([make_system_messages(content) for content in contents])


def make_hof_entry(
    *,
    score: float,
    trial: int = 1,
    metric_name: str = "accuracy",
    baseline_score: float = 0.5,
    prompt_messages: list[dict[str, Any]] | None = None,
    extracted_patterns: list[str] | None = None,
    metadata: dict[str, Any] | None = None,
) -> HallOfFameEntry:
    return HallOfFameEntry(
        prompt_messages=prompt_messages or make_system_messages(f"Score {score}"),
        score=score,
        trial_number=trial,
        improvement_over_baseline=score - baseline_score,
        metric_name=metric_name,
        extracted_patterns=extracted_patterns,
        metadata=metadata or {},
    )

