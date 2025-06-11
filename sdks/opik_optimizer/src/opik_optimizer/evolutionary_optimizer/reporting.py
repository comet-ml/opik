from contextlib import contextmanager
from io import StringIO
from typing import List

from rich.panel import Panel
from rich.text import Text

from ..optimization_config import chat_prompt
from ..reporting_utils import (
    convert_tqdm_to_rich,
    display_configuration,  # noqa: F401
    display_header,  # noqa: F401
    display_messages,  # noqa: F401
    display_result,  # noqa: F401
    get_console,
    suppress_opik_logs,
)

PANEL_WIDTH = 70
console = get_console()


@contextmanager
def infer_output_style(verbose: int = 1):
    class Reporter:
        def start_style_inference(self, output_style_prompt):
            if verbose >= 1:
                console.print("> Infering the output style using the prompt:")
                console.print("│")
            
        def error(self, error_message):
            if verbose >= 1:
                console.print(Text("│    ").append(Text(f"Failed to infer output style: {error_message}", style="red")))
                console.print(Text("│    ").append(Text("Continuing with default style", style="dim")))
        
        def display_style_inference_prompt(self, output_style_prompt):
            if verbose >= 1:
                panel = Panel(
                    Text(output_style_prompt),
                    title="Output style inference prompt",
                    title_align="left",
                    border_style="dim",
                    width=PANEL_WIDTH,
                    padding=(1, 2),
                )

                # Use a temporary buffer to render the panel
                buffer = StringIO()
                temp_console = get_console(file=buffer, width=console.width)
                temp_console.print(panel)

                # Add prefix to each line
                panel_output = buffer.getvalue()
                prefixed = "\n".join(f"│ {line}" for line in panel_output.splitlines())

                # Print the final result
                console.print(prefixed)
                console.print(Text("│"))

        def success(self, output_style_prompt):
            if verbose >= 1:
                panel = Panel(
                    Text(output_style_prompt),
                    title="Successfully inferred output style",
                    title_align="left",
                    border_style="green",
                    width=PANEL_WIDTH,
                    padding=(1, 2),
                )
                
                # Capture the panel as rendered text with ANSI styles
                with console.capture() as capture:
                    console.print(panel)

                # Retrieve the rendered string (with ANSI)
                rendered_panel = capture.get()

                # Prefix each line with '│ ', preserving ANSI styles
                prefixed_output = "\n".join(f"│ {line}" for line in rendered_panel.splitlines())

                # Print the prefixed output (will include colors)
                console.print(prefixed_output, highlight=False)
                console.print(Text(""))
    
    try:
        yield Reporter()
    finally:
        pass

@contextmanager
def initializing_population(verbose: int = 1):
    class Reporter:
        def start(self, population_size):
            if verbose >= 1:
                console.print(f"> Creating {population_size - 1} variations of the initial prompt")
                console.print("│")
            
        def start_fresh_prompts(self, num_fresh_starts):
            if verbose >= 1:
                console.print(f"│    Generating {num_fresh_starts} fresh prompts based on the task description.")
        
        def success_fresh_prompts(self, num_fresh_starts):
            if verbose >= 1:
                console.print(Text("│       ").append(Text(f"Successfully generated {num_fresh_starts} fresh prompts based on the task description.", style="dim green")))
                console.print("│")
            
        def failed_fresh_prompts(self, num_fresh_starts, error):
            if verbose >= 1:
                console.print(Text("│       ").append(Text(f"Failed to generate fresh prompts from LLM: {error}", style="dim red")))
                console.print("│")

        def start_variations(self, num_variations):
            if verbose >= 1:
                console.print(f"│    Generating {num_variations} variations of the initial prompt.")
        
        def success_variations(self, num_variations):
            if verbose >= 1:
                console.print(Text(f"│       Successfully generated {num_variations - 1} variations of the initial prompt).", style="dim green"))
                console.print("│")
            
        def failed_variations(self, num_variations, error):
            if verbose >= 1:
                console.print(Text(f"│       Failed to generate {num_variations - 1} variations of the initial prompt: {error}", style="dim red"))
                console.print("│")
            
        def end(self, population_prompts: List[chat_prompt.ChatPrompt]):
            if verbose >= 1:
                console.print(f"│ Successfully initialized population with {len(population_prompts)} prompts.")
                console.print("")
                
    
    try:
        yield Reporter()
    finally:
        pass

@contextmanager
def baseline_performance(verbose: int = 1):
    """Context manager to display messages during an evaluation phase."""
    # Entry point
    if verbose >= 1:
        console.print(Text("> First we will establish the baseline performance."))
    
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

@contextmanager
def evaluate_initial_population(verbose: int = 1):
    """Context manager to display messages during an evaluation phase."""
    # Entry point
    if verbose >= 1:
        console.print(Text("> Let's now evaluate the initial population"))
    
    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, index, score, baseline_score):
            if verbose >= 1:
                if score >= baseline_score:
                    console.print(Text(f"\r  Prompt {index+1} score was: {score}.", style="green"))
                else:
                    console.print(Text(f"\r  Prompt {index+1} score was: {score}.", style="dim"))
        
    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("│   Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                if verbose >= 1:
                    console.print("")

@contextmanager
def start_evolutionary_algo(verbose: int = 1):
    """Context manager to display messages during an evolutionary algorithm phase."""
    # Entry point
    if verbose >= 1:
        console.print(Text("> Starting evolutionary algorithm optimization"))
    
    # Create a simple object with a method to set the score
    class Reporter:
        def start_gen(self, gen, num_gens):
            if verbose >= 1:
                console.print(Text(f"│   Starting generation {gen} of {num_gens}"))

        def restart_population(self, restart_generation_nb):
            if verbose >= 1:
                console.print(Text(f"│      Re-creating the population as we have not made progress in {restart_generation_nb} generations."))
        
        def performing_crossover(self):
            if verbose >= 1:
                console.print(Text("│      Performing crossover - Combining multiple prompts into a new one."))
        
        def performing_mutation(self):
            if verbose >= 1:
                console.print(Text("│      Performing mutation - Altering prompts to improve their performance."))
        
        def performing_evaluation(self, num_prompts: int):
            if verbose >= 1:
                console.print(Text(f"│      Performing evaluation - Assessing {num_prompts} prompts' performance."))
        
        def performed_evaluation(self, prompt_idx: int, score: float):
            if verbose >= 1:
                console.print(Text(f"│      Performed evaluation for prompt {prompt_idx} - Score: {score:.4f}.", style="dim"))

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("│         Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                if verbose >= 1:
                    console.print("")

def display_error(error_message, verbose: int = 1):
    if verbose >= 1:
        console.print(Text("│   ").append(Text(error_message, style="dim red")))

def display_success(message, verbose: int = 1):
    if verbose >= 1:
        console.print(Text("│   ").append(Text(message, style="dim green")))

def display_message(message, verbose: int = 1):
    if verbose >= 1:
        console.print(Text("│   ").append(Text(message, style="dim")))

def end_gen(generation_idx, best_gen_score, initial_primary_score, verbose: int = 1):
    if verbose >= 1:
        if best_gen_score >= initial_primary_score:
            console.print(Text(f"│   Generation {generation_idx} completed. Found a new prompt with a score of {best_gen_score:.4f}.", style="green"))
        else:
            console.print(Text(f"│   Generation {generation_idx} completed. No improvement in this generation."))

        console.print("│")
