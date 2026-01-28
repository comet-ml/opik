from contextlib import contextmanager
import math
from typing import Any
from collections.abc import Iterator

import rich.panel
import rich.text

from ...api_objects import chat_prompt
from ...constants import DEFAULT_PANEL_WIDTH
from ...utils.reporting import convert_tqdm_to_rich, suppress_opik_logs
from ...utils.display import (
    display_messages,
    display_renderable_with_prefix,
    display_text_block,
)
from ...utils.display.format import format_score_progress
from .display_utils import (
    compute_message_diff_order,  # noqa: F401
    display_optimized_prompt_diff as _display_optimized_prompt_diff,
)
from .types import MessageDiffItem  # noqa: F401

__all__ = [
    "MessageDiffItem",
    "compute_message_diff_order",
    "display_optimized_prompt_diff",
]


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
def display_round_progress(max_rounds: int, verbose: int = 1) -> Any:
    """Context manager to display messages during an evaluation phase."""

    # Create a simple object with a method to set the score
    class Reporter:
        def failed_to_generate(self, num_prompts: int, error: str) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│    Failed to generate {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}: {error}",
                    style="red",
                )
                display_text_block("│")

        def round_start(self, round_number: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│ - Starting round {round_number + 1} of {max_rounds}"
                )

        def round_end(self, round_number: int, score: float, best_score: float) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│    Completed round {round_number + 1} of {max_rounds}"
                )
                if best_score == 0 and score == 0:
                    display_text_block(
                        "│    No improvement in this round - score is 0",
                        style="yellow",
                    )
                elif best_score == 0:
                    display_text_block(
                        f"│    Found a new best performing prompt: {score:.4f}",
                        style="green",
                    )
                elif score > best_score:
                    perc_change = (score - best_score) / best_score
                    display_text_block(
                        f"│    Found a new best performing prompt: {score:.4f} ({perc_change:.2%})",
                        style="green",
                    )
                elif score <= best_score:
                    display_text_block(
                        "│    No improvement in this round",
                        style="red",
                    )

                display_text_block("│")

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich(verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass


@contextmanager
def display_evaluation(
    message: str = "First we will establish the baseline performance:",
    verbose: int = 1,
    indent: str = "> ",
    baseline_score: float | None = None,
) -> Any:
    """Context manager to display messages during an evaluation phase.

    Args:
        message: Message to display
        verbose: Verbosity level
        indent: Prefix for the message (default "> " for top-level, "│   " for nested)
        baseline_score: If provided, shows score comparison instead of "Baseline score"
    """
    # Entry point
    if verbose >= 1:
        display_text_block(f"{indent}{message}")

    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s: float) -> None:
            if verbose >= 1:
                # Adjust score indentation based on indent style
                score_indent = "│ " if indent == "> " else "│   "

                if baseline_score is None:
                    # This is the baseline evaluation
                    display_text_block(
                        f"{score_indent}Baseline score was: {s:.4f}.",
                        style="green",
                    )
                    display_text_block("│")
                else:
                    # This is an improved prompt evaluation - show comparison
                    if s > baseline_score:
                        improvement_pct = (
                            ((s - baseline_score) / baseline_score * 100)
                            if baseline_score > 0
                            else 0
                        )
                        display_text_block(
                            f"{score_indent}Score for updated prompt: {s:.4f} (+{improvement_pct:.1f}%)",
                            style="green bold",
                        )
                    elif s < baseline_score:
                        decline_pct = (
                            ((baseline_score - s) / baseline_score * 100)
                            if baseline_score > 0
                            else 0
                        )
                        display_text_block(
                            f"{score_indent}Score for updated prompt: {s:.4f} (-{decline_pct:.1f}%)",
                            style="red",
                        )
                    else:
                        display_text_block(
                            f"{score_indent}Score for updated prompt: {s:.4f} (no change)",
                            style="yellow",
                        )
                    display_text_block("│")

    # Use our log suppression context manager and yield the reporter
    # Adjust progress bar indentation based on indent style
    progress_indent = "│ Evaluation" if indent == "> " else "│   Evaluation"
    with suppress_opik_logs():
        with convert_tqdm_to_rich(progress_indent, verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass


def display_optimization_start_message(verbose: int = 1) -> None:
    if verbose >= 1:
        display_text_block("> Starting the optimization run")
        display_text_block("│")


class CandidateGenerationReporter:
    def __init__(self, num_prompts: int, selection_summary: str | None = None):
        self.num_prompts = num_prompts
        self.selection_summary = selection_summary

    def set_generated_prompts(self) -> None:
        summary = f" ({self.selection_summary})" if self.selection_summary else ""
        display_text_block(
            f"│      Successfully generated {self.num_prompts} candidate prompt{'' if self.num_prompts == 1 else 's'}{summary}",
            style="dim",
        )
        display_text_block("│")


@contextmanager
def display_candidate_generation_report(
    num_prompts: int, verbose: int = 1, selection_summary: str | None = None
) -> Iterator[CandidateGenerationReporter]:
    if verbose >= 1:
        display_text_block(
            f"│    Generating candidate prompt{'' if num_prompts == 1 else 's'}:",
        )
        if selection_summary:
            display_text_block(
                f"│      Evaluation settings: {selection_summary}", style="dim"
            )

    try:
        yield CandidateGenerationReporter(num_prompts, selection_summary)
    finally:
        pass


@contextmanager
def display_prompt_candidate_scoring_report(
    verbose: int = 1, selection_summary: str | None = None
) -> Any:
    """Context manager to display messages during an evaluation phase."""

    # Create a simple object with a method to set the score
    class Reporter:
        def set_generated_prompts(
            self, candidate_count: int, prompt: chat_prompt.ChatPrompt
        ) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│       Evaluating candidate prompt {candidate_count + 1}:",
                )
                if selection_summary:
                    display_text_block(
                        f"│            Evaluation settings: {selection_summary}",
                        style="dim",
                    )
                display_messages(prompt.get_messages(), "│            ")

        def set_final_score(self, best_score: float, score: float) -> None:
            if verbose >= 1:
                score_text, style = format_score_progress(score, best_score)
                display_text_block(
                    f"│             Evaluation score: {score_text}",
                    style,
                )

                display_text_block("│   ")
                display_text_block("│   ")

    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich("│            Evaluation", verbose=verbose):
                yield Reporter()
    finally:
        pass


@contextmanager
def display_optimization_iteration(round_index: int, verbose: int = 1) -> Iterator[Any]:
    """Context manager to display progress for a single optimization round."""
    if verbose >= 1:
        display_text_block("│")
        display_text_block("│")
        display_text_block(f"│ Round {round_index}", style="bold cyan")

    class Reporter:
        def iteration_complete(self, best_score: float, improved: bool) -> None:
            if verbose >= 1:
                if improved:
                    display_text_block(
                        f"│ Round {round_index} complete - New best score: {best_score:.4f}",
                        style="green",
                    )
                else:
                    display_text_block(
                        f"│ Round {round_index} complete - No improvement (best: {best_score:.4f})",
                        style="yellow",
                    )
                display_text_block("│")

    try:
        yield Reporter()
    finally:
        pass


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

    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich("│            Batch analysis", verbose=verbose):
                yield Reporter()
    finally:
        pass


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

    class Reporter:
        def set_reasoning(self, reasoning: str) -> None:
            if verbose >= 1:
                content_lines = [
                    "Improvement Strategy:",
                    reasoning,
                ]

                display_text_block("│")
                _display_box(
                    title="💡 Reasoning",
                    content_lines=content_lines,
                    title_style="bright_blue",
                    width=DEFAULT_PANEL_WIDTH
                    - 10,  # Slightly narrower for nested display
                )
                display_text_block("│   ")

    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich(
                "│     Generating improved prompt", verbose=verbose
            ):
                yield Reporter()
    finally:
        pass


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


def display_optimized_prompt_diff(
    initial_messages: list[dict[str, str]],
    optimized_messages: list[dict[str, str]],
    initial_score: float,
    best_score: float,
    verbose: int = 1,
    prompt_name: str | None = None,
) -> None:
    """Display git-style diff of prompt changes."""
    _display_optimized_prompt_diff(
        initial_messages,
        optimized_messages,
        initial_score,
        best_score,
        verbose=verbose,
        prompt_name=prompt_name,
    )
