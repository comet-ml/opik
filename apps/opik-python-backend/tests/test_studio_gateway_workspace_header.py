"""Tests that gateway-routed LLM calls carry the Comet-Workspace header.

Optimization Studio routes every LiteLLM completion through the Opik backend
gateway (OPENAI_API_BASE, set in optimizer.py). The gateway authenticates each
request per workspace via the Comet-Workspace header, so the subprocess runner
must attach it to every completion and async completion. Without it the gateway
rejects every call with 403 "Workspace name should be provided". These tests
cover the wrapping seam in optimizer_runner.
"""

import asyncio
import sys
import types

import pytest

from opik_backend.jobs.optimizer_runner import route_litellm_calls_through_gateway


@pytest.fixture
def fake_litellm(monkeypatch):
    """Install a stub `litellm` module that records the kwargs it receives."""
    calls = []

    def completion(*args, **kwargs):
        calls.append(kwargs)
        return "sync-response"

    async def acompletion(*args, **kwargs):
        calls.append(kwargs)
        return "async-response"

    module = types.ModuleType("litellm")
    module.completion = completion
    module.acompletion = acompletion
    module.calls = calls
    monkeypatch.setitem(sys.modules, "litellm", module)
    return module


@pytest.fixture
def gateway_env(monkeypatch):
    monkeypatch.setenv("OPENAI_API_BASE", "http://gateway/v1/private")


class TestRouteLitellmCallsThroughGateway:
    def test_injects_workspace_header_on_completion(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("my-workspace")

        result = fake_litellm.completion(model="openai/gpt-4o-mini", messages=[])

        assert result == "sync-response"
        assert fake_litellm.calls[-1]["extra_headers"]["Comet-Workspace"] == "my-workspace"

    def test_injects_workspace_header_on_acompletion(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("my-workspace")

        result = asyncio.run(
            fake_litellm.acompletion(model="openai/gpt-4o-mini", messages=[])
        )

        assert result == "async-response"
        assert fake_litellm.calls[-1]["extra_headers"]["Comet-Workspace"] == "my-workspace"

    def test_preserves_existing_extra_headers(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("my-workspace")

        fake_litellm.completion(messages=[], extra_headers={"X-Custom": "1"})

        extra_headers = fake_litellm.calls[-1]["extra_headers"]
        assert extra_headers["X-Custom"] == "1"
        assert extra_headers["Comet-Workspace"] == "my-workspace"

    def test_does_not_override_explicit_workspace_header(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("my-workspace")

        fake_litellm.completion(
            messages=[], extra_headers={"Comet-Workspace": "explicit-ws"}
        )

        assert fake_litellm.calls[-1]["extra_headers"]["Comet-Workspace"] == "explicit-ws"

    def test_noop_when_gateway_not_active(self, fake_litellm, monkeypatch):
        monkeypatch.delenv("OPENAI_API_BASE", raising=False)

        route_litellm_calls_through_gateway("my-workspace")
        fake_litellm.completion(messages=[])

        assert "extra_headers" not in fake_litellm.calls[-1]

    def test_noop_when_workspace_missing(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("")
        fake_litellm.completion(messages=[])

        assert "extra_headers" not in fake_litellm.calls[-1]

    @pytest.mark.parametrize(
        "workspace_name",
        [
            "scout:comet-ml/scout-test-repo",  # colon + slash (Scout-style)
            "team space",  # whitespace
            "wß-üñïçødé",  # non-ascii
            "a+b%c&d",  # url-reserved characters
        ],
    )
    def test_injects_special_character_workspace_verbatim(
        self, fake_litellm, gateway_env, workspace_name
    ):
        # The workspace name is the only value flowing into the gateway path;
        # it must reach the Comet-Workspace header unchanged (no encoding or
        # truncation) so the backend can match it exactly.
        route_litellm_calls_through_gateway(workspace_name)

        fake_litellm.completion(messages=[])

        assert (
            fake_litellm.calls[-1]["extra_headers"]["Comet-Workspace"]
            == workspace_name
        )

    def test_is_idempotent(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("my-workspace")
        wrapped_once = fake_litellm.completion
        route_litellm_calls_through_gateway("my-workspace")

        assert fake_litellm.completion is wrapped_once

        fake_litellm.completion(messages=[])
        # Header injected exactly once (no nested extra_headers stacking).
        assert fake_litellm.calls[-1]["extra_headers"] == {"Comet-Workspace": "my-workspace"}
