import pytest

from ..utils.metrics import (
    build_metric_function,
    build_multi_metric_objective,
    get_metric_definition,
    normalized_weights,
)


def test_metric_function_uses_evaluation_data():
    evaluation_data = {
        "composite_value": 0.42,
        "metrics": {
            "arc_agi2_exact": 0.8,
            "arc_agi2_approx_match": 0.6,
            "arc_agi2_label_iou": 0.5,
        },
        "reason": "ok",
        "metadata": {"foo": "bar"},
    }

    calls = []

    def evaluation_fn(dataset_item, llm_output):  # noqa: D401 - simple stub
        calls.append((dataset_item, llm_output))
        return evaluation_data

    def handle_exception(name, exc):  # pragma: no cover - not hit
        pytest.fail(f"Unexpected exception for {name}: {exc}")

    definition = get_metric_definition("arc_agi2_exact")
    metric = build_metric_function(definition, evaluation_fn, handle_exception)
    result = metric({}, "dummy")

    assert result.name == "arc_agi2_exact"
    assert result.value == evaluation_data["metrics"]["arc_agi2_exact"]
    assert result.reason == evaluation_data["reason"]
    assert result.metadata == evaluation_data["metadata"]
    assert calls, "evaluation_fn should be invoked"


def test_normalized_weights_sum_to_one():
    weights = normalized_weights(
        ["arc_agi2_exact", "arc_agi2_approx_match", "arc_agi2_label_iou"]
    )
    assert pytest.approx(sum(weights), rel=1e-9) == 1.0


def test_build_multi_metric_objective_returns_expected_shape():
    def evaluation_fn(dataset_item, llm_output):  # pragma: no cover - simple stub
        return {
            "composite_value": 1.0,
            "metrics": {
                "arc_agi2_exact": 1.0,
                "arc_agi2_approx_match": 1.0,
                "arc_agi2_label_iou": 1.0,
            },
        }

    def handle_exception(name, exc):  # pragma: no cover - not triggered
        raise exc

    objective = build_multi_metric_objective(
        ["arc_agi2_exact", "arc_agi2_label_iou"],
        evaluation_fn,
        handle_exception,
        objective_name="test",
    )

    assert len(objective.metrics) == 2
    assert pytest.approx(sum(objective.weights), rel=1e-9) == 1.0
