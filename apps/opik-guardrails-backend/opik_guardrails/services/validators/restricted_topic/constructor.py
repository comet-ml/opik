from . import validator, pipeline_loader


def construct_restricted_topic_validator() -> validator.RestrictedTopicValidator:
    pipeline = pipeline_loader.load_pipeline()
    return validator.RestrictedTopicValidator(pipeline)
