import opik
from opik import opik_context
from . import verifiers
from opik.guardrails import Guardrail, PII, Topic
from opik import exceptions
from unittest import mock
import pytest

from .conftest import OPIK_E2E_TESTS_PROJECT_NAME


def find_guardrail_span(opik_client, trace_id, project_name):
    # We cannot easily access the created span id from the test so we need to query it dynamically
    spans = opik_client.search_spans(
        trace_id=trace_id,
        project_name=project_name,
        filter_string='name = "Guardrail"',
    )

    assert len(spans) == 1, "Expected to find 1 span"
    span = spans[0]

    return span.id


@pytest.mark.parametrize(
    "project_name",
    [
        "e2e-tests-manual-project-name",
        None,
    ],
)
def test_passing_guardrails__happyflow(opik_client, project_name):
    # Setup
    ID_STORAGE = {}

    guard = Guardrail(
        guards=[
            Topic(restricted_topics=["finance"], threshold=0.8),
            PII(blocked_entities=["CREDIT_CARD", "PERSON"], threshold=0.4),
        ]
    )

    generation = "Hi how are you doing?"

    @opik.track(project_name=project_name)
    def test_function():
        ID_STORAGE["trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["parent-span-id"] = opik_context.get_current_span_data().id

        result = guard.validate(generation)

        return result

    _ = test_function()

    opik_client.flush()

    # Check trace guardrails validations
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
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )

    span_id = find_guardrail_span(opik_client, ID_STORAGE["trace-id"], project_name)

    EXPECTED_OUTPUT = {
        "guardrail_result": "passed",
        "validations": [mock.ANY, mock.ANY],
        "validation_passed": mock.ANY,
    }

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span_id,
        parent_span_id=ID_STORAGE["parent-span-id"],
        trace_id=ID_STORAGE["trace-id"],
        name="Guardrail",
        input={"generation": generation},
        output=EXPECTED_OUTPUT,
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
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

    generation = "First name: Samantha Last name: Martinez"

    @opik.track
    def test_function():
        ID_STORAGE["trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["parent-span-id"] = opik_context.get_current_span_data().id

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

    span_id = find_guardrail_span(
        opik_client, ID_STORAGE["trace-id"], OPIK_E2E_TESTS_PROJECT_NAME
    )

    EXPECTED_OUTPUT = {
        "guardrail_result": "failed",
        "validations": [mock.ANY, mock.ANY],
        "validation_passed": mock.ANY,
    }

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span_id,
        parent_span_id=ID_STORAGE["parent-span-id"],
        trace_id=ID_STORAGE["trace-id"],
        name="Guardrail",
        input={"generation": generation},
        output=EXPECTED_OUTPUT,
    )
