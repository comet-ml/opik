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


class RecordingJudge(StubJudge):
    """Keeps every string the metric handed to the judge, so a test can assert on it."""

    def __init__(self) -> None:
        super().__init__()
        self.received: List[str] = []

    def score(self, output: str, **_: Any) -> ScoreResult:
        self.received.append(output)
        return super().score(output)


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


def test_geval_conversation_metric_grades_answer_before_empty_final_turn():
    """A closing turn with no text must not hide the answer before it.

    ``create_conversation_from_traces`` only skips an assistant message when the output
    transform returns ``None``, so a turn whose text is ``""`` (an agent call that issued
    tool calls only, or an empty completion) reaches the metric. ``score()`` documents
    that only assistant turns with non-empty content are considered, so the judge must
    get the summary rather than nothing.
    """
    judge = RecordingJudge()
    metric = GEvalConversationMetric(judge=judge, name="conversation_stub")
    conversation = _conversation(
        ["Summarise these notes.", "Summary: timelines and budgets.", "Thanks", ""]
    )

    result = metric.score(conversation)

    assert judge.received == ["Summary: timelines and budgets."]
    assert result.scoring_failed is False
    assert result.value == 0.8
    assert result.reason == "ok"


def test_geval_conversation_metric_takes_latest_text_turn_not_the_first_one():
    """Skipping must stay backwards: of two gradeable answers, the later one is graded."""
    judge = RecordingJudge()
    metric = GEvalConversationMetric(judge=judge, name="conversation_stub")
    conversation = _conversation(
        ["q1", "FIRST answer", "q2", "SECOND answer", "q3", ""]
    )

    result = metric.score(conversation)

    assert judge.received == ["SECOND answer"]
    assert result.scoring_failed is False


def test_geval_conversation_metric_treats_whitespace_as_no_text():
    """A blank-but-present closing turn carries no text, so the earlier answer is graded."""
    judge = RecordingJudge()
    metric = GEvalConversationMetric(judge=judge, name="conversation_stub")
    conversation = _conversation(["q", "real answer", "thanks", "  \n "])

    result = metric.score(conversation)

    assert judge.received == ["real answer"]
    assert result.scoring_failed is False


def test_geval_conversation_metric_all_assistant_turns_without_text_marks_failed():
    """When nothing carries text, the score still fails with the real reason."""
    judge = RecordingJudge()
    metric = GEvalConversationMetric(judge=judge, name="conversation_stub")
    conversation = _conversation(["Hello", "   "])

    result = metric.score(conversation)

    assert judge.received == []
    assert result.scoring_failed is True
    assert result.value == 0.0
    assert result.reason == "Conversation contains no assistant messages to evaluate."


def test_geval_conversation_metric_non_string_content_marks_failed_without_raising():
    """A hand-built dict whose content is not text is not 'text to evaluate' either.

    The TypedDict asks for ``str``, and the conversation built from traces enforces it,
    but ``score()`` takes plain dicts: it must report a failed score rather than raise.
    """
    metric = GEvalConversationMetric(judge=RecordingJudge(), name="conversation_stub")
    conversation = [
        {"role": "assistant", "content": 0},
        {"role": "user", "content": "q"},
        {"role": "assistant", "content": "   "},
    ]

    result = metric.score(conversation)

    assert result.scoring_failed is True
    assert result.value == 0.0
    assert result.reason == "Conversation contains no assistant messages to evaluate."
