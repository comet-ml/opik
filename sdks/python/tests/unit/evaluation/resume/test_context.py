import json
from types import SimpleNamespace
from unittest import mock

import pytest

from opik import exceptions
from opik.evaluation.engine import helpers as engine_helpers
from opik.evaluation.resume import context, state


def _metadata_with_blob(blob_dict):
    """Wrap a resume-blob dict in the on-the-wire JSON-string form."""
    return {state.RESUME_METADATA_KEY: json.dumps(blob_dict)}


def _completed_trace_metadata():
    """Trace metadata that marks a trial as fully completed."""
    return {engine_helpers.EVALUATION_PENDING_METADATA_KEY: False}


def _make_client(
    metadata,
    *,
    dataset_name: str = "ds",
    project_name=None,
    experiment_items=None,
):
    if experiment_items is None:
        # The two "a" items and the "c" item are fully completed (marker
        # flipped to False); "b" never finished — marker stays True — and
        # is the case resume must replay.
        experiment_items = [
            SimpleNamespace(
                dataset_item_id="a",
                evaluation_task_output={"x": 1},
                trace_metadata=_completed_trace_metadata(),
            ),
            SimpleNamespace(
                dataset_item_id="a",
                evaluation_task_output={"x": 2},
                trace_metadata=_completed_trace_metadata(),
            ),
            SimpleNamespace(
                dataset_item_id="b",
                evaluation_task_output=None,
                trace_metadata={
                    engine_helpers.EVALUATION_PENDING_METADATA_KEY: True
                },
            ),
            SimpleNamespace(
                dataset_item_id="c",
                evaluation_task_output={"x": 3},
                trace_metadata=_completed_trace_metadata(),
            ),
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
        metadata = _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 3,
                "dataset_filter_string": "tags contains 'x'",
                "dataset_version_name": "v1",
                "nb_samples": 25,
                "requires_local_checkpoint": False,
            }
        )
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
        # only trials whose trace metadata marks them fully completed are
        # counted; "b" (marker=True) is skipped even though output is null
        assert dict(ctx.completed_runs_by_item_id) == {"a": 2, "c": 1}
        # no checkpoint required → reader should not be touched
        unused_reader.assert_not_called()

    def test_resumable_experiment__with_version_name__pins_dataset_version(self):
        metadata = _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v3",
                "nb_samples": None,
                "requires_local_checkpoint": False,
            }
        )
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
        metadata = _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v1",
                "nb_samples": None,
                "requires_local_checkpoint": True,
            }
        )
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
        metadata = _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v1",
                "nb_samples": None,
                "requires_local_checkpoint": True,
            }
        )
        client, _ = _make_client(metadata)
        absent_reader = mock.Mock(return_value=None)

        with pytest.raises(exceptions.LocalCheckpointMissing):
            context.prepare_resume_context(
                client, "exp-1", checkpoint_reader=absent_reader
            )

    def test_non_resumable_experiment__raises_with_reason(self):
        metadata = _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": False,
                "non_resumable_reason": "boom",
            }
        )
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
        metadata = _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": None,
                "nb_samples": None,
                "requires_local_checkpoint": False,
            }
        )
        client, _ = _make_client(metadata)

        with pytest.raises(exceptions.ExperimentNotResumable) as exc_info:
            context.prepare_resume_context(client, "exp-1")

        assert "pinned dataset_version_name" in str(exc_info.value)


class TestIsTrialFullyCompleted:
    """The marker is the single source of truth for resume completion."""

    def _resumable_metadata(self):
        return _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v1",
                "nb_samples": None,
                "requires_local_checkpoint": False,
            }
        )

    def test_marker_false_counts_as_completed(self):
        item = SimpleNamespace(
            dataset_item_id="a",
            evaluation_task_output={"x": 1},
            trace_metadata={
                engine_helpers.EVALUATION_PENDING_METADATA_KEY: False
            },
        )
        assert context.is_trial_fully_completed(item) is True

    def test_marker_true_does_not_count(self):
        """Output present but happy line never reached → must replay."""
        item = SimpleNamespace(
            dataset_item_id="a",
            evaluation_task_output={"x": 1},
            trace_metadata={
                engine_helpers.EVALUATION_PENDING_METADATA_KEY: True
            },
        )
        assert context.is_trial_fully_completed(item) is False

    def test_missing_trace_metadata_does_not_count(self):
        """Pre-marker SDK / external traces are treated as incomplete."""
        item = SimpleNamespace(
            dataset_item_id="a",
            evaluation_task_output={"x": 1},
            trace_metadata=None,
        )
        assert context.is_trial_fully_completed(item) is False

    def test_marker_key_absent_from_metadata_does_not_count(self):
        item = SimpleNamespace(
            dataset_item_id="a",
            evaluation_task_output={"x": 1},
            trace_metadata={"some_other_key": "value"},
        )
        assert context.is_trial_fully_completed(item) is False

    def test_count_completed_runs_uses_marker_only(self):
        """
        Defense in depth: a trial with output set but marker=True must
        not contribute to the per-item count even though under the old
        rule it would have. This is the bug case the marker exists for.
        """
        items = [
            SimpleNamespace(
                dataset_item_id="a",
                evaluation_task_output={"x": 1},
                trace_metadata={
                    engine_helpers.EVALUATION_PENDING_METADATA_KEY: False
                },
            ),
            # Output is set (task succeeded) but marker is still True —
            # scoring crashed or process was interrupted between task and
            # the happy-path-only line.
            SimpleNamespace(
                dataset_item_id="a",
                evaluation_task_output={"x": 2},
                trace_metadata={
                    engine_helpers.EVALUATION_PENDING_METADATA_KEY: True
                },
            ),
        ]
        client, _ = _make_client(
            self._resumable_metadata(), experiment_items=items
        )
        client.get_dataset.return_value.get_version_view.return_value = (
            mock.Mock(name="ds-v1")
        )

        ctx = context.prepare_resume_context(client, "exp-1")

        assert dict(ctx.completed_runs_by_item_id) == {"a": 1}


class TestBackendTooOldDetection:
    """``requires_completion_marker`` + empty trace_metadata → raise."""

    def _marker_required_metadata(self):
        return _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v1",
                "nb_samples": None,
                "requires_local_checkpoint": False,
                "requires_completion_marker": True,
            }
        )

    def _legacy_be_items(self):
        # Old BE: trace_metadata is None on every item.
        return [
            SimpleNamespace(
                dataset_item_id="a",
                evaluation_task_output={"x": 1},
                trace_metadata=None,
            ),
            SimpleNamespace(
                dataset_item_id="b",
                evaluation_task_output=None,
                trace_metadata=None,
            ),
        ]

    def test_old_be_raises_with_actionable_message(self):
        client, _ = _make_client(
            self._marker_required_metadata(),
            experiment_items=self._legacy_be_items(),
        )
        client.get_dataset.return_value.get_version_view.return_value = (
            mock.Mock(name="ds-v1")
        )

        with pytest.raises(exceptions.BackendTooOldForResume) as exc_info:
            context.prepare_resume_context(client, "exp-1")

        message = str(exc_info.value)
        assert "trace_metadata" in message
        assert "OPIK-5269" in message

    def test_marker_present_on_any_item__no_raise(self):
        items = [
            SimpleNamespace(
                dataset_item_id="a",
                evaluation_task_output={"x": 1},
                trace_metadata={
                    engine_helpers.EVALUATION_PENDING_METADATA_KEY: False
                },
            ),
            SimpleNamespace(
                dataset_item_id="b",
                evaluation_task_output=None,
                trace_metadata=None,
            ),
        ]
        client, _ = _make_client(
            self._marker_required_metadata(), experiment_items=items
        )
        client.get_dataset.return_value.get_version_view.return_value = (
            mock.Mock(name="ds-v1")
        )

        ctx = context.prepare_resume_context(client, "exp-1")

        # Only "a" has the cleared marker; "b" has no marker, so it's
        # incomplete under strict rules.
        assert dict(ctx.completed_runs_by_item_id) == {"a": 1}

    def test_marker_not_required__no_raise_even_with_empty_metadata(self):
        """Old experiment blob without ``requires_completion_marker`` shouldn't
        trigger the check — the SDK that wrote it didn't promise a marker."""
        old_blob_metadata = _metadata_with_blob(
            {
                "schema_version": 1,
                "resumable": True,
                "default_runs_per_item": 1,
                "dataset_filter_string": None,
                "dataset_version_name": "v1",
                "nb_samples": None,
                "requires_local_checkpoint": False,
                # No requires_completion_marker → defaults to False.
            }
        )
        client, _ = _make_client(
            old_blob_metadata, experiment_items=self._legacy_be_items()
        )
        client.get_dataset.return_value.get_version_view.return_value = (
            mock.Mock(name="ds-v1")
        )

        # No raise; with no marker on any item, the strict predicate
        # treats every trial as incomplete (the safe default).
        ctx = context.prepare_resume_context(client, "exp-1")
        assert dict(ctx.completed_runs_by_item_id) == {}

    def test_empty_experiment__no_raise(self):
        """An experiment with zero items can't trigger the check (nothing to
        sample). Resume should still build the context — the iteration logic
        will then process all dataset items as fresh."""
        client, _ = _make_client(
            self._marker_required_metadata(), experiment_items=[]
        )
        client.get_dataset.return_value.get_version_view.return_value = (
            mock.Mock(name="ds-v1")
        )

        ctx = context.prepare_resume_context(client, "exp-1")
        assert dict(ctx.completed_runs_by_item_id) == {}
