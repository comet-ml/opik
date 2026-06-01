from types import SimpleNamespace
from unittest import mock

from opik.evaluation.engine import helpers as engine_helpers
from opik.evaluation.resume import merge


# Trace metadata that marks a trial as fully completed by the happy-path
# line in the engine. Tests that want a partial/failed trial either pass
# ``trace_metadata=None`` (legacy / pre-marker) or supply a dict with the
# marker still at ``True``.
_COMPLETED_TRACE_METADATA = {
    engine_helpers.EVALUATION_PENDING_METADATA_KEY: False
}


def _experiment_item(
    *,
    id: str = "ei-x",
    dataset_item_id: str,
    trace_id: str,
    evaluation_task_output,
    feedback_scores=None,
    trace_metadata=_COMPLETED_TRACE_METADATA,
):
    return SimpleNamespace(
        id=id,
        dataset_item_id=dataset_item_id,
        trace_id=trace_id,
        evaluation_task_output=evaluation_task_output,
        feedback_scores=feedback_scores or [],
        trace_metadata=trace_metadata,
    )


def _dataset_with(items):
    dataset = mock.Mock()
    dataset.get_items.return_value = items
    return dataset


def _experiment_with(experiment_items):
    experiment = mock.Mock()
    experiment.get_items.return_value = experiment_items
    return experiment


class TestReconstructPreviousTestResults:
    def test_items_without_completion_marker__skipped(self):
        """The marker, not output presence, decides reconstruction.

        Item 'a' has output set but its trace metadata still carries the
        pending marker (the engine never reached the happy-path line —
        scoring crashed or was interrupted). Even if the caller passes it
        in ``fully_completed_dataset_item_ids`` by mistake, the per-trial
        defensive check in ``reconstruct_previous_test_results`` keeps the
        stale row from leaking into the merged result.
        """
        experiment = _experiment_with(
            [
                _experiment_item(
                    dataset_item_id="a",
                    trace_id="t-a",
                    evaluation_task_output={"output": "stale"},
                    trace_metadata={
                        engine_helpers.EVALUATION_PENDING_METADATA_KEY: True
                    },
                ),
                _experiment_item(
                    dataset_item_id="b",
                    trace_id="t-b",
                    evaluation_task_output={"output": "ok"},
                ),
            ]
        )
        dataset = _dataset_with(
            [{"id": "a", "input": "v-a"}, {"id": "b", "input": "v-b"}]
        )

        results = merge.reconstruct_previous_test_results(
            experiment=experiment,
            dataset_=dataset,
            fully_completed_dataset_item_ids={"a", "b"},
        )

        assert [r.test_case.dataset_item_id for r in results] == ["b"]

    def test_partial_items__not_reconstructed(self):
        """Items not in ``fully_completed_dataset_item_ids`` are dropped —
        they're being redone from scratch by the resume call."""
        experiment = _experiment_with(
            [
                _experiment_item(
                    dataset_item_id="a",
                    trace_id="t-a-trial-1",
                    evaluation_task_output={"output": "stale"},
                ),
                _experiment_item(
                    dataset_item_id="b",
                    trace_id="t-b",
                    evaluation_task_output={"output": "ok"},
                ),
            ]
        )
        dataset = _dataset_with(
            [{"id": "a", "input": "v-a"}, {"id": "b", "input": "v-b"}]
        )

        results = merge.reconstruct_previous_test_results(
            experiment=experiment,
            dataset_=dataset,
            # only 'b' fully completed; 'a' had partial trials → exclude
            fully_completed_dataset_item_ids={"b"},
        )

        assert [r.test_case.dataset_item_id for r in results] == ["b"]

    def test_reconstructed_test_case_carries_stored_output_and_dataset_content(
        self,
    ):
        experiment = _experiment_with(
            [
                _experiment_item(
                    dataset_item_id="a",
                    trace_id="t-a",
                    evaluation_task_output={"output": "stored"},
                ),
            ]
        )
        dataset = _dataset_with(
            [{"id": "a", "input": {"q": "hello"}, "expected_output": "stored"}]
        )

        results = merge.reconstruct_previous_test_results(
            experiment=experiment,
            dataset_=dataset,
            fully_completed_dataset_item_ids={"a"},
        )

        assert len(results) == 1
        test_case = results[0].test_case
        assert test_case.trace_id == "t-a"
        assert test_case.dataset_item_id == "a"
        assert test_case.task_output == {"output": "stored"}
        assert test_case.dataset_item_content == {
            "id": "a",
            "input": {"q": "hello"},
            "expected_output": "stored",
        }

    def test_score_results_built_from_stored_feedback_scores(self):
        experiment = _experiment_with(
            [
                _experiment_item(
                    dataset_item_id="a",
                    trace_id="t-a",
                    evaluation_task_output={"output": "ok"},
                    feedback_scores=[
                        {
                            "name": "equals_metric",
                            "value": 1.0,
                            "reason": "match",
                            "category_name": None,
                        },
                        {
                            "name": "custom_metric",
                            "value": 0.42,
                            "reason": None,
                            "category_name": "ok",
                        },
                    ],
                ),
            ]
        )
        dataset = _dataset_with([{"id": "a", "input": "v"}])

        results = merge.reconstruct_previous_test_results(
            experiment=experiment,
            dataset_=dataset,
            fully_completed_dataset_item_ids={"a"},
        )

        scores = {sr.name: sr for sr in results[0].score_results}
        assert scores["equals_metric"].value == 1.0
        assert scores["equals_metric"].reason == "match"
        assert scores["custom_metric"].value == 0.42
        assert scores["custom_metric"].category_name == "ok"

    def test_dataset_item_removed__experiment_item_skipped(self):
        experiment = _experiment_with(
            [
                _experiment_item(
                    dataset_item_id="a",
                    trace_id="t-a",
                    evaluation_task_output={"output": "ok"},
                ),
                _experiment_item(
                    dataset_item_id="ghost",
                    trace_id="t-ghost",
                    evaluation_task_output={"output": "ok"},
                ),
            ]
        )
        # 'ghost' is referenced by the experiment but no longer in the dataset
        dataset = _dataset_with([{"id": "a", "input": "v-a"}])

        results = merge.reconstruct_previous_test_results(
            experiment=experiment,
            dataset_=dataset,
            fully_completed_dataset_item_ids={"a", "ghost"},
        )

        assert [r.test_case.dataset_item_id for r in results] == ["a"]

    def test_no_fully_completed_items__returns_empty_list(self):
        experiment = _experiment_with(
            [
                _experiment_item(
                    dataset_item_id="a",
                    trace_id="t-a",
                    evaluation_task_output={"output": "ok"},
                ),
            ]
        )
        dataset = _dataset_with([{"id": "a", "input": "v"}])

        results = merge.reconstruct_previous_test_results(
            experiment=experiment,
            dataset_=dataset,
            fully_completed_dataset_item_ids=set(),
        )

        assert results == []

    def test_multiple_trials__all_reconstructed_for_fully_completed_item(self):
        """A fully-completed item with trial_count=3 has 3 stored experiment
        items; all three are reconstructed as separate TestResults."""
        experiment = _experiment_with(
            [
                _experiment_item(
                    id="ei-1",
                    dataset_item_id="a",
                    trace_id="t-a-trial-1",
                    evaluation_task_output={"output": "trial-1"},
                ),
                _experiment_item(
                    id="ei-2",
                    dataset_item_id="a",
                    trace_id="t-a-trial-2",
                    evaluation_task_output={"output": "trial-2"},
                ),
                _experiment_item(
                    id="ei-3",
                    dataset_item_id="a",
                    trace_id="t-a-trial-3",
                    evaluation_task_output={"output": "trial-3"},
                ),
            ]
        )
        dataset = _dataset_with([{"id": "a", "input": "v"}])

        results = merge.reconstruct_previous_test_results(
            experiment=experiment,
            dataset_=dataset,
            fully_completed_dataset_item_ids={"a"},
        )

        assert [r.test_case.trace_id for r in results] == [
            "t-a-trial-1",
            "t-a-trial-2",
            "t-a-trial-3",
        ]
