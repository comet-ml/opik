import pytest

import opik.exceptions as exceptions
import opik.guardrails.guards.llm_judge as llm_judge
import opik.guardrails.schemas as schemas
from opik.evaluation.models import base_model


class _FakeModel(base_model.OpikBaseModel):
    """Stands in for a client-side model, recording what the judge asked it."""

    def __init__(self, content=None, raise_exc=None, model_name="fake-model"):
        super().__init__(model_name=model_name)
        self._content = content
        self._raise_exc = raise_exc
        self.calls = []

    def generate_chat_completion(self, messages, response_format=None, **kwargs):
        self.calls.append({"messages": messages, "response_format": response_format})
        if self._raise_exc is not None:
            raise self._raise_exc
        return {"role": "assistant", "content": self._content}

    def generate_string(self, input, **kwargs):
        raise NotImplementedError

    def generate_provider_response(self, **kwargs):
        raise NotImplementedError

    async def agenerate_string(self, input, **kwargs):
        raise NotImplementedError

    async def agenerate_provider_response(self, **kwargs):
        raise NotImplementedError


def test_llm_judge__runs_locally_without_backend_config():
    guard = llm_judge.LLMJudge(
        name="no_medical_advice", instructions="No medical advice.", model="gpt-4o-mini"
    )

    assert guard.local is True
    assert guard.get_validation_configs() == []


def test_llm_judge__model_name__resolved_client_side(monkeypatch):
    requested = {}

    def get(model_name, track, **model_kwargs):
        requested.update({"model_name": model_name, "track": track, **model_kwargs})
        return _FakeModel(model_name=model_name or "default")

    monkeypatch.setattr(llm_judge.models_factory, "get", get)

    llm_judge.LLMJudge(
        name="policy", instructions="Some policy.", model="anthropic/claude-haiku-4-5"
    )

    assert requested == {
        "model_name": "anthropic/claude-haiku-4-5",
        "track": True,
        "temperature": 0.0,
    }


def test_llm_judge__model_instance__used_as_is():
    model = _FakeModel('{"passed": true, "reason": "fine"}')

    guard = llm_judge.LLMJudge(name="policy", instructions="Some policy.", model=model)
    results = guard.validate_local("Hello", client=None)

    assert results[0].validation_passed is True
    assert results[0].validation_config["model"] == "fake-model"
    assert [message["role"] for message in model.calls[0]["messages"]] == [
        "system",
        "user",
    ]
    assert model.calls[0]["messages"][1]["content"] == "Hello"


def test_llm_judge__failed_decision():
    guard = llm_judge.LLMJudge(
        name="no_medical_advice",
        instructions="No medical advice.",
        model=_FakeModel('{"passed": false, "reason": "gives dosage advice"}'),
    )

    results = guard.validate_local("Take 400mg ibuprofen.", client=None)

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
        name="policy",
        instructions="Some policy.",
        model=_FakeModel('Sure. {"passed": true, "reason": "fine"} done.'),
    )

    results = guard.validate_local("Hello", client=None)

    assert results[0].validation_passed is True


def test_llm_judge__fails_closed_on_unparseable_output():
    guard = llm_judge.LLMJudge(
        name="policy",
        instructions="Some policy.",
        model=_FakeModel("I cannot comply with that."),
    )

    with pytest.raises(exceptions.GuardrailValidationError):
        guard.validate_local("Hello", client=None)


def test_llm_judge__fails_closed_on_provider_error():
    guard = llm_judge.LLMJudge(
        name="policy",
        instructions="Some policy.",
        model=_FakeModel(raise_exc=RuntimeError("provider unavailable")),
    )

    with pytest.raises(exceptions.GuardrailValidationError):
        guard.validate_local("Hello", client=None)
