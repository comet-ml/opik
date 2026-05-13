"""``opik migrate`` Click group and the ``dataset`` subcommand.

Slice 1 surface: ``opik migrate dataset NAME --to-project=B`` migrates a
dataset entity (and its current items) into the destination project, in a
single workspace. The follow-up slices extend this surface; this slice
deliberately keeps it minimal.
"""

from __future__ import annotations

import contextlib
import logging
import sys
import time
from pathlib import Path
from typing import Iterator, Optional

import click
from rich.console import Console
from rich.table import Table

import opik

from .audit import AuditLog, default_audit_path
from .datasets.executor import execute_plan, record_planned
from .datasets.planner import (
    CascadeExperiments,
    CreateDestination,
    MigrationPlan,
    RenameSource,
    ReplayVersions,
    build_dataset_plan,
)
from .errors import MigrationError, safe_error_string

console = Console()

MIGRATE_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


@contextlib.contextmanager
def _quiet_streamer_rate_limit_logs() -> Iterator[None]:
    """Suppress the streamer's per-message INFO log on ingestion 429s.

    The streamer's queue_consumer logs every ingestion rate-limit retry
    at INFO level, including the full HTTP response headers dict (cookies,
    AWS load-balancer markers, rate-limit telemetry). During a cascade
    that writes thousands of traces/spans, the message bus can hit dozens
    of 429s and each one dumps a long line that drowns out the Rich
    progress bars.

    The streamer's retry logic still functions identically -- we only
    raise the *log threshold* for that one logger during the migrate
    command. Other SDK consumers (and the SDK's own behaviour) are
    untouched. ``WARNING`` keeps real ingestion errors visible while
    silencing the routine retries.
    """
    queue_consumer_logger = logging.getLogger("opik.message_processing.queue_consumer")
    previous_level = queue_consumer_logger.level
    queue_consumer_logger.setLevel(logging.WARNING)
    try:
        yield
    finally:
        queue_consumer_logger.setLevel(previous_level)


@click.group(name="migrate", context_settings=MIGRATE_CONTEXT_SETTINGS)
@click.option(
    "--workspace",
    type=str,
    default=None,
    help=(
        "Workspace name. Falls back to OPIK_WORKSPACE env var, then "
        "~/.opik.config, then 'default'."
    ),
)
@click.option(
    "--api-key",
    type=str,
    help="Opik API key. If not provided, will use OPIK_API_KEY environment variable or configuration.",
)
@click.pass_context
def migrate_group(
    ctx: click.Context, workspace: Optional[str], api_key: Optional[str]
) -> None:
    """Migrate Opik entities between projects (within a single workspace).

    \b
    Commands:
        dataset    Migrate a dataset (and its full version history) into a destination project

    \b
    Examples:
        # Migrate a dataset (full version history + experiments cascade)
        opik migrate dataset "MyDataset" --to-project=B

        # Preview the migration without touching the backend
        opik migrate dataset "MyDataset" --to-project=B --dry-run

        # Override the configured workspace per-invocation
        opik migrate --workspace=production dataset "MyDataset" --to-project=B
    """
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace
    ctx.obj["api_key"] = api_key or (
        ctx.parent.obj.get("api_key") if ctx.parent and ctx.parent.obj else None
    )


def _build_client(ctx: click.Context) -> opik.Opik:
    """Construct the Opik client.

    ``workspace=None`` means defer to the SDK's resolution chain
    (OPIK_WORKSPACE env -> ~/.opik.config -> 'default').
    """
    workspace = ctx.obj.get("workspace")
    api_key = ctx.obj.get("api_key")
    kwargs = {}
    if workspace is not None:
        kwargs["workspace"] = workspace
    if api_key is not None:
        kwargs["api_key"] = api_key
    return opik.Opik(**kwargs)


def _print_workspace_banner(client: opik.Opik) -> None:
    """Print the workspace the client resolved to before any side effect.

    Because workspace can come from any of four sources (flag/env/config/
    default), printing the resolved name removes ambiguity for destructive
    operations.
    """
    resolved = getattr(client, "_workspace", None) or "default"
    console.print(f"[blue]Workspace:[/blue] {resolved}")


def _finalize_and_fail(
    audit: AuditLog,
    audit_path: Path,
    exc: BaseException,
    *,
    user_facing: bool,
    elapsed_seconds: float,
) -> None:
    """Shared exception path: write audit log + print sanitized error + exit.

    ``user_facing`` distinguishes ``MigrationError`` (already user-friendly,
    print verbatim) from generic exceptions (sanitize via
    ``safe_error_string`` so ``ApiError`` response bodies/headers don't leak
    to the terminal). Always finalises the audit log to ``failed`` first so
    the on-disk record matches what the operator saw.

    ``elapsed_seconds`` lets the operator see how long they waited before
    the failure surfaced -- useful for debugging long-running migrates
    that die at the cascade tail.
    """
    audit.finalize("failed")
    audit.write(audit_path)
    elapsed = _format_elapsed(elapsed_seconds)
    if user_facing:
        console.print(f"[red]{exc}[/red] [dim](after {elapsed})[/dim]")
    else:
        console.print(
            f"[red]Migration failed: {safe_error_string(exc)}[/red] "
            f"[dim](after {elapsed})[/dim]"
        )
    sys.exit(1)


@migrate_group.command(name="dataset")
@click.argument("name", type=str)
@click.option(
    "--to-project",
    required=True,
    type=str,
    help="Destination project name (required).",
)
@click.option(
    "--from-project",
    type=str,
    default=None,
    help="Source project name. Omit to look up workspace-scoped datasets.",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Preview the migration without applying any changes.",
)
@click.option(
    "--audit-log",
    type=click.Path(dir_okay=False, writable=True, path_type=Path),
    default=None,
    help="Path to write the audit-log JSON file (default: ./opik-migrate-<timestamp>.json).",
)
@click.pass_context
def migrate_dataset_command(
    ctx: click.Context,
    name: str,
    to_project: str,
    from_project: Optional[str],
    dry_run: bool,
    audit_log: Optional[Path],
) -> None:
    """Migrate a dataset (and its full version history) into --to-project.

    \b
    Steps performed (in order):
        1. Rename source to "<name>_v1"
        2. Create the destination dataset under --to-project
        3. Replay every source version onto the destination (full history)
        4. Cascade experiments + traces + spans into the destination project
    """
    args = {
        "name": name,
        "to_project": to_project,
        "from_project": from_project,
        "dry_run": dry_run,
    }
    audit = AuditLog(command="opik migrate dataset", args=args)
    audit_path = audit_log or default_audit_path()
    started_at = time.monotonic()

    try:
        client = _build_client(ctx)
        _print_workspace_banner(client)
        plan = build_dataset_plan(
            client=client,
            name=name,
            to_project=to_project,
            from_project=from_project,
        )

        _print_plan(plan)

        if dry_run:
            record_planned(plan, audit)
            audit.finalize("planned")
            audit.write(audit_path)
            console.print(
                f"[blue]Dry run complete. Audit log written to {audit_path}[/blue]"
            )
            return

        with _quiet_streamer_rate_limit_logs():
            execute_plan(client, plan, audit)
    except MigrationError as exc:
        _finalize_and_fail(
            audit,
            audit_path,
            exc,
            user_facing=True,
            elapsed_seconds=time.monotonic() - started_at,
        )
    except Exception as exc:
        _finalize_and_fail(
            audit,
            audit_path,
            exc,
            user_facing=False,
            elapsed_seconds=time.monotonic() - started_at,
        )

    audit.finalize("ok")
    audit.write(audit_path)
    elapsed = _format_elapsed(time.monotonic() - started_at)
    console.print(
        f"[green]Migrated '{name}' into project '{to_project}' as '{plan.target_name}'.[/green] "
        f"Took {elapsed}. Audit log: {audit_path}"
    )


def _format_elapsed(seconds: float) -> str:
    """Render a wall-clock duration as ``Hh Mm Ss`` / ``Mm Ss`` / ``Ss``.

    Sub-minute runs show ``12.3s`` (one decimal). Anything past a minute
    drops the decimal -- once you're in minutes you don't care about
    fractional seconds, and ``5m 12s`` reads cleaner than ``5m 12.3s``.
    """
    if seconds < 60:
        return f"{seconds:.1f}s"
    total = int(seconds)
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    if hours:
        return f"{hours}h {minutes}m {secs}s"
    return f"{minutes}m {secs}s"


def _print_plan(plan: MigrationPlan) -> None:
    table = Table(title="Migration plan")
    table.add_column("#", justify="right")
    table.add_column("Action")
    table.add_column("Detail")
    for idx, action in enumerate(plan.actions, start=1):
        if isinstance(action, RenameSource):
            table.add_row(
                str(idx), "rename source", f"{action.from_name} → {action.to_name}"
            )
        elif isinstance(action, CreateDestination):
            table.add_row(
                str(idx),
                "create destination",
                f"{action.name} (project: {action.project_name})",
            )
        elif isinstance(action, ReplayVersions):
            table.add_row(
                str(idx),
                "replay versions",
                f"{action.source_name_after_rename} → {action.dest_name} (full history)",
            )
        elif isinstance(action, CascadeExperiments):
            table.add_row(
                str(idx),
                "cascade experiments",
                f"experiments + traces + spans → project {action.dest_project_name}",
            )
    console.print(table)
