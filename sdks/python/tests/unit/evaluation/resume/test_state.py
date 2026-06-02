import json
from types import SimpleNamespace
from unittest import mock

from opik.evaluation.resume import state


class TestEmbedResumableState:
    def test_writes_full_config_blob_as_json_string(self):
        result = state.embed_resumable_state(
            {"foo": "bar"},
            state.ResumableState(
                default_runs_per_item=3,
                dataset_filter_string="tags contains 'eval'",
                dataset_version_name="v7",
                nb_samples=50,
                requires_local_checkpoint=False,
            ),
        )

        assert result["foo"] == "bar"
        # The blob is a single JSON-encoded string under one key (keeps the
        # experiment Configuration UI from listing every nested field as a
        # separate row).
        raw = result[state.RESUME_METADATA_KEY]
        assert isinstance(raw, str)
        blob = json.loads(raw)
        assert blob["resumable"] is True
        assert blob["schema_version"] == state.RESUME_SCHEMA_VERSION
        assert blob["default_runs_per_item"] == 3
        assert blob["dataset_filter_string"] == "tags contains 'eval'"
        assert blob["dataset_version_name"] == "v7"
        assert blob["nb_samples"] == 50
        assert blob["requires_local_checkpoint"] is False

    def test_no_existing_config__returns_new_dict(self):
        result = state.embed_resumable_state(
            None,
            state.ResumableState(
                default_runs_per_item=1,
                dataset_filter_string=None,
                dataset_version_name="v1",
                nb_samples=None,
                requires_local_checkpoint=False,
            ),
        )

        blob = json.loads(result[state.RESUME_METADATA_KEY])
        assert blob["resumable"] is True
        assert blob["dataset_version_name"] == "v1"
        assert blob["nb_samples"] is None

    def test_does_not_mutate_caller_config(self):
        caller_config = {"foo": "bar"}

        state.embed_resumable_state(
            caller_config,
            state.ResumableState(
                default_runs_per_item=1,
                dataset_filter_string=None,
                dataset_version_name="v1",
                nb_samples=None,
                requires_local_checkpoint=False,
            ),
        )

        assert caller_config == {"foo": "bar"}

    def test_requires_local_checkpoint__persists_true_flag(self):
        result = state.embed_resumable_state(
            None,
            state.ResumableState(
                default_runs_per_item=2,
                dataset_filter_string=None,
                dataset_version_name="v1",
                nb_samples=None,
                requires_local_checkpoint=True,
            ),
        )

        blob = json.loads(result[state.RESUME_METADATA_KEY])
        assert blob["requires_local_checkpoint"] is True


class TestEmbedNonResumableState:
    def test_stores_marker_and_reason_only(self):
        result = state.embed_non_resumable_state(
            None,
            state.NonResumableState(reason="some reason"),
        )

        raw = result[state.RESUME_METADATA_KEY]
        assert isinstance(raw, str)
        blob = json.loads(raw)
        assert blob["resumable"] is False
        assert blob["non_resumable_reason"] == "some reason"
        # No iteration configs leak through when non-resumable.
        assert "default_runs_per_item" not in blob
        assert "dataset_filter_string" not in blob
        assert "dataset_version_name" not in blob
        assert "nb_samples" not in blob
        assert "requires_local_checkpoint" not in blob


class TestReadResumeState:
    def _experiment_with_metadata(self, metadata) -> mock.Mock:
        experiment = mock.Mock()
        experiment.get_experiment_data.return_value = SimpleNamespace(metadata=metadata)
        return experiment

    def _metadata_with_blob(self, blob_dict):
        """Wrap a resume-blob dict in the on-the-wire JSON-string form."""
        return {state.RESUME_METADATA_KEY: json.dumps(blob_dict)}

    def test_missing_metadata__returns_none(self):
        experiment = self._experiment_with_metadata({})

        assert state.read_resume_state(experiment) is None

    def test_metadata_without_resume_key__returns_none(self):
        experiment = self._experiment_with_metadata({"other": "data"})

        assert state.read_resume_state(experiment) is None

    def test_resume_value_not_a_string__returns_none(self):
        """The persisted value must be a JSON-encoded string; a raw dict is
        considered malformed and treated as no resume state."""
        experiment = self._experiment_with_metadata(
            {state.RESUME_METADATA_KEY: {"resumable": True}}
        )

        assert state.read_resume_state(experiment) is None

    def test_resumable_blob__decoded_into_resumable_state(self):
        experiment = self._experiment_with_metadata(
            self._metadata_with_blob(
                {
                    "schema_version": 1,
                    "resumable": True,
                    "default_runs_per_item": 3,
                    "dataset_filter_string": "tags contains 'x'",
                    "dataset_version_name": "v3",
                    "nb_samples": 50,
                    "requires_local_checkpoint": True,
                }
            )
        )

        persisted = state.read_resume_state(experiment)

        assert isinstance(persisted, state.ResumableState)
        assert persisted.default_runs_per_item == 3
        assert persisted.dataset_filter_string == "tags contains 'x'"
        assert persisted.dataset_version_name == "v3"
        assert persisted.nb_samples == 50
        assert persisted.requires_local_checkpoint is True

    def test_non_resumable_blob__exposes_reason(self):
        experiment = self._experiment_with_metadata(
            self._metadata_with_blob(
                {
                    "schema_version": 1,
                    "resumable": False,
                    "non_resumable_reason": "boom",
                }
            )
        )

        persisted = state.read_resume_state(experiment)

        assert isinstance(persisted, state.NonResumableState)
        assert persisted.reason == "boom"

    def test_resumable_blob_missing_version_name__downgraded_to_non_resumable(self):
        """A blob that claims resumable=True but has no pinned dataset
        version name is downgraded to NonResumableState — iterating against
        a moving dataset HEAD would break the resume contract."""
        experiment = self._experiment_with_metadata(
            self._metadata_with_blob(
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
        )

        persisted = state.read_resume_state(experiment)

        assert isinstance(persisted, state.NonResumableState)
        assert "pinned dataset_version_name" in persisted.reason

    def test_round_trip__embedded_json_string_decodes_back(self):
        """``embed_resumable_state`` writes a JSON string; ``read_resume_state``
        must decode it back into a ``ResumableState``."""
        embedded = state.embed_resumable_state(
            None,
            state.ResumableState(
                default_runs_per_item=3,
                dataset_filter_string="tags contains 'x'",
                dataset_version_name="v3",
                nb_samples=50,
                requires_local_checkpoint=True,
            ),
        )
        experiment = self._experiment_with_metadata(embedded)

        persisted = state.read_resume_state(experiment)

        assert isinstance(persisted, state.ResumableState)
        assert persisted.default_runs_per_item == 3
        assert persisted.dataset_filter_string == "tags contains 'x'"
        assert persisted.dataset_version_name == "v3"
        assert persisted.nb_samples == 50
        assert persisted.requires_local_checkpoint is True

    def test_malformed_json_string__treated_as_no_resume_state(self):
        experiment = self._experiment_with_metadata(
            {state.RESUME_METADATA_KEY: "{not valid json"}
        )

        assert state.read_resume_state(experiment) is None

    def test_corrupted_field_types__coerced_to_safe_defaults(self):
        experiment = self._experiment_with_metadata(
            self._metadata_with_blob(
                {
                    "schema_version": 1,
                    "resumable": True,
                    "default_runs_per_item": "not-an-int",
                    "dataset_filter_string": 42,
                    "dataset_version_name": "v1",
                    "nb_samples": -5,
                }
            )
        )

        persisted = state.read_resume_state(experiment)

        assert isinstance(persisted, state.ResumableState)
        assert persisted.default_runs_per_item == 1
        assert persisted.dataset_filter_string is None
        assert persisted.dataset_version_name == "v1"
        assert persisted.nb_samples is None
