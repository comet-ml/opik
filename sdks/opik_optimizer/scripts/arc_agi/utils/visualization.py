"""Rich-powered visualization helpers for ARC-AGI grids."""

from __future__ import annotations

from collections.abc import Sequence

from rich.columns import Columns
from rich.panel import Panel
from rich.text import Text

from .logging_utils import CONSOLE

_PALETTE = [
    "#ffffff",  # 0 (white)
    "#1f77b4",  # 1
    "#d62728",  # 2
    "#2ca02c",  # 3
    "#ff7f0e",  # 4
    "#9467bd",  # 5
    "#17becf",  # 6
    "#999999",  # 7
    "#8c564b",  # 8
    "#e377c2",  # 9
]


def _color_for_value(value: int) -> str:
    """Map a palette index to a Rich color string."""
    idx = int(value)
    if idx < 0 or idx >= len(_PALETTE):
        return _PALETTE[0]
    return _PALETTE[idx]


def render_grid(grid: Sequence[Sequence[int]]) -> Text:
    """Return a Rich ``Text`` object showing the grid as colored blocks."""
    text = Text()
    for row in grid:
        for cell in row:
            text.append("██", style=_color_for_value(int(cell)))
        text.append("\n")
    return text


def grid_panel(grid: Sequence[Sequence[int]], title: str) -> Panel:
    """Wrap ``render_grid`` output inside a Rich ``Panel`` with a title."""
    return Panel(render_grid(grid), title=title, border_style="white")


def print_task_preview(
    train_examples: Sequence[dict],
    test_inputs: Sequence[Sequence[Sequence[int]]],
) -> None:
    """Render sample train/test grids for debug mode."""
    if train_examples:
        CONSOLE.print("\nSample ARC-AGI-2 grids (train examples):")
        for idx, example in enumerate(train_examples):
            panels = [
                grid_panel(example.get("input", []), f"train input #{idx}"),
                grid_panel(example.get("output", []), f"train output #{idx}"),
            ]
            CONSOLE.print(Columns(panels, expand=True, padding=2))
    if test_inputs:
        CONSOLE.print("\nSample ARC-AGI-2 grids (test inputs):")
        for idx, grid in enumerate(test_inputs):
            CONSOLE.print(
                Columns(
                    [grid_panel(grid, f"test input #{idx}")],
                    expand=True,
                    padding=2,
                )
            )


def print_grid_triplet(
    input_grid: Sequence[Sequence[int]],
    expected_grid: Sequence[Sequence[int]],
    predicted_grid: Sequence[Sequence[int]],
    *,
    label: str | None = None,
) -> None:
    """Show input/expected/predicted grids side by side."""
    panels = [
        grid_panel(input_grid, "input"),
        grid_panel(expected_grid, "expected"),
        grid_panel(predicted_grid, "predicted"),
    ]
    header = (
        label
        or "Best candidate vs expected for test[0] (input | expected | predicted):"
    )
    CONSOLE.print(f"\n{header}")
    CONSOLE.print(Columns(panels, expand=True, padding=2))


__all__ = [
    "CONSOLE",
    "grid_panel",
    "print_task_preview",
    "print_grid_triplet",
    "render_grid",
]
