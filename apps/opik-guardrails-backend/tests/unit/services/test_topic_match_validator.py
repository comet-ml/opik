import mock

from opik_guardrails.services.validators.topic_match import validator
from opik_guardrails import schemas


def test_validate_with_no_matched_topics__restrict_mode__validation_passed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics"],
        "scores": [0.3, 0.2, 0.1],
    }

    topic_validator = validator.TopicMatchValidator(pipeline=mock_pipeline)
    text = "This is a test text about nothing in particular."
    config = schemas.TopicMatchValidationConfig(
        topics=["finance", "healthcare", "politics"], threshold=0.7, mode="restrict"
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
            "mode": "restrict",
        },
        "type": schemas.ValidationType.TOPIC_MATCH,
    }


def test_validate_with_matched_topics__restrict_mode__validation_failed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics"],
        "scores": [0.8, 0.3, 0.2],
    }

    topic_validator = validator.TopicMatchValidator(pipeline=mock_pipeline)
    text = "This is a financial text about stocks and market trends."
    config = schemas.TopicMatchValidationConfig(
        topics=["finance", "healthcare", "politics"], threshold=0.7, mode="restrict"
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
            "mode": "restrict",
        },
        "type": schemas.ValidationType.TOPIC_MATCH,
    }


def test_validate_with_custom_threshold__restrict_mode__validation_failed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {"labels": ["healthcare"], "scores": [0.35]}

    topic_validator = validator.TopicMatchValidator(pipeline=mock_pipeline)
    text = "This is a text that is slightly healthcare-related."
    config = schemas.TopicMatchValidationConfig(
        topics=["healthcare"], threshold=0.3, mode="restrict"
    )

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "matched_topics_scores": {"healthcare": 0.35},
            "scores": {"healthcare": 0.35},
        },
        "validation_config": {
            "topics": ["healthcare"],
            "threshold": 0.3,
            "mode": "restrict",
        },
        "type": schemas.ValidationType.TOPIC_MATCH,
    }


def test_validate_with_no_matched_topics__allow_mode__validation_failed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics"],
        "scores": [0.3, 0.2, 0.1],
    }

    topic_validator = validator.TopicMatchValidator(pipeline=mock_pipeline)
    text = "This is a test text about nothing in particular."
    config = schemas.TopicMatchValidationConfig(
        topics=["finance", "healthcare", "politics"], threshold=0.7, mode="allow"
    )

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
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
            "mode": "allow",
        },
        "type": schemas.ValidationType.TOPIC_MATCH,
    }


def test_validate_with_matched_topics__allow_mode__validation_passed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics"],
        "scores": [0.8, 0.3, 0.2],
    }

    topic_validator = validator.TopicMatchValidator(pipeline=mock_pipeline)
    text = "This is a financial text about stocks and market trends."
    config = schemas.TopicMatchValidationConfig(
        topics=["finance", "healthcare", "politics"], threshold=0.7, mode="allow"
    )

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": True,
        "validation_details": {
            "matched_topics_scores": {"finance": 0.8},
            "scores": {"finance": 0.8, "healthcare": 0.3, "politics": 0.2},
        },
        "validation_config": {
            "topics": ["finance", "healthcare", "politics"],
            "threshold": 0.7,
            "mode": "allow",
        },
        "type": schemas.ValidationType.TOPIC_MATCH,
    }


def test_multiple_topics_with_mixed_matches__restrict_mode__validation_failed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics", "technology"],
        "scores": [0.8, 0.3, 0.7, 0.4],
    }

    topic_validator = validator.TopicMatchValidator(pipeline=mock_pipeline)
    text = "This text discusses financial policies and political implications."
    config = schemas.TopicMatchValidationConfig(
        topics=["finance", "healthcare", "politics", "technology"],
        threshold=0.6,
        mode="restrict",
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
            "mode": "restrict",
        },
        "type": schemas.ValidationType.TOPIC_MATCH,
    }


def test_multiple_topics_with_mixed_matches__allow_mode__validation_passed():
    mock_pipeline = mock.Mock()
    mock_pipeline.return_value = {
        "labels": ["finance", "healthcare", "politics", "technology"],
        "scores": [0.8, 0.3, 0.7, 0.4],
    }

    topic_validator = validator.TopicMatchValidator(pipeline=mock_pipeline)
    text = "This text discusses financial policies and political implications."
    config = schemas.TopicMatchValidationConfig(
        topics=["finance", "healthcare", "politics", "technology"],
        threshold=0.6,
        mode="allow",
    )

    result = topic_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": True,
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
            "mode": "allow",
        },
        "type": schemas.ValidationType.TOPIC_MATCH,
    }
