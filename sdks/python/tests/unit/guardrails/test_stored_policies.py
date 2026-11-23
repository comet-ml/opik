import json
import types

import httpx
import pytest

import opik.exceptions as exceptions
import opik.guardrails.stored_policies as stored_policies
from opik.guardrails import Guardrail, guardrail as guardrail_module, schemas
from opik.rest_api import core as rest_api_core


def _policy(name, execution_scope="on_request"):
    return {
        "id": "0195b8e0-0000-7000-8000-000000000001",
        "name": name,
        "execution_scope": execution_scope,
        "rules": [{"type": "PII", "config": {"entities": ["EMAIL_ADDRESS"]}}],
    }


def _recording_client(respond):
    """A client whose policy list answers with ``respond(params)``, recording each call."""
    calls = []

    def request(path, method, params):
        calls.append({"path": path, "method": method, "params": params})
        return respond(params)

    httpx_client = types.SimpleNamespace(request=request)
    client = types.SimpleNamespace(
        rest_client=types.SimpleNamespace(
            _client_wrapper=types.SimpleNamespace(httpx_client=httpx_client)
        )
    )
    return client, calls


def _page(*policies):
    return httpx.Response(200, json={"content": list(policies)})


def test_retrieve_policies__happyflow():
    def respond(params):
        if "names" in params:
            return _page(_policy("no_contact_information"))
        return _page(_policy("company_wide", execution_scope="global"))

    client, calls = _recording_client(respond)

    policies = stored_policies.retrieve_policies(
        client=client, names=["no_contact_information"]
    )

    assert calls == [
        {
            "path": stored_policies.POLICIES_PATH,
            "method": "GET",
            "params": {
                "names": '["no_contact_information"]',
                "page": 1,
                "size": stored_policies.PAGE_SIZE,
            },
        },
        {
            "path": stored_policies.POLICIES_PATH,
            "method": "GET",
            "params": {
                "execution_scope": "global",
                "page": 1,
                "size": stored_policies.PAGE_SIZE,
            },
        },
    ]
    assert [policy.name for policy in policies] == [
        "company_wide",
        "no_contact_information",
    ]
    assert policies[1].rules[0].type == "PII"


def test_retrieve_policies__no_names_requested__only_global_policies_fetched():
    client, calls = _recording_client(lambda params: _page())

    assert stored_policies.retrieve_policies(client=client, names=None) == []
    assert [call["params"] for call in calls] == [
        {"execution_scope": "global", "page": 1, "size": stored_policies.PAGE_SIZE}
    ]


def test_retrieve_policies__policy_name_not_found__policy_error_raised():
    def respond(params):
        names = json.loads(params.get("names", "[]"))
        return _page(*[_policy(name) for name in names if name == "known"])

    client, calls = _recording_client(respond)

    with pytest.raises(exceptions.GuardrailPolicyError) as exception_info:
        stored_policies.retrieve_policies(client=client, names=["known", "unknown"])

    assert str(exception_info.value) == "Guardrail policies not found: unknown"
    # The lookup by names, then "unknown" on its own; no global lookup.
    assert [call["params"].get("names") for call in calls] == [
        '["known", "unknown"]',
        '["unknown"]',
    ]


def test_retrieve_policies__names_from_a_generator__unknown_name_still_raises():
    client, _ = _recording_client(lambda params: _page())

    with pytest.raises(exceptions.GuardrailPolicyError) as exception_info:
        stored_policies.retrieve_policies(
            client=client, names=(name for name in ["unknown"])
        )

    assert str(exception_info.value) == "Guardrail policies not found: unknown"


@pytest.mark.parametrize("invalid_name", [None, "", "  "])
def test_retrieve_policies__name_not_a_non_empty_string__policy_error_raised_before_any_lookup(
    invalid_name,
):
    client, calls = _recording_client(lambda params: _page())

    with pytest.raises(exceptions.GuardrailPolicyError) as exception_info:
        stored_policies.retrieve_policies(client=client, names=["known", invalid_name])

    assert str(exception_info.value) == (
        "Guardrail policy names must be non-empty strings, "
        f"but names[1] is {invalid_name!r}"
    )
    assert calls == []


def test_retrieve_policies__name_in_another_case__found():
    client, _ = _recording_client(
        lambda params: _page(_policy("No_Contact")) if "names" in params else _page()
    )

    policies = stored_policies.retrieve_policies(client=client, names=["no_contact"])

    assert [policy.name for policy in policies] == ["No_Contact"]


def test_retrieve_policies__name_matched_only_by_the_backend_collation__found():
    # The backend's collation ignores accents, so "Cafe" finds "Café", which lower() doesn't match.
    client, calls = _recording_client(
        lambda params: _page(_policy("Café")) if "names" in params else _page()
    )

    policies = stored_policies.retrieve_policies(client=client, names=["Cafe"])

    assert [policy.name for policy in policies] == ["Café"]
    assert [call["params"].get("names") for call in calls] == [
        '["Cafe"]',
        '["Cafe"]',
        None,
    ]


def test_retrieve_policies__named_policy_also_global__returned_once():
    client, _ = _recording_client(
        lambda params: _page(_policy("company_wide", execution_scope="global"))
    )

    policies = stored_policies.retrieve_policies(client=client, names=["company_wide"])

    assert [policy.name for policy in policies] == ["company_wide"]


def test_retrieve_policies__more_than_one_page__every_page_fetched(monkeypatch):
    monkeypatch.setattr(stored_policies, "PAGE_SIZE", 2)
    pages = {
        1: _page(_policy("a", "global"), _policy("b", "global")),
        2: _page(_policy("c", "global")),
    }
    client, calls = _recording_client(lambda params: pages[params["page"]])

    policies = stored_policies.retrieve_policies(client=client, names=None)

    assert [policy.name for policy in policies] == ["a", "b", "c"]
    assert [call["params"]["page"] for call in calls] == [1, 2]


def test_retrieve_policies__error_status__api_error_raised():
    client, _ = _recording_client(
        lambda params: httpx.Response(
            403, json={"message": "Guardrails are not enabled for this workspace"}
        )
    )

    with pytest.raises(rest_api_core.ApiError) as exception_info:
        stored_policies.retrieve_policies(client=client, names=["any"])

    assert exception_info.value.status_code == 403
    assert exception_info.value.body == {
        "message": "Guardrails are not enabled for this workspace"
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
