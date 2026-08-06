import logging
from typing import Any, Dict, List, Optional, Sequence

import pydantic

import opik.exceptions as exceptions
from opik.api_objects import opik_client
from opik.rest_api import core as rest_api_core

from . import guards, schemas

LOGGER = logging.getLogger(__name__)

RETRIEVE_POLICIES_PATH = "v1/private/guardrails/policies/retrieve"


class StoredGuard(pydantic.BaseModel):
    """A single stored check: a type and the configuration of the matching guard class."""

    type: str
    config: Dict[str, Any]


class StoredPolicy(pydantic.BaseModel):
    """A named group of guards, as stored in the workspace."""

    name: str
    guards: List[StoredGuard]


def retrieve_policies(
    client: opik_client.Opik, names: Optional[Sequence[str]]
) -> List[StoredPolicy]:
    """
    Fetch the named policies, plus every policy the workspace applies unconditionally.

    Composed by hand rather than through the generated REST client: the endpoint is not
    part of the published OpenAPI definition the client is generated from.
    """
    response = client.rest_client._client_wrapper.httpx_client.request(
        RETRIEVE_POLICIES_PATH,
        method="POST",
        json={"names": list(names) if names is not None else []},
    )

    if response.status_code != 200:
        raise rest_api_core.ApiError(
            status_code=response.status_code, body=response.text
        )

    return [StoredPolicy(**policy) for policy in response.json()["policies"]]


def build_guards(policies: Sequence[StoredPolicy]) -> List[guards.Guard]:
    """
    Turn stored policies into runtime guards.

    Which policy a guard came from is not preserved: the guards of every policy are
    checked together, as one flat set.
    """
    return [
        _build_guard(policy_name=policy.name, stored_guard=stored_guard)
        for policy in policies
        for stored_guard in policy.guards
    ]


def _build_guard(policy_name: str, stored_guard: StoredGuard) -> guards.Guard:
    config = stored_guard.config

    if stored_guard.type == schemas.ValidationType.PII:
        return guards.PII(
            blocked_entities=config["blocked_entities"],
            threshold=config["threshold"],
        )

    if stored_guard.type == schemas.ValidationType.TOPIC:
        return guards.Topic(
            allowed_topics=config.get("allowed_topics"),
            restricted_topics=config.get("restricted_topics"),
            threshold=config["threshold"],
        )

    if stored_guard.type == schemas.ValidationType.PROMPT_INJECTION:
        return guards.PromptInjection(threshold=config["threshold"])

    if stored_guard.type == schemas.ValidationType.CUSTOM_CLASSIFIER:
        return guards.CustomGuardrail(
            model_name=config["model_name"],
            threshold=config["threshold"],
        )

    if stored_guard.type == schemas.ValidationType.LLM_JUDGE:
        # A policy holds at most one judge, so its results are labeled with the policy name.
        return guards.LLMJudge(
            name=policy_name,
            instructions=config["instructions"],
            model=config["model"],
        )

    # Not skipped: guardrails fail closed, and a check that cannot run must not silently
    # reduce the protection a policy promises.
    raise exceptions.GuardrailPolicyError(
        f"Guardrail policy '{policy_name}' contains a guard of type '{stored_guard.type}', "
        "which this version of the Opik SDK cannot run. Upgrade the SDK."
    )
