from opik.evaluation import report
from opik.evaluation.metrics import score_result


def test_display_experiment_results__marks_failed_experiment_score(capsys):
    report.display_experiment_results(
        dataset_name="ds",
        total_time=1.0,
        test_results=[],
        experiment_scores=[
            score_result.ScoreResult(name="f1_macro", value=0.0, scoring_failed=True)
        ],
    )

    out = capsys.readouterr().out

    assert "f1_macro" in out
    assert "failed" in out
    assert "0.0000" not in out


def test_display_experiment_results__shows_successful_experiment_score(capsys):
    report.display_experiment_results(
        dataset_name="ds",
        total_time=1.0,
        test_results=[],
        experiment_scores=[score_result.ScoreResult(name="f1_macro", value=0.5)],
    )

    out = capsys.readouterr().out

    assert "0.5000" in out
    assert "failed" not in out
