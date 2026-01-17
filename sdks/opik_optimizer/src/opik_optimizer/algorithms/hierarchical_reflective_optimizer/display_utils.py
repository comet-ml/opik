"""Display helpers for Hierarchical Reflective optimizer diffs."""

from __future__ import annotations

from typing import Any, Literal

from rich.text import Text

from ...utils.display import display_text_block, safe_percentage_change
from ...utils.multimodal import content_to_string
from .types import MessageDiffItem


# FIXME: This needs to be centralized as we need this on all optimizers.
def compute_message_diff_order(
    initial_messages: list[dict[str, str]],
    optimized_messages: list[dict[str, str]],
) -> list[MessageDiffItem]:
    """
    Compute the diff between initial and optimized messages, returning them in optimized message order.
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

    all_roles: list[str] = []
    seen_roles: set[str] = set()
    for msg in optimized_messages:
        role = msg.get("role", "message")
        if role not in seen_roles:
            all_roles.append(role)
            seen_roles.add(role)
    for msg in initial_messages:
        role = msg.get("role", "message")
        if role not in seen_roles:
            all_roles.append(role)
            seen_roles.add(role)

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
            initial_str = (
                content_to_string(initial_content)
                if initial_content is not None
                else ""
            )
            optimized_str = (
                content_to_string(optimized_content)
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


# FIXME: This needs to be centralized as we need this on all optimizers.
def display_optimized_prompt_diff(
    console: Any,
    initial_messages: list[dict[str, str]],
    optimized_messages: list[dict[str, str]],
    initial_score: float,
    best_score: float,
    verbose: int = 1,
    prompt_name: str | None = None,
) -> None:
    """Display git-style diff of prompt changes."""
    import difflib

    if verbose < 1:
        return

    display_text_block(console, "│")
    display_text_block(console, "│")

    if prompt_name:
        display_text_block(
            console, f"│ > Optimization Results for '{prompt_name}'", "bold green"
        )
    else:
        display_text_block(console, "│ > Optimization Results", "bold green")
    display_text_block(console, "│")

    if initial_messages == optimized_messages:
        display_text_block(console, "│   Prompt unchanged", "dim")
        display_text_block(console, "│")
        return

    if best_score > initial_score:
        perc_change, has_percentage = safe_percentage_change(best_score, initial_score)
        if has_percentage:
            display_text_block(
                console,
                f"│   Prompt improved from {initial_score:.4f} to {best_score:.4f} ({perc_change:.2%})",
                "green",
            )
        else:
            display_text_block(
                console,
                f"│   Prompt improved from {initial_score:.4f} to {best_score:.4f}",
                "green",
            )
    else:
        display_text_block(
            console,
            f"│   No improvement found (score: {best_score:.4f})",
            "yellow",
        )

    display_text_block(console, "│")
    display_text_block(console, "│   Prompt Changes:", "cyan")
    display_text_block(console, "│")

    diff_items = compute_message_diff_order(initial_messages, optimized_messages)

    for item in diff_items:
        if item.change_type == "added":
            display_text_block(console, f"│     {item.role}: (added)", "green bold")
            assert item.optimized_content is not None
            optimized_str = content_to_string(item.optimized_content)
            for line in optimized_str.splitlines():
                console.print(Text("│       ").append(Text(f"+{line}", style="green")))
            display_text_block(console, "│")
        elif item.change_type == "removed":
            display_text_block(console, f"│     {item.role}: (removed)", "red bold")
            assert item.initial_content is not None
            initial_str = content_to_string(item.initial_content)
            for line in initial_str.splitlines():
                console.print(Text("│       ").append(Text(f"-{line}", style="red")))
            display_text_block(console, "│")
        elif item.change_type == "unchanged":
            display_text_block(console, f"│     {item.role}: (unchanged)", "dim")
        else:
            display_text_block(console, f"│     {item.role}: (changed)", "cyan bold")

            assert item.initial_content is not None
            assert item.optimized_content is not None

            initial_str = content_to_string(item.initial_content)
            optimized_str = content_to_string(item.optimized_content)

            diff_lines = list(
                difflib.unified_diff(
                    initial_str.splitlines(keepends=False),
                    optimized_str.splitlines(keepends=False),
                    lineterm="",
                    n=3,
                )
            )

            if len(diff_lines) > 3:
                diff_content = Text()
                for line in diff_lines[3:]:
                    if line.startswith("+"):
                        diff_content.append("│       " + line + "\n", style="green")
                    elif line.startswith("-"):
                        diff_content.append("│       " + line + "\n", style="red")
                    elif line.startswith("@@"):
                        diff_content.append("│       " + line + "\n", style="cyan dim")
                    else:
                        diff_content.append("│       " + line + "\n", style="dim")

                console.print(diff_content)
            elif initial_str != optimized_str:
                display_text_block(
                    console,
                    "│       (content changed but diff unavailable)",
                    "dim",
                )
                console.print(
                    Text("│       ").append(Text(f"-{initial_str}", style="red"))
                )
                console.print(
                    Text("│       ").append(Text(f"+{optimized_str}", style="green"))
                )
            display_text_block(console, "│")
