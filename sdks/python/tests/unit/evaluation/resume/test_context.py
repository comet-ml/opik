from types import SimpleNamespace
from unittest import mock

import pytest

from opik import exceptions
from opik.evaluation.resume import context, state


def _make_client(
    metadata,
    *,
    dataset_name: str = "ds",
    project_name=None,
    experiment_items=None,
):
    if experiment_items is None:
        experiment_items = [
            SimpleNamespace(dataset_item_id="a", evaluation_task_output={"x": 1}),
            SimpleNamespace(dataset_item_id="a", evaluation_task_output={"x": 2}),
            SimpleNamespace(dataset_item_id="b", evaluation_task_output=None),
            SimpleNamespace(dataset_item_id="c", evaluation_task_output={"x": 3}),
        ]

    experiment = mock.Mock()
    experiment.dataset_name = dataset_name
    experiment.project_name = project_name
    experiment.get_experiment_data.return_value = SimpleNamespace(metadata=metadata)
    experiment.get_items.return_value = experiment_items

    client = mock.Mock()
    client.get_experiment_by_id.return_value = experiment
    client.get_dataset.return_value = mock.Mock(name="dataset")
    return client, experiment


class TestPrepareResumeContext:
    def test_resumable_experiment__reads_state_and_counts_completed_runs(self):
        metadata = {
            state.RESUME_METADATA_KEY: {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 3,
                "dataset_filter_string": "tags contains 'x'",
                "dataset_version_name": "v1",
                "nb_samples": 25,
                "requires_local_checkpoint": False,
            }
        }
        client, _ = _make_client(metadata)
        pinned_version = mock.Mock(name="dataset-v1")
        client.get_dataset.return_value.get_version_view.return_value = (
            pinned_version
        )
        unused_reader = mock.Mock()

        ctx = context.prepare_resume_context(
            client, "exp-1", checkpoint_reader=unused_reader
        )

        assert ctx.default_runs_per_item == 3
        assert ctx.dataset_filter_string == "tags contains 'x'"
        assert ctx.nb_samples == 25
        assert ctx.candidate_dataset_item_ids is None
        # context.dataset is always the pinned DatasetVersion
        assert ctx.dataset is pinned_version
        client.get_dataset.return_value.get_version_view.assert_called_once_with(
            "v1"
        )
        # non-null output counts; null output is ignored
        assert dict(ctx.completed_runs_by_item_id) == {"a": 2, "c": 1}
        # no checkpoint required → reader should not be touched
        unused_reader.assert_not_called()

    def test_resumable_experiment__with_version_name__pins_dataset_version(self):
        metadata = {
            state.RESUME_METADATA_KEY: {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v3",
                "nb_samples": None,
                "requires_local_checkpoint": False,
            }
        }
        client, _ = _make_client(metadata)
        pinned_version = mock.Mock(name="dataset-v3")
        client.get_dataset.return_value.get_version_view.return_value = (
            pinned_version
        )

        ctx = context.prepare_resume_context(client, "exp-1")

        client.get_dataset.return_value.get_version_view.assert_called_once_with(
            "v3"
        )
        assert ctx.dataset is pinned_version

    def test_requires_checkpoint__reader_returns_ids__populated_into_context(self):
        metadata = {
            state.RESUME_METADATA_KEY: {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v1",
                "nb_samples": None,
                "requires_local_checkpoint": True,
            }
        }
        client, _ = _make_client(metadata)
        injected_reader = mock.Mock(return_value=["id-1", "id-2"])

        ctx = context.prepare_resume_context(
            client, "exp-1", checkpoint_reader=injected_reader
        )

        injected_reader.assert_called_once_with("exp-1")
        assert ctx.candidate_dataset_item_ids == ["id-1", "id-2"]

    def test_requires_checkpoint__reader_returns_none__raises_local_missing(
        self,
    ):
        metadata = {
            state.RESUME_METADATA_KEY: {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v1",
                "nb_samples": None,
                "requires_local_checkpoint": True,
            }
        }
        client, _ = _make_client(metadata)
        absent_reader = mock.Mock(return_value=None)

        with pytest.raises(exceptions.LocalCheckpointMissing):
            context.prepare_resume_context(
                client, "exp-1", checkpoint_reader=absent_reader
            )

    def test_non_resumable_experiment__raises_with_reason(self):
        metadata = {
            state.RESUME_METADATA_KEY: {
                "schema_version": 1,
                "resumable": False,
                "non_resumable_reason": "boom",
            }
        }
        client, _ = _make_client(metadata)

        with pytest.raises(exceptions.ExperimentNotResumable) as exc_info:
            context.prepare_resume_context(client, "exp-1")

        assert "boom" in str(exc_info.value)

    def test_missing_resume_state__raises_not_resumable(self):
        """
        Experiments created without resume state cannot be safely resumed:
        their dataset version isn't pinned. Refuse loudly.
        """
        client, _ = _make_client(metadata={})

        with pytest.raises(exceptions.ExperimentNotResumable) as exc_info:
            context.prepare_resume_context(client, "exp-1")

        assert "pinned dataset version" in str(exc_info.value)

    def test_resumable_blob_with_null_version_name__raises(self):
        """
        Defense in depth: even if the blob claims resumable=True, a missing
        ``dataset_version_name`` forbids resume — iterating against a moving
        dataset HEAD would break the contract.
        """
        metadata = {
            state.RESUME_METADATA_KEY: {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": None,
                "nb_samples": None,
                "requires_local_checkpoint": False,
            }
        }
        client, _ = _make_client(metadata)

        with pytest.raises(exceptions.ExperimentNotResumable) as exc_info:
            context.prepare_resume_context(client, "exp-1")

        assert "pinned dataset_version_name" in str(exc_info.value)
