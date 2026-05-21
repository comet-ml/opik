"""Shared `stop` action for ``opik connect`` and ``opik endpoint``.

Reads the local pid lock files written by Supervisor, picks the ones matching
the requested filter (``--project``/``--runner``/``--all``), and signals each
to exit. SIGTERM goes through Supervisor's shutdown handler which (since
OPIK-6497) notifies the backend so the FE flips off on the next poll.
SIGKILL is reserved for runners that haven't exited after the grace window —
those skip the backend notification and the heartbeat-TTL reaper picks them up
~15s later.
"""

import logging
import os
import signal
import time
from typing import List, Optional, Tuple

import click

from opik.runner import pid_file

from . import pairing

LOGGER = logging.getLogger(__name__)

# How long to wait for a SIGTERM'd supervisor to clean up before escalating to
# SIGKILL. The supervisor's own graceful child-stop budget is 5s, plus the bounded
# disconnect-notify call (~2s) and TUI teardown, so 10s is a comfortable ceiling.
_SIGTERM_GRACE_SECONDS = 10.0
_POLL_INTERVAL_SECONDS = 0.2


def do_stop(
    *,
    runner_type: pairing.RunnerType,
    project_name: Optional[str],
    all_flag: bool,
    runner_id_filter: Optional[str],
) -> None:
    """Find local runners of `runner_type` matching the filter and stop them."""
    _require_one_filter(project_name, all_flag, runner_id_filter)

    candidates = pid_file.list_all(runner_type=runner_type.value)
    matched = _filter(candidates, project_name, all_flag, runner_id_filter)

    if not matched:
        click.echo(_no_match_message(runner_type, project_name, runner_id_filter))
        return

    stopped: List[pid_file.RunnerInfo] = []
    failed: List[Tuple[pid_file.RunnerInfo, str]] = []
    for info in matched:
        ok, reason = _signal_until_gone(info)
        if ok:
            # Drop the pid file ourselves: a SIGKILL'd or already-dead process
            # never reaches the supervisor's own `finally`-block cleanup.
            # On failure we leave the file in place so a subsequent
            # `opik <type> stop` can rediscover the still-alive supervisor.
            pid_file.remove(runner_type=info.runner_type, runner_id=info.runner_id)
            stopped.append(info)
        else:
            failed.append((info, reason))

    _print_summary(runner_type, stopped, failed)

    if failed:
        raise SystemExit(1)


def _require_one_filter(
    project_name: Optional[str], all_flag: bool, runner_id_filter: Optional[str]
) -> None:
    if not (project_name or all_flag or runner_id_filter):
        raise click.UsageError(
            "Specify --project, --runner, or --all to choose what to stop."
        )


def _filter(
    runners: List[pid_file.RunnerInfo],
    project_name: Optional[str],
    all_flag: bool,
    runner_id_filter: Optional[str],
) -> List[pid_file.RunnerInfo]:
    if all_flag:
        return list(runners)
    out: List[pid_file.RunnerInfo] = []
    for runner in runners:
        if runner_id_filter and runner.runner_id != runner_id_filter:
            continue
        if project_name and runner.project_name != project_name:
            continue
        out.append(runner)
    return out


def _signal_until_gone(info: pid_file.RunnerInfo) -> Tuple[bool, str]:
    """SIGTERM the supervisor; escalate to SIGKILL on timeout. Returns (ok, reason)."""
    try:
        os.kill(info.pid, signal.SIGTERM)
    except ProcessLookupError:
        return True, "already exited"
    except PermissionError as e:
        return False, f"permission denied: {e}"
    except OSError as e:
        return False, f"SIGTERM failed: {e}"

    deadline = time.monotonic() + _SIGTERM_GRACE_SECONDS
    while time.monotonic() < deadline:
        if not pid_file.is_pid_alive(info.pid):
            return True, ""
        time.sleep(_POLL_INTERVAL_SECONDS)

    LOGGER.warning(
        "Runner %s did not exit after SIGTERM, escalating to SIGKILL", info.runner_id
    )
    try:
        os.kill(info.pid, signal.SIGKILL)
    except ProcessLookupError:
        return True, "exited during escalation"
    except OSError as e:
        return False, f"SIGKILL failed: {e}"

    # Give the kernel a moment to reap the process before we report success;
    # the BE won't be notified for SIGKILL — the heartbeat reaper handles it.
    time.sleep(_POLL_INTERVAL_SECONDS)
    if pid_file.is_pid_alive(info.pid):
        return False, "still alive after SIGKILL"
    return True, "killed (no backend notification — reaper will clear)"


def _no_match_message(
    runner_type: pairing.RunnerType,
    project_name: Optional[str],
    runner_id_filter: Optional[str],
) -> str:
    parts = [f"No local '{runner_type.value}' runners found"]
    if project_name:
        parts.append(f"for project '{project_name}'")
    if runner_id_filter:
        parts.append(f"with runner id '{runner_id_filter}'")
    return " ".join(parts) + "."


def _print_summary(
    runner_type: pairing.RunnerType,
    stopped: List[pid_file.RunnerInfo],
    failed: List[Tuple[pid_file.RunnerInfo, str]],
) -> None:
    for runner in stopped:
        click.echo(
            f"Stopped {runner_type.value} runner {runner.runner_id} "
            f"(pid {runner.pid}, project '{runner.project_name}')"
        )
    for runner, reason in failed:
        click.echo(
            f"Failed to stop {runner_type.value} runner {runner.runner_id} "
            f"(pid {runner.pid}): {reason}",
            err=True,
        )
