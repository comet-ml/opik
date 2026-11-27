"""Integration tests for the agentic LLM judge against a real model.

These tests sit between the unit suite (which mocks the LLM and stubs
`run_agentic_judge`) and the e2e suite (which goes through
`opik.run_tests` and builds traces via decorators). Here the
`TraceToolContext` is constructed inline with `_seeding.build_context`,
the judge is the only network call, and the verdict is checked field
by field.

Each test pins the scoring strategy to `"always"` so the agentic path
runs even if the heuristic selector ever changes how it interprets
"context present." That keeps these tests focused on agentic-judge
behavior; one-shot LLMJudge integration is covered by
`tests/library_integration/metrics_with_llm_judge/test_llm_judge.py`.

Assertions are written deliberately on the unambiguous side — a
competent judge model (`gpt-4o-mini`) should produce a stable verdict
across runs. If a test starts flaking, prefer tightening the trace
content over loosening the assertion, so we don't paper over a real
regression in the judge.
"""

from typing import Iterable, List, Tuple

import pytest

from opik.evaluation.metrics import score_result
from opik.evaluation.models import models_factory
from opik.evaluation.suite_evaluators import LLMJudge
from opik.evaluation.suite_evaluators.agentic import judge as agentic_judge
from opik.evaluation.suite_evaluators.agentic.compression import span_tree_serializer

from . import _seeding
from ._recording_registry import RecordingToolRegistry


def _by_name(
    results: List[score_result.ScoreResult], name: str
) -> score_result.ScoreResult:
    """Index a list of per-assertion results by assertion text.

    The judge returns results in the same order as the input assertions
    today, but pinning by name keeps tests robust to a future reorder.
    """
    matches = [r for r in results if r.name == name]
    assert len(matches) == 1, (
        f"expected exactly one result for assertion {name!r}, "
        f"got {[r.name for r in results]}"
    )
    return matches[0]


def _make_judge(assertion_or_assertions, model_name: str) -> LLMJudge:
    """Construct an LLMJudge wired for the always-agentic path.

    `track=False` keeps the test off the Opik backend; `seed=42`
    pins the model's RNG where supported (gpt-4o-mini accepts it via
    litellm); `temperature=0.0` removes the rest of the variance.
    """
    if isinstance(assertion_or_assertions, str):
        assertions = [assertion_or_assertions]
    else:
        assertions = list(assertion_or_assertions)
    return LLMJudge(
        assertions=assertions,
        model=model_name,
        track=False,
        scoring_tool_strategy="always",
        seed=42,
        temperature=0.0,
    )


def _make_recording_agentic_judge(
    assertions: Iterable[str], model_name: str
) -> Tuple[agentic_judge.AgenticLLMJudge, RecordingToolRegistry]:
    """Build an `AgenticLLMJudge` whose tool registry records every call.

    Bypasses `LLMJudge.score` because that path constructs the
    `AgenticLLMJudge` internally with the default registry; injecting
    a recorder requires going one level lower. The model is fetched
    via the same `models_factory.get` that `LLMJudge._init_model`
    uses, so the model wiring matches the rest of the suite.
    """
    base_registry = agentic_judge.default_tool_registry()
    recorder = RecordingToolRegistry(base_registry)
    model = models_factory.get(model_name, track=False, seed=42, temperature=0.0)
    judge = agentic_judge.AgenticLLMJudge(
        assertions=list(assertions),
        model=model,
        registry=recorder,
    )
    return judge, recorder


# Each parametrized run below is a paid multi-turn Sonnet call. Only the
# tool-loop tests stay active: they are the behavior that makes the judge
# agentic and the only ones the mocked unit suite cannot stand in for.
_SKIP_FOR_COST = pytest.mark.skip(
    reason="Cost: verdict correctness is covered by the mocked unit suite and "
    "the one-shot test_llm_judge.py; only the tool-loop tests run live."
)


@_SKIP_FOR_COST
class TestAgenticJudgeSpanStructure:
    """An assertion decidable only from the span tree. A one-shot judge
    seeing just trace input/output would have no signal to answer — a
    pass here is direct evidence the agentic overview was consulted.
    """

    def test_assertion_about_span_name__passes(self, judge_model_name):
        trace = _seeding.make_trace(
            input={"question": "Look up user u-1"},
            output={"status": "ok"},
        )
        # The output deliberately doesn't mention `fetch_user_data` —
        # the helper's name only surfaces in the span tree.
        spans = [
            _seeding.make_span(span_id="root", name="task"),
            _seeding.make_span(
                span_id="fetch",
                name="fetch_user_data",
                input={"user_id": "u-1"},
                output={"id": "u-1", "name": "alice"},
                start_offset_ms=1,
            ),
        ]
        ctx = _seeding.build_context(
            trace, spans=spans, parent_by_child={"root": None, "fetch": "root"}
        )

        assertion = "The agent called a step named `fetch_user_data`."
        results = _make_judge(assertion, judge_model_name).score(
            input=trace.input,
            output=trace.output,
            trace_tool_context=ctx,
        )

        result = _by_name(results, assertion)
        assert result.scoring_failed is False
        assert result.value is True


@_SKIP_FOR_COST
class TestAgenticJudgeMultipleAssertions:
    """The judge returns one ScoreResult per assertion. Mixing a clearly-
    true and a clearly-false assertion in a single call verifies the
    per-assertion parse and the result-name plumbing both work, and
    doubles as the only true/false verdict check in this suite — every
    extra run here is a paid multi-turn judge call.
    """

    def test_mixed_true_and_false_assertions__separate_verdicts(self, judge_model_name):
        trace = _seeding.make_trace(
            input={"question": "What is 2 + 2?"},
            output={"answer": "2 + 2 equals 4."},
        )
        ctx = _seeding.build_context(
            trace, spans=[_seeding.make_span(span_id="s-1", name="answer_step")]
        )

        true_assertion = "The output states that 2 + 2 equals 4."
        false_assertion = "The output states that 2 + 2 equals 5."

        results = _make_judge(
            [true_assertion, false_assertion], judge_model_name
        ).score(
            input=trace.input,
            output=trace.output,
            trace_tool_context=ctx,
        )
        assert len(results) == 2
        assert _by_name(results, true_assertion).value is True
        assert _by_name(results, false_assertion).value is False


def _pin_ladder_to_floor(monkeypatch) -> None:
    """Force every overview render to use the floor-tier per-field
     limit, so long fields are guaranteed to be truncated regardless of
    the judge model's actual context budget.

    Without this, `gpt-4o-mini`'s 128k window picks the no-truncation
    tier for the trace sizes used in these tests, and the judge can
    answer from the overview alone — which defeats the point of
    checking tool engagement.
    """
    monkeypatch.setattr(
        span_tree_serializer,
        "OVERVIEW_IO_LIMIT_LADDER",
        (span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT,),
    )


class TestAgenticJudgeToolUse:
    """Assertions whose only evidence sits past the overview's
    truncation horizon. The judge must call `read` (or `scan`/`search`)
    to recover the value, so a passing verdict combined with a
    non-empty recorder is direct proof the tool loop engaged.

    Pattern:
    1. Pin `OVERVIEW_IO_LIMIT_LADDER` to the 500-char floor.
    2. Plant a unique marker past 500 chars in a span field. Trace-
       level input/output are re-inlined verbatim into the user prompt
       (the `---BEGIN TASK INPUT/OUTPUT---` blocks), so a marker in
       `trace.input` would be visible without any tool call — span
       fields are the only place truncation actually hides content.
    3. Ask the judge whether that marker, and a marker that isn't
       there, appear in that field.
    4. Assert the verdicts are True and False respectively AND the
       recorder captured at least one drill-in call.
    """

    def test_buried_and_absent_markers_in_span_input__verdicts_after_lookup(
        self, judge_model_name, monkeypatch
    ):
        """One judge call with a positive and a negative assertion about the
        same truncated span input.

        The positive marker sits past the 500-char floor, so a True verdict
        means the judge retrieved the full input. The negative marker is
        not in the input at all, so a False verdict alongside it shows the
        judge didn't treat the `[TRUNCATED ...]` suffix as "content is
        absent" or answer both from the overview. The recorder proves a
        drill-in call happened.
        """
        _pin_ladder_to_floor(monkeypatch)

        # ~650 chars of pad before the marker guarantees it sits past the
        # 500-char floor and therefore past the truncation suffix, which
        # names `read(type='span', id=...)` as the recovery call.
        marker = "MARKER-XYZ-987"
        absent_marker = "EXPECTED-MISSING-MARKER-001"
        long_span_input = ("padding-text " * 50) + marker
        assert len(long_span_input) > span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT
        assert absent_marker not in long_span_input
        trace = _seeding.make_trace(
            input={"question": "answer it"},
            output={"answer": "ok"},
        )
        spans = [
            _seeding.make_span(
                span_id="s-1",
                name="answer_step",
                input={"prompt": long_span_input},
                output={"answer": "ok"},
            ),
        ]
        ctx = _seeding.build_context(trace, spans=spans)

        present_assertion = (
            f"The `answer_step` span's input contains the literal token `{marker}`."
        )
        absent_assertion = (
            f"The `answer_step` span's input contains the literal "
            f"token `{absent_marker}`."
        )
        judge, recorder = _make_recording_agentic_judge(
            [present_assertion, absent_assertion], judge_model_name
        )

        results = judge.score(ctx)

        present_result = _by_name(results, present_assertion)
        assert present_result.scoring_failed is False
        assert present_result.value is True
        absent_result = _by_name(results, absent_assertion)
        assert absent_result.scoring_failed is False
        assert absent_result.value is False, (
            f"verdict should be False (marker absent); reason={absent_result.reason!r}"
        )
        # We don't pin the exact tool: `read`, `scan`, and `search` are all
        # legitimate recoveries of the truncated content.
        called = recorder.tool_names_called()
        assert called, (
            "expected the judge to call at least one drill-in tool, "
            "but the recorder captured no calls"
        )
        assert {"read", "scan", "search"} & set(called), (
            f"expected one of read/scan/search; got {called!r}"
        )
