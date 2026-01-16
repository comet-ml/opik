"""Display helpers for formatting prompts and numbers."""

from __future__ import annotations

from typing import Any, cast

import rich.box
import rich.console
import rich.panel
import rich.table
import rich.text

from ..api_objects import chat_prompt
from .reporting import _format_message_content, get_link_text


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


def render_rich_result(result: Any) -> rich.panel.Panel:
    """Create a rich Panel for the final optimization result."""
    model_name = result.details.get("model", "[dim]N/A[/dim]")
    trials_completed = result.details.get("trials_completed")
    rounds_ran = (
        result._rounds_completed()
        if hasattr(result, "_rounds_completed")
        else (
            result.details.get("rounds_completed")
            if isinstance(result.details.get("rounds_completed"), int)
            else len(result.history)
        )
    )
    improvement_str = result._calculate_improvement_str()
    initial_score = result.initial_score
    initial_score_str = (
        f"{initial_score:.4f}"
        if isinstance(initial_score, (int, float))
        else "[dim]N/A[/dim]"
    )
    final_score_str = f"{result.score:.4f}"

    table = rich.table.Table.grid(padding=(0, 1))
    table.add_column(style="dim")
    table.add_column()

    table.add_row(
        "Optimizer:",
        f"[bold]{result.optimizer}[/bold]",
    )
    table.add_row("Model Used:", f"{model_name}")
    table.add_row("Metric Evaluated:", f"[bold]{result.metric_name}[/bold]")
    table.add_row("Initial Score:", initial_score_str)
    table.add_row("Final Best Score:", f"[bold cyan]{final_score_str}[/bold cyan]")
    table.add_row("Total Improvement:", improvement_str)
    display_trials = (
        str(trials_completed) if isinstance(trials_completed, int) else str(rounds_ran)
    )
    table.add_row("Trials Completed:", display_trials)
    table.add_row(
        "Optimization run link:",
        get_link_text(
            pre_text="",
            link_text="Open in Opik Dashboard",
            dataset_id=result.dataset_id,
            optimization_id=result.optimization_id,
        ),
    )

    optimized_params = result.details.get("optimized_parameters") or {}
    parameter_importance = result.details.get("parameter_importance") or {}
    search_ranges = result.details.get("search_ranges") or {}
    precision = result.details.get("parameter_precision", 6)

    panel_title = "[bold]Final Optimized Prompt[/bold]"
    try:
        chat_group_items: list[rich.console.RenderableType] = []
        if isinstance(result.prompt, dict):
            prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], result.prompt)
            for key, chat_p in prompt_dict.items():
                key_header = rich.text.Text(f"[{key}]", style="bold yellow")
                chat_group_items.append(key_header)
                chat_group_items.append(rich.text.Text("---", style="dim"))
                for msg in chat_p.get_messages():
                    role = msg.get("role", "unknown")
                    content = msg.get("content", "")
                    role_style = (
                        "bold green"
                        if role == "user"
                        else (
                            "bold blue"
                            if role == "assistant"
                            else ("bold magenta" if role == "system" else "")
                        )
                    )
                    formatted_content = _format_message_content(content)
                    role_text = rich.text.Text(
                        f"{role.capitalize()}:", style=role_style
                    )
                    chat_group_items.append(
                        rich.console.Group(role_text, formatted_content)
                    )
                    chat_group_items.append(rich.text.Text("---", style="dim"))
                chat_group_items.append(rich.text.Text(""))
        else:
            for msg in result.prompt.get_messages():
                role = msg.get("role", "unknown")
                content = msg.get("content", "")
                role_style = (
                    "bold green"
                    if role == "user"
                    else (
                        "bold blue"
                        if role == "assistant"
                        else ("bold magenta" if role == "system" else "")
                    )
                )
                formatted_content = _format_message_content(content)
                role_text = rich.text.Text(f"{role.capitalize()}:", style=role_style)
                chat_group_items.append(
                    rich.console.Group(role_text, formatted_content)
                )
                chat_group_items.append(rich.text.Text("---", style="dim"))

        prompt_renderable: rich.console.RenderableType = rich.console.Group(
            *chat_group_items
        )
    except Exception:
        prompt_renderable = rich.text.Text(str(result.prompt or ""), overflow="fold")
        panel_title = "[bold]Final Optimized Prompt (Instruction - fallback)[/bold]"

    prompt_panel = rich.panel.Panel(
        prompt_renderable, title=panel_title, border_style="blue", padding=(1, 2)
    )

    renderables: list[rich.console.RenderableType] = [table, "\n"]

    if optimized_params:
        summary_table = rich.table.Table(
            title="Parameter Summary", show_header=True, title_style="bold"
        )
        summary_table.add_column("Parameter", justify="left", style="cyan")
        summary_table.add_column("Value", justify="left")
        summary_table.add_column("Importance", justify="left", style="magenta")
        summary_table.add_column("Gain", justify="left", style="dim")
        summary_table.add_column("Ranges", justify="left")

        stage_order = [
            record.get("stage")
            for record in result.details.get("search_stages", [])
            if record.get("stage") in search_ranges
        ]
        if not stage_order:
            stage_order = sorted(search_ranges)

        def _format_range(desc: dict[str, Any]) -> str:
            if "min" in desc and "max" in desc:
                step_str = (
                    f", step={format_float(desc['step'], precision)}"
                    if desc.get("step") is not None
                    else ""
                )
                return f"[{format_float(desc['min'], precision)}, {format_float(desc['max'], precision)}{step_str}]"
            if desc.get("choices"):
                return ",".join(map(str, desc["choices"]))
            return str(desc)

        total_improvement = None
        if isinstance(result.initial_score, (int, float)) and isinstance(
            result.score, (int, float)
        ):
            if result.initial_score != 0:
                total_improvement = (result.score - result.initial_score) / abs(
                    result.initial_score
                )
            else:
                total_improvement = result.score

        for name in sorted(optimized_params):
            value_str = format_float(optimized_params[name], precision)
            contrib_val = parameter_importance.get(name)
            if contrib_val is not None:
                contrib_str = f"{contrib_val:.1%}"
                gain_str = (
                    f"{contrib_val * total_improvement:+.2%}"
                    if total_improvement is not None
                    else "N/A"
                )
            else:
                contrib_str = "N/A"
                gain_str = "N/A"
            ranges_parts = []
            for stage in stage_order:
                params = search_ranges.get(stage) or {}
                if name in params:
                    ranges_parts.append(f"{stage}: {_format_range(params[name])}")
            if not ranges_parts:
                for stage, params in search_ranges.items():
                    if name in params:
                        ranges_parts.append(f"{stage}: {_format_range(params[name])}")

            summary_table.add_row(
                name,
                value_str,
                contrib_str,
                gain_str,
                "\n".join(ranges_parts) if ranges_parts else "N/A",
            )

        renderables.extend([summary_table, "\n"])

    renderables.append(prompt_panel)

    content_group = rich.console.Group(*renderables)

    return rich.panel.Panel(
        content_group,
        title="[bold yellow]Optimization Complete[/bold yellow]",
        border_style="yellow",
        box=rich.box.DOUBLE_EDGE,
        padding=1,
    )


def format_prompt_snippet(text: str, max_length: int = 100) -> str:
    """
    Normalize whitespace in a prompt snippet and truncate it for compact display.

    Args:
        text: Raw text to summarize.
        max_length: Maximum characters to keep before adding an ellipsis.

    Returns:
        str: Condensed snippet safe for inline logging.
    """
    normalized = text.strip()
    normalized = " ".join(normalized.split())
    if len(normalized) > max_length:
        return normalized[:max_length] + "…"
    return normalized
