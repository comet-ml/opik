from opik.evaluation.metrics import score_result


def assert_score_result(
    result: score_result.ScoreResult, include_reason: bool = True
) -> None:
    assert result.scoring_failed is False
    assert isinstance(result.value, float)
    assert 0.0 <= result.value <= 1.0
    if include_reason:
        assert isinstance(result.reason, str)
        assert len(result.reason) > 0
