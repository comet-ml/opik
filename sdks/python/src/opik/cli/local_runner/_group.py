"""Shared Click Group with subcommand fallback for `connect` and `endpoint`.

Both runner CLIs need a Click Group so they can host a `stop` subcommand, but
want their legacy default form (`opik <type> [options] [-- cmd]`) to keep
working. Click normally requires the first non-option token to be a
subcommand name, which would either collide with the legacy form's `--`
positional (endpoint) or force `--project` to live as an `Optional[str]` on
the group with manual validation (connect).

This subclass catches the resulting "no such subcommand" UsageError and
re-resolves with the sentinel `_run` subcommand prepended — that subcommand
owns the legacy options and (for endpoint) the positional command argument.
"""

from typing import List, Optional, Tuple

import click

FALLBACK_SUBCOMMAND = "_run"


class RunnerGroup(click.Group):
    def resolve_command(
        self, ctx: click.Context, args: List[str]
    ) -> Tuple[Optional[str], Optional[click.Command], List[str]]:
        # Click >= 8.3 mutates `args` inside `Group.resolve_command` when the
        # first token looks like an option (it re-runs `parse_args` to produce
        # a nicer error). Snapshot the original args so the fallback receives
        # the unmodified list.
        original_args = list(args)
        try:
            return super().resolve_command(ctx, args)
        except click.UsageError:
            return super().resolve_command(
                ctx, [FALLBACK_SUBCOMMAND, *original_args]
            )
