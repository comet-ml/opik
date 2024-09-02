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
    ],
)
def test_feedback_score_validator(feedback_score_dict, is_valid):
    tested = feedback_score.FeedbackScoreValidator(feedback_score_dict)

    assert tested.validate().ok() is is_valid, f"Failed with {feedback_score_dict}"
