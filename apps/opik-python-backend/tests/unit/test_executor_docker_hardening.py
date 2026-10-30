import random

import pytest

from opik_backend.executor_docker import (
    HARDENING_ENV_VAR,
    PIDS_LIMIT_ENV_VAR,
    TMPFS_SIZE_ENV_VAR,
    sandbox_hardening_options,
)


@pytest.mark.parametrize("hardening", [None, "", "false", "False"])
def test_containers_are_not_hardened_without_setting(monkeypatch, hardening):
    if hardening is None:
        monkeypatch.delenv(HARDENING_ENV_VAR, raising=False)
    else:
        monkeypatch.setenv(HARDENING_ENV_VAR, hardening)

    assert sandbox_hardening_options() == {}


@pytest.mark.parametrize("hardening", ["true", "True"])
def test_containers_are_hardened_with_defaults(monkeypatch, hardening):
    monkeypatch.setenv(HARDENING_ENV_VAR, hardening)
    monkeypatch.delenv(PIDS_LIMIT_ENV_VAR, raising=False)
    monkeypatch.delenv(TMPFS_SIZE_ENV_VAR, raising=False)

    expected = {
        "cap_drop": ["ALL"],
        "read_only": True,
        "tmpfs": {"/tmp": "rw,noexec,nosuid,size=16m"},
        "pids_limit": 64,
    }

    assert sandbox_hardening_options() == expected


def test_containers_are_hardened_with_configured_limits(monkeypatch):
    pids_limit = random.randint(1, 4096)
    tmpfs_size = f"{random.randint(1, 512)}m"
    monkeypatch.setenv(HARDENING_ENV_VAR, "true")
    monkeypatch.setenv(PIDS_LIMIT_ENV_VAR, str(pids_limit))
    monkeypatch.setenv(TMPFS_SIZE_ENV_VAR, tmpfs_size)

    expected = {
        "cap_drop": ["ALL"],
        "read_only": True,
        "tmpfs": {"/tmp": f"rw,noexec,nosuid,size={tmpfs_size}"},
        "pids_limit": pids_limit,
    }

    assert sandbox_hardening_options() == expected


@pytest.mark.parametrize("hardening", ["yes", "1", "on", " true"])
def test_invalid_setting_fails_startup(monkeypatch, hardening):
    monkeypatch.setenv(HARDENING_ENV_VAR, hardening)

    with pytest.raises(ValueError, match=f"^{HARDENING_ENV_VAR} must be true or false, not "):
        sandbox_hardening_options()


@pytest.mark.parametrize("pids_limit", ["0", "-1", "1.5", "many"])
def test_invalid_pids_limit_fails_startup(monkeypatch, pids_limit):
    monkeypatch.setenv(HARDENING_ENV_VAR, "true")
    monkeypatch.setenv(PIDS_LIMIT_ENV_VAR, pids_limit)

    with pytest.raises(ValueError, match=f"^{PIDS_LIMIT_ENV_VAR} must be a positive integer, not "):
        sandbox_hardening_options()


@pytest.mark.parametrize("tmpfs_size", ["0", "16mb", "16 m", "16m,exec", "-16m"])
def test_invalid_tmpfs_size_fails_startup(monkeypatch, tmpfs_size):
    monkeypatch.setenv(HARDENING_ENV_VAR, "true")
    monkeypatch.setenv(TMPFS_SIZE_ENV_VAR, tmpfs_size)

    with pytest.raises(ValueError, match=f"^{TMPFS_SIZE_ENV_VAR} must be a size such as 16m, not "):
        sandbox_hardening_options()
