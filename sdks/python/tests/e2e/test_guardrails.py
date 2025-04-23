import opik
from opik import opik_context
from . import verifiers
from opik.guardrails import Guardrail, PII, Topic
from opik import exceptions
import mock
import pytest


def test_passing_guardrails__happyflow(opik_client: opik.Opik):
    # Setup
    ID_STORAGE = {}

    guard = Guardrail(
        guards=[
            Topic(restricted_topics=["finance"], threshold=0.8),
            PII(blocked_entities=["CREDIT_CARD", "PERSON"], threshold=0.4),
        ]
    )

    @opik.track
    def test_function():
        ID_STORAGE["trace-id"] = opik_context.get_current_trace_data().id

        generation = "Hi how are you doing?"
        result = guard.validate(generation)

        return result

    _ = test_function()

    opik_client.flush()

    EXPECTED_TRACE_GUARDRAILS_VALIDATIONS = [
        {
            "span_id": mock.ANY,
            "checks": [
                {"name": "PII", "result": "passed"},
                {"name": "TOPIC", "result": "passed"},
            ],
        },
    ]

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["trace-id"],
        guardrails_validations=EXPECTED_TRACE_GUARDRAILS_VALIDATIONS,
    )


def test_failing_guardrails__happyflow(opik_client: opik.Opik):
    # Setup
    ID_STORAGE = {}

    guard = Guardrail(
        guards=[
            Topic(restricted_topics=["finance"], threshold=0.8),
            PII(blocked_entities=["CREDIT_CARD", "PERSON"], threshold=0.4),
        ]
    )

    @opik.track
    def test_function():
        ID_STORAGE["trace-id"] = opik_context.get_current_trace_data().id

        generation = "First name: Samantha Last name: Martinez"

        with pytest.raises(exceptions.GuardrailValidationFailed):
            guard.validate(generation)

    test_function()

    opik_client.flush()

    EXPECTED_TRACE_GUARDRAILS_VALIDATIONS = [
        {
            "span_id": mock.ANY,
            "checks": [
                {"name": "PII", "result": "failed"},
                {"name": "TOPIC", "result": "passed"},
            ],
        },
    ]

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["trace-id"],
        guardrails_validations=EXPECTED_TRACE_GUARDRAILS_VALIDATIONS,
    )
