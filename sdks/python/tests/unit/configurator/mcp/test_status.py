import json
import pathlib
from unittest import mock

from opik.configurator.mcp import status, targets


def _config(url_override, workspace):
    return mock.Mock(url_override=url_override, workspace=workspace)


def _patch_single_host(monkeypatch, tmp_path, *, block, detected=True):
    config_path = tmp_path / "host.json"
    if block is not None:
        config_path.write_text(
            json.dumps({"mcpServers": {"opik-mcp": block}}), encoding="utf-8"
        )

    host_target = targets.HostTarget(
        key="host",
        display_name="Test Host",
        config_path=lambda: config_path,
        top_level_key="mcpServers",
        is_detected=lambda: detected,
        install=lambda server_spec: None,
    )
    monkeypatch.setattr(status.mcp_targets, "HOST_TARGETS", [host_target])


def test_collect__remote_matching_config__in_sync(monkeypatch, tmp_path):
    _patch_single_host(
        monkeypatch,
        tmp_path,
        block={"type": "http", "url": "https://dev.comet.com/opik/api/v1/mcp"},
    )

    [host] = status.collect_host_statuses(
        _config("https://dev.comet.com/opik/api/", "alex")
    )

    assert host.registered is True
    assert host.transport == status.TRANSPORT_HOSTED
    assert host.points_to == "https://dev.comet.com/opik/api/v1/mcp"
    assert host.workspace is None  # OAuth, not stored in the host config
    assert host.in_sync is True


def test_collect__remote_sse__labeled_as_sse(monkeypatch, tmp_path):
    _patch_single_host(
        monkeypatch,
        tmp_path,
        block={"type": "sse", "url": "https://dev.comet.com/opik/api/v1/mcp"},
    )

    [host] = status.collect_host_statuses(
        _config("https://dev.comet.com/opik/api/", "alex")
    )

    assert host.transport == status.TRANSPORT_HOSTED_SSE
    assert host.in_sync is True


def test_collect__remote_pointing_elsewhere__out_of_sync(monkeypatch, tmp_path):
    _patch_single_host(
        monkeypatch,
        tmp_path,
        block={"type": "http", "url": "https://www.comet.com/opik/api/v1/mcp"},
    )

    [host] = status.collect_host_statuses(
        _config("https://dev.comet.com/opik/api/", "alex")
    )

    assert host.in_sync is False


def test_collect__local_stdio_localhost_vs_dev__out_of_sync(monkeypatch, tmp_path):
    # The real drift case: SDK points at dev, but a stale uvx block points local.
    _patch_single_host(
        monkeypatch,
        tmp_path,
        block={
            "type": "stdio",
            "command": "/usr/bin/uvx",
            "args": ["opik-mcp"],
            "env": {
                "OPIK_URL": "http://localhost:5173/api/",
                "COMET_WORKSPACE": "default",
            },
        },
    )

    [host] = status.collect_host_statuses(
        _config("https://dev.comet.com/opik/api/", "alex")
    )

    assert host.transport == status.TRANSPORT_LOCAL
    assert host.points_to == "http://localhost:5173/api/"
    assert host.workspace == "default"
    assert host.in_sync is False


def test_collect__self_hosted_comet_stdio_matching__in_sync(monkeypatch, tmp_path):
    _patch_single_host(
        monkeypatch,
        tmp_path,
        block={
            "type": "stdio",
            "command": "/usr/bin/uvx",
            "args": ["opik-mcp"],
            "env": {
                "COMET_URL_OVERRIDE": "https://opik.acme.com",
                "COMET_WORKSPACE": "ws",
            },
        },
    )

    [host] = status.collect_host_statuses(
        _config("https://opik.acme.com/opik/api/", "ws")
    )

    assert host.points_to == "https://opik.acme.com"
    assert host.workspace == "ws"
    assert host.in_sync is True


def test_collect__cloud_stdio_no_url_env__in_sync(monkeypatch, tmp_path):
    _patch_single_host(
        monkeypatch,
        tmp_path,
        block={
            "type": "stdio",
            "command": "/usr/bin/uvx",
            "args": ["opik-mcp"],
            "env": {"OPIK_API_KEY": "key", "COMET_WORKSPACE": "ws"},
        },
    )

    [host] = status.collect_host_statuses(
        _config("https://www.comet.com/opik/api/", "ws")
    )

    assert host.points_to == "Opik Cloud"
    assert host.in_sync is True


def test_collect__not_registered__reports_detection(monkeypatch, tmp_path):
    _patch_single_host(monkeypatch, tmp_path, block=None, detected=False)

    [host] = status.collect_host_statuses(
        _config("https://dev.comet.com/opik/api/", "x")
    )

    assert host.registered is False
    assert host.detected is False
    assert host.transport is None
    assert host.in_sync is None


def _local_block(args):
    return {
        "type": "stdio",
        "command": "/usr/bin/uvx",
        "args": args,
        "env": {"OPIK_API_KEY": "key", "COMET_WORKSPACE": "alex"},
    }


def test_collect__bare_local_block__cannot_bypass_tool_install(monkeypatch, tmp_path):
    _patch_single_host(monkeypatch, tmp_path, block=_local_block(["opik-mcp"]))

    [host] = status.collect_host_statuses(
        _config("https://www.comet.com/opik/api/", "alex")
    )

    assert host.bypasses_tool_install is False


def test_collect__isolated_local_block__bypasses_tool_install(monkeypatch, tmp_path):
    _patch_single_host(
        monkeypatch, tmp_path, block=_local_block(["--isolated", "opik-mcp"])
    )

    [host] = status.collect_host_statuses(
        _config("https://www.comet.com/opik/api/", "alex")
    )

    assert host.bypasses_tool_install is True


def test_collect__opencode_command_list__bypasses_tool_install(monkeypatch, tmp_path):
    # opencode records the executable and its arguments in one `command` list.
    block = {
        "type": "local",
        "command": ["uvx", "--isolated", "opik-mcp"],
        "environment": {},
    }
    _patch_single_host(monkeypatch, tmp_path, block=block)

    [host] = status.collect_host_statuses(
        _config("https://www.comet.com/opik/api/", "alex")
    )

    assert host.bypasses_tool_install is True


def test_collect__remote_block__bypass_flag_is_none(monkeypatch, tmp_path):
    _patch_single_host(
        monkeypatch,
        tmp_path,
        block={"type": "http", "url": "https://www.comet.com/opik/api/v1/mcp"},
    )

    [host] = status.collect_host_statuses(
        _config("https://www.comet.com/opik/api/", "alex")
    )

    assert host.bypasses_tool_install is None


def _host_status(transport, bypasses_tool_install, registered=True):
    return status.HostStatus(
        display_name="Test Host",
        config_path=pathlib.Path("/tmp/host.json"),
        detected=True,
        registered=registered,
        transport=transport,
        bypasses_tool_install=bypasses_tool_install,
    )


def test_uv_tool_note__no_install__is_none(monkeypatch):
    monkeypatch.setattr(status.uv_tool, "installed_version", lambda: None)

    hosts = [_host_status(status.TRANSPORT_LOCAL, bypasses_tool_install=False)]

    assert status.uv_tool_install_note(hosts) is None


def test_uv_tool_note__install_but_only_hosted_registrations__is_none(monkeypatch):
    # A hosted server runs no local package, so an install is beside the point.
    monkeypatch.setattr(status.uv_tool, "installed_version", lambda: "0.2.12")

    hosts = [_host_status(status.TRANSPORT_HOSTED, bypasses_tool_install=None)]

    assert status.uv_tool_install_note(hosts) is None


def test_uv_tool_note__frozen_registration__names_host_and_remedy(monkeypatch):
    monkeypatch.setattr(status.uv_tool, "installed_version", lambda: "0.2.12")

    hosts = [_host_status(status.TRANSPORT_LOCAL, bypasses_tool_install=False)]
    note = status.uv_tool_install_note(hosts)

    assert "0.2.12" in note
    assert "Test Host" in note
    assert "opik mcp configure" in note


def test_uv_tool_note__registration_asks_for_latest__shadow_wording_only(monkeypatch):
    monkeypatch.setattr(status.uv_tool, "installed_version", lambda: "0.2.12")

    hosts = [_host_status(status.TRANSPORT_LOCAL, bypasses_tool_install=True)]
    note = status.uv_tool_install_note(hosts)

    assert "unaffected" in note
    assert "uv tool uninstall opik-mcp" in note
    # Not the frozen wording: nothing here needs re-configuring.
    assert "opik mcp configure" not in note
