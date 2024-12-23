from typing import Optional

import guardrails
from . import guardrails_decorator


def track_guardrails(
    guard: guardrails.Guard, project_name: Optional[str] = None
) -> guardrails.Guard:
    validators = guard._validators
    decorator_factory = guardrails_decorator.GuardrailsValidatorValidateDecorator()

    for validator in validators:
        validate_decorator = decorator_factory.track(
            name=f"{validator.rail_alias}.validate",
            project_name=project_name,
            type="llm",
        )

        setattr(
            validator, "async_validate", validate_decorator(validator.async_validate)
        )

    return guard
