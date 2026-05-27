"""Tests for the default tool registry built by AgenticLLMJudge.

The judge constructs a default registry when none is injected (the
common case from `LLMJudge.score`); this test guards what the agent
sees on the tool surface so adding/removing a tool there is an
explicit decision, not a silent change.
"""

import datetime

from opik.message_processing.emulation import (
    local_emulator_message_processor,
    models,
)

from opik.evaluation.suite_evaluators.agentic import context, judge


def _trace():
    return models.TraceModel(
        id="t-1",
        start_time=datetime.datetime(2026, 5, 13),
        name="trace",
        project_name="default",
        source="sdk",
    )


def _ctx():
    emulator = local_emulator_message_processor.LocalEmulatorMessageProcessor(
        active=True
    )
    return context.TraceToolContext(
        trace=_trace(),
        spans=[],
        parent_by_child={},
        emulator=emulator,
    )


def test_default_registry__judge_built_with_no_injection__exposes_overview_and_read_tools():
    registry = judge.default_tool_registry()

    assert sorted(registry.names()) == ["read", "scan", "search"]


def test_default_registry__specs__are_well_formed():
    registry = judge.default_tool_registry()

    # Each spec is an OpenAI-style tool descriptor with a function name.
    spec_names = {spec["function"]["name"] for spec in registry.specs()}
    assert spec_names == {"read", "scan", "search"}


def test_default_registry__read_tool_dispatch__reaches_execute():
    # Sanity: registry-level dispatch routes to ReadTool's execute and
    # returns its JSON envelope (here an `error` because the entity is
    # absent — but the routing itself succeeds, which is the point).
    registry = judge.default_tool_registry()

    result = registry.execute("read", '{"type": "trace", "id": "absent"}', ctx=_ctx())

    assert "absent" in result or "not found" in result.lower()
