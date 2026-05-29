"""Unit tests for Phase 2 compression primitives.

Covers `tier`, `tokens`, `string_truncator`, and `path_aware_truncator`.
These are the building blocks the per-entity compressors will compose
on top of, so the assertions here are deliberately tight.
"""

import dataclasses

import pytest

from opik.evaluation.suite_evaluators.agentic.compression import (
    path_aware_truncator,
    string_truncator,
    tier,
    tokens,
)


class TestCompressionTier:
    def test_compression_tier__enum_values__match_backend_vocabulary(self):
        assert tier.CompressionTier.FULL.value == "FULL"
        assert tier.CompressionTier.MEDIUM.value == "MEDIUM"
        assert tier.CompressionTier.SKELETON.value == "SKELETON"
        assert tier.CompressionTier.SUMMARY.value == "SUMMARY"

    def test_compression_result__mutation_attempt__raises_frozen_error(self):
        result = tier.CompressionResult(
            payload={"id": "x"}, tier=tier.CompressionTier.FULL
        )
        with pytest.raises(dataclasses.FrozenInstanceError):
            result.__setattr__("tier", tier.CompressionTier.MEDIUM)


class TestEstimateTokens:
    def test_estimate_tokens__string_input__uses_length_over_four(self):
        # 16 chars → 4 tokens.
        assert tokens.estimate_tokens("a" * 16) == 4

    def test_estimate_tokens__short_string__returns_zero(self):
        assert tokens.estimate_tokens("abc") == 0

    def test_estimate_tokens__dict_input__json_rendered_first(self):
        # `{"k": "v"}` → 10 chars → 2 tokens.
        assert tokens.estimate_tokens({"k": "v"}) == 2

    def test_estimate_tokens__non_serializable_value__falls_back_to_str(self):
        # Datetime-like values are tolerated via default=str; non-JSON
        # objects fall back to str() — should not raise.
        class _Opaque:
            def __str__(self):
                return "x" * 20

        assert tokens.estimate_tokens(_Opaque()) == 5


class TestStringTruncator:
    def test_truncate__short_string__passed_through(self):
        assert string_truncator.truncate("hello", limit=10, scan_path=".") == "hello"

    def test_truncate__long_string__carries_scan_hint(self):
        result = string_truncator.truncate("x" * 30, limit=10, scan_path=".foo")

        assert result.startswith("x" * 10)
        assert "TRUNCATED" in result
        assert "scan('.foo')" in result
        assert "20 chars" in result  # 30 - 10 dropped

    def test_truncate__missing_scan_path__defaults_to_root_jq_form(self):
        result = string_truncator.truncate("x" * 30, limit=5, scan_path=None)
        assert "scan('.')" in result


class TestPathAwareTruncator:
    def test_truncate_strings__short_strings__unchanged(self):
        payload = {"a": "hi", "b": ["world"]}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=10)

        assert out == payload

    def test_truncate_strings__long_string_inside_object__truncated_with_field_path(
        self,
    ):
        payload = {"input": "x" * 50}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=10)

        # Head of the original retained, but the full value is gone — the
        # head should be exactly 10 x's followed by the truncation suffix.
        assert out["input"] != payload["input"]
        assert out["input"][:10] == "x" * 10
        assert out["input"][10] != "x"  # suffix kicks in right after the head
        assert "scan('.input')" in out["input"]

    def test_truncate_strings__long_string_inside_nested_array__truncated_with_index_path(
        self,
    ):
        payload = {"spans": [{"output": "y" * 80}, {"output": "ok"}]}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=10)

        truncated = out["spans"][0]["output"]
        assert truncated != payload["spans"][0]["output"]
        assert truncated[:10] == "y" * 10
        assert truncated[10] != "y"
        assert "scan('.spans[0].output')" in truncated
        # Second element fits under the limit, stays as-is.
        assert out["spans"][1]["output"] == "ok"

    def test_truncate_strings__non_identifier_keys__use_bracket_quoted_path(self):
        payload = {"a-b": "z" * 50}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=5)

        assert "scan('[\"a-b\"]')" in out["a-b"]

    def test_truncate_strings__root_level_string__uses_root_jq_path(self):
        out = path_aware_truncator.truncate_strings("z" * 50, max_string_chars=5)

        assert out != "z" * 50
        assert out[:5] == "z" * 5
        assert out[5] != "z"
        assert "scan('.')" in out

    def test_truncate_strings__non_string_values__pass_through(self):
        payload = {"n": 42, "b": True, "x": None, "f": 3.14}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=1)

        assert out == payload

    def test_truncate_strings__tuple_input__coerced_to_list(self):
        out = path_aware_truncator.truncate_strings(("a", "b"), max_string_chars=10)
        assert out == ["a", "b"]
