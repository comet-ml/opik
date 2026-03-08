from unittest.mock import MagicMock

import pytest

from opik_optimizer_framework.experiment_executor import _extract_score


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


class TestExtractScore:
    def test_empty_results(self):
        result = MagicMock()
        result.test_results = []
        assert _extract_score(result) == 0.0

    def test_no_test_results_attr(self):
        result = MagicMock(spec=[])
        assert _extract_score(result) == 0.0

    def test_single_item_passing(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [1.0])]
        assert _extract_score(result) == 1.0

    def test_single_item_failing(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [0.8])]
        assert _extract_score(result) == 0.0

    def test_two_items_one_passes_one_fails(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0]),
            _make_test_result("item-2", [0.5]),
        ]
        assert _extract_score(result) == 0.5

    def test_multiple_runs_per_item(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0], trial_id="t-1"),
            _make_test_result("item-1", [0.0], trial_id="t-2"),
            _make_test_result("item-2", [1.0], trial_id="t-1"),
        ]
        # item-1: 2 runs, 1 passes → runs_passed=1 >= threshold=1 → passes
        # item-2: 1 run, passes → passes
        assert _extract_score(result) == 1.0

    def test_no_score_results_counts_as_pass(self):
        tr = MagicMock()
        tr.test_case = MagicMock()
        tr.test_case.dataset_item_id = "item-1"
        tr.test_case.dataset_item = None
        tr.score_results = []
        tr.trial_id = "t-1"

        result = MagicMock()
        result.test_results = [tr]
        assert _extract_score(result) == 1.0

    def test_boolean_true_passes(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [True])]
        assert _extract_score(result) == 1.0

    def test_boolean_false_fails(self):
        result = MagicMock()
        result.test_results = [_make_test_result("item-1", [False])]
        assert _extract_score(result) == 0.0

    def test_mixed_items(self):
        result = MagicMock()
        result.test_results = [
            _make_test_result("item-1", [1.0, 1.0]),
            _make_test_result("item-2", [1.0, 0.0]),
            _make_test_result("item-3", [1.0]),
            _make_test_result("item-4", [0.0]),
        ]
        # item-1: all truthy → pass
        # item-2: not all truthy (0.0 != 1) → fail
        # item-3: truthy → pass
        # item-4: not truthy → fail
        assert _extract_score(result) == pytest.approx(0.5)
