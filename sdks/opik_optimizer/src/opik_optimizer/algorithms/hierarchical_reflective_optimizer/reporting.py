from contextlib import contextmanager
import math
from typing import Any
from collections.abc import Iterator

from rich.panel import Panel
from rich.text import Text

from ...api_objects import chat_prompt
from ...utils.reporting import convert_tqdm_to_rich, suppress_opik_logs
from ...utils.display import (
    display_messages,
    display_text_block,
    display_renderable_with_prefix,
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

PANEL_WIDTH = 90


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
        display_text_block("│   Synthesizing failure modes", style="cyan")

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

    synthesis_content = Text()
    synthesis_content.append(
        f"Analyzed {total_test_cases} test cases across {num_batches} batches\n\n",
        style="bold",
    )
    synthesis_content.append("Synthesis Notes:\n", style="cyan")
    synthesis_content.append(synthesis_notes)

    panel = Panel(
        synthesis_content,
        title="🔍 Hierarchical Root Cause Analysis",
        title_align="left",
        border_style="cyan",
        width=PANEL_WIDTH,
    )

    # Capture the panel as rendered text with ANSI styles and prefix each line
    display_renderable_with_prefix(panel, prefix="│ ")
    display_text_block("│")


def display_failure_modes(failure_modes: list[Any], verbose: int = 1) -> None:
    """Display identified failure modes in formatted panels."""
    if verbose < 1:
        return

    # Display header panel
    header_panel = Panel(
        Text(
            f"Found {len(failure_modes)} distinct failure pattern{'s' if len(failure_modes) != 1 else ''}",
            style="bold yellow",
        ),
        title="⚠️ IDENTIFIED FAILURE MODES",
        title_align="left",
        border_style="yellow",
        width=PANEL_WIDTH,
    )

    display_text_block("│")
    display_renderable_with_prefix(header_panel, prefix="│ ")
    display_text_block("│")

    for idx, failure_mode in enumerate(failure_modes, 1):
        # Create content for this failure mode
        mode_content = Text()
        mode_content.append(f"{failure_mode.name}\n\n", style="bold white")
        mode_content.append("Description:\n", style="cyan")
        mode_content.append(f"{failure_mode.description}\n\n")
        mode_content.append("Root Cause:\n", style="cyan")
        mode_content.append(f"{failure_mode.root_cause}")

        panel = Panel(
            mode_content,
            title=f"Failure Mode {idx}",
            title_align="left",
            border_style="red" if idx == 1 else "yellow",
            width=PANEL_WIDTH,
        )

        display_renderable_with_prefix(panel, prefix="│ ")

        if idx < len(failure_modes):
            display_text_block("│")


@contextmanager
def display_prompt_improvement(
    failure_mode_name: str, verbose: int = 1
) -> Iterator[Any]:
    """Context manager to display progress while generating improved prompt."""
    if verbose >= 1:
        display_text_block("│")
        display_text_block("│   ")
        display_text_block(
            f"│   Addressing: {failure_mode_name}",
            style="bold cyan",
        )

    class Reporter:
        def set_reasoning(self, reasoning: str) -> None:
            if verbose >= 1:
                reasoning_content = Text()
                reasoning_content.append("Improvement Strategy:\n", style="cyan")
                reasoning_content.append(reasoning)

                panel = Panel(
                    reasoning_content,
                    title="💡 Reasoning",
                    title_align="left",
                    border_style="blue",
                    width=PANEL_WIDTH - 10,
                    padding=(0, 1),
                )

                display_renderable_with_prefix(panel, prefix="│ ")
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
            style="green bold",
        )
    else:
        display_text_block(
            f"│   ✗ No improvement: {improvement_str} (score: {current_score:.4f}, best: {best_score:.4f})",
            style="yellow",
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
