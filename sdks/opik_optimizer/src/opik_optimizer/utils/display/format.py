"""Pure formatting helpers for display output."""

from __future__ import annotations

from typing import Any, cast
import math
import json

from ...api_objects import chat_prompt
from ..candidate_selection import DEFAULT_SELECTION_POLICY


def format_float(value: Any, digits: int = 6) -> str:
    """Format float values with specified precision."""
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def format_prompt(prompt: str, **kwargs: Any) -> str:
    """
    Format a prompt string with the given keyword arguments.

    Args:
        prompt: The prompt string to format
        **kwargs: Keyword arguments to format into the prompt

    Returns:
        str: The formatted prompt string

    Raises:
        ValueError: If any required keys are missing from kwargs
    """
    try:
        return prompt.format(**kwargs)
    except KeyError as e:
        raise ValueError(f"Missing required key in prompt: {e}")


def format_prompt_snippet(text: str, max_length: int = 100) -> str:
    """
    Normalize whitespace in a prompt snippet and truncate it for compact display.

    Args:
        text: Raw text to summarize.
        max_length: Maximum length of the returned snippet.
    """
    normalized = " ".join(text.split())
    if len(normalized) <= max_length:
        return normalized
    return normalized[: max_length - 3] + "..."


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

    single_parts: list[str] = []
    for msg in prompt.get_messages():
        role = msg.get("role", "unknown")
        content = msg.get("content", "")
        if isinstance(content, str):
            snippet = format_prompt_snippet(content, max_length=150)
        else:
            snippet = "[multimodal content]"
        single_parts.append(f"  {role}: {snippet}")
    return "\n".join(single_parts)


def format_prompt_messages(
    messages: list[dict[str, Any]],
    *,
    pretty: bool = True,
) -> str:
    """Format chat messages for history/context rendering."""
    if pretty:
        lines: list[str] = []
        for msg in messages:
            role = msg.get("role", "unknown")
            msg_content = msg.get("content", "")
            lines.append("  [" + role.upper() + "]: " + msg_content)
        return "\n".join(lines)
    return json.dumps(messages, indent=2)


def format_prompt_payload(payload: Any, *, pretty: bool = True) -> str:
    """Format prompt payloads that may include tools or bundled prompts."""
    if isinstance(payload, list):
        return format_prompt_messages(payload, pretty=pretty)
    if isinstance(payload, dict):
        if "messages" in payload and isinstance(payload.get("messages"), list):
            text = format_prompt_messages(payload["messages"], pretty=pretty)
            tools = payload.get("tools")
            if tools:
                if isinstance(tools, list) and tools and isinstance(tools[0], dict):
                    lines = [f"- {format_tool_summary(tool)}" for tool in tools]
                    tool_line = "\n  ".join(lines)
                    text = f"{text}\n  [TOOLS]:\n  {tool_line}"
                else:
                    tool_line = ", ".join(map(str, tools))
                    text = f"{text}\n  [TOOLS]: {tool_line}"
            return text
        parts: list[str] = []
        for name, value in payload.items():
            formatted = format_prompt_payload(value, pretty=pretty)
            parts.append(f"{name}:\n{formatted}")
        return "\n".join(parts)
    return str(payload)


def redact_prompt_payload(prompt_or_payload: Any) -> Any:
    """Summarize prompt payloads for safe display/history."""
    if isinstance(prompt_or_payload, dict):
        summary: dict[str, Any] = {}
        for name, prompt in prompt_or_payload.items():
            if hasattr(prompt, "get_messages"):
                messages = prompt.get_messages()
                summary[name] = {
                    "message_roles": [msg.get("role") for msg in messages],
                    "message_count": len(messages),
                    "has_tools": bool(getattr(prompt, "tools", None)),
                }
            else:
                summary[name] = {"type": type(prompt).__name__}
        return summary
    if hasattr(prompt_or_payload, "get_messages"):
        messages = prompt_or_payload.get_messages()
        return {
            "message_roles": [msg.get("role") for msg in messages],
            "message_count": len(messages),
            "has_tools": bool(getattr(prompt_or_payload, "tools", None)),
        }
    return {"type": type(prompt_or_payload).__name__}


def safe_percentage_change(current: float, baseline: float) -> tuple[float, bool]:
    """
    Calculate percentage change safely, handling division by zero.

    Args:
        current: Current value.
        baseline: Baseline value to compare against.

    Returns:
        Tuple of (percentage_change, has_percentage) where:
        - percentage_change: The percentage change if calculable, otherwise 0
        - has_percentage: True if percentage was calculated, False if baseline was zero
    """
    if baseline == 0:
        return 0.0, False
    return ((current - baseline) / baseline), True


def format_score_progress(score: float, best_score: float | None) -> tuple[str, str]:
    """Return a formatted score string and a suggested style."""
    if not math.isfinite(score):
        return "non-finite score", "yellow"

    if isinstance(best_score, (int, float)) and math.isfinite(best_score):
        delta = score - best_score
        if abs(delta) < 1e-12:
            return f"{score:.4f} (no improvement)", "yellow"

        perc_change, has_percentage = safe_percentage_change(score, best_score)
        if delta > 0:
            if has_percentage:
                return (
                    f"{score:.4f} (improved {delta:+.4f}, {perc_change:+.2%} vs best)",
                    "green",
                )
            return f"{score:.4f} (improved {delta:+.4f} vs best)", "green"

        if has_percentage:
            return (
                f"{score:.4f} (worse {delta:.4f}, {perc_change:.2%} vs best)",
                "red",
            )
        return f"{score:.4f} (worse {delta:.4f} vs best)", "red"

    return f"{score:.4f}", "green"


def summarize_selection_policy(
    prompts: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
) -> str:
    """Return a compact n/policy summary for prompt evaluation reporting."""
    prompts_list = list(prompts.values()) if isinstance(prompts, dict) else [prompts]
    summaries: list[str] = []
    for prompt in prompts_list:
        model_parameters = prompt.model_kwargs or {}
        n_value = model_parameters.get("n", 1) or 1
        try:
            n_value = int(n_value)
        except (TypeError, ValueError):
            n_value = 1
        policy = str(
            model_parameters.get("selection_policy", DEFAULT_SELECTION_POLICY)
            or DEFAULT_SELECTION_POLICY
        ).lower()
        summaries.append(f"n={n_value} policy={policy}")

    unique = sorted(set(summaries))
    if len(unique) == 1:
        return unique[0]
    return "mixed (" + ", ".join(unique) + ")"


def format_tool_summary(tool: dict[str, Any]) -> str:
    """Format a concise tool summary showing only name and description."""
    function_block = tool.get("function", {})
    function_name = function_block.get("name") or tool.get("name", "unknown_tool")
    description = function_block.get("description") or tool.get("description")
    if not description:
        description = "No description"

    display_name = function_name
    mcp_block = tool.get("mcp")
    if isinstance(mcp_block, dict):
        server_label = mcp_block.get("server_label")
        tool_block = mcp_block.get("tool")
        tool_name = None
        if isinstance(tool_block, dict):
            tool_name = tool_block.get("name")
        tool_name = tool_name or mcp_block.get("name")
        if server_label and tool_name:
            display_name = f"{server_label}.{tool_name}"
            if function_name and function_name != tool_name:
                display_name = f"{display_name} (function {function_name})"
        elif server_label and function_name:
            display_name = f"{server_label}.{function_name}"

    return f"{display_name}: {description}"


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
