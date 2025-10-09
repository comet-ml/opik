from contextlib import contextmanager
from typing import Any
from collections.abc import Iterator

from rich.panel import Panel
from rich.text import Text

from ..optimization_config import chat_prompt
from ..reporting_utils import (
    convert_tqdm_to_rich,
    display_configuration,  # noqa: F401
    display_header,  # noqa: F401
    display_messages,
    display_result,  # noqa: F401
    get_console,
    suppress_opik_logs,
)

PANEL_WIDTH = 90
console = get_console()


def display_retry_attempt(
    attempt: int,
    max_attempts: int,
    failure_mode_name: str,
    verbose: int = 1,
) -> None:
    """Display retry attempt information."""
    if verbose >= 1:
        console.print(
            Text(
                f"│    Retry attempt {attempt + 1}/{max_attempts} for failure mode '{failure_mode_name}' (no improvement observed)",
                style="yellow"
            )
        )


@contextmanager
def display_round_progress(max_rounds: int, verbose: int = 1) -> Any:
    """Context manager to display messages during an evaluation phase."""

    # Create a simple object with a method to set the score
    class Reporter:
        def failed_to_generate(self, num_prompts: int, error: str) -> None:
            if verbose >= 1:
                console.print(
                    Text(
                        f"│    Failed to generate {num_prompts} candidate prompt{'' if num_prompts == 1 else 's'}: {error}",
                        style="red",
                    )
                )
                console.print(Text("│"))

        def round_start(self, round_number: int) -> None:
            if verbose >= 1:
                console.print(
                    Text(
                        f"│ - Starting optimization round {round_number + 1} of {max_rounds}"
                    )
                )

        def round_end(self, round_number: int, score: float, best_score: float) -> None:
            if verbose >= 1:
                console.print(
                    Text(
                        f"│    Completed optimization round {round_number + 1} of {max_rounds}"
                    )
                )
                if best_score == 0 and score == 0:
                    console.print(
                        Text(
                            "│    No improvement in this optimization round - score is 0",
                            style="yellow",
                        )
                    )
                elif best_score == 0:
                    console.print(
                        Text(
                            f"│    Found a new best performing prompt: {score:.4f}",
                            style="green",
                        )
                    )
                elif score > best_score:
                    perc_change = (score - best_score) / best_score
                    console.print(
                        Text(
                            f"│    Found a new best performing prompt: {score:.4f} ({perc_change:.2%})",
                            style="green",
                        )
                    )
                elif score <= best_score:
                    console.print(
                        Text(
                            "│    No improvement in this optimization round",
                            style="red",
                        )
                    )

                console.print(Text("│"))

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
    indent: str = "> ",
    baseline_score: float | None = None
) -> Any:
    """Context manager to display messages during an evaluation phase.

    Args:
        message: Message to display
        verbose: Verbosity level
        indent: Prefix for the message (default "> " for top-level, "│   " for nested)
        baseline_score: If provided, shows score comparison instead of "Baseline score"
    """
    # Entry point
    if verbose >= 1:
        console.print(Text(f"{indent}{message}"))

    # Create a simple object with a method to set the score
    class Reporter:
        def set_score(self, s: float) -> None:
            if verbose >= 1:
                # Adjust score indentation based on indent style
                score_indent = "  " if indent == "> " else "│   "

                if baseline_score is None:
                    # This is the baseline evaluation
                    console.print(
                        Text(f"\r{score_indent}Baseline score was: {s:.4f}.", style="green")
                    )
                    console.print(Text("│"))
                else:
                    # This is an improved prompt evaluation - show comparison
                    if s > baseline_score:
                        improvement_pct = ((s - baseline_score) / baseline_score * 100) if baseline_score > 0 else 0
                        console.print(
                            Text(
                                f"\r{score_indent}Score for updated prompt: {s:.4f} (+{improvement_pct:.1f}%)",
                                style="green bold"
                            )
                        )
                    elif s < baseline_score:
                        decline_pct = ((baseline_score - s) / baseline_score * 100) if baseline_score > 0 else 0
                        console.print(
                            Text(
                                f"\r{score_indent}Score for updated prompt: {s:.4f} (-{decline_pct:.1f}%)",
                                style="red"
                            )
                        )
                    else:
                        console.print(
                            Text(
                                f"\r{score_indent}Score for updated prompt: {s:.4f} (no change)",
                                style="yellow"
                            )
                        )
                    console.print(Text("│"))

    # Use our log suppression context manager and yield the reporter
    # Adjust progress bar indentation based on indent style
    progress_indent = "  Evaluation" if indent == "> " else "│   Evaluation"
    with suppress_opik_logs():
        with convert_tqdm_to_rich(progress_indent, verbose=verbose):
            try:
                yield Reporter()
            finally:
                pass


def display_optimization_start_message(verbose: int = 1) -> None:
    if verbose >= 1:
        console.print(Text("> Starting the optimization run"))
        console.print(Text("│"))


class CandidateGenerationReporter:
    def __init__(self, num_prompts: int):
        self.num_prompts = num_prompts

    def set_generated_prompts(self) -> None:
        console.print(
            Text(
                f"│      Successfully generated {self.num_prompts} candidate prompt{'' if self.num_prompts == 1 else 's'}",
                style="dim",
            )
        )
        console.print(Text("│"))


def display_tool_description(description: str, label: str, color: str) -> None:
    if not description.strip():
        return
    console.print(
        Panel(
            description.strip(),
            title=label,
            border_style=color,
        )
    )


@contextmanager
def display_candidate_generation_report(
    num_prompts: int, verbose: int = 1
) -> Iterator[CandidateGenerationReporter]:
    if verbose >= 1:
        console.print(
            Text(f"│    Generating candidate prompt{'' if num_prompts == 1 else 's'}:")
        )

    try:
        yield CandidateGenerationReporter(num_prompts)
    finally:
        pass


@contextmanager
def display_prompt_candidate_scoring_report(verbose: int = 1) -> Any:
    """Context manager to display messages during an evaluation phase."""

    # Create a simple object with a method to set the score
    class Reporter:
        def set_generated_prompts(
            self, candidate_count: int, prompt: chat_prompt.ChatPrompt
        ) -> None:
            if verbose >= 1:
                console.print(
                    Text(f"│       Evaluating candidate prompt {candidate_count + 1}:")
                )
                display_messages(prompt.get_messages(), "│            ")

        def set_final_score(self, best_score: float, score: float) -> None:
            if verbose >= 1:
                if best_score == 0 and score > 0:
                    console.print(
                        Text(f"│             Evaluation score: {score:.4f}", style="green")
                    )
                elif best_score == 0 and score == 0:
                    console.print(
                        Text(
                            f"│            Evaluation score: {score:.4f}",
                            style="dim yellow",
                        )
                    )
                elif score > best_score:
                    perc_change = (score - best_score) / best_score
                    console.print(
                        Text(
                            f"│             Evaluation score: {score:.4f} ({perc_change:.2%})",
                            style="green",
                        )
                    )
                elif score < best_score:
                    perc_change = (score - best_score) / best_score
                    console.print(
                        Text(
                            f"│             Evaluation score: {score:.4f} ({perc_change:.2%})",
                            style="red",
                        )
                    )
                else:
                    console.print(
                        Text(
                            f"│            Evaluation score: {score:.4f}",
                            style="dim yellow",
                        )
                    )

                console.print(Text("│   "))
                console.print(Text("│   "))

    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich("│            Evaluation", verbose=verbose):
                yield Reporter()
    finally:
        pass


@contextmanager
def display_optimization_iteration(iteration: int, verbose: int = 1) -> Iterator[Any]:
    """Context manager to display progress for a single optimization iteration."""
    if verbose >= 1:
        console.print()
        console.print(Text("│"))
        console.print(Text(f"│ Iteration {iteration}", style="bold cyan"))
    
    class Reporter:
        def iteration_complete(self, best_score: float, improved: bool) -> None:
            if verbose >= 1:
                if improved:
                    console.print(
                        Text(
                            f"│ Iteration {iteration} complete - New best score: {best_score:.4f}",
                            style="green"
                        )
                    )
                else:
                    console.print(
                        Text(
                            f"│ Iteration {iteration} complete - No improvement (best: {best_score:.4f})",
                            style="yellow"
                        )
                    )
                console.print(Text("│"))
    
    try:
        yield Reporter()
    finally:
        pass


@contextmanager
def display_root_cause_analysis(verbose: int = 1) -> Iterator[Any]:
    """Context manager to display progress during root cause analysis with batch tracking."""
    if verbose >= 1:
        console.print(Text("│   "))
        console.print(Text("│   Analyzing root cause of failed evaluation items", style="cyan"))
    
    class Reporter:
        def set_completed(self, total_test_cases: int, num_batches: int) -> None:
            if verbose >= 1:
                console.print(
                    Text(
                        f"│   Analyzed {total_test_cases} test cases across {num_batches} batches",
                        style="green"
                    )
                )
                console.print(Text("│   "))
    
    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich("│            Batch analysis", verbose=verbose):
                yield Reporter()
    finally:
        pass


@contextmanager
def display_batch_synthesis(num_batches: int, verbose: int = 1) -> Iterator[Any]:
    """Context manager to display message during batch synthesis."""
    if verbose >= 1:
        console.print(Text("│   Synthesizing failure modes", style="cyan"))
    
    class Reporter:
        def set_completed(self, num_unified_modes: int) -> None:
            # No completion message needed - failure modes will be displayed next
            pass
    
    with suppress_opik_logs():
        yield Reporter()


def display_hierarchical_synthesis(
    total_test_cases: int,
    num_batches: int,
    synthesis_notes: str,
    verbose: int = 1
) -> None:
    """Display hierarchical analysis synthesis information in a box."""
    if verbose < 1:
        return
    
    synthesis_content = Text()
    synthesis_content.append(f"Analyzed {total_test_cases} test cases across {num_batches} batches\n\n", style="bold")
    synthesis_content.append("Synthesis Notes:\n", style="cyan")
    synthesis_content.append(synthesis_notes)
    
    panel = Panel(
        synthesis_content,
        title="🔍 Hierarchical Root Cause Analysis",
        title_align="left",
        border_style="cyan",
        width=PANEL_WIDTH,
    )
    
    # Capture the panel as rendered text with ANSI styles and prefix each line
    with console.capture() as capture:
        console.print(panel)
    
    rendered_panel = capture.get()
    for line in rendered_panel.splitlines():
        console.print(Text("│ ") + Text.from_ansi(line))
    
    console.print()


def display_failure_modes(
    failure_modes: list[Any],
    verbose: int = 1
) -> None:
    """Display identified failure modes in formatted panels."""
    if verbose < 1:
        return
    
    # Display header panel
    header_panel = Panel(
        Text(f"Found {len(failure_modes)} distinct failure pattern{'s' if len(failure_modes) != 1 else ''}", style="bold yellow"),
        title="⚠️  IDENTIFIED FAILURE MODES",
        title_align="left",
        border_style="yellow",
        width=PANEL_WIDTH,
    )
    
    with console.capture() as capture:
        console.print(header_panel)
    
    rendered_header = capture.get()
    for line in rendered_header.splitlines():
        console.print(Text("│   ") + Text.from_ansi(line))
    
    console.print()
    
    for idx, failure_mode in enumerate(failure_modes, 1):
        # Create content for this failure mode
        mode_content = Text()
        mode_content.append(f"{failure_mode.name}\n\n", style="bold white")
        mode_content.append("Description:\n", style="cyan")
        mode_content.append(f"{failure_mode.description}\n\n")
        mode_content.append("Root Cause:\n", style="cyan")
        mode_content.append(f"{failure_mode.root_cause}")
        
        panel = Panel(
            mode_content,
            title=f"Failure Mode {idx}",
            title_align="left",
            border_style="red" if idx == 1 else "yellow",
            width=PANEL_WIDTH,
        )
        
        # Capture and prefix each line
        with console.capture() as capture:
            console.print(panel)
        
        rendered_panel = capture.get()
        for line in rendered_panel.splitlines():
            console.print(Text("│   ") + Text.from_ansi(line))
        
        if idx < len(failure_modes):
            console.print("│")


@contextmanager
def display_prompt_improvement(
    failure_mode_name: str,
    verbose: int = 1
) -> Iterator[Any]:
    """Context manager to display progress while generating improved prompt."""
    if verbose >= 1:
        console.print()
        console.print(Text("│   "))
        console.print(Text(f"│   Addressing: {failure_mode_name}", style="bold cyan"))
    
    class Reporter:
        def set_reasoning(self, reasoning: str) -> None:
            if verbose >= 1:
                reasoning_content = Text()
                reasoning_content.append("Improvement Strategy:\n", style="cyan")
                reasoning_content.append(reasoning)
                
                panel = Panel(
                    reasoning_content,
                    title="💡 Reasoning",
                    title_align="left",
                    border_style="blue",
                    width=PANEL_WIDTH - 10,
                    padding=(0, 1),
                )
                
                # Capture and prefix each line
                with console.capture() as capture:
                    console.print(panel)
                
                rendered_panel = capture.get()
                for line in rendered_panel.splitlines():
                    console.print(Text("│     ") + Text.from_ansi(line))
                
                console.print(Text("│   "))
    
    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich("│     Generating improved prompt", verbose=verbose):
                yield Reporter()
    finally:
        pass


def display_improvement_reasoning(
    failure_mode_name: str,
    reasoning: str,
    verbose: int = 1
) -> None:
    """Display prompt improvement reasoning for a specific failure mode."""
    if verbose < 1:
        return
    
    console.print()
    console.print(Text("│   "))
    console.print(Text(f"│   Addressing: {failure_mode_name}", style="bold cyan"))
    
    reasoning_content = Text()
    reasoning_content.append("Improvement Strategy:\n", style="cyan")
    reasoning_content.append(reasoning)
    
    panel = Panel(
        reasoning_content,
        title="💡 Reasoning",
        title_align="left",
        border_style="blue",
        width=PANEL_WIDTH - 10,
        padding=(0, 1),
    )
    
    # Capture and prefix each line
    with console.capture() as capture:
        console.print(panel)
    
    rendered_panel = capture.get()
    for line in rendered_panel.splitlines():
        console.print(Text("│     ") + Text.from_ansi(line))
    
    console.print(Text("│   "))


def display_iteration_improvement(
    improvement: float,
    current_score: float,
    best_score: float,
    verbose: int = 1
) -> None:
    """Display the improvement result for a failure mode iteration."""
    if verbose < 1:
        return
    
    if improvement > 0:
        console.print(Text(
            f"│   ✓ Improvement: {improvement:.2%} (from {best_score:.4f} to {current_score:.4f})",
            style="green bold"
        ))
    else:
        console.print(Text(
            f"│   ✗ No improvement: {improvement:.2%} (score: {current_score:.4f}, best: {best_score:.4f})",
            style="yellow"
        ))


def display_optimized_prompt_diff(
    initial_messages: list[dict[str, str]],
    optimized_messages: list[dict[str, str]],
    initial_score: float,
    best_score: float,
    verbose: int = 1,
) -> None:
    """Display git-style diff of prompt changes."""
    import difflib
    
    if verbose < 1:
        return
    
    console.print()
    console.print(Text("│"))
    console.print(Text("│ > Optimization Results", style="bold green"))
    console.print(Text("│"))
    
    # Show score improvement
    if best_score > initial_score:
        perc_change = (best_score - initial_score) / initial_score
        console.print(Text(
            f"│   Prompt improved from {initial_score:.4f} to {best_score:.4f} ({perc_change:.2%})",
            style="green"
        ))
    else:
        console.print(Text(
            f"│   No improvement found (score: {best_score:.4f})",
            style="yellow"
        ))
    
    console.print(Text("│"))
    console.print(Text("│   Prompt Changes:", style="cyan"))
    console.print(Text("│"))
    
    # Compare each message
    for idx in range(max(len(initial_messages), len(optimized_messages))):
        initial_msg = initial_messages[idx] if idx < len(initial_messages) else None
        optimized_msg = optimized_messages[idx] if idx < len(optimized_messages) else None
        
        # Get role from whichever message exists
        role = 'message'
        if initial_msg:
            role = initial_msg.get('role', 'message')
        elif optimized_msg:
            role = optimized_msg.get('role', 'message')
        
        initial_content = initial_msg.get('content', '') if initial_msg else ''
        optimized_content = optimized_msg.get('content', '') if optimized_msg else ''
        
        # Handle added messages
        if not initial_msg:
            console.print(Text(f"│     {role}: (added)", style="green bold"))
            for line in optimized_content.splitlines():
                console.print(Text(f"│       +{line}", style="green"))
            console.print(Text("│"))
            continue
        
        # Handle removed messages
        if not optimized_msg:
            console.print(Text(f"│     {role}: (removed)", style="red bold"))
            for line in initial_content.splitlines():
                console.print(Text(f"│       -{line}", style="red"))
            console.print(Text("│"))
            continue
        
        # Check if there are changes
        if initial_content == optimized_content:
            # No changes in this message
            console.print(Text(f"│     {role}: (unchanged)", style="dim"))
            continue
        
        # Generate unified diff
        diff_lines = list(difflib.unified_diff(
            initial_content.splitlines(keepends=False),
            optimized_content.splitlines(keepends=False),
            lineterm='',
            n=3  # 3 lines of context
        ))
        
        if not diff_lines:
            continue
        
        # Display message header
        console.print(Text(f"│     {role}:", style="bold cyan"))
        
        # Create diff content
        diff_content = Text()
        for line in diff_lines[3:]:  # Skip first 3 lines (---, +++, @@)
            if line.startswith('+'):
                diff_content.append("│       " + line + "\n", style="green")
            elif line.startswith('-'):
                diff_content.append("│       " + line + "\n", style="red")
            elif line.startswith('@@'):
                diff_content.append("│       " + line + "\n", style="cyan dim")
            else:
                # Context line
                diff_content.append("│       " + line + "\n", style="dim")
        
        console.print(diff_content)
        console.print(Text("│"))
