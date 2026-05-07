"""``opik migrate`` Click group and subcommands.

Slice 1: ``opik migrate dataset NAME --to-project=B`` migrates a dataset
entity and its current items into the destination project. ``opik migrate
plan`` is the workspace-wide survey surface — slice 1 lists candidate
datasets and what the per-dataset migration would do by default.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

import click
from rich.console import Console
from rich.table import Table

import opik
from opik.api_objects import rest_helpers

from .audit import AuditLog, default_audit_path
from .errors import MigrationError, safe_error_string
from .executor import execute_plan, record_planned
from .planner import (
    CopyCurrentItems,
    CopyTestSuiteConfig,
    CreateDestination,
    DeleteSource,
    MigrationPlan,
    RenameSource,
    build_dataset_plan,
)

console = Console()

MIGRATE_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}

DEFAULT_SOURCE_SUFFIX = "_v1"


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
        dataset    Migrate a dataset (and its current items) into a destination project
        plan       Workspace-wide survey of what would be migrated (no side effects)

    \b
    Examples:
        # Migrate a dataset into project B (renames source to "<name>_v1")
        opik migrate dataset "MyDataset" --to-project=B

        # Preview a single migration without touching the backend
        opik migrate dataset "MyDataset" --to-project=B --dry-run

        # Survey the workspace
        opik migrate plan

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
    prefix: str,
) -> None:
    """Shared exception path: write audit log + print sanitized error + exit.

    ``user_facing`` distinguishes ``MigrationError`` (already user-friendly,
    print verbatim) from generic exceptions (sanitize via
    ``safe_error_string`` so ``ApiError`` response bodies/headers don't leak
    to the terminal). Always finalises the audit log to ``failed`` first so
    the on-disk record matches what the operator saw.
    """
    audit.finalize("failed")
    audit.write(audit_path)
    if user_facing:
        console.print(f"[red]{exc}[/red]")
    else:
        console.print(f"[red]{prefix}: {safe_error_string(exc)}[/red]")
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
    "--target-name",
    type=str,
    default=None,
    help="Override the destination dataset name. When set, the source is NOT renamed.",
)
@click.option(
    "--source-suffix",
    type=str,
    default=DEFAULT_SOURCE_SUFFIX,
    show_default=True,
    help="Suffix appended to the source name during the rename step.",
)
@click.option(
    "--delete-source",
    is_flag=True,
    help="Delete the renamed source dataset after a successful copy.",
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
    target_name: Optional[str],
    source_suffix: str,
    delete_source: bool,
    dry_run: bool,
    audit_log: Optional[Path],
) -> None:
    """Migrate a dataset (and its current items) into --to-project.

    \b
    Steps performed (in order):
        1. Rename source to "<name>{--source-suffix}" (skipped when --target-name is set)
        2. Create the destination dataset under --to-project
        3. Copy the source's current items into the destination
        4. Optionally delete the renamed source (--delete-source)
    """
    args = {
        "name": name,
        "to_project": to_project,
        "from_project": from_project,
        "target_name": target_name,
        "source_suffix": source_suffix,
        "delete_source": delete_source,
        "dry_run": dry_run,
    }
    audit = AuditLog(command="opik migrate dataset", args=args)
    audit_path = audit_log or default_audit_path()

    try:
        client = _build_client(ctx)
        _print_workspace_banner(client)
        plan = build_dataset_plan(
            rest_client=client.rest_client,
            name=name,
            to_project=to_project,
            from_project=from_project,
            target_name=target_name,
            source_suffix=source_suffix,
            delete_source=delete_source,
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

        execute_plan(client, plan, audit)
    except MigrationError as exc:
        _finalize_and_fail(
            audit, audit_path, exc, user_facing=True, prefix="Migration failed"
        )
    except Exception as exc:
        _finalize_and_fail(
            audit, audit_path, exc, user_facing=False, prefix="Migration failed"
        )

    audit.finalize("ok")
    audit.write(audit_path)
    console.print(
        f"[green]Migrated '{name}' into project '{to_project}' as '{plan.target_name}'.[/green] "
        f"Audit log: {audit_path}"
    )


@migrate_group.command(name="plan")
@click.option(
    "--from-project",
    type=str,
    default=None,
    help="Restrict the survey to a single source project. Omit for workspace-scoped datasets.",
)
@click.option(
    "--audit-log",
    type=click.Path(dir_okay=False, writable=True, path_type=Path),
    default=None,
    help="Path to write the audit-log JSON file (default: ./opik-migrate-<timestamp>.json).",
)
@click.pass_context
def migrate_plan_command(
    ctx: click.Context,
    from_project: Optional[str],
    audit_log: Optional[Path],
) -> None:
    """Survey the workspace for datasets eligible for migration (no side effects).

    Slice 1 lists every dataset in scope and shows what ``opik migrate dataset
    <name> --to-project=<...>`` would do by default. Slices 2-4 will extend the
    output with version, experiment, and optimization counts.
    """
    args = {"from_project": from_project}
    audit = AuditLog(command="opik migrate plan", args=args)
    audit_path = audit_log or default_audit_path()

    try:
        client = _build_client(ctx)
        _print_workspace_banner(client)
        rest_client = client.rest_client
        project_id = rest_helpers.resolve_project_id_by_name_optional(
            rest_client, project_name=from_project
        )

        datasets = []
        page = 1
        while True:
            response = rest_client.datasets.find_datasets(
                page=page, size=100, project_id=project_id
            )
            if not response.content:
                break
            datasets.extend(response.content)
            if len(response.content) < 100:
                break
            page += 1

        scope_label = (
            f"project '{from_project}'"
            if from_project
            else "the workspace (no project)"
        )
        if not datasets:
            console.print(f"[yellow]No datasets found in {scope_label}.[/yellow]")
            audit.finalize("planned")
            audit.write(audit_path)
            return

        table = Table(title=f"Datasets in {scope_label}")
        table.add_column("Name")
        table.add_column("Items", justify="right")
        table.add_column("Default migration")
        for d in datasets:
            table.add_row(
                d.name,
                str(d.dataset_items_count or 0),
                f"rename to '{d.name}{DEFAULT_SOURCE_SUFFIX}', copy current items",
            )
            audit.record(
                type="plan_candidate",
                status="planned",
                details={
                    "dataset": d.name,
                    "items": d.dataset_items_count or 0,
                    "from_project": from_project,
                },
            )
        console.print(table)
        console.print(
            "[blue]Run `opik migrate <workspace> dataset <name> --to-project=<dest>` to migrate one.[/blue]"
        )

        audit.finalize("planned")
        audit.write(audit_path)
    except MigrationError as exc:
        _finalize_and_fail(
            audit, audit_path, exc, user_facing=True, prefix="Plan failed"
        )
    except Exception as exc:
        _finalize_and_fail(
            audit, audit_path, exc, user_facing=False, prefix="Plan failed"
        )


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
        elif isinstance(action, CopyCurrentItems):
            table.add_row(
                str(idx),
                "copy items",
                f"{action.source_name_after_rename} → {action.dest_name}",
            )
        elif isinstance(action, CopyTestSuiteConfig):
            table.add_row(
                str(idx),
                "copy suite config",
                f"latest evaluators + execution_policy → {action.dest_name}",
            )
        elif isinstance(action, DeleteSource):
            table.add_row(
                str(idx),
                "delete source",
                action.name_after_rename,
            )
    console.print(table)
