"""A dataset or prompt that fails to export must be reported as an error.

export_single_dataset and export_single_prompt used to print the error and
return 0, which is also what they return for a file skipped because it already
exists. So a failed export was counted as "skipped", `opik export ... dataset
NAME` said the dataset "already exists" and exited 0, and `opik export ... all`
said "Export complete." and exited 0 with the dataset missing from the export.
These tests pin the behaviour export_experiment_by_id already has: the error
propagates and the caller records it.
"""

import json
from pathlib import Path
from typing import Optional
from unittest.mock import MagicMock, Mock, patch

import pytest

from opik.rest_api.core.api_error import ApiError

_ALL_MODULE = "opik.cli.exports.all"
_DATASET_MODULE = "opik.cli.exports.dataset"
_PROMPT_MODULE = "opik.cli.exports.prompt"
_PROJECT_ID = "proj-id"


def _make_dataset(
    name: str = "golden-set", items_error: Optional[Exception] = None
) -> MagicMock:
    dataset = MagicMock()
    dataset.id = f"id-{name}"
    dataset.name = name
    dataset.description = None
    dataset.get_tags.return_value = []
    if items_error is not None:
        dataset.get_items.side_effect = items_error
    else:
        dataset.get_items.return_value = [{"id": "item-1", "question": "q1"}]
    return dataset


def _make_listing_client(*datasets: MagicMock) -> MagicMock:
    client = MagicMock()
    listed = []
    for dataset in datasets:
        ds_public = MagicMock()
        ds_public.name = dataset.name
        listed.append(ds_public)
    client.rest_client.datasets.find_datasets.return_value = MagicMock(
        content=listed, total=len(listed)
    )
    by_name = {dataset.name: dataset for dataset in datasets}
    client.get_dataset.side_effect = lambda name, **kwargs: by_name[name]
    return client


def _make_prompt() -> Mock:
    prompt = Mock()
    prompt.id = "p1"
    prompt.name = "greeting"
    prompt.tags = ["prod"]
    return prompt


def _make_prompt_client() -> Mock:
    client = Mock()
    # No history available; _safe_prompt_history swallows the error.
    client.get_prompt_history = Mock(side_effect=Exception("no history"))
    client.get_chat_prompt = Mock(return_value=_make_prompt())
    return client


def _fake_prepare_export_dir(client, output_path, workspace, project_name):
    project_dir = Path(output_path) / workspace / "projects" / _PROJECT_ID
    project_dir.mkdir(parents=True, exist_ok=True)
    return _PROJECT_ID, project_dir


_UNAVAILABLE = ApiError(status_code=503, headers={})


class TestExportSingleDatasetErrors:
    def test_export_single_dataset__items_fetch_fails__error_propagates(self, tmp_path):
        from opik.cli.exports.dataset import export_single_dataset

        dataset = _make_dataset(items_error=_UNAVAILABLE)

        with pytest.raises(ApiError):
            export_single_dataset(dataset, tmp_path, None, True, False, "json")

        assert not (tmp_path / "dataset_id-golden-set.json").exists()

    def test_export_single_dataset__success__returns_1_and_writes_file(self, tmp_path):
        from opik.cli.exports.dataset import export_single_dataset

        result = export_single_dataset(
            _make_dataset(), tmp_path, None, True, False, "json"
        )

        assert result == 1
        data = json.loads((tmp_path / "dataset_id-golden-set.json").read_text())
        assert data["items"] == [{"question": "q1"}]

    def test_export_single_dataset__existing_file__skipped_without_error(
        self, tmp_path
    ):
        from opik.cli.exports.dataset import export_single_dataset

        (tmp_path / "dataset_id-golden-set.json").write_text("{}")
        dataset = _make_dataset()

        result = export_single_dataset(dataset, tmp_path, None, False, False, "json")

        assert result == 0
        dataset.get_items.assert_not_called()


class TestExportDatasetByNameErrors:
    def _export(self, tmp_path, dataset):
        from opik.cli.exports.dataset import export_dataset_by_name

        client = MagicMock()
        client.get_dataset.return_value = dataset
        with (
            patch(f"{_DATASET_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_DATASET_MODULE}.prepare_project_export_dir",
                side_effect=_fake_prepare_export_dir,
            ),
        ):
            export_dataset_by_name(
                "golden-set", "ws", "proj", str(tmp_path), None, False, False, "json"
            )

    def test_export_dataset_by_name__items_fetch_fails__exits_1(self, tmp_path, capsys):
        with pytest.raises(SystemExit) as exc_info:
            self._export(tmp_path, _make_dataset(items_error=_UNAVAILABLE))

        assert exc_info.value.code == 1
        assert "already exists" not in capsys.readouterr().out

    def test_export_dataset_by_name__success__does_not_exit(self, tmp_path):
        self._export(tmp_path, _make_dataset())

        datasets_dir = tmp_path / "ws" / "projects" / _PROJECT_ID / "datasets"
        assert (datasets_dir / "dataset_id-golden-set.json").exists()


class TestExportAllDatasetsErrors:
    def test_export_all_datasets__one_failure__counted_as_error_others_exported(
        self, tmp_path
    ):
        from opik.cli.exports.all import _export_all_datasets

        failing = _make_dataset("failing", items_error=_UNAVAILABLE)
        client = _make_listing_client(failing, _make_dataset("healthy"))

        result = _export_all_datasets(
            client, tmp_path, "proj", _PROJECT_ID, None, True, False, "json"
        )

        assert result == (1, 0, 1)
        assert (tmp_path / "dataset_id-healthy.json").exists()
        assert not (tmp_path / "dataset_id-failing.json").exists()

    def test_export_all_datasets__listing_failure__counted_as_error(self, tmp_path):
        from opik.cli.exports.all import _export_all_datasets

        client = MagicMock()
        client.rest_client.datasets.find_datasets.side_effect = _UNAVAILABLE

        result = _export_all_datasets(
            client, tmp_path, "proj", _PROJECT_ID, None, True, False, "json"
        )

        assert result == (0, 0, 1)

    # The two controls below compare only (exported, skipped): they must also
    # pass on the code before this change, which returned a 2-tuple.
    def test_export_all_datasets__success__counted_as_exported(self, tmp_path):
        from opik.cli.exports.all import _export_all_datasets

        client = _make_listing_client(_make_dataset())

        result = _export_all_datasets(
            client, tmp_path, "proj", _PROJECT_ID, None, True, False, "json"
        )

        assert result[:2] == (1, 0)

    def test_export_all_datasets__existing_file__counted_as_skipped(self, tmp_path):
        from opik.cli.exports.all import _export_all_datasets

        (tmp_path / "dataset_id-golden-set.json").write_text("{}")
        client = _make_listing_client(_make_dataset())

        result = _export_all_datasets(
            client, tmp_path, "proj", _PROJECT_ID, None, False, False, "json"
        )

        assert result[:2] == (0, 1)


class TestExportAllExitCode:
    def _export_all(self, tmp_path, client):
        from opik.cli.exports.all import export_all

        with (
            patch(f"{_ALL_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_ALL_MODULE}.prepare_project_export_dir",
                side_effect=_fake_prepare_export_dir,
            ),
        ):
            export_all(
                workspace="ws",
                project_name="proj",
                output_path=str(tmp_path),
                include=["datasets"],
                max_results=None,
                force=False,
                debug=False,
                format="json",
            )

    def test_export_all__dataset_failure__exits_1(self, tmp_path, capsys):
        client = _make_listing_client(_make_dataset(items_error=_UNAVAILABLE))

        with pytest.raises(SystemExit) as exc_info:
            self._export_all(tmp_path, client)

        assert exc_info.value.code == 1
        assert "Export complete." not in capsys.readouterr().out

    def test_export_all__datasets_exported__does_not_exit(self, tmp_path, capsys):
        self._export_all(tmp_path, _make_listing_client(_make_dataset()))

        assert "Export complete." in capsys.readouterr().out


class TestExportPromptErrors:
    def test_export_single_prompt__write_fails__error_propagates(self, tmp_path):
        from opik.cli.exports.prompt import export_single_prompt

        with pytest.raises(OSError):
            export_single_prompt(
                client=_make_prompt_client(),
                prompt=_make_prompt(),
                output_dir=tmp_path / "does-not-exist",
                project_name="proj",
                max_results=None,
                force=True,
                debug=False,
                format="json",
            )

    def test_export_single_prompt__existing_file__skipped_without_error(self, tmp_path):
        from opik.cli.exports.prompt import export_single_prompt

        (tmp_path / "prompt_p1.json").write_text("{}")

        result = export_single_prompt(
            client=_make_prompt_client(),
            prompt=_make_prompt(),
            output_dir=tmp_path,
            project_name="proj",
            max_results=None,
            force=False,
            debug=False,
            format="json",
        )

        assert result == 0

    def test_export_all_prompts__write_fails__counted_as_error(self, tmp_path):
        from opik.cli.exports.all import _export_all_prompts

        client = _make_prompt_client()
        prompt_public = MagicMock(template_structure="chat")
        prompt_public.name = "greeting"
        client.rest_client.prompts.get_prompts.return_value = MagicMock(
            content=[prompt_public], total=1
        )

        result = _export_all_prompts(
            client,
            tmp_path / "does-not-exist",
            "proj",
            _PROJECT_ID,
            None,
            True,
            False,
            "json",
        )

        assert result == (0, 0, 1)

    def test_export_prompt_by_name__write_fails__exits_1(self, tmp_path, capsys):
        from opik.cli.exports.prompt import export_prompt_by_name

        with (
            patch(f"{_PROMPT_MODULE}.opik.Opik", return_value=_make_prompt_client()),
            patch(
                f"{_PROMPT_MODULE}.prepare_project_export_dir",
                side_effect=_fake_prepare_export_dir,
            ),
            patch(
                f"{_PROMPT_MODULE}._write_prompt_export_file",
                side_effect=OSError(28, "No space left on device"),
            ),
        ):
            with pytest.raises(SystemExit) as exc_info:
                export_prompt_by_name(
                    "greeting", "ws", "proj", str(tmp_path), None, False, False, "json"
                )

        assert exc_info.value.code == 1
        assert "already exists" not in capsys.readouterr().out


class TestPromptLookupErrors:
    @staticmethod
    def _listing_client(get_prompt: Mock) -> Mock:
        client = _make_prompt_client()
        client.get_prompt = get_prompt
        prompt_public = MagicMock(template_structure="text")
        prompt_public.name = "greeting"
        client.rest_client.prompts.get_prompts.return_value = MagicMock(
            content=[prompt_public], total=1
        )
        return client

    def test_export_all_prompts__lookup_fails__counted_as_error(self, tmp_path, capsys):
        from opik.cli.exports.all import _export_all_prompts

        client = self._listing_client(Mock(side_effect=_UNAVAILABLE))

        result = _export_all_prompts(
            client, tmp_path, "proj", _PROJECT_ID, None, True, False, "json"
        )

        assert result == (0, 0, 1)
        out = capsys.readouterr().out
        assert "greeting" in out
        assert "503" in out

    def test_export_all_prompts__prompt_missing__still_skipped(self, tmp_path):
        from opik.cli.exports.all import _export_all_prompts

        client = self._listing_client(Mock(return_value=None))

        result = _export_all_prompts(
            client, tmp_path, "proj", _PROJECT_ID, None, True, False, "json"
        )

        assert result == (0, 1, 0)

    def test_export_prompt_by_name__lookup_fails__exits_1(self, tmp_path, capsys):
        from opik.cli.exports.prompt import export_prompt_by_name

        client = _make_prompt_client()
        client.get_chat_prompt = Mock(side_effect=_UNAVAILABLE)
        client.get_prompt = Mock(side_effect=_UNAVAILABLE)

        with (
            patch(f"{_PROMPT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_PROMPT_MODULE}.prepare_project_export_dir",
                side_effect=_fake_prepare_export_dir,
            ),
        ):
            with pytest.raises(SystemExit) as exc_info:
                export_prompt_by_name(
                    "greeting", "ws", "proj", str(tmp_path), None, False, False, "json"
                )

        assert exc_info.value.code == 1
        out = capsys.readouterr().out
        assert "greeting" in out
        assert "503" in out

    def test_export_prompt_by_name__not_found__no_error_exit(self, tmp_path, capsys):
        from opik.cli.exports.prompt import export_prompt_by_name

        client = _make_prompt_client()
        client.get_chat_prompt = Mock(return_value=None)

        with (
            patch(f"{_PROMPT_MODULE}.opik.Opik", return_value=client),
            patch(
                f"{_PROMPT_MODULE}.prepare_project_export_dir",
                side_effect=_fake_prepare_export_dir,
            ),
        ):
            export_prompt_by_name(
                "greeting", "ws", "proj", str(tmp_path), None, False, False, "json"
            )

        assert "not found" in capsys.readouterr().out
