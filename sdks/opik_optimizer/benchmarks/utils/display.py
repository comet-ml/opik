from __future__ import annotations

from rich.text import Text


def render_active_task_line(
    dataset_name: str,
    optimizer_name: str,
    model_name: str,
    short_id: str | None,
) -> Text:
    del model_name
    if short_id:
        return Text.assemble(
            " • ",
            (f"#{short_id} ", "dim"),
            (f"{dataset_name}", "yellow"),
            (f" [{optimizer_name}]", "dim"),
        )
    return Text.assemble(
        " • ",
        (f"{dataset_name}", "yellow"),
        (f" [{optimizer_name}]", "dim"),
    )
