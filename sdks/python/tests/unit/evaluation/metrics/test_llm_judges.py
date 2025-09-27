from typing import Any

import pytest

from opik.evaluation.metrics.llm_judges.reviseval.metric import RevisEvalJudge
from opik.evaluation.metrics.llm_judges.mmad.metric import MMADJudge
from opik.evaluation.metrics.heuristics.prompt_injection import PromptInjectionGuard
from opik.evaluation.metrics.score_result import ScoreResult


class StubModel:
    def generate_string(self, input: str, response_format: Any) -> str:
        return '{"score": 0.75, "reason": "Grounded in context."}'

    async def agenerate_string(self, input: str, response_format: Any) -> str:
        return '{"score": 0.5, "reason": "Async"}'


class StubJudge(ScoreResult):
    pass


def test_reviseval_judge_with_stub_model():
    judge = RevisEvalJudge(model=StubModel(), track=False)
    result = judge.score(question="What is X?", answer="X is Y", context=["Y is correct."])

    assert isinstance(result, ScoreResult)
    assert result.value == pytest.approx(0.75)


def test_mmad_judge_average_scores():
    class ConstantJudge(PromptInjectionGuard):
        def __init__(self, value: float):
            super().__init__(track=False)
            self._value = value

        def score(self, *args: Any, **kwargs: Any) -> ScoreResult:
            return ScoreResult(name="constant", value=self._value)

    mmad = MMADJudge(judges=[ConstantJudge(0.2), ConstantJudge(0.8)], track=False)
    result = mmad.score("dummy output")
    assert result.value == pytest.approx(0.5)
*** End Patch
