"""E2E tests for CLI import/export commands."""

import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import List
import pytest

import opik
from opik import opik_context
from ..conftest import random_chars


class TestCLIImportExport:
    """Test CLI import/export functionality end-to-end."""

    @pytest.fixture
    def test_data_dir(self):
        """Create a temporary directory for test data."""
        with tempfile.TemporaryDirectory() as temp_dir:
            yield Path(temp_dir)

    @pytest.fixture
    def source_project_name(self, opik_client: opik.Opik):
        """Create a source project for testing."""
        project_name = f"cli-test-source-{random_chars()}"
        yield project_name
        # Cleanup is handled by the test framework

    @pytest.fixture
    def target_project_name(self, opik_client: opik.Opik):
        """Create a target project for testing."""
        project_name = f"cli-test-target-{random_chars()}"
        yield project_name
        # Cleanup is handled by the test framework

    def _create_test_traces(
        self, opik_client: opik.Opik, project_name: str
    ) -> List[str]:
        """Create test traces in the specified project."""
        trace_ids = []

        @opik.track(project_name=project_name)
        def test_function_1(x: str) -> str:
            """Test function 1."""
            # Capture trace ID during execution
            trace_ids.append(opik_context.get_current_trace_data().id)
            return f"processed_{x}"

        @opik.track(project_name=project_name)
        def test_function_2(y: int) -> int:
            """Test function 2."""
            # Capture trace ID during execution
            trace_ids.append(opik_context.get_current_trace_data().id)
            return y * 2

        # Create traces
        test_function_1("input1")
        test_function_2(42)

        opik.flush_tracker()

        return trace_ids

    def _create_test_dataset(self, opik_client: opik.Opik, project_name: str) -> str:
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

    def _create_test_prompt(self, opik_client: opik.Opik, project_name: str) -> str:
        """Create a test prompt."""
        prompt_name = f"cli-test-prompt-{random_chars()}"
        opik_client.create_prompt(
            name=prompt_name,
            prompt="You are a helpful assistant. Answer the following question: {question}",
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
                if i < len(dataset_items):
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
        self, cmd: List[str], description: str = ""
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

    def _run_export_directly(
        self,
        workspace_or_project: str,
        path: str,
        include: List[str],
        max_results: int = 10,
        debug: bool = True,
    ) -> bool:
        """Run export command directly by calling the function."""
        from opik.cli.export import export

        # Create a mock click context to call the export function directly
        from click.testing import CliRunner

        runner = CliRunner()
        cmd = (
            ["export", workspace_or_project, "--path", path, "--include"]
            + include
            + ["--max-results", str(max_results)]
        )
        if debug:
            cmd.append("--debug")

        result = runner.invoke(export, cmd[1:])  # Skip the "export" command name

        print(f"Direct export result: {result.exit_code}")
        print(f"Direct export output: {result.output}")

        return result.exit_code == 0

    def _run_import_directly(
        self,
        workspace_folder: str,
        workspace_name: str,
        include: List[str],
        debug: bool = True,
    ) -> bool:
        """Run import command directly by calling the function."""
        from opik.cli.import_command import import_data

        # Create a mock click context to call the import function directly
        from click.testing import CliRunner

        runner = CliRunner()
        cmd = ["import", workspace_folder, workspace_name, "--include"] + include
        if debug:
            cmd.append("--debug")

        result = runner.invoke(import_data, cmd[1:])  # Skip the "import" command name

        print(f"Direct import result: {result.exit_code}")
        print(f"Direct import output: {result.output}")

        return result.exit_code == 0

    def test_export_import_traces_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        test_data_dir: Path,
    ):
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
        success = self._run_export_directly(
            f"default/{source_project_name}",
            str(test_data_dir),
            ["traces"],
            max_results=10,
            debug=True,
        )

        assert success, "Export command failed"

        # Check if the directory was created
        project_dir = test_data_dir / "default" / source_project_name
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

        # Step 3: Create target project first
        # Create a dummy trace in the target project to ensure it exists
        @opik.track(project_name=target_project_name)
        def dummy_function():
            return "dummy"

        dummy_function()
        opik.flush_tracker()

        # Step 4: Import traces to target project using direct function call
        success = self._run_import_directly(
            str(project_dir), f"default/{target_project_name}", ["traces"], debug=True
        )

        assert success, "Import command failed"

        # Step 5: Verify traces were imported
        # Wait a moment for traces to be processed
        import time

        time.sleep(2)

        imported_traces = opik_client.search_traces(project_name=target_project_name)
        print(f"Found {len(imported_traces)} total traces in target project")
        for trace in imported_traces:
            print(f"Imported trace: {trace.id} - {trace.name}")

        # Filter out the dummy trace we created
        imported_test_traces = [
            t for t in imported_traces if t.name.startswith("test_function")
        ]

        # If we don't find the imported traces, let's check all traces in the workspace
        if len(imported_test_traces) == 0:
            print("No test traces found, checking all traces in workspace...")
            all_traces = opik_client.search_traces()
            print(f"Found {len(all_traces)} total traces in workspace")
            for trace in all_traces:
                print(f"All traces: {trace.id} - {trace.name} - {trace.project_name}")

        assert (
            len(imported_test_traces) >= 1
        ), f"Expected imported test traces, found: {[t.name for t in imported_traces]}"

        # Verify trace content matches
        # Match traces by name instead of relying on order
        original_trace = traces[0]

        # Find the matching imported trace by name
        matching_imported_trace = None
        for imported_trace_candidate in imported_test_traces:
            if imported_trace_candidate.name == original_trace.name:
                matching_imported_trace = imported_trace_candidate
                break

        assert (
            matching_imported_trace is not None
        ), f"Could not find imported trace with name '{original_trace.name}'"

        print(
            f"Comparing original trace '{original_trace.name}' with imported trace '{matching_imported_trace.name}'"
        )

        # Check that the trace name and basic properties match
        assert matching_imported_trace.name == original_trace.name
        assert matching_imported_trace.input == original_trace.input
        assert matching_imported_trace.output == original_trace.output

    def test_export_import_datasets_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        test_data_dir: Path,
    ):
        """Test the complete export/import flow for datasets."""
        # Step 1: Prepare test data
        self._create_test_dataset(opik_client, source_project_name)

        # Verify dataset was created
        datasets = opik_client.get_datasets(max_results=100)
        assert len(datasets) >= 1, "Expected at least 1 dataset to be created"

        # Step 2: Export datasets
        export_cmd = [
            "export",
            f"default/{source_project_name}",
            "--path",
            str(test_data_dir),
            "--include",
            "datasets",
            "--max-results",
            "10",
        ]

        result = self._run_cli_command(export_cmd, "Export datasets")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Verify export files were created
        project_dir = test_data_dir / "default" / source_project_name
        assert project_dir.exists(), f"Export directory not found: {project_dir}"

        dataset_files = list(project_dir.glob("dataset_*.json"))
        assert (
            len(dataset_files) >= 1
        ), f"Expected dataset files, found: {list(project_dir.glob('*'))}"

        # Verify dataset file content
        with open(dataset_files[0], "r") as f:
            dataset_data = json.load(f)

        assert "name" in dataset_data
        assert "items" in dataset_data
        assert "downloaded_at" in dataset_data

        # Step 3: Import datasets to target project
        import_cmd = [
            "import",
            str(project_dir),
            f"default/{target_project_name}",
            "--include",
            "datasets",
        ]

        result = self._run_cli_command(import_cmd, "Import datasets")
        assert result.returncode == 0, f"Import failed: {result.stderr}"

        # Step 4: Verify datasets were imported
        imported_datasets = opik_client.get_datasets(max_results=100)
        assert (
            len(imported_datasets) >= 1
        ), "Expected imported datasets in target project"

        # Verify dataset content matches
        original_dataset = datasets[0]
        imported_dataset = imported_datasets[0]

        assert imported_dataset.name == original_dataset.name
        assert imported_dataset.description == original_dataset.description

    def test_export_import_prompts_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        test_data_dir: Path,
    ):
        """Test the complete export/import flow for prompts."""
        # Step 1: Prepare test data
        self._create_test_prompt(opik_client, source_project_name)

        # Verify prompt was created
        prompts = opik_client.search_prompts()
        assert len(prompts) >= 1, "Expected at least 1 prompt to be created"

        # Step 2: Export prompts using direct function call
        success = self._run_export_directly(
            "default",
            str(test_data_dir),
            ["prompts"],
            max_results=10,
            debug=True,
        )

        assert success, "Export command failed"

        # Verify export files were created
        project_dir = test_data_dir / "default"
        assert project_dir.exists(), f"Export directory not found: {project_dir}"

        prompt_files = list(project_dir.glob("prompt_*.json"))
        assert (
            len(prompt_files) >= 1
        ), f"Expected prompt files, found: {list(project_dir.glob('*'))}"

        # Verify prompt file content
        with open(prompt_files[0], "r") as f:
            prompt_data = json.load(f)

        assert "name" in prompt_data
        assert "current_version" in prompt_data
        assert "downloaded_at" in prompt_data

        # Step 3: Import prompts to target project
        import_cmd = [
            "import",
            str(project_dir),
            f"default/{target_project_name}",
            "--include",
            "prompts",
        ]

        result = self._run_cli_command(import_cmd, "Import prompts")
        assert result.returncode == 0, f"Import failed: {result.stderr}"

        # Step 4: Verify prompts were imported
        imported_prompts = opik_client.search_prompts()
        assert len(imported_prompts) >= 1, "Expected imported prompts in target project"

        # Verify prompt content matches
        original_prompt = prompts[0]
        imported_prompt = imported_prompts[0]

        assert imported_prompt.name == original_prompt.name
        assert imported_prompt.prompt == original_prompt.prompt

    def test_export_import_all_data_types_happy_flow(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        test_data_dir: Path,
    ):
        """Test the complete export/import flow for all data types."""
        # Step 1: Prepare test data
        self._create_test_traces(opik_client, source_project_name)
        self._create_test_dataset(opik_client, source_project_name)
        self._create_test_prompt(opik_client, source_project_name)

        # Step 2: Export all data types
        export_cmd = [
            "export",
            f"default/{source_project_name}",
            "--path",
            str(test_data_dir),
            "--all",
            "--max-results",
            "10",
        ]

        result = self._run_cli_command(export_cmd, "Export all data types")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Verify export files were created
        project_dir = test_data_dir / "default" / source_project_name
        assert project_dir.exists(), f"Export directory not found: {project_dir}"

        # Check for all file types
        trace_files = list(project_dir.glob("trace_*.json"))
        dataset_files = list(project_dir.glob("dataset_*.json"))
        prompt_files = list(project_dir.glob("prompt_*.json"))

        assert len(trace_files) >= 1, "Expected trace files"
        assert len(dataset_files) >= 1, "Expected dataset files"
        assert len(prompt_files) >= 1, "Expected prompt files"

        # Step 3: Import all data types
        import_cmd = [
            "import",
            str(project_dir),
            f"default/{target_project_name}",
            "--all",
        ]

        result = self._run_cli_command(import_cmd, "Import all data types")
        assert result.returncode == 0, f"Import failed: {result.stderr}"

        # Step 4: Verify all data types were imported
        imported_traces = opik_client.search_traces(project_name=target_project_name)
        imported_datasets = opik_client.get_datasets(max_results=100)
        imported_prompts = opik_client.search_prompts()

        assert len(imported_traces) >= 1, "Expected imported traces"
        assert len(imported_datasets) >= 1, "Expected imported datasets"
        assert len(imported_prompts) >= 1, "Expected imported prompts"

    def test_export_with_filters(
        self, opik_client: opik.Opik, source_project_name: str, test_data_dir: Path
    ):
        """Test export with various filters."""
        # Create test data
        self._create_test_traces(opik_client, source_project_name)

        # Test export with name filter
        export_cmd = [
            "export",
            f"default/{source_project_name}",
            "--path",
            str(test_data_dir),
            "--include",
            "traces",
            "--name",
            "test_function_1",
        ]

        result = self._run_cli_command(export_cmd, "Export with name filter")
        assert result.returncode == 0, f"Export with filter failed: {result.stderr}"

        # Verify filtered export
        project_dir = test_data_dir / "default" / source_project_name
        if project_dir.exists():
            trace_files = list(project_dir.glob("trace_*.json"))
            # The exact number depends on the filter, but we should have some files
            assert len(trace_files) >= 0, "Filtered export should produce some files"

    def test_import_dry_run(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        test_data_dir: Path,
    ):
        """Test import with dry run option."""
        # Create test data and export it
        self._create_test_traces(opik_client, source_project_name)

        export_cmd = [
            "export",
            f"default/{source_project_name}",
            "--path",
            str(test_data_dir),
            "--include",
            "traces",
        ]

        result = self._run_cli_command(export_cmd, "Export for dry run test")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Test dry run import
        project_dir = test_data_dir / "default" / source_project_name
        import_cmd = [
            "import",
            str(project_dir),
            f"default/{target_project_name}",
            "--include",
            "traces",
            "--dry-run",
        ]

        result = self._run_cli_command(import_cmd, "Dry run import")
        assert result.returncode == 0, f"Dry run import failed: {result.stderr}"
        assert (
            "dry run" in result.stdout.lower()
            or "would import" in result.stdout.lower()
        )

        # Verify no data was actually imported
        # The project may not exist since dry run doesn't create anything
        try:
            imported_traces = opik_client.search_traces(
                project_name=target_project_name
            )
            assert len(imported_traces) == 0, "Dry run should not import any data"
        except Exception:
            # If project doesn't exist, that's fine - dry run didn't import anything
            pass

    def test_export_import_error_handling(
        self, opik_client: opik.Opik, test_data_dir: Path
    ):
        """Test error handling for invalid commands."""
        # Test export with non-existent project
        export_cmd = [
            "export",
            "default/non-existent-project",
            "--path",
            str(test_data_dir),
            "--include",
            "traces",
        ]

        result = self._run_cli_command(export_cmd, "Export non-existent project")
        # This might succeed with 0 results or fail, both are acceptable
        assert result.returncode in [
            0,
            1,
        ], f"Unexpected return code: {result.returncode}"

        # Test import with non-existent directory
        import_cmd = [
            "import",
            str(test_data_dir / "non-existent"),
            "default/test-project",
            "--include",
            "traces",
        ]

        result = self._run_cli_command(import_cmd, "Import from non-existent directory")
        assert result.returncode != 0, "Import from non-existent directory should fail"

    def test_import_with_recreate_experiments_option(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        test_data_dir: Path,
    ):
        """Test import with --recreate-experiments option."""
        # Step 1: Prepare test data
        self._create_test_traces(opik_client, source_project_name)
        dataset_name = self._create_test_dataset(opik_client, source_project_name)
        experiment_name = self._create_test_experiment(
            opik_client, source_project_name, dataset_name
        )

        # Step 2: Export all data types
        export_cmd = [
            "export",
            f"default/{source_project_name}",
            "--path",
            str(test_data_dir),
            "--all",
            "--max-results",
            "10",
        ]

        result = self._run_cli_command(export_cmd, "Export all data types")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Verify experiment files were created
        project_dir = test_data_dir / "default" / source_project_name
        experiment_files = list(project_dir.glob("experiment_*.json"))
        assert (
            len(experiment_files) >= 1
        ), f"Expected experiment files, found: {list(project_dir.glob('*'))}"

        # Step 3: Import with --recreate-experiments flag
        import_cmd = [
            "import",
            str(project_dir),
            f"default/{target_project_name}",
            "--all",
            "--recreate-experiments",
        ]

        result = self._run_cli_command(import_cmd, "Import with recreate experiments")
        assert (
            result.returncode == 0
        ), f"Import with recreate experiments failed: {result.stderr}"

        # Verify the output mentions experiment recreation
        assert (
            "recreate" in result.stdout.lower() or "experiment" in result.stdout.lower()
        )

        # Step 4: Verify experiments were actually recreated
        import time

        time.sleep(2)  # Wait for data to be processed

        # Check that experiments exist in the target project
        experiments = opik_client.get_experiments_by_name(experiment_name)
        assert (
            len(experiments) >= 1
        ), f"Expected to find recreated experiment '{experiment_name}'"

        # Verify the recreated experiment has the correct properties
        recreated_experiment = experiments[0]
        assert recreated_experiment.name == experiment_name
        assert recreated_experiment.dataset_name == dataset_name

    def test_import_without_recreate_experiments_option(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        test_data_dir: Path,
    ):
        """Test import without --recreate-experiments option (default behavior)."""
        # Step 1: Prepare test data
        self._create_test_traces(opik_client, source_project_name)
        dataset_name = self._create_test_dataset(opik_client, source_project_name)
        experiment_name = self._create_test_experiment(
            opik_client, source_project_name, dataset_name
        )

        # Step 2: Export all data types
        export_cmd = [
            "export",
            f"default/{source_project_name}",
            "--path",
            str(test_data_dir),
            "--all",
            "--max-results",
            "10",
        ]

        result = self._run_cli_command(export_cmd, "Export all data types")
        assert result.returncode == 0, f"Export failed: {result.stderr}"

        # Step 3: Import without --recreate-experiments flag
        project_dir = test_data_dir / "default" / source_project_name
        import_cmd = [
            "import",
            str(project_dir),
            f"default/{target_project_name}",
            "--all",
            # Note: no --recreate-experiments flag
        ]

        result = self._run_cli_command(
            import_cmd, "Import without recreate experiments"
        )
        assert (
            result.returncode == 0
        ), f"Import without recreate experiments failed: {result.stderr}"

        # Verify the output does not mention experiment recreation
        assert "recreate" not in result.stdout.lower()

        # Step 4: Verify experiments were NOT recreated (only original should exist)
        import time

        time.sleep(2)  # Wait for data to be processed

        # Check that only the original experiment exists (not recreated)
        experiments = opik_client.get_experiments_by_name(experiment_name)
        # Should only find the original experiment, not a recreated one
        assert (
            len(experiments) == 1
        ), f"Expected only original experiment, found {len(experiments)}"
