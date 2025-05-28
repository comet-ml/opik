from contextlib import contextmanager
from io import StringIO
from typing import Dict, List

import rich
from rich import box
from rich.console import Console, Group
from rich.panel import Panel
from rich.progress import track
from rich.text import Text

PANEL_WIDTH = 70
console = Console()

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
def display_round_progress(max_rounds: int):
    """Context manager to display messages during an evaluation phase."""
    
    # Create a simple object with a method to set the score
    class Reporter:
        def round_start(self, round_number):
            rich.print(Text(f"│ - Starting optimization round {round_number + 1} of {max_rounds}"))

        def round_end(self, round_number, score, best_score, best_prompt):
            rich.print(Text(f"│    Completed optimization round {round_number + 1} of {max_rounds}"))
            if score > best_score:
                perc_change = (score - best_score) / best_score
                rich.print(Text(f"│    Found a new best performing prompt: {score:.4f} ({perc_change:.2%})", style="green"))
            elif score <= best_score:
                perc_change = (score - best_score) / best_score
                rich.print(Text("│    No improvement in this optimization round", style="red"))
            
    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich():
            try:
                yield Reporter()
            finally:
                pass


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

def display_optimization_start_message():
    rich.print(Text("> Starting the optimization run"))
    rich.print(Text("│"))


@contextmanager
def display_candidate_generation_report(num_prompts: int):
    """Context manager to display messages during an evaluation phase."""
    # Entry point
    rich.print(Text(f"│    Generating candidate prompt{'' if num_prompts == 1 else 's'}:"))
    
    # Create a simple object with a method to set the score
    class Reporter:
        def set_failed_to_generate(self, num_prompts, error):
            rich.print(Text("│") + Text(f"    Failed to generate {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}", style="dim red"))
            rich.print(Text("│"))
        
        def set_generated_prompts(self, prompts):
            rich.print(Text(f"│      Successfully generated {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}", style="dim"))
            rich.print(Text("│"))

    try:
        yield Reporter()
    finally:
        pass


@contextmanager
def display_prompt_candidate_scoring_report(candidate_count, prompt):
    """Context manager to display messages during an evaluation phase."""
    # Entry point
    rich.print(Text("│    Evaluating candidate prompts:"))
    
    # Create a simple object with a method to set the score
    class Reporter:
        def set_generated_prompts(self, candidate_count, prompt):
            rich.print(Text(f"│      Candidate prompt {candidate_count+1}:"))
            for msg in prompt:
                panel = Panel(
                    Text(msg.get('content', ''), overflow="fold"),
                    title=f"{msg.get('role', 'message')}",
                    title_align="left",
                    border_style="dim",
                    width=PANEL_WIDTH,
                    padding=(1, 2),
                )
                # Use a temporary buffer to render the panel
                buffer = StringIO()
                temp_console = Console(file=buffer, width=console.width)
                temp_console.print(panel)

                # Add prefix to each line
                panel_output = buffer.getvalue()
                prefixed = "\n".join(f"│         {line}" for line in panel_output.splitlines())

                # Print the final result
                console.print(prefixed)
                rich.print(Text("│"))
        
        def set_final_score(self, best_score, score):
            if score > best_score:
                perc_change = (score - best_score) / best_score
                rich.print(Text(f"│          Evaluation score: {score:.4f} ({perc_change:.2%})", style="green"))
            elif score < best_score:
                perc_change = (score - best_score) / best_score
                rich.print(Text(f"│         Evaluation score: {score:.4f} ({perc_change:.2%})", style="red"))
            else:
                rich.print(Text(f"│         Evaluation score: {score:.4f}", style="dim yellow"))
            
            rich.print(Text("│"))
            rich.print(Text("│"))
    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich("│         Evaluation"):
                yield Reporter()
    finally:
        pass

