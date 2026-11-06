import json
import logging
from typing import Any, Dict, List, Optional, Sequence

import pydantic

import opik.exceptions as exceptions
from opik.api_objects import opik_client
from opik.rest_api import core as rest_api_core

from . import rest_api_client, rules, schemas

LOGGER = logging.getLogger(__name__)

POLICIES_PATH = "v1/private/guardrails/policies"
PAGE_SIZE = 100


class StoredRule(pydantic.BaseModel):
    """A single stored check: a type and the configuration of the matching rule class."""

    type: str
    config: Dict[str, Any]


class StoredPolicy(pydantic.BaseModel):
    """A named group of rules, as stored in the workspace."""

    name: str
    rules: List[StoredRule]


def retrieve_policies(
    client: opik_client.Opik, names: Optional[Sequence[str]]
) -> List[StoredPolicy]:
    """
    Fetch the named policies, plus every global policy of the workspace, ordered by name.

    Two lookups on the policies list: by names, then by the global execution scope. A
    policy both named and global is returned once.

    Composed by hand rather than through the generated REST client: the endpoint is not
    part of the published OpenAPI definition the client is generated from.

    Raises:
        exceptions.GuardrailPolicyError: If a named policy does not exist. The list
            leaves an unknown name out, and a guardrail must not run with fewer checks
            than the policies it names.
    """
    named_policies = (
        _list_policies(client, {"names": json.dumps(list(names))}) if names else []
    )
    # The backend matches names ignoring case.
    found_names = {policy.name.lower() for policy in named_policies}
    missing_names = sorted(
        name for name in set(names or []) if name.lower() not in found_names
    )
    if missing_names:
        raise exceptions.GuardrailPolicyError(
            f"Guardrail policies not found: {', '.join(missing_names)}"
        )

    global_policies = _list_policies(client, {"execution_scope": "global"})
    policies_by_name = {
        policy.name.lower(): policy for policy in named_policies + global_policies
    }
    return [policies_by_name[name] for name in sorted(policies_by_name)]


def _list_policies(
    client: opik_client.Opik, filters: Dict[str, str]
) -> List[StoredPolicy]:
    policies: List[StoredPolicy] = []
    page = 1
    while True:
        response = client.rest_client._client_wrapper.httpx_client.request(
            POLICIES_PATH,
            method="GET",
            params={**filters, "page": page, "size": PAGE_SIZE},
        )
        if response.status_code != 200:
            raise rest_api_core.ApiError(
                status_code=response.status_code,
                body=rest_api_client.error_body(response),
            )

        page_policies = response.json()["content"]
        policies.extend(StoredPolicy(**policy) for policy in page_policies)
        if len(page_policies) < PAGE_SIZE:
            return policies
        page += 1


def build_rules(policies: Sequence[StoredPolicy]) -> List[rules.Rule]:
    """
    Turn stored policies into rules.

    Which policy a rule came from is not preserved: the rules of every policy are
    checked together, as one flat set.
    """
    return [
        _build_rule(policy_name=policy.name, stored_rule=stored_rule)
        for policy in policies
        for stored_rule in policy.rules
    ]


def _build_rule(policy_name: str, stored_rule: StoredRule) -> rules.Rule:
    config = stored_rule.config

    if stored_rule.type == schemas.ValidationType.PII:
        return rules.PII(entities=config["entities"])

    if stored_rule.type == schemas.ValidationType.TOPIC:
        return rules.Topic(
            allowed_topics=config.get("allowed_topics"),
            restricted_topics=config.get("restricted_topics"),
        )

    if stored_rule.type == schemas.ValidationType.PROMPT_INJECTION:
        return rules.PromptInjection()

    # Not skipped: guardrails fail closed, and a check that cannot run must not silently
    # reduce the protection a policy promises.
    raise exceptions.GuardrailPolicyError(
        f"Guardrail policy '{policy_name}' contains a rule of type '{stored_rule.type}', "
        "which this version of the Opik SDK cannot run. Upgrade the SDK."
    )
