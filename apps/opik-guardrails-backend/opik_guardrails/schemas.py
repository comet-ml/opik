from typing import List, Optional, Dict, Any, Literal

import enum
import pydantic


class ValidationType(str, enum.Enum):
    PII = "PII"
    TOPIC = "TOPIC"
    PROMPT_INJECTION = "PROMPT_INJECTION"
    CUSTOM_CLASSIFIER = "CUSTOM_CLASSIFIER"


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


class PromptInjectionValidationConfig(ValidationConfig):
    threshold: float = pydantic.Field(
        0.5, description="Injection-probability threshold above which validation fails"
    )


class CustomClassifierValidationConfig(ValidationConfig):
    model_name: str = pydantic.Field(
        description="Name of the locally-stored custom guardrail model to run"
    )
    threshold: float = pydantic.Field(
        0.5, description="Score threshold above which validation fails"
    )


class CustomGuardrailExample(pydantic.BaseModel):
    text: str
    label: int = pydantic.Field(
        ge=0, le=1, description="1 = metric holds, 0 = does not"
    )


class CustomGuardrailTrainingRequest(pydantic.BaseModel):
    name: str = pydantic.Field(description="Model name used to serve the guardrail")
    description: str = pydantic.Field(
        description='Completes "Determine whether it ...", e.g. "contains toxic language"'
    )
    examples: List[CustomGuardrailExample] = pydantic.Field(
        min_length=10, max_length=50000
    )
    base_model: str = "Qwen/Qwen2.5-1.5B-Instruct"
    epochs: float = 3.0
    overwrite: bool = False


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
