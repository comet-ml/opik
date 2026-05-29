import json
from unittest import mock

from opik.api_objects.dataset import dataset_item
from opik.evaluation.resume import integration, state
from opik.evaluation.samplers import base_dataset_sampler


def _blob(result):
    """Decode the JSON-string resume blob the integration helpers persist."""
    return json.loads(result[state.RESUME_METADATA_KEY])


class _IdentitySampler(base_dataset_sampler.BaseDatasetSampler):
    def sample(self, data_item):
        return list(data_item)


class TestResumeStateForEvaluate:
    def _dataset_with_version(self, version_name):
        ds = mock.Mock()
        ds.get_version_info.return_value = (
            mock.Mock(version_name=version_name) if version_name else None
        )
        return ds

    def test_no_sampler_no_explicit_ids__no_checkpoint_required(self):
        result = integration.resume_state_for_evaluate(
            experiment_config={"foo": "bar"},
            dataset_=self._dataset_with_version("v1"),
            trial_count=3,
            dataset_filter_string="tags contains 'eval'",
            nb_samples=10,
            dataset_sampler=None,
            dataset_item_ids=None,
        )

        blob = _blob(result)
        assert blob["resumable"] is True
        assert blob["requires_local_checkpoint"] is False
        assert blob["default_runs_per_item"] == 3
        assert blob["dataset_filter_string"] == "tags contains 'eval'"
        assert blob["dataset_version_name"] == "v1"
        assert blob["nb_samples"] == 10

    def test_with_sampler__marks_requires_local_checkpoint(self):
        result = integration.resume_state_for_evaluate(
            experiment_config=None,
            dataset_=self._dataset_with_version("v1"),
            trial_count=1,
            dataset_filter_string=None,
            nb_samples=None,
            dataset_sampler=_IdentitySampler(),
            dataset_item_ids=None,
        )

        assert (
            _blob(result)["requires_local_checkpoint"] is True
        )

    def test_with_explicit_ids__marks_requires_local_checkpoint(self):
        result = integration.resume_state_for_evaluate(
            experiment_config=None,
            dataset_=self._dataset_with_version("v1"),
            trial_count=1,
            dataset_filter_string=None,
            nb_samples=None,
            dataset_sampler=None,
            dataset_item_ids=["a", "b"],
        )

        assert (
            _blob(result)["requires_local_checkpoint"] is True
        )

    def test_dataset_without_versions__marks_non_resumable(self):
        result = integration.resume_state_for_evaluate(
            experiment_config=None,
            dataset_=self._dataset_with_version(None),
            trial_count=1,
            dataset_filter_string=None,
            nb_samples=None,
            dataset_sampler=None,
            dataset_item_ids=None,
        )

        blob = _blob(result)
        assert blob["resumable"] is False
        assert "pinned dataset version" in blob["non_resumable_reason"]
        # No iteration configs leak through when resumable=False.
        assert "default_runs_per_item" not in blob
        assert "dataset_version_name" not in blob


class TestWriteCheckpointIfNeeded:
    def test_no_sampler_no_explicit_ids__writes_nothing(self):
        writer = mock.Mock()

        integration.write_checkpoint_if_needed(
            experiment_id="exp-1",
            resolved_items=[dataset_item.DatasetItem(id="r-1")],
            dataset_item_ids=None,
            dataset_sampler=None,
            checkpoint_writer=writer,
        )

        writer.assert_not_called()

    def test_explicit_ids__writes_resolved_item_ids(self):
        writer = mock.Mock()

        integration.write_checkpoint_if_needed(
            experiment_id="exp-1",
            resolved_items=[
                dataset_item.DatasetItem(id="r-1"),
                dataset_item.DatasetItem(id="r-2"),
            ],
            dataset_item_ids=["a", "b"],
            dataset_sampler=None,
            checkpoint_writer=writer,
        )

        writer.assert_called_once_with("exp-1", ["r-1", "r-2"])

    def test_sampler__writes_resolved_item_ids(self):
        writer = mock.Mock()

        integration.write_checkpoint_if_needed(
            experiment_id="exp-1",
            resolved_items=[
                dataset_item.DatasetItem(id="sampled-1"),
                dataset_item.DatasetItem(id="sampled-2"),
            ],
            dataset_item_ids=None,
            dataset_sampler=_IdentitySampler(),
            checkpoint_writer=writer,
        )

        writer.assert_called_once_with("exp-1", ["sampled-1", "sampled-2"])
