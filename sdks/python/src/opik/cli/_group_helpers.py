"""Shared helpers for the top-level Click groups (``export``, ``import``, ``copy``).

These groups all present their subcommands as data-type "Items" (datasets,
experiments, etc.) rather than the default Click "Commands" header. Keeping
the formatter in one place avoids drift across the three groups.
"""

from typing import Callable

import click


def items_format_commands(
    self: click.Group, ctx: click.Context, formatter: click.HelpFormatter
) -> None:
    """Click ``format_commands`` override that renders subcommands under ``Items``.

    Bound onto a group via :func:`bind_items_format_commands` so ``--help``
    output reads ``Items: dataset …`` instead of ``Commands: dataset …``.
    """
    commands = []
    for subcommand in self.list_commands(ctx):
        cmd = self.get_command(ctx, subcommand)
        if cmd is None or cmd.hidden:
            continue
        commands.append((subcommand, cmd))

    if len(commands):
        limit = formatter.width - 6 - max(len(cmd[0]) for cmd in commands)
        rows = []
        for subcommand, cmd in commands:
            help_text = cmd.get_short_help_str(limit)
            rows.append((subcommand, help_text))

        if rows:
            with formatter.section("Items"):
                formatter.write_dl(rows)


def bind_items_format_commands(group: click.Group) -> None:
    """Install :func:`items_format_commands` as ``group.format_commands``."""
    bound: Callable[..., None] = items_format_commands.__get__(group, type(group))
    setattr(group, "format_commands", bound)
