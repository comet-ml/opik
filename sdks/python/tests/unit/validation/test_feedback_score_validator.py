import pytest
from opik.validation import feedback_score


@pytest.mark.parametrize(
    argnames="feedback_score_dict, is_valid",
    argvalues=[
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
                "reason": "good reason",
                "category_name": "sentiment",
            },
            True,
        ),
        (
            {
                "id": 123213232,
                "name": "toxicity",
                "value": 0.5,
                "reason": "good reason",
                "category_name": "sentiment",
            },
            False,
        ),
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
            },
            True,
        ),
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
                "unknown-key": "any-value",
            },
            False,
        ),
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": "0.5",
            },
            True,
        ),
        ("not-even-a-dict", False),
        # Test cases for project_name type coercion prevention (StrictStr)
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
                "project_name": "valid-project",
            },
            True,
        ),
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
                "project_name": 123,  # Integer should be rejected for project_name
            },
            False,
        ),
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
                "project_name": [
                    "project-list"
                ],  # List should be rejected for project_name
            },
            False,
        ),
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
                "project_name": {
                    "name": "project"
                },  # Dict should be rejected for project_name
            },
            False,
        ),
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
                "project_name": True,  # Boolean should be rejected for project_name
            },
            False,
        ),
        (
            {
                "id": "some-id",
                "name": "toxicity",
                "value": 0.5,
                "project_name": 3.14,  # Float should be rejected for project_name
            },
            False,
        ),
    ],
)
def test_feedback_score_validator(feedback_score_dict, is_valid):
    tested = feedback_score.FeedbackScoreValidator(feedback_score_dict)

    assert tested.validate().ok() is is_valid, f"Failed with {feedback_score_dict}"


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf"), "nan", "inf"])
def test_a_non_finite_score_is_rejected(value):
    score = {"id": "some-id", "name": "accuracy", "value": value}

    validation_result = feedback_score.FeedbackScoreValidator(score).validate()

    assert validation_result.failed
    assert "NaN and infinity are not valid" in validation_result.failure_reasons[0]


@pytest.mark.parametrize("value", [0, 1, -1, 0.5, "0.5"])
def test_a_finite_score_is_accepted(value):
    score = {"id": "some-id", "name": "accuracy", "value": value}

    assert not feedback_score.FeedbackScoreValidator(score).validate().failed
