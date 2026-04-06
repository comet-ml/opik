from typing import Any, Dict, List


import pytest


from opik.evaluation import test_result
from opik.evaluation.metrics import score_result
from opik.evaluation.classification_scoring import (
    f1_scoring_function,
    precision_scoring_function,
    recall_scoring_function,
)


def _make_test_results(
    predictions: List[str], references: List[str]
) -> List[test_result.TestResult]:
    """Helper — build minimal TestResult objects."""
    from opik.evaluation import test_case as tc

    results = []
    for i, (pred, ref) in enumerate(zip(predictions, references)):
        results.append(
            test_result.TestResult(
                test_case=tc.TestCase(
                    trace_id=f"trace_{i}",
                    dataset_item_id=f"item_{i}",
                    task_output={"output": pred, "reference": ref},
                    dataset_item_content={},
                ),
                score_results=[],
                trial_id=0,
            )
        )
    return results


# ─── F1 ───────────────────────────────────────────────────────────────────────


class TestF1ScoringFunction:
    def test_f1_scoring_function__happyflow(self):
        fn = f1_scoring_function(average="macro")
        results = _make_test_results(
            predictions=["cat", "cat", "dog"],
            references=["cat", "cat", "dog"],
        )
        scores = fn(results)
        assert len(scores) == 1
        assert scores[0].value == pytest.approx(1.0)
        assert scores[0].name == "f1_score"

    def test_f1_scoring_function__all_wrong__zero(self):
        fn = f1_scoring_function(average="macro")
        results = _make_test_results(
            predictions=["dog", "dog"],
            references=["cat", "cat"],
        )
        scores = fn(results)
        assert scores[0].value == pytest.approx(0.0)

    def test_f1_scoring_function__weighted_average__range(self):
        fn = f1_scoring_function(average="weighted")
        results = _make_test_results(
            predictions=["cat", "dog", "cat"],
            references=["cat", "cat", "cat"],
        )
        scores = fn(results)
        assert 0.0 <= scores[0].value <= 1.0

    def test_f1_scoring_function__micro_average__range(self):
        fn = f1_scoring_function(average="micro")
        results = _make_test_results(
            predictions=["cat", "cat", "dog"],
            references=["cat", "cat", "cat"],
        )
        scores = fn(results)
        assert 0.0 <= scores[0].value <= 1.0

    def test_f1_scoring_function__empty_results__zero(self):
        fn = f1_scoring_function()
        scores = fn([])
        assert scores[0].value == pytest.approx(0.0)

    def test_f1_scoring_function__custom_name__in_result(self):
        fn = f1_scoring_function(name="my_f1")
        results = _make_test_results(["cat"], ["cat"])
        scores = fn(results)
        assert scores[0].name == "my_f1"

    def test_f1_scoring_function__reason__contains_average_and_count(self):
        fn = f1_scoring_function(average="macro")
        results = _make_test_results(["cat", "dog"], ["cat", "dog"])
        scores = fn(results)
        assert "macro" in scores[0].reason
        assert "2" in scores[0].reason


# ─── Precision ────────────────────────────────────────────────────────────────


class TestPrecisionScoringFunction:
    def test_precision_scoring_function__happyflow(self):
        fn = precision_scoring_function(average="macro")
        results = _make_test_results(["cat", "dog"], ["cat", "dog"])
        scores = fn(results)
        assert scores[0].value == pytest.approx(1.0)
        assert scores[0].name == "precision_score"

    def test_precision_scoring_function__partial__range(self):
        fn = precision_scoring_function(average="macro")
        results = _make_test_results(
            predictions=["cat", "dog", "cat"],
            references=["cat", "cat", "cat"],
        )
        scores = fn(results)
        assert 0.0 <= scores[0].value <= 1.0

    def test_precision_scoring_function__empty_results__zero(self):
        fn = precision_scoring_function()
        scores = fn([])
        assert scores[0].value == pytest.approx(0.0)


# ─── Recall ───────────────────────────────────────────────────────────────────


class TestRecallScoringFunction:
    def test_recall_scoring_function__happyflow(self):
        fn = recall_scoring_function(average="macro")
        results = _make_test_results(["cat", "dog"], ["cat", "dog"])
        scores = fn(results)
        assert scores[0].value == pytest.approx(1.0)
        assert scores[0].name == "recall_score"

    def test_recall_scoring_function__partial__range(self):
        fn = recall_scoring_function(average="weighted")
        results = _make_test_results(
            predictions=["cat", "dog", "cat"],
            references=["cat", "cat", "cat"],
        )
        scores = fn(results)
        assert 0.0 <= scores[0].value <= 1.0

    def test_recall_scoring_function__empty_results__zero(self):
        fn = recall_scoring_function()
        scores = fn([])
        assert scores[0].value == pytest.approx(0.0)
