"""Unit tests for the e2e provider-resolution helpers (tests/e2e/conftest.py).

The e2e fixture provisions a workspace provider key for whichever provider the
configured model needs. Getting that wrong makes the backend reject the run for a
missing key of the *other* provider, so the mapping is worth pinning down here —
these are plain functions and need no backend.
"""

import importlib.util
import pathlib
import sys

import pytest

_CONFTEST_PATH = (
    pathlib.Path(__file__).resolve().parents[1] / "e2e" / "conftest.py"
)


def _load_e2e_conftest():
    """Import tests/e2e/conftest.py as a module without collecting the e2e suite."""
    spec = importlib.util.spec_from_file_location(
        "_studio_e2e_conftest", _CONFTEST_PATH
    )
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def e2e_conftest():
    return _load_e2e_conftest()


class TestProviderForModel:
    @pytest.mark.parametrize(
        "model,expected",
        [
            # Bare ids
            ("gpt-5-nano", "openai"),
            ("gpt-4o-mini", "openai"),
            ("claude-haiku-4-5-20251001", "anthropic"),
            # Gateway-prefixed ids — the Studio routes everything as openai/*
            ("openai/gpt-4o", "openai"),
            ("openai/claude-haiku-4-5", "openai"),
            ("anthropic/claude-haiku-4-5", "anthropic"),
            # Unset / unknown fall back to the CI default (anthropic)
            (None, "anthropic"),
            ("", "anthropic"),
            ("some-local-model", "anthropic"),
        ],
    )
    def test_provider_for_model(self, e2e_conftest, model, expected):
        assert e2e_conftest._provider_for_model(model) == expected

    def test_every_provider_has_a_secret_env(self, e2e_conftest):
        """workspace_provider_key indexes this map directly — a provider without
        an entry would raise KeyError instead of skipping cleanly."""
        for model in ("gpt-4o", "openai/gpt-4o", "claude-haiku-4-5", None):
            provider = e2e_conftest._provider_for_model(model)
            assert provider in e2e_conftest._PROVIDER_SECRET_ENV

    def test_secret_env_names(self, e2e_conftest):
        assert e2e_conftest._PROVIDER_SECRET_ENV == {
            "anthropic": "ANTHROPIC_API_KEY",
            "openai": "OPENAI_API_KEY",
        }
