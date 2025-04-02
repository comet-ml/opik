from typing import Any, Dict, List

import httpx

import opik.guardrails.schemas as schemas


class GuardrailsApiClient:
    def __init__(
        self,
        httpx_client: httpx.Client,
        host_url: str,
    ) -> None:
        self._api_url = host_url.rstrip("/")
        self._httpx_client = httpx_client

    def __del__(self) -> None:
        self._httpx_client.close()

    def validate(
        self, text: str, validations: List[Dict[str, Any]]
    ) -> schemas.ValidationResponse:
        payload = {"text": text, "validations": validations}

        response = self._httpx_client.post(
            f"{self._api_url}/api/v1/guardrails/validations", json=payload
        )
        response.raise_for_status()
        return schemas.ValidationResponse(**response.json())
