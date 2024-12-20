from functools import wraps
from typing import (
    Any,
    Callable,
    Coroutine,
)

from guardrails import settings
from guardrails.classes.validation.validation_result import ValidationResult
from guardrails.validator_base import Validator

import guardrails

from opik import opik_context
from . import guardrails_decorator

class GuardValidatorsPatcher:
    def __init__(self, project_name: str):
        self._project_name = project_name
        self._decorator_factory = (
            guardrails_decorator.GuardrailsValidatorValidateDecorator()
        )
        # Disable legacy OTEL tracing to avoid duplicate spans
        settings.disable_tracing = True

    def patch(self):
        validators = guardrails.hub.__dir__()  # type: ignore

        for validator_name in validators:
            export = getattr(guardrails.hub, validator_name)  # type: ignore
            if isinstance(export, type) and issubclass(export, Validator):
                # wrapped_validator_validate = self._instrument_validator_validate(
                #     export.validate
                # )
                # setattr(export, "validate", wrapped_validator_validate)

                wrapped_validator_async_validate = (
                    self._instrument_validator_async_validate(export.async_validate)
                )
                setattr(export, "async_validate", wrapped_validator_async_validate)

                setattr(guardrails.hub, validator_name, export)  # type: ignore

    def _instrument_validator_validate(
        self, validator_validate: Callable[..., ValidationResult]
    ):
        @wraps(validator_validate)
        def trace_validator_wrapper(*args, **kwargs):
            validator_name = "validator"

            validator_self = args[0]
            if validator_self is not None and isinstance(validator_self, Validator):
                validator_name = validator_self.rail_alias

            validator_span_name = f"{validator_name}.validate"

            #  Skip this instrumentation in the case of async
            #  when the parent span cannot be fetched from the current context
            #  because Validator.validate is running in a ThreadPoolExecutor
            parent_span = opik_context.get_current_span_data()
            #if not parent_span:
            #   return validator_validate(*args, **kwargs)

            opik_decorator = self._decorator_factory.track(
                name=validator_span_name,
                project_name=self._project_name,
            )

            return opik_decorator(validator_validate)(*args, **kwargs)

        return trace_validator_wrapper

    def _instrument_validator_async_validate(
        self,
        validator_async_validate: Callable[..., Coroutine[Any, Any, ValidationResult]],
    ):
        @wraps(validator_async_validate)
        async def trace_async_validator_wrapper(*args, **kwargs):
            validator_name = "validator"

            validator_self = args[0]
            if validator_self is not None and isinstance(validator_self, Validator):
                validator_name = validator_self.rail_alias

            validator_span_name = f"{validator_name}.validate"

            opik_decorator = self._decorator_factory.track(
                name=validator_span_name,
                project_name=self._project_name,
            )
            print(validator_span_name, "started")
            return await opik_decorator(validator_async_validate)(*args, **kwargs)

        return trace_async_validator_wrapper
