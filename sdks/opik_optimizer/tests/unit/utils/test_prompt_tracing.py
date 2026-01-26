from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.utils import prompt_tracing


class _StubOpikPrompt:
    def __init__(self, name: str, prompt: str) -> None:
        self.name = name
        self.prompt = prompt

    def __internal_api__to_info_dict__(self) -> dict[str, Any]:
        return {"name": self.name, "version": {"template": self.prompt}}


class _StubOpikChatPrompt:
    def __init__(self, name: str, template: list[dict[str, Any]]) -> None:
        self.name = name
        self.template = template

    def __internal_api__to_info_dict__(self) -> dict[str, Any]:
        return {"name": self.name, "version": {"template": self.template}}


class _TraceData:
    def __init__(self, metadata: dict[str, Any] | None = None) -> None:
        self.metadata = metadata


class _SpanData:
    def __init__(self, metadata: dict[str, Any] | None = None) -> None:
        self.metadata = metadata


def _patch_opik_prompt_types(monkeypatch: pytest.MonkeyPatch) -> None:
    import opik

    monkeypatch.setattr(opik, "Prompt", _StubOpikPrompt, raising=False)
    monkeypatch.setattr(opik, "ChatPrompt", _StubOpikChatPrompt, raising=False)
    monkeypatch.setattr(opik, "__version__", "1.9.90", raising=False)


def _patch_prompt_support(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    from opik import opik_context

    calls: dict[str, Any] = {"trace": [], "span": []}

    def update_current_trace(
        *,
        name: str | None = None,
        input: dict[str, Any] | None = None,
        output: dict[str, Any] | None = None,
        metadata: dict[str, Any] | None = None,
        tags: list[str] | None = None,
        _feedback_scores: list[dict[str, Any]] | None = None,
        _thread_id: str | None = None,
        _attachments: list[Any] | None = None,
        prompts: list[Any] | None = None,
    ) -> None:
        calls["trace"].append(
            {
                "metadata": metadata,
                "prompts": prompts,
            }
        )

    def update_current_span(
        *,
        name: str | None = None,
        input: dict[str, Any] | None = None,
        output: dict[str, Any] | None = None,
        metadata: dict[str, Any] | None = None,
        tags: list[str] | None = None,
        usage: dict[str, Any] | None = None,
        _feedback_scores: list[dict[str, Any]] | None = None,
        model: str | None = None,
        _provider: str | None = None,
        total_cost: float | None = None,
        _attachments: list[Any] | None = None,
        _error_info: dict[str, Any] | None = None,
        prompts: list[Any] | None = None,
    ) -> None:
        calls["span"].append(
            {
                "metadata": metadata,
                "prompts": prompts,
            }
        )

    monkeypatch.setattr(opik_context, "update_current_trace", update_current_trace)
    monkeypatch.setattr(opik_context, "update_current_span", update_current_span)
    return calls


def test_normalize_prompt_input__opik_prompt(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_opik_prompt_types(monkeypatch)
    opik_prompt = _StubOpikPrompt(name="text-prompt", prompt="Hello {name}")

    normalized, is_single = prompt_tracing.normalize_prompt_input(opik_prompt)

    assert is_single is True
    prompt = normalized["text-prompt"]
    assert isinstance(prompt, chat_prompt.ChatPrompt)
    assert prompt.user == "Hello {name}"
    assert getattr(prompt, "_opik_prompt_type", None) == "text"
    assert getattr(prompt, "_opik_prompt_source", None) is opik_prompt


def test_normalize_prompt_input__opik_chat_prompt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_opik_prompt_types(monkeypatch)
    messages = [{"role": "user", "content": "Hi {name}"}]
    opik_chat_prompt = _StubOpikChatPrompt(name="chat-prompt", template=messages)

    normalized, is_single = prompt_tracing.normalize_prompt_input(opik_chat_prompt)

    assert is_single is True
    prompt = normalized["chat-prompt"]
    assert isinstance(prompt, chat_prompt.ChatPrompt)
    assert prompt.messages == messages
    assert getattr(prompt, "_opik_prompt_type", None) == "chat"
    assert getattr(prompt, "_opik_prompt_source", None) is opik_chat_prompt


def test_attach_initial_prompts__uses_opik_prompt_api(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_opik_prompt_types(monkeypatch)
    calls = _patch_prompt_support(monkeypatch)

    from opik import opik_context

    monkeypatch.setattr(opik_context, "get_current_trace_data", lambda: _TraceData())

    opik_prompt = _StubOpikPrompt(name="text-prompt", prompt="Hello {name}")
    normalized, _ = prompt_tracing.normalize_prompt_input(opik_prompt)

    prompt_tracing.attach_initial_prompts(normalized)

    assert calls["trace"]
    last_call = calls["trace"][-1]
    assert last_call["prompts"] == [opik_prompt]
    metadata = last_call["metadata"] or {}
    assert "opik_optimizer" in metadata
    assert "initial_prompts" in metadata["opik_optimizer"]


def test_attach_initial_prompts__fallbacks_on_old_sdk(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_opik_prompt_types(monkeypatch)
    import opik

    monkeypatch.setattr(opik, "__version__", "0.0.1", raising=False)
    calls = _patch_prompt_support(monkeypatch)

    from opik import opik_context

    monkeypatch.setattr(opik_context, "get_current_trace_data", lambda: _TraceData())

    opik_prompt = _StubOpikPrompt(name="text-prompt", prompt="Hello {name}")
    normalized, _ = prompt_tracing.normalize_prompt_input(opik_prompt)

    prompt_tracing.attach_initial_prompts(normalized)

    assert calls["trace"]
    last_call = calls["trace"][-1]
    assert last_call["prompts"] is None
    metadata = last_call["metadata"] or {}
    assert "opik_optimizer" in metadata


def test_attach_initial_prompts__optimizer_prompt_uses_metadata_only(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_opik_prompt_types(monkeypatch)
    calls = _patch_prompt_support(monkeypatch)

    from opik import opik_context

    monkeypatch.setattr(opik_context, "get_current_trace_data", lambda: _TraceData())

    prompt = chat_prompt.ChatPrompt(name="p1", user="Hello {name}")

    prompt_tracing.attach_initial_prompts({"p1": prompt})

    assert calls["trace"]
    last_call = calls["trace"][-1]
    assert last_call["prompts"] is None
    metadata = last_call["metadata"] or {}
    assert metadata["opik_optimizer"]["initial_prompts"][0]["name"] == "p1"


def test_attach_span_prompt_payload__adds_rendered_messages(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = _patch_prompt_support(monkeypatch)

    from opik import opik_context

    monkeypatch.setattr(opik_context, "get_current_span_data", lambda: _SpanData())

    prompt = chat_prompt.ChatPrompt(name="p", user="Hello {name}")
    rendered = [{"role": "user", "content": "Hello Ada"}]

    prompt_tracing.attach_span_prompt_payload(prompt, rendered_messages=rendered)

    assert calls["span"]
    metadata = calls["span"][-1]["metadata"] or {}
    payloads = metadata["opik_optimizer"]["prompt_payloads"]
    assert payloads[0]["rendered_messages"] == rendered


def test_record_candidate_prompts__appends_candidates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = _patch_prompt_support(monkeypatch)

    from opik import opik_context

    existing_metadata = {
        "opik_optimizer": {"candidate_prompts": [{"name": "old"}]}
    }
    monkeypatch.setattr(
        opik_context, "get_current_trace_data", lambda: _TraceData(existing_metadata)
    )

    prompts = {
        "p1": chat_prompt.ChatPrompt(name="p1", user="Hello {name}")
    }

    prompt_tracing.record_candidate_prompts(prompts)

    assert calls["trace"]
    metadata = calls["trace"][-1]["metadata"] or {}
    candidate_prompts = metadata["opik_optimizer"]["candidate_prompts"]
    assert len(candidate_prompts) == 2
    assert candidate_prompts[0]["name"] == "old"
