from contextlib import contextmanager

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
def display_round_progress(max_rounds: int, verbose: int = 1):
    """Context manager to display messages during an evaluation phase."""
    
    # Create a simple object with a method to set the score
    class Reporter:
        def failed_to_generate(self, num_prompts, error):
            if verbose >= 1:
                console.print(Text(f"│    Failed to generate {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}: {error}", style="red"))
                console.print(Text("│"))
            
        def round_start(self, round_number):
            if verbose >= 1:
                console.print(Text(f"│ - Starting optimization round {round_number + 1} of {max_rounds}"))

        def round_end(self, round_number, score, best_score, best_prompt):
            if verbose >= 1:
                console.print(Text(f"│    Completed optimization round {round_number + 1} of {max_rounds}"))
                if best_score == 0 and score == 0:
                    console.print(Text("│    No improvement in this optimization round - score is 0", style="yellow"))
                elif best_score == 0:
                    console.print(Text(f"│    Found a new best performing prompt: {score:.4f}", style="green"))
                elif score > best_score:
                    perc_change = (score - best_score) / best_score
                    console.print(Text(f"│    Found a new best performing prompt: {score:.4f} ({perc_change:.2%})", style="green"))
                elif score <= best_score:
                    console.print(Text("│    No improvement in this optimization round", style="red"))
                
                console.print(Text("│"))

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich(verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass


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
            if verbose >= 1:
                console.print(Text(f"\r  Baseline score was: {s:.4f}.\n", style="green"))
    
    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("  Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass

def display_optimization_start_message(verbose: int = 1):
    if verbose >= 1:
        console.print(Text("> Starting the optimization run"))
        console.print(Text("│"))


@contextmanager
def display_candidate_generation_report(num_prompts: int, verbose: int = 1):
    """Context manager to display messages during an evaluation phase."""
    # Entry point
    if verbose >= 1:
        console.print(Text(f"│    Generating candidate prompt{'' if num_prompts == 1 else 's'}:"))
    
    # Create a simple object with a method to set the score
    class Reporter:
        def set_generated_prompts(self, prompts):
            console.print(Text(f"│      Successfully generated {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}", style="dim"))
            console.print(Text("│"))

    try:
        yield Reporter()
    finally:
        pass


@contextmanager
def display_prompt_candidate_scoring_report(candidate_count, prompt, verbose: int = 1):
    """Context manager to display messages during an evaluation phase."""
    # Create a simple object with a method to set the score
    class Reporter:
        def set_generated_prompts(self, candidate_count, prompt):
            if verbose >= 1:
                console.print(Text(f"│    Evaluating candidate prompt {candidate_count+1}:"))
                display_messages(prompt, "│         ")
        
        def set_final_score(self, best_score, score):
            if verbose >= 1:
                if best_score == 0 and score > 0:
                    console.print(Text(f"│          Evaluation score: {score:.4f}", style="green"))
                elif best_score == 0 and score == 0:
                    console.print(Text(f"│         Evaluation score: {score:.4f}", style="dim yellow"))
                elif score > best_score:
                    perc_change = (score - best_score) / best_score
                    console.print(Text(f"│          Evaluation score: {score:.4f} ({perc_change:.2%})", style="green"))
                elif score < best_score:
                    perc_change = (score - best_score) / best_score
                    console.print(Text(f"│          Evaluation score: {score:.4f} ({perc_change:.2%})", style="red"))
                else:
                    console.print(Text(f"│         Evaluation score: {score:.4f}", style="dim yellow"))
            
                console.print(Text("│"))
                console.print(Text("│"))
    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich("│         Evaluation", verbose=verbose):
                yield Reporter()
    finally:
        pass

