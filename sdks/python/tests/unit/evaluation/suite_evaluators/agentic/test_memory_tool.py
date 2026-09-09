"""Unit + integration tests for the `memory` tool (SAFARI Short-Term Memory).

Covers argument parsing, the record/recall/clear actions, cross-turn
persistence, the defensive recall cap, and — critically — the wiring into
the default registry built by `AgenticLLMJudge`, which is the integration
point that makes the tool reachable from the real tool-call loop.
"""

import json

from opik.evaluation.suite_evaluators.agentic import judge
from opik.evaluation.suite_evaluators.agentic.tools.memory import ShortTermMemoryTool

# ShortTermMemoryTool never reads `ctx`; the loop always passes a TraceToolContext,
# but for these tests None mirrors how the registry-level stub tests pass it
# and keeps the focus on the memory state machine itself.
_CTX = None


def _run(tool, arguments):
    return json.loads(tool.execute(arguments, _CTX))


class TestRecordRecallClear:
    def test_memory__record_then_recall__returns_the_note(self):
        tool = ShortTermMemoryTool()

        record = _run(
            tool,
            json.dumps(
                {"action": "record", "key": "root_cause", "note": "s-3 errored"}
            ),
        )
        recall = _run(tool, json.dumps({"action": "recall"}))

        assert record["status"] == "recorded"
        assert record["count"] == 1
        assert recall["count"] == 1
        assert recall["notes"] == [{"key": "root_cause", "note": "s-3 errored"}]

    def test_memory__record_preserves_insertion_order(self):
        tool = ShortTermMemoryTool()

        _run(tool, json.dumps({"action": "record", "key": "first", "note": "1"}))
        _run(tool, json.dumps({"action": "record", "key": "second", "note": "2"}))
        _run(tool, json.dumps({"action": "record", "key": "third", "note": "3"}))

        recall = _run(tool, json.dumps({"action": "recall"}))
        assert [n["key"] for n in recall["notes"]] == ["first", "second", "third"]

    def test_memory__record_overwrites_existing_key(self):
        tool = ShortTermMemoryTool()

        _run(tool, json.dumps({"action": "record", "key": "k", "note": "old"}))
        record = _run(tool, json.dumps({"action": "record", "key": "k", "note": "new"}))

        recall = _run(tool, json.dumps({"action": "recall"}))
        assert record["count"] == 1  # overwrite, not append
        assert recall["notes"] == [{"key": "k", "note": "new"}]

    def test_memory__recall_with_key_filter__subsets_notes(self):
        tool = ShortTermMemoryTool()

        _run(tool, json.dumps({"action": "record", "key": "error_span", "note": "e"}))
        _run(tool, json.dumps({"action": "record", "key": "ok_span", "note": "o"}))

        filtered = _run(tool, json.dumps({"action": "recall", "key": "error"}))
        assert filtered["count"] == 1
        assert filtered["notes"] == [{"key": "error_span", "note": "e"}]

    def test_memory__recall_on_empty_notepad__returns_zero(self):
        tool = ShortTermMemoryTool()

        recall = _run(tool, json.dumps({"action": "recall"}))

        assert recall["count"] == 0
        assert recall["notes"] == []

    def test_memory__clear__wipes_notes_and_reports_count(self):
        tool = ShortTermMemoryTool()

        _run(tool, json.dumps({"action": "record", "key": "a", "note": "1"}))
        _run(tool, json.dumps({"action": "record", "key": "b", "note": "2"}))
        cleared = _run(tool, json.dumps({"action": "clear"}))

        assert cleared == {"status": "cleared", "count": 2}

        recall = _run(tool, json.dumps({"action": "recall"}))
        assert recall["count"] == 0


class TestCrossTurnPersistence:
    def test_memory__separate_execute_calls_share_state(self):
        # The whole point of SAFARI's STM: a finding recorded on turn N is
        # readable on turn N+k of the same loop. execute() is called once per
        # tool turn, so persistence across calls is what makes it memory.
        tool = ShortTermMemoryTool()

        first_turn = tool.execute(
            json.dumps(
                {"action": "record", "key": "hyp", "note": "search_api missing"}
            ),
            _CTX,
        )
        later_turn = tool.execute(json.dumps({"action": "recall"}), _CTX)

        assert json.loads(first_turn)["status"] == "recorded"
        notes = json.loads(later_turn)["notes"]
        assert notes == [{"key": "hyp", "note": "search_api missing"}]

    def test_memory__fresh_instance_starts_empty(self):
        # State must not leak across evaluations: each default_tool_registry()
        # builds a new ShortTermMemoryTool, so the notepad is scoped to one loop run.
        first = ShortTermMemoryTool()
        _run(first, json.dumps({"action": "record", "key": "x", "note": "y"}))

        second = ShortTermMemoryTool()
        recall = _run(second, json.dumps({"action": "recall"}))

        assert recall["count"] == 0


class TestArgumentValidation:
    def test_memory__missing_action__returns_error(self):
        assert "error" in _run(ShortTermMemoryTool(), json.dumps({}))

    def test_memory__unsupported_action__lists_supported(self):
        result = _run(ShortTermMemoryTool(), json.dumps({"action": "prepend"}))

        assert "error" in result
        assert "record" in result["error"]
        assert "recall" in result["error"]
        assert "clear" in result["error"]

    def test_memory__record_missing_key__returns_error(self):
        result = _run(
            ShortTermMemoryTool(),
            json.dumps({"action": "record", "note": "no key"}),
        )
        assert result == {"error": "Missing required 'key'"}

    def test_memory__record_missing_note__returns_error(self):
        result = _run(
            ShortTermMemoryTool(),
            json.dumps({"action": "record", "key": "k"}),
        )
        assert result == {"error": "Missing required 'note'"}

    def test_memory__invalid_json__returns_error_envelope_not_raise(self):
        # Tools must never raise out of execute; malformed input surfaces as JSON.
        result = json.loads(ShortTermMemoryTool().execute("not json", _CTX))
        assert "error" in result

    def test_memory__recall_ignores_empty_key_filter(self):
        # An empty `key` should read everything, not filter to "" substring.
        tool = ShortTermMemoryTool()
        _run(tool, json.dumps({"action": "record", "key": "k", "note": "v"}))

        recall = _run(tool, json.dumps({"action": "recall", "key": ""}))
        assert recall["count"] == 1


class TestRecallCap:
    def test_memory__oversized_note__recall_signals_truncation(self):
        # A single note larger than the 16 KB recall budget is dropped and the
        # response is flagged truncated, so the tool cannot re-introduce the
        # context bloat it exists to avoid.
        tool = ShortTermMemoryTool()
        _run(
            tool,
            json.dumps({"action": "record", "key": "huge", "note": "x" * 20_000}),
        )

        recall = _run(tool, json.dumps({"action": "recall"}))

        assert recall["truncated"] is True
        assert recall["count"] == 0


class TestDefaultRegistryIntegration:
    """Exercises the wiring through `judge.default_tool_registry` — the
    non-new call-site module — to prove the tool is reachable from the real
    tool-call loop (registry.specs advertises it; registry.execute routes to
    it), not just callable in isolation.
    """

    def test_default_registry__memory_advertised_in_specs(self):
        registry = judge.default_tool_registry()
        spec_names = {spec["function"]["name"] for spec in registry.specs()}
        assert "memory" in spec_names

    def test_default_registry__memory_roundtrip_through_registry(self):
        registry = judge.default_tool_registry()

        recorded = json.loads(
            registry.execute(
                "memory",
                json.dumps({"action": "record", "key": "finding", "note": "ok"}),
                ctx=None,
            )
        )
        # Same registry instance ⇒ same ShortTermMemoryTool ⇒ same notepad, so the
        # recall on the next loop turn sees what was just recorded.
        recalled = json.loads(
            registry.execute("memory", json.dumps({"action": "recall"}), ctx=None)
        )

        assert recorded["status"] == "recorded"
        assert recalled["notes"] == [{"key": "finding", "note": "ok"}]

    def test_default_registry__memory_uses_fresh_notepad_per_build(self):
        # default_tool_registry() is built once per AgenticLLMJudge (one
        # evaluation item), so memory must not leak between two evaluations.
        first = judge.default_tool_registry()
        first.execute(
            "memory",
            json.dumps({"action": "record", "key": "leak", "note": "no"}),
            ctx=None,
        )

        second = judge.default_tool_registry()
        recalled = json.loads(
            second.execute("memory", json.dumps({"action": "recall"}), ctx=None)
        )

        assert recalled["count"] == 0
