import json
import logging
import sys
import types
from contextlib import asynccontextmanager, contextmanager
from types import SimpleNamespace

import pytest

from opik.evaluation.metrics.llm_judges import g_eval
from opik.evaluation.models import models_factory
from opik.evaluation.models.litellm import litellm_chat_model
from opik.evaluation.models import base_model


def _install_litellm_stub(monkeypatch, *, supported_params=None):
    stub_module = types.ModuleType("litellm")
    stub_module.suppress_debug_info = False
    stub_module._calls = []

    if supported_params is None:
        supported_params = ["temperature", "response_format"]

    def completion(model, messages, **kwargs):
        stub_module._calls.append((model, messages, kwargs))
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="ok"))]
        )

    def get_supported_openai_params(model):
        return list(supported_params)

    def get_llm_provider(model):
        return ("openai", "openai")

    stub_module.completion = completion
    stub_module.get_supported_openai_params = get_supported_openai_params
    stub_module.get_llm_provider = get_llm_provider
    stub_module.utils = SimpleNamespace(UnsupportedParamsError=Exception)
    stub_module.exceptions = SimpleNamespace(BadRequestError=Exception)

    monkeypatch.setitem(sys.modules, "litellm", stub_module)
    return stub_module


@pytest.fixture(autouse=True)
def _clear_model_cache():
    models_factory._MODEL_CACHE.clear()
    yield
    models_factory._MODEL_CACHE.clear()


def test_models_factory_reuses_cached_instance(monkeypatch):
    _install_litellm_stub(monkeypatch)

    first_gpt5 = models_factory.get("gpt-5-nano")
    second_gpt5 = models_factory.get("gpt-5-nano")

    first_gpt4 = models_factory.get("gpt-4o")
    second_gpt4 = models_factory.get("gpt-4o")

    assert first_gpt5 is second_gpt5
    assert first_gpt4 is second_gpt4
    assert first_gpt5 is not first_gpt4


def test_models_factory_cache_freezes_unhashable(monkeypatch):
    _install_litellm_stub(monkeypatch)

    params = {"metadata": {"labels": ["a", "b"], "nested": {"c"}}}
    first = models_factory.get("gpt-4o", **params)
    second = models_factory.get("gpt-4o", **params)

    assert first is second


def test_models_factory_default_model(monkeypatch):
    _install_litellm_stub(monkeypatch)

    default_instance = models_factory.get(None)

    assert default_instance.model_name == "gpt-5-nano"


def test_litellm_chat_model_drops_temperature_for_gpt5(monkeypatch, caplog):
    stub = _install_litellm_stub(monkeypatch)

    caplog.set_level(logging.WARNING)
    model = litellm_chat_model.LiteLLMChatModel(
        model_name="gpt-5-nano",
        temperature=0.5,
    )

    assert "temperature" not in model._completion_kwargs
    assert any(
        "temperature" in record.message and "Dropping" in record.message
        for record in caplog.records
    )

    caplog.clear()
    model.generate_string("hello")

    assert stub._calls, "Expected completion to be invoked"
    _, _, kwargs = stub._calls[-1]
    assert "temperature" not in kwargs
    assert not caplog.records


def test_geval_passes_logprobs_only_when_supported(monkeypatch):
    _install_litellm_stub(
        monkeypatch, supported_params=["logprobs", "top_logprobs", "response_format"]
    )

    captured = {}

    @contextmanager
    def fake_get_provider_response(model_provider, messages, **kwargs):
        captured["kwargs"] = kwargs
        yield SimpleNamespace(
            choices=[
                {
                    "message": {"content": json.dumps({"score": 10, "reason": "ok"})},
                    "logprobs": {
                        "content": [
                            {},
                            {},
                            {},
                            {
                                "top_logprobs": [
                                    {"token": "10", "logprob": 0.0},
                                ],
                                "token": "10",
                            },
                        ]
                    },
                }
            ]
        )

    monkeypatch.setattr(base_model, "get_provider_response", fake_get_provider_response)

    metric = g_eval.GEval(
        task_introduction="intro",
        evaluation_criteria="criteria",
        model="gpt-4o",
    )
    metric.score("{}")

    assert captured["kwargs"]["logprobs"] is True
    assert captured["kwargs"]["top_logprobs"] == 20

    # Now simulate model without logprob support
    _install_litellm_stub(monkeypatch, supported_params=["response_format"])
    captured.clear()

    @contextmanager
    def fake_response_no_logprobs(model_provider, messages, **kwargs):
        captured["kwargs"] = kwargs
        yield SimpleNamespace(
            choices=[
                SimpleNamespace(
                    message=SimpleNamespace(
                        content=json.dumps({"score": 10, "reason": "ok"})
                    )
                )
            ]
        )

    monkeypatch.setattr(base_model, "get_provider_response", fake_response_no_logprobs)

    metric = g_eval.GEval(
        task_introduction="intro",
        evaluation_criteria="criteria",
        model="gpt-5-nano",
    )

    # Even if litellm claims logprob support, gpt-5 should drop them
    _install_litellm_stub(
        monkeypatch, supported_params=["logprobs", "top_logprobs", "response_format"]
    )
    captured.clear()

    monkeypatch.setattr(base_model, "get_provider_response", fake_response_no_logprobs)

    metric = g_eval.GEval(
        task_introduction="intro",
        evaluation_criteria="criteria",
        model="gpt-5-nano",
    )
    metric.score("{}")

    assert "logprobs" not in captured["kwargs"]
    assert "top_logprobs" not in captured["kwargs"]


@pytest.mark.asyncio
async def test_litellm_chat_model_agenerate_string_supports_dict_choices(monkeypatch):
    _install_litellm_stub(monkeypatch)

    captured_kwargs = {}

    @asynccontextmanager
    async def fake_aget_provider_response(model_provider, messages, **kwargs):
        captured_kwargs["messages"] = messages
        yield SimpleNamespace(
            choices=[
                {
                    "message": {"content": "async-ok"},
                    "logprobs": None,
                }
            ]
        )

    monkeypatch.setattr(base_model, "aget_provider_response", fake_aget_provider_response)

    model = litellm_chat_model.LiteLLMChatModel(model_name="gpt-4o")

    result = await model.agenerate_string(input="hello async")

    assert result == "async-ok"
    assert captured_kwargs["messages"][0]["content"] == "hello async"
