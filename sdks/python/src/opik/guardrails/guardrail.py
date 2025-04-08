from typing import (
    List,
    Optional,
    Callable,
    AsyncGenerator,
    Generator,
    Union,
    Any,
    Dict,
    Tuple,
)

import httpx

from opik import exceptions
from opik.api_objects import opik_client
from opik.decorator import (
    arguments_helpers,
)
from opik.decorator import base_track_decorator
from opik.api_objects import span

from . import guards, rest_api_client, schemas


class GuardrailsTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for guardrails span.
    """

    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert isinstance(kwargs, dict)

        result = arguments_helpers.StartSpanParameters(
            name="Guardrail",
            input={"generation": kwargs["generation"]},
            type="tool",  # TODO: Replace with type="guardrail"
        )

        return result

    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        result = arguments_helpers.EndSpanParameters(
            output=output,
        )

        return result

    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
        return super()._streams_handler(output, capture_output, generations_aggregator)


GUARDRAIL_DECORATOR = GuardrailsTrackDecorator()


class Guardrail:
    """
    Client for the Opik Guardrails API.

    This class provides a way to validate text against a set of guardrails.
    """

    def __init__(
        self,
        guards: List[guards.Guard],
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

        self._initialize_api_client(
            host_url=self._client.config.guardrails_backend_host,
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
        result = self._validate(generation=text)

        return self._parse_result(result)

    @GUARDRAIL_DECORATOR.track
    def _validate(self, generation: str) -> schemas.ValidationResponse:
        validations = []

        for guard in self.guards:
            validations.extend(guard.get_validation_configs())

        result = self._api_client.validate(generation, validations)

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
