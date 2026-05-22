"""Tests for the overview-limit ladder and the budget helper.

Together these decide how rich the agentic-judge inline overview can be
without overflowing the judge model's context budget.
"""

import datetime

import pytest

from opik.evaluation.suite_evaluators.agentic.compression import (
    span_tree_serializer,
    tokens,
)
from opik.evaluation.suite_evaluators.llm_judge import strategy_selector
from opik.message_processing.emulation import models


def _now():
    return datetime.datetime(2026, 5, 13, 12, 0, 0)


def _trace(input_payload=None, output_payload=None):
    return models.TraceModel(
        id="t-1",
        start_time=_now(),
        name="trace",
        project_name="default",
        source="sdk",
        input=input_payload or {"q": "hi"},
        output=output_payload or {"a": "hello"},
        end_time=_now() + datetime.timedelta(seconds=1),
    )


def _span(span_id, payload):
    return models.SpanModel(
        id=span_id,
        start_time=_now(),
        source="sdk",
        name=span_id,
        type="general",
        input=payload,
        output=payload,
    )


class TestPickOverviewIoCharLimit:
    def test_small_trace_large_budget__picks_top_of_ladder(self):
        """Tiny trace, generous budget → return the no-truncation tier."""
        limit, overview = span_tree_serializer.pick_overview_io_char_limit(
            trace=_trace(),
            spans=[_span("s1", {"x": "y"})],
            parent_by_child={"s1": None},
            budget_tokens=1_000_000,
            ladder=None,
        )
        assert limit == span_tree_serializer.NO_OVERVIEW_TRUNCATION
        assert limit == span_tree_serializer.OVERVIEW_IO_LIMIT_LADDER[0]
        # Returned overview matches what a direct render at that limit
        # would produce — the sizer's render is reused.
        expected = span_tree_serializer.serialize_overview(
            _trace(),
            [_span("s1", {"x": "y"})],
            {"s1": None},
            io_char_limit=limit,
        )
        assert overview == expected

    def test_large_field_fits_budget__no_truncation_tier_used(self):
        """A single big field + tiny rest, with a budget large enough to
        absorb it, should pick the no-truncation tier and produce an
        un-truncated overview."""
        big = "x" * 150_000
        limit, overview = span_tree_serializer.pick_overview_io_char_limit(
            trace=_trace(),
            spans=[_span("s1", {"data": big})],
            parent_by_child={"s1": None},
            budget_tokens=1_000_000,
            ladder=None,
        )
        assert limit == span_tree_serializer.NO_OVERVIEW_TRUNCATION
        span_input = overview["spans"][0]["input"]
        assert "[TRUNCATED" not in span_input

    def test_huge_trace_tiny_budget__falls_back_to_floor(self):
        """Trace exceeds every ladder entry → caller gets the floor."""
        big = "x" * 200_000
        limit, overview = span_tree_serializer.pick_overview_io_char_limit(
            trace=_trace(input_payload={"prompt": big}),
            spans=[_span("s1", {"data": big})],
            parent_by_child={"s1": None},
            budget_tokens=100,
            ladder=None,
        )
        assert limit == span_tree_serializer.OVERVIEW_IO_LIMIT_LADDER[-1]
        # Floor overview is still returned (no extra render needed by
        # the caller).
        assert "[TRUNCATED" in overview["trace"]["input"]

    def test_picks_largest_entry_that_fits(self):
        """Budget midway through the ladder → first fitting entry wins."""
        # Render with the ladder's second entry as a yardstick; set the
        # budget slightly above that so the second entry should be picked,
        # not the (larger) first.
        target = span_tree_serializer.OVERVIEW_IO_LIMIT_LADDER[1]
        big = "x" * (target * 4)  # enough to make even target trip its limit
        overview_at_target = span_tree_serializer.serialize_overview(
            _trace(input_payload={"prompt": big}),
            [_span("s1", {"data": big})],
            {"s1": None},
            io_char_limit=target,
        )
        # Budget = exactly the size of the target-rendered overview. The
        # ladder entry above `target` produces a strictly larger render,
        # so the sizer should land on `target`.
        budget = tokens.estimate_tokens(overview_at_target)
        chosen, _ = span_tree_serializer.pick_overview_io_char_limit(
            trace=_trace(input_payload={"prompt": big}),
            spans=[_span("s1", {"data": big})],
            parent_by_child={"s1": None},
            budget_tokens=budget,
            ladder=None,
        )
        assert chosen == target

    def test_zero_budget__returns_floor(self):
        limit, overview = span_tree_serializer.pick_overview_io_char_limit(
            trace=_trace(), spans=[], parent_by_child={}, budget_tokens=0, ladder=None
        )
        assert limit == span_tree_serializer.OVERVIEW_IO_LIMIT_LADDER[-1]
        # Overview still produced for the zero-budget shortcut.
        assert overview["trace"]["id"] == "t-1"

    def test_empty_ladder__raises(self):
        with pytest.raises(ValueError):
            span_tree_serializer.pick_overview_io_char_limit(
                trace=_trace(),
                spans=[],
                parent_by_child={},
                budget_tokens=1000,
                ladder=(),
            )

    def test_custom_ladder_is_honored(self):
        # Tiny budget, custom ladder; floor entry should still come back.
        chosen, _ = span_tree_serializer.pick_overview_io_char_limit(
            trace=_trace(),
            spans=[],
            parent_by_child={},
            budget_tokens=1,
            ladder=(50_000, 100),
        )
        assert chosen == 100

    def test_monkeypatched_module_ladder_is_honored(self, monkeypatch):
        """Regression: function defaults are evaluated at definition
        time, so capturing the module-level ladder as the default value
        would let monkeypatches silently no-op. The sizer must read the
        module attribute at call time. The e2e
        `test_test_suite_agentic__assertion_requires_buried_keyword_lookup`
        test depends on this behavior to force floor-tier truncation
        regardless of the model's context budget.
        """
        monkeypatch.setattr(
            span_tree_serializer,
            "OVERVIEW_IO_LIMIT_LADDER",
            (span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT,),
        )

        big = "x" * 5_000
        chosen, overview = span_tree_serializer.pick_overview_io_char_limit(
            trace=_trace(input_payload={"prompt": big}),
            spans=[],
            parent_by_child={},
            budget_tokens=1_000_000,  # would normally pick NO_OVERVIEW_TRUNCATION
            ladder=None,
        )
        assert chosen == span_tree_serializer.OVERVIEW_IO_FLOOR_CHAR_LIMIT
        assert "[TRUNCATED" in overview["trace"]["input"]


class TestSerializeOverviewIoLimitParam:
    def test_default_limit_matches_module_constant(self):
        big = "x" * 5_000
        result = span_tree_serializer.serialize_overview(
            _trace(input_payload={"prompt": big}),
            spans=[],
            parent_by_child={},
        )
        # Default applies → truncation marker appears, original length lost.
        assert "[TRUNCATED" in result["trace"]["input"]
        assert len(result["trace"]["input"]) < len(big)

    def test_explicit_large_limit__no_truncation(self):
        big = "x" * 5_000
        result = span_tree_serializer.serialize_overview(
            _trace(input_payload={"prompt": big}),
            spans=[],
            parent_by_child={},
            io_char_limit=50_000,
        )
        assert "[TRUNCATED" not in result["trace"]["input"]


class TestComputeBudgetTokens:
    def test_known_model__formula(self):
        # gpt-5 has context_window=400_000 in the default table.
        budget = strategy_selector.compute_budget_tokens(
            "gpt-5", safety_factor=0.5, prompt_overhead_tokens=1_500
        )
        assert budget == 400_000 // 2 - 1_500

    def test_versioned_id__longest_prefix_wins(self):
        # "gpt-5-nano-2025-08-07" should resolve to the gpt-5-nano entry,
        # not gpt-5. The two share the same context_window, but the
        # function under test is about prefix selection.
        from_versioned = strategy_selector.compute_budget_tokens(
            "gpt-5-nano-2025-08-07"
        )
        from_canonical = strategy_selector.compute_budget_tokens("gpt-5-nano")
        assert from_versioned == from_canonical

    def test_unknown_model__uses_default_capability(self):
        # _DEFAULT_CAPABILITY.context_window == 8_000
        budget = strategy_selector.compute_budget_tokens(
            "totally-unknown-model",
            safety_factor=0.5,
            prompt_overhead_tokens=0,
        )
        assert budget == 4_000

    def test_invalid_safety_factor__raises(self):
        with pytest.raises(ValueError):
            strategy_selector.compute_budget_tokens("gpt-5", safety_factor=0)
        with pytest.raises(ValueError):
            strategy_selector.compute_budget_tokens("gpt-5", safety_factor=1.1)

    def test_custom_capabilities_table__used(self):
        custom = {
            "tinybot": strategy_selector.ModelCapability(
                context_window=2_000, single_pass_quality_ok=True
            )
        }
        budget = strategy_selector.compute_budget_tokens(
            "tinybot",
            safety_factor=0.5,
            prompt_overhead_tokens=0,
            capabilities=custom,
        )
        assert budget == 1_000
