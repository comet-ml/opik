import mock

from opik_guardrails.services.validators.restricted_topic import validator
from opik_guardrails import schemas


def test_validate_with_no_matched_topics__validation_passed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics"],
        "scores": [0.3, 0.2, 0.1],
    }

    topic_validator = validator.RestrictedTopicValidator(pipeline=mock_pipeline)
    text = "This is a test text about nothing in particular."
    config = schemas.RestrictedTopicValidationConfig(
        topics=["finance", "healthcare", "politics"], threshold=0.7
    )

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": True,
        "validation_details": {
            "matched_topics_scores": {},
            "scores": {
                "finance": 0.3,
                "healthcare": 0.2,
                "politics": 0.1,
            },
        },
        "validation_config": {
            "topics": ["finance", "healthcare", "politics"],
            "threshold": 0.7,
        },
        "type": schemas.ValidationType.RESTRICTED_TOPIC,
    }


def test_validate_with_matched_topics__validation_failed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics"],
        "scores": [0.8, 0.3, 0.2],
    }

    topic_validator = validator.RestrictedTopicValidator(pipeline=mock_pipeline)
    text = "This is a financial text about stocks and market trends."
    config = schemas.RestrictedTopicValidationConfig(
        topics=["finance", "healthcare", "politics"], threshold=0.7
    )

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "matched_topics_scores": {"finance": 0.8},
            "scores": {"finance": 0.8, "healthcare": 0.3, "politics": 0.2},
        },
        "validation_config": {
            "topics": ["finance", "healthcare", "politics"],
            "threshold": 0.7,
        },
        "type": schemas.ValidationType.RESTRICTED_TOPIC,
    }


def test_validate_with_custom_threshold__validation_failed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {"labels": ["healthcare"], "scores": [0.35]}

    topic_validator = validator.RestrictedTopicValidator(pipeline=mock_pipeline)
    text = "This is a text that is slightly healthcare-related."
    config = schemas.RestrictedTopicValidationConfig(
        topics=["healthcare"], threshold=0.3
    )

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "matched_topics_scores": {"healthcare": 0.35},
            "scores": {"healthcare": 0.35},
        },
        "validation_config": {"topics": ["healthcare"], "threshold": 0.3},
        "type": schemas.ValidationType.RESTRICTED_TOPIC,
    }


def test_validate_with_default_threshold__validation_failed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {"labels": ["finance"], "scores": [0.51]}

    topic_validator = validator.RestrictedTopicValidator(pipeline=mock_pipeline)
    text = "This is a financial text about stocks and market trends."
    config = schemas.RestrictedTopicValidationConfig(
        topics=["finance"]
    )  # Uses default threshold of 0.5

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "matched_topics_scores": {"finance": 0.51},
            "scores": {"finance": 0.51},
        },
        "validation_config": {"topics": ["finance"], "threshold": 0.5},
        "type": schemas.ValidationType.RESTRICTED_TOPIC,
    }


def test_multiple_topics_with_mixed_matches__validation_failed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics", "technology"],
        "scores": [0.8, 0.3, 0.7, 0.4],
    }

    topic_validator = validator.RestrictedTopicValidator(pipeline=mock_pipeline)
    text = "This text discusses financial policies and political implications."
    config = schemas.RestrictedTopicValidationConfig(
        topics=["finance", "healthcare", "politics", "technology"], threshold=0.6
    )

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "matched_topics_scores": {"finance": 0.8, "politics": 0.7},
            "scores": {
                "finance": 0.8,
                "healthcare": 0.3,
                "politics": 0.7,
                "technology": 0.4,
            },
        },
        "validation_config": {
            "topics": ["finance", "healthcare", "politics", "technology"],
            "threshold": 0.6,
        },
        "type": schemas.ValidationType.RESTRICTED_TOPIC,
    }
