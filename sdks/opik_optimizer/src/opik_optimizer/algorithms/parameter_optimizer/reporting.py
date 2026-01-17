"""Reporting utilities for ParameterOptimizer."""

from contextlib import contextmanager
from typing import Any
from collections.abc import Iterator

from ...utils.reporting import (  # noqa: F401
    convert_tqdm_to_rich,
    get_console,
    suppress_opik_logs,
)
from ...utils.display import display_text_block, display_prefixed_block


@contextmanager
def display_evaluation(
    message: str = "First we will establish the baseline performance:",
    verbose: int = 1,
    selection_summary: str | None = None,
) -> Iterator[Any]:
    """Context manager to display messages during an evaluation phase."""

    # Entry point
    if verbose >= 1:
        display_text_block(f"> {message}")
        if selection_summary:
            display_text_block(
                f"│ Evaluation settings: {selection_summary}", style="dim"
            )

    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s: float) -> None:
            if verbose >= 1:
                display_text_block(f"│ Baseline score was: {s:.4f}.\n", style="green")

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("│ Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass


@contextmanager
def display_trial_evaluation(
    trial_number: int,
    total_trials: int,
    stage: str,
    parameters: dict[str, Any],
    verbose: int = 1,
    selection_summary: str | None = None,
) -> Iterator[Any]:
    """Context manager to display a single trial evaluation with parameters."""

    if verbose >= 1:
        get_console().print("")
        display_text_block(
            f"│ Trial {trial_number + 1}/{total_trials} ({stage} search)",
            style="cyan bold",
        )
        if selection_summary:
            display_text_block(
                f"│ Evaluation settings: {selection_summary}", style="dim"
            )

        # Display parameters being tested
        if parameters:
            lines = ["Testing parameters:"]
            for key, value in parameters.items():
                formatted_value = f"{value:.6f}" if isinstance(value, float) else str(value)
                lines.append(f"  {key}: {formatted_value}")
            display_prefixed_block(lines, prefix="│ ", style="dim")

    class Reporter:
        def set_score(self, s: float, is_best: bool = False) -> None:
            if verbose >= 1:
                if is_best:
                    display_text_block(
                        f"│ Score: {s:.4f} (new best)",
                        style="green bold",
                    )
                else:
                    display_text_block(f"│ Score: {s:.4f}", style="dim")

    with suppress_opik_logs():
        with convert_tqdm_to_rich("│   Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass
