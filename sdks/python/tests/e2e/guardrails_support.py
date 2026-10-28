import httpx

import opik

POLICIES_PATH = "v1/private/guardrails/policies"


def guardrails_available(opik_client: opik.Opik) -> bool:
    """Whether the backend runs guardrails for this workspace.

    The policies API is gated exactly like the guardrail checks, and a backend with
    guardrails enabled does not start without its inference service configured, so a
    successful read means the checks can run.
    """
    try:
        response = opik_client.rest_client._client_wrapper.httpx_client.request(
            POLICIES_PATH, method="GET", params={"size": 1}
        )
    except httpx.HTTPError:
        return False

    return response.status_code == 200
