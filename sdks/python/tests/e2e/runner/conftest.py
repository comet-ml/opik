import base64
import dataclasses
import hashlib
import hmac as hmac_mod
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import uuid

import pytest
import urllib.request

import opik
import opik.api_objects.opik_client
from opik.api_objects import rest_helpers
from opik.rest_api import core as rest_api_core
from ..conftest import OPIK_E2E_TESTS_PROJECT_NAME


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
def project_id(api_client):
    try:
        api_client.projects.create_project(name=OPIK_E2E_TESTS_PROJECT_NAME)
    except rest_api_core.ApiError:
        pass
    return rest_helpers.resolve_project_id_by_name(
        api_client, OPIK_E2E_TESTS_PROJECT_NAME
    )


def _drain_stdout(proc, output_lines):
    for line in proc.stdout:
        output_lines.append(line.rstrip())


def _activate_session(
    api_url, workspace, project_id, activation_key, session_id, runner_name
):
    """Simulate the browser side of pairing: compute HMAC and call activate."""
    session_id_bytes = uuid.UUID(session_id).bytes
    runner_name_hash = hashlib.sha256(runner_name.encode("utf-8")).digest()
    message = session_id_bytes + runner_name_hash
    sig = hmac_mod.new(activation_key, message, hashlib.sha256).digest()
    hmac_b64 = base64.b64encode(sig).decode("ascii")

    base = api_url.rstrip("/")
    body = json.dumps({"runner_name": runner_name, "hmac": hmac_b64}).encode()
    req = urllib.request.Request(
        f"{base}/v1/private/opik-connect/sessions/{session_id}/activate",
        data=body,
        headers={"Content-Type": "application/json", "Comet-Workspace": workspace},
        method="POST",
    )
    urllib.request.urlopen(req)


@pytest.fixture()
def runner_process(api_client, subprocess_env, project_id, opik_client, request):
    cfg = opik_client.config

    # Create session via API
    import secrets

    activation_key = secrets.token_bytes(32)
    activation_key_b64 = base64.b64encode(activation_key).decode("ascii")
    resp = api_client.opik_connect.create_opik_connect_session(
        project_id=project_id,
        activation_key=activation_key_b64,
        ttl_seconds=300,
    )
    session_id = resp.session_id
    runner_id = resp.runner_id

    runner_name = f"e2e-runner-{secrets.token_hex(3)}"

    # Start CLI with endpoint command (includes echo app as child process)
    proc = subprocess.Popen(
        [
            OPIK_CLI,
            "endpoint",
            "--project",
            OPIK_E2E_TESTS_PROJECT_NAME,
            "--",
            sys.executable,
            ECHO_APP,
        ],
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

    # Activate the session (simulates browser clicking Connect)
    # Resolve the base API URL (strip /api/ suffix to get the raw backend URL)
    api_base = cfg.url_override
    workspace = cfg.workspace or "default"
    time.sleep(1)  # give CLI a moment to start polling
    _activate_session(
        api_base, workspace, project_id, activation_key, session_id, runner_name
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
        time.sleep(0.5)

    if not connected:
        proc.terminate()
        proc.wait(timeout=5)
        drain_thread.join(timeout=5)
        pytest.fail(
            f"Runner did not connect within {RUNNER_STARTUP_TIMEOUT}s.\n"
            f"Output:\n" + "\n".join(output_lines)
        )

    # Derive bridge key (same HKDF as CLI and FE)
    from opik.cli.pairing import hkdf_sha256

    bridge_key = hkdf_sha256(
        ikm=activation_key,
        salt=uuid.UUID(session_id).bytes,
        info=b"opik-bridge-v1",
    )

    info = RunnerInfo(
        runner_id=runner_id,
        process=proc,
        output_lines=output_lines,
        bridge_key=bridge_key,
    )

    yield info

    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
    drain_thread.join(timeout=5)

    report = request.node.stash.get(_phase_report_key, {})
    if report.get("call") and report["call"].failed:
        print(f"\n--- Runner output (runner_id={runner_id}) ---")
        print("\n".join(output_lines))
        print("--- End runner output ---")
