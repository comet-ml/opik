"""Unit tests for opik_backend.studio.types helpers."""

from types import SimpleNamespace

from opik_backend.studio.types import extract_scoring_health


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
