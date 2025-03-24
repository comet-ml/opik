from typing import Dict

import pydantic
from opik_guardrails import schemas

from .. import base_validator
import transformers


class RestrictedTopicValidationDetails(pydantic.BaseModel):
    matched_topics_scores: Dict[str, float]
    scores: Dict[str, float]


class RestrictedTopicValidator(base_validator.BaseValidator):
    """A wrapper for the zero-shot classification model."""

    def __init__(self, pipeline: transformers.Pipeline) -> None:
        self._classification_pipeline = pipeline

    def validate(
        self,
        text: str,
        config: schemas.RestrictedTopicValidationConfig,
    ) -> schemas.ValidationResult:
        classification_result = self._classification_pipeline(text, config.topics)
        scores = {
            label: score
            for label, score in zip(
                classification_result["labels"], classification_result["scores"]
            )
        }

        relevant_topics_scores = {
            label: score for label, score in scores.items() if score >= config.threshold
        }

        return schemas.ValidationResult(
            validation_passed=len(relevant_topics_scores) == 0,
            validation_details=RestrictedTopicValidationDetails(
                matched_topics_scores=relevant_topics_scores,
                scores=scores,
            ),
            type=schemas.ValidationType.RESTRICTED_TOPIC,
            validation_config=config,
        )
