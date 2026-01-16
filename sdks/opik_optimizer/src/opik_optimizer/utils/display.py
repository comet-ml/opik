"""Display helpers for formatting prompts and numbers."""

from __future__ import annotations

from typing import Any, cast

from ..api_objects import chat_prompt
from .reporting import format_prompt_snippet


def format_float(value: Any, digits: int = 6) -> str:
    """Format float values with specified precision."""
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def format_prompt_for_plaintext(
    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
) -> str:
    """Format a prompt (single or dict) for plain text display."""
    if isinstance(prompt, dict):
        prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
        parts: list[str] = []
        for key, chat_p in prompt_dict.items():
            parts.append(f"[{key}]")
            for msg in chat_p.get_messages():
                role = msg.get("role", "unknown")
                content = msg.get("content", "")
                if isinstance(content, str):
                    snippet = format_prompt_snippet(content, max_length=150)
                else:
                    snippet = "[multimodal content]"
                parts.append(f"  {role}: {snippet}")
        return "\n".join(parts)

    parts = []
    for msg in prompt.get_messages():
        role = msg.get("role", "unknown")
        content = msg.get("content", "")
        if isinstance(content, str):
            snippet = format_prompt_snippet(content, max_length=150)
        else:
            snippet = "[multimodal content]"
        parts.append(f"  {role}: {snippet}")
    return "\n".join(parts)


def build_plaintext_summary(
    *,
    optimizer: str,
    model_name: str,
    metric_name: str,
    initial_score: float | None,
    final_score: float,
    improvement_str: str,
    trials_completed: int | None,
    rounds_ran: int,
    optimized_params: dict[str, Any],
    parameter_importance: dict[str, Any],
    search_ranges: dict[str, Any],
    parameter_precision: int,
    final_prompt_display: str,
) -> str:
    """Construct the plain-text summary for an optimization result."""
    separator = "=" * 80
    initial_score_str = (
        f"{initial_score:.4f}" if isinstance(initial_score, (int, float)) else "N/A"
    )
    final_score_str = f"{final_score:.4f}"

    lines: list[str] = [
        f"\n{separator}",
        "OPTIMIZATION COMPLETE",
        f"{separator}",
        f"Optimizer:        {optimizer}",
        f"Model Used:       {model_name}",
        f"Metric Evaluated: {metric_name}",
        f"Initial Score:    {initial_score_str}",
        f"Final Best Score: {final_score_str}",
        f"Total Improvement:{improvement_str.rjust(max(0, 18 - len('Total Improvement:')))}",
        f"Trials Completed: {trials_completed if isinstance(trials_completed, int) else rounds_ran}",
    ]

    def _format_range(desc: dict[str, Any]) -> str:
        if "min" in desc and "max" in desc:
            step_str = (
                f", step={format_float(desc['step'], parameter_precision)}"
                if desc.get("step") is not None
                else ""
            )
            return f"[{format_float(desc['min'], parameter_precision)}, {format_float(desc['max'], parameter_precision)}{step_str}]"
        if desc.get("choices"):
            return ",".join(map(str, desc["choices"]))
        return str(desc)

    if optimized_params:
        lines.append("Parameter Summary:")
        rows = []
        for name in sorted(optimized_params):
            contribution = parameter_importance.get(name)
            stage_ranges = []
            for stage, params in search_ranges.items():
                if name in params:
                    stage_ranges.append(f"{stage}: {_format_range(params[name])}")
            joined_ranges = "\n".join(stage_ranges) if stage_ranges else "N/A"
            rows.append(
                {
                    "parameter": name,
                    "value": optimized_params[name],
                    "contribution": contribution,
                    "ranges": joined_ranges,
                }
            )
        total_improvement = None
        if isinstance(initial_score, (int, float)) and isinstance(
            final_score, (int, float)
        ):
            total_improvement = (
                (final_score - initial_score) / abs(initial_score)
                if initial_score != 0
                else final_score
            )
        for row in rows:
            value_str = format_float(row["value"], parameter_precision)
            contrib_val = row["contribution"]
            if contrib_val is not None:
                contrib_percent = contrib_val * 100
                gain_str = ""
                if total_improvement is not None:
                    gain_value = contrib_val * total_improvement * 100
                    gain_str = f" ({gain_value:+.2f}%)"
                contrib_str = f"{contrib_percent:.1f}%{gain_str}"
            else:
                contrib_str = "N/A"
            lines.append(
                f"- {row['parameter']}: value={value_str}, contribution={contrib_str}, ranges=\n  {row['ranges']}"
            )

    lines.extend(
        [
            "\nFINAL OPTIMIZED PROMPT / STRUCTURE:",
            "--------------------------------------------------------------------------------",
            f"{final_prompt_display}",
            "--------------------------------------------------------------------------------",
            f"{separator}",
        ]
    )
    return "\n".join(lines)
