"""Unit tests for the `search` tool.

Covers argument parsing, regex semantics, path narrowing, output cap
behavior (max matches + per-value truncation + total byte cap), and
error propagation from the underlying path evaluator.
"""

import datetime
import json

from opik.message_processing.emulation import models

from opik.evaluation.suite_evaluators.agentic.tools import search as search_module

from . import _seeding


def _now():
    return datetime.datetime(2026, 5, 13, 12, 0, 0)


def _trace(trace_id="t-1", **overrides):
    base = dict(
        id=trace_id,
        start_time=_now(),
        end_time=_now() + datetime.timedelta(seconds=1),
        name="trace",
        project_name="default",
        source="sdk",
        input={"q": "hi"},
        output={"a": "there"},
    )
    base.update(overrides)
    return models.TraceModel(**base)


def _span(span_id, start_offset_s=0, **overrides):
    base = dict(
        id=span_id,
        start_time=_now() + datetime.timedelta(seconds=start_offset_s),
        source="sdk",
        name=span_id,
        type="general",
    )
    base.update(overrides)
    return models.SpanModel(**base)


def _ctx(trace, spans, parent_by_child=None):
    return _seeding.build_ctx(trace, spans, parent_by_child)


# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------


class TestArgumentParsing:
    def test_search__missing_pattern__returns_error(self):
        ctx = _ctx(_trace(), [])
        tool = search_module.SearchTool()

        result = tool.execute(json.dumps({"type": "trace", "id": "t-1"}), ctx)

        # Argument parsing fails before type/id are echoed, so the
        # envelope's positional slots fall back to `?`.
        assert result == (
            "[search: ?:? | pattern='?' | ERROR]\nMissing required 'pattern'"
        )

    def test_search__invalid_regex__returns_error(self):
        ctx = _ctx(_trace(), [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": "t-1", "pattern": "[unclosed"}),
            ctx,
        )

        # Header is deterministic; body wording comes from `re.error` and
        # varies across Python versions, so check the prefix only.
        header, body = result.split("\n", 1)
        assert header == "[search: trace:t-1 | pattern='[unclosed' | ERROR]"
        assert body.startswith("Invalid regex: ")

    def test_search__unknown_entity__returns_error(self):
        ctx = _ctx(_trace(), [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": "missing", "pattern": "boom"}),
            ctx,
        )

        assert result == (
            "[search: trace:missing | pattern='boom' | ERROR]\n"
            "Entity (type=trace, id=missing) not found in local trace cache"
        )


# ---------------------------------------------------------------------------
# Matching
# ---------------------------------------------------------------------------


class TestRegexSearch:
    def test_search__no_matches__renders_placeholder(self):
        ctx = _ctx(_trace(), [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": "t-1", "pattern": "nonexistent"}),
            ctx,
        )

        body = result.split("\n", 1)[1]
        assert body == "<no matches>"

    def test_search__match_in_nested_dict__surfaces_full_path(self):
        trace = _trace(input={"prompt": "hello world"})
        ctx = _ctx(trace, [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": trace.id, "pattern": "world"}),
            ctx,
        )

        body = result.split("\n", 1)[1]
        # Composite cache shape → trace.input.prompt. Fixture has exactly
        # one matching string, so the body should be the single match
        # line — strict equality catches accidental extra matches.
        assert body == ".trace.input.prompt: hello world"

    def test_search__match_in_span_input__uses_composite_shape(self):
        trace = _trace()
        spans = [_span("tool", input={"k": "BOOM in body"})]
        ctx = _ctx(trace, spans)
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": trace.id, "pattern": "boom"}),
            ctx,
        )

        body = result.split("\n", 1)[1]
        # Single match in the fixture — strict equality catches accidental
        # extra matches or path-rendering drift.
        assert body == ".spans[0].input.k: BOOM in body"

    def test_search__different_case_pattern__matches_case_insensitive(self):
        trace = _trace(input={"prompt": "HELLO"})
        ctx = _ctx(trace, [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": trace.id, "pattern": "hello"}),
            ctx,
        )

        body = result.split("\n", 1)[1]
        assert body == ".trace.input.prompt: HELLO"

    def test_search__regex_metacharacters__match_as_regex(self):
        trace = _trace(input={"prompt": "v1.2.3"})
        ctx = _ctx(trace, [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": trace.id, "pattern": r"v\d+\.\d+\.\d+"}),
            ctx,
        )

        body = result.split("\n", 1)[1]
        assert body == ".trace.input.prompt: v1.2.3"


# ---------------------------------------------------------------------------
# Path narrowing
# ---------------------------------------------------------------------------


class TestPathNarrowing:
    def test_search__path_argument__restricts_search_scope(self):
        # `target` lives in spans[1].input — `path=.spans[1]` should find it,
        # but `path=.spans[0]` should not.
        trace = _trace()
        spans = [
            _span("a", input={"k": "target string"}),
            _span("b", input={"k": "no hits here"}),
        ]
        ctx = _ctx(trace, spans)
        tool = search_module.SearchTool()

        first = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": trace.id,
                    "pattern": "target",
                    "path": ".spans[0]",
                }
            ),
            ctx,
        )
        second = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": trace.id,
                    "pattern": "target",
                    "path": ".spans[1]",
                }
            ),
            ctx,
        )

        # Path narrows to spans[0] for `first`; the single match must be
        # the only body line and must stay anchored at the entity root.
        assert first.split("\n", 1)[1] == ".spans[0].input.k: target string"
        assert second.split("\n", 1)[1] == "<no matches>"

    def test_search__path_narrowed_match__paths_remain_rooted_at_entity(self):
        # Even when narrowed via `path=.spans[0]`, surfaced match paths
        # must reference the full entity-rooted location so the agent can
        # paste them into `scan` against the same entity.
        trace = _trace()
        spans = [_span("a", input={"k": "needle"})]
        ctx = _ctx(trace, spans)
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": trace.id,
                    "pattern": "needle",
                    "path": ".spans[0]",
                }
            ),
            ctx,
        )

        body = result.split("\n", 1)[1]
        # Only one matching string in the fixture; strict equality keeps
        # the anchor-rooted path expectation tight.
        assert body == ".spans[0].input.k: needle"

    def test_search__path_missing_leading_dot__auto_prepended(self):
        """Regression: models sometimes drop the leading `.` on the
        optional `path` argument (e.g. `spans[0]` instead of `.spans[0]`).
        Search routes `path` through `path_evaluator.normalize_expression`,
        so the call succeeds rather than bouncing back with an error.
        """
        trace = _trace()
        spans = [_span("a", input={"k": "target string"})]
        ctx = _ctx(trace, spans)
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": trace.id,
                    "pattern": "target",
                    "path": "spans[0]",  # missing leading `.`
                }
            ),
            ctx,
        )

        assert "ERROR" not in result
        assert result.split("\n", 1)[1] == ".spans[0].input.k: target string"

    def test_search__pattern_is_not_normalized(self):
        """Sanity: only the path expression is normalized. The regex
        `pattern` is opaque to the grammar and must not be touched —
        otherwise patterns starting with an identifier (very common)
        would silently get a leading `.` prepended and stop matching.
        """
        trace = _trace(input={"k": "target string"})
        ctx = _ctx(trace, [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": trace.id,
                    "pattern": "target",  # would break if rewritten to `.target`
                }
            ),
            ctx,
        )

        # Original pattern still matches; envelope echoes it verbatim.
        assert "pattern='target'" in result
        assert ".trace.input.k: target string" in result

    def test_search__unsupported_path_form__returns_structured_error(self):
        # Recursive descent isn't allowed as a search-narrowing path.
        ctx = _ctx(_trace(), [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": "t-1",
                    "pattern": "x",
                    "path": "..",
                }
            ),
            ctx,
        )

        # `..` traversals as a `path` are explicitly rejected with a
        # structured error pointing the agent to `scan`.
        assert result == (
            "[search: trace:t-1 | pattern='x' | path='..' | ERROR]\n"
            "Unsupported path expression: search `path` argument supports "
            "field access, index, and `[]` iteration only — use `scan` for "
            "slices and `..` traversals. See prompt examples."
        )


# ---------------------------------------------------------------------------
# Output caps
# ---------------------------------------------------------------------------


class TestCaps:
    def test_search__long_match_value__truncates_keeping_head_and_dropped_count(self):
        long = "abc " + ("x" * 500)  # match is at the head
        trace = _trace(input={"prompt": long})
        ctx = _ctx(trace, [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": trace.id, "pattern": "abc"}),
            ctx,
        )

        body = result.split("\n", 1)[1]
        # 200-char head + " [+N chars]" suffix; computed explicitly so a
        # change in either the truncation length or the suffix format is
        # caught immediately.
        head = long[: search_module.VALUE_TRUNCATION_LENGTH]
        dropped = len(long) - search_module.VALUE_TRUNCATION_LENGTH
        assert body == f".trace.input.prompt: {head} [+{dropped:,} chars]"

    def test_search__exceeds_match_limit__drops_extras_with_suffix(self):
        # Create > MAX_MATCHES matching spans.
        trace = _trace()
        spans = [_span(f"s-{i}", input={"k": "hit"}) for i in range(55)]
        ctx = _ctx(trace, spans)
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": trace.id, "pattern": "hit"}),
            ctx,
        )

        body = result.split("\n", 1)[1]
        # Expected: 50 lines `.spans[i].input.k: hit` then the
        # match-limit suffix (whose leading `\n` joins onto the body).
        expected_lines = [
            f".spans[{i}].input.k: hit" for i in range(search_module.MAX_MATCHES)
        ]
        expected_body = "\n".join(expected_lines) + search_module.MATCH_LIMIT_SUFFIX
        assert body == expected_body


# ---------------------------------------------------------------------------
# Envelope format
# ---------------------------------------------------------------------------


class TestEnvelope:
    def test_search__ok_response_without_path__envelope_format(self):
        trace = _trace(input={"prompt": "hello"})
        ctx = _ctx(trace, [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": trace.id, "pattern": "hello"}),
            ctx,
        )

        header = result.splitlines()[0]
        assert header == "[search: trace:t-1 | pattern='hello']"

    def test_search__ok_response_with_path__envelope_format(self):
        trace = _trace(input={"prompt": "hello"})
        ctx = _ctx(trace, [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps(
                {
                    "type": "trace",
                    "id": trace.id,
                    "pattern": "hello",
                    "path": ".trace.input",
                }
            ),
            ctx,
        )

        header = result.splitlines()[0]
        assert header == "[search: trace:t-1 | pattern='hello' | path='.trace.input']"

    def test_search__error_response__envelope_includes_error_tag(self):
        ctx = _ctx(_trace(), [])
        tool = search_module.SearchTool()

        result = tool.execute(
            json.dumps({"type": "trace", "id": "missing", "pattern": "x"}),
            ctx,
        )

        header = result.splitlines()[0]
        assert header == "[search: trace:missing | pattern='x' | ERROR]"
