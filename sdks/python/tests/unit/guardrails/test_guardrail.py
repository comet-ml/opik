import httpx
import pytest

import opik
import opik.exceptions as exceptions
from opik.guardrails import Guardrail, PII, Topic, rest_api_client, schemas
from opik.guardrails.rules import rule as rule_module
from opik.message_processing.messages import GuardrailBatchMessage
from opik.rest_api import core as rest_api_core


class _FailingLocalRule(rule_module.Rule):
    location = rule_module.RuleLocation.LOCAL

    def validate_local(self, text, client):
        raise exceptions.GuardrailValidationError(
            "LLM judge 'policy' could not be evaluated, failing closed: provider down"
        )


class _PassingLocalRule(rule_module.Rule):
    location = rule_module.RuleLocation.LOCAL

    def validate_local(self, text, client):
        return [
            schemas.ValidationResult(
                validation_passed=True,
                type=schemas.ValidationType.LLM_JUDGE,
                validation_config={"name": "policy"},
                validation_details={"name": "policy", "passed": True},
            )
        ]


def _guardrail_span_output(fake_backend):
    output = fake_backend.trace_trees[0].spans[0].output
    if hasattr(output, "model_dump"):
        output = output.model_dump()
    return output


def _record_opik_calls(monkeypatch, response):
    calls = []

    def validate_on_opik(client, text, validations, timeout_seconds):
        calls.append({"text": text, "validations": validations})
        if isinstance(response, Exception):
            raise response
        return response

    monkeypatch.setattr(rest_api_client, "validate_on_opik", validate_on_opik)

    return calls


def _pii_result(passed):
    return schemas.ValidationResponse(
        validation_passed=passed,
        validations=[
            schemas.ValidationResult(
                validation_passed=passed,
                type=schemas.ValidationType.PII,
                validation_config={"entities": ["EMAIL_ADDRESS"]},
                validation_details={},
            )
        ],
    )


def test_guardrail_validate__local_rule_fails_closed__span_records_output(
    fake_backend,
):
    guardrail = Guardrail(rules=[_FailingLocalRule()])

    with pytest.raises(exceptions.GuardrailValidationError):
        guardrail.validate("some text")

    opik.flush_tracker()

    output = _guardrail_span_output(fake_backend)
    assert output["guardrail_result"] == "failed"
    assert output["validation_passed"] is False
    assert "failing closed" in output["error"]


def test_guardrail_validate__backend_unreachable__span_records_output(
    fake_backend, monkeypatch
):
    _record_opik_calls(monkeypatch, httpx.ConnectError("connection refused"))
    guardrail = Guardrail(rules=[PII(entities=["EMAIL_ADDRESS"])])

    with pytest.raises(exceptions.GuardrailValidationError):
        guardrail.validate("some text")

    opik.flush_tracker()

    output = _guardrail_span_output(fake_backend)
    assert output["guardrail_result"] == "failed"
    assert "failing closed" in output["error"]


def test_guardrail_validate__guardrails_not_available__backend_message_surfaced(
    fake_backend, monkeypatch
):
    _record_opik_calls(
        monkeypatch,
        rest_api_core.ApiError(
            status_code=403,
            body={"message": "Guardrails is available on the Enterprise plan"},
        ),
    )
    guardrail = Guardrail(rules=[PII(entities=["EMAIL_ADDRESS"])])

    with pytest.raises(
        exceptions.GuardrailValidationError,
        match="Guardrails is available on the Enterprise plan",
    ):
        guardrail.validate("some text")


def test_guardrail_validate__opik_rules__sent_to_opik_in_one_call(
    fake_backend, monkeypatch
):
    calls = _record_opik_calls(monkeypatch, _pii_result(passed=True))
    guardrail = Guardrail(
        rules=[PII(entities=["EMAIL_ADDRESS"]), Topic(restricted_topics=["finance"])]
    )

    result = guardrail.validate("some text")

    assert result.validation_passed is True
    assert calls == [
        {
            "text": "some text",
            "validations": [
                {
                    "type": schemas.ValidationType.PII,
                    "config": {"entities": ["EMAIL_ADDRESS"]},
                },
                {
                    "type": schemas.ValidationType.TOPIC,
                    "config": {"restricted_topics": ["finance"]},
                },
            ],
        }
    ]


def test_guardrail_validate__opik_check_fails__raises_validation_failed(
    fake_backend, monkeypatch
):
    _record_opik_calls(monkeypatch, _pii_result(passed=False))
    guardrail = Guardrail(rules=[PII(entities=["EMAIL_ADDRESS"])])

    with pytest.raises(exceptions.GuardrailValidationFailed) as error:
        guardrail.validate("mail me at someone@example.com")

    assert [validation.type for validation in error.value.failed_validations] == [
        schemas.ValidationType.PII
    ]


def test_guardrail_validate__local_rules_only__opik_not_called(
    fake_backend, monkeypatch
):
    calls = _record_opik_calls(monkeypatch, _pii_result(passed=True))
    guardrail = Guardrail(rules=[_PassingLocalRule()])

    guardrail.validate("some text")

    assert calls == []


def test_guardrail_validate__passing_rule__span_records_passed_output(fake_backend):
    guardrail = Guardrail(rules=[_PassingLocalRule()])

    result = guardrail.validate("some text")

    assert result.guardrail_result == "passed"
    assert result.error is None

    opik.flush_tracker()

    output = _guardrail_span_output(fake_backend)
    assert output["guardrail_result"] == "passed"
    assert output["error"] is None


def _recorded_guardrail_batches(guardrail, monkeypatch):
    """Collect the guardrail batches this guardrail hands to the streamer."""
    batches = []
    original_put = guardrail._client._streamer.put

    def put(message):
        if isinstance(message, GuardrailBatchMessage):
            batches.append(message)
        return original_put(message)

    monkeypatch.setattr(guardrail._client._streamer, "put", put)

    return batches


def test_guardrail_validate__no_rules__no_guardrail_batch_sent(
    fake_backend, monkeypatch
):
    # The backend rejects an empty guardrail batch, so sending one would report data loss
    # for a guardrail that simply had nothing to check.
    guardrail = Guardrail(rules=[])
    batches = _recorded_guardrail_batches(guardrail, monkeypatch)

    result = guardrail.validate("some text")

    assert result.validation_passed is True
    assert result.validations == []
    assert batches == []


def test_guardrail_validate__rule_produced_results__guardrail_batch_sent(
    fake_backend, monkeypatch
):
    guardrail = Guardrail(rules=[_PassingLocalRule()])
    batches = _recorded_guardrail_batches(guardrail, monkeypatch)

    guardrail.validate("some text")

    assert len(batches) == 1
    assert [item.name for item in batches[0].batch] == [
        schemas.ValidationType.LLM_JUDGE
    ]
