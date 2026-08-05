import types

import pytest

import opik.exceptions as exceptions
import opik.guardrails.stored_policies as stored_policies
from opik.guardrails import Guardrail, guardrail as guardrail_module, schemas
from opik.rest_api import core as rest_api_core


def _fake_client(response):
    httpx_client = types.SimpleNamespace(request=lambda *args, **kwargs: response)
    return types.SimpleNamespace(
        rest_client=types.SimpleNamespace(
            _client_wrapper=types.SimpleNamespace(httpx_client=httpx_client)
        )
    )


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
    response = types.SimpleNamespace(
        status_code=200,
        json=lambda: {
            "policies": [
                {
                    "id": "0195b8e0-0000-7000-8000-000000000001",
                    "name": "no_contact_information",
                    "execution_mode": "ON_REQUEST",
                    "guards": [
                        {
                            "type": "PII",
                            "config": {
                                "blocked_entities": ["EMAIL_ADDRESS"],
                                "threshold": 0.4,
                            },
                        }
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
    assert policies[0].guards[0].type == "PII"


def test_retrieve_policies__no_names_requested__empty_name_list_sent():
    response = types.SimpleNamespace(status_code=200, json=lambda: {"policies": []})
    client, calls = _recording_client(response)

    assert stored_policies.retrieve_policies(client=client, names=None) == []
    assert calls[0]["json"] == {"names": []}


def test_retrieve_policies__policy_name_not_found__api_error_raised():
    response = types.SimpleNamespace(
        status_code=404, text='{"message": "Guardrail policies not found: unknown"}'
    )

    with pytest.raises(rest_api_core.ApiError) as exception_info:
        stored_policies.retrieve_policies(
            client=_fake_client(response), names=["unknown"]
        )

    assert exception_info.value.status_code == 404


def test_build_guards__every_guard_type__merged_into_one_guard_list():
    policies = [
        stored_policies.StoredPolicy(
            name="policy_a",
            guards=[
                {
                    "type": "PII",
                    "config": {
                        "blocked_entities": ["CREDIT_CARD", "PERSON"],
                        "threshold": 0.4,
                    },
                },
                {
                    "type": "TOPIC",
                    "config": {
                        "allowed_topics": ["support"],
                        "restricted_topics": ["finance"],
                        "threshold": 0.8,
                    },
                },
            ],
        ),
        stored_policies.StoredPolicy(
            name="policy_b",
            guards=[
                {"type": "PROMPT_INJECTION", "config": {"threshold": 0.7}},
                {
                    "type": "CUSTOM_CLASSIFIER",
                    "config": {"model_name": "toxicity", "threshold": 0.6},
                },
                {
                    "type": "LLM_JUDGE",
                    "config": {
                        "instructions": "must not mention competitors",
                        "model": "gpt-4o",
                    },
                },
            ],
        ),
    ]

    built_guards = stored_policies.build_guards(policies)

    validation_configs = [
        config for guard in built_guards for config in guard.get_validation_configs()
    ]
    assert validation_configs == [
        {
            "type": schemas.ValidationType.PII,
            "config": {
                "entities": ["CREDIT_CARD", "PERSON"],
                "language": "en",
                "threshold": 0.4,
            },
        },
        {
            "type": schemas.ValidationType.TOPIC,
            "config": {"topics": ["support"], "threshold": 0.8, "mode": "allow"},
        },
        {
            "type": schemas.ValidationType.TOPIC,
            "config": {"topics": ["finance"], "threshold": 0.8, "mode": "restrict"},
        },
        {
            "type": schemas.ValidationType.PROMPT_INJECTION,
            "config": {"threshold": 0.7},
        },
        {
            "type": schemas.ValidationType.CUSTOM_CLASSIFIER,
            "config": {"model_name": "toxicity", "threshold": 0.6},
        },
    ]

    # The judge runs in the SDK, so it contributes no backend validation config, and it is
    # labeled with the name of the policy that holds it.
    llm_judge = built_guards[-1]
    assert llm_judge.local is True
    assert llm_judge._name == "policy_b"
    assert llm_judge._instructions == "must not mention competitors"
    # The stored model name is resolved client-side, so the guard holds a model object.
    assert llm_judge._model.model_name == "gpt-4o"


def test_build_guards__unknown_guard_type__error_raised():
    policies = [
        stored_policies.StoredPolicy(
            name="policy_a",
            guards=[{"type": "SOMETHING_NEW", "config": {"threshold": 0.5}}],
        )
    ]

    with pytest.raises(exceptions.GuardrailPolicyError):
        stored_policies.build_guards(policies)


def test_from_stored_policies__happyflow(fake_backend, monkeypatch):
    policies = [
        stored_policies.StoredPolicy(
            name="policy_a",
            guards=[{"type": "PROMPT_INJECTION", "config": {"threshold": 0.7}}],
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
        config
        for guard in guardrail.guards
        for config in guard.get_validation_configs()
    ] == [
        {
            "type": schemas.ValidationType.PROMPT_INJECTION,
            "config": {"threshold": 0.7},
        }
    ]


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

    assert guardrail.guards == []
    assert "let every input pass" in logged_warnings[0]
