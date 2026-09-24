"""Unit tests for the e2e provider-resolution helpers (tests/e2e/conftest.py).

The e2e fixture provisions a workspace provider key for whichever provider the
configured model needs. Getting that wrong makes the backend reject the run for a
missing key of the *other* provider, so the mapping is worth pinning down here —
these are plain functions and need no backend.

The same applies to the preflight that chooses between them: it decides, from a
live probe, whether the suite runs on Anthropic or falls back to OpenAI. Both
directions are silent failures if wrong — a missed rejection fails every test
minutes later at the gateway, and an over-eager one moves a run onto a provider
nobody asked for. The probe is mocked here; the network call is the e2e suite's.
"""

import importlib.util
import os
import pathlib
import sys
from unittest import mock

import httpx
import pytest

from llm_constants import ANTHROPIC_CLAUDE_HAIKU, OPENAI_GPT_MINI, OPENAI_GPT_NANO

_CONFTEST_PATH = (
    pathlib.Path(__file__).resolve().parents[1] / "e2e" / "conftest.py"
)


_MODULE_NAME = "_studio_e2e_conftest"


@pytest.fixture(scope="module")
def e2e_conftest():
    """Import tests/e2e/conftest.py as a module without collecting the e2e suite.

    Registered in ``sys.modules`` only while the tests run (some import
    machinery resolves a module by name during execution) and removed afterwards
    so nothing later in the session can pick up this stale copy.
    """
    spec = importlib.util.spec_from_file_location(_MODULE_NAME, _CONFTEST_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    previous = sys.modules.get(_MODULE_NAME)
    sys.modules[_MODULE_NAME] = module
    try:
        spec.loader.exec_module(module)
        yield module
    finally:
        if previous is None:
            sys.modules.pop(_MODULE_NAME, None)
        else:
            sys.modules[_MODULE_NAME] = previous


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


def _response(status: int, payload: dict) -> httpx.Response:
    return httpx.Response(
        status_code=status, json=payload, request=httpx.Request("POST", "https://x")
    )


class TestAnthropicRejectionReason:
    """Only an authoritative rejection may condemn the credential.

    The probe decides whether the suite switches providers, so a transient
    failure that read as a rejection would silently move a run onto OpenAI (and
    a missed rejection puts it back to failing mid-optimization).
    """

    @pytest.mark.parametrize(
        "status,payload,rejected",
        [
            (200, {"content": []}, False),
            # The case this preflight exists for: the e2e key was disabled.
            (401, {"error": {"message": "API key is invalid."}}, True),
            (403, {"error": {"message": "Forbidden"}}, True),
            # Anthropic reports an exhausted balance as a 400, not a 402/401.
            (400, {"error": {"message": "Your credit balance is too low"}}, True),
            # ...but a 400 about the request must not condemn the key, or
            # retiring the probe model would move the suite onto OpenAI.
            (400, {"error": {"message": "model: unknown model"}}, False),
            (429, {"error": {"message": "slow down"}}, False),
            (500, {"error": {"message": "internal"}}, False),
        ],
    )
    def test_classifies_response(self, e2e_conftest, status, payload, rejected):
        with mock.patch.object(
            httpx, "post", return_value=_response(status, payload)
        ):
            reason = e2e_conftest._anthropic_rejection_reason("sk-test")
        assert (reason is not None) is rejected

    def test_network_failure_keeps_the_key(self, e2e_conftest):
        with mock.patch.object(
            httpx, "post", side_effect=httpx.ConnectTimeout("timeout")
        ):
            assert e2e_conftest._anthropic_rejection_reason("sk-test") is None


class TestResolveE2eModel:
    @pytest.fixture(autouse=True)
    def _clear_cache(self, e2e_conftest):
        # Cached per session in real runs; each case needs a fresh resolution.
        e2e_conftest.resolve_e2e_model.cache_clear()
        yield
        e2e_conftest.resolve_e2e_model.cache_clear()

    @pytest.mark.parametrize(
        "env,rejection,expected_model,expected_provider",
        [
            (
                {"ANTHROPIC_API_KEY": "a", "OPENAI_API_KEY": "o"},
                None,
                ANTHROPIC_CLAUDE_HAIKU,
                "anthropic",
            ),
            # The live case: Anthropic key present but disabled.
            (
                {"ANTHROPIC_API_KEY": "a", "OPENAI_API_KEY": "o"},
                "API key is invalid.",
                OPENAI_GPT_MINI,
                "openai",
            ),
            # No OpenAI to fall back to: stay on Anthropic so the fixture skips
            # with the rejection reason instead of failing mid-optimization.
            (
                {"ANTHROPIC_API_KEY": "a"},
                "API key is invalid.",
                ANTHROPIC_CLAUDE_HAIKU,
                "anthropic",
            ),
            ({"OPENAI_API_KEY": "o"}, None, OPENAI_GPT_MINI, "openai"),
            ({}, None, ANTHROPIC_CLAUDE_HAIKU, "anthropic"),
        ],
    )
    def test_resolution(
        self, e2e_conftest, env, rejection, expected_model, expected_provider
    ):
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(
            e2e_conftest, "_anthropic_rejection_reason", return_value=rejection
        ):
            model = e2e_conftest.resolve_e2e_model()
        assert model == expected_model
        assert e2e_conftest._provider_for_model(model) == expected_provider

    def test_explicit_model_wins_without_probing(self, e2e_conftest):
        """A pinned model must not be second-guessed — nor pay for a probe."""
        env = {"OPTSTUDIO_E2E_MODEL": "gpt-4o", "ANTHROPIC_API_KEY": "a"}
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(
            e2e_conftest, "_anthropic_rejection_reason"
        ) as probe:
            assert e2e_conftest.resolve_e2e_model() == "gpt-4o"
        probe.assert_not_called()

    def test_probe_runs_once_per_session(self, e2e_conftest):
        """Every test asks for the model; the probe is a live network call."""
        env = {"ANTHROPIC_API_KEY": "a", "OPENAI_API_KEY": "o"}
        with mock.patch.dict(os.environ, env, clear=True), mock.patch.object(
            e2e_conftest, "_anthropic_rejection_reason", return_value=None
        ) as probe:
            for _ in range(5):
                e2e_conftest.resolve_e2e_model()
        assert probe.call_count == 1

    def test_openai_fallback_is_not_the_sdk_default(self):
        """test_studio_optimization asserts the SDK default never leaks into
        traces. If the fallback were that model, a correct fallback run and the
        model-passing regression would be indistinguishable."""
        assert OPENAI_GPT_MINI != OPENAI_GPT_NANO
