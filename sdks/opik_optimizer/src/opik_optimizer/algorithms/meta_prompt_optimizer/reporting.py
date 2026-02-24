from contextlib import contextmanager
from typing import Any
from collections.abc import Iterator
import logging


from ...api_objects import chat_prompt
from ...utils.reporting import (
    convert_tqdm_to_rich,
    suppress_opik_logs,
)
from ...utils.display import (
    display_messages,
    display_prefixed_block,
    display_text_block,
    display_tools_panel,
)
from ...utils.display import format as display_format
from ...utils.logging import compact_debug_text, debug_log

logger = logging.getLogger(__name__)


@contextmanager
def display_round_progress(max_rounds: int, verbose: int = 1) -> Any:
    """Context manager to display messages during an evaluation phase."""

    # Create a simple object with a method to set the score
    class Reporter:
        def failed_to_generate(self, num_prompts: int, error: str) -> None:
            if verbose >= 1:
                display_prefixed_block(
                    [
                        f"Failed to generate {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}: {error}",
                        "",
                    ],
                    prefix="│    ",
                    style="red",
                )

        def round_start(self, round_number: int) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│ - Starting round {round_number + 1} of {max_rounds}"
                )

        def round_end(self, round_number: int, score: float, best_score: float) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│    Completed round {round_number + 1} of {max_rounds}"
                )
                if best_score == 0 and score == 0:
                    display_text_block(
                        "│    No improvement in this optimization round - score is 0",
                        style="yellow",
                    )
                elif best_score == 0:
                    display_text_block(
                        f"│    Found a new best performing prompt: {score:.4f}",
                        style="green",
                    )
                elif score > best_score:
                    perc_change = (score - best_score) / best_score
                    display_text_block(
                        f"│    Found a new best performing prompt: {score:.4f} ({perc_change:.2%})",
                        style="green",
                    )
                elif score <= best_score:
                    display_text_block(
                        "│    No improvement in this optimization round",
                        style="red",
                    )

                display_text_block("│")

    # Use our log suppression context manager and yield the reporter
    with suppress_opik_logs():
        with convert_tqdm_to_rich(verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass


@contextmanager
def display_evaluation(
    message: str = "First we will establish the baseline performance:",
    verbose: int = 1,
    dataset_name: str | None = None,
    is_validation: bool = False,
) -> Any:
    """Context manager to display messages during an evaluation phase."""
    # Entry point
    if verbose >= 1:
        display_text_block(f"> {message}")
        if dataset_name:
            dataset_type = "validation" if is_validation else "training"
            display_text_block(
                f"  Using {dataset_type} dataset: {dataset_name}",
                style="dim",
            )

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


def display_optimization_start_message(
    verbose: int = 1,
    dataset_training_name: str | None = None,
    validation_dataset_name: str | None = None,
    is_using_validation: bool = False,
) -> None:
    if verbose >= 1:
        display_text_block("> Starting the optimization run")
        if dataset_training_name and validation_dataset_name and is_using_validation:
            display_text_block(
                f"│  Training dataset (feedback): {dataset_training_name}",
                style="dim",
            )
            display_text_block(
                f"│  Validation dataset (ranking): {validation_dataset_name}",
                style="dim",
            )
        elif dataset_training_name:
            display_text_block(
                f"│  Using training dataset: {dataset_training_name} (for both feedback and ranking)",
                style="dim",
            )
        display_text_block("│")


class CandidateGenerationReporter:
    def __init__(self, num_prompts: int, selection_summary: str | None = None):
        self.num_prompts = num_prompts
        self.selection_summary = selection_summary

    def set_generated_prompts(self, generated_count: int) -> None:
        summary = f" ({self.selection_summary})" if self.selection_summary else ""
        cap_text = (
            f" (cap {self.num_prompts})" if generated_count > self.num_prompts else ""
        )
        display_text_block(
            f"│      Successfully generated {generated_count} candidate prompt{'' if generated_count == 1 else 's'}{cap_text}{summary}",
            style="dim",
        )
        display_text_block("│")


@contextmanager
def display_candidate_generation_report(
    num_prompts: int, verbose: int = 1, selection_summary: str | None = None
) -> Iterator[CandidateGenerationReporter]:
    if verbose >= 1:
        display_text_block(
            f"│    Generating up to {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}:",
        )
        if selection_summary:
            display_text_block(
                f"│      Evaluation settings: {selection_summary}", style="dim"
            )

    try:
        yield CandidateGenerationReporter(num_prompts, selection_summary)
    finally:
        pass


def log_generation_start(
    *,
    round_num: int,
    best_score: float,
    source: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
) -> None:
    """Log a standardized start message for candidate generation."""
    debug_log("generation_start", round_index=round_num + 1, best_score=best_score)


def log_pattern_injection(patterns: list[str] | None) -> None:
    """Log winning pattern injection details."""
    if patterns:
        logger.info("Injecting %s patterns into generation", len(patterns))


def log_bundle_candidates_summary(candidates: list[Any]) -> None:
    """Log bundle candidate summaries for debugging."""
    logger.debug("Bundle LLM response: %d candidate bundles", len(candidates))
    for idx, cand in enumerate(candidates, start=1):
        agents = [a.name for a in cand.agents]
        focus = cand.bundle_improvement_focus
        logger.debug(
            "  Candidate %d: agents=%s focus=%s",
            idx,
            agents,
            (focus[:120] + "...")
            if isinstance(focus, str) and len(focus) > 120
            else focus,
        )


def log_candidate_generated(
    *,
    round_num: int | None,
    candidate_id: str | None,
    prompt_messages: list[dict[str, Any]],
    improvement_focus: str | None,
    reasoning: str | None,
) -> None:
    """Log a concise candidate generation event with clipped previews."""
    combined = " | ".join(
        display_format.format_prompt_snippet(msg.get("content", ""), max_length=120)
        if isinstance(msg.get("content", ""), str)
        else "[multimodal content]"
        for msg in prompt_messages
        if msg.get("content", "") is not None
    )
    prompt_preview = compact_debug_text(combined, limit=240)
    focus_preview = (
        compact_debug_text(improvement_focus, limit=120)
        if isinstance(improvement_focus, str)
        else None
    )
    reasoning_preview = (
        compact_debug_text(reasoning, limit=160) if isinstance(reasoning, str) else None
    )
    debug_log(
        "candidate_generated",
        round_index=(round_num + 1) if round_num is not None else None,
        candidate_id=candidate_id,
        prompt_preview=prompt_preview,
        improvement_focus=focus_preview,
        reasoning_preview=reasoning_preview,
    )


@contextmanager
def display_prompt_candidate_scoring_report(
    verbose: int = 1,
    dataset_name: str | None = None,
    is_validation: bool = False,
    selection_summary: str | None = None,
) -> Any:
    """Context manager to display messages during an evaluation phase."""

    # Create a simple object with a method to set the score
    class Reporter:
        def set_generated_prompts(
            self, candidate_count: int, prompts: dict[str, chat_prompt.ChatPrompt]
        ) -> None:
            if verbose >= 1:
                display_text_block(
                    f"│    Evaluating candidate prompt {candidate_count + 1}:"
                )
                if selection_summary:
                    display_text_block(
                        f"│         Evaluation settings: {selection_summary}",
                        style="dim",
                    )
                if dataset_name:
                    dataset_type = "validation" if is_validation else "training"
                    display_text_block(
                        f"│         (using {dataset_type} dataset: {dataset_name} for ranking)",
                        style="dim",
                    )
                for name, prompt in prompts.items():
                    display_text_block(f"│         {name}:")
                    display_messages(prompt.get_messages(), "│         ")
                    if getattr(prompt, "tools", None):
                        tool_use_allowed = True
                        if isinstance(prompt.model_kwargs, dict):
                            tool_use_allowed = bool(
                                prompt.model_kwargs.get("allow_tool_use", True)
                            )
                        display_tools_panel(
                            prompt.tools,
                            prefix="│         ",
                            tool_use_allowed=tool_use_allowed,
                        )
                    display_text_block("│")

        def set_final_score(self, best_score: float, score: float) -> None:
            if verbose >= 1:
                score_text, style = display_format.format_score_progress(
                    score, best_score
                )
                display_text_block(
                    f"│          Evaluation score: {score_text}",
                    style,
                )

                display_text_block("│")
                display_text_block("│")

    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich("│         Evaluation", verbose=verbose):
                yield Reporter()
    finally:
        pass
