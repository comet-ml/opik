from . import validator, pipeline_loader
import os


def construct_restricted_topic_validator() -> validator.RestrictedTopicValidator:
    pipeline = pipeline_loader.load_pipeline(
        model_path="facebook/bart-large-mnli",
        device=os.environ.get("OPIK_GUARDRAILS_DEVICE", "cpu"),
    )
    return validator.RestrictedTopicValidator(pipeline)
