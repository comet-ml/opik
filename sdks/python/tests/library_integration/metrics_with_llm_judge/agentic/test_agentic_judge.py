"""Integration tests for the agentic LLM judge against a real model.

These tests sit between the unit suite (which mocks the LLM and stubs
`run_agentic_judge`) and the e2e suite (which goes through
`opik.run_tests` and builds traces via decorators). Here the
`TraceToolContext` is constructed inline with `_seeding.build_context`,
the judge is the only network call, and the verdict is checked field
by field.

Each test pins the scoring strategy to `"always"` so the agentic path
runs regardless of the trace size or the model's
`single_pass_quality_ok` capability. That keeps these tests focused on
agentic-judge behavior; one-shot LLMJudge integration is covered by
`tests/library_integration/metrics_with_llm_judge/test_llm_judge.py`.

Assertions are written deliberately on the unambiguous side — a
competent judge model (`gpt-4o-mini`) should produce a stable verdict
across runs. If a test starts flaking, prefer tightening the trace
content over loosening the assertion, so we don't paper over a real
regression in the judge.
"""

from typing import Iterable, List, Tuple

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
        scoring_strategy="always",
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
    base_registry = agentic_judge._default_registry()
    recorder = RecordingToolRegistry(base_registry)
    model = models_factory.get(model_name, track=False, seed=42, temperature=0.0)
    judge = agentic_judge.AgenticLLMJudge(
        assertions=list(assertions),
        model=model,
        registry=recorder,
    )
    return judge, recorder


class TestAgenticJudgeBasicVerdicts:
    """Assertions decidable from the trace I/O surface alone — these
    should pass/fail crisply on every run. If one of these flakes, the
    judge's overview rendering or prompt is the suspect, not the model.
    """

    def test_assertion_about_output__passes(self, judge_model_name):
        trace = _seeding.make_trace(
            input={"question": "What is the capital of France?"},
            output={"answer": "The capital of France is Paris."},
        )
        ctx = _seeding.build_context(
            trace, spans=[_seeding.make_span(span_id="s-1", name="answer_step")]
        )

        assertion = "The agent's output mentions Paris."
        results = _make_judge(assertion, judge_model_name).score(
            input=trace.input,
            output=trace.output,
            trace_tool_context=ctx,
        )

        result = _by_name(results, assertion)
        assert result.scoring_failed is False
        assert result.value is True
        assert result.reason

    def test_false_assertion__fails(self, judge_model_name):
        trace = _seeding.make_trace(
            input={"question": "What is the capital of France?"},
            output={"answer": "The capital of France is Paris."},
        )
        ctx = _seeding.build_context(
            trace, spans=[_seeding.make_span(span_id="s-1", name="answer_step")]
        )

        # Output clearly contradicts the assertion.
        assertion = "The agent's output mentions Tokyo."
        results = _make_judge(assertion, judge_model_name).score(
            input=trace.input,
            output=trace.output,
            trace_tool_context=ctx,
        )

        result = _by_name(results, assertion)
        assert result.scoring_failed is False
        assert result.value is False
        assert result.reason


class TestAgenticJudgeSpanStructure:
    """Assertions decidable only from the span tree. A one-shot judge
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

    def test_assertion_about_child_span_error__passes(self, judge_model_name):
        trace = _seeding.make_trace(
            input={"question": "fetch and summarize"},
            # Task output looks fine because the task caught the inner
            # error; the only evidence of failure lives in the span tree.
            output={"summary": "n/a"},
        )
        spans = [
            _seeding.make_span(span_id="root", name="task"),
            _seeding.make_span(
                span_id="fetch",
                name="fetch_remote",
                start_offset_ms=1,
                error_info={
                    "exception_type": "ConnectionError",
                    "message": "remote unreachable",
                    "traceback": "Traceback (most recent call last): ...",
                },
            ),
        ]
        ctx = _seeding.build_context(
            trace, spans=spans, parent_by_child={"root": None, "fetch": "root"}
        )

        assertion = "At least one span in the trace recorded an error."
        results = _make_judge(assertion, judge_model_name).score(
            input=trace.input,
            output=trace.output,
            trace_tool_context=ctx,
        )

        result = _by_name(results, assertion)
        assert result.scoring_failed is False
        assert result.value is True


class TestAgenticJudgeMultipleAssertions:
    """The judge returns one ScoreResult per assertion. Mixing a clearly-
    true and a clearly-false assertion in a single call verifies the
    per-assertion parse and the result-name plumbing both work.
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

    Pattern in each test:
    1. Pin `OVERVIEW_IO_LIMIT_LADDER` to the 500-char floor.
    2. Plant a unique marker past 500 chars in a trace or span field.
    3. Ask the judge whether the marker appears in that field.
    4. Assert verdict is True (recovery succeeded) AND the recorder
       captured at least one drill-in call.
    """

    def test_buried_marker_in_trace_input__triggers_read(
        self, judge_model_name, monkeypatch
    ):
        _pin_ladder_to_floor(monkeypatch)

        # Marker placement: ~600 chars of pad before the marker
        # guarantees it sits past the 500-char floor and therefore
        # past the truncation suffix. The truncation suffix the
        # judge sees explicitly names `read(type='trace', id=...)`
        # as the recovery call, which `gpt-4o-mini` follows reliably.
        marker = "MARKER-XYZ-987"
        long_input = ("padding-text " * 50) + marker
        assert len(long_input) > span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT
        trace = _seeding.make_trace(
            input={"prompt": long_input},
            output={"answer": "ok"},
        )
        ctx = _seeding.build_context(
            trace,
            spans=[_seeding.make_span(span_id="s-1", name="answer_step")],
        )

        assertion = f"The trace input contains the literal token `{marker}`."
        judge, recorder = _make_recording_agentic_judge([assertion], judge_model_name)

        results = judge.score(ctx)

        result = _by_name(results, assertion)
        # Marker is in the un-truncated input → verdict True iff the
        # judge actually retrieved it.
        assert result.scoring_failed is False
        assert result.value is True
        # Direct evidence of the agentic loop engaged. We don't pin the
        # exact tool because `read`, `scan`, and `search` are all
        # legitimate recoveries of the truncated content; the
        # truncation suffix nudges toward `read` but the judge is
        # allowed to pick whichever fits its plan.
        called = recorder.tool_names_called()
        assert called, (
            "expected the judge to call at least one drill-in tool, "
            "but the recorder captured no calls"
        )
        assert {"read", "scan", "search"} & set(called), (
            f"expected one of read/scan/search; got {called!r}"
        )

    def test_absent_marker_with_truncated_input__verdict_false_after_lookup(
        self, judge_model_name, monkeypatch
    ):
        """Negative-case companion to the buried-marker tests.

        The trace input is long enough to be truncated AND deliberately
        does not contain the asked-about marker — only an unrelated
        token sits past the floor. A trustworthy judge has to look at
        the full content before declaring "absent", because guessing
        "absent" from the truncated overview would be indistinguishable
        from "didn't check at all."

        We assert:
        - verdict is False (the marker really isn't there),
        - the recorder is non-empty (the judge looked rather than
          shortcutting to "no").

        This catches the failure mode where a model treats a
        `[TRUNCATED ...]` suffix as "content is absent" — the same
        pattern the truncation-suffix hint was designed to defeat.
        """
        _pin_ladder_to_floor(monkeypatch)

        absent_marker = "EXPECTED-MISSING-MARKER-001"
        unrelated_marker = "OTHER-TOKEN-456"
        # Pad so the unrelated marker sits past the 500-char floor —
        # i.e. it's hidden from the overview just like a real marker
        # would be. The judge has to recover the full input to confirm
        # the *absence* of `absent_marker`.
        long_input = ("padding-text " * 50) + unrelated_marker
        assert len(long_input) > span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT
        assert absent_marker not in long_input

        trace = _seeding.make_trace(
            input={"prompt": long_input},
            output={"answer": "ok"},
        )
        ctx = _seeding.build_context(
            trace,
            spans=[_seeding.make_span(span_id="s-1", name="answer_step")],
        )

        assertion = f"The trace input contains the literal token `{absent_marker}`."
        judge, recorder = _make_recording_agentic_judge([assertion], judge_model_name)

        results = judge.score(ctx)

        result = _by_name(results, assertion)
        assert result.scoring_failed is False
        assert result.value is False, (
            f"verdict should be False (marker absent); reason={result.reason!r}"
        )
        called = recorder.tool_names_called()
        # The crux of this test: even though the correct answer is
        # "no", the judge has to verify that by drilling in. An empty
        # `called` here means the judge took the truncated overview at
        # face value — a regression in the truncation-suffix hint or
        # in the loop's prompt.
        assert called, (
            "expected the judge to call at least one drill-in tool "
            "before declaring the marker absent; recorder captured no "
            "calls, suggesting the judge shortcutted to 'no' from the "
            "truncated overview"
        )
        assert {"read", "scan", "search"} & set(called), (
            f"expected one of read/scan/search; got {called!r}"
        )

    def test_buried_marker_in_span_output__triggers_read(
        self, judge_model_name, monkeypatch
    ):
        _pin_ladder_to_floor(monkeypatch)

        # Same pattern but on a span field — verifies the truncation
        # suffix's `entity_type='span'` anchor steers the judge into
        # the right `read` argument shape, not just the trace one.
        marker = "BURIED-IN-SPAN-512"
        long_output = ("payload-line " * 50) + marker
        assert len(long_output) > span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT
        trace = _seeding.make_trace(
            input={"q": "look up something"},
            output={"summary": "ok"},
        )
        spans = [
            _seeding.make_span(
                span_id="lookup",
                name="lookup_step",
                input={"q": "look up something"},
                output={"data": long_output},
            ),
        ]
        ctx = _seeding.build_context(trace, spans=spans)

        assertion = (
            f"The `lookup_step` span's output contains the literal token `{marker}`."
        )
        judge, recorder = _make_recording_agentic_judge([assertion], judge_model_name)

        results = judge.score(ctx)

        result = _by_name(results, assertion)
        assert result.scoring_failed is False
        assert result.value is True
        called = recorder.tool_names_called()
        assert called, (
            "expected the judge to call at least one drill-in tool, "
            "but the recorder captured no calls"
        )
        assert {"read", "scan", "search"} & set(called), (
            f"expected one of read/scan/search; got {called!r}"
        )
