import types

import pytest

import opik.exceptions as exceptions
import opik.guardrails.guards.llm_judge as llm_judge
import opik.guardrails.schemas as schemas


def _make_client(
    content=None,
    raise_exc=None,
    provider="openai",
    actual_model="gpt-4o-mini-2024-07-18",
):
    def create_chat_completions(model, temperature, messages):
        if raise_exc is not None:
            raise raise_exc
        message = types.SimpleNamespace(content=content)
        data = types.SimpleNamespace(
            choices=[types.SimpleNamespace(message=message)],
            model=None,
            usage=None,
        )
        headers = {}
        if provider is not None:
            headers["x-opik-provider"] = provider
        if actual_model is not None:
            headers["x-opik-actual-model"] = actual_model
        return types.SimpleNamespace(data=data, headers=headers)

    raw = types.SimpleNamespace(create_chat_completions=create_chat_completions)
    chat_completions = types.SimpleNamespace(with_raw_response=raw)
    return types.SimpleNamespace(
        rest_client=types.SimpleNamespace(chat_completions=chat_completions)
    )


def test_llm_judge__runs_locally_without_backend_config():
    guard = llm_judge.LLMJudge(
        name="no_medical_advice", instructions="No medical advice.", model="gpt-4o-mini"
    )

    assert guard.local is True
    assert guard.get_validation_configs() == []


def test_llm_judge__failed_decision():
    guard = llm_judge.LLMJudge(
        name="no_medical_advice", instructions="No medical advice.", model="gpt-4o-mini"
    )

    client = _make_client('{"passed": false, "reason": "gives dosage advice"}')
    results = guard.validate_local("Take 400mg ibuprofen.", client)

    assert len(results) == 1
    result = results[0]
    assert result.type == schemas.ValidationType.LLM_JUDGE
    assert result.validation_passed is False
    assert result.validation_details == {
        "name": "no_medical_advice",
        "passed": False,
        "reason": "gives dosage advice",
    }


def test_llm_judge__parses_json_embedded_in_prose():
    guard = llm_judge.LLMJudge(
        name="policy", instructions="Some policy.", model="gpt-4o-mini"
    )

    client = _make_client('Sure. {"passed": true, "reason": "fine"} done.')
    results = guard.validate_local("Hello", client)

    assert results[0].validation_passed is True


def test_llm_judge__fails_closed_on_unparseable_output():
    guard = llm_judge.LLMJudge(
        name="policy", instructions="Some policy.", model="gpt-4o-mini"
    )

    client = _make_client("I cannot comply with that.")

    with pytest.raises(exceptions.GuardrailValidationError):
        guard.validate_local("Hello", client)


def test_llm_judge__fails_closed_on_provider_error():
    guard = llm_judge.LLMJudge(
        name="policy", instructions="Some policy.", model="gpt-4o-mini"
    )

    client = _make_client(raise_exc=RuntimeError("provider unavailable"))

    with pytest.raises(exceptions.GuardrailValidationError):
        guard.validate_local("Hello", client)


def test_llm_judge__records_nested_llm_span(monkeypatch):
    guard = llm_judge.LLMJudge(
        name="policy", instructions="Some policy.", model="gpt-4o-mini"
    )

    current_span = types.SimpleNamespace(id="span-1", trace_id="trace-1")
    monkeypatch.setattr(
        llm_judge.opik_context, "get_current_span_data", lambda: current_span
    )

    recorded = {}

    def span(**kwargs):
        recorded["span"] = kwargs

    client = _make_client(
        '{"passed": true, "reason": "ok"}',
        provider="openai",
        actual_model="gpt-4o-mini-2024-07-18",
    )
    client.span = span

    results = guard.validate_local("Hello", client)

    assert results[0].validation_passed is True
    assert recorded["span"]["type"] == "llm"
    assert recorded["span"]["name"] == "llm_judge"
    assert recorded["span"]["parent_span_id"] == "span-1"
    assert recorded["span"]["trace_id"] == "trace-1"
    assert recorded["span"]["model"] == "gpt-4o-mini-2024-07-18"
    assert recorded["span"]["provider"] == "openai"
    assert recorded["span"]["output"] == {"content": '{"passed": true, "reason": "ok"}'}
    assert recorded["span"]["start_time"] is not None
    assert recorded["span"]["end_time"] is not None


def test_llm_judge__span_recording_failure_is_not_fatal(monkeypatch):
    guard = llm_judge.LLMJudge(
        name="policy", instructions="Some policy.", model="gpt-4o-mini"
    )

    current_span = types.SimpleNamespace(id="span-1", trace_id="trace-1")
    monkeypatch.setattr(
        llm_judge.opik_context, "get_current_span_data", lambda: current_span
    )

    client = _make_client('{"passed": true, "reason": "ok"}')
    client.span = lambda **kwargs: (_ for _ in ()).throw(RuntimeError("span boom"))

    results = guard.validate_local("Hello", client)

    assert results[0].validation_passed is True
