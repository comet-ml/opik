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
        assert _extract_score(result, PASS_RATE) == 0.0

    def test_no_test_results_attr(self):
        result = MagicMock(spec=[])
        assert _extract_score(result, PASS_RATE) == 0.0

    def test_single_item_passing(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [1.0])]
        assert _extract_score(result, PASS_RATE) == 1.0

    def test_single_item_failing(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [0.8])]
        assert _extract_score(result, PASS_RATE) == 0.0

    def test_two_items_one_passes_one_fails(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0]),
            _make_test_result("item-2", [0.5]),
        ]
        assert _extract_score(result, PASS_RATE) == 0.5

    def test_multiple_runs_per_item(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0], trial_id="t-1"),
            _make_test_result("item-1", [0.0], trial_id="t-2"),
            _make_test_result("item-2", [1.0], trial_id="t-1"),
        ]
        assert _extract_score(result, PASS_RATE) == 1.0

    def test_no_score_results_counts_as_pass(self):
        tr = MagicMock()
        tr.test_case = MagicMock()
        tr.test_case.dataset_item_id = "item-1"
        tr.test_case.dataset_item = None
        tr.score_results = []
        tr.trial_id = "t-1"

        result = MagicMock()
        result.test_results = [tr]
        assert _extract_score(result, PASS_RATE) == 1.0

    def test_boolean_true_passes(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [True])]
        assert _extract_score(result, PASS_RATE) == 1.0

    def test_boolean_false_fails(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [False])]
        assert _extract_score(result, PASS_RATE) == 0.0

    def test_mixed_items(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [1.0, 0.0]),
            _make_test_result("item-3", [1.0]),
            _make_test_result("item-4", [0.0]),
        ]
        assert _extract_score(result, PASS_RATE) == pytest.approx(0.5)


class TestExtractScoreBlended:
    """Tests for strategy='blended' (assertion-level tiebreaker)."""

    def test_same_pass_rate_different_assertion_progress(self):
        # Both have pass_rate=0.5 (1/2 items), but different assertion rates
        result_a = MagicMock()
        result_a.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),  # passes
            _make_test_result("item-2", [0.0, 0.0]),  # fails, 0/2 assertions
        ]
        result_b = MagicMock()
        result_b.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),  # passes
            _make_test_result("item-2", [1.0, 0.0]),  # fails, 1/2 assertions
        ]
        cfg = ScoringConfig()
        score_a = _extract_score(result_a, cfg)
        score_b = _extract_score(result_b, cfg)
        assert score_b > score_a

    def test_pass_rate_always_dominates(self):
        # 1/2 items pass with perfect assertion rate vs 2/2 items pass with min assertions
        result_lower_pass_rate = MagicMock()
        result_lower_pass_rate.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),  # passes
            _make_test_result("item-2", [1.0, 0.0]),  # fails (best possible for failing item)
        ]
        result_higher_pass_rate = MagicMock()
        result_higher_pass_rate.test_results = [
            _make_test_result("item-1", [1.0]),  # passes
            _make_test_result("item-2", [1.0]),  # passes
        ]
        cfg = ScoringConfig()
        assert _extract_score(result_higher_pass_rate, cfg) > _extract_score(result_lower_pass_rate, cfg)

    def test_auto_epsilon_value(self):
        # 2 items: epsilon = 1/(2+1) = 1/3
        # pass_rate = 0.5, assertion_rate = 3/4
        # blended = 1.0 * 0.5 + (1/3) * 0.75
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),  # passes, 2/2
            _make_test_result("item-2", [1.0, 0.0]),  # fails, 1/2
        ]
        cfg = ScoringConfig()
        expected = 1.0 * 0.5 + (1 / 3) * (3 / 4)
        assert _extract_score(result, cfg) == pytest.approx(expected)

    def test_explicit_weights(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),  # passes
            _make_test_result("item-2", [1.0, 0.0]),  # fails
        ]
        # pass_rate = 0.5, assertion_rate = 3/4
        cfg = ScoringConfig(pass_rate_weight=2.0, assertion_rate_weight=0.5)
        expected = 2.0 * 0.5 + 0.5 * (3 / 4)
        assert _extract_score(result, cfg) == pytest.approx(expected)

    def test_all_passing_score_exceeds_one(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0]),
            _make_test_result("item-2", [1.0]),
        ]
        cfg = ScoringConfig()
        # pass_rate=1.0, assertion_rate=1.0, epsilon=1/3
        # blended = 1.0 + 1/3 * 1.0 = 1.333...
        score = _extract_score(result, cfg)
        assert score > 1.0
        assert score == pytest.approx(1.0 + 1 / 3)

    def test_single_item_epsilon(self):
        # 1 item: epsilon = 1/(1+1) = 0.5
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 0.0]),  # fails
        ]
        cfg = ScoringConfig()
        # pass_rate=0.0, assertion_rate=1/2
        expected = 0.0 + 0.5 * 0.5
        assert _extract_score(result, cfg) == pytest.approx(expected)

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
        # pass_rate=1.0 (no assertions = pass), assertion_rate=1.0 (default)
        # epsilon=1/(1+1)=0.5, blended = 1.0 + 0.5 * 1.0 = 1.5
        assert _extract_score(result, cfg) == pytest.approx(1.5)

    def test_empty_results_returns_zero(self):
        result = MagicMock()
        result.test_results = []
        assert _extract_score(result, ScoringConfig()) == 0.0
