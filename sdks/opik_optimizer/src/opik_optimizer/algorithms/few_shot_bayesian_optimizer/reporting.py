from io import StringIO
from typing import TYPE_CHECKING

from rich.panel import Panel
from rich.text import Text

if TYPE_CHECKING:
    from ...api_objects.chat_prompt import ChatPrompt

from ...utils.reporting import get_console  # noqa: F401
from ...utils.display import (
    DEFAULT_PANEL_WIDTH,
    display_messages,
    display_text_block,
)

console = get_console()


def display_few_shot_prompt_template(
    prompts_with_placeholder: dict[str, "ChatPrompt"],
    fewshot_template: str,
    placeholder: str,
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

    console.print(Text(f"│\n│   With the {placeholder} following the format:"))

    panel = Panel(
        Text(fewshot_template),
        width=DEFAULT_PANEL_WIDTH,
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
        display_text_block(console, "\n> Starting the optimization run")
        console.print(Text("│"))


def display_trial_start(
    trial_number: int,
    total_trials: int,
    messages: list[dict[str, str]],
    verbose: int = 1,
    selection_summary: str | None = None,
) -> None:
    """Display the start of an optimization trial."""
    if verbose < 1:
        return

    console.print(Text(f"│ - Starting trial {trial_number + 1} of {total_trials}"))
    if selection_summary:
        display_text_block(
            console, f"│   Evaluation settings: {selection_summary}", style="dim"
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
        display_text_block(
            console,
            f"│    Trial {trial_number + 1} - score was: {score:.4f}\n│",
            style="green",
        )
    elif score is not None and score > baseline_score:
        display_text_block(
            console,
            f"│    Trial {trial_number + 1} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│",
            style="green",
        )
    elif score is not None and score <= baseline_score:
        display_text_block(
            console,
            f"│    Trial {trial_number + 1} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│",
            style="red",
        )
    else:
        display_text_block(
            console,
            f"│    Trial {trial_number + 1} - score was not set.\n│",
            style="dim yellow",
        )
