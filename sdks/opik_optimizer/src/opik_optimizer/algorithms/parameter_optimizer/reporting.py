"""Reporting utilities for ParameterOptimizer."""

from contextlib import contextmanager
from typing import Any
from collections.abc import Iterator

from rich.text import Text

from ...reporting_utils import (  # noqa: F401
    convert_tqdm_to_rich,
    display_configuration,
    display_header,
    display_result,
    get_console,
    suppress_opik_logs,
)

console = get_console()
PANEL_WIDTH = 70


@contextmanager
def display_evaluation(
    message: str = "First we will establish the baseline performance:",
    verbose: int = 1,
    selection_summary: str | None = None,
) -> Iterator[Any]:
    """Context manager to display messages during an evaluation phase."""

    # Entry point
    if verbose >= 1:
        console.print(Text(f"> {message}"))
        if selection_summary:
            console.print(
                Text(f"│ Evaluation settings: {selection_summary}", style="dim")
            )

    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s: float) -> None:
            if verbose >= 1:
                console.print(Text(f"│ Baseline score was: {s:.4f}.\n", style="green"))

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
        console.print("")
        console.print(
            Text(
                f"│ Trial {trial_number + 1}/{total_trials} ({stage} search)",
                style="cyan bold",
            )
        )
        if selection_summary:
            console.print(
                Text(f"│ Evaluation settings: {selection_summary}", style="dim")
            )

        # Display parameters being tested
        if parameters:
            param_text = Text()
            param_text.append("│ Testing parameters:\n", style="dim")
            for key, value in parameters.items():
                # Format the value nicely
                if isinstance(value, float):
                    formatted_value = f"{value:.6f}"
                else:
                    formatted_value = str(value)
                param_text.append(f"│   {key}: ", style="dim")
                param_text.append(f"{formatted_value}\n", style="cyan")
            console.print(param_text)

    class Reporter:
        def set_score(self, s: float, is_best: bool = False) -> None:
            if verbose >= 1:
                if is_best:
                    console.print(
                        Text(f"│ Score: {s:.4f} (new best)", style="green bold")
                    )
                else:
                    console.print(Text(f"│ Score: {s:.4f}", style="dim"))

    with suppress_opik_logs():
        with convert_tqdm_to_rich("│   Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass
