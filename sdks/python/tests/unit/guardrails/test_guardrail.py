import httpx
import pytest

import opik
import opik.exceptions as exceptions
from opik.guardrails import Guardrail, schemas
from opik.guardrails.guards import guard as guard_module


class _FailingLocalGuard(guard_module.Guard):
    local = True

    def validate_local(self, text, client):
        raise exceptions.GuardrailValidationError(
            "LLM judge 'policy' could not be evaluated, failing closed: provider down"
        )


class _PassingLocalGuard(guard_module.Guard):
    local = True

    def validate_local(self, text, client):
        return [
            schemas.ValidationResult(
                validation_passed=True,
                type=schemas.ValidationType.LLM_JUDGE,
                validation_config={"name": "policy"},
                validation_details={"name": "policy", "passed": True},
            )
        ]


class _RemoteGuard(guard_module.Guard):
    local = False

    def get_validation_configs(self):
        return [{"type": "PII", "config": {}}]


def _guardrail_span_output(fake_backend):
    output = fake_backend.trace_trees[0].spans[0].output
    if hasattr(output, "model_dump"):
        output = output.model_dump()
    return output


def test_guardrail_validate__local_guard_fails_closed__span_records_output(
    fake_backend,
):
    guardrail = Guardrail(guards=[_FailingLocalGuard()])

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
    guardrail = Guardrail(guards=[_RemoteGuard()])

    def raise_connect_error(*args, **kwargs):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(guardrail._api_client, "validate", raise_connect_error)

    with pytest.raises(exceptions.GuardrailValidationError):
        guardrail.validate("some text")

    opik.flush_tracker()

    output = _guardrail_span_output(fake_backend)
    assert output["guardrail_result"] == "failed"
    assert "failing closed" in output["error"]


def test_guardrail_validate__passing_guard__span_records_passed_output(fake_backend):
    guardrail = Guardrail(guards=[_PassingLocalGuard()])

    result = guardrail.validate("some text")

    assert result.guardrail_result == "passed"
    assert result.error is None

    opik.flush_tracker()

    output = _guardrail_span_output(fake_backend)
    assert output["guardrail_result"] == "passed"
    assert output["error"] is None
