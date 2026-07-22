"""Unit tests verifying that item tags round-trip through CLI export/import.

Covers the regression where ``opik export`` / ``opik import`` silently dropped
tags for prompts, datasets, and experiments because the exporters used
hand-written field allowlists that omitted ``tags`` (and the importers never
forwarded them). Traces and spans already round-tripped correctly.
"""

import json
import sys
from pathlib import Path
from unittest.mock import Mock, MagicMock, patch

# The experiment/prompt import modules pull in a prompt module that is awkward
# to import in isolation; mirror the shim used by test_import_experiment.py.
sys.modules.setdefault("opik.api_objects.prompt.prompt", MagicMock())

from opik.cli.imports.prompt import import_prompts_from_directory  # noqa: E402
from opik.cli.imports.dataset import import_datasets_from_directory  # noqa: E402
from opik.cli.exports.prompt import export_single_prompt  # noqa: E402
from opik.cli.imports.experiment import (  # noqa: E402
    ExperimentData,
    recreate_experiment,
)
from opik.cli.exports.utils import create_experiment_data_structure  # noqa: E402


class TestPromptTagsImport:
    def test_import_text_prompt__with_tags__forwards_tags(self, tmp_path: Path) -> None:
        prompt_file = tmp_path / "prompt_p1.json"
        prompt_file.write_text(
            json.dumps(
                {
                    "name": "greeting",
                    "current_version": {
                        "prompt": "Hello {{name}}",
                        "type": "mustache",
                        "template_structure": "text",
                        "tags": ["prod", "greeting"],
                    },
                    "history": [],
                }
            )
        )

        client = Mock()
        client.create_prompt = Mock()

        import_prompts_from_directory(
            client=client,
            source_dir=tmp_path,
            project_name="proj",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        client.create_prompt.assert_called_once()
        assert client.create_prompt.call_args.kwargs["tags"] == ["prod", "greeting"]

    def test_import_chat_prompt__with_tags__forwards_tags(self, tmp_path: Path) -> None:
        prompt_file = tmp_path / "prompt_c1.json"
        prompt_file.write_text(
            json.dumps(
                {
                    "name": "chat",
                    "current_version": {
                        "prompt": [{"role": "user", "content": "hi"}],
                        "type": "mustache",
                        "template_structure": "chat",
                        "tags": ["chatty"],
                    },
                    "history": [],
                }
            )
        )

        client = Mock()
        client.create_chat_prompt = Mock()

        import_prompts_from_directory(
            client=client,
            source_dir=tmp_path,
            project_name="proj",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        client.create_chat_prompt.assert_called_once()
        assert client.create_chat_prompt.call_args.kwargs["tags"] == ["chatty"]


class TestDatasetTagsImport:
    def _write_dataset(self, tmp_path: Path, payload: dict) -> None:
        (tmp_path / "dataset_d1.json").write_text(json.dumps(payload))

    def _make_client(self) -> Mock:
        client = Mock()
        # Force the create path (get_dataset raises -> create_dataset used).
        client.get_dataset = Mock(side_effect=Exception("not found"))
        created = Mock()
        created.id = "new-ds-id"
        client.create_dataset = Mock(return_value=created)
        client.rest_client = Mock()
        client.rest_client.datasets = Mock()
        client.rest_client.datasets.update_dataset = Mock()
        return client

    def test_import_dataset__flat_format_with_tags__applies_tags(
        self, tmp_path: Path
    ) -> None:
        self._write_dataset(
            tmp_path,
            {"name": "ds", "tags": ["a", "b"], "items": []},
        )
        client = self._make_client()

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path,
            project_name="proj",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        client.rest_client.datasets.update_dataset.assert_called_once()
        call = client.rest_client.datasets.update_dataset.call_args
        assert call.args[0] == "new-ds-id"
        assert call.kwargs["tags"] == ["a", "b"]

    def test_import_dataset__nested_format_with_tags__applies_tags(
        self, tmp_path: Path
    ) -> None:
        self._write_dataset(
            tmp_path,
            {"dataset": {"name": "ds", "id": "x", "tags": ["c"]}, "items": []},
        )
        client = self._make_client()

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path,
            project_name="proj",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        client.rest_client.datasets.update_dataset.assert_called_once()
        assert client.rest_client.datasets.update_dataset.call_args.kwargs["tags"] == [
            "c"
        ]

    def test_import_dataset__no_tags_field__skips_update(self, tmp_path: Path) -> None:
        self._write_dataset(tmp_path, {"name": "ds", "items": []})
        client = self._make_client()

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path,
            project_name="proj",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        client.rest_client.datasets.update_dataset.assert_not_called()

    def test_import_dataset__empty_tags_list__clears_tags(self, tmp_path: Path) -> None:
        # An explicit empty list must still call the REST update so a
        # re-import can clear pre-existing tags on the destination.
        self._write_dataset(tmp_path, {"name": "ds", "tags": [], "items": []})
        client = self._make_client()

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path,
            project_name="proj",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        client.rest_client.datasets.update_dataset.assert_called_once()
        assert client.rest_client.datasets.update_dataset.call_args.kwargs["tags"] == []

    def test_import_dataset__existing_dataset_with_description__preserves_description(
        self, tmp_path: Path
    ) -> None:
        # Tags-only import must not silently null out a description the
        # destination dataset already had (the backend's update PUT doesn't
        # COALESCE description against the existing row).
        self._write_dataset(
            tmp_path,
            {"name": "ds", "tags": ["a"], "items": []},
        )
        client = self._make_client()
        # Re-import onto a dataset that already exists (get_dataset succeeds).
        existing_dataset = Mock()
        existing_dataset.id = "existing-ds-id"
        client.get_dataset = Mock(return_value=existing_dataset)
        existing_remote = Mock()
        existing_remote.description = "original description"
        existing_remote.visibility = "private"
        client.rest_client.datasets.get_dataset_by_id = Mock(
            return_value=existing_remote
        )

        import_datasets_from_directory(
            client=client,
            source_dir=tmp_path,
            project_name="proj",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        client.rest_client.datasets.update_dataset.assert_called_once()
        call = client.rest_client.datasets.update_dataset.call_args
        assert call.kwargs["description"] == "original description"
        assert call.kwargs["visibility"] == "private"
        assert call.kwargs["tags"] == ["a"]

    def test_import_dataset__update_dataset_raises__continues_and_inserts_items(
        self, tmp_path: Path
    ) -> None:
        # A tag-update failure must be swallowed with a warning so the rest of
        # the import (item insertion, count, manifest) still proceeds.
        self._write_dataset(
            tmp_path,
            {"name": "ds", "tags": ["a"], "items": [{"id": "1", "input": "x"}]},
        )
        client = self._make_client()
        client.rest_client.datasets.update_dataset = Mock(side_effect=Exception("boom"))

        result = import_datasets_from_directory(
            client=client,
            source_dir=tmp_path,
            project_name="proj",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        # The import did not abort: items were still inserted and the dataset
        # counted as imported.
        client.create_dataset.return_value.insert.assert_called_once()
        assert result["datasets"] == 1
        assert result["datasets_errors"] == 0


class TestPromptTagsExport:
    """Prompt tags are container-level and are dropped by the direct
    get_prompt() lookup; the exporter must recover them via search_prompts.
    """

    def test_export_single_prompt__object_has_tags__skips_search(
        self, tmp_path: Path
    ) -> None:
        prompt = Mock()
        prompt.id = "p1"
        prompt.name = "greeting"
        prompt.tags = ["prod"]  # direct lookup already carries the tags
        client = Mock()
        # No history available; _safe_prompt_history swallows the error.
        client.get_prompt_history = Mock(side_effect=Exception("no history"))

        count = export_single_prompt(
            client=client,
            prompt=prompt,
            output_dir=tmp_path,
            project_name="proj",
            max_results=None,
            force=True,
            debug=False,
            format="json",
        )

        assert count == 1
        data = json.loads((tmp_path / "prompt_p1.json").read_text())
        assert data["current_version"]["tags"] == ["prod"]
        # Tags were already present, so no recovery search is needed.
        client.search_prompts.assert_not_called()

    def test_export_single_prompt__container_only_tags__recovered_from_search(
        self, tmp_path: Path
    ) -> None:
        prompt = Mock()
        prompt.id = "p1"
        prompt.name = "greeting"
        prompt.tags = []  # direct lookup dropped the container-level tags

        candidate = Mock()
        candidate.name = "greeting"
        candidate.tags = ["prod", "greeting"]
        client = Mock()
        client.search_prompts = Mock(return_value=[candidate])
        # No history available; _safe_prompt_history swallows the error.
        client.get_prompt_history = Mock(side_effect=Exception("no history"))

        count = export_single_prompt(
            client=client,
            prompt=prompt,
            output_dir=tmp_path,
            project_name="proj",
            max_results=None,
            force=True,
            debug=False,
            format="json",
        )

        assert count == 1
        data = json.loads((tmp_path / "prompt_p1.json").read_text())
        assert data["current_version"]["tags"] == ["prod", "greeting"]

    def test_export_single_prompt__search_fails__still_exports_with_fallback(
        self, tmp_path: Path
    ) -> None:
        # Tag resolution is best-effort: a search_prompts failure is logged but
        # must not abort the export — the prompt still exports with whatever tags
        # the object carried.
        prompt = Mock()
        prompt.id = "p1"
        prompt.name = "greeting"
        prompt.tags = []  # nothing on the object; recovery will be attempted
        client = Mock()
        client.search_prompts = Mock(side_effect=Exception("api down"))
        client.get_prompt_history = Mock(side_effect=Exception("no history"))

        count = export_single_prompt(
            client=client,
            prompt=prompt,
            output_dir=tmp_path,
            project_name="proj",
            max_results=None,
            force=True,
            debug=False,
            format="json",
        )

        assert count == 1
        data = json.loads((tmp_path / "prompt_p1.json").read_text())
        assert data["current_version"]["tags"] == []


class TestExperimentTags:
    def test_export_experiment_structure__with_tags__includes_tags(self) -> None:
        experiment = Mock()
        experiment.id = "exp-1"
        experiment.name = "e"
        experiment.dataset_name = "ds"
        data_obj = Mock()
        data_obj.tags = ["t1", "t2"]
        experiment.get_experiment_data = Mock(return_value=data_obj)

        structure = create_experiment_data_structure(experiment, [])

        assert structure["experiment"]["tags"] == ["t1", "t2"]

    def test_import_experiment_from_disk__with_tags__forwards_tags(self) -> None:
        client = Mock()
        client.flush = Mock(return_value=True)
        client.get_or_create_dataset = Mock(return_value=Mock(name="ds"))
        created_experiment = Mock()
        created_experiment.id = "exp-123"
        client.create_experiment = Mock(return_value=created_experiment)
        client._rest_client = Mock()

        experiment_data = ExperimentData(
            experiment={
                "id": "exp-123",
                "name": "test-experiment",
                "dataset_name": "test-dataset",
                "type": "regular",
                "tags": ["golden", "regression"],
            },
            items=[],
        )

        with patch("opik.cli.imports.experiment.id_helpers_module") as mock_id_helpers:
            mock_id_helpers.generate_id = Mock(return_value="generated-id")

            recreate_experiment(
                client,
                experiment_data,
                "test-project",
                {},  # trace_id_map
                {},  # dataset_item_id_map
                dry_run=False,
                debug=False,
                # target_project_name defaults to None -> import-from-disk path
            )

        client.create_experiment.assert_called_once()
        assert client.create_experiment.call_args.kwargs["tags"] == [
            "golden",
            "regression",
        ]

    def test_import_experiment_from_disk__empty_tags_list__forwards_empty_tags(
        self,
    ) -> None:
        # An explicit empty list must round-trip as tags=[], not collapse to
        # tags=None (which would drop a deliberately cleared tag set).
        client = Mock()
        client.flush = Mock(return_value=True)
        client.get_or_create_dataset = Mock(return_value=Mock(name="ds"))
        created_experiment = Mock()
        created_experiment.id = "exp-123"
        client.create_experiment = Mock(return_value=created_experiment)
        client._rest_client = Mock()

        experiment_data = ExperimentData(
            experiment={
                "id": "exp-123",
                "name": "test-experiment",
                "dataset_name": "test-dataset",
                "type": "regular",
                "tags": [],
            },
            items=[],
        )

        with patch("opik.cli.imports.experiment.id_helpers_module") as mock_id_helpers:
            mock_id_helpers.generate_id = Mock(return_value="generated-id")

            recreate_experiment(
                client,
                experiment_data,
                "test-project",
                {},  # trace_id_map
                {},  # dataset_item_id_map
                dry_run=False,
                debug=False,
            )

        client.create_experiment.assert_called_once()
        assert client.create_experiment.call_args.kwargs["tags"] == []
