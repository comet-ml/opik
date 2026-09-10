"""Cost reporting for LiteLLM-routed ADK calls.

A ``litellm_proxy/<alias>`` route can never be priced from Opik's (provider, model)
table: "litellm_proxy" is not an ``opik.LLMProvider``, and ``model`` stays the proxy
alias because the OpenAI-compatible spec requires the response to echo the requested
model - so no proxy configuration can fix it either. The proxy does compute the real
cost server-side and LiteLLM hands it back on the response, so the integration has to
forward that instead of estimating one.

These tests build the same response objects a proxied LiteLLM call produces, which
keeps them offline. The end-to-end chain (live proxy -> ``x-litellm-response-cost``
-> ``_hidden_params`` -> span) was verified against a real local LiteLLM proxy when
this was fixed; what can regress silently afterwards is our side of it, which is what
is pinned here.
"""

import asyncio
from typing import AsyncGenerator

import litellm
import pytest
from google.adk import agents as adk_agents
from google.adk import models as adk_models
from google.adk import runners as adk_runners
from google.adk.models import base_llm, lite_llm as adk_lite_llm
from google.adk.models import llm_request
from google.adk.sessions import in_memory_session_service
from google.genai import types as genai_types

import opik
from opik.integrations.adk import OpikTracer
from opik.integrations.adk.patchers import (
    litellm_wrappers,
    llm_response_wrapper,
    patchers,
)
from . import helpers

PROXY_ALIAS = "gemini-3.5-flash"
PROXY_ROUTE = f"litellm_proxy/{PROXY_ALIAS}"
# The value a real proxy returned for one call while investigating the ticket.
RESPONSE_COST = 0.0009352500000000001
RESPONSE_TEXT = "It is sunny and 22C."


def _build_proxy_model_response() -> "litellm.types.utils.ModelResponse":
    """The response object a ``litellm_proxy/<alias>`` call produces.

    ``model`` echoes the proxy alias, and the cost the proxy computed arrives in
    ``_hidden_params`` - LiteLLM folds the ``x-litellm-response-cost`` response
    header into it. ``provider_and_model`` is what Opik's own ``acompletion``
    patch attaches upstream of this point.
    """
    response = litellm.types.utils.ModelResponse(
        id="chatcmpl-proxy-1",
        created=1758000000,
        model=PROXY_ALIAS,
        object="chat.completion",
        choices=[
            litellm.types.utils.Choices(
                index=0,
                finish_reason="stop",
                message=litellm.types.utils.Message(
                    role="assistant", content=RESPONSE_TEXT
                ),
            )
        ],
        usage=litellm.types.utils.Usage(
            prompt_tokens=100, completion_tokens=50, total_tokens=150
        ),
    )
    response._hidden_params = {"response_cost": RESPONSE_COST}
    response.provider_and_model = PROXY_ROUTE
    return response


def test_proxy_alias_is_unpriceable_by_the_price_table():
    """Pins the premise the whole fix rests on.

    If "litellm_proxy" ever became a real provider this forwarding would be
    redundant, and if the assumption is wrong the tests below would be asserting
    a workaround for a problem that does not exist.
    """
    with pytest.raises(ValueError):
        opik.LLMProvider("litellm_proxy")

    provider, model = litellm_wrappers.parse_provider_and_model(PROXY_ROUTE)
    assert provider == "litellm_proxy"
    assert model == PROXY_ALIAS


def test_generate_content_response_decorator__proxy_cost__attached_for_the_tracer():
    """The cost has to leave the LiteLLM boundary, where the raw response is.

    Asserted through the patched module attribute rather than the decorator
    directly, so this also covers that ``patch_adk`` still lands on the function
    ADK uses to convert LiteLLM responses.
    """
    patchers.patch_adk()

    llm_response = adk_lite_llm._model_response_to_generate_content_response(
        _build_proxy_model_response()
    )

    assert llm_response.custom_metadata["opik_response_cost"] == RESPONSE_COST
    # The usage/provider handling must keep working alongside it.
    assert llm_response.custom_metadata["provider"] == "litellm_proxy"
    assert llm_response.custom_metadata["model_version"] == PROXY_ALIAS


def test_generate_content_response_decorator__no_cost_reported__no_cost_key():
    """A call LiteLLM reported no cost for must not gain a null cost entry.

    ``total_cost=None`` is skipped by the span update, but an ``opik_response_cost``
    key would still leak into the logged span output.
    """
    patchers.patch_adk()
    model_response = _build_proxy_model_response()
    model_response._hidden_params = {}

    llm_response = adk_lite_llm._model_response_to_generate_content_response(
        model_response
    )

    assert "opik_response_cost" not in llm_response.custom_metadata


@pytest.mark.parametrize("raw_cost", [float("nan"), float("inf"), float("-inf")])
def test_try_get_response_cost__non_finite__returns_none(raw_cost):
    """A nan/inf cost must not reach the span.

    `float()` accepts both, and they serialize to bare NaN/Infinity, which is not
    valid JSON - so forwarding one risks the span it rides on, not just the cost.
    """
    model_response = _build_proxy_model_response()
    model_response._hidden_params = {"response_cost": raw_cost}

    assert litellm_wrappers.try_get_response_cost(model_response) is None


def test_try_get_response_cost__no_hidden_params__returns_none():
    """LiteLLM owns ``_hidden_params``; losing it must degrade, not raise.

    The conversion this runs inside is ADK's, so an exception here would cost the
    response, not just the cost.
    """
    assert litellm_wrappers.try_get_response_cost(object()) is None


def test_pop_response_cost__reads_and_removes_the_cost():
    """Popped rather than read, so the cost is reported as the span's cost
    instead of also being duplicated into the logged output."""
    result_dict = {"custom_metadata": {"opik_response_cost": RESPONSE_COST}}

    assert llm_response_wrapper.pop_response_cost(result_dict) == RESPONSE_COST
    assert result_dict["custom_metadata"] == {}


def test_pop_response_cost__unusable_usage__cost_still_read():
    """The cost is reported by the proxy, so unusable usage must not take it down."""
    result_dict = {
        "custom_metadata": {
            "opik_response_cost": RESPONSE_COST,
            "opik_usage": "not-a-usage-dict",
        }
    }

    assert llm_response_wrapper.pop_response_cost(result_dict) == RESPONSE_COST
    assert llm_response_wrapper.pop_llm_usage_data(result_dict, "litellm_proxy") is None


class _FakeLiteLlmProxyModel(base_llm.BaseLlm):
    """An ADK model that answers with a proxied LiteLLM response, offline.

    Converts through ``lite_llm._model_response_to_generate_content_response`` -
    the boundary Opik patches - so the run exercises the real conversion instead
    of a hand-built ``LlmResponse`` that would pass with the patching gone. The
    model name is the proxy route, which is what makes ``before_model_callback``
    resolve the unpriceable ("litellm_proxy", alias) pair.
    """

    model: str = PROXY_ROUTE

    async def generate_content_async(
        self, request: llm_request.LlmRequest, stream: bool = False
    ) -> AsyncGenerator[adk_models.LlmResponse, None]:
        yield adk_lite_llm._model_response_to_generate_content_response(
            _build_proxy_model_response()
        )


def _run_fake_proxy_agent() -> None:
    """Drive a real ADK agent through the fake proxied model, offline."""
    tracer = OpikTracer(project_name="adk-litellm-cost-test")
    agent = adk_agents.LlmAgent(
        name="weather_agent",
        model=_FakeLiteLlmProxyModel(),
        instruction="Answer the weather question.",
        before_agent_callback=tracer.before_agent_callback,
        after_agent_callback=tracer.after_agent_callback,
        before_model_callback=tracer.before_model_callback,
        after_model_callback=tracer.after_model_callback,
    )

    session_service = in_memory_session_service.InMemorySessionService()
    runner = adk_runners.Runner(
        agent=agent, app_name="litellm-cost-probe", session_service=session_service
    )

    async def _run() -> None:
        await session_service.create_session(
            app_name="litellm-cost-probe", user_id="u1", session_id="s1"
        )
        async for _ in runner.run_async(
            user_id="u1",
            session_id="s1",
            new_message=genai_types.Content(
                role="user", parts=[genai_types.Part(text="weather?")]
            ),
        ):
            pass

    asyncio.run(_run())
    tracer.flush()


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk_llm_span__litellm_proxy_cost__reaches_the_backend(fake_backend):
    """The user-facing symptom: the span the backend receives carries the cost.

    Every assertion above stops at one of our own boundaries, which is how a cost
    that parses correctly can still be dropped on the way to the span - the exact
    shape of this bug. So this asserts the recorded span.
    """
    _run_fake_proxy_agent()

    assert len(fake_backend.trace_trees) == 1
    llm_span = fake_backend.trace_trees[0].spans[0]

    assert llm_span.total_cost == RESPONSE_COST
    # Recorded despite the provider/model pair the price table cannot resolve --
    # a span that had somehow become priceable would not be proving anything.
    assert llm_span.provider == "litellm_proxy"
    assert llm_span.model == PROXY_ALIAS
    # The cost rides in custom_metadata, so it must not also be left in the output.
    assert "opik_response_cost" not in llm_span.output["custom_metadata"]


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk_llm_span__usage_extraction_raises__cost_still_recorded(
    fake_backend, monkeypatch
):
    """Why the cost is read before the usage, not alongside it.

    Both run in one try/except in ``after_model_callback``, and usage extraction
    does raise on payloads it cannot parse - so reading the cost afterwards would
    silently forfeit it to a problem it has nothing to do with. The cost comes
    from the proxy, not from the tokens; it has no reason to go down with them.
    """

    def _raise(*args, **kwargs):
        raise ValueError("usage extraction blew up")

    monkeypatch.setattr(llm_response_wrapper, "pop_llm_usage_data", _raise)

    _run_fake_proxy_agent()

    llm_span = fake_backend.trace_trees[0].spans[0]
    assert llm_span.usage is None
    assert llm_span.total_cost == RESPONSE_COST


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_adk_trace_output__no_span_to_charge__cost_marker_not_leaked(fake_backend):
    """The recovery path must not surface the cost marker as agent output.

    With no LLM span registered, after_model_callback can only recover the model
    output into the per-invocation cache, which after_agent_callback then stamps as
    the trace output. `opik_response_cost` is ours, not the model's, so leaving it in
    that dict would publish an internal marker as ordinary output.
    """
    tracer = OpikTracer(project_name="adk-litellm-cost-test")
    # Only the agent callbacks: without before_model_callback there is no span for
    # after_model_callback to find, which is the path being exercised.
    agent = adk_agents.LlmAgent(
        name="weather_agent",
        model=_FakeLiteLlmProxyModel(),
        instruction="Answer the weather question.",
        before_agent_callback=tracer.before_agent_callback,
        after_agent_callback=tracer.after_agent_callback,
        after_model_callback=tracer.after_model_callback,
    )

    session_service = in_memory_session_service.InMemorySessionService()
    runner = adk_runners.Runner(
        agent=agent, app_name="litellm-cost-probe", session_service=session_service
    )

    async def _run() -> None:
        await session_service.create_session(
            app_name="litellm-cost-probe", user_id="u1", session_id="s1"
        )
        async for _ in runner.run_async(
            user_id="u1",
            session_id="s1",
            new_message=genai_types.Content(
                role="user", parts=[genai_types.Part(text="weather?")]
            ),
        ):
            pass

    asyncio.run(_run())
    tracer.flush()

    trace_output = fake_backend.trace_trees[0].output
    assert "opik_response_cost" not in (trace_output.get("custom_metadata") or {})
