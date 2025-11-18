import json
from numbers import Number
from contextlib import contextmanager
from typing import Any

from rich.table import Table
from rich.text import Text
from rich.panel import Panel
from rich.progress import (
    Progress,
    SpinnerColumn,
    TextColumn,
    BarColumn,
    TimeRemainingColumn,
    MofNCompleteColumn,
)

from ...reporting_utils import (  # noqa: F401
    display_configuration,
    display_header,
    display_result,
    get_console,
    convert_tqdm_to_rich,
    format_prompt_snippet,
    suppress_opik_logs,
)

console = get_console()


def _format_pareto_note(note: str) -> str:
    try:
        data = json.loads(note)
    except json.JSONDecodeError:
        return note

    if isinstance(data, dict):
        parts: list[str] = []
        new_scores = data.get("new_scores") or data.get("scores")
        if isinstance(new_scores, list):
            formatted_scores = ", ".join(
                f"{float(score) if isinstance(score, (int, float)) else float(str(score)):.3f}"
                if isinstance(score, Number)
                else str(score)
                for score in new_scores
            )
            parts.append(f"scores=[{formatted_scores}]")

        chosen = data.get("chosen")
        if chosen is not None:
            parts.append(f"chosen={chosen}")

        train_val = data.get("pareto_front_train_val_score")
        if isinstance(train_val, dict) and chosen is not None:
            chosen_entry = train_val.get(str(chosen))
            if isinstance(chosen_entry, dict):
                score = chosen_entry.get("score")
                if isinstance(score, Number):
                    parts.append(
                        f"train_val={float(score) if isinstance(score, (int, float)) else float(str(score)):.3f}"
                    )

        pareto_front = data.get("pareto_front")
        if isinstance(pareto_front, dict):
            parts.append(f"front_size={len(pareto_front)}")

        if parts:
            return ", ".join(parts)

        return note

    elif isinstance(data, list):
        return ", ".join(
            f"{float(item) if isinstance(item, (int, float)) else float(str(item)):.3f}"
            if isinstance(item, Number)
            else str(item)
            for item in data
        )

    elif isinstance(data, Number):
        return (
            f"{float(data) if isinstance(data, (int, float)) else float(str(data)):.3f}"
        )

    return str(data)


class RichGEPAOptimizerLogger:
    """Adapter for GEPA's logger that provides concise Rich output with progress tracking."""

    SUPPRESS_PREFIXES = (
        "Linear pareto front program index",
        "New program candidate index",
    )

    # Additional messages to suppress (too technical for users)
    SUPPRESS_KEYWORDS = (
        "Individual valset scores for new program",
        "New valset pareto front scores",
        "Updated valset pareto front programs",
        "Best program as per aggregate score on train_val",
        "Best program as per aggregate score on valset",
        "New program is on the linear pareto front",
        "Full train_val score for new program",
    )

    def __init__(
        self,
        optimizer: Any,
        verbose: int = 1,
        progress: Progress | None = None,
        task_id: Any | None = None,
        max_trials: int = 10,
    ) -> None:
        self.optimizer = optimizer
        self.verbose = verbose
        self.progress = progress
        self.task_id = task_id
        self.max_trials = max_trials
        self.current_iteration = 0
        self._last_best_message: tuple[str, str] | None = None
        self._last_raw_message: str | None = None

    def log(self, message: str) -> None:
        if self.verbose < 1:
            return

        msg = (message or "").strip()
        if not msg:
            return

        lines = [ln.strip() for ln in msg.splitlines() if ln.strip()]
        if not lines:
            return

        first = lines[0]

        if first == self._last_raw_message:
            return

        # Reset duplicate tracker when handling other messages
        if not first.startswith("Best "):
            self._last_best_message = None

        # Track iteration changes and add separation
        if first.startswith("Iteration "):
            colon = first.find(":")
            head = first[:colon] if colon != -1 else first
            parts = head.split()
            if len(parts) >= 2 and parts[1].isdigit():
                try:
                    iteration = int(parts[1])

                    # Add separator when starting a new iteration (except iteration 0)
                    if iteration > 0 and iteration != self.current_iteration:
                        console.print("│")

                    self.optimizer._gepa_current_iteration = iteration  # type: ignore[attr-defined]
                    self.current_iteration = iteration
                    self._last_raw_message = first

                    # Update progress bar
                    if self.progress and self.task_id is not None:
                        self.progress.update(self.task_id, completed=iteration)

                    # Add explanatory text for iteration start
                    if "Base program full valset score" in first:
                        # Extract score
                        score_match = first.split(":")[-1].strip()
                        console.print(
                            f"│ Baseline evaluation: {score_match}", style="bold"
                        )
                        return
                    elif "Selected program" in first:
                        # Extract program number and score
                        parts_info = first.split(":")
                        if "Selected program" in parts_info[1]:
                            program_info = parts_info[1].strip()
                            score_info = (
                                parts_info[2].strip() if len(parts_info) > 2 else ""
                            )
                            console.print(
                                f"│ Trial {iteration}: {program_info}, score: {score_info}",
                                style="bold cyan",
                            )
                        else:
                            console.print(f"│ Trial {iteration}", style="bold cyan")
                        console.print("│ ├─ Testing new prompt variant...")
                        return
                except Exception:
                    pass

        # Check if this message should be suppressed (unless verbose >= 2)
        if self.verbose <= 1:
            for keyword in self.SUPPRESS_KEYWORDS:
                if keyword in first:
                    return

            for prefix in self.SUPPRESS_PREFIXES:
                if prefix in first:
                    return

        # Format proposed prompts
        if "Proposed new text" in first and "system_prompt:" in first:
            _, _, rest = first.partition("system_prompt:")
            snippet = format_prompt_snippet(rest, max_length=100)
            console.print(f"│ │  Proposed: {snippet}", style="dim")
            self._last_raw_message = first
            return

        # Format subsample evaluation results
        if "New subsample score" in first and "is not better than" in first:
            console.print("│ └─ Rejected - no improvement", style="dim yellow")
            console.print("│")  # Add spacing after rejected trials
            self._last_raw_message = first
            return

        elif "New subsample score" in first and "is better than" in first:
            console.print("│ ├─ Promising! Running full validation...", style="green")
            self._last_raw_message = first
            return

        # Format final validation score
        if "Full valset score for new program" in first:
            # Extract score
            parts = first.split(":")
            if len(parts) >= 2:
                score = parts[-1].strip()
                console.print(f"│ ├─ Validation complete: {score}", style="bold green")
            else:
                console.print("│ ├─ Validation complete", style="green")
            self._last_raw_message = first
            return

        # Format best score updates
        if "Best score on train_val" in first:
            parts = first.split(":")
            if len(parts) >= 2:
                score = parts[-1].strip()
                console.print(f"│   Best train_val score: {score}", style="cyan")
                self._last_raw_message = first
            return

        if (
            "Best valset aggregate score so far" in first
            or "Best score on valset" in first
        ):
            # Extract score
            parts = first.split(":")
            if len(parts) >= 2:
                score = parts[-1].strip()
                key = ("new_best", score)
                if self._last_best_message != key:
                    console.print(f"│ └─ New best: {score} ✓", style="bold green")
                    console.print("│")  # Add spacing after successful trials
                    self._last_best_message = key
                    self._last_raw_message = first
            return

        if self.verbose >= 2:
            if "New valset pareto front scores" in first:
                note = first.split(":", 1)[-1].strip()
                console.print(
                    f"│   Pareto front scores updated: {_format_pareto_note(note)}",
                    style="cyan",
                )
                self._last_raw_message = first
                return
            if "Updated valset pareto front programs" in first:
                console.print("│   Pareto front programs updated", style="cyan")
                self._last_raw_message = first
                return
            if "New program is on the linear pareto front" in first:
                console.print("│   Candidate added to Pareto front", style="cyan")
                self._last_raw_message = first
                return

        # Suppress redundant "Iteration X:" prefix from detailed messages
        if first.startswith(f"Iteration {self.current_iteration}:"):
            # Remove the iteration prefix for cleaner output
            first = first.split(":", 1)[1].strip() if ":" in first else first

        # Truncate very long messages
        if len(first) > 160:
            first = first[:160] + "…"

        # Default: print with standard prefix only if not already handled
        if first:
            console.print(f"│ {first}", style="dim")
            self._last_raw_message = first


@contextmanager
def baseline_evaluation(verbose: int = 1) -> Any:
    if verbose >= 1:
        console.print("> Establishing baseline performance (seed prompt)")

    class Reporter:
        def set_score(self, s: float) -> None:
            if verbose >= 1:
                console.print(f"  Baseline score: {s:.4f}")

    with suppress_opik_logs():
        with convert_tqdm_to_rich("  Evaluation", verbose=verbose):
            yield Reporter()


@contextmanager
def start_gepa_optimization(verbose: int = 1, max_trials: int = 10) -> Any:
    if verbose >= 1:
        console.print("> Starting GEPA optimization")

    class Reporter:
        progress: Progress | None = None
        task_id: Any | None = None

        def info(self, message: str) -> None:
            if verbose >= 1:
                console.print(f"│   {message}")

    with suppress_opik_logs():
        try:
            # Create Rich progress bar
            if verbose >= 1:
                Reporter.progress = Progress(
                    SpinnerColumn(),
                    TextColumn("[bold blue]{task.description}"),
                    BarColumn(),
                    MofNCompleteColumn(),
                    TextColumn("•"),
                    TimeRemainingColumn(),
                    console=console,
                    transient=True,  # Make progress bar disappear when done
                )
                Reporter.progress.start()
                Reporter.task_id = Reporter.progress.add_task(
                    "GEPA Optimization", total=max_trials
                )

            yield Reporter()
        finally:
            if verbose >= 1:
                if Reporter.progress and Reporter.task_id is not None:
                    # Mark as complete before stopping
                    Reporter.progress.update(Reporter.task_id, completed=max_trials)
                    Reporter.progress.stop()
                console.print("")


def display_candidate_scores(
    rows: list[dict[str, Any]],
    *,
    verbose: int = 1,
    title: str = "GEPA Candidate Scores",
) -> None:
    """Render a summary table comparing GEPA's scores with Opik rescoring."""
    if verbose < 1 or not rows:
        return

    table = Table(title=title, show_lines=False, expand=True)
    table.add_column("#", justify="right", style="cyan")
    table.add_column("Source", style="dim")
    table.add_column("System Prompt", overflow="fold", ratio=2)
    table.add_column("GEPA Score", justify="right")
    table.add_column("Opik Score", justify="right", style="green")

    for row in rows:
        snippet = str(row.get("system_prompt", "")).replace("\n", " ")
        snippet = snippet[:200] + ("…" if len(snippet) > 200 else "")
        table.add_row(
            str(row.get("iteration", "")),
            str(row.get("source", "")),
            snippet or "[dim]<empty>[/dim]",
            _format_score(row.get("gepa_score")),
            _format_score(row.get("opik_score")),
        )

    console.print(table)


def display_selected_candidate(
    system_prompt: str,
    score: float,
    *,
    verbose: int = 1,
    title: str = "Selected Candidate",
    trial_info: dict[str, Any] | None = None,
) -> None:
    """Display the final selected candidate with its Opik score."""
    if verbose < 1:
        return

    snippet = system_prompt.strip() or "<empty>"
    text = Text(snippet)
    subtitle: Text | None = None
    if trial_info:
        trial_parts: list[str] = []
        trial_name = trial_info.get("experiment_name")
        trial_ids = trial_info.get("trial_ids") or []
        if trial_name:
            trial_parts.append(f"Trial {trial_name}")
        elif trial_ids:
            trial_parts.append(f"Trial {trial_ids[0]}")

        compare_url = trial_info.get("compare_url")
        experiment_url = trial_info.get("experiment_url")
        if compare_url:
            trial_parts.append(f"[link={compare_url}]Compare run[/link]")
        elif experiment_url:
            trial_parts.append(f"[link={experiment_url}]View experiment[/link]")

        if trial_parts:
            subtitle = Text.from_markup(" • ".join(trial_parts))

    panel = Panel(
        text,
        title=f"{title} — Opik score {score:.4f}",
        border_style="green",
        expand=True,
        subtitle=subtitle,
        subtitle_align="left",
    )
    console.print(panel)


def _format_score(value: Any) -> str:
    if value is None:
        return "[dim]—[/dim]"
    try:
        return f"{float(value):.4f}"
    except Exception:
        return str(value)


def display_candidate_update(
    iteration: int | None,
    phase: str,
    aggregate: float | None,
    prompt_snippet: str,
    *,
    verbose: int = 1,
) -> None:
    if verbose < 1:
        return
    iter_label = f"Iter {iteration}" if iteration is not None else "Candidate"
    agg_str = f"{aggregate:.4f}" if isinstance(aggregate, (int, float)) else "—"
    snippet = prompt_snippet.replace("\n", " ")
    if len(snippet) > 100:
        snippet = snippet[:100] + "…"
    console.print(f"│   {iter_label}: [{phase}] agg={agg_str} prompt={snippet}")
