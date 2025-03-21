from typing import Dict

from opik_guardrails import schemas

from .validators import base_validator
from .validators import pii as pii_validator
from .validators import restricted_topic as restricted_topic_validator

VALIDATORS_MAPPING: Dict[schemas.ValidatorType, base_validator.BaseValidator] = {
    schemas.ValidatorType.PII: pii_validator.PIIValidator(),
    schemas.ValidatorType.RESTRICTED_TOPIC: restricted_topic_validator.RestrictedTopicValidator(),
}


def run_validator(
    validator_type: schemas.ValidatorType, text: str, config: schemas.ValidationConfig
) -> base_validator.ValidationResult:
    """
    Run a validator of the specified type with the given text and configuration.

    Args:
        validator_type: Type of validator to run
        text: Text to validate
        config: Configuration for the validator

    Returns:
        Validation result from the validator
    """
    return VALIDATORS_MAPPING[validator_type].validate(text, config)
