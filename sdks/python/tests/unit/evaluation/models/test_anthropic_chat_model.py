import json
import sys
import types
from contextlib import asynccontextmanager, contextmanager
from types import SimpleNamespace
from unittest.mock import MagicMock, AsyncMock

import pydantic
import pytest

from opik.evaluation.models import models_factory
from opik.evaluation.models import base_model
from opik.evaluation.models.anthropic import anthropic_chat_model
from opik.evaluation.models.anthropic import message_adapter, response_parser


class SampleFormat(pydantic.BaseModel):
    score: int
    reason: str


def _make_text_response(text="ok"):
    block = SimpleNamespace(type="text", text=text)
    return SimpleNamespace(content=[block])


def _make_tool_use_response(data):
    block = SimpleNamespace(type="tool_use", input=data)
    return SimpleNamespace(content=[block])


def _install_anthropic_stub(monkeypatch):
    stub = types.ModuleType("anthropic")

    mock_messages = MagicMock()
    mock_messages.create = MagicMock(return_value=_make_text_response())

    mock_client = MagicMock()
    mock_client.messages = mock_messages

    async_mock_messages = MagicMock()
    async_mock_messages.create = AsyncMock(return_value=_make_text_response())

    async_mock_client = MagicMock()
    async_mock_client.messages = async_mock_messages

    stub.Anthropic = MagicMock(return_value=mock_client)
    stub.AsyncAnthropic = MagicMock(return_value=async_mock_client)

    monkeypatch.setitem(sys.modules, "anthropic", stub)
    return stub, mock_client, async_mock_client


@pytest.fixture(autouse=True)
def _clear_model_cache():
    models_factory._MODEL_CACHE.clear()
    yield
    models_factory._MODEL_CACHE.clear()


class TestResponseParser:
    def test_extract_text_content(self):
        response = _make_text_response("hello world")
        assert response_parser.extract_text_content(response) == "hello world"

    def test_extract_text_content_none_when_no_text(self):
        response = SimpleNamespace(content=[SimpleNamespace(type="tool_use", input={})])
        assert response_parser.extract_text_content(response) is None

    def test_extract_tool_use_content(self):
        data = {"score": 10, "reason": "good"}
        response = _make_tool_use_response(data)
        result = response_parser.extract_tool_use_content(response)
        assert json.loads(result) == data

    def test_extract_tool_use_content_none_when_no_tool(self):
        response = _make_text_response("hello")
        assert response_parser.extract_tool_use_content(response) is None


class TestMessageAdapter:
    def test_extracts_system_messages(self):
        messages = [
            {"role": "system", "content": "You are helpful."},
            {"role": "user", "content": "Hello"},
        ]
        system_text, non_system = message_adapter.extract_system_messages(messages)
        assert system_text == "You are helpful."
        assert len(non_system) == 1
        assert non_system[0]["role"] == "user"

    def test_multiple_system_messages(self):
        messages = [
            {"role": "system", "content": "Part 1"},
            {"role": "system", "content": "Part 2"},
            {"role": "user", "content": "Hello"},
        ]
        system_text, non_system = message_adapter.extract_system_messages(messages)
        assert system_text == "Part 1\n\nPart 2"
        assert len(non_system) == 1

    def test_no_system_messages(self):
        messages = [{"role": "user", "content": "Hello"}]
        system_text, non_system = message_adapter.extract_system_messages(messages)
        assert system_text is None
        assert len(non_system) == 1

    def test_converts_pydantic_model_to_output_config(self):
        config = message_adapter.pydantic_to_output_config(SampleFormat)
        assert config["format"]["type"] == "json_schema"
        schema = config["format"]["schema"]
        assert "score" in schema["properties"]
        assert "reason" in schema["properties"]
        assert "title" not in schema

    def test_strips_prefix(self):
        assert (
            message_adapter.strip_anthropic_prefix("anthropic/claude-sonnet-4-20250514")
            == "claude-sonnet-4-20250514"
        )

    def test_no_prefix(self):
        assert (
            message_adapter.strip_anthropic_prefix("claude-sonnet-4-20250514")
            == "claude-sonnet-4-20250514"
        )

    def test_filter_unsupported_params_drops_openai_specific(self):
        warned: set = set()
        result = message_adapter.filter_unsupported_params(
            {"temperature": 0.5, "logprobs": True, "top_logprobs": 20, "top_p": 0.9},
            warned,
        )
        assert result == {"temperature": 0.5, "top_p": 0.9}
        assert "logprobs" in warned
        assert "top_logprobs" in warned

    def test_filter_unsupported_params_warns_once(self):
        warned: set = set()
        message_adapter.filter_unsupported_params({"logprobs": True}, warned)
        message_adapter.filter_unsupported_params({"logprobs": True}, warned)
        assert warned == {"logprobs"}


class TestAnthropicChatModelGenerateString:
    def test_generate_string_text(self, monkeypatch):
        _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        @contextmanager
        def fake_provider_response(model_provider, messages, **kwargs):
            yield _make_text_response("test output")

        monkeypatch.setattr(base_model, "get_provider_response", fake_provider_response)

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )
        result = model.generate_string("hello")
        assert result == "test output"

    def test_generate_string_with_response_format(self, monkeypatch):
        _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        json_text = json.dumps({"score": 10, "reason": "good"})

        @contextmanager
        def fake_provider_response(model_provider, messages, **kwargs):
            yield _make_text_response(json_text)

        monkeypatch.setattr(base_model, "get_provider_response", fake_provider_response)

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )
        result = model.generate_string("hello", response_format=SampleFormat)
        parsed = json.loads(result)
        assert parsed["score"] == 10
        assert parsed["reason"] == "good"


class TestAnthropicChatModelProviderResponse:
    def test_passes_system_as_top_level_param(self, monkeypatch):
        _, mock_client, _ = _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )

        messages = [
            {"role": "system", "content": "Be helpful"},
            {"role": "user", "content": "Hello"},
        ]
        model.generate_provider_response(messages)

        call_kwargs = mock_client.messages.create.call_args
        assert call_kwargs.kwargs["system"] == "Be helpful"
        assert all(m["role"] != "system" for m in call_kwargs.kwargs["messages"])

    def test_response_format_uses_parse(self, monkeypatch):
        _, mock_client, _ = _install_anthropic_stub(monkeypatch)
        mock_client.messages.parse = MagicMock(return_value=_make_text_response())
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )

        messages = [{"role": "user", "content": "Score this"}]
        model.generate_provider_response(messages, response_format=SampleFormat)

        mock_client.messages.parse.assert_called_once()
        call_kwargs = mock_client.messages.parse.call_args.kwargs
        assert call_kwargs["output_format"] is SampleFormat
        assert "tools" not in call_kwargs
        assert "tool_choice" not in call_kwargs

    def test_default_max_tokens(self, monkeypatch):
        _, mock_client, _ = _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )

        model.generate_provider_response([{"role": "user", "content": "hi"}])
        call_kwargs = mock_client.messages.create.call_args.kwargs
        assert call_kwargs["max_tokens"] == 4096

    def test_strips_anthropic_prefix_in_api_call(self, monkeypatch):
        _, mock_client, _ = _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )

        model.generate_provider_response([{"role": "user", "content": "hi"}])
        call_kwargs = mock_client.messages.create.call_args.kwargs
        assert call_kwargs["model"] == "claude-sonnet-4-20250514"


class TestParamFiltering:
    def test_filters_unsupported_constructor_kwargs(self, monkeypatch):
        _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514",
            track=False,
            temperature=0.5,
            logprobs=True,
            top_logprobs=20,
            frequency_penalty=0.1,
        )

        assert model._completion_kwargs["temperature"] == 0.5
        assert "logprobs" not in model._completion_kwargs
        assert "top_logprobs" not in model._completion_kwargs
        assert "frequency_penalty" not in model._completion_kwargs

    def test_filters_unsupported_per_call_kwargs(self, monkeypatch):
        _, mock_client, _ = _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )

        model.generate_provider_response(
            [{"role": "user", "content": "hi"}],
            logprobs=True,
            top_logprobs=20,
            temperature=0.7,
        )

        call_kwargs = mock_client.messages.create.call_args.kwargs
        assert call_kwargs["temperature"] == 0.7
        assert "logprobs" not in call_kwargs
        assert "top_logprobs" not in call_kwargs

    def test_keeps_all_valid_anthropic_params(self, monkeypatch):
        _, mock_client, _ = _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )

        model.generate_provider_response(
            [{"role": "user", "content": "hi"}],
            temperature=0.5,
            top_p=0.9,
            top_k=40,
            stop_sequences=["END"],
        )

        call_kwargs = mock_client.messages.create.call_args.kwargs
        assert call_kwargs["temperature"] == 0.5
        assert call_kwargs["top_p"] == 0.9
        assert call_kwargs["top_k"] == 40
        assert call_kwargs["stop_sequences"] == ["END"]


class TestFactoryRouting:
    def test_factory_routes_anthropic_prefix(self, monkeypatch):
        _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = models_factory.get("anthropic/claude-sonnet-4-20250514", track=False)
        assert isinstance(model, anthropic_chat_model.AnthropicChatModel)

    def test_factory_routes_bare_claude_name(self, monkeypatch):
        _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = models_factory.get("claude-sonnet-4-20250514", track=False)
        assert isinstance(model, anthropic_chat_model.AnthropicChatModel)

    def test_factory_does_not_route_non_anthropic(self, monkeypatch):
        litellm_stub = types.ModuleType("litellm")
        litellm_stub.suppress_debug_info = False

        def completion(model, messages, **kwargs):
            return SimpleNamespace(
                choices=[SimpleNamespace(message=SimpleNamespace(content="ok"))]
            )

        litellm_stub.completion = completion
        litellm_stub.acompletion = completion
        litellm_stub.get_supported_openai_params = lambda model: [
            "temperature",
            "response_format",
        ]
        litellm_stub.get_llm_provider = lambda model: ("openai", "openai")
        litellm_stub.utils = SimpleNamespace(UnsupportedParamsError=Exception)
        litellm_stub.exceptions = SimpleNamespace(BadRequestError=Exception)
        litellm_stub.callbacks = []
        monkeypatch.setitem(sys.modules, "litellm", litellm_stub)

        from opik.evaluation.models.litellm import litellm_chat_model as lcm

        monkeypatch.setattr(
            lcm.litellm_integration,
            "track_completion",
            lambda **kw: (lambda f: f),
        )

        model = models_factory.get("gpt-4o", track=False)
        from opik.evaluation.models.litellm.litellm_chat_model import LiteLLMChatModel

        assert isinstance(model, LiteLLMChatModel)


@pytest.mark.asyncio
class TestAnthropicChatModelAsync:
    async def test_agenerate_string(self, monkeypatch):
        _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        @asynccontextmanager
        async def fake_aget_provider_response(model_provider, messages, **kwargs):
            yield _make_text_response("async result")

        monkeypatch.setattr(
            base_model, "aget_provider_response", fake_aget_provider_response
        )

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514", track=False
        )
        result = await model.agenerate_string("hello async")
        assert result == "async result"
