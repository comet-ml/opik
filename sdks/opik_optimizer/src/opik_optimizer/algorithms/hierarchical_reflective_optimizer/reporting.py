from contextlib import contextmanager
from typing import Any, Literal
from collections.abc import Iterator
from dataclasses import dataclass

from rich.panel import Panel
from rich.text import Text

from ...api_objects import chat_prompt
from ...reporting_utils import (  # noqa: F401
    convert_tqdm_to_rich,
    display_configuration,
    display_header,
    display_messages,
    display_result,
    get_console,
    suppress_opik_logs,
    safe_percentage_change,
)

PANEL_WIDTH = 90
console = get_console()


@dataclass
class MessageDiffItem:
    """Represents a single message's diff information."""

    role: str
    change_type: Literal["added", "removed", "unchanged", "changed"]
    initial_content: str | None
    optimized_content: str | None


def _content_to_string(content: str | list[dict[str, Any]]) -> str:
    """
    Convert message content to string representation for diff display.
    Handles both string content and multimodal content parts.

    Args:
        content: Message content, either a string or a list of content parts

    Returns:
        String representation of the content
    """
    if isinstance(content, str):
        return content

    # Handle multimodal content (list of parts)
    parts: list[str] = []
    for part in content:
        part_type = part.get("type")
        if part_type == "text":
            text_content = part.get("text", "")
            if text_content:
                parts.append(f"[text] {text_content}")
        elif part_type == "image_url":
            image_url_data = part.get("image_url", {})
            url = (
                image_url_data.get("url", "")
                if isinstance(image_url_data, dict)
                else ""
            )
            if url:
                # Truncate long URLs or base64 data
                if url.startswith("data:image"):
                    if "," in url:
                        base64_part = url.split(",", 1)[1]
                        preview = (
                            base64_part[:20] + "..."
                            if len(base64_part) > 20
                            else base64_part
                        )
                        parts.append(f"[image_url] data:image/...;base64,{preview}")
                    else:
                        parts.append(f"[image_url] {url[:50]}...")
                else:
                    display_url = url[:80] + "..." if len(url) > 80 else url
                    parts.append(f"[image_url] {display_url}")
            else:
                parts.append("[image_url] <no URL>")

    return "\n".join(parts) if parts else "(empty content)"


def compute_message_diff_order(
    initial_messages: list[dict[str, str]],
    optimized_messages: list[dict[str, str]],
) -> list[MessageDiffItem]:
    """
    Compute the diff between initial and optimized messages, returning them in optimized message order.

    This function groups messages by role and compares them to determine what changed.
    The returned list maintains the order of roles as they appear in the optimized messages.

    Args:
        initial_messages: List of initial message dictionaries with 'role' and 'content' keys
        optimized_messages: List of optimized message dictionaries with 'role' and 'content' keys

    Returns:
        List of MessageDiffItem objects in the order roles appear in optimized_messages,
        followed by any removed roles that only existed in initial_messages.
    """

    def group_by_role(
        messages: list[dict[str, str]],
    ) -> dict[str, list[tuple[int, str]]]:
        """Group messages by role, storing (index, content) tuples."""
        groups: dict[str, list[tuple[int, str]]] = {}
        for idx, msg in enumerate(messages):
            role = msg.get("role", "message")
            content = msg.get("content", "")
            if role not in groups:
                groups[role] = []
            groups[role].append((idx, content))
        return groups

    initial_by_role = group_by_role(initial_messages)
    optimized_by_role = group_by_role(optimized_messages)

    # Get all unique roles maintaining order from optimized messages
    all_roles = []
    seen_roles = set()
    for msg in optimized_messages:
        role = msg.get("role", "message")
        if role not in seen_roles:
            all_roles.append(role)
            seen_roles.add(role)
    # Add any roles that were in initial but not in optimized (removed roles)
    for msg in initial_messages:
        role = msg.get("role", "message")
        if role not in seen_roles:
            all_roles.append(role)
            seen_roles.add(role)

    # Build diff items for each role
    diff_items: list[MessageDiffItem] = []
    for role in all_roles:
        initial_content = (
            initial_by_role[role][0][1] if role in initial_by_role else None
        )
        optimized_content = (
            optimized_by_role[role][0][1] if role in optimized_by_role else None
        )

        if initial_content is None and optimized_content is not None:
            change_type: Literal["added", "removed", "unchanged", "changed"] = "added"
        elif initial_content is not None and optimized_content is None:
            change_type = "removed"
        else:
            # Normalize content to strings for comparison to handle both string and multimodal content
            initial_str = (
                _content_to_string(initial_content)
                if initial_content is not None
                else ""
            )
            optimized_str = (
                _content_to_string(optimized_content)
                if optimized_content is not None
                else ""
            )
            if initial_str == optimized_str:
                change_type = "unchanged"
            else:
                change_type = "changed"

        diff_items.append(
            MessageDiffItem(
                role=role,
                change_type=change_type,
                initial_content=initial_content,
                optimized_content=optimized_content,
            )
        )

    return diff_items


def display_retry_attempt(
    attempt: int,
    max_attempts: int,
    failure_mode_name: str,
    verbose: int = 1,
) -> None:
    """Display retry attempt information."""
    if verbose >= 1:
        console.print(
            Text("│    ").append(
                Text(
                    f"Retry attempt {attempt + 1}/{max_attempts} for failure mode '{failure_mode_name}' (no improvement observed)",
                    style="yellow",
                )
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
    baseline_score: float | None = None,
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
                score_indent = "│ " if indent == "> " else "│   "

                if baseline_score is None:
                    # This is the baseline evaluation
                    console.print(
                        Text(score_indent).append(
                            Text(
                                f"Baseline score was: {s:.4f}.",
                                style="green",
                            )
                        )
                    )
                    console.print(Text("│"))
                else:
                    # This is an improved prompt evaluation - show comparison
                    if s > baseline_score:
                        improvement_pct = (
                            ((s - baseline_score) / baseline_score * 100)
                            if baseline_score > 0
                            else 0
                        )
                        console.print(
                            Text(score_indent).append(
                                Text(
                                    f"Score for updated prompt: {s:.4f} (+{improvement_pct:.1f}%)",
                                    style="green bold",
                                )
                            )
                        )
                    elif s < baseline_score:
                        decline_pct = (
                            ((baseline_score - s) / baseline_score * 100)
                            if baseline_score > 0
                            else 0
                        )
                        console.print(
                            Text(score_indent).append(
                                Text(
                                    f"Score for updated prompt: {s:.4f} (-{decline_pct:.1f}%)",
                                    style="red",
                                )
                            )
                        )
                    else:
                        console.print(
                            Text(score_indent).append(
                                Text(
                                    f"Score for updated prompt: {s:.4f} (no change)",
                                    style="yellow",
                                )
                            )
                        )
                    console.print(Text("│"))

    # Use our log suppression context manager and yield the reporter
    # Adjust progress bar indentation based on indent style
    progress_indent = "│ Evaluation" if indent == "> " else "│   Evaluation"
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
                        Text(
                            f"│             Evaluation score: {score:.4f}",
                            style="green",
                        )
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
        console.print(Text("│"))
        console.print(Text("│"))
        console.print(
            Text("│ ").append(Text(f"Iteration {iteration}", style="bold cyan"))
        )

    class Reporter:
        def iteration_complete(self, best_score: float, improved: bool) -> None:
            if verbose >= 1:
                if improved:
                    console.print(
                        Text("│ ").append(
                            Text(
                                f"Iteration {iteration} complete - New best score: {best_score:.4f}",
                                style="green",
                            )
                        )
                    )
                else:
                    console.print(
                        Text("│ ").append(
                            Text(
                                f"Iteration {iteration} complete - No improvement (best: {best_score:.4f})",
                                style="yellow",
                            )
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
        console.print(
            Text("│   ").append(
                Text("Analyzing root cause of failed evaluation items", style="cyan")
            )
        )

    class Reporter:
        def set_completed(self, total_test_cases: int, num_batches: int) -> None:
            if verbose >= 1:
                console.print(
                    Text("│   ").append(
                        Text(
                            f"Analyzed {total_test_cases} test cases across {num_batches} batches",
                            style="green",
                        )
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
        console.print(
            Text("│   ").append(Text("Synthesizing failure modes", style="cyan"))
        )

    class Reporter:
        def set_completed(self, num_unified_modes: int) -> None:
            # No completion message needed - failure modes will be displayed next
            pass

    with suppress_opik_logs():
        yield Reporter()


def display_hierarchical_synthesis(
    total_test_cases: int, num_batches: int, synthesis_notes: str, verbose: int = 1
) -> None:
    """Display hierarchical analysis synthesis information in a box."""
    if verbose < 1:
        return

    synthesis_content = Text()
    synthesis_content.append(
        f"Analyzed {total_test_cases} test cases across {num_batches} batches\n\n",
        style="bold",
    )
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

    # Prefix each line with '│ ', preserving ANSI styles
    prefixed_output = "\n".join(f"│ {line}" for line in rendered_panel.splitlines())

    # Print the prefixed output (will include colors)
    console.print(prefixed_output, highlight=False)
    console.print(Text("│"))


def display_failure_modes(failure_modes: list[Any], verbose: int = 1) -> None:
    """Display identified failure modes in formatted panels."""
    if verbose < 1:
        return

    # Display header panel
    header_panel = Panel(
        Text(
            f"Found {len(failure_modes)} distinct failure pattern{'s' if len(failure_modes) != 1 else ''}",
            style="bold yellow",
        ),
        title="⚠️  IDENTIFIED FAILURE MODES",
        title_align="left",
        border_style="yellow",
        width=PANEL_WIDTH,
    )

    with console.capture() as capture:
        console.print(header_panel)

    rendered_header = capture.get()

    # Prefix each line with '│   ', preserving ANSI styles
    prefixed_output = "\n".join(f"│   {line}" for line in rendered_header.splitlines())

    # Print the prefixed output (will include colors)
    console.print(prefixed_output, highlight=False)
    console.print(Text("│"))

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

        # Prefix each line with '│   ', preserving ANSI styles
        prefixed_output = "\n".join(
            f"│   {line}" for line in rendered_panel.splitlines()
        )

        # Print the prefixed output (will include colors)
        console.print(prefixed_output, highlight=False)

        if idx < len(failure_modes):
            console.print("│")


@contextmanager
def display_prompt_improvement(
    failure_mode_name: str, verbose: int = 1
) -> Iterator[Any]:
    """Context manager to display progress while generating improved prompt."""
    if verbose >= 1:
        console.print(Text("│"))
        console.print(Text("│   "))
        console.print(
            Text("│   ").append(
                Text(f"Addressing: {failure_mode_name}", style="bold cyan")
            )
        )

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

                # Prefix each line with '│     ', preserving ANSI styles
                prefixed_output = "\n".join(
                    f"│     {line}" for line in rendered_panel.splitlines()
                )

                # Print the prefixed output (will include colors)
                console.print(prefixed_output, highlight=False)
                console.print(Text("│   "))

    try:
        with suppress_opik_logs():
            with convert_tqdm_to_rich(
                "│     Generating improved prompt", verbose=verbose
            ):
                yield Reporter()
    finally:
        pass


def display_improvement_reasoning(
    failure_mode_name: str, reasoning: str, verbose: int = 1
) -> None:
    """Display prompt improvement reasoning for a specific failure mode."""
    if verbose < 1:
        return

    console.print(Text("│"))
    console.print(Text("│   "))
    console.print(
        Text("│   ").append(Text(f"Addressing: {failure_mode_name}", style="bold cyan"))
    )

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

    # Prefix each line with '│     ', preserving ANSI styles
    prefixed_output = "\n".join(f"│     {line}" for line in rendered_panel.splitlines())

    # Print the prefixed output (will include colors)
    console.print(prefixed_output, highlight=False)
    console.print(Text("│   "))


def display_iteration_improvement(
    improvement: float, current_score: float, best_score: float, verbose: int = 1
) -> None:
    """Display the improvement result for a failure mode iteration."""
    if verbose < 1:
        return

    if improvement > 0:
        console.print(
            Text("│   ").append(
                Text(
                    f"✓ Improvement: {improvement:.2%} (from {best_score:.4f} to {current_score:.4f})",
                    style="green bold",
                )
            )
        )
    else:
        console.print(
            Text("│   ").append(
                Text(
                    f"✗ No improvement: {improvement:.2%} (score: {current_score:.4f}, best: {best_score:.4f})",
                    style="yellow",
                )
            )
        )


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

    console.print(Text("│"))
    console.print(Text("│"))
    console.print(Text("│ ").append(Text("> Optimization Results", style="bold green")))
    console.print(Text("│"))

    # Show score improvement
    if best_score > initial_score:
        perc_change, has_percentage = safe_percentage_change(best_score, initial_score)
        if has_percentage:
            console.print(
                Text("│   ").append(
                    Text(
                        f"Prompt improved from {initial_score:.4f} to {best_score:.4f} ({perc_change:.2%})",
                        style="green",
                    )
                )
            )
        else:
            console.print(
                Text("│   ").append(
                    Text(
                        f"Prompt improved from {initial_score:.4f} to {best_score:.4f}",
                        style="green",
                    )
                )
            )
    else:
        console.print(
            Text("│   ").append(
                Text(f"No improvement found (score: {best_score:.4f})", style="yellow")
            )
        )

    console.print(Text("│"))
    console.print(Text("│   ").append(Text("Prompt Changes:", style="cyan")))
    console.print(Text("│"))

    # Compute diff items using the extracted function
    diff_items = compute_message_diff_order(initial_messages, optimized_messages)

    # Display each diff item
    for item in diff_items:
        if item.change_type == "added":
            # Role was added
            console.print(
                Text("│     ").append(Text(f"{item.role}: (added)", style="green bold"))
            )
            assert item.optimized_content is not None
            optimized_str = _content_to_string(item.optimized_content)
            for line in optimized_str.splitlines():
                console.print(Text("│       ").append(Text(f"+{line}", style="green")))
            console.print(Text("│"))
        elif item.change_type == "removed":
            # Role was removed
            console.print(
                Text("│     ").append(Text(f"{item.role}: (removed)", style="red bold"))
            )
            assert item.initial_content is not None
            initial_str = _content_to_string(item.initial_content)
            for line in initial_str.splitlines():
                console.print(Text("│       ").append(Text(f"-{line}", style="red")))
            console.print(Text("│"))
        elif item.change_type == "unchanged":
            # No changes
            console.print(
                Text("│     ").append(Text(f"{item.role}: (unchanged)", style="dim"))
            )
        else:  # changed
            # Content changed - show diff
            console.print(
                Text("│     ").append(
                    Text(f"{item.role}: (changed)", style="cyan bold")
                )
            )

            assert item.initial_content is not None
            assert item.optimized_content is not None

            # Convert content to strings for diffing
            initial_str = _content_to_string(item.initial_content)
            optimized_str = _content_to_string(item.optimized_content)

            # Generate unified diff
            diff_lines = list(
                difflib.unified_diff(
                    initial_str.splitlines(keepends=False),
                    optimized_str.splitlines(keepends=False),
                    lineterm="",
                    n=3,  # 3 lines of context
                )
            )

            # Check if there are actual diff lines (more than just the header)
            if len(diff_lines) > 3:
                # Create diff content
                diff_content = Text()
                for line in diff_lines[3:]:  # Skip first 3 lines (---, +++, @@)
                    if line.startswith("+"):
                        diff_content.append("│       " + line + "\n", style="green")
                    elif line.startswith("-"):
                        diff_content.append("│       " + line + "\n", style="red")
                    elif line.startswith("@@"):
                        diff_content.append("│       " + line + "\n", style="cyan dim")
                    else:
                        # Context line
                        diff_content.append("│       " + line + "\n", style="dim")

                console.print(diff_content)
            elif initial_str != optimized_str:
                # Content changed but diff is empty (might be whitespace or formatting)
                # Show both versions for clarity
                console.print(
                    Text("│       ").append(
                        Text("(content changed but diff unavailable)", style="dim")
                    )
                )
                console.print(
                    Text("│       ").append(Text(f"-{initial_str}", style="red"))
                )
                console.print(
                    Text("│       ").append(Text(f"+{optimized_str}", style="green"))
                )
            console.print(Text("│"))
