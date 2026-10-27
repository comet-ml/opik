"""Tests that gateway-routed LLM calls carry the Comet-Workspace header.

Optimization Studio routes every LiteLLM completion through the Opik backend
gateway (OPENAI_API_BASE, set in optimizer.py). The gateway authenticates each
request per workspace via the Comet-Workspace header, so the subprocess runner
must attach it to every completion and async completion. Without it the gateway
rejects every call with 403 "Workspace name should be provided". These tests
cover the wrapping seam in optimizer_runner.
"""

import asyncio
import logging
import sys
import types

import pytest

from opik_backend.jobs import optimizer_runner
from opik_backend.jobs.optimizer_runner import route_litellm_calls_through_gateway


@pytest.fixture(autouse=True)
def reset_gateway_state(monkeypatch):
    """Isolate the module-level workspace container and drop log between tests."""
    monkeypatch.setattr(optimizer_runner, "_gateway_workspace", {"name": None})
    monkeypatch.setattr(optimizer_runner, "_dropped_params_logged", set())
    monkeypatch.setattr(optimizer_runner, "_dropped_headers_logged", set())


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
    monkeypatch.setattr(
        optimizer_runner, "OPENAI_API_BASE", "http://gateway/v1/private"
    )


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

    @pytest.mark.parametrize("call", ["completion", "acompletion"])
    def test_caller_identity_headers_never_reach_the_gateway(self, fake_litellm, gateway_env, call):
        route_litellm_calls_through_gateway("my-workspace")
        caller_headers = {
            "comet-workspace": "other", "COMET-WORKSPACE": "other", "Authorization": "x",
            "proxy-authorization": "y", "Cookie": "sessionToken=z", "X-Custom": "1",
        }

        result = getattr(fake_litellm, call)(model="openai/m", messages=[], extra_headers=caller_headers)
        if call == "acompletion":
            asyncio.run(result)

        assert fake_litellm.calls == [{
            "model": "openai/m",
            "messages": [],
            "api_base": "http://gateway/v1/private",
            "extra_headers": {"X-Custom": "1", "Comet-Workspace": "my-workspace"},
        }]

    def test_logs_dropped_header_names_once_without_values(self, fake_litellm, gateway_env, caplog):
        route_litellm_calls_through_gateway("my-workspace")

        with caplog.at_level(logging.WARNING, logger=optimizer_runner.__name__):
            for _ in range(3):
                fake_litellm.completion(messages=[], extra_headers={"comet-workspace": "other", "Authorization": "x"})

        assert [r.getMessage() for r in caplog.records] == [
            "Dropped headers not passed to the gateway: ['Authorization', 'comet-workspace']"]

    def test_noop_when_gateway_not_active(self, fake_litellm, monkeypatch):
        monkeypatch.setattr(optimizer_runner, "OPENAI_API_BASE", None)

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

    def test_updates_workspace_without_rewrapping(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("workspace-a")
        wrapped_once = fake_litellm.completion

        route_litellm_calls_through_gateway("workspace-b")

        # Same wrapper object (no nested re-wrap)...
        assert fake_litellm.completion is wrapped_once
        # ...but it now injects the updated workspace.
        fake_litellm.completion(messages=[])
        assert fake_litellm.calls[-1]["extra_headers"] == {"Comet-Workspace": "workspace-b"}

    @pytest.mark.parametrize(
        "overrides",
        [
            {"api_base": "http://169.254.169.254/latest"},
            {"base_url": "http://10.0.0.1/v1"},
            {"custom_llm_provider": "ollama", "api_base": "http://127.0.0.1:11434"},
            {"api_key": "someone-elses-key", "api_version": "2024-01-01", "organization": "org"},
        ],
    )
    def test_pins_every_call_to_the_gateway(self, fake_litellm, gateway_env, overrides):
        route_litellm_calls_through_gateway("my-workspace")

        fake_litellm.completion(model="openai/gpt-4o-mini", messages=[], **overrides)
        asyncio.run(fake_litellm.acompletion(model="openai/gpt-4o-mini", messages=[], **overrides))

        assert len(fake_litellm.calls) == 2
        for call in fake_litellm.calls:
            assert call == {
                "model": "openai/gpt-4o-mini",
                "messages": [],
                "api_base": "http://gateway/v1/private",
                "extra_headers": {"Comet-Workspace": "my-workspace"},
            }


# Model parameters LiteLLM reads as a destination, a handler or a credential, none of them named by the old
# blocklist: fallbacks and model_list route the call to other deployments, and the SageMaker handler takes its
# URL from sagemaker_base_url, not api_base.
REROUTING_PARAMS = {
    "sagemaker_base_url": "http://10.0.0.1/invocations",
    "fallbacks": [{"model": "openai/other", "api_base": "http://10.0.0.2/v1"}],
    "model_list": [{"model_name": "m", "litellm_params": {"model": "openai/m", "api_base": "http://10.0.0.3"}}],
    "extra_body": {"url": "http://10.0.0.4"},
    "aws_region_name": "us-east-1",
    "vertex_project": "p",
    # LiteLLM OpenAI parameters that pick a tier, store or cache the call, or add retries or server-side tools.
    "service_tier": "flex",
    "store": True,
    "prompt_cache_key": "k",
    "max_retries": 9,
    "web_search_options": {},
}

# Every parameter a Studio call keeps, with a value of its own type.
GENERATION_PARAMS = {
    "temperature": 0.2, "top_p": 0.9, "max_tokens": 100, "max_completion_tokens": 100, "stop": ["\\n"],
    "presence_penalty": 0.1, "frequency_penalty": 0.1, "reasoning_effort": "low", "seed": 1, "n": 2,
    "response_format": {"type": "json_object"}, "logprobs": True, "top_logprobs": 3, "stream": False,
    "timeout": 30, "num_retries": 6, "metadata": {"opik": {}}, "tools": [], "tool_choice": "auto",
    "parallel_tool_calls": False, "custom_parameters": {"thinking_level": "low"},
    "top_k": 40, "min_p": 0.1, "top_a": 0.1, "repetition_penalty": 1.1,
    "user": "u", "safety_identifier": "s", "logit_bias": {"50256": -100}, "verbosity": "low",
    "prediction": {"type": "content", "content": "x"}, "stream_options": {"include_usage": True},
    "thinking": {"type": "enabled", "budget_tokens": 1024},
}


class TestModelParameterAllowlist:
    def test_drops_parameters_that_reroute_the_call(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("my-workspace")

        fake_litellm.completion(model="openai/m", messages=[], **REROUTING_PARAMS)
        asyncio.run(fake_litellm.acompletion(model="openai/m", messages=[], **REROUTING_PARAMS))

        assert fake_litellm.calls == [{
            "model": "openai/m",
            "messages": [],
            "api_base": "http://gateway/v1/private",
            "extra_headers": {"Comet-Workspace": "my-workspace"},
        }] * 2

    def test_keeps_generation_parameters(self, fake_litellm, gateway_env):
        route_litellm_calls_through_gateway("my-workspace")

        fake_litellm.completion(model="openai/m", messages=[], **GENERATION_PARAMS, **REROUTING_PARAMS)

        assert fake_litellm.calls == [{
            "model": "openai/m",
            "messages": [],
            **GENERATION_PARAMS,
            "api_base": "http://gateway/v1/private",
            "extra_headers": {"Comet-Workspace": "my-workspace"},
        }]

    def test_logs_dropped_names_once_without_values(self, fake_litellm, gateway_env, caplog):
        route_litellm_calls_through_gateway("my-workspace")

        with caplog.at_level(logging.WARNING, logger=optimizer_runner.__name__):
            for _ in range(3):
                fake_litellm.completion(model="openai/m", messages=[], **REROUTING_PARAMS)

        assert [r.getMessage() for r in caplog.records] == [
            f"Dropped model parameters not passed to the gateway: {sorted(REROUTING_PARAMS)}"]


@pytest.fixture
def recorded_litellm(monkeypatch, gateway_env):
    """The real litellm, with completion and acompletion replaced by recorders, then wrapped for the gateway."""
    import litellm

    calls = []

    def completion(*args, **kwargs):
        calls.append(kwargs)
        return litellm.ModelResponse(choices=[{"message": {"role": "assistant", "content": '{"score": 1, "reason": "ok"}'}}])

    async def acompletion(*args, **kwargs):
        return completion(*args, **kwargs)

    monkeypatch.setattr(litellm, "completion", completion)
    monkeypatch.setattr(litellm, "acompletion", acompletion)
    route_litellm_calls_through_gateway("my-workspace")
    return calls


# The generation settings the Studio form saves, as the runner hands them to opik_optimizer.
STUDIO_MODEL_PARAMS = {"temperature": 0.2, "max_tokens": 100, "top_p": 0.9, "frequency_penalty": 0.1,
                       "presence_penalty": 0.1, "reasoning_effort": "low", "stream": False,
                       "custom_parameters": {"thinking_level": "low"}}


def _call_optimizer():
    from opik_optimizer.core import llm_calls

    llm_calls.call_model(messages=[{"role": "user", "content": "hi"}], model="openai/m",
                         model_parameters=dict(STUDIO_MODEL_PARAMS))


def _call_agent():
    from opik_optimizer import ChatPrompt
    from opik_optimizer.agents.litellm_agent import LiteLLMAgent

    prompt = ChatPrompt(messages=[{"role": "user", "content": "{q}"}], model="openai/m",
                        model_parameters=dict(STUDIO_MODEL_PARAMS))
    LiteLLMAgent(project_name="p").invoke_agent(prompts={"p": prompt}, dataset_item={"q": "x"})


def _call_geval():
    from opik_backend.studio.metrics import MetricFactory

    MetricFactory.build("geval", {"task_introduction": "t", "evaluation_criteria": "c"}, "openai/m",
                        project_name="p")({"answer": "a"}, "output")


GATEWAY = {"api_base": "http://gateway/v1/private", "extra_headers": {"Comet-Workspace": "my-workspace"}}


@pytest.mark.parametrize("call, expected", [
    (_call_optimizer, {"model": "openai/m", "messages": [{"role": "user", "content": "hi"}],
                       **STUDIO_MODEL_PARAMS, **GATEWAY}),
    (_call_agent, {"model": "openai/m", "messages": [{"role": "user", "content": "x"}],
                   **STUDIO_MODEL_PARAMS, **GATEWAY}),
    # The metric takes no model parameters from the run, only the model.
    (_call_geval, {"model": "openai/m", **GATEWAY}),
])
def test_studio_call_sites_reach_the_gateway_with_their_values(recorded_litellm, caplog, call, expected):
    with caplog.at_level(logging.WARNING, logger=optimizer_runner.__name__):
        call()

    # GEval makes more than one call; every call carries the same values.
    assert recorded_litellm, "the call site made no LLM call"
    assert [{key: kwargs.get(key) for key in expected} for kwargs in recorded_litellm] == (
        [expected] * len(recorded_litellm))
    assert all(
        {frozenset(message) for message in kwargs["messages"]} == {frozenset({"role", "content"})}
        for kwargs in recorded_litellm)
    assert [r.getMessage() for r in caplog.records if r.getMessage().startswith("Dropped")] == []
