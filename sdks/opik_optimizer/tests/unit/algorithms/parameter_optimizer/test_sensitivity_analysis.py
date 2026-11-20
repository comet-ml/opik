"""
Unit tests for opik_optimizer.algorithms.parameter_optimizer.ops.sensitivity_ops module.

Tests cover:
- sensitivity_analysis: Correlation-based sensitivity calculation
"""

from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import ParameterSpec
from opik_optimizer.algorithms.parameter_optimizer.types import ParameterType
from opik_optimizer.algorithms.parameter_optimizer.ops.sensitivity_ops import (
    sensitivity_analysis,
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


class TestSensitivityAnalysis:
    """Tests for sensitivity_analysis function."""

    def test_returns_zero_for_constant_parameter(self) -> None:
        """Should return zero when parameter value doesn't vary."""
        trials = [
            MockTrial({"param": 0.5}, 0.6),
            MockTrial({"param": 0.5}, 0.7),
            MockTrial({"param": 0.5}, 0.8),
        ]
        specs = [_make_parameter_spec("param")]

        result = sensitivity_analysis(trials, specs)

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

        result = sensitivity_analysis(trials, specs)

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

        result = sensitivity_analysis(trials, specs)

        # Should be close to 0.0 (no correlation when score is constant)
        assert result["param"] == 0.0

    def test_skips_trials_with_none_value(self) -> None:
        """Should skip trials where value is None."""
        trials = [
            MockTrial({"param": 0.1}, 0.1),
            MockTrial({"param": 0.5}, None),  # Should be skipped
            MockTrial({"param": 0.9}, 0.9),
        ]
        specs = [_make_parameter_spec("param")]

        result = sensitivity_analysis(trials, specs)

        # Should still compute correlation from valid trials
        assert result["param"] > 0.9
