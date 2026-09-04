"""
`get_user_identifier` labels both error reports and usage analytics, so anything that
makes two installs share one identifier makes them look like a single user.
"""

import pytest

from opik import config, environment


@pytest.fixture(autouse=True)
def uncached():
    """The identifier is cached for the process; these tests each need a fresh read."""
    environment.get_user_identifier.cache_clear()
    yield
    environment.get_user_identifier.cache_clear()


def _with_workspace(monkeypatch, workspace):
    monkeypatch.setattr(
        config, "OpikConfig", lambda **_: type("C", (), {"workspace": workspace})()
    )


def test_get_user_identifier__named_workspace__used_as_is(monkeypatch):
    _with_workspace(monkeypatch, "acme-corp")

    assert environment.get_user_identifier() == "acme-corp"


def test_get_user_identifier__default_workspace__falls_back_to_a_hash(monkeypatch):
    _with_workspace(monkeypatch, config.OPIK_WORKSPACE_DEFAULT_NAME)

    identifier = environment.get_user_identifier()

    assert environment.is_default_user_identifier(identifier)
    assert identifier != config.OPIK_WORKSPACE_DEFAULT_NAME


@pytest.mark.parametrize("workspace", ["", "   ", "\t", None])
def test_get_user_identifier__blank_workspace__falls_back_to_a_hash(
    monkeypatch, workspace
):
    """
    Returning the blank value would file every install that has one under a single
    identifier, and they would count as one very busy user.
    """
    _with_workspace(monkeypatch, workspace)

    assert environment.is_default_user_identifier(environment.get_user_identifier())


def test_get_user_identifier__padded_workspace__matches_the_unpadded_one(monkeypatch):
    _with_workspace(monkeypatch, "  acme-corp  ")

    assert environment.get_user_identifier() == "acme-corp"


def test_get_user_identifier__fallback__stable_across_calls(monkeypatch):
    """Unique-user counts depend on the same machine resolving the same way."""
    _with_workspace(monkeypatch, config.OPIK_WORKSPACE_DEFAULT_NAME)

    first = environment.get_user_identifier()
    environment.get_user_identifier.cache_clear()

    assert environment.get_user_identifier() == first
