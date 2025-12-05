"""
E2E integration tests for Harbor with Opik tracking.

These tests require:
- Harbor installed: pip install harbor
- Docker running (for Harbor environments)
- OPENAI_API_KEY environment variable set
"""

import asyncio
import json
import os
import tempfile
from datetime import datetime
from pathlib import Path

import pytest

from opik import Opik, synchronization
from opik.integrations.harbor import track_harbor, reset_harbor_tracking
from . import constants

pytest.importorskip("harbor")


def get_job_id_from_harbor_job_dir(jobs_dir: Path, job_name: str) -> str:
    """Extract job_id from Harbor's trial config.json file.

    Args:
        jobs_dir: The jobs directory where Harbor stores job results.
        job_name: The name of the job.

    Returns:
        The job_id UUID string.
    """
    job_dir = jobs_dir / job_name
    trial_dirs = [d for d in job_dir.iterdir() if d.is_dir()]
    assert len(trial_dirs) >= 1, "Trial directory should exist"
    trial_config_path = trial_dirs[0] / "config.json"
    with open(trial_config_path) as f:
        trial_config = json.load(f)
    return trial_config["job_id"]


def assert_harbor_experiment_created(opik_client: Opik, jobs_dir: Path, job_name: str) -> None:
    """Verify that a Harbor experiment was created with the correct name and has reward scores.

    The experiment name is based on job_id: "harbor-job-{job_id[:8]}"

    Args:
        opik_client: The Opik client instance.
        jobs_dir: The jobs directory where Harbor stores job results.
        job_name: The name of the job.
    """
    job_id = get_job_id_from_harbor_job_dir(jobs_dir, job_name)
    expected_experiment_name = f"harbor-job-{job_id[:8]}"

    experiments = opik_client.get_experiments_by_name(expected_experiment_name)
    assert len(experiments) >= 1, f"Experiment '{expected_experiment_name}' should be created"

    # Verify the experiment has exactly two items with non-null reward feedback scores
    experiment = experiments[0]
    items = experiment.get_items()
    assert len(items) == 2, f"Experiment should have exactly two items, got {len(items)}"

    has_reward_score = any(
        score.get("name") == "reward" and score.get("value") is not None
        for item in items
        for score in item.feedback_scores
    )
    assert has_reward_score, "Experiment should have at least one item with a non-null reward feedback score"

from harbor.job import Job
from harbor.models.job.config import (
    AgentConfig,
    JobConfig,
    EnvironmentConfig,
    OrchestratorConfig,
    RegistryDatasetConfig,
)
from harbor.models.registry import RemoteRegistryInfo


@pytest.fixture(autouse=True)
def reset_tracking_state():
    reset_harbor_tracking()
    yield
    reset_harbor_tracking()


@pytest.fixture
def temp_jobs_dir():
    with tempfile.TemporaryDirectory(prefix="harbor_test_") as tmpdir:
        yield Path(tmpdir)


class TestHarborSDKIntegration:
    @pytest.mark.skipif(
        not os.getenv("OPENAI_API_KEY"),
        reason="OPENAI_API_KEY not set",
    )
    def test_track_harbor_creates_traces_and_experiment(
        self,
        ensure_openai_configured,
        opik_client: Opik,
        configure_e2e_tests_env_unique_project_name: str,
        temp_jobs_dir: Path,
    ):
        """Test that track_harbor automatically creates traces, dataset, and experiment."""
        agent = AgentConfig(
            name=constants.AGENT_NAME,
            model_name=constants.MODEL_NAME,
            override_timeout_sec=constants.TIMEOUT_SEC,
        )

        dataset = RegistryDatasetConfig(
            registry=RemoteRegistryInfo(),
            name=constants.DATASET_NAME,
            version=constants.DATASET_VERSION,
            task_names=constants.TASK_NAMES,
        )

        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        job_name = f"opik-test-{timestamp}"

        job = Job(
            JobConfig(
                job_name=job_name,
                jobs_dir=temp_jobs_dir,
                orchestrator=OrchestratorConfig(n_concurrent_trials=1),
                environment=EnvironmentConfig(delete=True),
                agents=[agent],
                datasets=[dataset],
            )
        )

        tracked_job = track_harbor(job)

        asyncio.get_event_loop().run_until_complete(tracked_job.run())

        if not synchronization.until(
            function=lambda: opik_client.search_traces(project_name=configure_e2e_tests_env_unique_project_name)[0].output is not None,
            allow_errors=True,
            max_try_seconds=60,
        ):
            raise AssertionError("Failed to get traces")

        traces = opik_client.search_traces(project_name=configure_e2e_tests_env_unique_project_name, truncate=False)
        assert len(traces) == 2, f"Expected 2 traces (one per task), got {len(traces)}"

        for trace in traces:
            assert trace.metadata.get("created_from") == "harbor"
            assert "harbor" in (trace.tags or [])

            spans = opik_client.search_spans(trace_id=trace.id, truncate=False)
            assert len(spans) >= 1

        opik_dataset = opik_client.get_dataset(constants.DATASET_NAME)
        assert opik_dataset is not None, "Dataset should be created automatically"

        assert_harbor_experiment_created(opik_client, temp_jobs_dir, job_name)


class TestHarborCLIIntegration:
    @pytest.mark.skipif(
        not os.getenv("OPENAI_API_KEY"),
        reason="OPENAI_API_KEY not set",
    )
    def test_opik_harbor_cli_creates_traces_and_experiment(
        self,
        ensure_openai_configured,
        opik_client: Opik,
        configure_e2e_tests_env_unique_project_name: str,
        temp_jobs_dir: Path,
    ):
        """Test that `opik harbor run` automatically creates traces, dataset, and experiment."""
        import yaml
        from click.testing import CliRunner
        from opik.cli.main import cli

        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        job_name = f"opik-cli-test-{timestamp}"

        # Create config file with proper timeout settings
        # (--ak override_timeout_sec=X doesn't work due to Harbor CLI bug)
        config = {
            "job_name": job_name,
            "jobs_dir": str(temp_jobs_dir),
            "n_attempts": 1,
            "timeout_multiplier": 1.0,
            "orchestrator": {
                "type": "local",
                "n_concurrent_trials": 1,
            },
            "environment": {
                "type": "docker",
                "delete": True,
            },
            "agents": [
                {
                    "name": constants.AGENT_NAME,
                    "model_name": constants.MODEL_NAME,
                    "override_timeout_sec": constants.TIMEOUT_SEC,
                }
            ],
            "datasets": [
                {
                    "registry": {"type": "remote"},
                    "name": constants.DATASET_NAME,
                    "version": constants.DATASET_VERSION,
                    "task_names": constants.TASK_NAMES,
                }
            ],
        }

        config_path = temp_jobs_dir / "config.yaml"
        with open(config_path, "w") as f:
            yaml.dump(config, f)

        runner = CliRunner()

        result = runner.invoke(
            cli,
            [
                "harbor",
                "run",
                "-c", str(config_path),
            ],
            catch_exceptions=False,
        )

        # Print output for debugging visibility
        print(f"\n=== Harbor CLI Output ===\n{result.output}")

        assert result.exit_code == 0, f"CLI failed: {result.output}"

        if not synchronization.until(
            function=lambda: opik_client.search_traces(project_name=configure_e2e_tests_env_unique_project_name)[0].output is not None,
            allow_errors=True,
            max_try_seconds=60,
        ):
            raise AssertionError("Failed to get traces after CLI run")

        traces = opik_client.search_traces(project_name=configure_e2e_tests_env_unique_project_name, truncate=False)
        assert len(traces) == 2, f"Expected 2 traces (one per task), got {len(traces)}"
        for trace in traces:
            assert "harbor" in (trace.tags or [])

        opik_dataset = opik_client.get_dataset(constants.DATASET_NAME)
        assert opik_dataset is not None, "Dataset should be created automatically"

        assert_harbor_experiment_created(opik_client, temp_jobs_dir, job_name)
