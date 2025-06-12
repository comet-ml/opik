import pytest

from opik.evaluation.metrics import Sentiment
from opik.exceptions import MetricComputationError


@pytest.mark.parametrize(
    "text,expected_sentiment",
    [
        ("I love this product! It's amazing.", "positive"),
        ("This is terrible, I hate it.", "negative"),
        ("The sky is blue.", "neutral"),
    ],
)
def test_sentiment_classification(text, expected_sentiment):
    metric = Sentiment()
    result = metric.score(text)

    # Check that the reason contains the expected sentiment category
    assert expected_sentiment in result.reason

    # Verify the compound score is in the correct range
    assert -1.0 <= result.value <= 1.0

    # Check that metadata contains all expected keys
    assert "pos" in result.metadata
    assert "neg" in result.metadata
    assert "neu" in result.metadata
    assert "compound" in result.metadata

    # Verify the scores are in the correct ranges
    assert 0.0 <= result.metadata["pos"] <= 1.0
    assert 0.0 <= result.metadata["neg"] <= 1.0
    assert 0.0 <= result.metadata["neu"] <= 1.0
    assert -1.0 <= result.metadata["compound"] <= 1.0


def test_sentiment_import_error(monkeypatch):
    # Mock the import to simulate missing nltk
    monkeypatch.setattr("opik.evaluation.metrics.heuristics.sentiment.nltk", None)

    with pytest.raises(ImportError) as excinfo:
        Sentiment()

    assert "nltk" in str(excinfo.value)


def test_sentiment__empty_string__error_raise():
    metric = Sentiment()
    with pytest.raises(MetricComputationError):
        metric.score("")
