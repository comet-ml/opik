"""E2E tests for guardrails checked by the Opik backend.

The Opik backend runs these checks only when guardrails are enabled for it and for the
workspace, with its inference service configured. That is not a given, so the module skips
itself when the backend does not run guardrails instead of failing.

To run them by hand, start a backend with guardrails enabled, then point the SDK at it:

    TOGGLE_GUARDRAILS_VALIDATIONS_ENABLED=true \\
    AI_LABELS_INFERENCE_URL=<inference service url> \\
    AI_LABELS_INFERENCE_CREDENTIAL=<inference service credential> \\
        <start the backend>

    OPIK_URL_OVERRIDE=http://localhost:8080/ OPIK_WORKSPACE=default OPIK_API_KEY= \\
        pytest tests/e2e/test_guardrails.py tests/e2e/test_guardrails_stored_policies.py -v
"""

import opik
from opik import opik_context
from . import guardrails_support, verifiers
from opik.guardrails import Guardrail, PII, Topic
from opik import exceptions
from unittest import mock
import pytest

from ..testlib import generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)


@pytest.fixture(autouse=True)
def disable_tests_if_guardrails_not_supported(opik_client: opik.Opik):
    if not guardrails_support.guardrails_available(opik_client):
        pytest.skip("Backend does not run guardrails - skipping E2E tests")


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


@pytest.mark.parametrize("override_project_name", [True, False])
def test_passing_guardrails__happyflow(opik_client, override_project_name):
    project_name = (
        generate_project_name("e2e", "guardrails", "override")
        if override_project_name
        else None
    )

    # Setup
    ID_STORAGE = {}

    guardrail = Guardrail(
        rules=[
            Topic(restricted_topics=["finance"]),
            PII(entities=["CREDIT_CARD", "PERSON"]),
        ]
    )

    generation = "Hi how are you doing?"

    @opik.track(project_name=project_name)
    def test_function():
        ID_STORAGE["trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["parent-span-id"] = opik_context.get_current_span_data().id

        result = guardrail.validate(generation)

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
        project_name=project_name or PROJECT_NAME,
    )

    span_id = find_guardrail_span(opik_client, ID_STORAGE["trace-id"], project_name)

    EXPECTED_OUTPUT = {
        "guardrail_result": "passed",
        "validations": [mock.ANY, mock.ANY],
        "validation_passed": mock.ANY,
        "error": None,
    }

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=span_id,
        parent_span_id=ID_STORAGE["parent-span-id"],
        trace_id=ID_STORAGE["trace-id"],
        name="Guardrail",
        input={"generation": generation},
        output=EXPECTED_OUTPUT,
        project_name=project_name or PROJECT_NAME,
    )


def test_failing_guardrails__happyflow(opik_client: opik.Opik):
    # Setup
    ID_STORAGE = {}

    guardrail = Guardrail(
        rules=[
            Topic(restricted_topics=["finance"]),
            PII(entities=["CREDIT_CARD", "PERSON"]),
        ]
    )

    generation = "First name: Samantha Last name: Martinez"

    @opik.track
    def test_function():
        ID_STORAGE["trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["parent-span-id"] = opik_context.get_current_span_data().id

        with pytest.raises(exceptions.GuardrailValidationFailed):
            guardrail.validate(generation)

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

    span_id = find_guardrail_span(opik_client, ID_STORAGE["trace-id"], PROJECT_NAME)

    EXPECTED_OUTPUT = {
        "guardrail_result": "failed",
        "validations": [mock.ANY, mock.ANY],
        "validation_passed": mock.ANY,
        "error": None,
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
