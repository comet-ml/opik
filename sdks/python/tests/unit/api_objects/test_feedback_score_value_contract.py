"""A feedback score whose `value` is a bool must be rejected, not logged.

`bool` is a subclass of `int`, so the pydantic validator behind
`validate_feedback_score` accepts it, and that helper returns the caller's dict
unchanged -- the value that reaches the wire is a JSON `true`/`false` where
`opik.types.FeedbackScoreDict` declares `value: Required[float]`. The other two
readers of this field, `experiment.bulk_converters._validate_feedback_score` and
`experiment.experiment_item._require_leaf_type`, both exclude `bool` explicitly,
so a score logged through the write path could be refused when it is read back.
"""

import logging

import pytest

from opik.api_objects import validation_helpers
from opik import logging_messages


@pytest.mark.parametrize("value", [True, False])
def test_validate_feedback_score__bool_value__is_rejected(capture_log, value):
    # Rejecting silently is the other half of the same failure: the caller only
    # finds out from the missing score, so assert the warning names the score and
    # says why. `capture_log` turns on propagation for the `opik` logger, which is
    # the logger a caller passes in.
    logger = logging.getLogger("opik.test_feedback_score_value_contract")
    score = {"id": "some-id", "name": "accuracy", "value": value}

    with capture_log.at_level(logging.WARNING, logger=logger.name):
        assert validation_helpers.validate_feedback_score(score, logger) is None

    assert [
        record.getMessage()
        for record in capture_log.records
        if record.levelno == logging.WARNING
    ] == [
        logging_messages.INVALID_FEEDBACK_SCORE_WILL_NOT_BE_LOGGED
        % (score, "a feedback score value must be a number, not a bool")
    ]


@pytest.mark.parametrize("value", [0, 1, 0.0, 1.0, -1, 0.5])
def test_validate_feedback_score__numeric_value__is_kept(value):
    # 0 and 1 are falsy and must keep working: only the bool subclass is rejected.
    logger = logging.getLogger(__name__)

    kept = validation_helpers.validate_feedback_score(
        {"id": "some-id", "name": "accuracy", "value": value}, logger
    )

    assert kept is not None
    assert kept["value"] == value
    assert not isinstance(kept["value"], bool)


def test_validate_feedback_score__absent_value__still_validates_as_before():
    # The bool check must not change how a score with no value at all is handled,
    # which is left to the pydantic validator.
    logger = logging.getLogger(__name__)

    assert (
        validation_helpers.validate_feedback_score(
            {"id": "some-id", "name": "accuracy"}, logger
        )
        is None
    )


@pytest.mark.parametrize(
    "value,expected",
    [("0.5", "0.5"), ("  1  ", "  1  ")],
)
def test_validate_feedback_score__numeric_string__behaviour_unchanged(value, expected):
    # Pinned on purpose: `tests/unit/validation/test_feedback_score_validator.py`
    # asserts the validator accepts a numeric string, so a numeric string is still
    # logged. Recorded here so the change above is visibly scoped to bool.
    logger = logging.getLogger(__name__)

    kept = validation_helpers.validate_feedback_score(
        {"id": "some-id", "name": "toxicity", "value": value}, logger
    )

    assert kept is not None
    assert kept["value"] == expected
