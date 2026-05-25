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


def _make_tool_use_response(data, *, block_id="call_1", name="json_tool_call"):
    block = SimpleNamespace(type="tool_use", id=block_id, name=name, input=data)
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
    def test_parses_text_response(self):
        message = response_parser.parse_assistant_message(
            _make_text_response("hello world")
        )
        assert message == {"role": "assistant", "content": "hello world"}

    def test_concatenates_multiple_text_blocks(self):
        response = SimpleNamespace(
            content=[
                SimpleNamespace(type="text", text="hello "),
                SimpleNamespace(type="text", text="world"),
            ]
        )
        message = response_parser.parse_assistant_message(response)
        assert message["content"] == "hello world"

    def test_promotes_single_tool_use_arguments_into_content(self):
        data = {"score": 10, "reason": "good"}
        message = response_parser.parse_assistant_message(_make_tool_use_response(data))
        assert message["role"] == "assistant"
        assert "tool_calls" not in message
        assert json.loads(message["content"]) == data

    def test_emits_tool_calls_when_text_and_tool_use_coexist(self):
        response = SimpleNamespace(
            content=[
                SimpleNamespace(type="text", text="picking a tool"),
                SimpleNamespace(
                    type="tool_use",
                    id="call_42",
                    name="web_search",
                    input={"query": "capital of France"},
                ),
            ]
        )
        message = response_parser.parse_assistant_message(response)
        assert message["content"] == "picking a tool"
        assert message["tool_calls"] == [
            {
                "id": "call_42",
                "type": "function",
                "function": {
                    "name": "web_search",
                    "arguments": json.dumps({"query": "capital of France"}),
                },
            }
        ]

    def test_raises_when_no_text_and_no_tool_use(self):
        from opik import exceptions

        response = SimpleNamespace(content=[])
        with pytest.raises(exceptions.BaseLLMError):
            response_parser.parse_assistant_message(response)

    def test_keeps_registered_tool_use_as_tool_call(self):
        """Regression: with `output_format` set, Anthropic emits the
        structured-output finalizer as a `tool_use` block too. Without
        disambiguation we'd misclassify a *real* registered-tool call
        (e.g. `read`) as the finalizer and promote its arguments to
        `content`, leaving the agentic loop with nothing to execute.
        Passing `registered_tool_names` lets the parser tell them apart.
        """
        response = SimpleNamespace(
            content=[
                SimpleNamespace(
                    type="tool_use",
                    id="call_42",
                    name="read",
                    input={"type": "trace", "id": "t-1"},
                ),
            ]
        )
        message = response_parser.parse_assistant_message(
            response, registered_tool_names=["read", "scan", "search"]
        )
        assert message["role"] == "assistant"
        assert "content" not in message
        assert message["tool_calls"] == [
            {
                "id": "call_42",
                "type": "function",
                "function": {
                    "name": "read",
                    "arguments": json.dumps({"type": "trace", "id": "t-1"}),
                },
            }
        ]

    def test_promotes_unknown_tool_use_when_tools_registered(self):
        """Counterpart to the previous test: when the single tool_use's
        name is NOT in the registered set, treat it as the structured-
        output finalizer (Anthropic's name for it varies by SDK version,
        but it's always not one of the user's tools).
        """
        data = {"score": 10, "reason": "good"}
        message = response_parser.parse_assistant_message(
            _make_tool_use_response(data),
            registered_tool_names=["read", "scan", "search"],
        )
        assert "tool_calls" not in message
        assert json.loads(message["content"]) == data


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

    def test_normalize_tool_choice_translates_openai_strings(self):
        # OpenAI-style string forms map to Anthropic's object form.
        assert message_adapter.normalize_tool_choice("auto") == {"type": "auto"}
        assert message_adapter.normalize_tool_choice("none") == {"type": "none"}
        # "required" → "any" (Anthropic's name for "force *some* tool").
        assert message_adapter.normalize_tool_choice("required") == {"type": "any"}

    def test_normalize_tool_choice_translates_openai_function_object(self):
        # OpenAI "force this specific function" → Anthropic "force this tool".
        translated = message_adapter.normalize_tool_choice(
            {"type": "function", "function": {"name": "read"}}
        )
        assert translated == {"type": "tool", "name": "read"}

    def test_normalize_tool_choice_passes_through_anthropic_native_shape(self):
        # Already-correct Anthropic forms shouldn't be touched.
        assert message_adapter.normalize_tool_choice({"type": "auto"}) == {
            "type": "auto"
        }
        assert message_adapter.normalize_tool_choice(
            {"type": "tool", "name": "read"}
        ) == {"type": "tool", "name": "read"}

    def test_normalize_tool_choice_passes_through_unknown_values(self):
        # Unrecognized strings or shapes pass through unchanged so the
        # Anthropic SDK can surface the error rather than us silently
        # dropping the field.
        assert message_adapter.normalize_tool_choice("bogus") == "bogus"
        assert message_adapter.normalize_tool_choice(
            {"type": "function"}  # missing function.name
        ) == {"type": "function"}

    def test_normalize_tools_translates_openai_function_specs(self):
        openai_spec = {
            "type": "function",
            "function": {
                "name": "read",
                "description": "Fetch a trace by id.",
                "parameters": {
                    "type": "object",
                    "properties": {"id": {"type": "string"}},
                    "required": ["id"],
                },
            },
        }
        translated = message_adapter.normalize_tools([openai_spec])
        assert translated == [
            {
                "type": "custom",
                "name": "read",
                "description": "Fetch a trace by id.",
                "input_schema": {
                    "type": "object",
                    "properties": {"id": {"type": "string"}},
                    "required": ["id"],
                },
            }
        ]

    def test_normalize_tools_passes_through_native_anthropic_specs(self):
        # Hand-rolled Anthropic-native specs (no `type=function` wrapper)
        # must not be rewritten — we have no information to safely map
        # them, and rewriting would corrupt a working spec.
        native = {
            "type": "custom",
            "name": "scan",
            "description": "Evaluate a jq path.",
            "input_schema": {"type": "object"},
        }
        assert message_adapter.normalize_tools([native]) == [native]

    def test_normalize_tools_passes_through_malformed_specs(self):
        # No function.name → unrecognizable; pass through so the SDK
        # surfaces the error rather than us masking it.
        malformed = {"type": "function", "function": {"description": "x"}}
        assert message_adapter.normalize_tools([malformed]) == [malformed]

    def test_normalize_tools_passes_through_non_list_input(self):
        # `None` (or any non-list sentinel) should not blow up — just
        # return it so the SDK's own validation handles it.
        assert message_adapter.normalize_tools(None) is None

    def test_extract_tool_names_handles_openai_shape(self):
        tools = [
            {"type": "function", "function": {"name": "read"}},
            {"type": "function", "function": {"name": "scan"}},
        ]
        assert message_adapter.extract_tool_names(tools) == ["read", "scan"]

    def test_extract_tool_names_handles_anthropic_native_shape(self):
        # After `normalize_tools` runs, names live at the top level.
        # `extract_tool_names` must handle both shapes so it stays
        # usable on either side of normalization.
        tools = [
            {"type": "custom", "name": "read"},
            {"type": "custom", "name": "search"},
        ]
        assert message_adapter.extract_tool_names(tools) == ["read", "search"]

    def test_extract_tool_names_skips_malformed_entries(self):
        tools = [
            {"type": "function"},  # missing function dict
            {"type": "function", "function": {"description": "x"}},  # no name
            {"type": "custom", "name": "read"},  # well-formed
            "not a dict",  # ignored
        ]
        assert message_adapter.extract_tool_names(tools) == ["read"]

    def test_extract_tool_names_returns_empty_for_non_list(self):
        assert message_adapter.extract_tool_names(None) == []
        assert message_adapter.extract_tool_names("nope") == []

    def test_normalize_messages_passes_through_plain_history(self):
        # User + plain assistant text → no shape change beyond the
        # `tool_calls=None` cleanup that pop'd through the loop.
        messages = [
            {"role": "user", "content": "hi"},
            {"role": "assistant", "content": "hello"},
        ]
        assert message_adapter.normalize_messages(messages) == messages

    def test_normalize_messages_converts_assistant_tool_calls_to_blocks(self):
        # Assistant message with one tool_call → assistant message
        # whose content is a list of blocks (no leading text block
        # when `content` is empty/None).
        messages = [
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                            "name": "read",
                            "arguments": json.dumps({"type": "trace", "id": "t-1"}),
                        },
                    }
                ],
            }
        ]
        assert message_adapter.normalize_messages(messages) == [
            {
                "role": "assistant",
                "content": [
                    {
                        "type": "tool_use",
                        "id": "call_1",
                        "name": "read",
                        "input": {"type": "trace", "id": "t-1"},
                    }
                ],
            }
        ]

    def test_normalize_messages_keeps_leading_text_block(self):
        # Assistant emits text alongside the tool_use — both blocks
        # must appear, text first.
        messages = [
            {
                "role": "assistant",
                "content": "let me check",
                "tool_calls": [
                    {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                            "name": "read",
                            "arguments": "{}",
                        },
                    }
                ],
            }
        ]
        normalized = message_adapter.normalize_messages(messages)
        assert normalized[0]["content"] == [
            {"type": "text", "text": "let me check"},
            {"type": "tool_use", "id": "call_1", "name": "read", "input": {}},
        ]

    def test_normalize_messages_translates_tool_role_to_user_tool_result(self):
        messages = [
            {
                "role": "tool",
                "tool_call_id": "call_1",
                "content": "{'data': 'value'}",
            }
        ]
        assert message_adapter.normalize_messages(messages) == [
            {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": "call_1",
                        "content": "{'data': 'value'}",
                    }
                ],
            }
        ]

    def test_normalize_messages_coalesces_consecutive_tool_messages(self):
        # Two tool replies in a row must end up as a single user
        # message with two tool_result blocks — Anthropic rejects
        # split tool_result responses when the prior assistant turn
        # emitted multiple tool_use blocks.
        messages = [
            {"role": "tool", "tool_call_id": "call_1", "content": "result-1"},
            {"role": "tool", "tool_call_id": "call_2", "content": "result-2"},
        ]
        assert message_adapter.normalize_messages(messages) == [
            {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": "call_1",
                        "content": "result-1",
                    },
                    {
                        "type": "tool_result",
                        "tool_use_id": "call_2",
                        "content": "result-2",
                    },
                ],
            }
        ]

    def test_normalize_messages_coerces_non_object_arguments_to_empty_dict(self):
        """Anthropic's `tool_use.input` is specified as a JSON object.
        OpenAI's `arguments` is *almost* always a stringified dict, but
        a malformed model output (top-level array, scalar, null, or
        non-JSON text) could leak a non-dict value through. The
        translator must coerce those to `{}` so we hit the SDK's own
        schema validation instead of a generic 400 from the API.
        """
        # Top-level JSON list → empty dict.
        messages = [
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                            "name": "read",
                            "arguments": json.dumps([1, 2, 3]),
                        },
                    }
                ],
            }
        ]
        normalized = message_adapter.normalize_messages(messages)
        assert normalized[0]["content"][0]["input"] == {}

        # Top-level scalar → empty dict.
        messages[0]["tool_calls"][0]["function"]["arguments"] = json.dumps(42)
        assert (
            message_adapter.normalize_messages(messages)[0]["content"][0]["input"] == {}
        )

        # Null → empty dict.
        messages[0]["tool_calls"][0]["function"]["arguments"] = "null"
        assert (
            message_adapter.normalize_messages(messages)[0]["content"][0]["input"] == {}
        )

        # Non-JSON text → empty dict.
        messages[0]["tool_calls"][0]["function"]["arguments"] = "not json"
        assert (
            message_adapter.normalize_messages(messages)[0]["content"][0]["input"] == {}
        )

        # Already-a-list (not a string) → empty dict too — we only
        # forward dict-shaped values.
        messages[0]["tool_calls"][0]["function"]["arguments"] = [1, 2]
        assert (
            message_adapter.normalize_messages(messages)[0]["content"][0]["input"] == {}
        )

    def test_normalize_messages_full_round_trip(self):
        # End-to-end: a typical agentic-loop history with one
        # round-trip should land in valid Anthropic shape.
        messages = [
            {"role": "user", "content": "find the marker"},
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                            "name": "read",
                            "arguments": json.dumps({"type": "trace", "id": "t-1"}),
                        },
                    }
                ],
            },
            {"role": "tool", "tool_call_id": "call_1", "content": "MARKER-XYZ-987"},
        ]
        normalized = message_adapter.normalize_messages(messages)
        # User → unchanged.
        assert normalized[0] == {"role": "user", "content": "find the marker"}
        # Assistant → tool_use content block.
        assert normalized[1]["role"] == "assistant"
        assert normalized[1]["content"][0]["type"] == "tool_use"
        # Tool result → user message with tool_result block.
        assert normalized[2]["role"] == "user"
        assert normalized[2]["content"][0]["type"] == "tool_result"
        assert normalized[2]["content"][0]["tool_use_id"] == "call_1"


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

    def test_constructor_tools_feed_response_parser_disambiguation(self, monkeypatch):
        """Regression: when `tools` is supplied only at construction time
        (the agentic loop's default path through the factory), the
        response parser still needs the registered names to tell a real
        `read` tool call from the structured-output finalizer. Looking
        only at the per-call `kwargs` (as the original code did) would
        miss constructor-time tools and leave the parser blind.
        """
        _, mock_client, _ = _install_anthropic_stub(monkeypatch)
        mock_client.messages.parse = MagicMock(
            return_value=SimpleNamespace(
                content=[
                    SimpleNamespace(
                        type="tool_use",
                        id="call_42",
                        name="read",
                        input={"type": "trace", "id": "t-1"},
                    )
                ]
            )
        )
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514",
            track=False,
            tools=[
                {
                    "type": "function",
                    "function": {
                        "name": "read",
                        "description": "Fetch a trace.",
                        "parameters": {"type": "object", "properties": {}},
                    },
                },
            ],
        )

        # Per-call `tools` omitted on purpose — the constructor-time
        # list must still reach the parser.
        message = model.generate_chat_completion(
            messages=[{"role": "user", "content": "go"}],
            response_format=SampleFormat,
        )

        # If the parser got the registered names, the `read` tool_use
        # block stays as a tool_call. Without them, it would have been
        # promoted to `content` and the test would see `tool_calls`
        # missing.
        assert message["tool_calls"] == [
            {
                "id": "call_42",
                "type": "function",
                "function": {
                    "name": "read",
                    "arguments": json.dumps({"type": "trace", "id": "t-1"}),
                },
            }
        ]

    def test_per_call_tools_override_constructor_tools_for_parser(self, monkeypatch):
        """Per-call `tools` replace constructor-time `tools` in
        `_build_call_kwargs` (last-write-wins merge). The names the
        parser sees must follow the same precedence — otherwise a
        caller who narrows tools per-call could still get a tool_use
        block misclassified because the parser was keyed on the wider
        constructor set, or vice versa.
        """
        _, mock_client, _ = _install_anthropic_stub(monkeypatch)
        # Response uses the per-call tool name (`scan`), not the
        # constructor name (`read`) — proves the per-call list wins.
        mock_client.messages.create = MagicMock(
            return_value=SimpleNamespace(
                content=[
                    SimpleNamespace(
                        type="tool_use",
                        id="call_99",
                        name="scan",
                        input={"path": "$.trace"},
                    )
                ]
            )
        )
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514",
            track=False,
            tools=[
                {
                    "type": "function",
                    "function": {
                        "name": "read",
                        "description": "Fetch a trace.",
                        "parameters": {"type": "object"},
                    },
                },
            ],
        )

        message = model.generate_chat_completion(
            messages=[{"role": "user", "content": "go"}],
            tools=[
                {
                    "type": "function",
                    "function": {
                        "name": "scan",
                        "description": "Evaluate a jq path.",
                        "parameters": {"type": "object"},
                    },
                },
            ],
        )

        # `scan` (the per-call tool) is in the registered set the
        # parser saw, so the response's `scan` tool_use stays as a
        # tool call. If the precedence was wrong, the parser would have
        # seen only `read` and promoted `scan` to content.
        assert message.get("tool_calls", [])[0]["function"]["name"] == "scan"


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

    def test_normalizes_constructor_tools_into_anthropic_shape(self, monkeypatch):
        """Regression: OpenAI-shape `tools` passed at construction time
        (e.g. via `AnthropicChatModel(tools=[...])` or the factory)
        must be normalized before they're merged into per-call kwargs.
        Without this, the per-call normalization in `_build_call_kwargs`
        only catches `tools` arriving as call-time kwargs, and the
        constructor-time list reaches the Anthropic SDK unchanged.
        """
        _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514",
            track=False,
            tools=[
                {
                    "type": "function",
                    "function": {
                        "name": "read",
                        "description": "Fetch a trace.",
                        "parameters": {"type": "object", "properties": {}},
                    },
                },
            ],
        )

        # Constructor-stored tools must be the Anthropic shape.
        assert model._completion_kwargs["tools"] == [
            {
                "type": "custom",
                "name": "read",
                "description": "Fetch a trace.",
                "input_schema": {"type": "object", "properties": {}},
            }
        ]

    def test_normalizes_constructor_tool_choice_into_anthropic_shape(self, monkeypatch):
        # Companion to the `tools` test: this is the existing
        # constructor-side normalization for `tool_choice`. Pinning it
        # here so future refactors that reshuffle the __init__ order
        # don't silently regress the pairing.
        _install_anthropic_stub(monkeypatch)
        monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "false")

        model = anthropic_chat_model.AnthropicChatModel(
            model_name="anthropic/claude-sonnet-4-20250514",
            track=False,
            tool_choice="auto",
        )
        assert model._completion_kwargs["tool_choice"] == {"type": "auto"}

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

        litellm_integration_stub = types.ModuleType("opik.integrations.litellm")
        litellm_integration_stub.track_completion = lambda **kw: (lambda f: f)
        monkeypatch.setitem(
            sys.modules, "opik.integrations.litellm", litellm_integration_stub
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
