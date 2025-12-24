"""
Lightweight library integration tests for Harbor with Opik tracking.

These tests verify that the track_harbor function properly patches all expected
Harbor methods. This catches issues if Harbor maintainers rename internal methods.

These tests do NOT require:
- Docker running
- OpenAI API key
- Actually running any Harbor jobs
"""

import asyncio
from unittest.mock import MagicMock, patch

import pytest

from harbor.trial.trial import Trial
from harbor.verifier.verifier import Verifier
from harbor.models.trajectories.step import Step

from opik import flush_tracker
from opik.integrations.harbor import track_harbor, reset_harbor_tracking
from opik.integrations.harbor.opik_tracker import _patch_step_class


@pytest.fixture(autouse=True)
def reset_tracking_state():
    """Reset Harbor tracking state before and after each test."""
    reset_harbor_tracking()
    yield
    reset_harbor_tracking()


class TestTrackHarborPatching:
    """Tests to verify track_harbor correctly patches all expected Harbor methods."""

    def test_track_harbor_patches_trial_run(self):
        """Verify Trial.run method is patched with opik_tracked attribute."""
        track_harbor()
        assert hasattr(
            Trial.run, "opik_tracked"
        ), "Trial.run should have opik_tracked attribute after track_harbor()"

    def test_track_harbor_patches_trial_setup_environment(self):
        """Verify Trial._setup_environment method is patched with opik_tracked attribute."""
        track_harbor()
        assert hasattr(
            Trial._setup_environment, "opik_tracked"
        ), "Trial._setup_environment should have opik_tracked attribute after track_harbor()"

    def test_track_harbor_patches_trial_setup_agent(self):
        """Verify Trial._setup_agent method is patched with opik_tracked attribute."""
        track_harbor()
        assert hasattr(
            Trial._setup_agent, "opik_tracked"
        ), "Trial._setup_agent should have opik_tracked attribute after track_harbor()"

    def test_track_harbor_patches_trial_execute_agent(self):
        """Verify Trial._execute_agent method is patched with opik_tracked attribute."""
        track_harbor()
        assert hasattr(
            Trial._execute_agent, "opik_tracked"
        ), "Trial._execute_agent should have opik_tracked attribute after track_harbor()"

    def test_track_harbor_patches_trial_run_verification(self):
        """Verify Trial._run_verification method is patched with opik_tracked attribute."""
        track_harbor()
        assert hasattr(
            Trial._run_verification, "opik_tracked"
        ), "Trial._run_verification should have opik_tracked attribute after track_harbor()"

    def test_track_harbor_patches_verifier_verify(self):
        """Verify Verifier.verify method is patched with opik_tracked attribute."""
        track_harbor()
        assert hasattr(
            Verifier.verify, "opik_tracked"
        ), "Verifier.verify should have opik_tracked attribute after track_harbor()"

    def test_track_harbor_patches_step_init(self):
        """Verify Step.__init__ is patched via _patch_step_class."""
        track_harbor()
        assert hasattr(
            _patch_step_class, "_patched"
        ), "Step.__init__ should be patched (indicated by _patch_step_class._patched)"

    def test_track_harbor_returns_job_when_provided(self):
        """Verify track_harbor returns the job instance when one is provided."""
        # We can't easily create a real Job without complex config,
        # but we can verify the function accepts None and returns None
        result = track_harbor(job=None)
        assert result is None, "track_harbor(None) should return None"

    def test_track_harbor_no_error_when_called_multiple_times(self):
        """Verify track_harbor can be called multiple times without errors."""
        track_harbor()
        track_harbor()  # Should not raise
        track_harbor()  # Should not raise

        # All methods should still be patched
        assert hasattr(Trial.run, "opik_tracked")
        assert hasattr(Verifier.verify, "opik_tracked")


class TestHarborClassesExist:
    """
    Tests to verify that expected Harbor classes and methods exist.

    These tests will fail if Harbor renames or removes these classes/methods,
    alerting us to update the integration.
    """

    def test_trial_class_has_expected_methods(self):
        """Verify Trial class has all methods we expect to patch."""
        expected_methods = [
            "run",
            "_setup_environment",
            "_setup_agent",
            "_execute_agent",
            "_run_verification",
        ]
        for method_name in expected_methods:
            assert hasattr(
                Trial, method_name
            ), f"Trial class should have {method_name} method"

    def test_verifier_class_has_verify_method(self):
        """Verify Verifier class has verify method."""
        assert hasattr(Verifier, "verify"), "Verifier class should have verify method"

    def test_step_class_exists(self):
        """Verify Step class exists and can be imported."""
        # Step is imported at module level, so if we get here it exists
        assert Step is not None, "Step class should exist"


class TestHarborTraceName:
    """Tests to verify that trace names are set correctly via track_harbor."""

    @pytest.mark.asyncio
    async def test_track_harbor_sets_correct_trace_name(self, fake_backend):
        """Verify that track_harbor sets the correct trace name format when Trial.run is called.

        This test verifies that the trace name is set correctly:
        1. Immediately when the span starts (during execution)
        2. After the trial completes
        """
        # Create mock Trial with config
        mock_agent = MagicMock()
        mock_agent.name = "test-agent"
        mock_agent.model_name = "gpt-4"

        mock_task = MagicMock()
        mock_task.name = "test-task"
        mock_task.path = "/path/to/task"

        mock_config = MagicMock()
        mock_config.agent = mock_agent
        mock_config.trial_name = "test-trial-123"
        mock_config.task = mock_task
        mock_config.job_id = None  # No job_id for this test

        # Create mock TrialResult
        mock_trial_result = MagicMock()
        mock_trial_result.trial_name = "test-trial-123"
        mock_trial_result.task_name = "test-task"
        mock_trial_result.verifier_result = None
        mock_trial_result.error = None

        # Create mock Trial instance
        mock_trial = MagicMock(spec=Trial)
        mock_trial.config = mock_config

        # Create an event to pause execution so we can check trace name during execution
        pause_event = asyncio.Event()
        resume_event = asyncio.Event()

        # Mock the original Trial.run to pause during execution
        async def mock_original_run(self):
            # Signal that we've started
            pause_event.set()
            # Wait for resume signal
            await resume_event.wait()
            return mock_trial_result

        expected_trace_name = "test-agent/test-trial-123"

        # Patch the original method before track_harbor patches it
        with patch.object(Trial, "run", new=mock_original_run):
            # Enable tracking via public interface
            track_harbor(project_name="test-project")

            # Start the wrapped Trial.run method as a task
            task = asyncio.create_task(Trial.run(mock_trial))

            # Wait for the function to start (trace should be created by now)
            await pause_event.wait()

            # Flush to ensure trace is sent to fake_backend before checking
            flush_tracker()

            # Check trace name DURING execution (before completion)
            # The trace should exist and have the correct name immediately
            assert (
                len(fake_backend.trace_trees) >= 1
            ), "Expected trace to be created during execution"
            trace_during_execution = fake_backend.trace_trees[0]
            assert trace_during_execution.name == expected_trace_name, (
                f"Expected trace name '{expected_trace_name}' during execution, "
                f"got '{trace_during_execution.name}'. "
                f"This indicates the name is not set correctly when the span starts."
            )

            # Resume execution
            resume_event.set()

            # Wait for completion
            await task

            # Flush to ensure trace is sent to fake_backend
            flush_tracker()

        # Verify trace still has correct name after completion
        assert len(fake_backend.trace_trees) == 1, "Expected exactly one trace"
        trace = fake_backend.trace_trees[0]

        # Verify trace name is still correct after completion
        assert (
            trace.name == expected_trace_name
        ), f"Expected trace name '{expected_trace_name}' after completion, got '{trace.name}'"

        # Verify other properties are set correctly
        assert trace.project_name == "test-project"
        assert "harbor" in (trace.tags or [])
        assert "test-agent" in (trace.tags or [])
        assert trace.metadata.get("created_from") == "harbor"
        assert trace.input["trial_name"] == "test-trial-123"
        assert trace.input["agent"]["name"] == "test-agent"
