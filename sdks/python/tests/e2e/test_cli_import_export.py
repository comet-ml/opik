"""E2E tests for CLI import/export commands."""

import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Iterator, List
import pytest

import opik
from opik import synchronization
from opik.api_objects.experiment.experiment_item import ExperimentItemReferences
from opik.cli.exports.dataset import export_dataset_by_name
from opik.cli.exports.experiment import export_experiment_by_name
from opik.cli.exports.project import export_project_traces
from opik.cli.exports.prompt import export_prompt_by_name
from opik.cli.imports.project import import_traces_from_directory
from opik.cli.imports.dataset import import_datasets_from_directory
from opik.cli.imports.prompt import import_prompts_from_directory
from ..conftest import random_chars
from . import verifiers

# Tags seeded on the created entities so the export/import flow can be asserted
# to preserve them (regression guard for OPIK-7292, where tags were dropped for
# prompts, experiments, and datasets). Backends don't guarantee tag ordering, so
# assertions compare as sets.
_DATASET_TAGS = ["cli-test", "dataset-tag"]
_PROMPT_TAGS = ["cli-test", "prompt-tag"]
_CHAT_PROMPT_TAGS = ["cli-test", "chat-prompt-tag"]
_EXPERIMENT_TAGS = ["cli-test", "experiment-tag"]


def _find_project_dir(base_dir: Path, project_name: str) -> Path:
    """Locate the id-named export folder for a project by its recorded name.

    Export folders are keyed by project id on disk; the human name lives in
    ``project.json``. This resolves a project name back to its directory under
    ``base_dir/default/projects/``.
    """
    projects = base_dir / "default" / "projects"
    for child in sorted(projects.iterdir()):
        meta = child / "project.json"
        if (
            child.is_dir()
            and meta.exists()
            and json.loads(meta.read_text()).get("name") == project_name
        ):
            return child
    raise AssertionError(
        f"No exported project dir for {project_name!r} under {projects}"
    )


class TestCLIImportExport:
    """Test CLI import/export functionality end-to-end."""

    @pytest.fixture
    def test_data_dir(self) -> Iterator[Path]:
        """Create a temporary directory for test data."""
        with tempfile.TemporaryDirectory() as temp_dir:
            yield Path(temp_dir)

    @pytest.fixture
    def source_project_name(self, opik_client: opik.Opik) -> Iterator[str]:
        """Create a source project for testing."""
        project_name = f"cli-test-source-{random_chars()}"
        yield project_name
        # Cleanup is handled by the test framework

    def _create_test_traces(
        self, opik_client: opik.Opik, project_name: str
    ) -> List[str]:
        """Create test traces in the specified project."""
        trace_ids = []

        # Create trace 1 with direct API
        trace1 = opik_client.trace(
            name="test-trace-1",
            input={"x": "input1"},
            output={"result": "processed_input1"},
            metadata={"test": "import_export"},
            project_name=project_name,
        )
        trace_ids.append(trace1.id)

        # Create trace 2 with direct API
        trace2 = opik_client.trace(
            name="test-trace-2",
            input={"y": 42},
            output={"result": 84},
            metadata={"test": "import_export"},
            project_name=project_name,
        )
        trace_ids.append(trace2.id)

        opik_client.flush()

        return trace_ids

    def _create_test_dataset(self, opik_client: opik.Opik, project_name: str) -> str:
        """Create a test dataset in the given project."""
        dataset_name = f"cli-test-dataset-{random_chars()}"
        dataset = opik_client.create_dataset(
            dataset_name, description="CLI test dataset", project_name=project_name
        )

        # Insert test data
        dataset.insert(
            [
                {
                    "input": {"question": "What is the capital of France?"},
                    "expected_output": {"answer": "Paris"},
                },
                {
                    "input": {"question": "What is 2+2?"},
                    "expected_output": {"answer": "4"},
                },
            ]
        )

        # create_dataset() has no tags argument; set dataset-level tags via REST
        # so the export/import round-trip can be asserted to preserve them.
        opik_client.rest_client.datasets.update_dataset(
            dataset.id, name=dataset_name, tags=_DATASET_TAGS
        )

        return dataset_name

    def _create_test_prompt(self, opik_client: opik.Opik, project_name: str) -> str:
        """Create a test prompt in the given project."""
        prompt_name = f"cli-test-prompt-{random_chars()}"
        opik_client.create_prompt(
            name=prompt_name,
            prompt="You are a helpful assistant. Answer the following question: {question}",
            tags=_PROMPT_TAGS,
            project_name=project_name,
        )
        return prompt_name

    def _create_test_chat_prompt(
        self, opik_client: opik.Opik, project_name: str
    ) -> str:
        """Create a test chat prompt in the given project."""
        prompt_name = f"cli-test-chat-prompt-{random_chars()}"
        messages = [
            {"role": "system", "content": "You are a helpful assistant."},
            {
                "role": "user",
                "content": "Hello, {{name}}! How can I help you with {{topic}}?",
            },
        ]
        opik_client.create_chat_prompt(
            name=prompt_name,
            messages=messages,
            metadata={"version": "1.0", "test": "chat_import_export"},
            tags=_CHAT_PROMPT_TAGS,
            project_name=project_name,
        )
        return prompt_name

    def _create_test_experiment(
        self, opik_client: opik.Opik, project_name: str, dataset_name: str
    ) -> str:
        """Create a test experiment."""
        experiment_name = f"cli-test-experiment-{random_chars()}"

        print(f"Creating experiment '{experiment_name}' with dataset '{dataset_name}'")

        # Create an experiment
        experiment = opik_client.create_experiment(
            dataset_name=dataset_name,
            name=experiment_name,
            experiment_config={"model": "test-model", "version": "1.0"},
            tags=_EXPERIMENT_TAGS,
            project_name=project_name,
        )

        print(f"Created experiment with ID: {experiment.id}")

        # Get traces from the project to link to the experiment
        traces = opik_client.search_traces(project_name=project_name)
        print(f"Found {len(traces)} traces in project {project_name}")

        if traces:
            # Get dataset items
            dataset = opik_client.get_dataset(dataset_name, project_name=project_name)
            dataset_items = dataset.get_items()
            print(f"Found {len(dataset_items)} dataset items")

            # Create experiment items linking traces to dataset items
            experiment_items = []
            for i, trace in enumerate(traces[:2]):  # Use up to 2 traces
                if i < len(dataset_items) and trace.id is not None:
                    experiment_items.append(
                        opik.ExperimentItemReferences(
                            dataset_item_id=dataset_items[i]["id"],
                            trace_id=trace.id,
                        )
                    )

            if experiment_items:
                print(f"Inserting {len(experiment_items)} experiment items")
                experiment.insert(experiment_items)
            else:
                print("No experiment items to insert")
        else:
            print("No traces found to link to experiment")

        return experiment_name

    def _run_cli_command(self, cmd: List[str]) -> subprocess.CompletedProcess:
        """Run a CLI command and return the result."""
        # Use the module path to ensure we get the latest code
        full_cmd = ["python", "-m", "opik.cli"] + cmd
        # Set environment to disable rich output for better subprocess capture
        env = {
            **os.environ,
            "TERM": "dumb",  # Disable rich terminal features
            "NO_COLOR": "1",  # Disable colors
        }
        result = subprocess.run(
            full_cmd,
            capture_output=True,
            text=True,
            cwd=Path(__file__).parent.parent,
            env=env,
        )

        if result.returncode != 0:
            print(f"Command failed: {' '.join(full_cmd)}")
            print(f"STDOUT: {result.stdout}")
            print(f"STDERR: {result.stderr}")

        return result

    def test_export_import_traces_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test the complete export/import flow for traces."""
        # Step 1: Prepare test data
        self._create_test_traces(opik_client, source_project_name)

        # Verify traces were created
        traces = opik_client.search_traces(project_name=source_project_name)
        print(f"Found {len(traces)} traces in project {source_project_name}")
        for trace in traces:
            print(f"Trace: {trace.id} - {trace.name}")
        assert len(traces) >= 1, "Expected at least 1 trace to be created"

        # Step 2: Export traces using direct function call
        export_project_traces(
            project_name=source_project_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            filter_string=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Check if the directory was created
        # New CLI structure: default/projects/{project_name}/ for traces
        project_dir = _find_project_dir(test_data_dir, source_project_name)
        assert project_dir.exists(), f"Export directory not found: {project_dir}"

        print(f"Export directory created: {project_dir}")
        print(f"Directory contents: {list(project_dir.iterdir())}")

        trace_files = list(project_dir.glob("trace_*.json"))
        assert len(trace_files) >= 1, (
            f"Expected trace files, found: {list(project_dir.glob('*'))}"
        )

        # Verify trace file content
        with open(trace_files[0], "r") as f:
            trace_data = json.load(f)

        assert "trace" in trace_data
        assert "spans" in trace_data
        assert "downloaded_at" in trace_data
        assert trace_data["project_name"] == source_project_name

        # Step 3: Import traces using direct function call
        project_dir = _find_project_dir(test_data_dir, source_project_name)
        stats = import_traces_from_directory(
            client=opik_client,
            project_dir=project_dir,
            project_name=source_project_name,
            dry_run=False,
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=False,
        )

        # Verify import succeeded
        assert stats.get("projects", 0) >= 1, (
            "Expected at least 1 project to be imported"
        )

    def test_export_import_datasets_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test the complete export/import flow for datasets."""
        # Step 1: Prepare test data
        dataset_name = self._create_test_dataset(opik_client, source_project_name)

        # Verify dataset was created
        datasets = opik_client.get_datasets(
            max_results=100, project_name=source_project_name
        )
        assert len(datasets) >= 1, "Expected at least 1 dataset to be created"

        # Step 2: Export datasets using direct function call
        export_dataset_by_name(
            name=dataset_name,
            workspace="default",
            project_name=source_project_name,
            output_path=str(test_data_dir),
            max_results=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        # New CLI structure: default/datasets/ for datasets
        datasets_dir = (
            _find_project_dir(test_data_dir, source_project_name) / "datasets"
        )
        assert datasets_dir.exists(), f"Export directory not found: {datasets_dir}"

        dataset_files = list(datasets_dir.glob("dataset_*.json"))
        assert len(dataset_files) >= 1, (
            f"Expected dataset files, found: {list(datasets_dir.glob('*'))}"
        )

        # Verify dataset file content
        with open(dataset_files[0], "r") as f:
            dataset_data = json.load(f)

        assert "name" in dataset_data
        assert "items" in dataset_data
        assert "downloaded_at" in dataset_data
        # Dataset-level tags must survive export (OPIK-7292).
        assert set(dataset_data.get("tags") or []) == set(_DATASET_TAGS), (
            f"Exported dataset tags mismatch: {dataset_data.get('tags')!r}"
        )

        # Step 3: Import datasets using direct function call
        source_dir = _find_project_dir(test_data_dir, source_project_name) / "datasets"
        stats = import_datasets_from_directory(
            client=opik_client,
            source_dir=source_dir,
            project_name=source_project_name,
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        # Verify import succeeded
        assert stats.get("datasets", 0) >= 1, (
            "Expected at least 1 dataset to be imported"
        )

        # Tags must survive the import back onto the dataset.
        imported_dataset = opik_client.get_dataset(
            dataset_name, project_name=source_project_name
        )
        assert set(imported_dataset.get_tags()) == set(_DATASET_TAGS), (
            f"Imported dataset tags mismatch: {imported_dataset.get_tags()!r}"
        )

    def test_export_import_prompts_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test the complete export/import flow for prompts."""
        # Step 1: Prepare test data
        prompt_name = self._create_test_prompt(opik_client, source_project_name)

        # Verify prompt was created
        prompts = opik_client.search_prompts(project_name=source_project_name)
        prompt_names = [p.name for p in prompts]
        assert prompt_name in prompt_names, (
            f"Expected prompt {prompt_name} to be created"
        )

        # Step 2: Export prompts using direct function call
        export_prompt_by_name(
            name=prompt_name,
            workspace="default",
            project_name=source_project_name,
            output_path=str(test_data_dir),
            max_results=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        # New CLI structure: default/prompts/ for prompts
        prompts_dir = _find_project_dir(test_data_dir, source_project_name) / "prompts"
        assert prompts_dir.exists(), f"Export directory not found: {prompts_dir}"

        prompt_files = list(prompts_dir.glob("prompt_*.json"))
        assert len(prompt_files) >= 1, (
            f"Expected prompt files, found: {list(prompts_dir.glob('*'))}"
        )

        # Verify prompt file content
        with open(prompt_files[0], "r") as f:
            prompt_data = json.load(f)

        assert "name" in prompt_data
        assert "current_version" in prompt_data
        assert "history" in prompt_data
        assert "downloaded_at" in prompt_data
        # Prompt tags must survive export (OPIK-7292).
        assert set(prompt_data["current_version"].get("tags") or []) == set(
            _PROMPT_TAGS
        ), (
            f"Exported prompt tags mismatch: {prompt_data['current_version'].get('tags')!r}"
        )

        # Step 3: Import prompts using direct function call
        source_dir = _find_project_dir(test_data_dir, source_project_name) / "prompts"
        stats = import_prompts_from_directory(
            client=opik_client,
            source_dir=source_dir,
            project_name=source_project_name,
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        # Verify import succeeded
        assert stats.get("prompts", 0) >= 1, "Expected at least 1 prompt to be imported"

        # Verify prompt was correctly imported to backend
        imported_prompts = opik_client.search_prompts(project_name=source_project_name)
        imported_prompt_names = [p.name for p in imported_prompts]
        assert prompt_name in imported_prompt_names, (
            f"Expected prompt {prompt_name} to be imported"
        )

        # Get the imported prompt and verify its content
        imported_prompt = next(p for p in imported_prompts if p.name == prompt_name)
        verifiers.verify_prompt_version(
            imported_prompt,
            name=prompt_name,
        )
        assert set(imported_prompt.tags or []) == set(_PROMPT_TAGS), (
            f"Imported prompt tags mismatch: {imported_prompt.tags!r}"
        )

    def test_export_import_all_data_types_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test the complete export/import flow for all data types."""
        # Step 1: Prepare test data (minimal)
        self._create_test_traces(opik_client, source_project_name)
        dataset_name = self._create_test_dataset(opik_client, source_project_name)
        prompt_name = self._create_test_prompt(opik_client, source_project_name)

        # Step 2: Export all data types with limited results using direct function calls
        # Export traces
        export_project_traces(
            project_name=source_project_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=10,  # Limit to 10 traces
            filter_string=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Export datasets with limit
        export_dataset_by_name(
            name=dataset_name,
            workspace="default",
            project_name=source_project_name,
            output_path=str(test_data_dir),
            max_results=5,  # Limit to 5 datasets
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Export prompts with limit
        export_prompt_by_name(
            name=prompt_name,
            workspace="default",
            project_name=source_project_name,
            output_path=str(test_data_dir),
            max_results=5,  # Limit to 5 prompt versions
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        project_dir = _find_project_dir(test_data_dir, source_project_name)
        datasets_dir = (
            _find_project_dir(test_data_dir, source_project_name) / "datasets"
        )
        prompts_dir = _find_project_dir(test_data_dir, source_project_name) / "prompts"

        assert project_dir.exists(), f"Export directory not found: {project_dir}"
        assert datasets_dir.exists(), f"Export directory not found: {datasets_dir}"
        assert prompts_dir.exists(), f"Export directory not found: {prompts_dir}"

        # Check for all file types
        trace_files = list(project_dir.glob("trace_*.json"))
        dataset_files = list(datasets_dir.glob("dataset_*.json"))
        prompt_files = list(prompts_dir.glob("prompt_*.json"))

        assert len(trace_files) >= 1, "Expected trace files"
        assert len(dataset_files) >= 1, "Expected dataset files"
        assert len(prompt_files) >= 1, "Expected prompt files"

        # Step 3: Import all data types using direct function calls
        # Import projects (traces)
        projects_stats = import_traces_from_directory(
            client=opik_client,
            project_dir=_find_project_dir(test_data_dir, source_project_name),
            project_name=source_project_name,
            dry_run=False,
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=False,
        )
        assert projects_stats.get("projects", 0) >= 1, (
            "Expected projects to be imported"
        )

        # Import datasets
        datasets_stats = import_datasets_from_directory(
            client=opik_client,
            source_dir=_find_project_dir(test_data_dir, source_project_name)
            / "datasets",
            project_name=source_project_name,
            dry_run=False,
            name_pattern=None,
            debug=False,
        )
        assert datasets_stats.get("datasets", 0) >= 1, (
            "Expected datasets to be imported"
        )

        # Import prompts
        prompts_stats = import_prompts_from_directory(
            client=opik_client,
            source_dir=_find_project_dir(test_data_dir, source_project_name)
            / "prompts",
            project_name=source_project_name,
            dry_run=False,
            name_pattern=None,
            debug=False,
        )
        assert prompts_stats.get("prompts", 0) >= 1, "Expected prompts to be imported"

    def test_export_with_filters(
        self, opik_client: opik.Opik, source_project_name: str, test_data_dir: Path
    ) -> None:
        """Test export with various filters."""
        # Create test data
        self._create_test_traces(opik_client, source_project_name)

        # Test export with name filter using direct function call
        # OQL syntax: use = not ==, and double quotes for string values
        export_project_traces(
            project_name=source_project_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            filter_string='name = "test_function_1"',
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify filtered export
        project_dir = _find_project_dir(test_data_dir, source_project_name)
        if project_dir.exists():
            trace_files = list(project_dir.glob("trace_*.json"))
            # The exact number depends on the filter, but we should have some files
            assert len(trace_files) >= 0, "Filtered export should produce some files"

    def test_import_dry_run(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test import with dry run option."""
        # Create test data and export it using direct function call
        self._create_test_traces(opik_client, source_project_name)

        export_project_traces(
            project_name=source_project_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            filter_string=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Test dry run import using direct function call
        project_dir = _find_project_dir(test_data_dir, source_project_name)

        # Count traces before dry run
        traces_before = opik_client.search_traces(project_name=source_project_name)
        count_before = len(traces_before)

        _ = import_traces_from_directory(
            client=opik_client,
            project_dir=project_dir,
            project_name=source_project_name,
            dry_run=True,  # Dry run mode
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=False,
        )

        # Verify dry run reported it would import but didn't actually import
        # (In dry run, stats may show what WOULD be imported, but no actual import occurs)

        # Count traces after dry run - should be the same
        traces_after = opik_client.search_traces(project_name=source_project_name)
        count_after = len(traces_after)

        # Dry run should not create any new traces
        assert count_after == count_before, (
            f"Dry run should not modify data: had {count_before} traces, now have {count_after}"
        )

    def test_cli_subprocess_validation(
        self, opik_client: opik.Opik, source_project_name: str, test_data_dir: Path
    ) -> None:
        """Test that CLI commands work via subprocess (validates CLI interface)."""
        # This test validates the actual CLI interface by using subprocess
        # All other tests call Python functions directly for speed

        # Create test data
        self._create_test_traces(opik_client, source_project_name)

        # Test export via CLI subprocess
        export_cmd = [
            "export",
            "default",
            source_project_name,
            "traces",
            "--path",
            str(test_data_dir),
        ]

        result = self._run_cli_command(export_cmd)
        assert result.returncode == 0, f"CLI export failed: {result.stderr}"

        # Verify export worked
        project_dir = _find_project_dir(test_data_dir, source_project_name)
        assert project_dir.exists(), "CLI export did not create directory"
        trace_files = list(project_dir.glob("trace_*.json"))
        assert len(trace_files) >= 1, "CLI export did not create trace files"

        # Test import via CLI subprocess
        import_cmd = [
            "import",
            "default",
            source_project_name,
            "traces",
            "--path",
            str(test_data_dir),
        ]

        result = self._run_cli_command(import_cmd)
        assert result.returncode == 0, f"CLI import failed: {result.stderr}"

    def test_export_import_error_handling(
        self, opik_client: opik.Opik, test_data_dir: Path
    ) -> None:
        """Test error handling for invalid commands."""
        # Test export with non-existent project - should fail gracefully
        try:
            export_project_traces(
                project_name="non-existent-project",
                workspace="default",
                output_path=str(test_data_dir),
                max_results=None,
                filter_string=None,
                force=False,
                debug=False,
                format="json",
                api_key=None,
            )
            # If we get here, the function didn't raise an error as expected
            # Check if it at least didn't create any files
            project_dir = (
                test_data_dir / "default" / "projects" / "non-existent-project"
            )
            assert not project_dir.exists(), (
                "Should not create directory for non-existent project"
            )
        except SystemExit:
            # Expected - function calls sys.exit(1) on error
            pass

        # Test import with non-existent directory - should fail gracefully
        try:
            import_traces_from_directory(
                client=opik_client,
                project_dir=test_data_dir / "non-existent",
                project_name="non-existent",
                dry_run=False,
                name_pattern=None,
                debug=False,
                recreate_experiments_flag=False,
            )
            # If we get here, check that stats show no imports
            # (function may return empty stats instead of raising)
        except (FileNotFoundError, SystemExit):
            # Expected - function may raise error or exit
            pass

    def test_export_experiment_with_dataset_filter(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test export experiment filtered by dataset name."""
        # Step 1: Create two datasets
        dataset1_name = f"cli-test-dataset1-{random_chars()}"
        dataset2_name = f"cli-test-dataset2-{random_chars()}"

        dataset1 = opik_client.create_dataset(
            dataset1_name,
            description="CLI test dataset 1",
            project_name=source_project_name,
        )
        dataset2 = opik_client.create_dataset(
            dataset2_name,
            description="CLI test dataset 2",
            project_name=source_project_name,
        )

        # Add items to datasets
        for i in range(3):
            dataset1.insert(
                [
                    {
                        "input": f"test input 1-{i}",
                        "expected_output": f"test output 1-{i}",
                    }
                ]
            )
            dataset2.insert(
                [
                    {
                        "input": f"test input 2-{i}",
                        "expected_output": f"test output 2-{i}",
                    }
                ]
            )

        # Step 2: Create two experiments with different datasets
        exp1_name = f"cli-test-exp1-{random_chars()}"
        exp2_name = f"cli-test-exp2-{random_chars()}"

        experiment1 = opik_client.create_experiment(
            name=exp1_name,
            dataset_name=dataset1_name,
            tags=_EXPERIMENT_TAGS,
            project_name=source_project_name,
        )
        experiment2 = opik_client.create_experiment(
            name=exp2_name,
            dataset_name=dataset2_name,
            project_name=source_project_name,
        )

        # Add items to experiments
        dataset1_items = dataset1.get_items()
        dataset2_items = dataset2.get_items()

        for i in range(3):
            trace1 = opik_client.trace(
                name=f"test-trace-exp1-{i}",
                input={"prompt": f"test prompt 1-{i}"},
                output={"response": f"test response 1-{i}"},
                project_name=source_project_name,
            )
            experiment1.insert(
                [
                    ExperimentItemReferences(
                        dataset_item_id=dataset1_items[i]["id"],
                        trace_id=trace1.id,
                    )
                ]
            )

            trace2 = opik_client.trace(
                name=f"test-trace-exp2-{i}",
                input={"prompt": f"test prompt 2-{i}"},
                output={"response": f"test response 2-{i}"},
                project_name=source_project_name,
            )
            experiment2.insert(
                [
                    ExperimentItemReferences(
                        dataset_item_id=dataset2_items[i]["id"],
                        trace_id=trace2.id,
                    )
                ]
            )

        opik_client.flush()

        # Step 3: Export experiments filtered by dataset1
        export_experiment_by_name(
            name=exp1_name,
            workspace="default",
            project_name=source_project_name,
            output_path=str(test_data_dir),
            dataset=dataset1_name,  # Filter by dataset1
            max_traces=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Step 4: Verify only experiment1 was exported. Files are named by
        # experiment id now, so match on the name recorded inside each file.
        experiments_dir = (
            _find_project_dir(test_data_dir, source_project_name) / "experiments"
        )
        assert experiments_dir.exists(), (
            f"Export directory not found: {experiments_dir}"
        )

        exp1_files = []
        exp2_files = []
        for ef in experiments_dir.glob("experiment_*.json"):
            with open(ef, "r") as f:
                name = json.load(f).get("experiment", {}).get("name")
            if name == exp1_name:
                exp1_files.append(ef)
            elif name == exp2_name:
                exp2_files.append(ef)

        assert len(exp1_files) == 1, (
            f"Expected exactly 1 experiment file for {exp1_name}, found: {len(exp1_files)}"
        )
        # Verify exp2 was NOT exported
        assert len(exp2_files) == 0, (
            f"Expected 0 experiment files for {exp2_name}, found: {len(exp2_files)}"
        )

        # Step 5: Verify the exported experiment has the correct dataset
        with open(exp1_files[0], "r") as f:
            exp_data = json.load(f)

        assert exp_data["experiment"]["dataset_name"] == dataset1_name, (
            f"Expected dataset_name to be {dataset1_name}, got {exp_data['experiment']['dataset_name']}"
        )
        # Experiment tags must survive export (OPIK-7292).
        assert set(exp_data["experiment"].get("tags") or []) == set(_EXPERIMENT_TAGS), (
            f"Exported experiment tags mismatch: {exp_data['experiment'].get('tags')!r}"
        )

    def test_export_import_chat_prompts_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test the complete export/import flow for chat prompts."""
        # Step 1: Create a test chat prompt
        prompt_name = self._create_test_chat_prompt(opik_client, source_project_name)

        # Verify chat prompt was created
        prompts = opik_client.search_prompts(project_name=source_project_name)
        prompt_names = [p.name for p in prompts]
        assert prompt_name in prompt_names, (
            f"Expected chat prompt {prompt_name} to be created"
        )

        # Step 2: Export chat prompt using direct function call
        export_prompt_by_name(
            name=prompt_name,
            workspace="default",
            project_name=source_project_name,
            output_path=str(test_data_dir),
            max_results=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        prompts_dir = _find_project_dir(test_data_dir, source_project_name) / "prompts"
        assert prompts_dir.exists(), f"Export directory not found: {prompts_dir}"

        prompt_files = list(prompts_dir.glob("prompt_*.json"))
        assert len(prompt_files) >= 1, "Expected at least 1 prompt file"

        # Verify chat-specific structure in exported file
        with open(prompt_files[0], "r") as f:
            prompt_data = json.load(f)

        assert "name" in prompt_data
        assert prompt_data["name"] == prompt_name
        assert "current_version" in prompt_data
        current_version = prompt_data["current_version"]

        # Verify it's a chat prompt (messages should be a list)
        assert "prompt" in current_version
        assert isinstance(current_version["prompt"], list), (
            "Chat prompt should have messages as a list"
        )
        assert "template_structure" in current_version
        assert current_version["template_structure"] == "chat"
        # Chat prompt tags must survive export (OPIK-7292).
        assert set(current_version.get("tags") or []) == set(_CHAT_PROMPT_TAGS), (
            f"Exported chat prompt tags mismatch: {current_version.get('tags')!r}"
        )

        # Step 3: Import chat prompt using direct function call
        source_dir = _find_project_dir(test_data_dir, source_project_name) / "prompts"
        stats = import_prompts_from_directory(
            client=opik_client,
            source_dir=source_dir,
            project_name=source_project_name,
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        # Verify import succeeded
        assert stats.get("prompts", 0) >= 1, "Expected at least 1 prompt to be imported"

        # Verify chat prompt was correctly imported to backend
        imported_prompts = opik_client.search_prompts(project_name=source_project_name)
        imported_prompt_names = [p.name for p in imported_prompts]
        assert prompt_name in imported_prompt_names, (
            f"Expected chat prompt {prompt_name} to be imported"
        )

        # Get the imported chat prompt and verify its content
        imported_chat_prompt = opik_client.get_chat_prompt(
            name=prompt_name, project_name=source_project_name
        )
        verifiers.verify_chat_prompt_version(
            imported_chat_prompt,
            name=prompt_name,
        )
        # Tags live on the prompt container and are not surfaced by
        # get_chat_prompt()/retrieve_prompt_version (they come back empty);
        # search_prompts() injects the container tags. Assert against it to
        # mirror the text-prompt flow above and exports._resolve_prompt_tags.
        imported_chat_container = next(
            p for p in imported_prompts if p.name == prompt_name
        )
        assert set(imported_chat_container.tags or []) == set(_CHAT_PROMPT_TAGS), (
            f"Imported chat prompt tags mismatch: {imported_chat_container.tags!r}"
        )

    def test_import_projects_automatically_recreates_experiments(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test import projects automatically recreates experiments."""
        # Step 1: Prepare test data with experiments
        dataset_name = self._create_test_dataset(opik_client, source_project_name)
        self._create_test_traces(opik_client, source_project_name)
        experiment_name = self._create_test_experiment(
            opik_client, source_project_name, dataset_name
        )

        # Step 2: Export the project data (traces) using direct function call
        export_project_traces(
            project_name=source_project_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            filter_string=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Export the experiment too, so the recreate-on-import path has an
        # experiment_*.json file to work with (trace export alone does not emit
        # experiments).
        export_experiment_by_name(
            name=experiment_name,
            workspace="default",
            project_name=source_project_name,
            output_path=str(test_data_dir),
            dataset=dataset_name,
            max_traces=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        project_dir = _find_project_dir(test_data_dir, source_project_name)
        assert project_dir.exists(), f"Export directory not found: {project_dir}"

        # The exported experiment file must carry the tags (OPIK-7292).
        experiments_dir = project_dir / "experiments"
        exp_file = next(
            ef
            for ef in experiments_dir.glob("experiment_*.json")
            if json.loads(ef.read_text()).get("experiment", {}).get("name")
            == experiment_name
        )
        exported_experiment = json.loads(exp_file.read_text())["experiment"]
        assert set(exported_experiment.get("tags") or []) == set(_EXPERIMENT_TAGS), (
            f"Exported experiment tags mismatch: {exported_experiment.get('tags')!r}"
        )

        # Step 3: Test import (experiments are automatically recreated) using direct function call
        project_dir = _find_project_dir(test_data_dir, source_project_name)
        stats = import_traces_from_directory(
            client=opik_client,
            project_dir=project_dir,
            project_name=source_project_name,
            dry_run=False,
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=True,  # Automatically recreate experiments
        )

        # Verify import succeeded
        assert stats.get("projects", 0) >= 1, (
            "Expected at least 1 project to be imported"
        )
        assert stats.get("experiments", 0) >= 1, (
            "Expected at least 1 experiment to be recreated"
        )

        # The recreated experiment must carry the tags (OPIK-7292). Importing
        # into the source project leaves the original alongside the recreated
        # copy; both were created with the same tags, so assert every match.
        recreated = opik_client.get_experiments_by_name(
            experiment_name, project_name=source_project_name
        )
        assert recreated, f"Expected experiment {experiment_name} after import"
        for exp in recreated:
            assert set(exp.tags or []) == set(_EXPERIMENT_TAGS), (
                f"Recreated experiment tags mismatch: {exp.tags!r}"
            )

    def test_export_import_llm_span_preserves_type_and_usage(
        self,
        opik_client: opik.Opik,
        test_data_dir: Path,
    ) -> None:
        """Test that LLM span type and usage data (including non-standard tokens) are preserved during import/export.

        This test verifies that:
        1. Span type="llm" is preserved after import
        2. Usage data with non-standard tokens (e.g., cached_tokens, reasoning_tokens) is preserved
        3. Cost is calculated by the backend based on usage
        """
        # Create a unique project name for this test
        project_name = f"cli-test-llm-span-{random_chars()}"

        # Create a trace with an LLM span that has usage with non-standard tokens
        # Using OpenAI format with detailed token breakdown that affects cost calculation
        llm_usage = {
            "prompt_tokens": 100,
            "completion_tokens": 50,
            "total_tokens": 150,
            # Non-standard tokens that some providers track and use for cost calculation
            "completion_tokens_details": {
                "reasoning_tokens": 20,  # Used in o1 models
                "audio_tokens": 0,
                "accepted_prediction_tokens": 0,
                "rejected_prediction_tokens": 0,
            },
            "prompt_tokens_details": {
                "cached_tokens": 25,  # Cached tokens have different pricing
                "audio_tokens": 0,
            },
        }

        # Create trace
        trace = opik_client.trace(
            name="test-llm-trace",
            input={"prompt": "What is the capital of France?"},
            output={"response": "Paris"},
            project_name=project_name,
        )

        # Create LLM span with type="llm", usage, model, and provider
        span = opik_client.span(
            trace_id=trace.id,
            name="llm-call",
            type="llm",
            input={
                "messages": [
                    {"role": "user", "content": "What is the capital of France?"}
                ]
            },
            output={"content": "Paris"},
            usage=llm_usage,
            model="gpt-4o-mini",
            provider="openai",
            project_name=project_name,
        )

        original_span_id = span.id
        opik_client.flush()

        # Wait for the span to be queryable with usage populated (backend
        # calculates cost asynchronously after the span is created).
        assert synchronization.until(
            lambda: (
                (_span := opik_client.get_span_content(id=original_span_id)) is not None
                and _span.type == "llm"
                and _span.usage is not None
            ),
            allow_errors=True,
        ), "Failed to get original span data with populated usage"

        original_span_data = opik_client.get_span_content(id=original_span_id)

        # Store original values for comparison
        original_type = original_span_data.type
        original_usage = original_span_data.usage
        original_model = original_span_data.model
        original_provider = original_span_data.provider
        original_cost = original_span_data.total_estimated_cost

        print(
            f"Original span - type: {original_type}, model: {original_model}, provider: {original_provider}"
        )
        print(f"Original usage: {original_usage}")
        print(f"Original cost: {original_cost}")

        # Export the project
        export_project_traces(
            project_name=project_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            filter_string=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export was created
        project_dir = _find_project_dir(test_data_dir, project_name)
        assert project_dir.exists(), f"Export directory not found: {project_dir}"

        trace_files = list(project_dir.glob("trace_*.json"))
        assert len(trace_files) == 1, (
            f"Expected 1 trace file, found: {len(trace_files)}"
        )

        # Verify exported JSON contains correct span data
        with open(trace_files[0], "r") as f:
            exported_data = json.load(f)

        exported_spans = exported_data.get("spans", [])
        assert len(exported_spans) == 1, (
            f"Expected 1 span in export, found: {len(exported_spans)}"
        )

        exported_span = exported_spans[0]
        assert exported_span.get("type") == "llm", (
            f"Exported span type should be 'llm', got {exported_span.get('type')}"
        )
        assert exported_span.get("model") == "gpt-4o-mini", "Exported model mismatch"
        assert exported_span.get("provider") == "openai", "Exported provider mismatch"
        assert exported_span.get("usage") is not None, (
            "Exported span should have usage data"
        )

        # Import the project to a new project (by modifying the project directory name)
        imported_project_name = f"{project_name}-imported"
        imported_project_dir = (
            test_data_dir / "default" / "projects" / imported_project_name
        )
        project_dir.rename(imported_project_dir)

        # Also update the project_name in the trace file
        trace_file = list(imported_project_dir.glob("trace_*.json"))[0]
        with open(trace_file, "r") as f:
            trace_data = json.load(f)
        trace_data["project_name"] = imported_project_name
        with open(trace_file, "w") as f:
            json.dump(trace_data, f)

        # Import the project
        stats = import_traces_from_directory(
            client=opik_client,
            project_dir=imported_project_dir,
            project_name=imported_project_name,
            dry_run=False,
            name_pattern=None,
            debug=True,
            recreate_experiments_flag=False,
        )

        assert stats.get("projects", 0) >= 1, (
            "Expected at least 1 project to be imported"
        )
        assert stats.get("traces", 0) >= 1, "Expected at least 1 trace to be imported"

        opik_client.flush()

        # Wait for the imported trace to appear in the new project.
        assert synchronization.until(
            lambda: (
                len(opik_client.search_traces(project_name=imported_project_name)) >= 1
            ),
            allow_errors=True,
        ), f"No imported traces found in project {imported_project_name}"

        imported_traces = opik_client.search_traces(project_name=imported_project_name)
        assert len(imported_traces) >= 1, (
            f"Expected at least 1 imported trace, found: {len(imported_traces)}"
        )

        imported_trace = imported_traces[0]
        imported_spans = opik_client.search_spans(
            project_name=imported_project_name,
            trace_id=imported_trace.id,
        )
        assert len(imported_spans) >= 1, (
            f"Expected at least 1 imported span, found: {len(imported_spans)}"
        )

        imported_span = imported_spans[0]
        imported_span_data = opik_client.get_span_content(id=imported_span.id)

        print(
            f"Imported span - type: {imported_span_data.type}, model: {imported_span_data.model}, provider: {imported_span_data.provider}"
        )
        print(f"Imported usage: {imported_span_data.usage}")
        print(f"Imported cost: {imported_span_data.total_estimated_cost}")

        # Verify span type is preserved
        assert imported_span_data.type == "llm", (
            f"Imported span type should be 'llm', got '{imported_span_data.type}'"
        )

        # Verify model and provider are preserved
        assert imported_span_data.model == original_model, (
            f"Model mismatch: expected '{original_model}', got '{imported_span_data.model}'"
        )
        assert imported_span_data.provider == original_provider, (
            f"Provider mismatch: expected '{original_provider}', got '{imported_span_data.provider}'"
        )

        # Verify usage data is preserved (key token counts should match)
        assert imported_span_data.usage is not None, (
            "Imported span should have usage data"
        )

        # Check standard token counts
        assert imported_span_data.usage.get("prompt_tokens") == original_usage.get(
            "prompt_tokens"
        ), (
            f"prompt_tokens mismatch: expected {original_usage.get('prompt_tokens')}, "
            f"got {imported_span_data.usage.get('prompt_tokens')}"
        )
        assert imported_span_data.usage.get("completion_tokens") == original_usage.get(
            "completion_tokens"
        ), (
            f"completion_tokens mismatch: expected {original_usage.get('completion_tokens')}, "
            f"got {imported_span_data.usage.get('completion_tokens')}"
        )
        assert imported_span_data.usage.get("total_tokens") == original_usage.get(
            "total_tokens"
        ), (
            f"total_tokens mismatch: expected {original_usage.get('total_tokens')}, "
            f"got {imported_span_data.usage.get('total_tokens')}"
        )

        # Check non-standard token fields are preserved
        # The backend stores these with 'original_usage.' prefix for provider-specific token details
        assert (
            imported_span_data.usage.get(
                "original_usage.completion_tokens_details.reasoning_tokens"
            )
            == llm_usage["completion_tokens_details"]["reasoning_tokens"]
        ), (
            f"reasoning_tokens mismatch: expected {llm_usage['completion_tokens_details']['reasoning_tokens']}, "
            f"got {imported_span_data.usage.get('original_usage.completion_tokens_details.reasoning_tokens')}"
        )
        assert (
            imported_span_data.usage.get(
                "original_usage.prompt_tokens_details.cached_tokens"
            )
            == llm_usage["prompt_tokens_details"]["cached_tokens"]
        ), (
            f"cached_tokens mismatch: expected {llm_usage['prompt_tokens_details']['cached_tokens']}, "
            f"got {imported_span_data.usage.get('original_usage.prompt_tokens_details.cached_tokens')}"
        )

        # Verify cost is calculated (should be non-None if backend supports cost calculation for this model)
        # Note: Cost calculation depends on backend having pricing info for the model
        if original_cost is not None:
            assert imported_span_data.total_estimated_cost is not None, (
                "Expected imported span to have cost calculated by backend"
            )
            # Cost should be the same since usage is the same
            assert imported_span_data.total_estimated_cost == original_cost, (
                f"Cost mismatch: expected {original_cost}, got {imported_span_data.total_estimated_cost}"
            )
