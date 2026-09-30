"""
`session_id` is what ties one run's events together, across the handoff as well as
within a process.

`npx opik …` reports why a run started and then execs `uvx opik …`, which reports
what it did. Two halves of one command, so they have to carry one id: the launcher
mints it and passes it down rather than each side inventing its own.
"""

import pytest

from opik import environment_details


@pytest.fixture(autouse=True)
def uncached():
    """Collected once per process, and these tests each need a fresh read."""
    environment_details.collect_context_once.cache_clear()
    yield
    environment_details.collect_context_once.cache_clear()


def _session_id(monkeypatch, inherited):
    if inherited is None:
        monkeypatch.delenv(environment_details.SESSION_ID_ENV_VAR, raising=False)
    else:
        monkeypatch.setenv(environment_details.SESSION_ID_ENV_VAR, inherited)
    return environment_details.collect_context_once()["session_id"]


def test_session_id__launcher_passed_one__it_is_used(monkeypatch):
    """The join: the wrapper's events and this process's describe one run."""
    assert _session_id(monkeypatch, "AbCdEfGhI") == "AbCdEfGhI"


def test_session_id__no_launcher__one_is_generated(monkeypatch):
    """A run started by hand has nothing to inherit."""
    generated = _session_id(monkeypatch, None)

    assert len(generated) == environment_details.SESSION_ID_LENGTH
    assert generated.isalpha()


def test_session_id__blank_launcher_value__is_not_used(monkeypatch):
    """An empty variable is not an id, and sharing one would merge every such run."""
    generated = _session_id(monkeypatch, "   ")

    assert generated.strip() != ""
    assert len(generated) == environment_details.SESSION_ID_LENGTH
