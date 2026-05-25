import json
import logging
import sys
import types
from contextlib import asynccontextmanager, contextmanager
from types import SimpleNamespace

import pytest

from opik.evaluation.metrics.llm_judges import g_eval
from opik.evaluation.models import models_factory
from opik.evaluation.models.litellm import litellm_chat_model, response_parser
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

    async def acompletion(model, messages, **kwargs):
        # Async version for testing
        return await completion(model, messages, **kwargs)

    def get_supported_openai_params(model):
        return list(supported_params)

    def get_llm_provider(model):
        return ("openai", "openai")

    stub_module.completion = completion
    stub_module.acompletion = acompletion
    stub_module.get_supported_openai_params = get_supported_openai_params
    stub_module.get_llm_provider = get_llm_provider
    stub_module.utils = SimpleNamespace(UnsupportedParamsError=Exception)
    stub_module.exceptions = SimpleNamespace(BadRequestError=Exception)
    stub_module.callbacks = []

    monkeypatch.setitem(sys.modules, "litellm", stub_module)

    # Mock the track_completion decorator to be a no-op for unit tests
    def mock_track_completion(project_name=None):
        def decorator(func):
            # Mark as tracked to prevent actual tracking
            func.opik_tracked = True
            return func

        return decorator

    litellm_integration_stub = types.ModuleType("opik.integrations.litellm")
    litellm_integration_stub.track_completion = mock_track_completion
    monkeypatch.setitem(
        sys.modules, "opik.integrations.litellm", litellm_integration_stub
    )

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

    assert default_instance.model_name == "openai/gpt-5-nano"


def test_models_factory_default_model_from_env(monkeypatch):
    _install_litellm_stub(monkeypatch)
    monkeypatch.setenv("OPIK_DEFAULT_LLM", "gpt-4o-mini")

    default_instance = models_factory.get(None)

    assert default_instance.model_name == "gpt-4o-mini"


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


def test_litellm_chat_model_drops_temperature_for_provider_prefixed_gpt5(
    monkeypatch, caplog
):
    stub = _install_litellm_stub(monkeypatch)

    caplog.set_level(logging.WARNING)
    model = litellm_chat_model.LiteLLMChatModel(
        model_name="openai/gpt-5-nano",
        temperature=1e-8,
    )

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


def test_litellm_chat_model_drops_seed_when_provider_does_not_support(
    monkeypatch, caplog
):
    """`seed` is an OpenAI-shape param; Anthropic (and a handful of
    other providers) reject it with `UnsupportedParamsError` rather
    than ignoring it. The native `AnthropicChatModel` silently filters
    it; this test pins the same behavior on the LiteLLM path so callers
    that pass `seed` (e.g. the agentic judge integration tests for
    reproducibility) don't blow up when the underlying provider is
    Anthropic.
    """
    stub = _install_litellm_stub(
        monkeypatch,
        # Mirror Anthropic's litellm-reported support: no `seed`.
        supported_params=["temperature", "response_format", "tools", "tool_choice"],
    )

    caplog.set_level(
        logging.DEBUG, logger="opik.evaluation.models.litellm.litellm_chat_model"
    )
    model = litellm_chat_model.LiteLLMChatModel(
        model_name="anthropic/claude-haiku-4-5",
        seed=42,
        temperature=0.0,
    )

    # Constructor strips the unsupported param so it never reaches
    # litellm.completion.
    assert "seed" not in model._completion_kwargs
    assert "temperature" in model._completion_kwargs

    model.generate_string("hello")

    assert stub._calls, "Expected completion to be invoked"
    _, _, kwargs = stub._calls[-1]
    assert "seed" not in kwargs


def test_litellm_chat_model_drops_reasoning_effort_for_anthropic_when_temperature_conflicts(
    monkeypatch, caplog
):
    """LiteLLM translates OpenAI-shape `reasoning_effort` into the
    Anthropic-specific `thinking` parameter; with thinking enabled,
    Anthropic requires `temperature == 1`. Callers that set a
    deterministic `temperature` (the agentic loop's default, plus
    most reproducibility-sensitive callers) hit a 400 from the
    provider. The drop only fires when the conflict is real — i.e.
    when temperature is explicitly set to a non-1 value — so callers
    who opt into both keep extended thinking. See the
    `_keeps_reasoning_effort_when_temperature_is_one` counterpart.
    """
    stub = _install_litellm_stub(
        monkeypatch,
        # LiteLLM reports `reasoning_effort` as supported for
        # Anthropic — that's exactly why the generic
        # supported_params filter doesn't catch it.
        supported_params=[
            "temperature",
            "response_format",
            "reasoning_effort",
            "tools",
            "tool_choice",
        ],
    )

    caplog.set_level(
        logging.DEBUG, logger="opik.evaluation.models.litellm.litellm_chat_model"
    )
    model = litellm_chat_model.LiteLLMChatModel(
        model_name="anthropic/claude-haiku-4-5",
        reasoning_effort="low",
        temperature=0.0,
    )

    # Conflict resolution runs on the merged effective kwargs at call
    # time, not at construction. `_completion_kwargs` keeps the raw
    # values so the constructor + per-call merge can be diagnosed as
    # one source of truth — see `_resolve_provider_conflicts`.
    assert model._completion_kwargs.get("reasoning_effort") == "low"
    assert model._completion_kwargs.get("temperature") == 0.0

    model.generate_string("hello")
    _, _, kwargs = stub._calls[-1]
    # The actual call to `litellm.completion` is what matters — the
    # conflict-resolution pass must strip `reasoning_effort` from the
    # outbound request even though it was set at construction time.
    assert "reasoning_effort" not in kwargs


def test_litellm_chat_model_drops_reasoning_effort_for_bare_claude_when_temperature_conflicts(
    monkeypatch,
):
    # Same drop must trigger for the bare `claude-...` form too,
    # matching `models_factory._is_anthropic_model`'s predicate.
    stub = _install_litellm_stub(
        monkeypatch,
        supported_params=["temperature", "response_format", "reasoning_effort"],
    )

    model = litellm_chat_model.LiteLLMChatModel(
        model_name="claude-sonnet-4-6",
        reasoning_effort="low",
        temperature=0.5,
    )

    model.generate_string("hello")
    _, _, kwargs = stub._calls[-1]
    assert "reasoning_effort" not in kwargs


def test_litellm_chat_model_drops_reasoning_effort_when_temperature_is_per_call_only(
    monkeypatch,
):
    """Cross-source conflict — `reasoning_effort` from constructor,
    `temperature=0` from the per-call kwargs (the agentic judge loop's
    pattern). The conflict-resolution pass must run on the merged
    effective dict so it catches conflicts whose two halves come from
    different sources. The per-source `_remove_unnecessary_not_supported_params`
    by itself couldn't see this — at constructor time it never saw the
    per-call temperature, and at call time it never sees the
    constructor-time reasoning_effort.
    """
    stub = _install_litellm_stub(
        monkeypatch,
        supported_params=["temperature", "response_format", "reasoning_effort"],
    )

    model = litellm_chat_model.LiteLLMChatModel(
        model_name="anthropic/claude-haiku-4-5",
        reasoning_effort="low",
    )

    # Per-call temperature=0 (the agentic judge loop's hardcoded pin)
    # is the half of the conflict that lives outside `_completion_kwargs`.
    model.generate_string("hello", temperature=0)
    _, _, kwargs = stub._calls[-1]
    assert "reasoning_effort" not in kwargs


def test_litellm_chat_model_drops_reasoning_effort_when_reasoning_effort_is_per_call_only(
    monkeypatch,
):
    """Mirror of the previous test — the other cross-source ordering:
    `temperature` from the constructor, `reasoning_effort` arriving
    per-call. Without the merged-dict check this would slip past:
    constructor `_remove_unnecessary_not_supported_params` saw only
    `temperature`, per-call `_remove_unnecessary_not_supported_params`
    saw only `reasoning_effort`, neither was the conflict pair.
    """
    stub = _install_litellm_stub(
        monkeypatch,
        supported_params=["temperature", "response_format", "reasoning_effort"],
    )

    model = litellm_chat_model.LiteLLMChatModel(
        model_name="anthropic/claude-haiku-4-5",
        temperature=0,
    )

    model.generate_string("hello", reasoning_effort="low")
    _, _, kwargs = stub._calls[-1]
    assert "reasoning_effort" not in kwargs


def test_litellm_chat_model_keeps_reasoning_effort_for_anthropic_when_temperature_is_one(
    monkeypatch,
):
    """Opt-in path for Anthropic extended thinking: explicit
    `temperature=1` plus `reasoning_effort=...` keeps both. The
    `thinking` mode LiteLLM enables under the hood is compatible with
    `temperature=1`, so the drop must not fire.
    """
    stub = _install_litellm_stub(
        monkeypatch,
        supported_params=["temperature", "reasoning_effort", "response_format"],
    )

    model = litellm_chat_model.LiteLLMChatModel(
        model_name="anthropic/claude-haiku-4-5",
        reasoning_effort="medium",
        temperature=1,
    )
    assert model._completion_kwargs.get("reasoning_effort") == "medium"
    assert model._completion_kwargs.get("temperature") == 1

    model.generate_string("hello")
    _, _, kwargs = stub._calls[-1]
    assert kwargs.get("reasoning_effort") == "medium"


def test_litellm_chat_model_keeps_reasoning_effort_for_anthropic_when_temperature_omitted(
    monkeypatch,
):
    """When `temperature` isn't set explicitly, Anthropic defaults to
    1 server-side, which doesn't conflict with thinking mode. The
    drop must not fire under that signal-absent state.
    """
    stub = _install_litellm_stub(
        monkeypatch,
        supported_params=["temperature", "reasoning_effort", "response_format"],
    )

    model = litellm_chat_model.LiteLLMChatModel(
        model_name="anthropic/claude-haiku-4-5",
        reasoning_effort="low",
    )
    assert model._completion_kwargs.get("reasoning_effort") == "low"
    assert "temperature" not in model._completion_kwargs

    model.generate_string("hello")
    _, _, kwargs = stub._calls[-1]
    assert kwargs.get("reasoning_effort") == "low"


def test_litellm_chat_model_keeps_reasoning_effort_for_openai(monkeypatch):
    """Counterpart to the Anthropic drop tests: for OpenAI (and any
    other provider whose litellm support doesn't translate
    `reasoning_effort` into a conflicting param), the value must
    round-trip so determinism + reasoning callers keep working.
    """
    stub = _install_litellm_stub(
        monkeypatch,
        supported_params=["temperature", "reasoning_effort", "response_format"],
    )

    model = litellm_chat_model.LiteLLMChatModel(
        model_name="gpt-5-mini",
        reasoning_effort="low",
    )
    assert model._completion_kwargs.get("reasoning_effort") == "low"

    model.generate_string("hello")
    _, _, kwargs = stub._calls[-1]
    assert kwargs.get("reasoning_effort") == "low"


def test_litellm_chat_model_keeps_seed_when_provider_supports_it(monkeypatch):
    """Counterpart to the drop test: when `seed` IS in the provider's
    supported set (e.g. OpenAI), it must round-trip through to the
    completion call so determinism still works on providers that
    honor it.
    """
    stub = _install_litellm_stub(
        monkeypatch,
        supported_params=["temperature", "seed", "response_format"],
    )

    model = litellm_chat_model.LiteLLMChatModel(
        model_name="gpt-4o-mini",
        seed=42,
    )
    assert model._completion_kwargs.get("seed") == 42

    model.generate_string("hello")
    _, _, kwargs = stub._calls[-1]
    assert kwargs.get("seed") == 42


def test_litellm_chat_model_drops_top_logprobs_for_dashscope(
    monkeypatch,
):
    stub = _install_litellm_stub(
        monkeypatch,
        supported_params=["logprobs", "top_logprobs", "response_format"],
    )

    model = litellm_chat_model.LiteLLMChatModel(
        model_name="dashscope/qwen-flash",
        logprobs=True,
        top_logprobs=10,
    )

    # top_logprobs should not be kept in static completion kwargs
    assert "top_logprobs" not in model._completion_kwargs

    model.generate_string("hello")

    assert stub._calls, "Expected completion to be invoked"
    _, _, kwargs = stub._calls[-1]

    # top_logprobs should not be forwarded to the provider
    assert "top_logprobs" not in kwargs


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

    monkeypatch.setattr(
        base_model, "aget_provider_response", fake_aget_provider_response
    )

    model = litellm_chat_model.LiteLLMChatModel(model_name="gpt-4o")

    result = await model.agenerate_string(input="hello async")

    assert result == "async-ok"
    assert captured_kwargs["messages"][0]["content"] == "hello async"


def test_models_factory_track_parameter_creates_separate_instances(monkeypatch):
    """Test that track parameter creates separate cached instances."""
    _install_litellm_stub(monkeypatch)

    # Get model with track=True
    model_tracked = models_factory.get("gpt-4o", track=True)
    # Get model with track=False
    model_untracked = models_factory.get("gpt-4o", track=False)
    # Get another model with track=True (should reuse first)
    model_tracked_2 = models_factory.get("gpt-4o", track=True)

    # track=True and track=False should create separate instances
    assert model_tracked is not model_untracked
    # Same track value should reuse cached instance
    assert model_tracked is model_tracked_2


class TestParseAssistantMessage:
    def _wrap(self, message):
        return SimpleNamespace(choices=[{"message": message}])

    def test_returns_content_when_present(self):
        message = response_parser.parse_assistant_message(
            self._wrap({"content": "hello"})
        )
        assert message == {"role": "assistant", "content": "hello"}

    def test_raises_when_no_content_and_no_tool_calls(self):
        from opik import exceptions

        with pytest.raises(exceptions.BaseLLMError):
            response_parser.parse_assistant_message(self._wrap({"content": None}))

    def test_falls_back_to_structured_output_tool_call(self):
        message = response_parser.parse_assistant_message(
            self._wrap(
                {
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "call_synthetic",
                            "function": {
                                "name": "json_tool_call",
                                "arguments": '{"assertion_1": {"score": true, "reason": "ok", "confidence": 0.9}}',
                            },
                        }
                    ],
                }
            )
        )
        assert '"assertion_1"' in message["content"]
        assert "tool_calls" not in message

    def test_falls_back_to_structured_output_tool_call_from_object_message(self):
        message = response_parser.parse_assistant_message(
            self._wrap(
                SimpleNamespace(
                    content=None,
                    tool_calls=[
                        SimpleNamespace(
                            id="call_synthetic",
                            function=SimpleNamespace(
                                name="json_tool_call",
                                arguments='{"score": true}',
                            ),
                        )
                    ],
                )
            )
        )
        assert message["content"] == '{"score": true}'
        assert "tool_calls" not in message

    def test_surfaces_real_tool_calls_alongside_text(self):
        message = response_parser.parse_assistant_message(
            self._wrap(
                {
                    "content": "looking that up",
                    "tool_calls": [
                        {
                            "id": "call_123",
                            "function": {
                                "name": "web_search",
                                "arguments": '{"query": "capital of France"}',
                            },
                        }
                    ],
                }
            )
        )
        assert message["content"] == "looking that up"
        assert message["tool_calls"] == [
            {
                "id": "call_123",
                "type": "function",
                "function": {
                    "name": "web_search",
                    "arguments": '{"query": "capital of France"}',
                },
            }
        ]

    def test_skips_synthetic_tool_call_when_listed_alongside_real_ones(self):
        message = response_parser.parse_assistant_message(
            self._wrap(
                {
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "synth",
                            "function": {
                                "name": "json_tool_call",
                                "arguments": '{"score": 5}',
                            },
                        },
                        {
                            "id": "call_real",
                            "function": {
                                "name": "get_weather",
                                "arguments": '{"city": "Paris"}',
                            },
                        },
                    ],
                }
            )
        )
        assert message["content"] == '{"score": 5}'
        assert message["tool_calls"] == [
            {
                "id": "call_real",
                "type": "function",
                "function": {
                    "name": "get_weather",
                    "arguments": '{"city": "Paris"}',
                },
            }
        ]

    def test_prefers_content_over_synthetic_tool_call_arguments(self):
        message = response_parser.parse_assistant_message(
            self._wrap(
                {
                    "content": "direct content",
                    "tool_calls": [
                        {
                            "id": "synth",
                            "function": {
                                "name": "json_tool_call",
                                "arguments": "should not use this",
                            },
                        }
                    ],
                }
            )
        )
        assert message["content"] == "direct content"
        assert "tool_calls" not in message


@pytest.mark.parametrize(
    "track,expected_calls",
    [
        (False, 0),
        (True, 2),  # Once for completion, once for acompletion
    ],
)
def test_litellm_chat_model_track_parameter_controls_monitoring(
    monkeypatch, track, expected_calls
):
    """Test that track parameter controls LiteLLM monitoring when globally enabled."""
    _install_litellm_stub(monkeypatch)
    monkeypatch.setenv("OPIK_ENABLE_LITELLM_MODELS_MONITORING", "true")

    # Track which decorator was used
    decorator_calls = 0

    def mock_track_completion(project_name=None):
        def decorator(func):
            nonlocal decorator_calls
            decorator_calls += 1
            return func

        return decorator

    # Patch the function on the actual module. Replacing the `sys.modules`
    # entry isn't enough because `import opik.integrations.litellm as X`
    # resolves via the `opik.integrations` package attribute, which still
    # points at the real module once it has been imported earlier in the
    # suite (e.g. by any LLM-judge metric default-model instantiation).
    import opik.integrations.litellm as _real_litellm_integration

    monkeypatch.setattr(
        _real_litellm_integration, "track_completion", mock_track_completion
    )

    # Create model with specified track value
    litellm_chat_model.LiteLLMChatModel(model_name="gpt-4o", track=track)

    # Verify that track_completion decorator was applied the expected number of times
    assert decorator_calls == expected_calls
