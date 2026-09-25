from typing import Any, List

import pytest

from opik.evaluation.metrics.llm_judges.llm_juries.metric import (
    LLMJuriesJudge,
)
from opik.evaluation.metrics.heuristics.prompt_injection import PromptInjection
from opik.evaluation.metrics.score_result import ScoreResult


class StubJudge(ScoreResult):
    pass


def test_llm_juries_judge_average_scores():
    class ConstantJudge(PromptInjection):
        def __init__(self, value: float):
            super().__init__(track=False)
            self._value = value

        def score(self, *args: Any, **kwargs: Any) -> ScoreResult:
            return ScoreResult(name="constant", value=self._value)

    llm_juries = LLMJuriesJudge(
        judges=[ConstantJudge(0.2), ConstantJudge(0.8)],
        track=False,
    )
    result = llm_juries.score("dummy output")
    assert result.value == pytest.approx(0.5)


class NamedJudge(PromptInjection):
    """A model-free judge that emits one score under a chosen score name."""

    def __init__(self, value: float, score_name: str) -> None:
        super().__init__(track=False)
        self._value = value
        self._score_name = score_name

    def score(self, *args: Any, **kwargs: Any) -> ScoreResult:
        return ScoreResult(name=self._score_name, value=self._value)


class MultiResultJudge(PromptInjection):
    """A judge that returns several results, as list-valued metrics do."""

    def __init__(self, values: List[float], score_name: str) -> None:
        super().__init__(track=False)
        self._values = values
        self._score_name = score_name

    def score(self, *args: Any, **kwargs: Any) -> List[ScoreResult]:
        return [
            ScoreResult(name=self._score_name, value=value) for value in self._values
        ]


def test_votes_are_attributed_when_judges_share_a_score_name():
    jury = LLMJuriesJudge(
        judges=[
            NamedJudge(0.2, "g_eval_metric"),
            NamedJudge(0.8, "g_eval_metric"),
        ],
        track=False,
    )

    result = jury.score("dummy output")

    assert result.value == pytest.approx(0.5)
    assert result.metadata["judge_scores"] == {
        "g_eval_metric": 0.2,
        "g_eval_metric#2": 0.8,
    }
    # The vote count `reason` reports and the votes on record must agree.
    assert int(result.reason.split()[1]) == len(result.metadata["judge_scores"])


def test_numbered_score_name_does_not_evict_another_vote():
    jury = LLMJuriesJudge(
        judges=[
            NamedJudge(0.2, "g_eval_metric"),
            NamedJudge(0.4, "g_eval_metric#2"),
            NamedJudge(0.6, "g_eval_metric"),
        ],
        track=False,
    )

    result = jury.score("dummy output")

    assert result.value == pytest.approx(0.4)
    assert result.metadata["judge_scores"] == {
        "g_eval_metric": 0.2,
        "g_eval_metric#2": 0.4,
        "g_eval_metric#3": 0.6,
    }


def test_judge_returning_several_results_keeps_every_vote():
    jury = LLMJuriesJudge(
        judges=[
            MultiResultJudge([0.4, 0.6], "criteria_scores"),
            NamedJudge(1.0, "safety"),
        ],
        track=False,
    )

    result = jury.score("dummy output")

    assert result.value == pytest.approx((0.4 + 0.6 + 1.0) / 3)
    assert result.metadata["judge_scores"] == {
        "criteria_scores": 0.4,
        "criteria_scores#2": 0.6,
        "safety": 1.0,
    }


def test_distinct_score_names_keep_their_keys():
    jury = LLMJuriesJudge(
        judges=[NamedJudge(0.2, "usefulness"), NamedJudge(0.8, "safety")],
        track=False,
    )

    result = jury.score("dummy output")

    assert result.metadata["judge_scores"] == {"usefulness": 0.2, "safety": 0.8}
