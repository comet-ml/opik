import types

import httpx
import pytest

import opik.exceptions as exceptions
import opik.guardrails.stored_policies as stored_policies
from opik.guardrails import Guardrail, guardrail as guardrail_module, schemas
from opik.rest_api import core as rest_api_core


def _recording_client(response):
    calls = []

    def request(path, method, json):
        calls.append({"path": path, "method": method, "json": json})
        return response

    httpx_client = types.SimpleNamespace(request=request)
    client = types.SimpleNamespace(
        rest_client=types.SimpleNamespace(
            _client_wrapper=types.SimpleNamespace(httpx_client=httpx_client)
        )
    )
    return client, calls


def test_retrieve_policies__happyflow():
    response = httpx.Response(
        200,
        json={
            "policies": [
                {
                    "id": "0195b8e0-0000-7000-8000-000000000001",
                    "name": "no_contact_information",
                    "execution_scope": "on_request",
                    "rules": [
                        {"type": "PII", "config": {"entities": ["EMAIL_ADDRESS"]}}
                    ],
                }
            ]
        },
    )
    client, calls = _recording_client(response)

    policies = stored_policies.retrieve_policies(
        client=client, names=["no_contact_information"]
    )

    assert calls == [
        {
            "path": stored_policies.RETRIEVE_POLICIES_PATH,
            "method": "POST",
            "json": {"names": ["no_contact_information"]},
        }
    ]
    assert len(policies) == 1
    assert policies[0].name == "no_contact_information"
    assert policies[0].rules[0].type == "PII"


def test_retrieve_policies__no_names_requested__empty_name_list_sent():
    client, calls = _recording_client(httpx.Response(200, json={"policies": []}))

    assert stored_policies.retrieve_policies(client=client, names=None) == []
    assert calls[0]["json"] == {"names": []}


def test_retrieve_policies__policy_name_not_found__api_error_raised():
    client, _ = _recording_client(
        httpx.Response(404, json={"message": "Guardrail policies not found: unknown"})
    )

    with pytest.raises(rest_api_core.ApiError) as exception_info:
        stored_policies.retrieve_policies(client=client, names=["unknown"])

    assert exception_info.value.status_code == 404
    assert exception_info.value.body == {
        "message": "Guardrail policies not found: unknown"
    }


def test_build_rules__every_rule_type__merged_into_one_rule_list():
    policies = [
        stored_policies.StoredPolicy(
            name="policy_a",
            rules=[
                {"type": "PII", "config": {"entities": ["CREDIT_CARD", "PERSON"]}},
                {
                    "type": "TOPIC",
                    "config": {
                        "allowed_topics": ["support"],
                        "restricted_topics": ["finance"],
                    },
                },
            ],
        ),
        stored_policies.StoredPolicy(
            name="policy_b",
            rules=[{"type": "PROMPT_INJECTION", "config": {}}],
        ),
    ]

    built_rules = stored_policies.build_rules(policies)

    assert [
        config for rule in built_rules for config in rule.get_validation_configs()
    ] == [
        {
            "type": schemas.ValidationType.PII,
            "config": {"entities": ["CREDIT_CARD", "PERSON"]},
        },
        {
            "type": schemas.ValidationType.TOPIC,
            "config": {
                "allowed_topics": ["support"],
                "restricted_topics": ["finance"],
            },
        },
        {"type": schemas.ValidationType.PROMPT_INJECTION, "config": {}},
    ]


def test_build_rules__unknown_rule_type__error_raised():
    policies = [
        stored_policies.StoredPolicy(
            name="policy_a",
            rules=[{"type": "SOMETHING_NEW", "config": {}}],
        )
    ]

    with pytest.raises(exceptions.GuardrailPolicyError) as exception_info:
        stored_policies.build_rules(policies)

    message = str(exception_info.value)
    assert "policy_a" in message
    assert "SOMETHING_NEW" in message
    assert "Upgrade the SDK" in message


def test_from_stored_policies__happyflow(fake_backend, monkeypatch):
    policies = [
        stored_policies.StoredPolicy(
            name="policy_a",
            rules=[{"type": "PROMPT_INJECTION", "config": {}}],
        )
    ]
    requested_names = []

    def retrieve_policies(client, names):
        requested_names.append(names)
        return policies

    monkeypatch.setattr(
        guardrail_module.stored_policies, "retrieve_policies", retrieve_policies
    )

    guardrail = Guardrail.from_stored_policies(names=["policy_a"])

    assert requested_names == [["policy_a"]]
    assert [
        config for rule in guardrail.rules for config in rule.get_validation_configs()
    ] == [{"type": schemas.ValidationType.PROMPT_INJECTION, "config": {}}]


def test_from_stored_policies__no_policies_retrieved__warning_logged(
    fake_backend, monkeypatch
):
    monkeypatch.setattr(
        guardrail_module.stored_policies, "retrieve_policies", lambda client, names: []
    )
    logged_warnings = []
    monkeypatch.setattr(
        guardrail_module.LOGGER,
        "warning",
        lambda message, *args: logged_warnings.append(message % args),
    )

    guardrail = Guardrail.from_stored_policies()

    assert guardrail.rules == []
    assert "let every input pass" in logged_warnings[0]
