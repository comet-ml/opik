"""E2E tests for guardrails built from stored guardrail policies.

Two things have to be in place for these to mean anything: a backend that serves the
guardrail policies API, and a reachable guardrails backend to run the guards. Neither is
a given — the policies API ships with the guardrail policy registry, and the guardrails
backend is an opt-in service (``opik.sh --guardrails-cpu``) — so the module skips itself
when either is missing instead of failing. That way the suite can be run against a
backend that has the API while staying green everywhere else.

Since CI has no backend with the policies API yet, these only ever run by hand. Start a
backend that serves it (the guardrail policy registry fork) with a guardrails backend
alongside it, then point the SDK at both:

    OPIK_URL_OVERRIDE=http://localhost:8080/ \\
    OPIK_WORKSPACE=default \\
    OPIK_API_KEY= \\
    OPIK_GUARDRAILS_URL_OVERRIDE=http://localhost:5000 \\
        pytest tests/e2e/test_guardrails_stored_policies.py -v

Substitute the ports the stack actually published — a worktree with a port offset (see
``scripts/dev-runner.sh``) shifts both. Skips instead of failures mean one of the two URLs
is wrong or the service behind it is not up; ``-rs`` prints which.
"""

import uuid
from typing import Any, Dict, List, Tuple

import httpx
import pytest

import opik
from opik import exceptions, opik_context, synchronization
from opik.guardrails import Guardrail, stored_policies
from opik.rest_api import core as rest_api_core

from . import verifiers
from ..testlib import generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)

POLICIES_PATH = "v1/private/guardrails/policies"

PASSING_TEXT = "How can I start with evaluation in Opik platform?"
CONTACT_INFORMATION_TEXT = "First name: Samantha Last name: Martinez"
FINANCIAL_ADVICE_TEXT = "Where should I invest my money?"


def _policies_api_available(opik_client: opik.Opik) -> bool:
    try:
        response = opik_client.rest_client._client_wrapper.httpx_client.request(
            stored_policies.RETRIEVE_POLICIES_PATH, method="POST", json={"names": []}
        )
    except httpx.HTTPError:
        return False

    return response.status_code == 200


def _guardrails_backend_available(opik_client: opik.Opik) -> bool:
    healthcheck_url = (
        opik_client.config.guardrails_backend_host.rstrip("/") + "/healthcheck"
    )
    try:
        return httpx.get(healthcheck_url, timeout=5).status_code == 200
    except httpx.HTTPError:
        return False


@pytest.fixture(autouse=True)
def disable_tests_if_stored_policies_not_supported(opik_client: opik.Opik):
    """Disable tests unless both the policies API and the guardrails backend are up."""
    if not _policies_api_available(opik_client):
        pytest.skip(
            "Backend does not serve the guardrail policies API - skipping E2E tests"
        )

    if not _guardrails_backend_available(opik_client):
        pytest.skip("Guardrails backend is not reachable - skipping E2E tests")


def _create_policy(opik_client: opik.Opik, payload: Dict[str, Any]) -> str:
    response = opik_client.rest_client._client_wrapper.httpx_client.request(
        POLICIES_PATH, method="POST", json=payload
    )
    assert response.status_code == 201, (response.status_code, response.text)

    return response.headers["Location"].rsplit("/", 1)[-1]


def _delete_policies(opik_client: opik.Opik, policy_ids: List[str]) -> None:
    response = opik_client.rest_client._client_wrapper.httpx_client.request(
        f"{POLICIES_PATH}/delete", method="POST", json={"ids": policy_ids}
    )
    assert response.status_code == 204, (response.status_code, response.text)


@pytest.fixture
def stored_policies_(opik_client: opik.Opik) -> Tuple[str, str]:
    """One policy that applies only when named, and one the workspace always applies.

    Names carry a random suffix because they are unique per workspace, and the workspace
    is shared with every other run of this suite.
    """
    suffix = uuid.uuid4().hex[:8]
    on_request_policy = f"e2e_no_contact_information_{suffix}"
    always_policy = f"e2e_no_financial_advice_{suffix}"

    policy_ids = [
        _create_policy(
            opik_client,
            {
                "name": on_request_policy,
                "execution_mode": "ON_REQUEST",
                "guards": [
                    {
                        "type": "PII",
                        "config": {
                            "blocked_entities": ["CREDIT_CARD", "PERSON"],
                            "threshold": 0.4,
                        },
                    }
                ],
            },
        ),
        _create_policy(
            opik_client,
            {
                "name": always_policy,
                "execution_mode": "ALWAYS",
                "guards": [
                    {
                        "type": "TOPIC",
                        "config": {"restricted_topics": ["finance"], "threshold": 0.8},
                    }
                ],
            },
        ),
    ]

    yield on_request_policy, always_policy

    _delete_policies(opik_client, policy_ids)


def _guardrail_check_results(opik_client: opik.Opik, trace_id: str) -> set:
    trace = opik_client.get_trace_content(id=trace_id)

    return {
        (check["name"], check["result"])
        for validation in (trace.guardrails_validations or [])
        for check in validation.model_dump()["checks"]
    }


def _find_guardrail_span_id(opik_client: opik.Opik, trace_id: str) -> str:
    # The span id is not available to the caller of validate(), so it is looked up.
    spans = opik_client.search_spans(
        trace_id=trace_id,
        project_name=PROJECT_NAME,
        filter_string='name = "Guardrail"',
    )

    assert len(spans) == 1, "Expected to find 1 guardrail span"

    return spans[0].id


def test_guardrail_from_stored_policies__named_and_always_policies__guards_built(
    opik_client: opik.Opik, stored_policies_
):
    on_request_policy, _ = stored_policies_

    guardrail = Guardrail.from_stored_policies(names=[on_request_policy])

    validation_configs = [
        config
        for guard in guardrail.guards
        for config in guard.get_validation_configs()
    ]

    assert {
        "type": "PII",
        "config": {
            "entities": ["CREDIT_CARD", "PERSON"],
            "language": "en",
            "threshold": 0.4,
        },
    } in validation_configs

    # From the ALWAYS policy, which was never named.
    assert {
        "type": "TOPIC",
        "config": {"topics": ["finance"], "threshold": 0.8, "mode": "restrict"},
    } in validation_configs


def test_guardrail_from_stored_policies__passing_text__validations_logged(
    opik_client: opik.Opik, stored_policies_
):
    on_request_policy, _ = stored_policies_
    guardrail = Guardrail.from_stored_policies(names=[on_request_policy])
    ID_STORAGE = {}

    @opik.track
    def test_function():
        ID_STORAGE["trace-id"] = opik_context.get_current_trace_data().id
        ID_STORAGE["parent-span-id"] = opik_context.get_current_span_data().id

        return guardrail.validate(PASSING_TEXT)

    result = test_function()

    assert result.validation_passed is True
    assert result.guardrail_result == "passed"

    opik_client.flush()

    verifiers.verify_trace(
        opik_client=opik_client,
        trace_id=ID_STORAGE["trace-id"],
        project_name=PROJECT_NAME,
    )

    assert synchronization.until(
        lambda: {("PII", "passed"), ("TOPIC", "passed")}
        <= _guardrail_check_results(opik_client, ID_STORAGE["trace-id"])
    ), "The guardrail results of both policies did not reach the trace"

    verifiers.verify_span(
        opik_client=opik_client,
        span_id=_find_guardrail_span_id(opik_client, ID_STORAGE["trace-id"]),
        parent_span_id=ID_STORAGE["parent-span-id"],
        trace_id=ID_STORAGE["trace-id"],
        name="Guardrail",
        input={"generation": PASSING_TEXT},
        project_name=PROJECT_NAME,
    )


def test_guardrail_from_stored_policies__text_violating_named_policy__validation_failed(
    opik_client: opik.Opik, stored_policies_
):
    on_request_policy, _ = stored_policies_
    guardrail = Guardrail.from_stored_policies(names=[on_request_policy])

    with pytest.raises(exceptions.GuardrailValidationFailed) as exception_info:
        guardrail.validate(CONTACT_INFORMATION_TEXT)

    assert "PII" in [
        validation.type for validation in exception_info.value.failed_validations
    ]


def test_guardrail_from_stored_policies__text_violating_always_policy__validation_failed(
    opik_client: opik.Opik, stored_policies_
):
    on_request_policy, _ = stored_policies_
    guardrail = Guardrail.from_stored_policies(names=[on_request_policy])

    with pytest.raises(exceptions.GuardrailValidationFailed) as exception_info:
        guardrail.validate(FINANCIAL_ADVICE_TEXT)

    # Blocked by a policy this application never asked for.
    assert "TOPIC" in [
        validation.type for validation in exception_info.value.failed_validations
    ]


def test_guardrail_from_stored_policies__unknown_policy_name__error_raised(
    opik_client: opik.Opik,
):
    with pytest.raises(rest_api_core.ApiError) as exception_info:
        Guardrail.from_stored_policies(names=[f"e2e_missing_{uuid.uuid4().hex[:8]}"])

    assert exception_info.value.status_code == 404
