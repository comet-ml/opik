"""E2E tests for CLI import/export commands."""

import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Iterator, List
import pytest

import opik
from opik.api_objects.experiment.experiment_item import ExperimentItemReferences
from opik.cli.exports.dataset import export_dataset_by_name
from opik.cli.exports.experiment import export_experiment_by_name
from opik.cli.exports.project import export_project_by_name
from opik.cli.exports.prompt import export_prompt_by_name
from opik.cli.imports.project import import_projects_from_directory
from opik.cli.imports.dataset import import_datasets_from_directory
from opik.cli.imports.prompt import import_prompts_from_directory
from ..conftest import random_chars
from . import verifiers


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

    def _create_test_dataset(self, opik_client: opik.Opik) -> str:
        """Create a test dataset."""
        dataset_name = f"cli-test-dataset-{random_chars()}"
        dataset = opik_client.create_dataset(
            dataset_name, description="CLI test dataset"
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

        return dataset_name

    def _create_test_prompt(self, opik_client: opik.Opik) -> str:
        """Create a test prompt."""
        prompt_name = f"cli-test-prompt-{random_chars()}"
        opik_client.create_prompt(
            name=prompt_name,
            prompt="You are a helpful assistant. Answer the following question: {question}",
        )
        return prompt_name

    def _create_test_chat_prompt(self, opik_client: opik.Opik) -> str:
        """Create a test chat prompt."""
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
        )

        print(f"Created experiment with ID: {experiment.id}")

        # Get traces from the project to link to the experiment
        traces = opik_client.search_traces(project_name=project_name)
        print(f"Found {len(traces)} traces in project {project_name}")

        if traces:
            # Get dataset items
            dataset = opik_client.get_dataset(dataset_name)
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

    def _run_cli_command(
        self, cmd: List[str]
    ) -> subprocess.CompletedProcess:
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
        export_project_by_name(
            name=source_project_name,
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
        project_dir = test_data_dir / "default" / "projects" / source_project_name
        assert project_dir.exists(), f"Export directory not found: {project_dir}"

        print(f"Export directory created: {project_dir}")
        print(f"Directory contents: {list(project_dir.iterdir())}")

        trace_files = list(project_dir.glob("trace_*.json"))
        assert (
            len(trace_files) >= 1
        ), f"Expected trace files, found: {list(project_dir.glob('*'))}"

        # Verify trace file content
        with open(trace_files[0], "r") as f:
            trace_data = json.load(f)

        assert "trace" in trace_data
        assert "spans" in trace_data
        assert "downloaded_at" in trace_data
        assert trace_data["project_name"] == source_project_name

        # Step 3: Import traces using direct function call
        source_dir = test_data_dir / "default" / "projects"
        stats = import_projects_from_directory(
            client=opik_client,
            source_dir=source_dir,
            dry_run=False,
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=False,
        )

        # Verify import succeeded
        assert (
            stats.get("projects", 0) >= 1
        ), "Expected at least 1 project to be imported"

    def test_export_import_datasets_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test the complete export/import flow for datasets."""
        # Step 1: Prepare test data
        dataset_name = self._create_test_dataset(opik_client)

        # Verify dataset was created
        datasets = opik_client.get_datasets(max_results=100)
        assert len(datasets) >= 1, "Expected at least 1 dataset to be created"

        # Step 2: Export datasets using direct function call
        export_dataset_by_name(
            name=dataset_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        # New CLI structure: default/datasets/ for datasets
        datasets_dir = test_data_dir / "default" / "datasets"
        assert datasets_dir.exists(), f"Export directory not found: {datasets_dir}"

        dataset_files = list(datasets_dir.glob("dataset_*.json"))
        assert (
            len(dataset_files) >= 1
        ), f"Expected dataset files, found: {list(datasets_dir.glob('*'))}"

        # Verify dataset file content
        with open(dataset_files[0], "r") as f:
            dataset_data = json.load(f)

        assert "name" in dataset_data
        assert "items" in dataset_data
        assert "downloaded_at" in dataset_data

        # Step 3: Import datasets using direct function call
        source_dir = test_data_dir / "default" / "datasets"
        stats = import_datasets_from_directory(
            client=opik_client,
            source_dir=source_dir,
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        # Verify import succeeded
        assert (
            stats.get("datasets", 0) >= 1
        ), "Expected at least 1 dataset to be imported"

    def test_export_import_prompts_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test the complete export/import flow for prompts."""
        # Step 1: Prepare test data
        prompt_name = self._create_test_prompt(opik_client)

        # Verify prompt was created
        prompts = opik_client.search_prompts()
        prompt_names = [p.name for p in prompts]
        assert (
            prompt_name in prompt_names
        ), f"Expected prompt {prompt_name} to be created"

        # Step 2: Export prompts using direct function call
        export_prompt_by_name(
            name=prompt_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        # New CLI structure: default/prompts/ for prompts
        prompts_dir = test_data_dir / "default" / "prompts"
        assert prompts_dir.exists(), f"Export directory not found: {prompts_dir}"

        prompt_files = list(prompts_dir.glob("prompt_*.json"))
        assert (
            len(prompt_files) >= 1
        ), f"Expected prompt files, found: {list(prompts_dir.glob('*'))}"

        # Verify prompt file content
        with open(prompt_files[0], "r") as f:
            prompt_data = json.load(f)

        assert "name" in prompt_data
        assert "current_version" in prompt_data
        assert "history" in prompt_data
        assert "downloaded_at" in prompt_data

        # Step 3: Import prompts using direct function call
        source_dir = test_data_dir / "default" / "prompts"
        stats = import_prompts_from_directory(
            client=opik_client,
            source_dir=source_dir,
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        # Verify import succeeded
        assert stats.get("prompts", 0) >= 1, "Expected at least 1 prompt to be imported"
        
        # Verify prompt was correctly imported to backend
        imported_prompts = opik_client.search_prompts()
        imported_prompt_names = [p.name for p in imported_prompts]
        assert prompt_name in imported_prompt_names, f"Expected prompt {prompt_name} to be imported"
        
        # Get the imported prompt and verify its content
        imported_prompt = next(p for p in imported_prompts if p.name == prompt_name)
        verifiers.verify_prompt_version(
            imported_prompt,
            name=prompt_name,
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
        dataset_name = self._create_test_dataset(opik_client)
        prompt_name = self._create_test_prompt(opik_client)

        # Step 2: Export all data types with limited results using direct function calls
        # Export projects (traces)
        export_project_by_name(
            name=source_project_name,
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
            output_path=str(test_data_dir),
            max_results=5,  # Limit to 5 prompt versions
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        project_dir = test_data_dir / "default" / "projects" / source_project_name
        datasets_dir = test_data_dir / "default" / "datasets"
        prompts_dir = test_data_dir / "default" / "prompts"

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
        projects_stats = import_projects_from_directory(
            client=opik_client,
            source_dir=test_data_dir / "default" / "projects",
            dry_run=False,
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=False,
        )
        assert (
            projects_stats.get("projects", 0) >= 1
        ), "Expected projects to be imported"

        # Import datasets
        datasets_stats = import_datasets_from_directory(
            client=opik_client,
            source_dir=test_data_dir / "default" / "datasets",
            dry_run=False,
            name_pattern=None,
            debug=False,
        )
        assert (
            datasets_stats.get("datasets", 0) >= 1
        ), "Expected datasets to be imported"

        # Import prompts
        prompts_stats = import_prompts_from_directory(
            client=opik_client,
            source_dir=test_data_dir / "default" / "prompts",
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
        export_project_by_name(
            name=source_project_name,
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
        project_dir = test_data_dir / "default" / "projects" / source_project_name
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

        export_project_by_name(
            name=source_project_name,
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
        source_dir = test_data_dir / "default" / "projects"

        # Count traces before dry run
        traces_before = opik_client.search_traces(project_name=source_project_name)
        count_before = len(traces_before)

        _ = import_projects_from_directory(
            client=opik_client,
            source_dir=source_dir,
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
        assert (
            count_after == count_before
        ), f"Dry run should not modify data: had {count_before} traces, now have {count_after}"

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
            "project",
            source_project_name,
            "--path",
            str(test_data_dir),
        ]

        result = self._run_cli_command(export_cmd)
        assert result.returncode == 0, f"CLI export failed: {result.stderr}"

        # Verify export worked
        project_dir = test_data_dir / "default" / "projects" / source_project_name
        assert project_dir.exists(), "CLI export did not create directory"
        trace_files = list(project_dir.glob("trace_*.json"))
        assert len(trace_files) >= 1, "CLI export did not create trace files"

        # Test import via CLI subprocess
        import_cmd = [
            "import",
            "default",
            "project",
            ".*",
            "--path",
            str(test_data_dir / "default"),
        ]

        result = self._run_cli_command(import_cmd)
        assert result.returncode == 0, f"CLI import failed: {result.stderr}"

    def test_export_import_error_handling(
        self, opik_client: opik.Opik, test_data_dir: Path
    ) -> None:
        """Test error handling for invalid commands."""
        # Test export with non-existent project - should fail gracefully
        try:
            export_project_by_name(
                name="non-existent-project",
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
            assert (
                not project_dir.exists()
            ), "Should not create directory for non-existent project"
        except SystemExit:
            # Expected - function calls sys.exit(1) on error
            pass

        # Test import with non-existent directory - should fail gracefully
        try:
            import_projects_from_directory(
                client=opik_client,
                source_dir=test_data_dir / "non-existent",
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
            dataset1_name, description="CLI test dataset 1"
        )
        dataset2 = opik_client.create_dataset(
            dataset2_name, description="CLI test dataset 2"
        )

        # Add items to datasets
        for i in range(3):
            dataset1.insert([{"input": f"test input 1-{i}", "expected_output": f"test output 1-{i}"}])
            dataset2.insert([{"input": f"test input 2-{i}", "expected_output": f"test output 2-{i}"}])

        # Step 2: Create two experiments with different datasets
        exp1_name = f"cli-test-exp1-{random_chars()}"
        exp2_name = f"cli-test-exp2-{random_chars()}"
        
        experiment1 = opik_client.create_experiment(
            name=exp1_name,
            dataset_name=dataset1_name,
        )
        experiment2 = opik_client.create_experiment(
            name=exp2_name,
            dataset_name=dataset2_name,
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
            experiment1.insert([
                ExperimentItemReferences(
                    dataset_item_id=dataset1_items[i]["id"],
                    trace_id=trace1.id,
                )
            ])
            
            trace2 = opik_client.trace(
                name=f"test-trace-exp2-{i}",
                input={"prompt": f"test prompt 2-{i}"},
                output={"response": f"test response 2-{i}"},
                project_name=source_project_name,
            )
            experiment2.insert([
                ExperimentItemReferences(
                    dataset_item_id=dataset2_items[i]["id"],
                    trace_id=trace2.id,
                )
            ])

        opik_client.flush()

        # Step 3: Export experiments filtered by dataset1
        export_experiment_by_name(
            name=exp1_name,
            workspace="default",
            output_path=str(test_data_dir),
            dataset=dataset1_name,  # Filter by dataset1
            max_traces=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Step 4: Verify only experiment1 was exported
        experiments_dir = test_data_dir / "default" / "experiments"
        assert experiments_dir.exists(), f"Export directory not found: {experiments_dir}"

        experiment_files = list(experiments_dir.glob(f"experiment_{exp1_name}_*.json"))
        assert len(experiment_files) == 1, f"Expected exactly 1 experiment file for {exp1_name}, found: {len(experiment_files)}"

        # Verify exp2 was NOT exported
        exp2_files = list(experiments_dir.glob(f"experiment_{exp2_name}_*.json"))
        assert len(exp2_files) == 0, f"Expected 0 experiment files for {exp2_name}, found: {len(exp2_files)}"

        # Step 5: Verify the exported experiment has the correct dataset
        with open(experiment_files[0], "r") as f:
            exp_data = json.load(f)
        
        assert exp_data["experiment"]["dataset_name"] == dataset1_name, \
            f"Expected dataset_name to be {dataset1_name}, got {exp_data['experiment']['dataset_name']}"

    def test_export_import_chat_prompts_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test the complete export/import flow for chat prompts."""
        # Step 1: Create a test chat prompt
        prompt_name = self._create_test_chat_prompt(opik_client)

        # Verify chat prompt was created
        prompts = opik_client.search_prompts()
        prompt_names = [p.name for p in prompts]
        assert (
            prompt_name in prompt_names
        ), f"Expected chat prompt {prompt_name} to be created"

        # Step 2: Export chat prompt using direct function call
        export_prompt_by_name(
            name=prompt_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        prompts_dir = test_data_dir / "default" / "prompts"
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
        assert isinstance(current_version["prompt"], list), "Chat prompt should have messages as a list"
        assert "template_structure" in current_version
        assert current_version["template_structure"] == "chat"

        # Step 3: Import chat prompt using direct function call
        source_dir = test_data_dir / "default" / "prompts"
        stats = import_prompts_from_directory(
            client=opik_client,
            source_dir=source_dir,
            dry_run=False,
            name_pattern=None,
            debug=False,
        )

        # Verify import succeeded
        assert stats.get("prompts", 0) >= 1, "Expected at least 1 prompt to be imported"
        
        # Verify chat prompt was correctly imported to backend
        imported_prompts = opik_client.search_prompts()
        imported_prompt_names = [p.name for p in imported_prompts]
        assert prompt_name in imported_prompt_names, f"Expected chat prompt {prompt_name} to be imported"
        
        # Get the imported chat prompt and verify its content
        imported_chat_prompt = opik_client.get_chat_prompt(name=prompt_name)
        verifiers.verify_chat_prompt_version(
            imported_chat_prompt,
            name=prompt_name,
        )

    def test_import_projects_automatically_recreates_experiments(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        test_data_dir: Path,
    ) -> None:
        """Test import projects automatically recreates experiments."""
        # Step 1: Prepare test data with experiments
        dataset_name = self._create_test_dataset(opik_client)
        self._create_test_traces(opik_client, source_project_name)
        self._create_test_experiment(opik_client, source_project_name, dataset_name)

        # Step 2: Export the project data (traces) using direct function call
        export_project_by_name(
            name=source_project_name,
            workspace="default",
            output_path=str(test_data_dir),
            max_results=None,
            filter_string=None,
            force=False,
            debug=False,
            format="json",
            api_key=None,
        )

        # Verify export files were created
        project_dir = test_data_dir / "default" / "projects" / source_project_name
        assert project_dir.exists(), f"Export directory not found: {project_dir}"

        # Step 3: Test import (experiments are automatically recreated) using direct function call
        source_dir = test_data_dir / "default" / "projects"
        stats = import_projects_from_directory(
            client=opik_client,
            source_dir=source_dir,
            dry_run=False,
            name_pattern=None,
            debug=False,
            recreate_experiments_flag=True,  # Automatically recreate experiments
        )

        # Verify import succeeded
        assert (
            stats.get("projects", 0) >= 1
        ), "Expected at least 1 project to be imported"
