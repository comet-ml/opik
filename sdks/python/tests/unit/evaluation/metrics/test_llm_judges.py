from typing import Any, List, Optional

import pytest

from opik import exceptions
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

    def __init__(
        self,
        value: float,
        score_name: str,
        scoring_failed: bool = False,
        reason: Optional[str] = None,
    ) -> None:
        super().__init__(track=False)
        self._value = value
        self._score_name = score_name
        self._scoring_failed = scoring_failed
        self._reason = reason

    def score(self, *args: Any, **kwargs: Any) -> ScoreResult:
        return ScoreResult(
            name=self._score_name,
            value=self._value,
            scoring_failed=self._scoring_failed,
            reason=self._reason,
        )


class MultiResultJudge(PromptInjection):
    """A judge that returns several results, as list-valued metrics do."""

    def __init__(
        self,
        values: List[float],
        score_name: str,
        failed_index: Optional[int] = None,
        reason: Optional[str] = None,
    ) -> None:
        super().__init__(track=False)
        self._values = values
        self._score_name = score_name
        self._failed_index = failed_index
        self._reason = reason

    def score(self, *args: Any, **kwargs: Any) -> List[ScoreResult]:
        return [
            ScoreResult(
                name=self._score_name,
                value=value,
                scoring_failed=index == self._failed_index,
                reason=self._reason if index == self._failed_index else None,
            )
            for index, value in enumerate(self._values)
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


@pytest.mark.parametrize("include_valid_judge", [False, True])
@pytest.mark.parametrize("precompute_scores", [False, True])
def test_llm_juries_judge__failed_score__raises(
    include_valid_judge: bool, precompute_scores: bool
) -> None:
    reason = "Provider timed out while grading the answer"
    failed_judge = NamedJudge(0.0, "failed_judge", scoring_failed=True, reason=reason)
    judges = [failed_judge]
    if include_valid_judge:
        judges.insert(0, NamedJudge(1.0, "valid_judge"))

    precomputed = (
        {judge: judge.score() for judge in judges} if precompute_scores else None
    )
    jury = LLMJuriesJudge(judges=judges, track=False)

    with pytest.raises(
        exceptions.MetricComputationError, match="failed score 'failed_judge'"
    ) as error:
        jury.score(precomputed=precomputed)

    assert reason in str(error.value)


@pytest.mark.parametrize("failed_index", [0, 1])
def test_llm_juries_judge__failed_list_entry__raises(failed_index: int) -> None:
    reason = "No assistant messages to evaluate"
    judge = MultiResultJudge(
        [0.4, 0.6], "criteria_scores", failed_index=failed_index, reason=reason
    )
    jury = LLMJuriesJudge(judges=[judge], track=False)

    with pytest.raises(
        exceptions.MetricComputationError, match="failed score"
    ) as error:
        jury.score()

    assert reason in str(error.value)


@pytest.mark.parametrize("precompute_scores", [False, True])
@pytest.mark.parametrize(
    "value", [float("nan"), float("inf"), -float("inf"), -0.1, 1.1]
)
def test_llm_juries_judge__invalid_numeric_vote__raises(
    value: float, precompute_scores: bool
) -> None:
    judge = NamedJudge(value, "invalid_vote")
    jury = LLMJuriesJudge(judges=[judge], track=False)
    precomputed = {judge: judge.score()} if precompute_scores else None

    with pytest.raises(exceptions.MetricComputationError, match="out-of-range score"):
        jury.score(precomputed=precomputed)


def test_llm_juries_judge__valid_zero_score__included_in_average() -> None:
    jury = LLMJuriesJudge(
        judges=[NamedJudge(0.0, "zero"), NamedJudge(1.0, "one")], track=False
    )

    result = jury.score("dummy output")

    assert result.value == pytest.approx(0.5)
    assert not result.scoring_failed
    assert result.metadata["judge_scores"] == {"zero": 0.0, "one": 1.0}
