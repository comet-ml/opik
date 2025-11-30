from contextlib import contextmanager
from io import StringIO
from typing import Any, TYPE_CHECKING

from rich.panel import Panel
from rich.text import Text

if TYPE_CHECKING:
    from ...api_objects.chat_prompt import ChatPrompt

from ...reporting_utils import (  # noqa: F401
    convert_tqdm_to_rich,
    display_configuration,
    display_header,
    display_messages,
    display_result,
    get_console,
    suppress_opik_logs,
)

PANEL_WIDTH = 70
console = get_console()


@contextmanager
def display_evaluation(
    message: str = "First we will establish the baseline performance:", verbose: int = 1
) -> Any:
    """Context manager to display messages during an evaluation phase."""
    score = None

    # Entry point
    if verbose >= 1:
        console.print(Text(f"> {message}"))

    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s: float) -> None:
            nonlocal score
            score = s

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich(verbose=verbose):
            try:
                yield Reporter()
            finally:
                if verbose >= 1:
                    if score is not None:
                        console.print(
                            Text(
                                f"\r  Baseline score was: {score:.4f}.\n", style="green"
                            )
                        )
                    else:
                        console.print(
                            Text("\r  Baseline score was: None\n", style="red")
                        )


def display_few_shot_prompt_template(
    prompts_with_placeholder: dict[str, "ChatPrompt"],
    fewshot_template: str,
    verbose: int = 1,
) -> None:
    """Display the few-shot prompt template creation results."""
    if verbose < 1:
        return

    console.print(
        Text("> Let's add a placeholder for few-shot examples in the messages:")
    )
    console.print(Text("│    Created the prompt template:\n│", style="dim yellow"))

    # Display all prompts with placeholders
    for name, prompt in prompts_with_placeholder.items():
        console.print(Text(f"│    {name}:"))
        display_messages(prompt.get_messages(), prefix="│    ")
        console.print(Text("│"))

    console.print(
        Text("│\n│   With the FEW_SHOT_EXAMPLE_PLACEHOLDER following the format:")
    )

    panel = Panel(
        Text(fewshot_template),
        width=PANEL_WIDTH,
        border_style="dim",
    )
    # Use a temporary buffer to render the panel
    buffer = StringIO()
    temp_console = get_console(file=buffer, width=console.width)
    temp_console.print(panel)

    # Add prefix to each line
    panel_output = buffer.getvalue()
    prefixed = "\n".join(f"│    {line}" for line in panel_output.splitlines())

    # Print the final result
    console.print(Text(prefixed))
    console.print()


def start_optimization_run(verbose: int = 1) -> None:
    """Start the optimization run"""
    if verbose >= 1:
        console.print(Text("\n> Starting the optimization run"))
        console.print(Text("│"))


def display_trial_start(
    trial_number: int,
    total_trials: int,
    messages: list[dict[str, str]],
    verbose: int = 1,
) -> None:
    """Display the start of an optimization trial."""
    if verbose < 1:
        return

    console.print(
        Text(f"│ - Starting optimization round {trial_number + 1} of {total_trials}")
    )
    console.print(Text("│"))
    display_messages(messages, prefix="│    ")
    console.print("│")


def display_trial_score(
    trial_number: int,
    baseline_score: float,
    score: float,
    verbose: int = 1,
) -> None:
    """Display the score of an optimization trial."""
    if verbose < 1:
        return

    if baseline_score == 0:
        console.print(
            Text(
                f"│    Trial {trial_number + 1} - score was: {score:.4f}\n│",
                style="green",
            )
        )
    elif score is not None and score > baseline_score:
        console.print(
            Text(
                f"│    Trial {trial_number + 1} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│",
                style="green",
            )
        )
    elif score is not None and score <= baseline_score:
        console.print(
            Text(
                f"│    Trial {trial_number + 1} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│",
                style="red",
            )
        )
    else:
        console.print(
            Text(
                f"│    Trial {trial_number + 1} - score was not set.\n│",
                style="dim yellow",
            )
        )
