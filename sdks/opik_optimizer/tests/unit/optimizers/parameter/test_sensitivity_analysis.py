"""
Unit tests for opik_optimizer.algorithms.parameter_optimizer.sensitivity_analysis module.

Tests cover:
- compute_sensitivity_from_trials: Correlation-based sensitivity calculation
"""

from opik_optimizer.algorithms.parameter_optimizer.parameter_spec import ParameterSpec
from opik_optimizer.algorithms.parameter_optimizer.search_space_types import (
    ParameterType,
)
from opik_optimizer.algorithms.parameter_optimizer.sensitivity_analysis import (
    compute_sensitivity_from_trials,
)


class MockTrial:
    """Mock Optuna Trial for testing."""

    def __init__(self, params: dict, value: float | None):
        self.params = params
        self.value = value


def _make_parameter_spec(name: str) -> ParameterSpec:
    """Create a minimal float ParameterSpec used for tests."""
    return ParameterSpec(
        name=name,
        type=ParameterType.FLOAT,
        min=0.0,
        max=1.0,
    )


class TestComputeSensitivityFromTrials:
    """Tests for compute_sensitivity_from_trials function."""

    def test_returns_empty_for_no_specs(self) -> None:
        """Should return empty dict when no specs provided."""
        trials = [MockTrial({"param": 0.5}, 0.8)]

        result = compute_sensitivity_from_trials(trials, [])

        assert result == {}

    def test_returns_zero_for_single_trial(self) -> None:
        """Should return zero sensitivity for single trial."""
        trials = [MockTrial({"param": 0.5}, 0.8)]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        assert result["param"] == 0.0

    def test_returns_zero_for_constant_parameter(self) -> None:
        """Should return zero when parameter value doesn't vary."""
        trials = [
            MockTrial({"param": 0.5}, 0.6),
            MockTrial({"param": 0.5}, 0.7),
            MockTrial({"param": 0.5}, 0.8),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        assert result["param"] == 0.0

    def test_high_correlation_gives_high_sensitivity(self) -> None:
        """Should return high sensitivity for strongly correlated parameter."""
        # Perfect positive correlation: higher param = higher score
        trials = [
            MockTrial({"param": 0.1}, 0.1),
            MockTrial({"param": 0.3}, 0.3),
            MockTrial({"param": 0.5}, 0.5),
            MockTrial({"param": 0.7}, 0.7),
            MockTrial({"param": 0.9}, 0.9),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        # Should be close to 1.0 (high correlation)
        assert result["param"] > 0.9

    def test_no_correlation_gives_low_sensitivity(self) -> None:
        """Should return low sensitivity for uncorrelated parameter."""
        # No correlation: random relationship
        trials = [
            MockTrial({"param": 0.1}, 0.5),
            MockTrial({"param": 0.9}, 0.5),
            MockTrial({"param": 0.5}, 0.5),
            MockTrial({"param": 0.3}, 0.5),
            MockTrial({"param": 0.7}, 0.5),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        # Should be close to 0.0 (no correlation when score is constant)
        assert result["param"] == 0.0

    def test_handles_negative_correlation(self) -> None:
        """Should handle negative correlation (uses absolute value)."""
        # Perfect negative correlation: higher param = lower score
        trials = [
            MockTrial({"param": 0.1}, 0.9),
            MockTrial({"param": 0.3}, 0.7),
            MockTrial({"param": 0.5}, 0.5),
            MockTrial({"param": 0.7}, 0.3),
            MockTrial({"param": 0.9}, 0.1),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        # Should still be high (absolute correlation)
        assert result["param"] > 0.9

    def test_handles_boolean_parameters(self) -> None:
        """Should convert boolean parameters correctly."""
        trials = [
            MockTrial({"param": True}, 0.8),
            MockTrial({"param": False}, 0.3),
            MockTrial({"param": True}, 0.9),
            MockTrial({"param": False}, 0.2),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        # True (1.0) correlates with high scores
        assert result["param"] > 0.5

    def test_handles_integer_parameters(self) -> None:
        """Should handle integer parameters correctly."""
        trials = [
            MockTrial({"param": 1}, 0.2),
            MockTrial({"param": 2}, 0.4),
            MockTrial({"param": 3}, 0.6),
            MockTrial({"param": 4}, 0.8),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        assert result["param"] > 0.9

    def test_skips_trials_with_none_value(self) -> None:
        """Should skip trials where value is None."""
        trials = [
            MockTrial({"param": 0.1}, 0.1),
            MockTrial({"param": 0.5}, None),  # Should be skipped
            MockTrial({"param": 0.9}, 0.9),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        # Should still compute correlation from valid trials
        assert result["param"] > 0.9

    def test_skips_non_numeric_parameters(self) -> None:
        """Should return 0.0 sensitivity for parameters with non-numeric values."""
        trials = [
            MockTrial({"param": "string_value"}, 0.5),
            MockTrial({"param": "another_string"}, 0.7),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        # Non-numeric values are skipped, resulting in len(values) < 2, so sensitivity is 0.0
        assert result["param"] == 0.0

    def test_handles_multiple_parameters(self) -> None:
        """Should compute sensitivity for multiple parameters."""
        trials = [
            MockTrial({"param_a": 0.1, "param_b": 0.9}, 0.5),
            MockTrial({"param_a": 0.5, "param_b": 0.5}, 0.5),
            MockTrial({"param_a": 0.9, "param_b": 0.1}, 0.5),
        ]
        specs = [_make_parameter_spec("param_a"), _make_parameter_spec("param_b")]

        result = compute_sensitivity_from_trials(trials, specs)

        assert "param_a" in result
        assert "param_b" in result

    def test_clamps_sensitivity_to_0_1(self) -> None:
        """Sensitivity should be clamped between 0 and 1."""
        # Normal case
        trials = [
            MockTrial({"param": 0.1}, 0.2),
            MockTrial({"param": 0.9}, 0.8),
        ]
        specs = [_make_parameter_spec("param")]

        result = compute_sensitivity_from_trials(trials, specs)

        assert 0.0 <= result["param"] <= 1.0

    def test_handles_missing_parameter_in_trial(self) -> None:
        """Should handle trials missing the parameter."""
        trials = [
            MockTrial({"param": 0.5}, 0.5),
            MockTrial({}, 0.6),  # Missing param
            MockTrial({"param": 0.7}, 0.7),
        ]
        specs = [_make_parameter_spec("param")]

        # Should not crash, just use available data
        result = compute_sensitivity_from_trials(trials, specs)

        assert "param" in result
