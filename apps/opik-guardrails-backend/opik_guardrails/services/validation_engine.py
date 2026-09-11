from typing import Dict, Any, Type

from opik_guardrails import schemas

from .validators import pii
from .validators import topic
from .validators import prompt_injection
from .validators import custom_classifier
from .validators import base_validator


_VALIDATION_CONFIG_TYPES_MAPPING: Dict[
    schemas.ValidationType, Type[schemas.ValidationConfig]
] = {
    schemas.ValidationType.PII: schemas.PIIValidationConfig,
    schemas.ValidationType.TOPIC: schemas.TopicValidationConfig,
    schemas.ValidationType.PROMPT_INJECTION: schemas.PromptInjectionValidationConfig,
    schemas.ValidationType.CUSTOM_CLASSIFIER: schemas.CustomClassifierValidationConfig,
}


_VALIDATORS_MAPPING: Dict[schemas.ValidationType, base_validator.BaseValidator] = {
    schemas.ValidationType.PII: pii.construct_pii_validator(),
    schemas.ValidationType.TOPIC: topic.construct_topic_validator(),
    schemas.ValidationType.PROMPT_INJECTION: prompt_injection.construct_prompt_injection_validator(),
    schemas.ValidationType.CUSTOM_CLASSIFIER: custom_classifier.construct_custom_classifier_validator(),
}


def run_validator(
    validation_type: schemas.ValidationType,
    text: str,
    config: schemas.ValidationConfig,
) -> schemas.ValidationResult:
    return _VALIDATORS_MAPPING[validation_type].validate(text, config)


def build_validation_config_from_raw_dict(
    validation_type: schemas.ValidationType,
    config_dict: Dict[str, Any],
) -> schemas.ValidationConfig:
    return _VALIDATION_CONFIG_TYPES_MAPPING[validation_type](**config_dict)
