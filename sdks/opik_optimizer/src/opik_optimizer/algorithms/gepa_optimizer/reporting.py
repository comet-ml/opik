import json
from numbers import Number
from contextlib import contextmanager
from typing import Any

from rich.table import Table
from rich.text import Text
from rich.panel import Panel
from ...utils.reporting import convert_tqdm_to_rich, suppress_opik_logs
from ...utils.display import (
    display_text_block,
    format_prompt_snippet,
    display_renderable,
)
from ...api_objects import chat_prompt


# FIXME: Move to a pareto util/extension
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
        progress: Any | None = None,
        max_trials: int = 10,
    ) -> None:
        self.optimizer = optimizer
        self.verbose = verbose
        self.progress = progress
        self.max_trials = max_trials
        self.current_iteration = 0
        self._last_progress_value = 0
        self._last_best_score: float | None = None
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

        iteration_handled = self._handle_iteration_start(first)
        if iteration_handled:
            return

        # Check if this message should be suppressed (unless verbose >= 2)
        if self.verbose <= 1:
            for keyword in self.SUPPRESS_KEYWORDS:
                if keyword in first:
                    return

            for prefix in self.SUPPRESS_PREFIXES:
                if prefix in first:
                    return

        if self._handle_candidate_messages(first):
            return

        if self._handle_score_updates(first):
            return

        if self.verbose >= 2 and self._handle_verbose_pareto(first):
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
            display_text_block(f"│ {first}", style="dim")
            self._last_raw_message = first

    def _handle_iteration_start(self, first: str) -> bool:
        if not first.startswith("Iteration "):
            return False

        colon = first.find(":")
        head = first[:colon] if colon != -1 else first
        parts = head.split()
        if len(parts) < 2 or not parts[1].isdigit():
            return False

        try:
            iteration = int(parts[1])
        except Exception:
            return False

        if iteration > 0 and iteration != self.current_iteration:
            display_text_block("│")

        self.current_iteration = iteration
        self._last_raw_message = first

        if self.progress:
            self.progress.update_to(iteration)

        if "Base program full valset score" in first:
            score_match = first.split(":")[-1].strip()
            display_text_block(f"│ Baseline evaluation: {score_match}", style="bold")
            return True

        if "Selected program" in first:
            parts_info = first.split(":")
            if len(parts_info) > 1 and "Selected program" in parts_info[1]:
                program_info = parts_info[1].strip()
                score_info = parts_info[2].strip() if len(parts_info) > 2 else ""
                display_text_block(
                    f"│ Trial {iteration}: {program_info}, score: {score_info}",
                    style="bold cyan",
                )
            else:
                display_text_block(f"│ Trial {iteration}", style="bold cyan")
            display_text_block("│ ├─ Testing new prompt variant...")
            return True

        return False

    def _handle_candidate_messages(self, first: str) -> bool:
        if "Proposed new text" in first and "system_prompt:" in first:
            _, _, rest = first.partition("system_prompt:")
            snippet = format_prompt_snippet(rest, max_length=100)
            display_text_block(f"│ │  Proposed: {snippet}", style="dim")
            self._last_raw_message = first
            return True

        if "New subsample score" in first and "is not better than" in first:
            display_text_block("│ └─ Rejected - no improvement", style="dim yellow")
            display_text_block("│")
            self._last_raw_message = first
            return True

        if "New subsample score" in first and "is better than" in first:
            display_text_block(
                "│ ├─ Promising! Running full validation...", style="green"
            )
            self._last_raw_message = first
            return True

        if "Full valset score for new program" in first:
            parts = first.split(":")
            if len(parts) >= 2:
                score = parts[-1].strip()
                display_text_block(
                    f"│ ├─ Validation complete: {score}", style="bold green"
                )
            else:
                display_text_block("│ ├─ Validation complete", style="green")
            self._last_raw_message = first
            return True

        return False

    def _handle_score_updates(self, first: str) -> bool:
        if "Best score on train_val" in first:
            parts = first.split(":")
            if len(parts) >= 2:
                score = parts[-1].strip()
                display_text_block(f"│   Best train_val score: {score}", style="cyan")
                self._last_raw_message = first
            return True

        if (
            "Best valset aggregate score so far" in first
            or "Best score on valset" in first
        ):
            parts = first.split(":")
            if len(parts) >= 2:
                score_text = parts[-1].strip()
                try:
                    score_value = float(score_text)
                except ValueError:
                    score_value = None
                if score_value is not None and score_value != self._last_best_score:
                    display_text_block(
                        f"│ └─ New best: {score_text} ✓", style="bold green"
                    )
                    display_text_block("│")
                    self._last_best_score = score_value
                    self._last_raw_message = first
            return True

        return False

    def _handle_verbose_pareto(self, first: str) -> bool:
        if "New valset pareto front scores" in first:
            note = first.split(":", 1)[-1].strip()
            display_text_block(
                f"│   Pareto front scores updated: {_format_pareto_note(note)}",
                style="cyan",
            )
            self._last_raw_message = first
            return True
        if "Updated valset pareto front programs" in first:
            display_text_block("│   Pareto front programs updated", style="cyan")
            self._last_raw_message = first
            return True
        if "New program is on the linear pareto front" in first:
            display_text_block("│   Candidate added to Pareto front", style="cyan")
            self._last_raw_message = first
            return True
        return False


@contextmanager
def baseline_evaluation(verbose: int = 1) -> Any:
    if verbose >= 1:
        display_text_block("> Establishing baseline performance (seed prompt)")

    class Reporter:
        def set_score(self, s: float) -> None:
            if verbose >= 1:
                display_text_block(f"  Baseline score: {s:.4f}")

    with suppress_opik_logs():
        with convert_tqdm_to_rich("  Evaluation", verbose=verbose):
            yield Reporter()


@contextmanager
def start_gepa_optimization(verbose: int = 1, max_trials: int = 10) -> Any:
    if verbose >= 1:
        display_text_block("> Starting GEPA optimization")

    class Reporter:
        progress: Any | None = None

        def info(self, message: str) -> None:
            if verbose >= 1:
                display_text_block(f"│   {message}")

    class _GepaProgressAdapter:
        def __init__(self, tqdm_instance: Any) -> None:
            self._tqdm = tqdm_instance
            self._last_completed = 0

        def update_to(self, completed: int) -> None:
            total = getattr(self._tqdm, "total", None)
            if total is not None:
                completed = min(completed, int(total))
            delta = completed - self._last_completed
            if delta > 0:
                self._tqdm.update(delta)
                self._last_completed = completed

        def close(self) -> None:
            self._tqdm.close()

    with suppress_opik_logs():
        try:
            with convert_tqdm_to_rich("GEPA Optimization", verbose=verbose):
                if verbose >= 1:
                    import opik.evaluation.engine.evaluation_tasks_executor

                    tqdm_fn = opik.evaluation.engine.evaluation_tasks_executor._tqdm
                    tqdm_instance = tqdm_fn(total=max_trials, desc="GEPA Optimization")
                    Reporter.progress = _GepaProgressAdapter(tqdm_instance)

                yield Reporter()
        finally:
            if verbose >= 1:
                if Reporter.progress:
                    Reporter.progress.update_to(max_trials)
                    Reporter.progress.close()
                display_text_block("")


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

    display_renderable(table)


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
    display_renderable(panel)


def _format_score(value: Any) -> str:
    if value is None:
        return "[dim]—[/dim]"
    try:
        return f"{float(value):.4f}"
    except Exception:
        return str(value)


def candidate_summary_text(
    candidate: dict[str, str],
    base_prompts: dict[str, chat_prompt.ChatPrompt],
) -> str:
    """Get a summary text representation of a candidate for display."""
    for prompt_name in base_prompts:
        system_key = f"{prompt_name}_system_0"
        if system_key in candidate:
            return candidate[system_key][:200]
    for key, value in candidate.items():
        if not key.startswith("_") and key not in ("source", "id"):
            return str(value)[:200]
    return "<no content>"
