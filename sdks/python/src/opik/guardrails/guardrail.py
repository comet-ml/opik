from typing import List, Optional

import httpx

from opik import exceptions
from opik.api_objects import opik_client
from opik.decorator import (
    span_creation_handler,
    arguments_helpers,
)
from opik.types import DistributedTraceHeadersDict
from opik.decorator import error_info_collector

from . import guards, rest_api_client, schemas


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
        # TODO: Support?
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict] = None

        start_span_arguments = arguments_helpers.StartSpanParameters(
            name="Guardrail",
            input={"generation": text},
            type="tool",
            # type="guardrail", # TODO: Set it back to guardrail
        )

        created_trace_data, created_span_data = (
            span_creation_handler.create_span_for_current_context(
                start_span_arguments=start_span_arguments,
                distributed_trace_headers=opik_distributed_trace_headers,
            )
        )
        print("Creatred", created_trace_data, created_span_data)
        # There shouldn't be any spans created while guardrail is running so no need to track span and trace

        # if created_trace_data is not None:
        #     context_storage.set_trace_data(created_trace_data)

        # context_storage.add_span_data(created_span_data)
        # END

        try:
            result = self._validate(text)
        except exceptions.GuardrailValidationFailed as exception:
            print("Guardrail failed:", exception)

            error_info = error_info_collector.collect(exception)
            created_span_data.update(error_info=error_info).init_end_time()

            self._client.span(**created_span_data.__dict__)
            print("HERE???")

            if created_trace_data:
                created_trace_data.init_end_time().update(error_info=error_info)
                self._client.trace(**created_trace_data.__dict__)

            raise

        # END
        created_span_data.update(output=result).init_end_time()

        self._client.span(**created_span_data.__dict__)

        if created_trace_data:
            created_trace_data.init_end_time().update(output=result)
            self._client.trace(**created_trace_data.__dict__)
        # END END

        return result

    def _validate(self, text: str) -> schemas.ValidationResponse:
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
