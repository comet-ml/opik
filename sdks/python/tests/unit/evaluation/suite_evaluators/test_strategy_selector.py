"""Unit tests for LLMJudge scoring-strategy selection."""

import types

import pytest

from opik.evaluation.suite_evaluators import llm_judge
from opik.evaluation.suite_evaluators.llm_judge import strategy_selector


class TestMakeSelector:
    def test_make_selector__auto__returns_heuristic(self):
        assert isinstance(
            strategy_selector.make_selector("auto"), strategy_selector.HeuristicSelector
        )

    def test_make_selector__always__returns_always_agentic(self):
        assert isinstance(
            strategy_selector.make_selector("always"), strategy_selector.AlwaysAgentic
        )

    def test_make_selector__never__returns_never_agentic(self):
        assert isinstance(
            strategy_selector.make_selector("never"), strategy_selector.NeverAgentic
        )

    def test_make_selector__passthrough_for_selector_instance(self):
        sentinel = strategy_selector.NeverAgentic()
        assert strategy_selector.make_selector(sentinel) is sentinel

    def test_make_selector__invalid_mode__raises(self):
        with pytest.raises(ValueError):
            strategy_selector.make_selector("sometimes")  # type: ignore[arg-type]


class TestSimpleSelectors:
    def test_always_agentic__regardless_of_inputs(self):
        sel = strategy_selector.AlwaysAgentic()
        assert (
            sel.select(trace_tool_context=None, model_name="x", assertions=[])
            is strategy_selector.ScoringToolStrategy.AGENTIC
        )

    def test_never_agentic__regardless_of_inputs(self):
        sel = strategy_selector.NeverAgentic()
        ctx = _fake_ctx(payload={"trace": {"id": "t"}, "spans": []})
        assert (
            sel.select(trace_tool_context=ctx, model_name="x", assertions=[])
            is strategy_selector.ScoringToolStrategy.ONE_SHOT
        )


class TestHeuristicSelector:
    def test_no_context__returns_one_shot(self):
        sel = strategy_selector.HeuristicSelector()
        assert (
            sel.select(trace_tool_context=None, model_name="gpt-5", assertions=[])
            is strategy_selector.ScoringToolStrategy.ONE_SHOT
        )

    def test_unknown_model__defaults_to_agentic(self):
        sel = strategy_selector.HeuristicSelector()
        ctx = _fake_ctx(payload={"trace": {"id": "t"}, "spans": []})
        assert (
            sel.select(
                trace_tool_context=ctx,
                model_name="unknown-model-xyz",
                assertions=[],
            )
            is strategy_selector.ScoringToolStrategy.AGENTIC
        )

    def test_small_trace__capable_model__returns_one_shot(self):
        sel = strategy_selector.HeuristicSelector()
        ctx = _fake_ctx(payload={"trace": {"id": "t"}, "spans": []})
        assert (
            sel.select(trace_tool_context=ctx, model_name="gpt-5", assertions=[])
            is strategy_selector.ScoringToolStrategy.ONE_SHOT
        )

    def test_large_trace__capable_model__returns_agentic(self):
        sel = strategy_selector.HeuristicSelector(
            safety_factor=0.5, prompt_overhead_tokens=0
        )
        # Push past gpt-4o budget (128k * 0.5 = 64k tokens ~= 256k chars).
        big = "x" * 2_000_000
        ctx = _fake_ctx(payload={"trace": {"id": "t", "input": big}})
        assert (
            sel.select(trace_tool_context=ctx, model_name="gpt-4o", assertions=[])
            is strategy_selector.ScoringToolStrategy.AGENTIC
        )

    def test_small_model__forces_agentic_even_when_size_fits(self):
        sel = strategy_selector.HeuristicSelector()
        ctx = _fake_ctx(payload={"trace": {"id": "t"}, "spans": []})
        # gpt-5-nano is flagged single_pass_quality_ok=False
        assert (
            sel.select(trace_tool_context=ctx, model_name="gpt-5-nano", assertions=[])
            is strategy_selector.ScoringToolStrategy.AGENTIC
        )

    def test_invalid_safety_factor__raises(self):
        with pytest.raises(ValueError):
            strategy_selector.HeuristicSelector(safety_factor=0.0)
        with pytest.raises(ValueError):
            strategy_selector.HeuristicSelector(safety_factor=1.5)

    def test_capability_prefix_match__longest_wins(self):
        sel = strategy_selector.HeuristicSelector()
        cap = sel._capability_for("gpt-5-nano-2025-08-07")
        # Longest prefix is "gpt-5-nano", not "gpt-5".
        assert cap.single_pass_quality_ok is False


class TestLLMJudgeIntegration:
    def test_default_strategy_is_auto(self):
        judge = llm_judge.LLMJudge(assertions=["a"], track=False)
        assert isinstance(
            judge.get_scoring_tool_strategy(), strategy_selector.HeuristicSelector
        )

    def test_string_mode_resolves_to_selector(self):
        judge = llm_judge.LLMJudge(
            assertions=["a"], track=False, scoring_tool_strategy="always"
        )
        assert isinstance(
            judge.get_scoring_tool_strategy(), strategy_selector.AlwaysAgentic
        )

    def test_custom_selector_instance_passes_through(self):
        custom = strategy_selector.NeverAgentic()
        judge = llm_judge.LLMJudge(
            assertions=["a"], track=False, scoring_tool_strategy=custom
        )
        assert judge.get_scoring_tool_strategy() is custom

    def test_set_scoring_tool_strategy__overrides_existing(self):
        judge = llm_judge.LLMJudge(
            assertions=["a"], track=False, scoring_tool_strategy="never"
        )
        judge.set_scoring_tool_strategy("always")
        assert isinstance(
            judge.get_scoring_tool_strategy(), strategy_selector.AlwaysAgentic
        )

    def test_merged__propagates_scoring_tool_strategy(self):
        a = llm_judge.LLMJudge(
            assertions=["x"], track=False, scoring_tool_strategy="always"
        )
        b = llm_judge.LLMJudge(
            assertions=["y"], track=False, scoring_tool_strategy="always"
        )
        merged = llm_judge.LLMJudge.merged([a, b])
        assert merged is not None
        assert isinstance(
            merged.get_scoring_tool_strategy(), strategy_selector.AlwaysAgentic
        )

    def test_merged__different_strategies__returns_none(self):
        a = llm_judge.LLMJudge(
            assertions=["x"], track=False, scoring_tool_strategy="always"
        )
        b = llm_judge.LLMJudge(
            assertions=["y"], track=False, scoring_tool_strategy="never"
        )
        assert llm_judge.LLMJudge.merged([a, b]) is None


def _fake_ctx(payload):
    """Build a stand-in for TraceToolContext that returns `payload` from
    `get_cached`. We avoid constructing a real one (would require an
    emulator) — the selector only depends on `trace.id` and `get_cached`.
    """
    trace = types.SimpleNamespace(id="trace-1")
    return types.SimpleNamespace(
        trace=trace,
        get_cached=lambda _ref: payload,
    )
