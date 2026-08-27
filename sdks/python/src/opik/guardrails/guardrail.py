import logging
from typing import (
    List,
    Optional,
    Sequence,
)

import httpx

import opik.exceptions as exceptions
import opik.config as config
from opik.rest_api import core as rest_api_core
from opik.api_objects import opik_client
from opik.message_processing.messages import (
    GuardrailBatchItemMessage,
    GuardrailBatchMessage,
)
from opik.opik_context import get_current_span_data, get_current_trace_data

from . import guards, rest_api_client, schemas, stored_policies, tracing


LOGGER = logging.getLogger(__name__)

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
        self._client = opik_client.get_global_client()

        self.config_ = config.get_from_user_inputs(
            guardrail_timeout=guardrail_timeout,
        )

        self._initialize_api_client(
            host_url=self._client.config.guardrails_backend_host,
        )

    @classmethod
    def from_stored_policies(
        cls,
        names: Optional[Sequence[str]] = None,
        guardrail_timeout: Optional[int] = None,
    ) -> "Guardrail":
        """
        Build a Guardrail from guardrail policies stored in your Opik workspace.

        A policy is a named group of guards, stored in Opik. Referencing
        policies by name keeps the checks an application runs configurable without a code
        change. Policies the workspace applies unconditionally are always included, whether
        or not they are named here.

        The guards of all retrieved policies are checked together, as one flat set: which
        policy a guard came from is not preserved.

        Args:
            names: Names of the policies to build the guardrail from. Every name must exist,
                a name that does not is an error rather than a check silently not running.
            guardrail_timeout: Timeout in seconds for the guardrails backend calls.

        Returns:
            Guardrail: Guardrail running the guards of the retrieved policies.

        Raises:
            opik.rest_api.core.ApiError: If the policies cannot be retrieved, for example
                when one of ``names`` does not exist in the workspace.
            opik.exceptions.GuardrailPolicyError: If a policy holds a guard type this SDK
                version cannot run.

        Example:

        ```python
        from opik.guardrails import Guardrail
        from opik import exceptions

        guardrail = Guardrail.from_stored_policies(names=["no_contact_information"])

        try:
            guardrail.validate("Call me at 555-0123")
        except exceptions.GuardrailValidationFailed as e:
            print("Guardrail failed:", e)
        ```
        """
        client = opik_client.get_global_client()
        policies = stored_policies.retrieve_policies(client=client, names=names)
        policy_guards = stored_policies.build_guards(policies)

        if len(policy_guards) == 0:
            LOGGER.warning(
                "No guards were found in the stored guardrail policies, so this guardrail "
                "will let every input pass. Requested policy names: %s",
                list(names) if names is not None else [],
            )

        LOGGER.debug(
            "Built a guardrail from stored policies: %s",
            [policy.name for policy in policies],
        )

        return cls(guards=policy_guards, guardrail_timeout=guardrail_timeout)

    def _initialize_api_client(self, host_url: str) -> None:
        self._api_client = rest_api_client.GuardrailsApiClient(
            httpx_client=rest_api_client.build_httpx_client(
                config=self._client.config,
                timeout_seconds=self.config_.guardrail_timeout,
            ),
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
            opik.exceptions.GuardrailValidationError: If a guardrail cannot be
                evaluated (guardrails backend unreachable, timeout, or LLM judge
                provider failure). Guardrails fail closed, so this blocks the
                protected code path.
        """
        result = self._validate(generation=text)

        if result.error is not None:
            raise exceptions.GuardrailValidationError(result.error)

        return self._parse_result(result)

    @GUARDRAIL_DECORATOR.track
    def _validate(self, generation: str) -> schemas.ValidationResponse:
        result = schemas.ValidationResponse(validation_passed=True, validations=[])

        # Fail-closed errors are captured on the result instead of raised so the
        # decorated span still records the guardrail output; validate() re-raises them
        # once the span has been finalized.
        try:
            remote_validations = []
            for guard in self.guards:
                remote_validations.extend(guard.get_validation_configs())

            if remote_validations:
                result = self._api_client.validate(generation, remote_validations)

            for guard in self.guards:
                if guard.local:
                    result.validations.extend(
                        guard.validate_local(generation, self._client)
                    )
        except (httpx.HTTPError, rest_api_core.ApiError) as e:
            result.error = f"Guardrail could not be evaluated, failing closed: {e}"
        except exceptions.GuardrailValidationError as e:
            result.error = str(e)

        if result.error is not None:
            result.validation_passed = False
        else:
            result.validation_passed = all(
                validation.validation_passed for validation in result.validations
            )

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
                # Guardrail results must live in the same project as the trace/span they
                # are attached to; otherwise project-scoped reads (e.g. the traces list)
                # cannot match them. Fall back to the client project when the trace has
                # no explicit project (rare).
                project_name=current_trace.project_name or self._client._project_name,
                entity_id=current_trace.id,
                secondary_id=current_span.id,
                name=validation.type,
                result="passed" if validation.validation_passed else "failed",
                config=validation.validation_config,
                details=validation.validation_details,
            )
            batch.append(guardrail_batch_item_message)

        # A guardrail with no guards produces no results, and the backend rejects an empty
        # guardrail batch (422). Sending it anyway would log an error and report data loss
        # for something that was never lost.
        if batch:
            self._client._streamer.put(GuardrailBatchMessage(batch=batch))

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
