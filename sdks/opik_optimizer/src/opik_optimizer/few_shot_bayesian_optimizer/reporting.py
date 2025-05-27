from contextlib import contextmanager
from typing import Dict, List

import rich
from rich import box
from rich.console import Group
from rich.panel import Panel
from rich.progress import track
from rich.text import Text

PANEL_WIDTH = 70

def display_header(algorithm: str):
    content = Text.assemble(
        ("● ", "green"),  
        "Running Opik Evaluation - ",
        (algorithm, "blue")
    )

    panel = Panel(
        content,
        box=box.ROUNDED,
        width=PANEL_WIDTH
    )

    rich.print(panel)

def display_configuration(messages: List[Dict[str, str]], optimizer_config: Dict[str, str]):
    """Displays the LLM messages and optimizer configuration using Rich panels."""

    rich.print("\n")

    # Panel for Optimizer configuration
    content_panels = [Text("> Let's optimize the prompt:\n")]
    for i, msg in enumerate(messages):
        content_panels.append(
            Panel(
                Text(msg.get('content', ''), overflow="fold"),
                title=f"{msg.get('role', 'message')}",
                title_align="left",
                border_style="dim",
                width=PANEL_WIDTH,
                padding=(1, 2),
            )
        )
    
    # Panel for configuration
    content_panels.append(Text(f"\nUsing {optimizer_config['optimizer']} with the parameters: "))
    
    for key, value in optimizer_config.items():
        if key == "optimizer":  # Already displayed in the introductory text
            continue
        parameter_text = Text.assemble(
            Text(f"  - {key}: ", style="dim"), 
            Text(str(value), style="cyan")      
        )
        content_panels.append(parameter_text)
    content_panels.append(Text("\n"))
    rich.print(Group(*content_panels))

def display_messages(messages: List[Dict[str, str]]) -> None:
    for msg in messages:
        rich.print(
            Panel(
                Text(msg.get('content', ''), overflow="fold"),
                title=f"{msg.get('role', 'message')}",
                title_align="left",
                border_style="dim",
                width=PANEL_WIDTH,
                padding=(1, 2),
            )
        )


@contextmanager
def suppress_opik_logs():
    """Suppress Opik startup logs by temporarily increasing the log level."""
    import logging
    
    # Get the Opik logger
    opik_logger = logging.getLogger("opik.api_objects.opik_client")
    
    # Store original log level
    original_level = opik_logger.level
    
    # Set log level to ERROR to suppress INFO messages
    opik_logger.setLevel(logging.ERROR)
    
    try:
        yield
    finally:
        # Restore original log level
        opik_logger.setLevel(original_level)


@contextmanager
def convert_tqdm_to_rich(description: str = None):
    """Context manager to convert tqdm to rich."""
    import opik.evaluation.engine.evaluation_tasks_executor

    def _tqdm_to_track(iterable, desc, disable, total):
        return track(iterable, description=description or desc, disable=disable, total=total)


    original__tqdm = opik.evaluation.engine.evaluation_tasks_executor._tqdm
    opik.evaluation.engine.evaluation_tasks_executor._tqdm = _tqdm_to_track
    try:
        yield
    finally:
        opik.evaluation.engine.evaluation_tasks_executor._tqdm = original__tqdm


@contextmanager
def display_evaluation(message: str = "First we will establish the baseline performance:"):
    """Context manager to display messages during an evaluation phase."""
    import opik.evaluation.engine.evaluation_tasks_executor

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
        rich.print(Text("  Created the prompt template:\n", style="dim yellow"))
        display_messages(fewshot_template.message_list_with_placeholder)
        rich.print(Text("\n  With the FEW_SHOT_EXAMPLE_PLACEHOLDER following the format:"))
        rich.print(Panel(Text(f"{fewshot_template.example_template}"), width=PANEL_WIDTH, border_style="dim"))

def start_optimization_run():
    """Start the optimization run"""
    rich.print(Text("\n> Starting the optimization run"))
    rich.print(Text("│"))


@contextmanager
def start_optimization_trial(trial_number: int, baseline_score: float):
    """Context manager to display messages during an evaluation phase."""
    score = None
    
    # Entry point
    rich.print(Text(f"│ Running trial {trial_number}"))
    
    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s):
            nonlocal score
            score = s
    
    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("│ Evaluation"):
            try:
                yield Reporter()
            finally:
                if score is not None and score > baseline_score:
                    rich.print(Text(f"│ Trial {trial_number} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│", style="green"))
                elif score is not None and score <= baseline_score:
                    rich.print(Text(f"│ Trial {trial_number} - score was: {score:.4f} ({(score - baseline_score) / baseline_score * 100:.2f}%).\n│", style="red"))
                else:
                    rich.print(Text(f"│ Trial {trial_number} - score was not set.\n│", style="dim yellow"))
