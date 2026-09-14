import subprocess
from unittest import mock

from opik.configurator.mcp import uv_tool


def _patch_uv(monkeypatch, *, stdout="", returncode=0, side_effect=None):
    monkeypatch.setattr(uv_tool.shutil, "which", lambda name: "/usr/bin/uv")
    run = mock.Mock(
        return_value=subprocess.CompletedProcess([], returncode, stdout, ""),
        side_effect=side_effect,
    )
    monkeypatch.setattr(uv_tool.subprocess, "run", run)
    return run


# Real `uv tool list` output: a `<name> v<version>` line per tool, each followed
# by its entry points.
_TOOL_LIST = "opik-mcp v0.2.12\n- opik-mcp\nruff v0.6.9\n- ruff\n"


def test_installed_version__tool_present__returns_version(monkeypatch):
    _patch_uv(monkeypatch, stdout=_TOOL_LIST)

    assert uv_tool.installed_version() == "0.2.12"


def test_installed_version__other_tools_only__returns_none(monkeypatch):
    _patch_uv(monkeypatch, stdout="ruff v0.6.9\n- ruff\n")

    assert uv_tool.installed_version() is None


def test_installed_version__no_tools__returns_none(monkeypatch):
    _patch_uv(monkeypatch, stdout="No tools installed\n")

    assert uv_tool.installed_version() is None


def test_installed_version__entry_point_line_does_not_match(monkeypatch):
    # `- opik-mcp` is an entry point of some *other* package, not an install of
    # ours. Matching it would invent a version out of the next word.
    _patch_uv(monkeypatch, stdout="somepkg v1.0.0\n- opik-mcp\n")

    assert uv_tool.installed_version() is None


def test_installed_version__uv_missing__returns_none(monkeypatch):
    monkeypatch.setattr(uv_tool.shutil, "which", lambda name: None)

    assert uv_tool.installed_version() is None


def test_installed_version__uv_fails__returns_none(monkeypatch):
    _patch_uv(monkeypatch, stdout="", returncode=2)

    assert uv_tool.installed_version() is None


def test_installed_version__uv_hangs__returns_none(monkeypatch):
    _patch_uv(monkeypatch, side_effect=subprocess.TimeoutExpired("uv", 10))

    assert uv_tool.installed_version() is None


def test_installed_version__uv_unrunnable__returns_none(monkeypatch):
    _patch_uv(monkeypatch, side_effect=OSError("uv vanished"))

    assert uv_tool.installed_version() is None
