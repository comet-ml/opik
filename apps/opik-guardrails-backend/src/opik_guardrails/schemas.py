from typing import List, Optional, Dict, Any

import enum
import pydantic


class ValidationType(str, enum.Enum):
    PII = "PII"
    RESTRICTED_TOPIC = "RESTRICTED_TOPIC"


class ValidationConfig(pydantic.BaseModel):
    pass


class RestrictedTopicValidationConfig(ValidationConfig):
    topics: List[str] = pydantic.Field(
        min_items=1, description="A list of topics to check"
    )
    threshold: float = pydantic.Field(
        0.5, description="A threshold value for classifier"
    )


class PIIValidationConfig(ValidationConfig):
    entities: Optional[List[str]] = pydantic.Field(
        None,
        description="Optional list of entity types to detect. If not provided, all supported entity types will be detected.",
    )
    language: str = pydantic.Field("en", description="Language of the text")


class RestrictedTopicValidationRequest(pydantic.BaseModel):
    text: str = pydantic.Field(description="The text to classify")
    config: RestrictedTopicValidationConfig = pydantic.Field(
        description="Configuration for the topic classification"
    )


class PIIValidationRequest(pydantic.BaseModel):
    text: str = pydantic.Field(description="The text to analyze for PII")
    config: PIIValidationConfig = pydantic.Field(
        description="Configuration for the PII detection"
    )


class ValidationDescriptor(pydantic.BaseModel):
    type: ValidationType = pydantic.Field(description="Type of validation to perform")
    config: Dict[str, Any] = pydantic.Field(
        description="Configuration for the validation, must follow the format of the existing validation config types"
    )


class GuardrailsValidationRequest(pydantic.BaseModel):
    text: str = pydantic.Field(description="The text to validate")
    validations: List[ValidationDescriptor] = pydantic.Field(
        min_items=1, description="List of validations to perform"
    )
