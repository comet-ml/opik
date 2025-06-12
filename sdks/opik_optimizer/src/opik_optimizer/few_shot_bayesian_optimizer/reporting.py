from contextlib import contextmanager
from io import StringIO
from typing import Dict, List

import rich
from rich.panel import Panel
from rich.text import Text

from ..reporting_utils import (
    convert_tqdm_to_rich,
    display_configuration,  # noqa: F401
    display_header,  # noqa: F401
    display_messages,
    display_result,  # noqa: F401
    get_console,
    suppress_opik_logs,
)

PANEL_WIDTH = 70
console = get_console()


@contextmanager
def display_evaluation(message: str = "First we will establish the baseline performance:", verbose: int = 1):
    """Context manager to display messages during an evaluation phase."""
    score = None
    
    # Entry point
    if verbose >= 1:
        console.print(Text(f"> {message}"))
    
    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s):
            nonlocal score
            score = s
    
    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich(verbose=verbose):
            try:
                yield Reporter()
            finally:
                if verbose >= 1:
                    console.print(Text(f"\r  Baseline score was: {score:.4f}.\n", style="green"))

@contextmanager
def creation_few_shot_prompt_template(verbose: int = 1):
    """Context manager to display messages during the creation of a few-shot prompt template."""
    console.print(Text("> Let's add a placeholder for few-shot examples in the messages:"))

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
        if verbose >= 1:
            console.print(Text("│    Created the prompt template:\n│", style="dim yellow"))
            display_messages(fewshot_template.message_list_with_placeholder, prefix="│    ")
            console.print(Text("│\n│   With the FEW_SHOT_EXAMPLE_PLACEHOLDER following the format:"))
            
            panel = Panel(Text(fewshot_template.example_template), width=PANEL_WIDTH, border_style="dim")
            # Use a temporary buffer to render the panel
            buffer = StringIO()
            temp_console = get_console(file=buffer, width=console.width)
            temp_console.print(panel)

            # Add prefix to each line
            panel_output = buffer.getvalue()
            prefixed = "\n".join(f"│    {line}" for line in panel_output.splitlines())

            # Print the final result
            console.print(prefixed)
            console.print()

def start_optimization_run(verbose: int = 1):
    """Start the optimization run"""
    if verbose >= 1:
        console.print(Text("\n> Starting the optimization run"))
        console.print(Text("│"))


@contextmanager
def start_optimization_trial(trial_number: int, total_trials: int, verbose: int = 1):
    """Context manager to display messages during an evaluation phase."""
    # Create a simple object with a method to set the score
    class Reporter:
        def start_trial(self, messages: List[Dict[str, str]]):
            if verbose >= 1:
                console.print(Text(f"│ - Starting optimization round {trial_number + 1} of {total_trials}"))
                console.print(Text("│"))
                display_messages(messages, prefix="│    ")
                console.print("│")

        def set_score(self, baseline_score, score):
            if verbose >= 1:
                if baseline_score == 0:
                    console.print(Text(f"│    Trial {trial_number + 1} - score was: {score:.4f}\n│", style="green"))
                elif score is not None and score > baseline_score:
                    console.print(Text(f"│    Trial {trial_number + 1} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│", style="green"))
                elif score is not None and score <= baseline_score:
                    console.print(Text(f"│    Trial {trial_number + 1} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│", style="red"))
                else:
                    console.print(Text(f"│    Trial {trial_number + 1} - score was not set.\n│", style="dim yellow"))

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("│   Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass
