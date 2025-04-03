import mock
import presidio_analyzer

from opik_guardrails.services.validators.pii import validator
from opik_guardrails import schemas


def test_validate_with_no_pii_detected__validation_passed__happyflow():
    """Test validation when no PII is detected."""
    mock_engine = mock.Mock()
    mock_engine.analyze.return_value = []

    pii_validator = validator.PIIValidator(engine=mock_engine)
    text = "This is a text with no PII information."
    config = schemas.PIIValidationConfig(
        entities=["PERSON", "EMAIL_ADDRESS", "PHONE_NUMBER"],
        language="en",
        threshold=0.5,
    )

    result = pii_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": True,
        "validation_details": {"detected_entities": {}},
        "validation_config": {
            "entities": ["PERSON", "EMAIL_ADDRESS", "PHONE_NUMBER"],
            "language": "en",
            "threshold": 0.5,
        },
        "type": schemas.ValidationType.PII,
    }


def test_validate_with_pii_detected__validation_failed():
    """Test validation when PII is detected."""
    mock_engine = mock.Mock()
    mock_engine.analyze.return_value = [
        presidio_analyzer.RecognizerResult(
            entity_type="EMAIL_ADDRESS",
            start=11,
            end=31,
            score=0.85,
        ),
        presidio_analyzer.RecognizerResult(
            entity_type="PHONE_NUMBER",
            start=32,
            end=44,
            score=0.95,
        ),
    ]

    pii_validator = validator.PIIValidator(engine=mock_engine)
    text = "Contact at john.doe@example.com +1234567890"
    config = schemas.PIIValidationConfig(
        entities=["EMAIL_ADDRESS", "PHONE_NUMBER"], language="en", threshold=0.5
    )

    result = pii_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "detected_entities": {
                "EMAIL_ADDRESS": [
                    {
                        "start": 11,
                        "end": 31,
                        "score": 0.85,
                        "text": "john.doe@example.com",
                    }
                ],
                "PHONE_NUMBER": [
                    {"start": 32, "end": 44, "score": 0.95, "text": "+1234567890"}
                ],
            }
        },
        "validation_config": {
            "entities": ["EMAIL_ADDRESS", "PHONE_NUMBER"],
            "language": "en",
            "threshold": 0.5,
        },
        "type": schemas.ValidationType.PII,
    }


def test_validate_with_custom_threshold__validation_failed():
    """Test validation with a custom threshold that filters out low-confidence PII."""
    mock_engine = mock.Mock()
    mock_engine.analyze.return_value = [
        presidio_analyzer.RecognizerResult(
            entity_type="EMAIL_ADDRESS",
            start=11,
            end=31,
            score=0.85,
        ),
        presidio_analyzer.RecognizerResult(
            entity_type="PHONE_NUMBER",
            start=32,
            end=44,
            score=0.95,
        ),
    ]

    pii_validator = validator.PIIValidator(engine=mock_engine)
    text = "Contact at john.doe@example.com +1234567890"
    config = schemas.PIIValidationConfig(
        entities=["EMAIL_ADDRESS", "PHONE_NUMBER"], language="en", threshold=0.9
    )

    result = pii_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "detected_entities": {
                "PHONE_NUMBER": [
                    {"start": 32, "end": 44, "score": 0.95, "text": "+1234567890"}
                ]
            }
        },
        "validation_config": {
            "entities": ["EMAIL_ADDRESS", "PHONE_NUMBER"],
            "language": "en",
            "threshold": 0.9,
        },
        "type": schemas.ValidationType.PII,
    }


def test_validate_with_default_entities__validation_failed():
    """Test validation using the default entities list."""
    mock_engine = mock.Mock()
    mock_engine.analyze.return_value = [
        presidio_analyzer.RecognizerResult(
            entity_type="PHONE_NUMBER",
            start=32,
            end=44,
            score=0.95,
        )
    ]

    pii_validator = validator.PIIValidator(engine=mock_engine)
    text = "Contact at xxxxxxxxxxxxxxxxxxxx +1234567890"
    config = schemas.PIIValidationConfig(
        language="en",
    )

    result = pii_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "detected_entities": {
                "PHONE_NUMBER": [
                    {"start": 32, "end": 44, "score": 0.95, "text": "+1234567890"}
                ]
            }
        },
        "validation_config": {
            "entities": [
                "IP_ADDRESS",
                "PHONE_NUMBER",
                "PERSON",
                "MEDICAL_LICENSE",
                "URL",
                "EMAIL_ADDRESS",
                "IBAN_CODE",
            ],
            "language": "en",
            "threshold": 0.5,
        },
        "type": schemas.ValidationType.PII,
    }


def test_validate_with_multiple_entity_types__validation_failed():
    """Test validation with multiple types of PII entities detected."""
    mock_engine = mock.Mock()
    mock_engine.analyze.return_value = [
        presidio_analyzer.RecognizerResult(
            entity_type="PERSON",
            start=0,
            end=10,
            score=0.8,
        ),
        presidio_analyzer.RecognizerResult(
            entity_type="EMAIL_ADDRESS",
            start=22,
            end=38,
            score=0.9,
        ),
        presidio_analyzer.RecognizerResult(
            entity_type="URL",
            start=54,
            end=69,
            score=0.7,
        ),
    ]

    pii_validator = validator.PIIValidator(engine=mock_engine)
    text = "John Smith's email is john@example.com and website is www.example.com"
    config = schemas.PIIValidationConfig(
        entities=["PERSON", "EMAIL_ADDRESS", "URL"], language="en", threshold=0.6
    )

    result = pii_validator.validate(text, config)

    assert result.model_dump(serialize_as_any=True) == {
        "validation_passed": False,
        "validation_details": {
            "detected_entities": {
                "PERSON": [{"start": 0, "end": 10, "score": 0.8, "text": "John Smith"}],
                "EMAIL_ADDRESS": [
                    {"start": 22, "end": 38, "score": 0.9, "text": "john@example.com"}
                ],
                "URL": [
                    {"start": 54, "end": 69, "score": 0.7, "text": "www.example.com"}
                ],
            }
        },
        "validation_config": {
            "entities": ["PERSON", "EMAIL_ADDRESS", "URL"],
            "language": "en",
            "threshold": 0.6,
        },
        "type": schemas.ValidationType.PII,
    }
