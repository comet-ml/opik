from typing import (
    List,
    Optional,
)

import httpx

import opik.exceptions as exceptions
import opik.config as config
from opik.api_objects import opik_client
from opik.message_processing.messages import (
    GuardrailBatchItemMessage,
    GuardrailBatchMessage,
)
from opik.opik_context import get_current_span_data, get_current_trace_data

from . import guards, rest_api_client, schemas, tracing


GUARDRAIL_DECORATOR = tracing.GuardrailsTrackDecorator()


class Guardrail:
    """
    Client for the Opik Guardrails API.

    This class provides a way to validate text against a set of guardrails.
    """

    def __init__(
        self,
        guards: List[guards.Guard],
        guardrail_timeout: Optional[int] = None,
    ) -> None:
        """
        Initialize a Guardrail client.

        Args:
            guards: List of Guard objects to validate text against

        Example:

        ```python
        from opik.guardrails import Guardrail, PII, Topic
        from opik import exceptions
        guard = Guardrail(
            guards=[
                Topic(restricted_topics=["finance"], threshold=0.8),
                PII(blocked_entities=["CREDIT_CARD", "PERSON"], threshold=0.4),
            ]
        )

        result = guard.validate("How can I start with evaluation in Opik platform?")
        # Guardrail passes

        try:
            result = guard.validate("Where should I invest my money?")
        except exceptions.GuardrailValidationFailed as e:
            print("Guardrail failed:", e)

        try:
            result = guard.validate("John Doe, here is my card number 4111111111111111 how can I use it in Opik platform?.")
        except exceptions.GuardrailValidationFailed as e:
            print("Guardrail failed:", e)
        ```

        """
        self.guards = guards
        self._client = opik_client.get_client_cached()

        self.config_ = config.get_from_user_inputs(
            guardrail_timeout=guardrail_timeout,
        )

        self._initialize_api_client(
            host_url=self._client.config.guardrails_backend_host,
        )

    def _initialize_api_client(self, host_url: str) -> None:
        self._api_client = rest_api_client.GuardrailsApiClient(
            httpx_client=httpx.Client(timeout=self.config_.guardrail_timeout),
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
        result: schemas.ValidationResponse = self._validate(generation=text)  # type: ignore

        return self._parse_result(result)

    @GUARDRAIL_DECORATOR.track
    def _validate(self, generation: str) -> schemas.ValidationResponse:
        validations = []

        for guard in self.guards:
            validations.extend(guard.get_validation_configs())

        result = self._api_client.validate(generation, validations)

        if not result.validation_passed:
            result.guardrail_result = "failed"
        else:
            result.guardrail_result = "passed"

        batch = []

        # Makes mypy happy that a current span and trace exists
        current_span = get_current_span_data()
        current_trace = get_current_trace_data()
        assert current_span is not None
        assert current_trace is not None

        for validation in result.validations:
            guardrail_batch_item_message = GuardrailBatchItemMessage(
                project_name=self._client._project_name,
                entity_id=current_trace.id,
                secondary_id=current_span.id,
                name=validation.type,
                result="passed" if validation.validation_passed else "failed",
                config=validation.validation_config,
                details=validation.validation_details,
            )
            batch.append(guardrail_batch_item_message)

        message = GuardrailBatchMessage(batch=batch)
        self._client._streamer.put(message)

        return result

    def _parse_result(
        self, result: schemas.ValidationResponse
    ) -> schemas.ValidationResponse:
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
