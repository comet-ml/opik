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
    message: str = "First we will establish the baseline performance:", verbose: int = 1
) -> Iterator[Any]:
    """Context manager to display messages during an evaluation phase."""

    # Entry point
    if verbose >= 1:
        console.print(Text(f"> {message}"))

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
def display_trial_progress(
    stage: str, n_trials: int, verbose: int = 1
) -> Iterator[Any]:
    """Context manager to display progress during Optuna trial optimization."""

    if verbose >= 1:
        console.print(Text(f"> Running {stage} search with {n_trials} trials"))

    class Reporter:
        def trial_complete(
            self, trial_number: int, score: float, is_best: bool
        ) -> None:
            if verbose >= 1:
                if is_best:
                    console.print(
                        Text(
                            f"│ Trial {trial_number + 1}/{n_trials}: {score:.4f} (new best)",
                            style="green",
                        )
                    )
                else:
                    console.print(
                        Text(
                            f"│ Trial {trial_number + 1}/{n_trials}: {score:.4f}",
                            style="dim",
                        )
                    )

    with suppress_opik_logs():
        try:
            yield Reporter()
        finally:
            if verbose >= 1:
                console.print("")


def display_search_stage_summary(
    stage: str, best_score: float, best_params: dict[str, Any], verbose: int = 1
) -> None:
    """Display summary after a search stage completes."""
    if verbose < 1:
        return

    console.print(Text(f"│ {stage.capitalize()} search complete", style="cyan"))
    console.print(Text(f"│ Best score: {best_score:.4f}", style="green"))
    if best_params:
        console.print(Text("│ Best parameters:", style="dim"))
        for key, value in best_params.items():
            console.print(Text(f"│   {key}: {value}", style="dim cyan"))
    console.print("")


@contextmanager
def display_trial_evaluation(
    trial_number: int,
    total_trials: int,
    stage: str,
    parameters: dict[str, Any],
    verbose: int = 1,
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
