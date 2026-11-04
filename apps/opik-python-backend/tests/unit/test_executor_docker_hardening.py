import json
import random
from unittest.mock import MagicMock, patch

import pytest

from opik_backend import docker_daemon_config
from opik_backend.docker_daemon_config import (
    ALLOW_NETWORK_ENV_VAR,
    HARDENING_ENV_VAR,
    write_hardened_config,
)
from opik_backend.executor_docker import (
    PIDS_LIMIT_ENV_VAR,
    TMPFS_SIZE_ENV_VAR,
    DockerExecutor,
    sandbox_hardening_options,
)

HARDENED_DEFAULTS = {
    "cap_drop": ["ALL"],
    "read_only": True,
    "tmpfs": {"/tmp": "rw,noexec,nosuid,size=16m"},
    "pids_limit": 64,
}


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for name in (HARDENING_ENV_VAR, PIDS_LIMIT_ENV_VAR, TMPFS_SIZE_ENV_VAR, ALLOW_NETWORK_ENV_VAR):
        monkeypatch.delenv(name, raising=False)


@pytest.mark.parametrize("hardening", [None, "", "false", "False"])
def test_containers_are_not_hardened_without_setting(monkeypatch, hardening):
    if hardening is not None:
        monkeypatch.setenv(HARDENING_ENV_VAR, hardening)

    assert sandbox_hardening_options() == {}


@pytest.mark.parametrize("hardening", ["true", "True"])
def test_containers_are_hardened_with_defaults(monkeypatch, hardening):
    monkeypatch.setenv(HARDENING_ENV_VAR, hardening)

    assert sandbox_hardening_options() == HARDENED_DEFAULTS


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


@pytest.mark.parametrize("hardening, expected_options", [("false", {}), ("true", HARDENED_DEFAULTS)])
def test_executor_containers_are_created_with_hardening_options(monkeypatch, hardening, expected_options):
    monkeypatch.setenv(HARDENING_ENV_VAR, hardening)
    client = MagicMock()
    with (
        patch("opik_backend.executor_docker.docker.from_env", return_value=client),
        patch.object(DockerExecutor, "_pre_warm_container_pool"),
        patch.object(DockerExecutor, "_start_pool_monitor"),
    ):
        executor = DockerExecutor()
        try:
            executor.create_container()
        finally:
            executor.stop_event.set()

    actual_options = {
        key: value for key, value in client.containers.run.call_args.kwargs.items() if key in HARDENED_DEFAULTS
    }
    assert actual_options == expected_options


@pytest.mark.parametrize(
    "allow_network, expected_network_config",
    [("false", {"bridge": "none", "iptables": False, "ip6tables": False}), ("true", {})],
)
def test_daemon_config_keeps_mounted_settings(tmp_path, monkeypatch, allow_network, expected_network_config):
    monkeypatch.setenv(ALLOW_NETWORK_ENV_VAR, allow_network)
    mounted_config = {
        "log-level": random.choice(["warn", "info", "debug"]),
        "max-concurrent-downloads": random.randint(1, 9),
    }
    operator_config_path = tmp_path / "etc" / "daemon.json"
    operator_config_path.parent.mkdir()
    operator_config_path.write_text(json.dumps(mounted_config))
    operator_config_path.chmod(0o444)
    hardened_config_path = tmp_path / "run" / "daemon.json"

    write_hardened_config(str(operator_config_path), str(hardened_config_path))

    expected = {**mounted_config, "userns-remap": "default", **expected_network_config}
    assert json.loads(hardened_config_path.read_text()) == expected
    assert json.loads(operator_config_path.read_text()) == mounted_config


def test_daemon_config_without_mounted_file(tmp_path):
    hardened_config_path = tmp_path / "daemon.json"

    write_hardened_config(str(tmp_path / "missing.json"), str(hardened_config_path))

    expected = {"userns-remap": "default", "bridge": "none", "iptables": False, "ip6tables": False}
    assert json.loads(hardened_config_path.read_text()) == expected


def test_malformed_mounted_daemon_config_fails(tmp_path):
    operator_config_path = tmp_path / "daemon.json"
    operator_config_path.write_text("{not json")

    with pytest.raises(ValueError):
        write_hardened_config(str(operator_config_path), str(tmp_path / "out" / "daemon.json"))


@pytest.mark.parametrize("content", ["null", "[]", "42", "\"text\""])
def test_non_object_mounted_daemon_config_fails(tmp_path, content):
    operator_config_path = tmp_path / "daemon.json"
    operator_config_path.write_text(content)

    with pytest.raises(ValueError, match="must hold a JSON object"):
        write_hardened_config(str(operator_config_path), str(tmp_path / "out" / "daemon.json"))


@pytest.fixture
def config_paths(tmp_path, monkeypatch):
    operator_config_path = tmp_path / "etc" / "daemon.json"
    hardened_config_path = tmp_path / "run" / "daemon.json"
    monkeypatch.setattr(docker_daemon_config, "OPERATOR_CONFIG_PATH", str(operator_config_path))
    monkeypatch.setattr(docker_daemon_config, "HARDENED_CONFIG_PATH", str(hardened_config_path))
    return operator_config_path, hardened_config_path


@pytest.mark.parametrize("hardening", [None, "false"])
def test_entrypoint_contract_prints_nothing_when_off(monkeypatch, capsys, config_paths, hardening):
    if hardening is not None:
        monkeypatch.setenv(HARDENING_ENV_VAR, hardening)

    docker_daemon_config.main()

    assert capsys.readouterr().out == ""
    assert not config_paths[1].exists()


def test_entrypoint_contract_prints_config_path_when_on(monkeypatch, capsys, config_paths):
    monkeypatch.setenv(HARDENING_ENV_VAR, "true")

    docker_daemon_config.main()

    assert capsys.readouterr().out == f"{config_paths[1]}\n"
    assert json.loads(config_paths[1].read_text())["userns-remap"] == "default"


@pytest.mark.parametrize(
    "hardening, mounted_content, expected_error",
    [("yes", None, "must be true or false"), ("true", "{not json", "Expecting"), ("true", "[]", "JSON object")],
)
def test_entrypoint_contract_fails_startup(
    monkeypatch, capsys, config_paths, hardening, mounted_content, expected_error
):
    monkeypatch.setenv(HARDENING_ENV_VAR, hardening)
    if mounted_content is not None:
        config_paths[0].parent.mkdir()
        config_paths[0].write_text(mounted_content)

    with pytest.raises(SystemExit) as exit_info:
        docker_daemon_config.main()

    assert exit_info.value.code.startswith("Docker daemon hardening failed")
    assert expected_error in exit_info.value.code
    assert capsys.readouterr().out == ""
