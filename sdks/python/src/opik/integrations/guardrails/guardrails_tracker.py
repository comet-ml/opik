from typing import Optional

import guardrails

from . import guardrails_decorator


def track_guardrails(
    guard: guardrails.Guard, project_name: Optional[str] = None
) -> guardrails.Guard:
    """
    Adds Opik tracking to a guardrails Guard instance.

    Every validation step will be logged as a trace.

    Args:
        guard: An instance of Guard object.
        project_name: The name of the project to log data.

    Returns:
        The modified Guard instance with Opik tracking enabled for its validators.
    """
    validators = guard._validators
    decorator_factory = guardrails_decorator.GuardrailsValidatorValidateDecorator()

    for validator in validators:
        if hasattr(validator.validate, "opik_tracked"):
            continue

        validate_decorator = decorator_factory.track(
            name=f"{validator.rail_alias}.validate",
            project_name=project_name,
            type="llm" if hasattr(validator, "llm_callable") else "general",
        )
        setattr(validator, "validate", validate_decorator(validator.validate))

    return guard
