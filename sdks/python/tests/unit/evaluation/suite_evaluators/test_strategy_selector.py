"""Unit tests for LLMJudge scoring-strategy selection."""

import types

import pytest

from opik.evaluation.suite_evaluators import llm_judge
from opik.evaluation.suite_evaluators.llm_judge import (
    model_capabilities,
    strategy_selector,
)


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

    def test_context_present__capable_model__returns_agentic(self):
        sel = strategy_selector.HeuristicSelector()
        ctx = _fake_ctx(payload={"trace": {"id": "t"}, "spans": []})
        for model in ("gpt-5", "gpt-4o", "gpt-4o-mini", "claude-opus-4-7"):
            assert (
                sel.select(trace_tool_context=ctx, model_name=model, assertions=[])
                is strategy_selector.ScoringToolStrategy.AGENTIC
            ), model

    def test_context_present__opted_out_model__returns_one_shot(self):
        sel = strategy_selector.HeuristicSelector()
        ctx = _fake_ctx(payload={"trace": {"id": "t"}, "spans": []})
        # gpt-5-nano is flagged agentic_in_auto=False because it tends
        # to ignore tool affordances (see backend SupportedJudgeProvider).
        assert (
            sel.select(trace_tool_context=ctx, model_name="gpt-5-nano", assertions=[])
            is strategy_selector.ScoringToolStrategy.ONE_SHOT
        )

    def test_context_present__unknown_model__returns_one_shot(self):
        sel = strategy_selector.HeuristicSelector()
        ctx = _fake_ctx(payload={"trace": {"id": "t"}, "spans": []})
        # Unknown models fall back to DEFAULT_CAPABILITY (agentic_in_auto=False).
        assert (
            sel.select(
                trace_tool_context=ctx,
                model_name="totally-unknown-xyz",
                assertions=[],
            )
            is strategy_selector.ScoringToolStrategy.ONE_SHOT
        )


class TestCapabilityLookup:
    def test_capability_prefix_match__longest_wins(self):
        table = [
            model_capabilities.ModelCapability(
                "gpt-5", context_window=1, agentic_in_auto=True
            ),
            model_capabilities.ModelCapability(
                "gpt-5-nano", context_window=2, agentic_in_auto=False
            ),
        ]
        cap = strategy_selector._capability_for(
            "gpt-5-nano-2025-08-07", capabilities=table
        )
        assert cap.model_name_prefix == "gpt-5-nano"
        assert cap.agentic_in_auto is False

    def test_unknown_model__falls_back_to_default(self):
        cap = strategy_selector._capability_for("totally-unknown-model")
        assert cap is model_capabilities.DEFAULT_CAPABILITY

    def test_provider_qualified_name__resolves_same_as_bare(self):
        # `AnthropicChatModel` defaults to a provider-qualified name and
        # `base_model` stores it verbatim, so the qualified form is what
        # reaches the capability lookup. It has to resolve to the same
        # entry as the bare id, otherwise the judge silently drops to
        # DEFAULT_CAPABILITY.
        bare = strategy_selector._capability_for("claude-sonnet-4-6")
        qualified = strategy_selector._capability_for("anthropic/claude-sonnet-4-6")
        assert qualified is bare
        assert qualified is not model_capabilities.DEFAULT_CAPABILITY

    def test_provider_qualified_name__preserves_agentic_flag(self):
        # The fall-through changes more than the token budget: it also
        # flips `agentic_in_auto`, which changes strategy selection under
        # `auto` rather than just how rich the overview is.
        cap = strategy_selector._capability_for("anthropic/claude-sonnet-4-6")
        assert cap.agentic_in_auto is True

    def test_unknown_provider_prefix__still_falls_back(self):
        cap = strategy_selector._capability_for("someprovider/totally-unknown-model")
        assert cap is model_capabilities.DEFAULT_CAPABILITY

    def test_fifth_generation_models__are_registered(self):
        for model_name in ("claude-opus-5", "claude-sonnet-5"):
            cap = strategy_selector._capability_for(model_name)
            assert cap is not model_capabilities.DEFAULT_CAPABILITY, model_name

    def test_fifth_generation_models__have_expected_capabilities(self):
        # Asserting only "not DEFAULT_CAPABILITY" would pass on a wrong
        # prefix match, so pin the values.
        for model_name in ("claude-opus-5", "claude-sonnet-5"):
            cap = strategy_selector._capability_for(model_name)
            assert cap.model_name_prefix == model_name, model_name
            assert cap.context_window == 1_000_000, model_name

    def test_sampling_rejecting_models__stay_out_of_agentic_auto(self):
        # `agentic/loop.py` sends temperature=0 on every turn. Anthropic
        # returns 400 for a non-default temperature from Opus 4.7 onward,
        # so routing these models into the agentic loop under `auto` would
        # fail the first request instead of scoring.
        for model_name in ("claude-opus-5", "claude-sonnet-5"):
            cap = strategy_selector._capability_for(model_name)
            assert cap.agentic_in_auto is False, model_name

    def test_sampling_accepting_models__keep_agentic_auto(self):
        for model_name in ("claude-sonnet-4-6", "claude-opus-4-6", "gpt-4o"):
            cap = strategy_selector._capability_for(model_name)
            assert cap.agentic_in_auto is True, model_name

    def test_custom_table_with_empty_prefix__prefers_real_match(self):
        # An empty prefix matches every name via startswith. Without
        # deferring it, a caller-supplied table containing a catch-all
        # entry would shadow the real match for a qualified name.
        table = [
            model_capabilities.ModelCapability(
                "", context_window=16_000, agentic_in_auto=False
            ),
            model_capabilities.ModelCapability(
                "claude-sonnet-4-6", context_window=1_000_000, agentic_in_auto=True
            ),
        ]
        cap = strategy_selector._capability_for(
            "anthropic/claude-sonnet-4-6", capabilities=table
        )
        assert cap.model_name_prefix == "claude-sonnet-4-6"
        assert cap.context_window == 1_000_000


class TestBudgetTokens:
    def test_qualified_name__matches_bare_budget(self):
        assert strategy_selector.compute_budget_tokens(
            "anthropic/claude-sonnet-4-6"
        ) == strategy_selector.compute_budget_tokens("claude-sonnet-4-6")

    def test_qualified_name__is_not_the_default_budget(self):
        default_budget = (
            int(model_capabilities.DEFAULT_CAPABILITY.context_window * 0.5) - 1500
        )
        assert (
            strategy_selector.compute_budget_tokens("anthropic/claude-sonnet-4-6")
            != default_budget
        )


class TestLLMJudgeIntegration:
    @pytest.mark.skip("skipped until we have default scoring_tool_strategy='auto'")
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
