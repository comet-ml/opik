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

from typing import Any, List, Optional, Tuple

import click

FALLBACK_SUBCOMMAND = "_run"


class RunnerGroup(click.Group):
    """Click Group that falls back to a hidden `_run` subcommand.

    Set ``accepts_positional_after_run=True`` for groups whose ``_run``
    subcommand takes positional args (e.g. ``opik endpoint -- python script.py``).
    With it on, bare-word first tokens also route to ``_run`` so the user's
    real error ("Missing --project") surfaces instead of click's misleading
    "No such command 'python'". Leave it off for groups whose ``_run`` only
    takes options (``connect``), so subcommand typos like ``opik connect stp``
    surface as click's native "No such command 'stp'".
    """

    def __init__(
        self,
        *args: Any,
        accepts_positional_after_run: bool = False,
        **kwargs: Any,
    ) -> None:
        super().__init__(*args, **kwargs)
        self._accepts_positional_after_run = accepts_positional_after_run

    def resolve_command(
        self, ctx: click.Context, args: List[str]
    ) -> Tuple[Optional[str], Optional[click.Command], List[str]]:
        # Click >= 8.3 mutates `args` inside `Group.resolve_command` when the
        # first token starts with `-` (it re-runs `parse_args` to produce a
        # nicer error). Snapshot before the call so the fallback gets the
        # unmodified list.
        original_args = list(args)
        first_looks_like_option = bool(original_args) and original_args[0].startswith(
            "-"
        )
        try:
            return super().resolve_command(ctx, args)
        except click.UsageError:
            if not (first_looks_like_option or self._accepts_positional_after_run):
                raise
            return super().resolve_command(ctx, [FALLBACK_SUBCOMMAND, *original_args])
