from unittest.mock import MagicMock

from opik.evaluation.engine.metrics_evaluator import build_metrics_evaluator
from opik.evaluation.metrics import base_metric
from opik.evaluation.suite_evaluators import llm_judge


def _make_judge(assertions, **kwargs):
    defaults = {"track": False, "name": "llm_judge"}
    defaults.update(kwargs)
    return llm_judge.LLMJudge(assertions=assertions, **defaults)


def _make_non_judge_metric(name="other_metric"):
    metric = MagicMock(spec=base_metric.BaseMetric)
    metric.name = name
    return metric


def _build(regular_metrics):
    """Build a MetricsEvaluator with no item and extract its regular metrics."""
    evaluator = build_metrics_evaluator(
        item=None,
        regular_metrics=regular_metrics,
        scoring_key_mapping={},
        evaluator_model=None,
    )
    return evaluator.regular_metrics


class TestLLMJudgeMerged:
    def test_two_judges__combines_assertions(self):
        j1 = _make_judge(["A", "B"])
        j2 = _make_judge(["C"])

        merged = llm_judge.LLMJudge.merged([j1, j2])

        assert merged is not None
        assert merged.assertions == ["A", "B", "C"]

    def test_duplicate_assertions__deduplicated(self):
        j1 = _make_judge(["A", "B"])
        j2 = _make_judge(["B", "C"])

        merged = llm_judge.LLMJudge.merged([j1, j2])

        assert merged is not None
        assert merged.assertions == ["A", "B", "C"]

    def test_mismatched_settings__returns_none(self):
        j1 = _make_judge(["A"], temperature=0.5, seed=42)
        j2 = _make_judge(["B"], temperature=0.9, seed=99)

        assert llm_judge.LLMJudge.merged([j1, j2]) is None

    def test_mismatched_model__returns_none(self):
        j1 = _make_judge(["A"], model="gpt-4o")
        j2 = _make_judge(["B"], model="gpt-4o-mini")

        assert llm_judge.LLMJudge.merged([j1, j2]) is None

    def test_empty_list__returns_none(self):
        assert llm_judge.LLMJudge.merged([]) is None

    def test_matching_settings__merges(self):
        j1 = _make_judge(["A"], temperature=0.5, seed=42, name="suite_judge")
        j2 = _make_judge(["B"], temperature=0.5, seed=42, name="item_judge")

        merged = llm_judge.LLMJudge.merged([j1, j2])

        assert merged is not None
        assert merged.name == "suite_judge"
        assert merged._temperature == 0.5
        assert merged._seed == 42
        assert merged.assertions == ["A", "B"]

    def test_three_judges__all_merged(self):
        j1 = _make_judge(["A"])
        j2 = _make_judge(["B"])
        j3 = _make_judge(["C"])

        merged = llm_judge.LLMJudge.merged([j1, j2, j3])

        assert merged is not None
        assert merged.assertions == ["A", "B", "C"]

    def test_single_judge__returns_none(self):
        j1 = _make_judge(["A", "B"])

        assert llm_judge.LLMJudge.merged([j1]) is None


class TestBuildMetricsEvaluatorMerging:
    def test_no_judges__returns_unchanged(self):
        m1 = _make_non_judge_metric("m1")
        m2 = _make_non_judge_metric("m2")

        result = _build([m1, m2])

        assert result == [m1, m2]

    def test_single_judge__not_merged(self):
        judge = _make_judge(["assertion A"])

        result = _build([judge])

        assert len(result) == 1
        assert result[0] is judge

    def test_two_judges__merges_into_one(self):
        j1 = _make_judge(["A", "B"])
        j2 = _make_judge(["C"])

        result = _build([j1, j2])

        assert len(result) == 1
        assert isinstance(result[0], llm_judge.LLMJudge)
        assert result[0].assertions == ["A", "B", "C"]

    def test_mixed_metrics__merged_judge_first(self):
        m1 = _make_non_judge_metric("m1")
        j1 = _make_judge(["A"])
        m2 = _make_non_judge_metric("m2")
        j2 = _make_judge(["B"])

        result = _build([m1, j1, m2, j2])

        assert len(result) == 3
        assert isinstance(result[0], llm_judge.LLMJudge)
        assert result[0].assertions == ["A", "B"]
        assert result[1] is m1
        assert result[2] is m2

    def test_mismatched_settings__skips_merge(self):
        j1 = _make_judge(["A"], temperature=0.5)
        j2 = _make_judge(["B"], temperature=0.9)

        result = _build([j1, j2])

        assert len(result) == 2
        assert result[0] is j1
        assert result[1] is j2

    def test_empty_list__returns_empty(self):
        result = _build([])

        assert result == []
