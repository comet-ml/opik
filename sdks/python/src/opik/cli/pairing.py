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
    bridge_key: bytes = b""


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


_RUNNER_TYPE_BYTE = {
    RunnerType.CONNECT: 0x00,
    RunnerType.ENDPOINT: 0x01,
}


def build_pairing_link(
    base_url: str,
    session_id: str,
    activation_key: bytes,
    project_id: str,
    runner_name: str,
    runner_type: RunnerType = RunnerType.CONNECT,
    workspace: Optional[str] = None,
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
        + bytes([_RUNNER_TYPE_BYTE[runner_type]])
    )

    fragment = base64.urlsafe_b64encode(payload).rstrip(b"=").decode("ascii")
    domain_root = get_base_url(base_url)
    query = f"?workspace={workspace}" if workspace else ""
    return f"{domain_root}opik/pair/v1{query}#{fragment}"


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

    bridge_key = result.bridge_key or None
    if runner_type == RunnerType.CONNECT and not bridge_key:
        raise click.ClickException(
            "CONNECT runner requires a bridge key but none was provided."
        )

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
        bridge_key=bridge_key,
        runner_type=runner_type,
    )
    supervisor.run()


def _compute_activation_hmac(
    activation_key: bytes,
    session_id: str,
    runner_name: str,
) -> str:
    """Compute the HMAC that the browser PairingPage would normally produce.

    Message = session_id_bytes (16) || SHA-256(runner_name) (32).
    """
    session_id_bytes = uuid.UUID(session_id).bytes
    runner_name_hash = hashlib.sha256(runner_name.encode("utf-8")).digest()
    message = session_id_bytes + runner_name_hash
    sig = hmac.new(activation_key, message, hashlib.sha256).digest()
    return base64.b64encode(sig).decode("ascii")


@dataclass
class _Session:
    session_id: str
    runner_id: str
    project_id: str
    activation_key: bytes


def _create_session(
    api: "OpikApi",
    project_name: str,
    runner_name: str,
    runner_type: RunnerType,
    ttl_seconds: int = DEFAULT_TTL_SECONDS,
) -> _Session:
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

    return _Session(
        session_id=resp.session_id,
        runner_id=resp.runner_id,
        project_id=project_id,
        activation_key=activation_key,
    )


def run_headless(
    api: "OpikApi",
    project_name: str,
    runner_name: str,
    runner_type: RunnerType,
) -> PairingResult:
    """Register and self-activate a runner without browser pairing.

    Creates a pairing session and immediately activates it by computing
    the activation HMAC locally. No bridge key is derived — ENDPOINT
    runners don't use one (bridge commands only run on CONNECT runners).
    """
    if runner_type == RunnerType.CONNECT:
        raise click.ClickException(
            "Headless mode is not supported for CONNECT runners "
            "(no bridge key can be derived without browser pairing)."
        )

    session = _create_session(api, project_name, runner_name, runner_type)

    activation_hmac = _compute_activation_hmac(
        session.activation_key, session.session_id, runner_name
    )
    api.pairing.activate_pairing_session(
        session.session_id, runner_name=runner_name, hmac=activation_hmac
    )

    return PairingResult(
        runner_id=session.runner_id,
        project_name=project_name,
        project_id=session.project_id,
    )


def run_pairing(
    api: "OpikApi",
    project_name: str,
    runner_name: str,
    runner_type: RunnerType,
    base_url: str,
    workspace: Optional[str] = None,
    tui: Optional["RunnerTUI"] = None,
    ttl_seconds: int = DEFAULT_TTL_SECONDS,
) -> PairingResult:
    session = _create_session(api, project_name, runner_name, runner_type, ttl_seconds)

    pairing_url = build_pairing_link(
        base_url,
        session.session_id,
        session.activation_key,
        session.project_id,
        runner_name,
        runner_type,
        workspace=workspace,
    )

    if tui:
        tui.pairing_started(pairing_url, ttl_seconds)
    else:
        click.echo(f"Open this link to pair: {pairing_url}")

    deadline = time.monotonic() + ttl_seconds
    try:
        while time.monotonic() < deadline:
            try:
                runner = api.runners.get_runner(session.runner_id)
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

    bridge_key = hkdf_sha256(
        ikm=session.activation_key,
        salt=uuid.UUID(session.session_id).bytes,
        info=b"opik-bridge-v1",
    )

    return PairingResult(
        runner_id=session.runner_id,
        project_name=project_name,
        project_id=session.project_id,
        bridge_key=bridge_key,
    )
