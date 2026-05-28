"""Verify LLMJudge.score routes to the agentic path iff a context is passed."""

import datetime
from unittest import mock

from opik.evaluation.metrics import score_result
from opik.evaluation.suite_evaluators import llm_judge
from opik.message_processing.emulation import (
    local_emulator_message_processor,
    models,
)

from opik.evaluation.suite_evaluators.agentic.context import TraceToolContext


def _make_judge():
    judge = llm_judge.LLMJudge(assertions=["x"], track=False)
    # Avoid real model construction; tests patch the relevant method.
    return judge


def _ctx():
    trace = models.TraceModel(
        id="t-1",
        start_time=datetime.datetime(2026, 5, 13),
        name="t",
        project_name="default",
        source="sdk",
    )
    emulator = local_emulator_message_processor.LocalEmulatorMessageProcessor(
        active=True
    )
    return TraceToolContext(
        trace=trace, spans=[], parent_by_child={}, emulator=emulator
    )


def test_score__without_context__uses_one_shot_path():
    judge = _make_judge()
    with (
        mock.patch.object(
            judge,
            "_generate_and_parse",
            return_value=[score_result.ScoreResult(name="x", value=True, reason="ok")],
        ) as one_shot,
        mock.patch.object(judge, "_score_agentic") as agentic,
    ):
        results = judge.score(input="i", output="o")
        assert one_shot.called
        assert not agentic.called
        assert results[0].name == "x"


def test_score__with_context__routes_to_agentic_path():
    judge = llm_judge.LLMJudge(
        assertions=["x"], track=False, scoring_tool_strategy="always"
    )
    ctx = _ctx()
    expected = [score_result.ScoreResult(name="x", value=False, reason="r")]
    with (
        mock.patch.object(judge, "_score_agentic", return_value=expected) as agentic,
        mock.patch.object(judge, "_generate_and_parse") as one_shot,
    ):
        results = judge.score(input="i", output="o", trace_tool_context=ctx)
        assert agentic.called
        assert not one_shot.called
        assert results == expected
