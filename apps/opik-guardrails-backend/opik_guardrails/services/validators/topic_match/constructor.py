from . import validator, pipeline_loader


def construct_topic_match_validator() -> validator.TopicMatchValidator:
    pipeline = pipeline_loader.load_pipeline()
    return validator.TopicMatchValidator(pipeline)
