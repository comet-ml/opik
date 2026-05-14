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
    def test_tier_enum_values_match_backend_vocabulary(self):
        assert tier.CompressionTier.FULL.value == "FULL"
        assert tier.CompressionTier.MEDIUM.value == "MEDIUM"
        assert tier.CompressionTier.SKELETON.value == "SKELETON"
        assert tier.CompressionTier.SUMMARY.value == "SUMMARY"

    def test_compression_result_is_frozen(self):
        result = tier.CompressionResult(
            payload={"id": "x"}, tier=tier.CompressionTier.FULL
        )
        with pytest.raises(dataclasses.FrozenInstanceError):
            result.__setattr__("tier", tier.CompressionTier.MEDIUM)


class TestEstimateTokens:
    def test_string_uses_length_over_four(self):
        # 16 chars → 4 tokens.
        assert tokens.estimate_tokens("a" * 16) == 4

    def test_short_string_returns_zero(self):
        assert tokens.estimate_tokens("abc") == 0

    def test_dict_is_json_rendered_first(self):
        # `{"k": "v"}` → 10 chars → 2 tokens.
        assert tokens.estimate_tokens({"k": "v"}) == 2

    def test_non_serializable_falls_back_to_str(self):
        # Datetime-like values are tolerated via default=str; non-JSON
        # objects fall back to str() — should not raise.
        class _Opaque:
            def __str__(self):
                return "x" * 20

        assert tokens.estimate_tokens(_Opaque()) == 5


class TestStringTruncator:
    def test_short_string_passed_through(self):
        assert string_truncator.truncate("hello", limit=10, scan_path=".") == "hello"

    def test_truncated_string_carries_scan_hint(self):
        result = string_truncator.truncate("x" * 30, limit=10, scan_path=".foo")

        assert result.startswith("x" * 10)
        assert "TRUNCATED" in result
        assert "scan('.foo')" in result
        assert "20 chars" in result  # 30 - 10 dropped

    def test_missing_scan_path_defaults_to_root_jq_form(self):
        result = string_truncator.truncate("x" * 30, limit=5, scan_path=None)
        assert "scan('.')" in result


class TestPathAwareTruncator:
    def test_short_strings_unchanged(self):
        payload = {"a": "hi", "b": ["world"]}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=10)

        assert out == payload

    def test_long_string_inside_object_truncated_with_field_path(self):
        payload = {"input": "x" * 50}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=10)

        # Head of the original retained, but the full value is gone — the
        # head should be exactly 10 x's followed by the truncation suffix.
        assert out["input"] != payload["input"]
        assert out["input"][:10] == "x" * 10
        assert out["input"][10] != "x"  # suffix kicks in right after the head
        assert "scan('.input')" in out["input"]

    def test_long_string_inside_nested_array_truncated_with_index_path(self):
        payload = {"spans": [{"output": "y" * 80}, {"output": "ok"}]}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=10)

        truncated = out["spans"][0]["output"]
        assert truncated != payload["spans"][0]["output"]
        assert truncated[:10] == "y" * 10
        assert truncated[10] != "y"
        assert "scan('.spans[0].output')" in truncated
        # Second element fits under the limit, stays as-is.
        assert out["spans"][1]["output"] == "ok"

    def test_non_identifier_keys_use_bracket_quoted_path(self):
        payload = {"a-b": "z" * 50}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=5)

        assert "scan('[\"a-b\"]')" in out["a-b"]

    def test_root_level_string_uses_root_jq_path(self):
        out = path_aware_truncator.truncate_strings("z" * 50, max_string_chars=5)

        assert out != "z" * 50
        assert out[:5] == "z" * 5
        assert out[5] != "z"
        assert "scan('.')" in out

    def test_non_string_values_pass_through(self):
        payload = {"n": 42, "b": True, "x": None, "f": 3.14}

        out = path_aware_truncator.truncate_strings(payload, max_string_chars=1)

        assert out == payload

    def test_tuple_inputs_are_coerced_to_list(self):
        out = path_aware_truncator.truncate_strings(("a", "b"), max_string_chars=10)
        assert out == ["a", "b"]
