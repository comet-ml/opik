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
from typing import Any, Dict, Iterator, Optional

import click
from rich.console import Console
from rich.table import Table

import opik

from .audit import AuditLog, default_audit_path
from .checkpoint import MigrationCheckpoint, load_or_create
from .datasets.executor import execute_plan, record_planned
from .datasets.planner import (
    CascadeExperiments,
    CascadeOptimizations,
    CreateDestination,
    DiscardStaleTemp,
    PromoteDestination,
    RenameSource,
    ReplayVersions,
    build_dataset_plan,
)
from .errors import MigrationError, safe_error_string
from .prompts.executor import execute_plan as execute_prompt_plan
from .prompts.executor import record_planned as record_prompt_planned
from .prompts.planner import (
    CreateDestination as PromptCreateDestination,
)
from .prompts.planner import (
    RenameSource as PromptRenameSource,
)
from .prompts.planner import (
    ReplayVersions as PromptReplayVersions,
)
from .prompts.planner import (
    build_prompt_plan,
)

console = Console()
# Dedicated stderr console for the loud-fail path so the SKIP_SUMMARY
# line lands on stderr without flipping the default console (OPIK-6599).
# Tests assert against this stream; CI gates can grep stderr without
# parsing the audit JSON.
_stderr_console = Console(stderr=True)

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
        prompt     Migrate a prompt (and its full version history) into a destination project

    \b
    Examples:
        # Migrate a dataset (full version history + experiments cascade)
        opik migrate dataset "MyDataset" --to-project=B

        # Migrate a prompt (full version history)
        opik migrate prompt "MyPrompt" --to-project=B

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


def _finalize_with_skips_or_ok(
    audit: AuditLog,
    audit_path: Path,
    name: str,
    target_label: str,
    target_project: str,
    elapsed_seconds: float,
    experiments_excluded: bool = False,
    checkpoint: Optional[MigrationCheckpoint] = None,
) -> None:
    """Finalize the audit log, then either fail loud on skips or print the
    happy-path message.

    Per OPIK-6599: when the cascade emits any ``skip`` audit record, the
    migrate is "succeeded but lossy" — the destination state has partial
    data and is **not** rolled back. We finalize the audit to ``failed``,
    print a SKIP_SUMMARY line to **stderr** so CI pipelines can grep
    without parsing the JSON, and exit non-zero. Operators rely on the
    audit log to know what made it across.

    ``experiments_excluded`` (OPIK-7161) is orthogonal to the OPIK-6599
    skip machinery above: it's an intentional opt-out, not a lossy cascade,
    so it only annotates the happy-path success line. When
    ``--exclude-experiments`` is set there are no cascade actions and thus
    no ``skipped`` records, so this path is always the one taken.

    ``checkpoint`` (OPIK-7168) is deleted only on the clean-success path: a
    fully successful migration has nothing left to resume, so the local
    checkpoint file is removed. On the skip/loss path it is retained so a
    later re-run can still resume (the skipped experiments aren't marked done).
    """
    skip_records = [
        action for action in audit.actions if action.get("status") == "skipped"
    ]
    if not skip_records:
        audit.finalize("ok")
        audit.write(audit_path)
        if checkpoint is not None:
            checkpoint.delete()
        elapsed = _format_elapsed(elapsed_seconds)
        excluded_note = (
            " Experiments and optimizations were skipped (--exclude-experiments)."
            if experiments_excluded
            else ""
        )
        console.print(
            f"[green]Migrated '{name}' into project '{target_project}' as "
            f"'{target_label}'.[/green]{excluded_note} Took {elapsed}. "
            f"Audit log: {audit_path}"
        )
        return

    # Aggregate counts by reason for the SKIP_SUMMARY line. The cascade
    # summary record carries the totals too, but reading from skip records
    # directly keeps the message decoupled from the summary record's shape.
    totals: Dict[str, int] = {
        "experiments_skipped": 0,
        "items_skipped_missing_trace": 0,
        "items_skipped_missing_item": 0,
    }
    reason_to_total_key = {
        "experiment_recreate_returned_false": "experiments_skipped",
        "items_missing_trace_remap": "items_skipped_missing_trace",
        "items_missing_dataset_item_remap": "items_skipped_missing_item",
    }
    for record in skip_records:
        total_key = reason_to_total_key.get(record.get("reason", ""))
        if total_key is None:
            continue
        totals[total_key] += int(record.get("count", 0))

    total_skipped = sum(totals.values())
    audit.finalize("failed")
    audit.write(audit_path)

    elapsed = _format_elapsed(elapsed_seconds)
    _stderr_console.print(
        f"[red]opik migrate: {total_skipped} item{'s' if total_skipped != 1 else ''} "
        f"skipped — destination state was NOT rolled back; see audit log: "
        f"{audit_path}[/red]"
    )
    _stderr_console.print(
        f"SKIP_SUMMARY: "
        f"experiments_skipped={totals['experiments_skipped']} "
        f"items_skipped_missing_trace={totals['items_skipped_missing_trace']} "
        f"items_skipped_missing_item={totals['items_skipped_missing_item']}"
    )
    # High-level rollback hint. We deliberately don't ship a step-by-step
    # CLI playbook here -- the audit log is the source of truth for what
    # was actually created. Each ``ok`` action in ``audit.actions`` carries
    # the destination entity id; an operator can grep the audit JSON to
    # see exactly what landed in the destination project before deciding
    # what to delete. Auto-rollback (a one-flag clean reverse) is tracked
    # as a follow-up; this PR is the loud-fail mechanic only.
    _stderr_console.print(
        f"[yellow]To roll back manually: in project "
        f"'{target_project}', delete the destination dataset "
        f"'{target_label}' along with any experiments, optimizations, "
        f"traces, and spans that were cascaded into it (the audit log "
        f"lists each created entity id); then rename the source "
        f"'{name}_v1' back to '{name}'.[/yellow]"
    )
    _stderr_console.print(f"[dim](after {elapsed})[/dim]")
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
    help=(
        "Optional source project name. Omit to look up workspace-scoped "
        "datasets (V1 entities, or anything left at workspace scope after "
        "auto-migration). When provided, scopes the lookup to the named "
        "project for a smaller BE result set and a clearer not-found "
        "message."
    ),
)
@click.option(
    "--exclude-experiments",
    is_flag=True,
    help=(
        "Migrate the dataset and its full version history but skip all "
        "experiment (and optimization) migration. Opt-out and off by "
        "default; a plain run migrates experiments as before."
    ),
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
    exclude_experiments: bool,
    dry_run: bool,
    audit_log: Optional[Path],
) -> None:
    """Migrate a dataset (and its full version history) into --to-project.

    \b
    Steps performed (in order):
        1. Create the destination under a temp name ("<name>__migrating")
        2. Replay every source version onto the destination (full history)
        3. Cascade experiments + traces + spans into the destination project
        4. Rename source to "<name>_v1", then promote the destination to
           "<name>" — the name handoff runs only after the copy succeeds

    The source keeps its original name for the entire copy, so an interrupted
    run leaves the source untouched and only a discardable "<name>__migrating"
    dataset behind. Re-running with the original name is therefore safe and
    idempotent: a stale temp from a prior failed run is discarded and the
    copy restarts from scratch (OPIK-7162).

    Pass ``--exclude-experiments`` to stop before the cascade (step 3): the
    dataset and its versions migrate, but experiments and optimizations are
    skipped entirely (no discovery pass runs).

    If a migration is interrupted (crash, network drop, OOM), re-running the
    same command from the same machine resumes from the last completed
    experiment instead of restarting: already-completed experiments are
    skipped, and an experiment that was interrupted mid-flight has its partial
    destination data cleaned up before it is re-migrated (so no duplicates are
    left behind). Progress is checkpointed locally per experiment, keyed by
    workspace + destination project + dataset name, in a file next to the
    audit log; it is removed once the migration completes successfully.

    Dataset names are workspace-unique on the BE
    (``UNIQUE (workspace_id, name)``); ``--from-project`` is an
    optional source-scope hint that yields a smaller BE result set and
    a clearer not-found error message.
    """
    args = {
        "name": name,
        "to_project": to_project,
        "from_project": from_project,
        "exclude_experiments": exclude_experiments,
        "dry_run": dry_run,
    }
    audit = AuditLog(command="opik migrate dataset", args=args)
    audit_path = audit_log or default_audit_path()
    started_at = time.monotonic()
    checkpoint: Optional[MigrationCheckpoint] = None

    try:
        client = _build_client(ctx)
        _print_workspace_banner(client)
        if exclude_experiments:
            console.print(
                "[yellow]--exclude-experiments set: migrating dataset + "
                "versions only; experiments and optimizations will be "
                "skipped.[/yellow]"
            )
        # Checkpoint/resume (OPIK-7168) only applies to the experiment cascade,
        # so it's skipped when experiments are excluded (nothing to resume) and
        # on dry runs (no side effects to resume). Keyed by workspace +
        # destination project + dataset name and persisted locally so a re-run
        # from the same machine resumes. Loaded BEFORE the plan is built so the
        # planner can emit a cascade-only resume plan when a prior run already
        # finished the dataset phase.
        if not exclude_experiments and not dry_run:
            checkpoint = load_or_create(
                workspace=getattr(client, "_workspace", None) or "default",
                project=to_project,
                dataset=name,
            )
            if checkpoint is None:
                pass  # no resume support (location unresolvable); run uncheckpointed
            elif checkpoint.dataset_phase_done:
                console.print(
                    f"[blue]Resuming migration: dataset + versions already copied; "
                    f"{checkpoint.completed_count} experiment(s) completed on a "
                    f"prior run will be skipped, then the name handoff will "
                    f"finish.[/blue]"
                )
            elif checkpoint.completed_count > 0 or checkpoint.in_flight is not None:
                console.print(
                    f"[blue]Resuming migration: "
                    f"{checkpoint.completed_count} experiment(s) already "
                    f"completed on a prior run will be skipped.[/blue]"
                )

        plan = build_dataset_plan(
            client=client,
            name=name,
            to_project=to_project,
            from_project=from_project,
            exclude_experiments=exclude_experiments,
            resume_checkpoint=checkpoint,
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
            execute_plan(client, plan, audit, checkpoint=checkpoint)
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

    _finalize_with_skips_or_ok(
        audit,
        audit_path,
        name=name,
        target_label=plan.target_name,
        target_project=to_project,
        elapsed_seconds=time.monotonic() - started_at,
        experiments_excluded=exclude_experiments,
        checkpoint=checkpoint,
    )


@migrate_group.command(name="prompt")
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
    help=(
        "Optional source project name. Omit to look up workspace-scoped "
        "prompts (V1 entities, or anything left at workspace scope after "
        "auto-migration). When provided, scopes the lookup to the named "
        "project for a smaller BE result set and a clearer not-found "
        "message."
    ),
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
def migrate_prompt_command(
    ctx: click.Context,
    name: str,
    to_project: str,
    from_project: Optional[str],
    dry_run: bool,
    audit_log: Optional[Path],
) -> None:
    """Migrate a prompt (and its full version history) into --to-project.

    \b
    Steps performed (in order):
        1. Rename source to "<name>_v1"
        2. Create the destination prompt under --to-project
        3. Replay every source version onto the destination (full history,
           with source commit hashes preserved verbatim)

    Prompt names are workspace-unique on the BE
    (``UNIQUE (workspace_id, name)``); ``--from-project`` is an optional
    source-scope hint that yields a smaller BE result set and a clearer
    not-found error message.
    """
    args = {
        "name": name,
        "to_project": to_project,
        "from_project": from_project,
        "dry_run": dry_run,
    }
    audit = AuditLog(command="opik migrate prompt", args=args)
    audit_path = audit_log or default_audit_path()
    started_at = time.monotonic()

    try:
        client = _build_client(ctx)
        _print_workspace_banner(client)
        plan = build_prompt_plan(
            client=client,
            name=name,
            to_project=to_project,
            from_project=from_project,
        )

        _print_plan(plan)

        if dry_run:
            record_prompt_planned(plan, audit)
            audit.finalize("planned")
            audit.write(audit_path)
            console.print(
                f"[blue]Dry run complete. Audit log written to {audit_path}[/blue]"
            )
            return

        execute_prompt_plan(client, plan, audit)
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


def _print_plan(plan: Any) -> None:
    """Render the migration plan as a Rich table.

    Handles both dataset and prompt action records. The two action sets
    share the same field names where they overlap (``from_name`` /
    ``to_name`` on rename/promote; ``name`` / ``project_name`` on create).
    The replay source-name attribute differs (datasets read the source's
    original name — ``source_name`` — because OPIK-7162 defers the rename;
    prompts still rename up-front and expose ``source_name_after_rename``),
    so it's read with ``getattr`` fallback.
    """
    table = Table(title="Migration plan")
    table.add_column("#", justify="right")
    table.add_column("Action")
    table.add_column("Detail")
    for idx, action in enumerate(plan.actions, start=1):
        if isinstance(action, DiscardStaleTemp):
            table.add_row(
                str(idx),
                "discard stale temp",
                f"delete leftover '{action.temp_name}'",
            )
        elif isinstance(action, (RenameSource, PromptRenameSource)):
            table.add_row(
                str(idx), "rename source", f"{action.from_name} → {action.to_name}"
            )
        elif isinstance(action, PromoteDestination):
            table.add_row(
                str(idx),
                "promote destination",
                f"{action.from_name} → {action.to_name}",
            )
        elif isinstance(action, (CreateDestination, PromptCreateDestination)):
            table.add_row(
                str(idx),
                "create destination",
                f"{action.name} (project: {action.project_name})",
            )
        elif isinstance(action, (ReplayVersions, PromptReplayVersions)):
            replay_from = getattr(action, "source_name", None) or getattr(
                action, "source_name_after_rename", None
            )
            table.add_row(
                str(idx),
                "replay versions",
                f"{replay_from} → {action.dest_name} (full history)",
            )
        elif isinstance(action, CascadeOptimizations):
            table.add_row(
                str(idx),
                "cascade optimizations",
                f"optimizations → project {action.dest_project_name}",
            )
        elif isinstance(action, CascadeExperiments):
            table.add_row(
                str(idx),
                "cascade experiments",
                f"experiments + traces + spans → project {action.dest_project_name}",
            )
    console.print(table)
