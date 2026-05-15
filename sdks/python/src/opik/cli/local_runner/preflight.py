"""Pre-flight checks for `opik connect` / `opik endpoint`.

Decisions made before the pairing session starts:

* :func:`should_create_project` — should we auto-create the project when the
  backend lookup returns 404, or hand back to the formatted-error path?
* :func:`maybe_auto_configure` — should we walk the user through
  ``opik configure`` because no config file is on disk?

These were originally private helpers in ``_run.py``; they live here so tests
can target a public surface rather than reaching into an underscore-prefixed
implementation module.
"""

import logging
import os
import sys
from typing import Optional, Tuple

import click

from opik.api_objects.rest_helpers import resolve_project_id_by_name
from opik.config import OpikConfig
from opik.rest_api.core.api_error import ApiError

LOGGER = logging.getLogger(__name__)


def should_create_project(
    api: object, project_name: str, workspace: Optional[str], headless: bool
) -> Tuple[bool, bool]:
    """Decide whether to auto-create the project if pairing finds it missing.

    Headless callers (e.g. Ollie spawning `opik endpoint --headless`) cannot
    answer a prompt, so we create silently. Interactive callers see a
    confirmation prompt only when the project is confirmed-missing (404).
    Any other lookup error falls through so the downstream resolver can
    surface the formatted error.

    Returns ``(create_if_missing, known_missing)`` so the downstream resolver
    can skip a redundant lookup when interactive preflight already observed
    the 404. Headless skips the lookup here, so ``known_missing`` is False
    even when we want to create — the resolver has to look up first to stay
    idempotent across re-runs.
    """
    if headless:
        return True, False
    try:
        resolve_project_id_by_name(api, project_name)
        return False, False
    except ApiError as e:
        if e.status_code != 404:
            return False, False
    if not sys.stdin.isatty():
        return False, False
    workspace_label = f" in workspace '{workspace}'" if workspace else ""
    confirmed = click.confirm(
        f"Project '{project_name}'{workspace_label} does not exist. Create it?",
        default=True,
    )
    return confirmed, confirmed


def maybe_auto_configure(
    api_key_arg: Optional[str], non_interactive: bool, headless: bool
) -> None:
    """Walk the user through ``opik configure`` if no config is on disk.

    Skipped when the caller is non-interactive (``--non-interactive``,
    ``--headless``, no TTY) or when credentials are already supplied via
    ``--api-key`` or ``OPIK_API_KEY``. In those cases the downstream
    "no config" error message is the right user feedback.
    """
    if non_interactive or headless:
        return
    if api_key_arg or os.environ.get("OPIK_API_KEY"):
        return
    if not sys.stdin.isatty():
        return
    probe = OpikConfig()
    if probe.config_file_exists or probe.api_key:
        return

    # Lazy import — avoids dragging the configurator module into the CLI's
    # cold-start path unless we actually need it here.
    from ..configure import run_interactive_configure

    click.echo("No Opik config file found. Running `opik configure` first.\n")
    run_interactive_configure()
    click.echo()
