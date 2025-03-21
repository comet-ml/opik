from typing import Dict

import pydantic
import torch
import transformers

from opik_guardrails import schemas

from . import base_validator

MODEL_PATH = "facebook/bart-large-mnli"
DEVICE = "cuda:0"


class RestrictedTopicValidationDetails(pydantic.BaseModel):
    matched_topics_scores: Dict[str, float]
    scores: Dict[str, float]


class RestrictedTopicValidator(base_validator.BaseValidator):
    """A wrapper for the zero-shot classification model."""

    def __init__(self) -> None:
        self._classification_pipeline = _load_model(
            model_path=MODEL_PATH, device=DEVICE
        )

    def validate(
        self,
        text: str,
        config: schemas.RestrictedTopicValidationConfig,
    ) -> base_validator.ValidationResult:
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

        return base_validator.ValidationResult(
            validation_passed=len(relevant_topics_scores) == 0,
            validation_details=RestrictedTopicValidationDetails(
                matched_topics_scores=relevant_topics_scores,
                scores=scores,
            ),
            type=schemas.ValidationType.RESTRICTED_TOPIC,
            validation_config=config,
        )


def _load_model(model_path: str, device: str) -> transformers.Pipeline:
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    torch_dtype = (
        torch.float16
        if (torch.cuda.is_available() and device != "cpu")
        else torch.float32
    )

    model: torch.nn.Module = (
        transformers.AutoModelForSequenceClassification.from_pretrained(
            model_path,
            torch_dtype=torch_dtype,
            device_map=device,
        )
    )
    model.eval()

    tokenizer = transformers.AutoTokenizer.from_pretrained(model_path)

    classifier = transformers.pipeline(
        task="zero-shot-classification",
        model=model,
        tokenizer=tokenizer,
        multi_label=True,
    )

    return classifier
