"""Unit tests for opik_backend.studio.types helpers."""

from types import SimpleNamespace

import pytest

from opik_backend.studio.types import (
    extract_completion_metadata,
    extract_finish_reason,
    extract_scoring_health,
)


def _result(details):
    """A stand-in for the SDK OptimizationResult (only .details is read)."""
    return SimpleNamespace(details=details)


class TestExtractScoringHealth:
    def test_valid_details_returns_counts(self):
        sh = extract_scoring_health(
            _result({"scoring_health": {"failed_count": 2, "total_count": 5}})
        )
        assert sh == {"failed_count": 2, "total_count": 5}

    def test_all_passed_zero_failed(self):
        sh = extract_scoring_health(
            _result({"scoring_health": {"failed_count": 0, "total_count": 7}})
        )
        assert sh == {"failed_count": 0, "total_count": 7}

    def test_missing_key_returns_none(self):
        assert extract_scoring_health(_result({"other": 1})) is None

    def test_missing_details_returns_none(self):
        # Older SDK: no details attribute at all.
        assert extract_scoring_health(SimpleNamespace()) is None

    def test_none_details_returns_none(self):
        assert extract_scoring_health(_result(None)) is None

    def test_non_dict_details_returns_none(self):
        assert extract_scoring_health(_result("not-a-dict")) is None

    def test_malformed_non_dict_scoring_health_returns_none(self):
        assert extract_scoring_health(_result({"scoring_health": "oops"})) is None

    def test_malformed_missing_total_returns_none(self):
        assert (
            extract_scoring_health(_result({"scoring_health": {"failed_count": 3}}))
            is None
        )

    def test_malformed_non_int_counts_returns_none(self):
        sh = extract_scoring_health(
            _result({"scoring_health": {"failed_count": "3", "total_count": 5}})
        )
        assert sh is None

    def test_never_raises_on_weird_result(self):
        # A result whose .details raises when accessed must not blow up completion.
        class Boom:
            @property
            def details(self):
                raise RuntimeError("boom")

        assert extract_scoring_health(Boom()) is None


class TestExtractFinishReason:
    @pytest.mark.parametrize(
        "reason",
        [
            "completed",
            "perfect_score",
            "max_trials",
            "no_improvement",
            "error",
            "cancelled",
        ],
    )
    def test_known_reasons_pass_through(self, reason):
        assert extract_finish_reason(_result({"finish_reason": reason})) == reason

    def test_unknown_reason_rejected(self):
        # Not on the SDK's FinishReason allowlist — must not reach metadata.
        assert extract_finish_reason(_result({"finish_reason": "gremlins"})) is None

    def test_non_string_reason_rejected(self):
        assert extract_finish_reason(_result({"finish_reason": 42})) is None

    def test_missing_key_returns_none(self):
        assert extract_finish_reason(_result({"other": 1})) is None

    def test_missing_details_returns_none(self):
        assert extract_finish_reason(SimpleNamespace()) is None

    def test_non_dict_details_returns_none(self):
        assert extract_finish_reason(_result("not-a-dict")) is None

    def test_never_raises_on_weird_result(self):
        class Boom:
            @property
            def details(self):
                raise RuntimeError("boom")

        assert extract_finish_reason(Boom()) is None


class TestExtractCompletionMetadata:
    """The combined payload the runner forwards on mark_completed."""

    def test_both_fields_present(self):
        metadata = extract_completion_metadata(
            _result(
                {
                    "scoring_health": {"failed_count": 1, "total_count": 4},
                    "finish_reason": "max_trials",
                }
            )
        )
        assert metadata == {
            "scoring_health": {"failed_count": 1, "total_count": 4},
            "finish_reason": "max_trials",
        }

    def test_finish_reason_only(self):
        # Older partial results must still forward what they have.
        metadata = extract_completion_metadata(
            _result({"finish_reason": "perfect_score"})
        )
        assert metadata == {"finish_reason": "perfect_score"}

    def test_scoring_health_only(self):
        metadata = extract_completion_metadata(
            _result({"scoring_health": {"failed_count": 0, "total_count": 2}})
        )
        assert metadata == {
            "scoring_health": {"failed_count": 0, "total_count": 2}
        }

    def test_neither_returns_empty_dict(self):
        assert extract_completion_metadata(_result({})) == {}
        assert extract_completion_metadata(SimpleNamespace()) == {}

    def test_invalid_values_dropped(self):
        metadata = extract_completion_metadata(
            _result({"scoring_health": "oops", "finish_reason": "gremlins"})
        )
        assert metadata == {}
