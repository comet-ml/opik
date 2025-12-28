from ..utils.code_evaluator import (
    EvaluationConfig,
    evaluate_arc_response,
)


def test_evaluate_arc_response_handles_missing_code_block():
    dataset_item = {
        "training_examples": [],
        "test_outputs": [],
        "test_inputs": [],
    }
    data = evaluate_arc_response(dataset_item, "no code block here", EvaluationConfig())
    assert data["composite_value"] == 0.0
    assert data["metrics"]["arc_agi2_exact"] == 0.0
    assert data["metrics"]["arc_agi2_approx_match"] == 0.0
    assert data["metrics"]["arc_agi2_label_iou"] == 0.0
    assert "reason" in data
