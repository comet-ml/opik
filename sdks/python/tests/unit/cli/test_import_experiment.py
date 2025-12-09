"""Unit tests for experiment import functionality."""

import json
import sys
import types
from pathlib import Path
from typing import Dict, Any
from unittest.mock import Mock, MagicMock, patch
import pytest

# Mock the problematic imports before importing
# Mock the prompt import that's causing issues
sys.modules["opik.api_objects.prompt.prompt"] = MagicMock()

# Now we can import normally
from opik.cli.imports.experiment import (  # noqa: E402
    ExperimentData,
    load_experiment_data,
    recreate_experiment,
    _import_traces_from_projects_directory,
)
from opik.cli.imports.utils import (  # noqa: E402
    translate_trace_id as utils_translate_trace_id,
)


class TestExperimentData:
    """Test ExperimentData dataclass."""

    def test_experiment_data_from_dict(self) -> None:
        """Test creating ExperimentData from dictionary."""
        data = {
            "experiment": {
                "id": "exp-123",
                "name": "test-experiment",
                "dataset_name": "test-dataset",
            },
            "items": [
                {"id": "item-1", "trace_id": "trace-1"},
                {"id": "item-2", "trace_id": "trace-2"},
            ],
            "downloaded_at": "2024-01-01T00:00:00",
        }

        exp_data = ExperimentData.from_dict(data)

        assert exp_data.experiment["id"] == "exp-123"
        assert exp_data.experiment["name"] == "test-experiment"
        assert len(exp_data.items) == 2
        assert exp_data.downloaded_at == "2024-01-01T00:00:00"

    def test_experiment_data_from_dict_minimal(self) -> None:
        """Test creating ExperimentData with minimal data."""
        data = {
            "experiment": {"id": "exp-123", "dataset_name": "test-dataset"},
            "items": [],
        }

        exp_data = ExperimentData.from_dict(data)

        assert exp_data.experiment["id"] == "exp-123"
        assert exp_data.items == []
        assert exp_data.downloaded_at is None

    def test_load_experiment_data_from_file(self, tmp_path: Path) -> None:
        """Test loading experiment data from JSON file."""
        experiment_file = tmp_path / "experiment_test.json"
        data = {
            "experiment": {
                "id": "exp-123",
                "name": "test-experiment",
                "dataset_name": "test-dataset",
            },
            "items": [{"id": "item-1"}],
        }

        with open(experiment_file, "w") as f:
            json.dump(data, f)

        exp_data = load_experiment_data(experiment_file)

        assert isinstance(exp_data, ExperimentData)
        assert exp_data.experiment["id"] == "exp-123"
        assert len(exp_data.items) == 1


class TestTranslateTraceId:
    """Test translate_trace_id function."""

    def test_translate_trace_id_found(self) -> None:
        """Test translating trace ID when mapping exists."""
        trace_id_map = {"old-trace-1": "new-trace-1", "old-trace-2": "new-trace-2"}

        result = utils_translate_trace_id("old-trace-1", trace_id_map)

        assert result == "new-trace-1"

    def test_translate_trace_id_not_found(self) -> None:
        """Test translating trace ID when mapping doesn't exist."""
        trace_id_map = {"old-trace-1": "new-trace-1"}

        result = utils_translate_trace_id("old-trace-2", trace_id_map)

        assert result is None

    def test_translate_trace_id_empty_map(self) -> None:
        """Test translating trace ID with empty map."""
        trace_id_map: Dict[str, str] = {}

        result = utils_translate_trace_id("old-trace-1", trace_id_map)

        assert result is None

    def test_translate_trace_id_requires_dict(self) -> None:
        """Test that translate_trace_id requires Dict, not Optional."""
        # This test verifies the type signature is correct
        # If someone tries to pass None, type checker should catch it
        trace_id_map: Dict[str, str] = {}  # Required, not Optional

        result = utils_translate_trace_id("trace-1", trace_id_map)

        assert result is None


class TestRecreateExperiment:
    """Test recreate_experiment function."""

    @staticmethod
    def _extract_items_arg_from_call_args(call_args: Any) -> Any:
        """Helper to extract the items argument from call_args.

        Handles both positional and keyword arguments.
        """
        if hasattr(call_args, "args") and call_args.args:
            return call_args.args[0]
        if hasattr(call_args, "kwargs"):
            if "items" in call_args.kwargs:
                return call_args.kwargs["items"]
            for value in call_args.kwargs.values():
                if isinstance(value, list) and len(value) > 0:
                    return value
        return None

    @pytest.fixture
    def mock_client(self) -> Mock:
        """Create a mock Opik client."""
        client = Mock()
        # Ensure flush returns True to indicate success
        client.flush = Mock(return_value=True)

        # Mock dataset
        mock_dataset = Mock()
        mock_dataset.name = "test-dataset"
        mock_dataset.__internal_api__insert_items_as_dataclasses__ = Mock()

        # Mock experiment
        mock_experiment = Mock()
        mock_experiment.insert = Mock()
        mock_experiment.id = "exp-123"

        client.get_or_create_dataset = Mock(return_value=mock_dataset)
        client.create_experiment = Mock(return_value=mock_experiment)
        # Mock REST client for experiment items creation
        client._rest_client = Mock()
        client._rest_client.experiments = Mock()
        client._rest_client.experiments.create_experiment_items = Mock()

        return client

    @pytest.fixture
    def experiment_data(self) -> ExperimentData:
        """Create sample experiment data."""
        return ExperimentData(
            experiment={
                "id": "exp-123",
                "name": "test-experiment",
                "dataset_name": "test-dataset",
                "type": "regular",
            },
            items=[
                {
                    "trace_id": "trace-1",
                    "dataset_item_data": {
                        "input": "test input",
                        "expected_output": "test output",
                    },
                },
                {
                    "trace_id": "trace-2",
                    "dataset_item_data": {"input": "test input 2"},
                },
            ],
        )

    def test_recreate_experiment_requires_trace_id_map(
        self, mock_client: Mock, experiment_data: ExperimentData
    ) -> None:
        """Test that recreate_experiment requires trace_id_map (not Optional)."""
        # This test verifies the type signature
        trace_id_map: Dict[str, str] = {
            "trace-1": "new-trace-1",
            "trace-2": "new-trace-2",
        }

        # Should not accept None - type checker would catch this
        # We test that it works with a dict
        with (
            patch("opik.cli.imports.experiment.dataset_item_module") as mock_ds_module,
            patch("opik.cli.imports.experiment.id_helpers_module") as mock_id_helpers,
        ):
            # Mock DatasetItem
            mock_dataset_item = Mock()
            mock_dataset_item.id = "ds-item-1"
            mock_ds_module.DatasetItem = Mock(return_value=mock_dataset_item)
            mock_id_helpers.generate_id = Mock(return_value="generated-id")

            recreate_experiment(
                mock_client,
                experiment_data,
                "test-project",
                trace_id_map,  # Required, not Optional
                dry_run=False,
                debug=False,
            )

            # Verify dataset items were created
            assert mock_ds_module.DatasetItem.called

    def test_recreate_experiment_batches_dataset_items(
        self, mock_client: Mock, experiment_data: ExperimentData
    ) -> None:
        """Test that dataset items are inserted in batch, not one at a time."""
        with (
            patch("opik.cli.imports.experiment.dataset_item_module") as mock_ds_module,
            patch("opik.cli.imports.experiment.id_helpers_module") as mock_id_helpers,
            patch("time.sleep"),  # Patch to avoid actual delays
        ):
            # Create mock dataset items
            mock_items = []
            for i in range(2):
                mock_item = Mock()
                mock_item.id = f"ds-item-{i+1}"
                mock_items.append(mock_item)

            mock_ds_module.DatasetItem = Mock(side_effect=mock_items)
            mock_id_helpers.generate_id = Mock(return_value="generated-id")

            trace_id_map = {"trace-1": "new-trace-1", "trace-2": "new-trace-2"}

            recreate_experiment(
                mock_client,
                experiment_data,
                "test-project",
                trace_id_map,
                dry_run=False,
                debug=False,
            )

            # Get the dataset from the mock
            mock_dataset = mock_client.get_or_create_dataset.return_value

            # Verify batch insert was called ONCE with all items
            assert (
                mock_dataset.__internal_api__insert_items_as_dataclasses__.call_count
                == 1
            )

            # Verify it was called with a list of items (batch)
            call_args = (
                mock_dataset.__internal_api__insert_items_as_dataclasses__.call_args
            )
            assert call_args is not None

            # Use helper to extract items argument from call_args
            items_arg = self._extract_items_arg_from_call_args(call_args)

            assert (
                items_arg is not None
            ), f"Could not find items in call_args: {call_args}"
            assert (
                len(items_arg) == 2
            ), f"Expected 2 items in batch, got {len(items_arg)}"

    def test_recreate_experiment_uses_module_names_correctly(
        self, mock_client: Mock, experiment_data: ExperimentData
    ) -> None:
        """Test that module names (dataset_item_module, id_helpers_module) are used correctly."""
        with (
            patch("opik.cli.imports.experiment.dataset_item_module") as mock_ds_module,
            patch("opik.cli.imports.experiment.id_helpers_module") as mock_id_helpers,
        ):
            mock_item = Mock()
            mock_item.id = "ds-item-1"
            mock_ds_module.DatasetItem = Mock(return_value=mock_item)
            mock_id_helpers.generate_id = Mock(return_value="generated-id")

            trace_id_map = {"trace-1": "new-trace-1"}

            recreate_experiment(
                mock_client,
                experiment_data,
                "test-project",
                trace_id_map,
                dry_run=False,
                debug=False,
            )

            # Verify modules are used (not checked for None)
            assert mock_ds_module.DatasetItem.called
            assert mock_id_helpers.generate_id.called

    def test_recreate_experiment_handles_empty_trace_id_map(
        self, mock_client: Mock, experiment_data: ExperimentData
    ) -> None:
        """Test that empty trace_id_map is handled correctly."""
        trace_id_map: Dict[str, str] = {}  # Empty but valid

        recreate_experiment(
            mock_client,
            experiment_data,
            "test-project",
            trace_id_map,
            dry_run=False,
            debug=False,
        )

        # Should still create experiment and dataset, but skip items
        assert mock_client.get_or_create_dataset.called
        assert mock_client.create_experiment.called

    def test_recreate_experiment_dry_run(
        self, mock_client: Mock, experiment_data: ExperimentData
    ) -> None:
        """Test dry run mode."""
        trace_id_map = {"trace-1": "new-trace-1"}

        result = recreate_experiment(
            mock_client,
            experiment_data,
            "test-project",
            trace_id_map,
            dry_run=True,
            debug=False,
        )

        assert result is True
        # Should not create anything in dry run
        assert not mock_client.get_or_create_dataset.called
        assert not mock_client.create_experiment.called


class TestImportTracesWithSpans:
    """Test trace import with span parent_span_id preservation."""

    @pytest.fixture
    def mock_client(self) -> Mock:
        """Create a mock Opik client."""
        client = Mock()
        client.flush = Mock()

        # Mock trace creation
        mock_trace = Mock()
        mock_trace.id = "new-trace-1"
        client.trace = Mock(return_value=mock_trace)

        # Mock span creation
        mock_spans = []
        for i in range(3):
            mock_span = Mock()
            mock_span.id = f"new-span-{i+1}"
            mock_spans.append(mock_span)

        client.span = Mock(side_effect=mock_spans)

        return client

    def test_import_traces_preserves_span_hierarchy(
        self, mock_client: Mock, tmp_path: Path
    ) -> None:
        """Test that span parent_span_id relationships are preserved."""
        # Create test trace file with spans
        projects_dir = tmp_path / "projects" / "test-project"
        projects_dir.mkdir(parents=True)

        trace_data = {
            "trace": {
                "id": "original-trace-1",
                "name": "test-trace",
                "input": {},
                "output": {},
            },
            "spans": [
                {
                    "id": "span-1",
                    "name": "root-span",
                    "parent_span_id": None,  # Root span
                    "input": {},
                    "output": {},
                },
                {
                    "id": "span-2",
                    "name": "child-span",
                    "parent_span_id": "span-1",  # Child of span-1
                    "input": {},
                    "output": {},
                },
                {
                    "id": "span-3",
                    "name": "grandchild-span",
                    "parent_span_id": "span-2",  # Child of span-2
                    "input": {},
                    "output": {},
                },
            ],
        }

        trace_file = projects_dir / "trace_original-trace-1.json"
        with open(trace_file, "w") as f:
            json.dump(trace_data, f)

        # Import traces
        trace_id_map, _ = _import_traces_from_projects_directory(
            mock_client, tmp_path, dry_run=False, debug=False
        )

        # Verify spans were created
        assert mock_client.span.call_count == 3

        # Verify spans were created in correct order (root first, then children)
        span_calls = mock_client.span.call_args_list

        # First span should be root (no parent_span_id)
        first_call = span_calls[0]
        assert first_call.kwargs.get("parent_span_id") is None

        # Second span should have parent_span_id set to first span's new ID
        # Note: We can't easily verify the exact ID mapping without more complex mocking,
        # but we can verify that parent_span_id is being passed
        second_call = span_calls[1]
        # The parent_span_id should be set (not None) since span-1 was created first
        # and its new ID should be in span_id_map
        assert "parent_span_id" in second_call.kwargs

        # Verify trace was created
        assert mock_client.trace.called
        assert "original-trace-1" in trace_id_map

    def test_import_traces_sorts_spans_correctly(
        self, mock_client: Mock, tmp_path: Path
    ) -> None:
        """Test that spans are sorted (root spans first, then children)."""
        projects_dir = tmp_path / "projects" / "test-project"
        projects_dir.mkdir(parents=True)

        trace_data = {
            "trace": {
                "id": "original-trace-1",
                "name": "test-trace",
                "input": {},
                "output": {},
            },
            "spans": [
                {
                    "id": "span-2",
                    "name": "child-span",
                    "parent_span_id": "span-1",  # Child - should come after root
                    "input": {},
                    "output": {},
                },
                {
                    "id": "span-1",
                    "name": "root-span",
                    "parent_span_id": None,  # Root - should come first
                    "input": {},
                    "output": {},
                },
            ],
        }

        trace_file = projects_dir / "trace_original-trace-1.json"
        with open(trace_file, "w") as f:
            json.dump(trace_data, f)

        # Import traces
        _, _ = _import_traces_from_projects_directory(
            mock_client, tmp_path, dry_run=False, debug=False
        )  # Returns (trace_id_map, stats), but we don't need them for this test

        # Verify spans were created in correct order
        span_calls = mock_client.span.call_args_list

        # First span should be root (no parent)
        assert span_calls[0].kwargs.get("parent_span_id") is None

        # Second span should have parent_span_id
        assert span_calls[1].kwargs.get("parent_span_id") is not None


class TestModuleNameUsage:
    """Test that module names are used correctly (not checked for None)."""

    def test_module_names_are_modules_not_variables(self) -> None:
        """Test that dataset_item_module and id_helpers_module are modules."""
        from opik.cli.imports.experiment import dataset_item_module, id_helpers_module

        # Modules should exist and be importable
        assert dataset_item_module is not None
        assert id_helpers_module is not None

        # They should be modules, not None
        assert isinstance(dataset_item_module, types.ModuleType)
        assert isinstance(id_helpers_module, types.ModuleType)
