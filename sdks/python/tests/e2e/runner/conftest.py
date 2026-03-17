import dataclasses
import os
import re
import shutil
import subprocess
import sys
import threading
import time

import pytest

import opik
import opik.api_objects.opik_client
from opik.api_objects import rest_helpers
from opik.rest_api import core as rest_api_core
from ..conftest import OPIK_E2E_TESTS_PROJECT_NAME


ECHO_APP = os.path.join(os.path.dirname(__file__), "echo_app.py")
OPIK_CLI = shutil.which("opik") or "opik"

RUNNER_STARTUP_TIMEOUT = 15

_phase_report_key = pytest.StashKey[dict]()


@dataclasses.dataclass
class RunnerInfo:
    runner_id: str
    process: subprocess.Popen
    output_lines: list


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    item.stash.setdefault(_phase_report_key, {})[rep.when] = rep


@pytest.fixture()
def opik_client(configure_e2e_tests_env, shutdown_cached_client_after_test):
    client = opik.api_objects.opik_client.Opik(_use_batching=True)
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


@pytest.fixture()
def runner_process(api_client, subprocess_env, project_id, request):
    pair = api_client.runners.generate_pairing_code(project_id=project_id)

    proc = subprocess.Popen(
        [OPIK_CLI, "connect", "--pair", pair.pairing_code, sys.executable, ECHO_APP],
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

    runner_id = None
    deadline = time.monotonic() + RUNNER_STARTUP_TIMEOUT

    while time.monotonic() < deadline:
        for line in list(output_lines):
            match = re.search(r"Runner connected \(ID: ([^)]+)\)", line)
            if match:
                runner_id = match.group(1)
                break
        if runner_id is not None:
            break
        if proc.poll() is not None:
            break
        time.sleep(0.05)

    if runner_id is None:
        proc.terminate()
        proc.wait(timeout=5)
        drain_thread.join(timeout=5)
        pytest.fail(
            f"Runner did not start within {RUNNER_STARTUP_TIMEOUT}s.\n"
            f"Output:\n" + "\n".join(output_lines)
        )

    info = RunnerInfo(runner_id=runner_id, process=proc, output_lines=output_lines)

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
