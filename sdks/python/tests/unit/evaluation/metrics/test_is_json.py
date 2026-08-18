import pytest

from opik.evaluation.metrics import IsJson
from opik.evaluation.metrics.score_result import ScoreResult


@pytest.mark.parametrize(
    "output",
    [
        '{"key": "value"}',
        "[1, 2, 3]",
        "5",
        '"a json string"',
        "true",
        "null",
    ],
)
def test_is_json__valid_json__returns_score_1(output):
    metric = IsJson(track=False)

    assert metric.score(output=output) == ScoreResult(
        name=metric.name, value=1.0, reason=None, metadata=None
    )


@pytest.mark.parametrize(
    "output",
    [
        "Not a JSON string",
        "",
        '{"key": "value"',
        None,
        123,
    ],
)
def test_is_json__invalid_json__returns_score_0(output):
    metric = IsJson(track=False)

    assert metric.score(output=output) == ScoreResult(
        name=metric.name, value=0.0, reason=None, metadata=None
    )
