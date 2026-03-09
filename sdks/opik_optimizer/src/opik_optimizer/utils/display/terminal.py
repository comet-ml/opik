"""Rich/terminal display helpers."""

from __future__ import annotations

import logging
from typing import Any, cast
from collections.abc import Iterator
from contextlib import contextmanager

import rich.box
import rich.console
import rich.panel
import rich.table
import rich.text

from ...api_objects import chat_prompt
from ...constants import is_optimization_studio
from ..reporting import get_optimization_run_url_by_id
from .format import (
    format_float,
    format_tool_summary,
    safe_percentage_change,
    summarize_selection_policy,
)
from ..multimodal import format_message_content
from ..reporting import convert_tqdm_to_rich, get_console, suppress_opik_logs
from ...constants import DEFAULT_PANEL_WIDTH, DEFAULT_DISPLAY_PREFIX

PARAMETER_DESCRIPTIONS: dict[str, str] = {
    "n_samples": "max dataset items evaluated per trial",
    "n_samples_minibatch": "dataset items per minibatch evaluation",
    "n_samples_strategy": "sampling strategy",
}


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

        console = get_console()
        if isinstance(content, str) and "<!-- FEW-SHOT EXAMPLES -->" in content:
            if role == "system":
                before, after = content.split("<!-- FEW-SHOT EXAMPLES -->", 1)
                before_text = _format_message_content(before.strip())
                after_text = rich.text.Text(after.strip())
                for title, body in (
                    ("system", before_text),
                    ("few-shot examples", after_text),
                ):
                    panel = rich.panel.Panel(
                        body,
                        title=title,
                        title_align="left",
                        border_style="dim",
                        width=DEFAULT_PANEL_WIDTH,
                        padding=(1, 2),
                    )
                    with console.capture() as capture:
                        console.print(panel)
                    rendered_panel = capture.get()
                    for line in rendered_panel.splitlines():
                        console.print(
                            rich.text.Text(prefix) + rich.text.Text.from_ansi(line)
                        )
                continue

        panel = rich.panel.Panel(
            formatted_content,
            title=role,
            title_align="left",
            border_style="dim",
            width=DEFAULT_PANEL_WIDTH,
            padding=(1, 2),
        )

        with console.capture() as capture:
            console.print(panel)

        rendered_panel = capture.get()
        for line in rendered_panel.splitlines():
            console.print(rich.text.Text(prefix) + rich.text.Text.from_ansi(line))


def _display_tools(
    tools: list[dict[str, Any]] | None,
    *,
    prefix: str = "",
    tool_use_allowed: bool | None = None,
    optimized_tool_names: set[str] | None = None,
) -> None:
    """Display tools with optional prefix for each line."""
    if not tools:
        return

    tool_text = rich.text.Text()
    tool_text.append("\n")
    for tool in tools:
        tool_text.append(
            _render_tool_summary_line(
                tool=tool,
                optimized_tool_names=optimized_tool_names,
                tool_use_allowed=bool(tool_use_allowed is not False),
            )
        )
        tool_text.append("\n")

    panel = rich.panel.Panel(
        tool_text,
        title="Tools",
        title_align="left",
        border_style="dim",
        width=DEFAULT_PANEL_WIDTH,
        padding=(0, 2),
    )
    if prefix:
        display_renderable_with_prefix(panel, prefix=prefix)
    else:
        display_renderable(panel)


def _format_tool_details(tool: dict[str, Any]) -> tuple[rich.text.Text, int]:
    """Render tool name, description, and parameter descriptions."""
    text = rich.text.Text()
    summary = format_tool_summary(tool)
    name, sep, rest = summary.partition(":")
    name_len = 0
    if sep:
        name_text = name.strip()
        text.append(name_text)
        name_len = len(text.plain)
        text.append(": ")
        text.append(rest.strip(), style="dim")
    else:
        name_text = summary.strip()
        text.append(name_text)
        name_len = len(text.plain)
        text.stylize("dim")

    function_block = tool.get("function", {})
    parameters = function_block.get("parameters")
    if isinstance(parameters, dict):
        properties = parameters.get("properties")
        if isinstance(properties, dict) and properties:
            text.append("\n\n", style="dim")
            text.append("Parameters:", style="dim")
            for param_name, schema in properties.items():
                if not isinstance(param_name, str):
                    continue
                desc = ""
                if isinstance(schema, dict):
                    desc = str(schema.get("description", "")).strip()
                line = f"\n- {param_name}"
                if desc:
                    line += f": {desc}"
                text.append(line, style="dim")
    return text, name_len


def _render_tool_summary_line(
    *,
    tool: dict[str, Any],
    optimized_tool_names: set[str] | None,
    tool_use_allowed: bool,
) -> rich.text.Text:
    """Render one summary line for a tool with centralized styles."""
    text = rich.text.Text()
    tool_key = tool.get("function", {}).get("name") or tool.get("name")
    is_optimized = (
        tool_key in optimized_tool_names if optimized_tool_names is not None else None
    )
    summary = format_tool_summary(tool)
    name, sep, rest = summary.partition(":")
    if sep:
        name_style = "cyan"
        if is_optimized is False:
            name_style = "dim"
        text.append(name.strip(), style=name_style)
        if not tool_use_allowed:
            text.append(" (disabled)", style="red")
        text.append(": ")
        text.append(rest.strip(), style="dim")
    else:
        text.append(summary.strip(), style="dim")
    return text


def _display_chat_prompt_messages_and_tools(
    chat_p: chat_prompt.ChatPrompt,
    key: str | None = None,
    optimizable_roles: set[str] | None = None,
    optimized_tool_names: set[str] | None = None,
    show_tool_details: bool = False,
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
        role = msg.get("role", "message")

        if (
            isinstance(content_value, str)
            and "<!-- FEW-SHOT EXAMPLES -->" in content_value
            and role == "system"
        ):
            before, after = content_value.split("<!-- FEW-SHOT EXAMPLES -->", 1)
            before_text = _format_message_content(before.strip())
            after_text = rich.text.Text(after.strip())
            for title, body in (
                ("system", before_text),
                ("few-shot examples", after_text),
            ):
                items.append(
                    rich.panel.Panel(
                        body,
                        title=title,
                        title_align="left",
                        border_style="dim",
                        width=DEFAULT_PANEL_WIDTH,
                        padding=(1, 2),
                    )
                )
            continue

        formatted_content = _format_message_content(content_value)
        if optimizable_roles is not None and role not in optimizable_roles:
            if isinstance(formatted_content, rich.text.Text):
                formatted_content.stylize("dim")
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
        tool_text = rich.text.Text()
        tool_text.append("\n")
        tool_use_allowed = True
        if isinstance(chat_p.model_kwargs, dict):
            tool_use_allowed = bool(chat_p.model_kwargs.get("allow_tool_use", True))
        for tool in chat_p.tools:
            tool_key = tool.get("function", {}).get("name") or tool.get("name")
            is_optimized = (
                tool_key in optimized_tool_names
                if optimized_tool_names is not None
                else None
            )
            name_style = "cyan"
            if is_optimized is False:
                name_style = "dim"
            if show_tool_details:
                details, name_len = _format_tool_details(tool)
                if name_len:
                    details.stylize(name_style, 0, name_len)
                tool_text.append(details)
            else:
                tool_text.append(
                    _render_tool_summary_line(
                        tool=tool,
                        optimized_tool_names=optimized_tool_names,
                        tool_use_allowed=tool_use_allowed,
                    )
                )
            tool_text.append("\n")
        items.append(
            rich.panel.Panel(
                tool_text,
                title="Tools",
                title_align="left",
                border_style="dim",
                width=DEFAULT_PANEL_WIDTH,
                padding=(0, 2),
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
    if is_optimization_studio():
        return

    link_text = get_link_text(
        pre_text="-> View optimization details ",
        link_text="in your Opik dashboard",
        optimization_id=optimization_id,
        dataset_id=dataset_id,
    )

    content = rich.text.Text.assemble(
        ("● ", "green"), "Running Opik Evaluation - ", (algorithm, "blue"), "\n"
    )
    if optimization_id is not None and dataset_id is not None:
        content.append("\n").append(link_text)

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
    Display a brief optimization summary.
    """
    if verbose < 1:
        return

    display_text_block("\n> Optimization complete\n")

    if best_score > initial_score:
        perc_change, has_percentage = safe_percentage_change(best_score, initial_score)
        if has_percentage:
            display_text_block(
                f"  Improved from {initial_score:.4f} to {best_score:.4f} ({perc_change:.2%})",
                style="bold green",
            )
        else:
            display_text_block(
                f"  Improved from {initial_score:.4f} to {best_score:.4f}",
                style="bold green",
            )
    else:
        display_text_block(
            "  No improvement over the initial prompt.",
            style="dim bold red",
        )


def display_configuration(
    messages: list[dict[str, str]]
    | dict[str, chat_prompt.ChatPrompt]
    | chat_prompt.ChatPrompt
    | None,
    optimizer_config: dict[str, Any],
    verbose: int = 1,
    tools: list[dict[str, Any]] | None = None,
    tool_use_allowed: bool | None = None,
    optimizable_roles: set[str] | None = None,
    optimized_tool_names: set[str] | None = None,
) -> None:
    """Displays the LLM messages and optimizer configuration using Rich panels."""
    if verbose < 1:
        return

    console = get_console()
    display_text_block("> Let's optimize the prompt:\n")

    has_prompt_tools = False
    if messages is None:
        pass
    elif isinstance(messages, dict):
        messages_dict = cast(dict[str, chat_prompt.ChatPrompt], messages)
        if len(messages_dict) == 1:
            chat_p = list(messages_dict.values())[0]
            prompt_items = _display_chat_prompt_messages_and_tools(
                chat_p,
                key=None,
                optimizable_roles=optimizable_roles,
                optimized_tool_names=optimized_tool_names,
            )
            for item in prompt_items:
                console.print(item)
            has_prompt_tools = bool(chat_p.tools)
        else:
            for key, chat_p in messages_dict.items():
                prompt_items = _display_chat_prompt_messages_and_tools(
                    chat_p,
                    key=key,
                    optimizable_roles=optimizable_roles,
                    optimized_tool_names=optimized_tool_names,
                )
                for item in prompt_items:
                    console.print(item)
                if chat_p.tools:
                    has_prompt_tools = True
    elif isinstance(messages, chat_prompt.ChatPrompt):
        prompt_items = _display_chat_prompt_messages_and_tools(
            messages,
            key=None,
            optimizable_roles=optimizable_roles,
            optimized_tool_names=optimized_tool_names,
        )
        for item in prompt_items:
            console.print(item)
        has_prompt_tools = bool(messages.tools)
    elif isinstance(messages, list):
        display_messages(messages)
        _display_tools(
            tools,
            tool_use_allowed=tool_use_allowed,
            optimized_tool_names=optimized_tool_names,
        )
        tools = None

    if tools and not has_prompt_tools:
        _display_tools(tools, tool_use_allowed=tool_use_allowed)

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
        description = PARAMETER_DESCRIPTIONS.get(key)
        if description:
            parameter_text.append(rich.text.Text(f" ({description})", style="dim"))
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
        messages = prompt.get_messages()
        display_messages(messages, "│         ")
        # Check if user messages are missing and show a note
        has_user_message = any(msg.get("role") == "user" for msg in messages)
        if not has_user_message:
            display_text_block(
                "│         (Note: No user message in this prompt)",
                style="dim",
            )

    display_text_block(f"│         Score: {score_text}", style=style)
    display_text_block("│")


def _format_model_name(result: Any) -> str:
    model_name = result.details.get("model", "[dim]N/A[/dim]")
    model_params = result.details.get("model_parameters") or {}
    optimized_model_kwargs = result.details.get("optimized_model_kwargs") or {}
    if isinstance(model_name, dict):
        if len(model_name) == 1:
            prompt_name, prompt_model = next(iter(model_name.items()))
            model_name = prompt_model
            if isinstance(optimized_model_kwargs, dict):
                model_params = optimized_model_kwargs.get(prompt_name, model_params)
        else:
            model_name = ", ".join(
                f"{name}: {value}" for name, value in model_name.items()
            )
    params_parts: list[str] = []
    if isinstance(model_params, dict):
        for key in (
            "temperature",
            "top_p",
            "max_tokens",
            "frequency_penalty",
            "presence_penalty",
        ):
            value = model_params.get(key)
            if isinstance(value, float):
                params_parts.append(f"{key}={value:.2f}")
            elif value is not None:
                params_parts.append(f"{key}={value}")
    elif isinstance(result.details.get("temperature"), (int, float)):
        params_parts.append(f"temperature={result.details.get('temperature'):.2f}")
    if params_parts:
        model_name = f"{model_name} ({', '.join(params_parts)})"
    return model_name


def _format_improvement_display(result: Any) -> str:
    improvement_display = "N/A"
    if isinstance(result.initial_score, (int, float)):
        improvement_value, has_percentage = safe_percentage_change(
            result.score, result.initial_score
        )
        if has_percentage and improvement_value is not None:
            improvement_display = f"{improvement_value:.2%}"
    return improvement_display


def _format_stop_display(stop_reason: str | None) -> str:
    stop_display = stop_reason or "completed"
    return {
        "max_trials": "Budget",
        "perfect_score": "Perfect",
        "completed": "Completed",
        "no_improvement": "No improvement",
        "error": "Error",
        "cancelled": "Cancelled",
    }.get(stop_display, stop_display)


def _count_candidates(history: list[dict[str, Any]] | None) -> int:
    return sum(len(entry.get("candidates") or []) for entry in (history or []))


def _find_best_candidate_id(
    history: list[dict[str, Any]] | None, score: float
) -> str | None:
    for entry in history or []:
        candidates = entry.get("candidates") or []
        for cand in candidates:
            if cand.get("score") == score and cand.get("id"):
                return str(cand.get("id"))
    return None


def _build_key_metrics(
    final_score_str: str, improvement_display: str, stop_display: str
) -> rich.text.Text:
    delta_text = rich.text.Text(improvement_display)
    if improvement_display.startswith("-"):
        delta_text.stylize("bold red")
    elif improvement_display not in ("N/A", "0.00%"):
        delta_text.stylize("bold green")
    key_metrics = rich.text.Text(f"Best: {final_score_str} | Δ: ")
    key_metrics.append(delta_text)
    key_metrics.append(f" | Stop: {stop_display}")
    return key_metrics


def _build_prompt_panel(result: Any) -> rich.panel.Panel:
    panel_title = "[bold]Final Optimized Prompt[/bold]"
    try:
        prompt_items: list[rich.console.RenderableType] = []
        if isinstance(result.prompt, dict):
            prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], result.prompt)
            for key, chat_p in prompt_dict.items():
                prompt_items.extend(
                    _display_chat_prompt_messages_and_tools(
                        chat_p, key=key, show_tool_details=True
                    )
                )
        else:
            prompt_items.extend(
                _display_chat_prompt_messages_and_tools(
                    result.prompt, key=None, show_tool_details=True
                )
            )
        prompt_renderable: rich.console.RenderableType = rich.console.Group(
            *prompt_items
        )
    except Exception:
        prompt_renderable = rich.text.Text(str(result.prompt or ""), overflow="fold")
        panel_title = "[bold]Final Optimized Prompt (Instruction - fallback)[/bold]"

    return rich.panel.Panel(
        prompt_renderable, title=panel_title, border_style="blue", padding=(1, 2)
    )


def _build_parameter_summary_table(
    *,
    param_names: list[str],
    optimized_params: dict[str, Any],
    parameter_importance: dict[str, Any],
    search_ranges: dict[str, dict[str, Any]],
    search_stages: list[dict[str, Any]],
    precision: int,
    total_improvement: float | None,
) -> rich.table.Table:
    summary_table = rich.table.Table(
        title="Parameter Summary", show_header=True, title_style="bold"
    )
    summary_table.add_column("Parameter", justify="left", style="cyan")
    summary_table.add_column("Value", justify="left")
    summary_table.add_column("Importance", justify="left", style="magenta")
    summary_table.add_column("Gain", justify="left", style="dim")
    summary_table.add_column("Ranges", justify="left")

    stage_order: list[str] = []
    for record in search_stages:
        stage = record.get("stage")
        if isinstance(stage, str) and stage in search_ranges:
            stage_order.append(stage)
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

    for name in param_names:
        value = optimized_params.get(name)
        value_str = (
            format_float(value, precision) if value is not None else "[dim]N/A[/dim]"
        )
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

    return summary_table


def _format_score_strings(result: Any) -> tuple[str, str, str]:
    initial_score = result.initial_score
    initial_score_str = (
        f"{initial_score:.4f}"
        if isinstance(initial_score, (int, float))
        else "[dim]N/A[/dim]"
    )
    final_score_str = f"{result.score:.4f}"
    improvement_str = result._calculate_improvement_str()
    return initial_score_str, final_score_str, improvement_str


def _collect_result_stats(result: Any) -> dict[str, Any]:
    trials_completed = result.details.get("trials_completed")
    stop_reason = result.details.get("stop_reason")
    rounds_ran = len(result.history)
    candidates_count = _count_candidates(result.history)
    best_candidate_id = _find_best_candidate_id(result.history, result.score)
    return {
        "trials_completed": trials_completed,
        "stop_reason": stop_reason,
        "rounds_ran": rounds_ran,
        "candidates_count": candidates_count,
        "best_candidate_id": best_candidate_id,
        "llm_calls": result.llm_calls,
        "llm_tools": result.llm_calls_tools,
    }


def _build_result_overview_table(result: Any) -> rich.table.Table:
    model_name = _format_model_name(result)
    initial_score_str, final_score_str, improvement_str = _format_score_strings(result)
    stats = _collect_result_stats(result)

    table = rich.table.Table.grid(padding=(0, 1))
    table.add_column(style="dim")
    table.add_column()

    table.add_row("Optimizer:", f"[bold]{result.optimizer}[/bold]")
    if getattr(result, "dataset_id", None):
        table.add_row("Dataset ID:", str(result.dataset_id))
    table.add_row("Model Used:", f"{model_name}")
    table.add_row("Metric Evaluated:", f"[bold]{result.metric_name}[/bold]")
    table.add_row("Initial Score:", initial_score_str)
    table.add_row("Final Best Score:", f"[bold cyan]{final_score_str}[/bold cyan]")
    table.add_row("Total Improvement:", improvement_str)

    display_trials = (
        str(stats["trials_completed"])
        if isinstance(stats["trials_completed"], int)
        else str(stats["rounds_ran"])
    )
    improvement_display = _format_improvement_display(result)
    stop_display = _format_stop_display(stats["stop_reason"])
    table.add_row(
        "Key metrics:",
        _build_key_metrics(final_score_str, improvement_display, stop_display),
    )
    table.add_row(
        "Statistics:",
        f"Trials: {display_trials} | Rounds: {stats['rounds_ran']} | Candidates: {stats['candidates_count']}",
    )

    if stats["best_candidate_id"]:
        table.add_row("Best candidate:", stats["best_candidate_id"])
    _maybe_add_counters(table, stats)
    table.add_row(
        "Best prompt summary:",
        _summarize_prompt_structure(result.prompt),
    )
    table.add_row(
        "Optimization run link:",
        get_link_text(
            pre_text="",
            link_text="Open in Opik Dashboard",
            dataset_id=result.dataset_id,
            optimization_id=result.optimization_id,
        ),
    )
    return table


def _maybe_add_counters(table: rich.table.Table, stats: dict[str, Any]) -> None:
    llm_calls = stats.get("llm_calls")
    llm_tools = stats.get("llm_tools")
    if (llm_calls is None and llm_tools is None) or not logging.getLogger(
        "opik_optimizer"
    ).isEnabledFor(logging.DEBUG):
        return

    counters: list[str] = []
    if isinstance(llm_calls, int):
        counters.append(f"LLM Calls: {llm_calls}")
    if isinstance(llm_tools, int):
        counters.append(f"Tool Calls: {llm_tools}")
    if counters:
        table.add_row("Counters:", " | ".join(counters))


def _collect_parameter_names(
    optimized_params: dict[str, Any],
    parameter_space: dict[str, Any] | None,
    search_ranges: dict[str, Any],
) -> list[str]:
    if optimized_params:
        return sorted(optimized_params)

    param_names: list[str] = []
    if isinstance(parameter_space, dict):
        for spec in parameter_space.get("parameters", []) or []:
            if isinstance(spec, dict) and isinstance(spec.get("name"), str):
                param_names.append(spec["name"])

    if not param_names and search_ranges:
        seen: set[str] = set()
        for stage_params in search_ranges.values():
            if not isinstance(stage_params, dict):
                continue
            for key in stage_params:
                if isinstance(key, str) and key not in seen:
                    seen.add(key)
                    param_names.append(key)

    return param_names


def _build_parameter_summary_renderables(
    result: Any,
    param_names: list[str],
) -> list[rich.console.RenderableType]:
    optimized_params = result.details.get("optimized_parameters") or {}
    parameter_importance = result.details.get("parameter_importance") or {}
    search_ranges = result.details.get("search_ranges") or {}
    precision = result.details.get("parameter_precision", 6)

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

    summary_table = _build_parameter_summary_table(
        param_names=param_names,
        optimized_params=optimized_params,
        parameter_importance=parameter_importance,
        search_ranges=search_ranges,
        search_stages=result.details.get("search_stages", []),
        precision=precision,
        total_improvement=total_improvement,
    )
    return [summary_table, "\n"]


def render_rich_result(result: Any) -> rich.panel.Panel:
    """Create a rich Panel for the final optimization result."""
    overview_table = _build_result_overview_table(result)
    prompt_panel = _build_prompt_panel(result)

    renderables: list[rich.console.RenderableType] = [overview_table, "\n"]

    search_ranges = result.details.get("search_ranges") or {}
    parameter_space = result.details.get("parameter_space") or {}
    optimized_params = result.details.get("optimized_parameters") or {}
    param_names = _collect_parameter_names(
        optimized_params, parameter_space, search_ranges
    )
    if param_names:
        renderables.extend(_build_parameter_summary_renderables(result, param_names))

    renderables.append(prompt_panel)
    content_group = rich.console.Group(*renderables)

    return rich.panel.Panel(
        content_group,
        title="[bold yellow]Optimization Complete[/bold yellow]",
        border_style="yellow",
        box=rich.box.DOUBLE_EDGE,
        padding=1,
    )


def _summarize_prompt_structure(
    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
) -> str:
    """Summarize prompt structure for the final report row."""

    def _summarize_single(chat_p: chat_prompt.ChatPrompt) -> str:
        has_tools = bool(getattr(chat_p, "tools", None))
        shots = 0
        for msg in chat_p.get_messages():
            content = msg.get("content", "")
            if isinstance(content, str) and "<!-- FEW-SHOT EXAMPLES -->" in content:
                _, after = content.split("<!-- FEW-SHOT EXAMPLES -->", 1)
                shots = after.count("\nQ:") or after.count("\n Q:") or after.count("Q:")
                break
        parts = ["system"]
        if shots:
            parts.append(f"{shots} shots")
        parts.append("user")
        if has_tools:
            parts.append("tools")
        return " + ".join(parts)

    if isinstance(prompt, dict):
        summaries = []
        for key, chat_p in prompt.items():
            summaries.append(f"[{key}] {_summarize_single(chat_p)}")
        return "; ".join(summaries)
    return _summarize_single(prompt)


def display_tool_description(
    description: str | rich.text.Text, title: str, style: str
) -> None:
    """Render a simple tool description panel."""
    console = get_console()
    rendered = (
        description
        if isinstance(description, rich.text.Text)
        else rich.text.Text(description)
    )
    panel = rich.panel.Panel(
        rendered,
        title=title,
        title_align="left",
        border_style=style,
        width=DEFAULT_PANEL_WIDTH,
        padding=(1, 2),
    )
    console.print(panel)


def display_tools_panel(
    tools: list[dict[str, Any]] | None,
    *,
    prefix: str = DEFAULT_DISPLAY_PREFIX,
    tool_use_allowed: bool | None = None,
) -> None:
    """Render the tools panel with an optional prefix."""
    _display_tools(tools, prefix=prefix, tool_use_allowed=tool_use_allowed)


def display_text_block(text: str, style: str = "") -> None:
    """Print a prefixed single-line text block."""
    console = get_console()
    console.print(rich.text.Text(text, style=style))


def display_prefixed_block(
    lines: list[str],
    prefix: str = DEFAULT_DISPLAY_PREFIX,
    style: str = "",
) -> None:
    """Print a block of lines with a prefix."""
    rendered = "\n".join(f"{prefix}{line}" for line in lines)
    console = get_console()
    console.print(rich.text.Text(rendered, style=style), highlight=False)


def display_renderable(renderable: rich.console.RenderableType) -> None:
    """Render a Rich object directly to the console."""
    console = get_console()
    console.print(renderable)


def display_renderable_with_prefix(
    renderable: rich.console.RenderableType,
    *,
    prefix: str = DEFAULT_DISPLAY_PREFIX,
) -> None:
    """Render a Rich object and reprint it with a prefix on each line."""
    console = get_console()
    with console.capture() as capture:
        console.print(renderable)
    rendered = capture.get()
    display_prefixed_block(rendered.splitlines(), prefix=prefix)


def display_key_value_block(
    title: str,
    items: dict[str, Any],
    *,
    prefix: str = DEFAULT_DISPLAY_PREFIX,
    title_style: str = "dim",
    float_precision: int = 6,
) -> None:
    """Display a title followed by key/value lines using a prefixed block."""
    if not items:
        return
    lines = [title]
    for key, value in items.items():
        formatted = (
            f"{value:.{float_precision}f}" if isinstance(value, float) else str(value)
        )
        lines.append(f"  {key}: {formatted}")
    display_prefixed_block(lines, prefix=prefix, style=title_style)


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
