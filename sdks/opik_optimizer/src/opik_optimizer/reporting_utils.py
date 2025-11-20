import json
import logging
import re
from contextlib import contextmanager
from typing import Any

from rich import box
from rich.console import Console, Group, RenderableType
from rich.panel import Panel
from rich.progress import track
from rich.text import Text

from .utils import get_optimization_run_url_by_id

PANEL_WIDTH = 70


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


def get_console(*args: Any, **kwargs: Any) -> Console:
    console = Console(*args, **kwargs)
    console.is_jupyter = False
    return console


@contextmanager
def convert_tqdm_to_rich(description: str | None = None, verbose: int = 1) -> Any:
    """Context manager to convert tqdm to rich."""
    import opik.evaluation.engine.evaluation_tasks_executor

    def _tqdm_to_track(iterable: Any, desc: str, disable: bool, total: int) -> Any:
        disable = verbose == 0
        return track(
            iterable, description=description or desc, disable=disable, total=total
        )

    original__tqdm = opik.evaluation.engine.evaluation_tasks_executor._tqdm
    opik.evaluation.engine.evaluation_tasks_executor._tqdm = _tqdm_to_track

    from opik.evaluation import report

    # Store original functions
    original_display_experiment_results = report.display_experiment_results
    original_display_experiment_link = report.display_experiment_link

    # Replace with no-ops
    report.display_experiment_results = lambda *args, **kwargs: None
    report.display_experiment_link = lambda *args, **kwargs: None

    try:
        yield
    finally:
        # Restore everything
        opik.evaluation.engine.evaluation_tasks_executor._tqdm = original__tqdm
        report.display_experiment_results = original_display_experiment_results
        report.display_experiment_link = original_display_experiment_link


@contextmanager
def suppress_opik_logs() -> Any:
    """Suppress Opik startup logs by temporarily increasing the log level."""
    # Get all loggers we need to suppress
    opik_client_logger = logging.getLogger("opik.api_objects.opik_client")
    opik_logger = logging.getLogger("opik")

    # Store original log levels
    original_client_level = opik_client_logger.level
    original_opik_level = opik_logger.level

    # Set log level to WARNING to suppress INFO messages
    opik_client_logger.setLevel(logging.WARNING)
    opik_logger.setLevel(logging.WARNING)

    try:
        yield
    finally:
        # Restore original log levels
        opik_client_logger.setLevel(original_client_level)
        opik_logger.setLevel(original_opik_level)


def format_prompt_snippet(text: str, max_length: int = 100) -> str:
    """
    Normalize whitespace in a prompt snippet and truncate it for compact display.

    Args:
        text: Raw text to summarize.
        max_length: Maximum characters to keep before adding an ellipsis.

    Returns:
        str: Condensed snippet safe for inline logging.
    """
    normalized = re.sub(r"\s+", " ", text.strip())
    if len(normalized) > max_length:
        return normalized[:max_length] + "…"
    return normalized


def _format_message_content(content: str | list[dict[str, Any]]) -> Text:
    """
    Format message content for display, handling both string and multimodal content.

    Args:
        content: Message content, either a string or a list of text/image parts.

    Returns:
        Text object ready for Rich display.
    """
    if isinstance(content, str):
        return Text(content, overflow="fold")

    # Handle multimodal content (list of parts)
    formatted_parts: list[Text] = []

    for part in content:
        part_type = part.get("type")
        if part_type == "text":
            text_content = part.get("text", "")
            if text_content:
                # Split text into lines and format with pipe prefix
                lines = text_content.split("\n")
                formatted_lines: list[str] = ["text:"]
                for line in lines:
                    formatted_lines.append(f"  | {line}")
                formatted_parts.append(
                    Text("\n".join(formatted_lines), overflow="fold")
                )
        elif part_type == "image_url":
            image_url_data = part.get("image_url", {})
            url = (
                image_url_data.get("url", "")
                if isinstance(image_url_data, dict)
                else ""
            )

            if url:
                # Check if it's a base64 data URI
                if url.startswith("data:image"):
                    # Extract base64 part (after the comma)
                    if "," in url:
                        base64_part = url.split(",", 1)[1]
                        # Show first 10 characters of base64
                        preview = (
                            base64_part[:10] + "..."
                            if len(base64_part) > 10
                            else base64_part
                        )
                        formatted_parts.append(
                            Text(
                                f"image_url:\n  | {preview}",
                                overflow="fold",
                                style="dim",
                            )
                        )
                    else:
                        formatted_parts.append(
                            Text(
                                f"image_url:\n  | {url[:50]}...",
                                overflow="fold",
                                style="dim",
                            )
                        )
                else:
                    # Regular URL
                    display_url = url[:80] + "..." if len(url) > 80 else url
                    formatted_parts.append(
                        Text(
                            f"image_url:\n  | {display_url}",
                            overflow="fold",
                            style="dim",
                        )
                    )
            else:
                formatted_parts.append(
                    Text("image_url:\n  | <no URL>", overflow="fold", style="dim")
                )

    # Combine all parts with spacing
    if not formatted_parts:
        return Text("(empty content)", style="dim")

    result = Text()
    for i, text_part in enumerate(formatted_parts):
        if i > 0:
            result.append("\n\n")
        result.append(text_part)

    return result


def display_messages(messages: list[dict[str, Any]], prefix: str = "") -> None:
    """
    Display messages using Rich panels, supporting both string and multimodal content.

    Args:
        messages: List of message dictionaries to display.
        prefix: Optional prefix to add to each line of output.
    """
    for i, msg in enumerate(messages):
        # MessageDict requires content, but we use .get() for defensive programming
        content: str | list[dict[str, Any]] = msg.get("content", "")
        formatted_content = _format_message_content(content)
        role = msg.get("role", "message")

        panel = Panel(
            formatted_content,
            title=role,
            title_align="left",
            border_style="dim",
            width=PANEL_WIDTH,
            padding=(1, 2),
        )

        # Capture the panel as rendered text with ANSI styles
        console = get_console()
        with console.capture() as capture:
            console.print(panel)

        # Retrieve the rendered string (with ANSI)
        rendered_panel = capture.get()

        # Prefix each line with '| ', preserving ANSI styles
        for line in rendered_panel.splitlines():
            console.print(Text(prefix) + Text.from_ansi(line))


def _format_tool_panel(tool: dict[str, Any]) -> Panel:
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

    content = Text(
        "\n".join(body_lines) if body_lines else "(no metadata)", overflow="fold"
    )
    return Panel(
        content,
        title=f"tool: {name}",
        title_align="left",
        border_style="cyan",
        width=PANEL_WIDTH,
        padding=(1, 2),
    )


def _display_tools(tools: list[dict[str, Any]] | None) -> None:
    if not tools:
        return

    console = get_console()
    console.print(Text("\nTools registered:\n", style="bold"))
    for tool in tools:
        panel = _format_tool_panel(tool)
        with console.capture() as capture:
            console.print(panel)
        rendered_panel = capture.get()
        for line in rendered_panel.splitlines():
            console.print(Text.from_ansi(line))
    console.print("")


def get_link_text(
    pre_text: str,
    link_text: str,
    optimization_id: str | None = None,
    dataset_id: str | None = None,
) -> Text:
    if optimization_id is not None and dataset_id is not None:
        optimization_url = get_optimization_run_url_by_id(
            optimization_id=optimization_id, dataset_id=dataset_id
        )

        # Create a visually appealing panel with an icon and ensure link doesn't wrap
        result_text = Text(pre_text + link_text)
        result_text.stylize(f"link {optimization_url}", len(pre_text), len(result_text))  # type: ignore
        return result_text
    else:
        return Text("No optimization run link available", style="dim")


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

    content = Text.assemble(
        ("● ", "green"), "Running Opik Evaluation - ", (algorithm, "blue"), "\n\n"
    ).append(link_text)

    panel = Panel(content, box=box.ROUNDED, width=PANEL_WIDTH)

    console = get_console()
    console.print(panel)
    console.print("\n")


def display_result(
    initial_score: float,
    best_score: float,
    best_prompt: list[dict[str, str]],
    verbose: int = 1,
    tools: list[dict[str, Any]] | None = None,
) -> None:
    if verbose < 1:
        return

    console = get_console()
    console.print(Text("\n> Optimization complete\n"))

    content: list[RenderableType] = []

    if best_score > initial_score:
        perc_change, has_percentage = safe_percentage_change(best_score, initial_score)
        if has_percentage:
            content.append(
                Text(
                    f"Prompt was optimized and improved from {initial_score:.4f} to {best_score:.4f} ({perc_change:.2%})",
                    style="bold green",
                )
            )
        else:
            content.append(
                Text(
                    f"Prompt was optimized and improved from {initial_score:.4f} to {best_score:.4f}",
                    style="bold green",
                )
            )
    else:
        content.append(
            Text(
                f"Optimization run did not find a better prompt than the initial one.\nScore: {best_score:.4f}",
                style="dim bold red",
            )
        )

    content.append(Text("\nOptimized prompt:"))
    for i, msg in enumerate(best_prompt):
        # MessageDict requires content, but we use .get() for defensive programming
        content_value: str | list[dict[str, Any]] = msg.get("content", "")
        formatted_content = _format_message_content(content_value)
        role = msg.get("role", "message")

        content.append(
            Panel(
                formatted_content,
                title=role,
                title_align="left",
                border_style="dim",
                width=PANEL_WIDTH,
                padding=(1, 2),
            )
        )

    console.print(
        Panel(
            Group(*content),
            title="Optimization results",
            title_align="left",
            border_style="green",
            width=PANEL_WIDTH,
            padding=(1, 2),
        )
    )

    if tools:
        _display_tools(tools)


def display_configuration(
    messages: list[dict[str, str]],
    optimizer_config: dict[str, Any],
    verbose: int = 1,
    tools: list[dict[str, Any]] | None = None,
) -> None:
    """Displays the LLM messages and optimizer configuration using Rich panels."""

    if verbose < 1:
        return

    # Panel for Optimizer configuration
    console = get_console()
    console.print(Text("> Let's optimize the prompt:\n"))

    display_messages(messages)
    _display_tools(tools)

    # Panel for configuration
    console.print(
        Text(f"\nUsing {optimizer_config['optimizer']} with the parameters: ")
    )

    for key, value in optimizer_config.items():
        if key == "optimizer":  # Already displayed in the introductory text
            continue
        parameter_text = Text.assemble(
            Text(f"  - {key}: ", style="dim"), Text(str(value), style="cyan")
        )
        console.print(parameter_text)

    console.print("\n")
