"""Unit tests for the SDK `scan` path evaluator (design doc §5.3).

Every supported grammar form has at least one round-trip test; every
unsupported form has a parse-error test, so the prompt-taught surface
can't drift silently.
"""

import pytest

from opik.evaluation.suite_evaluators.agentic.tools import path_evaluator


# Fixtures --------------------------------------------------------------------


def _trace_doc():
    return {
        "trace": {
            "id": "t-1",
            "name": "agent",
            "input": {"prompt": "hello"},
            "output": {"answer": "world"},
        },
        "spans": [
            {
                "id": "root",
                "name": "agent.run",
                "type": "general",
                "parent_span_id": None,
                "input": {"q": "hi"},
                "output": "ok",
                "error_info": None,
            },
            {
                "id": "tool",
                "name": "tool_call",
                "type": "tool",
                "parent_span_id": "root",
                "input": {"k": 1},
                "output": {"is_error": False},
            },
            {
                "id": "err",
                "name": "broken",
                "type": "general",
                "parent_span_id": "root",
                "input": None,
                "output": None,
                "error_info": {"message": "boom"},
            },
        ],
    }


# Root and field access -------------------------------------------------------


class TestBasicForms:
    def test_root_returns_whole_value(self):
        doc = _trace_doc()
        assert path_evaluator.evaluate(".", doc) == [doc]

    def test_dotted_field_chain(self):
        results = path_evaluator.evaluate(".trace.input.prompt", _trace_doc())
        assert results == ["hello"]

    def test_missing_field_returns_empty(self):
        # Per the grammar, missing keys yield no results — not an error.
        # The caller can decide whether emptiness is meaningful.
        assert path_evaluator.evaluate(".trace.nonexistent", _trace_doc()) == []


# Index and slice -------------------------------------------------------------


class TestIndexAndSlice:
    def test_positive_index(self):
        assert path_evaluator.evaluate(".spans[1].id", _trace_doc()) == ["tool"]

    def test_negative_index_counts_from_end(self):
        assert path_evaluator.evaluate(".spans[-1].id", _trace_doc()) == ["err"]

    def test_out_of_range_index_yields_nothing(self):
        assert path_evaluator.evaluate(".spans[99]", _trace_doc()) == []

    def test_slice_with_both_bounds(self):
        results = path_evaluator.evaluate(".spans[1:3]", _trace_doc())
        assert len(results) == 1  # slice produces one list value
        assert [s["id"] for s in results[0]] == ["tool", "err"]

    def test_slice_open_end(self):
        results = path_evaluator.evaluate(".spans[1:]", _trace_doc())
        assert [s["id"] for s in results[0]] == ["tool", "err"]

    def test_slice_open_start(self):
        results = path_evaluator.evaluate(".spans[:2]", _trace_doc())
        assert [s["id"] for s in results[0]] == ["root", "tool"]


# Iteration -------------------------------------------------------------------


class TestIterate:
    def test_iterate_yields_each_element(self):
        results = path_evaluator.evaluate(".spans[]", _trace_doc())
        assert [r["id"] for r in results] == ["root", "tool", "err"]

    def test_iterate_then_field(self):
        results = path_evaluator.evaluate(".spans[].name", _trace_doc())
        assert results == ["agent.run", "tool_call", "broken"]


# Recursive descent -----------------------------------------------------------


class TestRecursiveDescent:
    def test_recursive_descent_emits_root_and_descendants(self):
        doc = {"a": 1, "b": {"c": 2}}
        results = path_evaluator.evaluate("..", doc)
        # Root, value 1, dict {c:2}, value 2.
        assert doc in results
        assert 1 in results
        assert {"c": 2} in results
        assert 2 in results

    def test_strings_filter_keeps_only_strings(self):
        doc = {"a": "hello", "b": 42, "c": ["world", 1]}
        results = path_evaluator.evaluate("..|strings", doc)
        assert sorted(results) == ["hello", "world"]

    def test_select_with_key_present(self):
        # `..|select(.error_info?)` finds nodes that have an `error_info` key,
        # regardless of value — that matches the prompt-taught pattern for
        # "find spans with error info present".
        results = path_evaluator.evaluate("..|select(.error_info?)", _trace_doc())
        # First and third span dict has the key, so they match, plus the
        # surrounding span objects themselves are dicts that contain it.
        ids = {r["id"] for r in results if isinstance(r, dict) and "id" in r}
        assert ids == {"root", "err"}

    def test_select_with_equality(self):
        results = path_evaluator.evaluate(
            '..|select(.name == "tool_call")', _trace_doc()
        )
        # Should match the span dict whose name is `tool_call`.
        names = [r["name"] for r in results if isinstance(r, dict)]
        assert names == ["tool_call"]

    def test_select_with_inequality(self):
        results = path_evaluator.evaluate('..|select(.type != "tool")', _trace_doc())
        # Spans of type general → root and err.
        ids = {
            r["id"]
            for r in results
            if isinstance(r, dict) and r.get("id") in {"root", "tool", "err"}
        }
        assert ids == {"root", "err"}

    def test_select_with_and_or_not(self):
        # type != tool AND error_info is truthy → just the `err` span.
        results = path_evaluator.evaluate(
            '..|select(.type != "tool" and .error_info)', _trace_doc()
        )
        ids = {r["id"] for r in results if isinstance(r, dict) and "id" in r}
        assert ids == {"err"}

    def test_select_with_or(self):
        results = path_evaluator.evaluate(
            '..|select(.id == "tool" or .id == "err")', _trace_doc()
        )
        ids = {r["id"] for r in results if isinstance(r, dict) and "id" in r}
        assert ids == {"tool", "err"}

    def test_select_with_not(self):
        results = path_evaluator.evaluate(
            '..|select(not (.type == "tool"))', _trace_doc()
        )
        ids = {
            r["id"]
            for r in results
            if isinstance(r, dict) and r.get("id") in {"root", "tool", "err"}
        }
        assert ids == {"root", "err"}


# Parse-time errors -----------------------------------------------------------


class TestUnsupportedSyntax:
    @pytest.mark.parametrize(
        "expression",
        [
            "foo",  # missing leading dot
            ".foo +",  # arithmetic not supported
            ".foo | length",  # pipe outside of `..`
            ".foo[",  # unterminated bracket
            "..|wat",  # unknown post-descent filter
            ".foo == ",  # equality without literal
            ".foo as $x",  # bindings not supported
        ],
    )
    def test_unsupported_expression_raises_path_error(self, expression):
        with pytest.raises(path_evaluator.PathError):
            path_evaluator.parse(expression)


# Runtime guards --------------------------------------------------------------


class TestGuards:
    def test_recursion_depth_limit_fires(self):
        # Build a deeply nested dict: {"v": {"v": {"v": ...}}}
        nest = {}
        cursor = nest
        for _ in range(300):
            cursor["v"] = {}
            cursor = cursor["v"]
        with pytest.raises(path_evaluator.PathLimitError):
            path_evaluator.evaluate("..", nest, max_depth=200)

    def test_result_count_limit_fires(self):
        # `..` walks every descendant; a 50k-element list trips the guard
        # well before exhausting the generator.
        big = {"items": list(range(50_000))}
        with pytest.raises(path_evaluator.PathLimitError):
            path_evaluator.evaluate("..", big, max_results=10)
