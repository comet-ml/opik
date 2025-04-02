from . import validator, pipeline_loader


def construct_topic_validator() -> validator.TopicValidator:
    pipeline = pipeline_loader.load_pipeline()
    return validator.TopicValidator(pipeline)
