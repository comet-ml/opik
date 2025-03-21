from typing import List, Optional, Union, Dict, Any

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
    config: Union[RestrictedTopicValidationConfig, PIIValidationConfig] = (
        pydantic.Field(description="Configuration for the validation")
    )

    @pydantic.field_validator("config")
    def config_type_matches_validation_type(
        cls,
        config: Union[RestrictedTopicValidationConfig, PIIValidationConfig],
        values: Dict[str, Any],
    ) -> Union[RestrictedTopicValidationConfig, PIIValidationConfig]:
        if values["type"] == ValidationType.RESTRICTED_TOPIC and not isinstance(
            config, RestrictedTopicValidationConfig
        ):
            raise pydantic.ValidationError(
                "Topic validation requires RestrictedTopicValidationConfig config"
            )
        if values["type"] == ValidationType.PII and not isinstance(
            config, PIIValidationConfig
        ):
            raise pydantic.ValidationError(
                "PII validation requires PIIValidationConfig config"
            )
        return config


class GuardrailsValidationRequest(pydantic.BaseModel):
    text: str = pydantic.Field(description="The text to validate")
    validations: List[ValidationDescriptor] = pydantic.Field(
        min_items=1, description="List of validations to perform"
    )
