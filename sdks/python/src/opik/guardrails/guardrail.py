from typing import List

import httpx

from opik import exceptions

from . import guards, rest_api_client, schemas


class Guardrail:
    """
    Client for the Opik Guardrails API.

    This class provides a way to validate text against a set of guardrails.
    """

    def __init__(
        self,
        guards: List[guards.Guard],
        host_url: str = "http://localhost:5000",
    ) -> None:
        """
        Initialize a Guardrail client.

        Args:
            guards: List of Guard objects to validate text against
            host_url: URL of the Opik Guardrails API
            timeout: Request timeout in seconds
            headers: Additional headers to include in requests
        """
        self.guards = guards
        self._initialize_api_client(
            host_url=host_url,
        )

    def _initialize_api_client(self, host_url: str) -> None:
        self._api_client = rest_api_client.GuardrailsApiClient(
            httpx_client=httpx.Client(),
            host_url=host_url,
        )

    def validate(self, text: str) -> schemas.ValidationResponse:
        """
        Validate text against all configured guardrails.

        Args:
            text: Text to validate

        Returns:
            ValidationResponse: API response containing validation results

        Raises:
            opik.exceptions.GuardrailValidationFailed: If validation fails
            httpx.HTTPStatusError: If the API returns an error status code
        """
        validations = []
        for guard in self.guards:
            validations.extend(guard.get_validation_configs())

        result = self._api_client.validate(text, validations)

        if not result.validation_passed:
            failed_validations = []
            for validation in result.validations:
                if not validation.validation_passed:
                    failed_validations.append(validation)

            raise exceptions.GuardrailValidationFailed(
                "Guardrail validation failed",
                validation_results=result,
                failed_validations=failed_validations,
            )

        return result
