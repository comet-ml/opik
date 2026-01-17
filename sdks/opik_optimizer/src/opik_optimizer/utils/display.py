"""Display helpers for formatting prompts and numbers."""

from __future__ import annotations

import json
from typing import Any, cast
from collections.abc import Iterator
from contextlib import contextmanager

import rich.box
import rich.console
import rich.panel
import rich.table
import rich.text

from ..api_objects import chat_prompt
from .candidate_selection import DEFAULT_SELECTION_POLICY
from .core import get_optimization_run_url_by_id
from .multimodal import format_message_content
from .reporting import convert_tqdm_to_rich, get_console, suppress_opik_logs


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


def safe_percentage_change(current: float, baseline: float) -> tuple[float, bool]:
    """
    Calculate percentage change safely, handling division by zero.

    Args:
        current: Current value
        baseline: Baseline value to compare against

    Returns:
        Tuple of (percentage_change, has_percentage) where:
        - percentage_change: The percentage change if calculable, otherwise 0
        - has_percentage: True if percentage was calculated, False if baseline was zero
    """
    if baseline == 0:
        return 0.0, False
    return ((current - baseline) / baseline), True


def summarize_selection_policy(
    prompts: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
) -> str:
    """Return a compact n/policy summary for prompt evaluation reporting."""
    prompts_list = list(prompts.values()) if isinstance(prompts, dict) else [prompts]
    summaries = []
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


def _format_message_content(content: str | list[dict[str, Any]]) -> rich.text.Text:
    return format_message_content(content)


def display_messages(messages: list[dict[str, Any]], prefix: str = "") -> None:
    """
    Display messages using Rich panels, supporting both string and multimodal content.

    Args:
        messages: List of message dictionaries to display.
        prefix: Optional prefix to add to each line of output.
    """
    for msg in messages:
        content: str | list[dict[str, Any]] = msg.get("content", "")
        formatted_content = _format_message_content(content)
        role = msg.get("role", "message")

        panel = rich.panel.Panel(
            formatted_content,
            title=role,
            title_align="left",
            border_style="dim",
            width=DEFAULT_PANEL_WIDTH,
            padding=(1, 2),
        )

        console = get_console()
        with console.capture() as capture:
            console.print(panel)

        rendered_panel = capture.get()
        for line in rendered_panel.splitlines():
            console.print(rich.text.Text(prefix) + rich.text.Text.from_ansi(line))


def _format_tool_panel(tool: dict[str, Any]) -> rich.panel.Panel:
    function_block = tool.get("function", {})
    name = function_block.get("name") or tool.get("name", "unknown_tool")
    description = function_block.get("description", "")
    parameters = function_block.get("parameters", {})

    body_lines: list[str] = []
    if description:
        body_lines.append(description)
    if parameters:
        formatted_schema = json.dumps(parameters, indent=2, sort_keys=True)
        body_lines.append("\nSchema:\n" + formatted_schema)

    content = rich.text.Text(
        "\n".join(body_lines) if body_lines else "(no metadata)", overflow="fold"
    )
    return rich.panel.Panel(
        content,
        title=f"tool: {name}",
        title_align="left",
        border_style="cyan",
        width=DEFAULT_PANEL_WIDTH,
        padding=(1, 2),
    )


def _display_tools(tools: list[dict[str, Any]] | None, prefix: str = "") -> None:
    """Display tools with optional prefix for each line."""
    if not tools:
        return

    console = get_console()
    console.print(rich.text.Text(f"{prefix}Tools registered:\n", style="bold"))
    for tool in tools:
        panel = _format_tool_panel(tool)
        with console.capture() as capture:
            console.print(panel)
        rendered_panel = capture.get()
        for line in rendered_panel.splitlines():
            console.print(rich.text.Text(prefix) + rich.text.Text.from_ansi(line))
    console.print("")


def _format_tool_summary(tool: dict[str, Any]) -> str:
    """Format a concise tool summary showing only name and description."""
    function_block = tool.get("function", {})
    name = function_block.get("name") or tool.get("name", "unknown_tool")
    description = function_block.get("description", "No description")
    return f"  • {name}: {description}"


def _display_chat_prompt_messages_and_tools(
    chat_p: chat_prompt.ChatPrompt,
    key: str | None = None,
) -> list[rich.console.RenderableType]:
    """
    Extract and format messages and tools from a ChatPrompt for display.

    Args:
        chat_p: The ChatPrompt to display
        key: Optional key name if this is part of a dictionary of prompts

    Returns:
        List of Rich renderable items
    """
    items: list[rich.console.RenderableType] = []

    if key:
        items.append(rich.text.Text(f"\n[{key}]", style="bold yellow"))

    messages = chat_p.get_messages()
    for msg in messages:
        content_value: str | list[dict[str, Any]] = msg.get("content", "")
        formatted_content = _format_message_content(content_value)
        role = msg.get("role", "message")

        items.append(
            rich.panel.Panel(
                formatted_content,
                title=role,
                title_align="left",
                border_style="dim",
                width=DEFAULT_PANEL_WIDTH,
                padding=(1, 2),
            )
        )

    if chat_p.tools:
        tool_summary_lines = ["Tools:"]
        for tool in chat_p.tools:
            tool_summary_lines.append(_format_tool_summary(tool))
        items.append(
            rich.panel.Panel(
                rich.text.Text("\n".join(tool_summary_lines), style="dim cyan"),
                border_style="dim",
                width=DEFAULT_PANEL_WIDTH,
                padding=(1, 2),
            )
        )

    return items


def get_link_text(
    pre_text: str,
    link_text: str,
    optimization_id: str | None = None,
    dataset_id: str | None = None,
) -> rich.text.Text:
    if optimization_id is not None and dataset_id is not None:
        optimization_url = get_optimization_run_url_by_id(
            optimization_id=optimization_id, dataset_id=dataset_id
        )

        result_text = rich.text.Text(pre_text + link_text)
        result_text.stylize(
            f"link {optimization_url}",
            len(pre_text),
            len(result_text),
        )
        return result_text
    return rich.text.Text("No optimization run link available", style="dim")


def display_header(
    algorithm: str,
    optimization_id: str | None = None,
    dataset_id: str | None = None,
    verbose: int = 1,
) -> None:
    if verbose < 1:
        return

    link_text = get_link_text(
        pre_text="-> View optimization details ",
        link_text="in your Opik dashboard",
        optimization_id=optimization_id,
        dataset_id=dataset_id,
    )

    content = rich.text.Text.assemble(
        ("● ", "green"), "Running Opik Evaluation - ", (algorithm, "blue"), "\n\n"
    ).append(link_text)

    panel = rich.panel.Panel(content, box=rich.box.ROUNDED, width=DEFAULT_PANEL_WIDTH)

    console = get_console()
    console.print(panel)
    console.print("\n")


def display_result(
    initial_score: float,
    best_score: float,
    prompt: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    verbose: int = 1,
) -> None:
    """
    Display optimization results including score improvement and optimized prompts.
    """
    if verbose < 1:
        return

    console = get_console()
    display_text_block("\n> Optimization complete\n")

    content: list[rich.console.RenderableType] = []

    if best_score > initial_score:
        perc_change, has_percentage = safe_percentage_change(best_score, initial_score)
        if has_percentage:
            content.append(
                rich.text.Text(
                    f"Prompt was optimized and improved from {initial_score:.4f} to {best_score:.4f} ({perc_change:.2%})",
                    style="bold green",
                )
            )
        else:
            content.append(
                rich.text.Text(
                    f"Prompt was optimized and improved from {initial_score:.4f} to {best_score:.4f}",
                    style="bold green",
                )
            )
    else:
        content.append(
            rich.text.Text(
                "Optimization run did not find a better prompt than the initial one.\n"
                f"Score: {best_score:.4f}",
                style="dim bold red",
            )
        )

    content.append(rich.text.Text("\nOptimized prompt:"))

    if isinstance(prompt, dict):
        prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
        for key, chat_p in prompt_dict.items():
            prompt_items = _display_chat_prompt_messages_and_tools(chat_p, key=key)
            content.extend(prompt_items)
    else:
        prompt_items = _display_chat_prompt_messages_and_tools(prompt, key=None)
        content.extend(prompt_items)

    console.print(
        rich.panel.Panel(
            rich.console.Group(*content),
            title="Optimization results",
            title_align="left",
            border_style="green",
            width=DEFAULT_PANEL_WIDTH,
            padding=(1, 2),
        )
    )


def display_configuration(
    messages: list[dict[str, str]]
    | dict[str, chat_prompt.ChatPrompt]
    | chat_prompt.ChatPrompt
    | None,
    optimizer_config: dict[str, Any],
    verbose: int = 1,
    tools: list[dict[str, Any]] | None = None,
) -> None:
    """Displays the LLM messages and optimizer configuration using Rich panels."""
    if verbose < 1:
        return

    console = get_console()
    display_text_block("> Let's optimize the prompt:\n")

    if messages is None:
        pass
    elif isinstance(messages, dict):
        messages_dict = cast(dict[str, chat_prompt.ChatPrompt], messages)
        if len(messages_dict) == 1:
            chat_p = list(messages_dict.values())[0]
            prompt_items = _display_chat_prompt_messages_and_tools(chat_p, key=None)
            for item in prompt_items:
                console.print(item)
        else:
            for key, chat_p in messages_dict.items():
                prompt_items = _display_chat_prompt_messages_and_tools(chat_p, key=key)
                for item in prompt_items:
                    console.print(item)
    elif isinstance(messages, chat_prompt.ChatPrompt):
        prompt_items = _display_chat_prompt_messages_and_tools(messages, key=None)
        for item in prompt_items:
            console.print(item)
    elif isinstance(messages, list):
        display_messages(messages)
        _display_tools(tools)

    selection_summary = None
    if isinstance(messages, (chat_prompt.ChatPrompt, dict)):
        selection_summary = summarize_selection_policy(messages)

    display_text_block(f"\nUsing {optimizer_config['optimizer']} with the parameters: ")
    if selection_summary:
        display_text_block(f"  - evaluation: {selection_summary}", style="dim")

    for key, value in optimizer_config.items():
        if key == "optimizer":
            continue
        parameter_text = rich.text.Text.assemble(
            rich.text.Text(f"  - {key}: ", style="dim"),
            rich.text.Text(str(value), style="cyan"),
        )
        console.print(parameter_text)

    display_text_block("\n")


@contextmanager
def display_evaluation(
    message: str = "First we will establish the baseline performance:",
    verbose: int = 1,
    dataset_name: str | None = None,
    is_validation: bool = False,
    selection_summary: str | None = None,
) -> Iterator[Any]:
    """Context manager to display messages during an evaluation phase."""
    if verbose >= 1:
        display_text_block(f"> {message}")
        if dataset_name:
            dataset_type = "validation" if is_validation else "training"
            display_text_block(
                f"  Using {dataset_type} dataset: {dataset_name}",
                style="dim",
            )
        if selection_summary:
            display_text_block(
                f"  Evaluation settings: {selection_summary}",
                style="dim",
            )

    class Reporter:
        def set_score(self, score: float) -> None:
            if verbose >= 1:
                display_text_block(
                    f"\r  Baseline score was: {score:.4f}.\n",
                    style="green",
                )

    with suppress_opik_logs():
        with convert_tqdm_to_rich("  Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass


def display_evaluation_progress(
    prefix: str,
    score_text: str,
    style: str,
    prompts: dict[str, chat_prompt.ChatPrompt],
    verbose: int = 1,
    dataset_name: str | None = None,
    dataset_type: str | None = None,
    evaluation_settings: str | None = None,
) -> None:
    """Display progress after evaluating a prompt candidate."""
    if verbose < 1:
        return

    display_text_block(f"│    {prefix}:")

    if evaluation_settings:
        display_text_block(
            f"│         Evaluation settings: {evaluation_settings}",
            style="dim",
        )

    if dataset_name:
        ds_type = dataset_type or "training"
        display_text_block(
            f"│         (using {ds_type} dataset: {dataset_name} for ranking)",
            style="dim",
        )

    for name, prompt in prompts.items():
        display_text_block(f"│         {name}:")
        display_messages(prompt.get_messages(), "│         ")

    display_text_block(f"│         Score: {score_text}", style=style)
    display_text_block("│")


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


def display_tool_description(description: str, title: str, style: str) -> None:
    """Render a simple tool description panel."""
    console = get_console()
    panel = rich.panel.Panel(
        rich.text.Text(description),
        title=title,
        title_align="left",
        border_style=style,
        width=DEFAULT_PANEL_WIDTH,
        padding=(1, 2),
    )
    console.print(panel)


def display_text_block(text: str, style: str = "") -> None:
    """Print a prefixed single-line text block."""
    console = get_console()
    console.print(rich.text.Text(text, style=style))


def display_prefixed_block(
    lines: list[str],
    prefix: str = "│ ",
    style: str = "",
) -> None:
    """Print a block of lines with a prefix."""
    rendered = "\n".join(f"{prefix}{line}" for line in lines)
    console = get_console()
    console.print(rich.text.Text(rendered, style=style), highlight=False)


def display_error(error_message: str, verbose: int = 1) -> None:
    """Display an error message with a standard prefix."""
    if verbose >= 1:
        display_text_block(f"│   {error_message}", style="dim red")


def display_success(message: str, verbose: int = 1) -> None:
    """Display a success message with a standard prefix."""
    if verbose >= 1:
        display_text_block(f"│   {message}", style="dim green")


def display_message(message: str, verbose: int = 1) -> None:
    """Display a neutral message with a standard prefix."""
    if verbose >= 1:
        display_text_block(f"│   {message}", style="dim")


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


DEFAULT_PANEL_WIDTH = 70
