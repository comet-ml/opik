from typing import Dict

from opik_guardrails import schemas

from .validators import base_validator
from .validators import pii as pii_validator
from .validators import restricted_topic as restricted_topic_validator

VALIDATORS_MAPPING: Dict[schemas.ValidationType, base_validator.BaseValidator] = {
    schemas.ValidationType.PII: pii_validator.PIIValidator(),
    schemas.ValidationType.RESTRICTED_TOPIC: restricted_topic_validator.RestrictedTopicValidator(),
}


def run_validator(
    validation_type: schemas.ValidationType, text: str, config: schemas.ValidationConfig
) -> base_validator.ValidationResult:
    return VALIDATORS_MAPPING[validation_type].validate(text, config)
