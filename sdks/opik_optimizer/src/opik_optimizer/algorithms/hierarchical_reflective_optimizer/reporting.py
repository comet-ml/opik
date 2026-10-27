from contextlib import contextmanager
import math
from typing import Any
from collections.abc import Iterator

import rich.panel
import rich.text

from ...constants import DEFAULT_PANEL_WIDTH
from ...utils.reporting import convert_tqdm_to_rich, suppress_opik_logs
from ...utils.display import (
    display_renderable_with_prefix,
    display_text_block,
)


def _display_box(
    title: str,
    content_lines: list[str],
    title_style: str = "",
    width: int = DEFAULT_PANEL_WIDTH,
) -> None:
    """Display a box with title and content using Rich panels.

    Format matches chat prompts: simple header with role name, grey borders.
    The box is displayed with a "│ " prefix to align with other output.
    Width is the total box width including borders (excluding the "│ " prefix).

    Args:
        title: Title to display in the header (colored if title_style provided)
        content_lines: List of content lines to display
        title_style: Style for the title text only (borders remain grey/dim)
        width: Total box width
    """
    content = "\n".join(content_lines).rstrip()
    content_text = rich.text.Text(content)

    if title_style:
        styled_title = f"[bold {title_style}]{title}[/bold {title_style}]"
    else:
        styled_title = title

    panel = rich.panel.Panel(
        content_text,
        title=styled_title,
        title_align="left",
        border_style="dim",
        width=width,
        padding=(1, 2),
    )

    display_renderable_with_prefix(panel, prefix="│ ")


def display_retry_attempt(
    attempt: int,
    max_attempts: int,
    failure_mode_name: str,
    verbose: int = 1,
) -> None:
    """Display retry attempt information."""
    if verbose >= 1:
        display_text_block(
            f"│    Retry attempt {attempt + 1}/{max_attempts} for failure mode '{failure_mode_name}' (no improvement observed)",
            style="yellow",
        )


@contextmanager
def display_root_cause_analysis(verbose: int = 1) -> Iterator[Any]:
    """Context manager to display progress during root cause analysis with batch tracking."""
    if verbose >= 1:
        display_text_block("│   ")
        display_text_block(
            "│   Analyzing root cause of failed evaluation items",
            style="cyan",
        )

    class Reporter:
        def set_completed(self, total_test_cases: int, num_batches: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│   Analyzed {total_test_cases} test cases across {num_batches} batches",
                    style="green",
                )
                display_text_block("│   ")

    with suppress_opik_logs():
        with convert_tqdm_to_rich("│            Batch analysis", verbose=verbose):
            yield Reporter()


@contextmanager
def display_batch_synthesis(num_batches: int, verbose: int = 1) -> Iterator[Any]:
    """Context manager to display message during batch synthesis."""
    if verbose >= 1:
        display_text_block("│   Synthesizing failure modes", style="bright_cyan")

    class Reporter:
        def set_completed(self, _num_unified_modes: int) -> None:
            # No completion message needed - failure modes will be displayed next
            pass

    with suppress_opik_logs():
        yield Reporter()


def display_hierarchical_synthesis(
    total_test_cases: int, num_batches: int, synthesis_notes: str, verbose: int = 1
) -> None:
    """Display hierarchical analysis synthesis information in a box."""
    if verbose < 1:
        return

    content_lines = [
        f"Analyzed {total_test_cases} {'case' if total_test_cases == 1 else 'cases'} "
        f"across {num_batches} {'batch' if num_batches == 1 else 'batches'}",
        "",
        "Synthesis Notes:",
        synthesis_notes,
    ]

    display_text_block("│")
    _display_box(
        title="🔍 Hierarchical Root Cause Analysis",
        content_lines=content_lines,
        title_style="bright_cyan",
    )
    display_text_block("│")


def display_failure_modes(failure_modes: list[Any], verbose: int = 1) -> None:
    """Display identified failure modes in formatted boxes."""
    if verbose < 1:
        return

    display_text_block("│")
    display_text_block(
        f"│   ✓ Identified Failure Modes: Found {len(failure_modes)} distinct "
        f"{'failure pattern' if len(failure_modes) == 1 else 'failure patterns'}",
        style="bold bright_green",
    )

    # Display each failure mode in its own box
    total_modes = len(failure_modes)
    for idx, failure_mode in enumerate(failure_modes, 1):
        display_text_block("│")
        display_text_block(f"│   Failure {idx}/{total_modes}:")
        content_lines = [
            "Description:",
            failure_mode.description,
            "",
            "Root Cause:",
            failure_mode.root_cause,
        ]

        _display_box(
            title=failure_mode.name,
            content_lines=content_lines,
            title_style="yellow",
        )

        if idx < total_modes:
            display_text_block("│")


@contextmanager
def display_prompt_improvement(
    failure_mode_name: str,
    failure_mode_index: int | None = None,
    total_failure_modes: int | None = None,
    verbose: int = 1,
) -> Iterator[Any]:
    """Context manager to display progress while generating improved prompt."""
    if verbose >= 1:
        display_text_block("│")
        display_text_block("│")

        # Build the addressing message as a simple status line
        if failure_mode_index is not None and total_failure_modes is not None:
            message = f"│   Addressing failure mode ({failure_mode_index} of {total_failure_modes}): {failure_mode_name}"
        else:
            message = f"│   Addressing: {failure_mode_name}"

        display_text_block(message, style="bold bright_cyan")

    with suppress_opik_logs():
        with convert_tqdm_to_rich("│     Generating improved prompt", verbose=verbose):
            yield object()


def display_iteration_improvement(
    improvement: float, current_score: float, best_score: float, verbose: int = 1
) -> None:
    """Display the improvement result for a failure mode round."""
    if verbose < 1:
        return

    improvement_str = f"{improvement:.2%}"
    if math.isinf(improvement):
        improvement_str = "inf"

    if improvement > 0:
        display_text_block(
            f"│   ✓ Improvement: {improvement_str} (from {best_score:.4f} to {current_score:.4f})",
            style="bold bright_green",
        )
    else:
        display_text_block(
            f"│   ✗ No improvement: {improvement_str} (score: {current_score:.4f}, best: {best_score:.4f})",
            style="bright_yellow",
        )
