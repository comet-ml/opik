from unittest.mock import MagicMock

import pytest

from opik_optimizer_framework.experiment_executor import _extract_score
from opik_optimizer_framework.types import ScoringConfig

PASS_RATE = ScoringConfig(strategy="pass_rate")


def _make_test_result(dataset_item_id, score_values, trial_id="trial-0"):
    """Build a mock test result compatible with build_suite_result."""
    test_case = MagicMock()
    test_case.dataset_item_id = dataset_item_id
    test_case.dataset_item = None

    score_results = []
    for val in score_values:
        sr = MagicMock()
        sr.value = val
        score_results.append(sr)

    tr = MagicMock()
    tr.test_case = test_case
    tr.score_results = score_results
    tr.trial_id = trial_id
    return tr


class TestExtractScorePassRate:
    """Tests for strategy='pass_rate' (legacy behavior)."""

    def test_empty_results(self):
        result = MagicMock()
        result.test_results = []
        score, display = _extract_score(result, PASS_RATE)
        assert score == 0.0
        assert display == 0.0

    def test_no_test_results_attr(self):
        result = MagicMock(spec=[])
        score, display = _extract_score(result, PASS_RATE)
        assert score == 0.0
        assert display == 0.0

    def test_single_item_passing(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [1.0])]
        score, display = _extract_score(result, PASS_RATE)
        assert score == 1.0
        assert display == 1.0

    def test_single_item_failing(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [0.8])]
        score, display = _extract_score(result, PASS_RATE)
        assert score == 0.0
        assert display == 0.0

    def test_two_items_one_passes_one_fails(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0]),
            _make_test_result("item-2", [0.5]),
        ]
        score, display = _extract_score(result, PASS_RATE)
        assert score == 0.5
        assert display == 0.5

    def test_multiple_runs_per_item(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0], trial_id="t-1"),
            _make_test_result("item-1", [0.0], trial_id="t-2"),
            _make_test_result("item-2", [1.0], trial_id="t-1"),
        ]
        score, _ = _extract_score(result, PASS_RATE)
        assert score == 1.0

    def test_no_score_results_counts_as_pass(self):
        tr = MagicMock()
        tr.test_case = MagicMock()
        tr.test_case.dataset_item_id = "item-1"
        tr.test_case.dataset_item = None
        tr.score_results = []
        tr.trial_id = "t-1"

        result = MagicMock()
        result.test_results = [tr]
        score, _ = _extract_score(result, PASS_RATE)
        assert score == 1.0

    def test_boolean_true_passes(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [True])]
        score, _ = _extract_score(result, PASS_RATE)
        assert score == 1.0

    def test_boolean_false_fails(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [False])]
        score, _ = _extract_score(result, PASS_RATE)
        assert score == 0.0

    def test_mixed_items(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [1.0, 0.0]),
            _make_test_result("item-3", [1.0]),
            _make_test_result("item-4", [0.0]),
        ]
        score, _ = _extract_score(result, PASS_RATE)
        assert score == pytest.approx(0.5)


class TestExtractScoreBlended:
    """Tests for strategy='blended' (assertion-level tiebreaker)."""

    def test_same_pass_rate_different_assertion_progress(self):
        result_a = MagicMock()
        result_a.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [0.0, 0.0]),
        ]
        result_b = MagicMock()
        result_b.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [1.0, 0.0]),
        ]
        cfg = ScoringConfig()
        score_a, _ = _extract_score(result_a, cfg)
        score_b, _ = _extract_score(result_b, cfg)
        assert score_b > score_a

    def test_pass_rate_always_dominates(self):
        result_lower_pass_rate = MagicMock()
        result_lower_pass_rate.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [1.0, 0.0]),
        ]
        result_higher_pass_rate = MagicMock()
        result_higher_pass_rate.test_results = [
            _make_test_result("item-1", [1.0]),
            _make_test_result("item-2", [1.0]),
        ]
        cfg = ScoringConfig()
        score_low, _ = _extract_score(result_lower_pass_rate, cfg)
        score_high, _ = _extract_score(result_higher_pass_rate, cfg)
        assert score_high > score_low

    def test_auto_epsilon_value(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [1.0, 0.0]),
        ]
        cfg = ScoringConfig()
        expected = 1.0 * 0.5 + (1 / 3) * (3 / 4)
        score, _ = _extract_score(result, cfg)
        assert score == pytest.approx(expected)

    def test_explicit_weights(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [1.0, 0.0]),
        ]
        cfg = ScoringConfig(pass_rate_weight=2.0, assertion_rate_weight=0.5)
        expected = 2.0 * 0.5 + 0.5 * (3 / 4)
        score, _ = _extract_score(result, cfg)
        assert score == pytest.approx(expected)

    def test_all_passing_score_exceeds_one(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0]),
            _make_test_result("item-2", [1.0]),
        ]
        cfg = ScoringConfig()
        score, _ = _extract_score(result, cfg)
        assert score > 1.0
        assert score == pytest.approx(1.0 + 1 / 3)

    def test_single_item_epsilon(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 0.0]),
        ]
        cfg = ScoringConfig()
        expected = 0.0 + 0.5 * 0.5
        score, _ = _extract_score(result, cfg)
        assert score == pytest.approx(expected)

    def test_zero_assertions_defaults_to_one(self):
        tr = MagicMock()
        tr.test_case = MagicMock()
        tr.test_case.dataset_item_id = "item-1"
        tr.test_case.dataset_item = None
        tr.score_results = []
        tr.trial_id = "t-1"

        result = MagicMock()
        result.test_results = [tr]
        cfg = ScoringConfig()
        score, _ = _extract_score(result, cfg)
        assert score == pytest.approx(1.5)

    def test_empty_results_returns_zero(self):
        result = MagicMock()
        result.test_results = []
        score, display = _extract_score(result, ScoringConfig())
        assert score == 0.0
        assert display == 0.0

    def test_display_score_is_raw_pass_rate(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [1.0, 0.0]),
        ]
        cfg = ScoringConfig()
        score, display = _extract_score(result, cfg)
        assert display == pytest.approx(0.5)
        assert score > display

    def test_all_passing_display_score_is_one(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0]),
            _make_test_result("item-2", [1.0]),
        ]
        cfg = ScoringConfig()
        score, display = _extract_score(result, cfg)
        assert display == 1.0
        assert score > 1.0
