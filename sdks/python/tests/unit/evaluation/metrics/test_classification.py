import pytest
from opik.evaluation.metrics.heuristics.classification import (
    F1Score,
    PrecisionScore,
    RecallScore,
)


class TestF1Score:
    def test_perfect_predictions(self):
        metric = F1Score(average="macro")
        result = metric.score(
            predictions=["cat", "cat", "dog"],
            references=["cat", "cat", "dog"],
        )
        assert result.value == pytest.approx(1.0)
        assert result.name == "f1_score_metric"

    def test_all_wrong_predictions(self):
        metric = F1Score(average="macro")
        result = metric.score(
            predictions=["dog", "dog"],
            references=["cat", "cat"],
        )
        assert result.value == pytest.approx(0.0)

    def test_weighted_average(self):
        metric = F1Score(average="weighted")
        result = metric.score(
            predictions=["cat", "dog", "cat"],
            references=["cat", "cat", "cat"],
        )
        assert 0.0 <= result.value <= 1.0
        assert result.name == "f1_score_metric"

    def test_micro_average(self):
        metric = F1Score(average="micro")
        result = metric.score(
            predictions=["cat", "cat", "dog"],
            references=["cat", "cat", "cat"],
        )
        assert 0.0 <= result.value <= 1.0

    def test_mismatched_lengths_raises_error(self):
        metric = F1Score()
        with pytest.raises(ValueError, match="same length"):
            metric.score(
                predictions=["cat", "dog"],
                references=["cat"],
            )

    def test_reason_in_result(self):
        metric = F1Score(average="macro")
        result = metric.score(
            predictions=["cat", "dog"],
            references=["cat", "dog"],
        )
        assert "macro" in result.reason
        assert "2" in result.reason


class TestPrecisionScore:
    def test_perfect_predictions(self):
        metric = PrecisionScore(average="macro")
        result = metric.score(
            predictions=["cat", "dog"],
            references=["cat", "dog"],
        )
        assert result.value == pytest.approx(1.0)
        assert result.name == "precision_score_metric"

    def test_partial_predictions(self):
        metric = PrecisionScore(average="macro")
        result = metric.score(
            predictions=["cat", "dog", "cat"],
            references=["cat", "cat", "cat"],
        )
        assert 0.0 <= result.value <= 1.0

    def test_mismatched_lengths_raises_error(self):
        metric = PrecisionScore()
        with pytest.raises(ValueError, match="same length"):
            metric.score(
                predictions=["cat", "dog"],
                references=["cat"],
            )


class TestRecallScore:
    def test_perfect_predictions(self):
        metric = RecallScore(average="macro")
        result = metric.score(
            predictions=["cat", "dog"],
            references=["cat", "dog"],
        )
        assert result.value == pytest.approx(1.0)
        assert result.name == "recall_score_metric"

    def test_partial_predictions(self):
        metric = RecallScore(average="weighted")
        result = metric.score(
            predictions=["cat", "dog", "cat"],
            references=["cat", "cat", "cat"],
        )
        assert 0.0 <= result.value <= 1.0

    def test_mismatched_lengths_raises_error(self):
        metric = RecallScore()
        with pytest.raises(ValueError, match="same length"):
            metric.score(
                predictions=["cat"],
                references=["cat", "dog"],
            )
