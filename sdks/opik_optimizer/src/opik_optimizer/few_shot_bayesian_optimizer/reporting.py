from contextlib import contextmanager
from io import StringIO
from typing import Dict, List

import rich
from rich.console import Console
from rich.panel import Panel
from rich.text import Text

from ..reporting_utils import (
    convert_tqdm_to_rich,
    display_configuration,  # noqa: F401
    display_header,  # noqa: F401
    display_messages,
    display_result,  # noqa: F401
    suppress_opik_logs,
)

PANEL_WIDTH = 70
console = Console()


@contextmanager
def display_evaluation(message: str = "First we will establish the baseline performance:"):
    """Context manager to display messages during an evaluation phase."""
    score = None
    
    # Entry point
    rich.print(Text(f"> {message}"))
    
    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s):
            nonlocal score
            score = s
    
    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich():
            try:
                yield Reporter()
            finally:
                rich.print(Text(f"\r  Baseline score was: {score:.4f}.\n", style="green"))

@contextmanager
def creation_few_shot_prompt_template():
    """Context manager to display messages during the creation of a few-shot prompt template."""
    rich.print(Text("> Let's add a placeholder for few-shot examples in the messages:"))

    fewshot_template = None
    
    # Create a simple object with a method to set the prompt template
    class Reporter:
        def set_fewshot_template(self, s):
            nonlocal fewshot_template
            fewshot_template = s
    
    # Use our log suppression context manager and yield the reporter
    try:
        yield Reporter()
    finally:
        rich.print(Text("│    Created the prompt template:\n│", style="dim yellow"))
        display_messages(fewshot_template.message_list_with_placeholder, prefix="│    ")
        rich.print(Text("│\n│   With the FEW_SHOT_EXAMPLE_PLACEHOLDER following the format:"))
        
        panel = Panel(Text(fewshot_template.example_template), width=PANEL_WIDTH, border_style="dim")
        # Use a temporary buffer to render the panel
        buffer = StringIO()
        temp_console = Console(file=buffer, width=console.width)
        temp_console.print(panel)

        # Add prefix to each line
        panel_output = buffer.getvalue()
        prefixed = "\n".join(f"│    {line}" for line in panel_output.splitlines())

        # Print the final result
        console.print(prefixed)
        rich.print()

def start_optimization_run():
    """Start the optimization run"""
    rich.print(Text("\n> Starting the optimization run"))
    rich.print(Text("│"))


@contextmanager
def start_optimization_trial(trial_number: int, total_trials: int):
    """Context manager to display messages during an evaluation phase."""
    # Create a simple object with a method to set the score
    class Reporter:
        def start_trial(self, messages: List[Dict[str, str]]):
            rich.print(Text(f"│ - Starting optimization round {trial_number + 1} of {total_trials}"))
            rich.print(Text("│"))
            display_messages(messages, prefix="│    ")
            rich.print("│")

        def set_score(self, baseline_score, score):
            if score is not None and score > baseline_score:
                rich.print(Text(f"│    Trial {trial_number + 1} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│", style="green"))
            elif score is not None and score <= baseline_score:
                rich.print(Text(f"│    Trial {trial_number + 1} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│", style="red"))
            else:
                rich.print(Text(f"│    Trial {trial_number + 1} - score was not set.\n│", style="dim yellow"))

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("│   Evaluation"):
            try:
                yield Reporter()
            finally:
                pass
