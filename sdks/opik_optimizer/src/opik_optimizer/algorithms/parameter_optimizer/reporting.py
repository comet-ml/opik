"""Reporting utilities for ParameterOptimizer."""

from contextlib import contextmanager
from typing import Any
from collections.abc import Iterator

from ...utils.reporting import (  # noqa: F401
    convert_tqdm_to_rich,
    get_console,
    suppress_opik_logs,
)
from ...utils.display import (
    display_text_block,
    display_key_value_block,
)
from ...utils.display.format import format_score_progress


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
        display_text_block("")
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
            display_key_value_block(
                "Testing parameters:",
                parameters,
                prefix="│ ",
                title_style="dim",
            )

    class Reporter:
        def set_score(self, s: float, best_score: float) -> None:
            if verbose >= 1:
                score_text, style = format_score_progress(s, best_score)
                display_text_block(f"│ Score: {score_text}", style=style)

    with suppress_opik_logs():
        with convert_tqdm_to_rich("│   Evaluation", verbose=verbose):
            yield Reporter()
