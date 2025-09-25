from contextlib import contextmanager
from typing import Any

from rich.table import Table
from rich.text import Text
from rich.panel import Panel

from ..reporting_utils import (
    display_configuration,  # noqa: F401
    display_header,  # noqa: F401
    display_result,  # noqa: F401
    get_console,
    convert_tqdm_to_rich,
    suppress_opik_logs,
)

console = get_console()


class RichGEPAOptimizerLogger:
    """Adapter for GEPA's logger that provides concise Rich output."""

    SUPPRESS_PREFIXES = (
        "Linear pareto front program index",
        "New program candidate index",
    )

    def __init__(self, optimizer: Any, verbose: int = 1) -> None:
        self.optimizer = optimizer
        self.verbose = verbose

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

        if first.startswith("Iteration "):
            colon = first.find(":")
            head = first[:colon] if colon != -1 else first
            parts = head.split()
            if len(parts) >= 2 and parts[1].isdigit():
                try:
                    self.optimizer._gepa_current_iteration = int(parts[1])  # type: ignore[attr-defined]
                except Exception:
                    pass

        if "Proposed new text" in first and "system_prompt:" in first:
            _, _, rest = first.partition("system_prompt:")
            snippet = rest.strip()
            if len(snippet) > 120:
                snippet = snippet[:120] + "…"
            first = "Proposed new text · system_prompt: " + snippet
        elif len(first) > 160:
            first = first[:160] + "…"

        for prefix in self.SUPPRESS_PREFIXES:
            if prefix in first:
                return

        console.print(f"│ {first}")


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
def start_gepa_optimization(verbose: int = 1) -> Any:
    if verbose >= 1:
        console.print("> Starting GEPA optimization")

    class Reporter:
        def info(self, message: str) -> None:
            if verbose >= 1:
                console.print(f"│   {message}")

    try:
        yield Reporter()
    finally:
        if verbose >= 1:
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
) -> None:
    """Display the final selected candidate with its Opik score."""
    if verbose < 1:
        return

    snippet = system_prompt.strip() or "<empty>"
    text = Text(snippet)
    panel = Panel(
        text,
        title=f"{title} — Opik score {score:.4f}",
        border_style="green",
        expand=True,
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
