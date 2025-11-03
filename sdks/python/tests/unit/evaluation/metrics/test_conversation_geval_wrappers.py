from typing import Any, List

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics.conversation.llm_judges.g_eval_wrappers import (
    GEvalConversationMetric,
)


class StubJudge(BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="stub_judge", track=False)

    def score(self, output: str, **_: Any) -> ScoreResult:
        return ScoreResult(name=self.name, value=0.8, reason="ok")


class ErrorJudge(BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="error_judge", track=False)

    def score(self, output: str, **_: Any) -> ScoreResult:
        raise ValueError("fail")


def _conversation(messages: List[str]) -> List[dict]:
    turns = []
    for idx, content in enumerate(messages):
        role = "assistant" if idx % 2 else "user"
        turns.append({"role": role, "content": content})
    return turns


def test_geval_conversation_metric_success():
    metric = GEvalConversationMetric(judge=StubJudge(), name="conversation_stub")
    conversation = _conversation(
        ["Hello", "Hi there", "Tell me a joke", "Why did the chicken cross the road?"]
    )

    result = metric.score(conversation)

    assert result.name == "conversation_stub"
    assert result.value == 0.8
    assert result.reason == "ok"


def test_geval_conversation_metric_no_assistant_message_marks_failed():
    metric = GEvalConversationMetric(judge=StubJudge(), name="conversation_stub")
    conversation = [{"role": "user", "content": "Only user text"}]

    result = metric.score(conversation)

    assert result.scoring_failed is True
    assert result.value == 0.0


def test_geval_conversation_metric_exception_marks_failed():
    metric = GEvalConversationMetric(judge=ErrorJudge(), name="conversation_error")
    conversation = _conversation(["User", "Assistant reply"])

    result = metric.score(conversation)

    assert result.scoring_failed is True
    assert result.name == "conversation_error"
