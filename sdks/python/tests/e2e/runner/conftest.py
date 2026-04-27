import base64
import dataclasses
import hashlib
import hmac as hmac_mod
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid

import pytest

import opik
import opik.api_objects.opik_client
from opik.api_objects import rest_helpers
from opik.cli.pairing import hkdf_sha256
from opik.rest_api import core as rest_api_core


ECHO_APP = os.path.join(os.path.dirname(__file__), "echo_app.py")
OPIK_CLI = shutil.which("opik") or "opik"

RUNNER_STARTUP_TIMEOUT = 30

_phase_report_key = pytest.StashKey[dict]()


@dataclasses.dataclass
class RunnerInfo:
    runner_id: str
    process: subprocess.Popen
    output_lines: list
    bridge_key: bytes = b""


@dataclasses.dataclass
class TestProject:
    id: str
    name: str


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    item.stash.setdefault(_phase_report_key, {})[rep.when] = rep


@pytest.fixture()
def opik_client(configure_e2e_tests_env, shutdown_cached_client_after_test):
    client = opik.api_objects.opik_client.Opik(batching=True)
    yield client
    client.end()


@pytest.fixture()
def api_client(opik_client):
    return opik_client.rest_client


@pytest.fixture()
def subprocess_env(opik_client):
    cfg = opik_client.config
    env = os.environ.copy()
    env["OPIK_URL_OVERRIDE"] = cfg.url_override
    if cfg.api_key:
        env["OPIK_API_KEY"] = cfg.api_key
    if cfg.workspace:
        env["OPIK_WORKSPACE"] = cfg.workspace
    return env


@pytest.fixture()
def project(api_client, temporary_project_name) -> TestProject:
    """Per-test project, created on setup. The shared ``temporary_project_name``
    fixture handles name generation and teardown."""
    try:
        api_client.projects.create_project(name=temporary_project_name)
    except rest_api_core.ApiError:
        pass
    project_id = rest_helpers.resolve_project_id_by_name(
        api_client, temporary_project_name
    )
    return TestProject(id=project_id, name=temporary_project_name)


def _drain_stdout(proc, output_lines):
    for line in proc.stdout:
        output_lines.append(line.rstrip())


def _parse_pairing_url(output_lines, timeout):
    """Wait for the CLI to print the pairing URL, then parse the fragment.

    The URL may be split across multiple stdout lines by the terminal.
    Join all output and search for the full URL.
    """
    deadline = time.monotonic() + timeout
    url_pattern = re.compile(
        r"(https?://\S+/opik/pair/v1(?:\?[^#\s]*)?#[A-Za-z0-9_\-]+)"
    )

    while time.monotonic() < deadline:
        joined = "".join(list(output_lines))
        match = url_pattern.search(joined)
        if match:
            url = match.group(1)
            fragment = url.split("#", 1)[1]
            padded = fragment + "=" * ((4 - len(fragment) % 4) % 4)
            raw = base64.urlsafe_b64decode(padded)
            if len(raw) < 65:
                time.sleep(0.05)
                continue
            name_len = raw[64]
            if len(raw) < 65 + name_len:
                time.sleep(0.05)
                continue
            return {
                "session_id": str(uuid.UUID(bytes=raw[0:16])),
                "activation_key": raw[16:48],
                "project_id": str(uuid.UUID(bytes=raw[48:64])),
                "runner_name": raw[65 : 65 + name_len].decode("utf-8"),
            }
        time.sleep(0.05)
    return None


def _activate_session(api_client, activation_key, session_id, runner_name):
    session_id_bytes = uuid.UUID(session_id).bytes
    runner_name_hash = hashlib.sha256(runner_name.encode("utf-8")).digest()
    message = session_id_bytes + runner_name_hash
    sig = hmac_mod.new(activation_key, message, hashlib.sha256).digest()
    hmac_b64 = base64.b64encode(sig).decode("ascii")

    api_client.pairing.activate_pairing_session(
        session_id, runner_name=runner_name, hmac=hmac_b64
    )


def _start_runner(
    api_client, subprocess_env, project_id, opik_client, request, cli_args
):
    proc = subprocess.Popen(
        cli_args,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env=subprocess_env,
    )

    output_lines = []
    drain_thread = threading.Thread(
        target=_drain_stdout, args=(proc, output_lines), daemon=True
    )
    drain_thread.start()

    # Wait for the CLI to print its pairing URL, then parse the fragment
    payload = _parse_pairing_url(output_lines, timeout=15)
    if payload is None:
        proc.terminate()
        proc.wait(timeout=5)
        drain_thread.join(timeout=5)
        pytest.fail(
            "CLI did not print pairing URL within 15s.\n"
            "Output:\n" + "\n".join(output_lines)
        )

    # Activate the CLI's session (simulates browser clicking Connect)
    _activate_session(
        api_client,
        payload["activation_key"],
        payload["session_id"],
        payload["runner_name"],
    )

    # Wait for the CLI to detect connected status
    deadline = time.monotonic() + RUNNER_STARTUP_TIMEOUT
    connected = False
    while time.monotonic() < deadline:
        for line in list(output_lines):
            if "Paired" in line:
                connected = True
                break
        if connected:
            break
        if proc.poll() is not None:
            break
        time.sleep(0.1)

    if not connected:
        proc.terminate()
        proc.wait(timeout=5)
        drain_thread.join(timeout=5)
        pytest.fail(
            f"Runner did not connect within {RUNNER_STARTUP_TIMEOUT}s.\n"
            f"Output:\n" + "\n".join(output_lines)
        )

    # Get runner_id by polling the API
    runners_page = api_client.runners.list_runners(project_id=project_id, size=10)
    runner_id = None
    if runners_page.content:
        for r in runners_page.content:
            if r.status == "connected":
                runner_id = r.id
                break

    if runner_id is None:
        proc.terminate()
        proc.wait(timeout=5)
        drain_thread.join(timeout=5)
        pytest.fail("No connected runner found after activation")

    bridge_key = hkdf_sha256(
        ikm=payload["activation_key"],
        salt=uuid.UUID(payload["session_id"]).bytes,
        info=b"opik-bridge-v1",
    )

    info = RunnerInfo(
        runner_id=runner_id,
        process=proc,
        output_lines=output_lines,
        bridge_key=bridge_key,
    )

    yield info

    # Runner CLI + echo_app don't need a graceful window in tests; SIGTERM →
    # short wait → SIGKILL is fine. Drops per-test teardown from ~10s to ~2s.
    proc.terminate()
    try:
        proc.wait(timeout=2)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
    drain_thread.join(timeout=2)

    report = request.node.stash.get(_phase_report_key, {})
    if report.get("call") and report["call"].failed:
        print(f"\n--- Runner output (runner_id={runner_id}) ---")
        print("\n".join(output_lines))
        print("--- End runner output ---")


@pytest.fixture()
def runner_process(api_client, subprocess_env, project, opik_client, request):
    """Endpoint runner — for job/agent E2E tests."""
    yield from _start_runner(
        api_client,
        subprocess_env,
        project.id,
        opik_client,
        request,
        cli_args=[
            OPIK_CLI,
            "endpoint",
            "--project",
            project.name,
            "--",
            sys.executable,
            ECHO_APP,
        ],
    )


@pytest.fixture()
def bridge_runner_process(api_client, subprocess_env, project, opik_client, request):
    """Connect runner — for bridge command E2E tests."""
    yield from _start_runner(
        api_client,
        subprocess_env,
        project.id,
        opik_client,
        request,
        cli_args=[
            OPIK_CLI,
            "connect",
            "--project",
            project.name,
        ],
    )
