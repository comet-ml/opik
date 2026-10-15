from unittest.mock import Mock

from opik.api_objects.experiment import experiment as experiment_module
from opik.evaluation.metrics import score_result
from opik.rest_api.types.experiment_public import ExperimentPublic
from opik.rest_api.types.experiment_score_public import ExperimentScorePublic


def test_log_experiment_scores__preserves_unrelated_scores() -> None:
    rest_client = Mock()
    experiment = experiment_module.Experiment(
        id="experiment-id",
        name="experiment-name",
        dataset_name="dataset-name",
        rest_client=rest_client,
        streamer=Mock(),
        experiments_client=Mock(),
    )
    experiment.get_experiment_data = Mock(
        return_value=ExperimentPublic(
            experiment_scores=[
                ExperimentScorePublic(name="accuracy", value=0.1),
                ExperimentScorePublic(name="unrelated", value=0.4),
                ExperimentScorePublic(name="stale", value=0.8),
            ]
        )
    )

    effective_scores = experiment.log_experiment_scores(
        score_results=[
            score_result.ScoreResult(name="accuracy", value=0.9),
            score_result.ScoreResult(name="stale", value=0.0, scoring_failed=True),
        ],
        preserve_unrelated=True,
    )

    scores = rest_client.experiments.update_experiment.call_args.kwargs[
        "experiment_scores"
    ]
    assert [(score.name, score.value) for score in scores] == [
        ("unrelated", 0.4),
        ("accuracy", 0.9),
    ]
    assert [
        (score.name, score.value, score.scoring_failed) for score in effective_scores
    ] == [
        ("unrelated", 0.4, False),
        ("accuracy", 0.9, False),
        ("stale", 0.0, True),
    ]


def test_log_experiment_scores__empty_list__no_op() -> None:
    rest_client = Mock()
    experiment = experiment_module.Experiment(
        id="experiment-id",
        name="experiment-name",
        dataset_name="dataset-name",
        rest_client=rest_client,
        streamer=Mock(),
        experiments_client=Mock(),
    )
    experiment.get_experiment_data = Mock()

    effective_scores = experiment.log_experiment_scores(
        score_results=[],
        preserve_unrelated=True,
    )

    assert effective_scores == []
    experiment.get_experiment_data.assert_not_called()
    rest_client.experiments.update_experiment.assert_not_called()


def test_log_experiment_scores__duplicate_names__deduplicates_payload() -> None:
    rest_client = Mock()
    experiment = experiment_module.Experiment(
        id="experiment-id",
        name="experiment-name",
        dataset_name="dataset-name",
        rest_client=rest_client,
        streamer=Mock(),
        experiments_client=Mock(),
    )

    experiment.log_experiment_scores(
        score_results=[
            score_result.ScoreResult(name="accuracy", value=0.5),
            score_result.ScoreResult(name="accuracy", value=0.9),
        ]
    )

    scores = rest_client.experiments.update_experiment.call_args.kwargs[
        "experiment_scores"
    ]
    assert [(score.name, score.value) for score in scores] == [
        ("accuracy", 0.9),
    ]


def test_log_experiment_scores__all_failed_without_preserve__no_op() -> None:
    rest_client = Mock()
    experiment = experiment_module.Experiment(
        id="experiment-id",
        name="experiment-name",
        dataset_name="dataset-name",
        rest_client=rest_client,
        streamer=Mock(),
        experiments_client=Mock(),
    )

    effective_scores = experiment.log_experiment_scores(
        score_results=[
            score_result.ScoreResult(name="accuracy", value=0.0, scoring_failed=True),
        ],
    )

    rest_client.experiments.update_experiment.assert_not_called()
    assert [(score.name, score.scoring_failed) for score in effective_scores] == [
        ("accuracy", True)
    ]
