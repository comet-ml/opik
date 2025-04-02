from typing import List, Optional, Dict, Any, Literal

import enum
import pydantic


class ValidationType(str, enum.Enum):
    PII = "PII"
    TOPIC = "TOPIC"


class ValidationConfig(pydantic.BaseModel):
    pass


class TopicValidationConfig(ValidationConfig):
    topics: List[str] = pydantic.Field(
        min_items=1, description="A list of topics to check"
    )
    threshold: float = pydantic.Field(
        0.5, description="A threshold value for classifier"
    )
    mode: Literal["restrict", "allow"] = pydantic.Field(
        description="Mode for topic matching"
    )


class PIIValidationConfig(ValidationConfig):
    entities: Optional[List[str]] = pydantic.Field(
        [
            "IP_ADDRESS",
            "PHONE_NUMBER",
            "PERSON",
            "MEDICAL_LICENSE",
            "URL",
            "EMAIL_ADDRESS",
            "IBAN_CODE",
        ],
        description="Optional list of entity types to detect. If not provided, the default list will be used.",
    )
    language: str = pydantic.Field("en", description="Language of the text")
    threshold: float = pydantic.Field(
        0.5, description="A threshold value for PII detector"
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


class ValidationResult(pydantic.BaseModel):
    validation_passed: bool
    type: ValidationType
    validation_config: ValidationConfig
    validation_details: pydantic.BaseModel
