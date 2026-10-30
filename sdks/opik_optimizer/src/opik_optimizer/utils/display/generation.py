"""Shared candidate-generation console events."""

from .terminal import display_text_block


class CandidateGenerationReporter:
    """Reporter for candidate generation display output."""

    def __init__(
        self, num_prompts: int, selection_summary: str | None = None, verbose: int = 1
    ) -> None:
        """Create a reporter for a prompt cap and selection summary."""
        self.verbose = verbose
        self.num_prompts = num_prompts
        self.selection_summary = selection_summary

    def set_generated_prompts(self, generated_count: int) -> None:
        """Display a summary line for generated candidate counts."""
        if self.verbose < 1:
            return
        summary = f" ({self.selection_summary})" if self.selection_summary else ""
        cap_text = (
            f" (cap {self.num_prompts})" if generated_count > self.num_prompts else ""
        )
        display_text_block(
            f"│      Successfully generated {generated_count} candidate prompt{'' if generated_count == 1 else 's'}{cap_text}{summary}",
            style="dim",
        )
        display_text_block("│")


def display_candidate_generation_report(
    num_prompts: int, verbose: int = 1, selection_summary: str | None = None
) -> CandidateGenerationReporter:
    """Display the candidate generation header and return a reporter."""
    if verbose >= 1:
        display_text_block(
            f"│    Generating up to {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}:",
        )
        if selection_summary:
            display_text_block(
                f"│      Evaluation settings: {selection_summary}", style="dim"
            )
    return CandidateGenerationReporter(num_prompts, selection_summary, verbose)
