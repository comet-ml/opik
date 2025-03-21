from typing import Dict, Any, Type

from opik_guardrails import schemas

from .validators import base_validator
from .validators import pii as pii_validator
from .validators import restricted_topic as restricted_topic_validator


_VALIDATION_CONFIG_TYPES_MAPPING: Dict[
    schemas.ValidationType, Type[schemas.ValidationConfig]
] = {
    schemas.ValidationType.PII: schemas.PIIValidationConfig,
    schemas.ValidationType.RESTRICTED_TOPIC: schemas.RestrictedTopicValidationConfig,
}


_VALIDATORS_MAPPING: Dict[schemas.ValidationType, base_validator.BaseValidator] = {
    schemas.ValidationType.PII: pii_validator.PIIValidator(),
    schemas.ValidationType.RESTRICTED_TOPIC: restricted_topic_validator.RestrictedTopicValidator(),
}


def run_validator(
    validation_type: schemas.ValidationType,
    text: str,
    config: schemas.ValidationConfig,
) -> base_validator.ValidationResult:
    return _VALIDATORS_MAPPING[validation_type].validate(text, config)


def build_validation_config_from_raw_dict(
    validation_type: schemas.ValidationType,
    config_dict: Dict[str, Any],
) -> schemas.ValidationConfig:
    return _VALIDATION_CONFIG_TYPES_MAPPING[validation_type](**config_dict)
