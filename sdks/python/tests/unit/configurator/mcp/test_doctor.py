import pathlib
import subprocess
from unittest import mock

import pytest

from opik.configurator.mcp import doctor, targets

_EPHEMERAL_TRACE = (
    "DEBUG Resolved 39 packages\n"
    "DEBUG Checking for Python environment at: "
    "`/Users/x/.cache/uv/archive-v0/Woh80FRc_FOgUF8Nbqa8a`\n"
)
_TOOL_INSTALL_TRACE = (
    "DEBUG Found existing environment for tool `opik-mcp`: /Users/x/.local/...\n"
)


def _target(tmp_path):
    return targets.HostTarget(
        key="host",
        display_name="Test Host",
        config_path=lambda: tmp_path / "host.json",
        top_level_key="mcpServers",
        is_detected=lambda: True,
        install=lambda server_spec: None,
    )


def _patch(monkeypatch, tmp_path, *, block, trace="", version="0.2.35"):
    monkeypatch.setattr(doctor.mcp_targets, "HOST_TARGETS", [_target(tmp_path)])
    monkeypatch.setattr(
        doctor.mcp_targets, "read_registered_block", lambda target: block
    )
    monkeypatch.setattr(doctor.uv_tool, "installed_version", lambda: None)
    monkeypatch.setattr(doctor, "latest_published_version", lambda: "0.2.35")
    monkeypatch.setattr(
        doctor.subprocess,
        "run",
        mock.Mock(return_value=subprocess.CompletedProcess([], 0, "", trace)),
    )
    monkeypatch.setattr(doctor, "_installed_version_in", lambda env_dir: version)
    monkeypatch.setattr(doctor, "_tool_install_dir", lambda: tmp_path)


_LOCAL_BLOCK = {
    "type": "stdio",
    "command": "/usr/bin/uvx",
    "args": ["--isolated", "opik-mcp"],
    "env": {"OPIK_API_KEY": "key"},
}


def test_collect__resolved_from_index__reports_index_source(monkeypatch, tmp_path):
    _patch(monkeypatch, tmp_path, block=_LOCAL_BLOCK, trace=_EPHEMERAL_TRACE)

    [host] = doctor.collect_diagnosis().hosts

    assert host.source == doctor.SOURCE_INDEX
    assert host.running_version == "0.2.35"
    assert host.problem is None


def test_collect__served_by_tool_install__is_reported_as_pinned(monkeypatch, tmp_path):
    # No ephemeral environment in the trace means uv served the launch from the
    # persistent tool install — the frozen case this command exists to catch.
    _patch(
        monkeypatch,
        tmp_path,
        block={**_LOCAL_BLOCK, "args": ["opik-mcp"]},
        trace=_TOOL_INSTALL_TRACE,
        version="0.2.12",
    )

    [host] = doctor.collect_diagnosis().hosts

    assert host.source == doctor.SOURCE_TOOL_INSTALL
    assert host.running_version == "0.2.12"


def test_collect__hosted_block__is_not_launched(monkeypatch, tmp_path):
    block = {"type": "http", "url": "https://www.comet.com/opik/api/v1/mcp"}
    _patch(monkeypatch, tmp_path, block=block)
    run = doctor.subprocess.run

    [host] = doctor.collect_diagnosis().hosts

    assert host.hosted_url == "https://www.comet.com/opik/api/v1/mcp"
    assert host.running_version is None
    run.assert_not_called()


def test_probe__disables_analytics(monkeypatch, tmp_path):
    # A diagnostic launch must not file `server_started` events in the telemetry
    # used to find affected installs.
    _patch(monkeypatch, tmp_path, block=_LOCAL_BLOCK, trace=_EPHEMERAL_TRACE)

    doctor.collect_diagnosis()

    env = doctor.subprocess.run.call_args.kwargs["env"]
    assert env["OPIK_MCP_ANALYTICS_ENABLED"] == "false"


def test_probe__inserts_verbose_flag_after_the_executable(monkeypatch, tmp_path):
    _patch(monkeypatch, tmp_path, block=_LOCAL_BLOCK, trace=_EPHEMERAL_TRACE)

    doctor.collect_diagnosis()

    assert doctor.subprocess.run.call_args.args[0] == [
        "/usr/bin/uvx",
        "-v",
        "--isolated",
        "opik-mcp",
    ]


def test_probe__timeout__is_reported_not_raised(monkeypatch, tmp_path):
    _patch(monkeypatch, tmp_path, block=_LOCAL_BLOCK)
    monkeypatch.setattr(
        doctor.subprocess,
        "run",
        mock.Mock(side_effect=subprocess.TimeoutExpired("uvx", 240)),
    )

    [host] = doctor.collect_diagnosis().hosts

    assert host.running_version is None
    assert "did not start" in host.problem


def test_probe__missing_executable__explains_the_stale_path(monkeypatch, tmp_path):
    # The recorded command is an absolute path; a deleted virtualenv is the
    # common way it stops existing.
    _patch(monkeypatch, tmp_path, block=_LOCAL_BLOCK)
    monkeypatch.setattr(
        doctor.subprocess, "run", mock.Mock(side_effect=OSError("No such file"))
    )

    [host] = doctor.collect_diagnosis().hosts

    assert "may no longer exist" in host.problem


@pytest.mark.parametrize(
    "block, expected",
    [
        (_LOCAL_BLOCK, ["/usr/bin/uvx", "--isolated", "opik-mcp"]),
        # opencode records the executable and its arguments in one list.
        (
            {"command": ["uvx", "--isolated", "opik-mcp"]},
            ["uvx", "--isolated", "opik-mcp"],
        ),
        ({"command": None}, None),
    ],
)
def test_launch_argv__handles_each_host_spelling(block, expected):
    assert doctor._launch_argv(block) == expected


def test_latest_published_version__index_unreachable__returns_none(monkeypatch):
    monkeypatch.setattr(
        doctor.urllib.request, "urlopen", mock.Mock(side_effect=OSError("no route"))
    )

    assert doctor.latest_published_version() is None


def test_installed_version_in__reads_the_dist_info(tmp_path):
    site = tmp_path / "lib" / "python3.13" / "site-packages"
    site.mkdir(parents=True)
    (site / "opik_mcp-0.2.35.dist-info").mkdir()

    assert doctor._installed_version_in(pathlib.Path(tmp_path)) == "0.2.35"
