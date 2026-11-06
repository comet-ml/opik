from typing import Any, Dict, List

import httpx

import opik.guardrails.schemas as schemas
from opik.api_objects import opik_client
from opik.rest_api import core as rest_api_core

VALIDATIONS_PATH = "v1/private/guardrails/validations"


def validate_on_opik(
    client: opik_client.Opik,
    text: str,
    validations: List[Dict[str, Any]],
    timeout_seconds: int,
) -> schemas.ValidationResponse:
    """
    Check the text on the Opik backend against the given rules.

    Composed by hand rather than through the generated REST client: the endpoint is not
    yet part of the published OpenAPI definition the client is generated from.
    """
    response = client.rest_client._client_wrapper.httpx_client.request(
        VALIDATIONS_PATH,
        method="POST",
        json={"text": text, "validations": validations},
        request_options={"timeout_in_seconds": timeout_seconds},
    )
    if response.status_code != 200:
        raise rest_api_core.ApiError(
            status_code=response.status_code, body=error_body(response)
        )

    return schemas.ValidationResponse(**response.json())


def error_body(response: httpx.Response) -> Any:
    try:
        return response.json()
    except ValueError:
        return response.text
