"""
Lightweight library integration tests for Harbor with Opik tracking.

These tests verify that the track_harbor function properly patches all expected
Harbor methods. This catches issues if Harbor maintainers rename internal methods.

These tests do NOT require:
- Docker running
- OpenAI API key
- Actually running any Harbor jobs
"""

import pytest

pytest.importorskip("harbor")

from harbor.trial.trial import Trial
from harbor.verifier.verifier import Verifier
from harbor.models.trajectories.step import Step

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

