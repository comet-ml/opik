from typing import Any

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
