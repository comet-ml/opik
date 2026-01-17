from contextlib import contextmanager
from typing import Any

from ...api_objects import chat_prompt
from ...utils.reporting import convert_tqdm_to_rich, suppress_opik_logs
from ...utils.display import display_text_block, display_tool_description


# FIXME: Move to new reporting utils module.
@contextmanager
def infer_output_style(verbose: int = 1) -> Any:
    class Reporter:
        def start_style_inference(self) -> None:
            if verbose >= 1:
                display_text_block("> Inferring the output style using the prompt:")
                display_text_block("│")

        def error(self, error_message: str) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│    Failed to infer output style: {error_message}",
                    style="red",
                )
                display_text_block(
                    "│    Continuing with default style",
                    style="dim",
                )

        def success(self, output_style_prompt: str) -> None:
            if verbose >= 1:
                display_tool_description(
                    output_style_prompt,
                    "Successfully inferred output style",
                    "green",
                )
                display_text_block("")

    try:
        yield Reporter()
    finally:
        pass


@contextmanager
def initializing_population(verbose: int = 1) -> Any:
    class Reporter:
        def start(self, population_size: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"> Creating {population_size - 1} variations of the initial prompt"
                )
                display_text_block("│")

        def start_fresh_prompts(self, num_fresh_starts: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│    Generating {num_fresh_starts} fresh prompts based on the task description."
                )

        def failed_fresh_prompts(self, num_fresh_starts: int, error: str) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│       Failed to generate {num_fresh_starts} fresh prompts: {error}",
                    style="dim red",
                )
                display_text_block("│")

        def success_fresh_prompts(self, num_fresh_starts: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│       Successfully generated {num_fresh_starts} fresh prompts based on the task description.",
                    style="dim green",
                )
                display_text_block("│")

        def start_variations(self, num_variations: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│    Generating {num_variations} variations of the initial prompt."
                )

        def success_variations(self, num_variations: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│       Successfully generated {num_variations - 1} variations of the initial prompt.",
                    style="dim green",
                )
                display_text_block("│")

        def failed_variations(self, num_variations: int, error: str) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│       Failed to generate {num_variations - 1} variations of the initial prompt: {error}",
                    style="dim red",
                )
                display_text_block("│")

        def end(self, population_prompts: list[chat_prompt.ChatPrompt]) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│ Successfully initialized population with {len(population_prompts)} prompts."
                )
                display_text_block("")

    try:
        yield Reporter()
    finally:
        pass


@contextmanager
def baseline_performance(verbose: int = 1) -> Any:
    """Context manager to display messages during an evaluation phase."""
    # Entry point
    if verbose >= 1:
        display_text_block("> First we will establish the baseline performance.")

    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s: float) -> None:
            if verbose >= 1:
                display_text_block(f"\r  Baseline score was: {s:.4f}.\n", style="green")

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("  Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass


@contextmanager
def evaluate_initial_population(verbose: int = 1) -> Any:
    """Context manager to display messages during an evaluation phase."""
    # Entry point
    if verbose >= 1:
        display_text_block("> Let's now evaluate the initial population")

    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, index: int, score: float, baseline_score: float) -> None:
            if verbose >= 1:
                if score >= baseline_score:
                    display_text_block(
                        f"\r  Prompt {index + 1} score was: {score}.",
                        style="green",
                    )
                else:
                    display_text_block(
                        f"\r  Prompt {index + 1} score was: {score}.",
                        style="dim",
                    )

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("│   Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                if verbose >= 1:
                    display_text_block("")


@contextmanager
def start_evolutionary_algo(verbose: int = 1) -> Any:
    """Context manager to display messages during an evolutionary algorithm phase."""
    # Entry point
    if verbose >= 1:
        display_text_block("> Starting evolutionary algorithm optimization")

    # Create a simple object with a method to set the score
    class Reporter:
        def start_gen(self, gen: int, num_gens: int) -> None:
            if verbose >= 1:
                display_text_block(f"│   Starting round {gen} of {num_gens}")

        def restart_population(self, restart_generation_nb: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│      Re-creating the population as we have not made progress in {restart_generation_nb} rounds."
                )

        def performing_crossover(self) -> None:
            if verbose >= 1:
                display_text_block(
                    "│      Performing crossover - Combining multiple prompts into a new one."
                )

        def performing_mutation(self) -> None:
            if verbose >= 1:
                display_text_block(
                    "│      Performing mutation - Altering prompts to improve their performance."
                )

        def performing_evaluation(self, num_prompts: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│      Performing evaluation - Assessing {num_prompts} prompts' performance."
                )

        def performed_evaluation(self, prompt_idx: int, score: float) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│      Performed evaluation for prompt {prompt_idx} - Score: {score:.4f}.",
                    style="dim",
                )

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich("│         Evaluation", verbose=verbose):
            try:
                yield Reporter()
            finally:
                if verbose >= 1:
                    display_text_block("")


def end_gen(
    generation_idx: int,
    best_gen_score: float,
    initial_primary_score: float,
    verbose: int = 1,
) -> None:
    if verbose >= 1:
        if best_gen_score >= initial_primary_score:
            display_text_block(
                f"│   Generation {generation_idx} completed. Found a new prompt with a score of {best_gen_score:.4f}.",
                style="green",
            )
        else:
            display_text_block(
                f"│   Generation {generation_idx} completed. No improvement in this generation."
            )

        display_text_block("│")
