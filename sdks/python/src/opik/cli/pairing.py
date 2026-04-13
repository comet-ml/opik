"""Shared relay-based pairing flow for `opik connect` and `opik endpoint`."""

import base64
import enum
import hashlib
import hmac
import logging
import os
import platform
import secrets
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, List, Optional

import click
import httpx

from opik.api_objects.rest_helpers import resolve_project_id_by_name
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.errors.not_found_error import NotFoundError
from opik.url_helpers import get_base_url

LOGGER = logging.getLogger(__name__)

POLL_INTERVAL_SECONDS = 2
DEFAULT_TTL_SECONDS = 300


class RunnerType(str, enum.Enum):
    CONNECT = "connect"
    ENDPOINT = "endpoint"


if TYPE_CHECKING:
    from opik.rest_api.client import OpikApi
    from opik.runner.tui import RunnerTUI


@dataclass
class PairingResult:
    runner_id: str
    project_name: str
    project_id: str
    bridge_key: bytes


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    """HKDF-SHA256 extract-then-expand (RFC 5869), stdlib only."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm = hmac.new(prk, info + b"\x01", hashlib.sha256).digest()
    return okm[:length]


def resolve_project_id(api: "OpikApi", project_name: str) -> str:
    try:
        return resolve_project_id_by_name(api, project_name)
    except ApiError:
        raise click.ClickException(
            f"Project '{project_name}' not found. Check the project name and try again."
        )


def build_pairing_link(
    base_url: str,
    session_id: str,
    activation_key: bytes,
    project_id: str,
    runner_name: str,
) -> str:
    session_bytes = uuid.UUID(session_id).bytes
    project_bytes = uuid.UUID(project_id).bytes
    runner_name_bytes = runner_name.encode("utf-8")

    payload = (
        session_bytes
        + activation_key
        + project_bytes
        + bytes([len(runner_name_bytes)])
        + runner_name_bytes
    )

    fragment = base64.urlsafe_b64encode(payload).rstrip(b"=").decode("ascii")
    domain_root = get_base_url(base_url)
    return f"{domain_root}opik/pair/v1#{fragment}"


def validate_runner_name(runner_name: str) -> None:
    if not runner_name or not runner_name.strip():
        raise click.ClickException("Runner name must not be empty.")
    if len(runner_name) > 128:
        raise click.ClickException(
            f"Runner name exceeds 128 characters ({len(runner_name)})."
        )
    if len(runner_name.encode("utf-8")) > 255:
        raise click.ClickException("Runner name exceeds 255 UTF-8 bytes.")


def generate_runner_name(name: Optional[str]) -> str:
    if name is not None:
        return name
    return f"{platform.node()}-{secrets.token_hex(3)}"


def launch_supervisor(
    result: PairingResult,
    api: "OpikApi",
    tui: "RunnerTUI",
    runner_type: RunnerType,
    command: Optional[List[str]] = None,
    watch: Optional[bool] = None,
) -> None:
    from opik.runner.supervisor import Supervisor

    env = {
        **os.environ,
        "OPIK_RUNNER_MODE": "true",
        "OPIK_RUNNER_ID": result.runner_id,
        "OPIK_PROJECT_NAME": result.project_name,
    }

    opik_logger = logging.getLogger("opik")
    opik_logger.handlers = [
        h
        for h in opik_logger.handlers
        if not isinstance(h, logging.StreamHandler)
        or isinstance(h, logging.FileHandler)
    ]

    supervisor = Supervisor(
        command=command,
        env=env,
        repo_root=Path.cwd(),
        runner_id=result.runner_id,
        api=api,
        on_child_output=tui.app_line,
        on_child_restart=tui.child_restarted,
        on_error=tui.error,
        on_command_start=tui.op_start,
        on_command_end=tui.op_end,
        watch=watch,
        bridge_key=result.bridge_key,
        runner_type=runner_type,
    )
    supervisor.run()


def run_pairing(
    api: "OpikApi",
    project_name: str,
    runner_name: str,
    runner_type: RunnerType,
    base_url: str,
    tui: Optional["RunnerTUI"] = None,
    ttl_seconds: int = DEFAULT_TTL_SECONDS,
) -> PairingResult:
    validate_runner_name(runner_name)

    project_id = resolve_project_id(api, project_name)
    activation_key = secrets.token_bytes(32)

    activation_key_b64 = base64.b64encode(activation_key).decode("ascii")
    resp = api.pairing.create_pairing_session(
        project_id=project_id,
        activation_key=activation_key_b64,
        type=runner_type.value,
        ttl_seconds=ttl_seconds,
    )
    if not resp.session_id:
        raise click.ClickException("Server did not return a session_id.")
    if not resp.runner_id:
        raise click.ClickException("Server did not return a runner_id.")

    session_id = resp.session_id
    runner_id = resp.runner_id

    pairing_url = build_pairing_link(
        base_url, session_id, activation_key, project_id, runner_name
    )

    if tui:
        tui.pairing_started(pairing_url, ttl_seconds)
    else:
        click.echo(f"Open this link to pair: {pairing_url}")

    deadline = time.monotonic() + ttl_seconds
    try:
        while time.monotonic() < deadline:
            try:
                runner = api.runners.get_runner(runner_id)
            except NotFoundError:
                time.sleep(POLL_INTERVAL_SECONDS)
                continue
            except (httpx.ConnectError, httpx.TimeoutException):
                LOGGER.debug("Transient network error during pairing poll, retrying")
                time.sleep(POLL_INTERVAL_SECONDS)
                continue
            if runner.status == "connected":
                if tui:
                    tui.pairing_completed()
                break
            time.sleep(POLL_INTERVAL_SECONDS)
        else:
            if tui:
                tui.pairing_failed("timed out")
            raise click.ClickException(
                f"Pairing timed out after {ttl_seconds} seconds."
            )
    except KeyboardInterrupt:
        if tui:
            tui.pairing_failed("interrupted")
        raise

    session_id_bytes = uuid.UUID(session_id).bytes
    bridge_key = hkdf_sha256(
        ikm=activation_key,
        salt=session_id_bytes,
        info=b"opik-bridge-v1",
    )

    return PairingResult(
        runner_id=runner_id,
        project_name=project_name,
        project_id=project_id,
        bridge_key=bridge_key,
    )
