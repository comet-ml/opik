from contextlib import contextmanager

import rich
from rich.console import Console
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
def display_round_progress(max_rounds: int):
    """Context manager to display messages during an evaluation phase."""
    
    # Create a simple object with a method to set the score
    class Reporter:
        def failed_to_generate(self, num_prompts, error):
            rich.print(Text(f"│    Failed to generate {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}: {error}", style="red"))
            rich.print(Text("│"))
        
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
            
            rich.print(Text("│"))

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
        with convert_tqdm_to_rich("  Evaluation"):
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
    # Create a simple object with a method to set the score
    class Reporter:
        def set_generated_prompts(self, candidate_count, prompt):
            rich.print(Text(f"│    Evaluating candidate prompt {candidate_count+1}:"))
            display_messages(prompt, "│         ")
        
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

