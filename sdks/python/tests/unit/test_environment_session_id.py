"""
`session_id` is what ties one run's events together, across the handoff as well as
within a process.

`npx opik …` reports why a run started and then execs `uvx opik …`, which reports
what it did. Two halves of one command, so they have to carry one id: the launcher
mints it and passes it down rather than each side inventing its own.
"""

import importlib

import pytest

from opik import environment_details


@pytest.fixture(autouse=True)
def uncached():
    """Collected once per process, and these tests each need a fresh read."""
    environment_details.collect_context_once.cache_clear()
    yield
    environment_details.collect_context_once.cache_clear()


def _session_id(monkeypatch, inherited):
    monkeypatch.setattr(environment_details, "_INHERITED_SESSION_ID", inherited)
    return environment_details.collect_context_once()["session_id"]


def test_session_id__launcher_passed_one__it_is_used(monkeypatch):
    """The join: the wrapper's events and this process's describe one run."""
    assert _session_id(monkeypatch, "AbCdEfGhI") == "AbCdEfGhI"


def test_session_id__no_launcher__one_is_generated(monkeypatch):
    """A run started by hand has nothing to inherit."""
    generated = _session_id(monkeypatch, "")

    assert len(generated) == environment_details.SESSION_ID_LENGTH
    assert generated.isalpha()


def test_run_context__launcher_named__is_reported(monkeypatch):
    monkeypatch.setattr(environment_details, "_LAUNCHER", "npx")
    monkeypatch.setattr(environment_details, "_RUN_CONTEXT", {})

    assert environment_details.run_context() == {"cli_launcher": "npx"}


@pytest.fixture
def reimported(monkeypatch):
    """Re-runs the module's import-time read, then restores the real module state."""

    def reimport(**env):
        for name, value in env.items():
            monkeypatch.setenv(name, value)
        return importlib.reload(environment_details)

    yield reimport
    monkeypatch.undo()
    importlib.reload(environment_details)


def test_launcher_values__are_read_from_the_environment(reimported):
    module = reimported(OPIK_CLI_SESSION_ID=" AbCdEfGhI ", OPIK_CLI_LAUNCHER="npx")

    assert module._INHERITED_SESSION_ID == "AbCdEfGhI"
    assert module._LAUNCHER == "npx"


def test_launcher_values__blank__are_not_used(reimported):
    """An empty variable is not an id, and sharing one would merge every such run."""
    module = reimported(OPIK_CLI_SESSION_ID="   ")

    generated = module.collect_context_once()["session_id"]

    assert len(generated) == module.SESSION_ID_LENGTH
    assert generated.isalpha()


def test_launcher_values__are_not_passed_on_to_child_processes(reimported):
    """The launcher described this command, not whatever it starts.

    `opik mcp configure` ends by exec'ing into the user's agent, and `opik run`
    starts the user's app. Left in the environment, every Opik process under
    either would report this run's `session_id` and `cli_launcher` as its own.
    """
    import os

    reimported(OPIK_CLI_SESSION_ID="AbCdEfGhI", OPIK_CLI_LAUNCHER="npx")

    assert "OPIK_CLI_SESSION_ID" not in os.environ
    assert "OPIK_CLI_LAUNCHER" not in os.environ


def test_session_id__forked_child__does_not_keep_the_launcher_id(monkeypatch):
    """A child is its own process, and the launcher's id named the parent."""
    assert _session_id(monkeypatch, "AbCdEfGhI") == "AbCdEfGhI"

    environment_details._reset_after_fork()

    assert environment_details.collect_context_once()["session_id"] != "AbCdEfGhI"
