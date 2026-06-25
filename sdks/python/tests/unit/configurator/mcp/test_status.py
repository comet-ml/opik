import json
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
