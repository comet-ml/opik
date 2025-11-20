import pytest

import opik
import opik.hooks
from opik import opik_context
from opik.anonymizer import factory, anonymizer

from . import verifiers
from .conftest import OPIK_E2E_TESTS_PROJECT_NAME


# Email pattern
EMAIL_RULE = (
    r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b",
    "[EMAIL_REDACTED]",
)

# Credit card pattern (simplified)
CC_RULE = (r"\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b", "[CARD_REDACTED]")

# SSN pattern
SSN_RULE = {"regex": r"\b\d{3}-\d{2}-\d{4}\b", "replace": "[SSN_REDACTED]"}


@pytest.mark.parametrize(
    "project_name",
    [
        "e2e-tests-anonymization",
        None,
    ],
)
def test_tracked_function__regexp_rules_anonymization__happy_flow(
    opik_client, project_name
):
    """Test that sensitive fields are masked in input, output and metadata."""
    # create and register anonymizer
    opik.hooks.clear_anonymizers()
    rules_anonymizer = factory.create_anonymizer([EMAIL_RULE, CC_RULE, SSN_RULE])
    opik.hooks.add_anonymizer(rules_anonymizer)

    # Setup
    ID_STORAGE = {}

    # Example PII values used in this test:
    # Email: john.doe@example.com
    # Credit Card: 1234 5678 9012 3456
    # SSN: 123-45-6789

    @opik.track(
        tags=["outer-tag1", "outer-tag2"],
        metadata={
            "outer-metadata-key": "outer-metadata-value",
            "card-number": "1234 5678 9012 3456",
        },
        project_name=project_name,
    )
    def f_outer(x: str, email: str):
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span_data().id

        f_inner("inner-input")
        return email

    @opik.track(
        tags=["inner-tag1", "inner-tag2"],
        metadata={"inner-metadata-key": "inner-metadata-value", "ssn": "123-45-6789"},
        project_name=project_name,
    )
    def f_inner(y):
        ID_STORAGE["f_inner-span-id"] = opik_context.get_current_span_data().id
        return "inner-output"

    # Call
    f_outer("outer-input", email="john.doe@example.com")
    opik.flush_tracker()

    anonymized_outer_metadata = {
        "outer-metadata-key": "outer-metadata-value",
        "card-number": "[CARD_REDACTED]",
    }

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input", "email": "[EMAIL_REDACTED]"},
        output={"output": "[EMAIL_REDACTED]"},
        metadata=anonymized_outer_metadata,
        tags=["outer-tag1", "outer-tag2"],
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Verify the top level span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_outer-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input", "email": "[EMAIL_REDACTED]"},
        output={"output": "[EMAIL_REDACTED]"},
        metadata=anonymized_outer_metadata,
        tags=["outer-tag1", "outer-tag2"],
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )

    anonymized_inner_metadata = {
        "inner-metadata-key": "inner-metadata-value",
        "ssn": "[SSN_REDACTED]",
    }

    # Verify nested span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_inner-span-id"],
        parent_span_id=ID_STORAGE["f_outer-span-id"],
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_inner",
        input={"y": "inner-input"},
        output={"output": "inner-output"},
        metadata=anonymized_inner_metadata,
        tags=["inner-tag1", "inner-tag2"],
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )


@pytest.mark.parametrize(
    "project_name",
    [
        "e2e-tests-anonymization",
        None,
    ],
)
def test_tracked_function__rules_anonymization_remove_sensitive_key__happy_flow(
    opik_client, project_name
):
    """Test that sensitive keys are removed from metadata and other sensitive fields are masked in input and output."""

    class ApiKeyAnonymizer(anonymizer.Anonymizer):
        def anonymize(self, data, **kwargs):
            field_name = kwargs.get("field_name")
            object_type = kwargs.get("object_type")
            if (
                field_name == "metadata"
                and object_type in ["span", "trace"]
                and "api_key" in data
            ):
                del data["api_key"]
            return data

    # create and register anonymizer
    opik.hooks.clear_anonymizers()
    api_key_anonymizer = ApiKeyAnonymizer()
    opik.hooks.add_anonymizer(api_key_anonymizer)
    rules_anonymizer = factory.create_anonymizer([EMAIL_RULE, CC_RULE, SSN_RULE])
    opik.hooks.add_anonymizer(rules_anonymizer)

    # Setup
    ID_STORAGE = {}

    @opik.track(
        tags=["outer-tag1", "outer-tag2"],
        metadata={
            "outer-metadata-key": "outer-metadata-value",
            "card-number": "1234 5678 9012 3456",
            "api_key": "secret-api-key",
        },
        project_name=project_name,
    )
    def f_outer(x: str, email: str):
        ID_STORAGE["f_outer-trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["f_outer-span-id"] = opik_context.get_current_span_data().id

        return email

    # Call
    f_outer("outer-input", email="john.doe@example.com")
    opik.flush_tracker()

    anonymized_metadata = {
        "outer-metadata-key": "outer-metadata-value",
        "card-number": "[CARD_REDACTED]",
    }

    # Verify trace
    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input", "email": "[EMAIL_REDACTED]"},
        output={"output": "[EMAIL_REDACTED]"},
        metadata=anonymized_metadata,
        tags=["outer-tag1", "outer-tag2"],
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )

    # Verify the top level span
    verifiers.verify_span(
        opik_client=opik_client,
        span_id=ID_STORAGE["f_outer-span-id"],
        parent_span_id=None,
        trace_id=ID_STORAGE["f_outer-trace-id"],
        name="f_outer",
        input={"x": "outer-input", "email": "[EMAIL_REDACTED]"},
        output={"output": "[EMAIL_REDACTED]"},
        metadata=anonymized_metadata,
        tags=["outer-tag1", "outer-tag2"],
        project_name=project_name or OPIK_E2E_TESTS_PROJECT_NAME,
    )
