from typing import Any
from opik_backend.executor_docker import DockerExecutor

def test_network_access_blocked():
    """Test that network access is blocked in Docker containers."""
    executor = DockerExecutor()
    try:
        # Code that attempts to make a network request
        code = """
from typing import Any
from opik.evaluation.metrics import base_metric, score_result
import urllib.request

class NetworkAccessTest(base_metric.BaseMetric):
    def __init__(self, name: str = "network_access_test"):
        super().__init__(name=name, track=False)

    def score(self, output: str, reference: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        try:
            urllib.request.urlopen('http://example.com')
            success = True
            error = None
        except Exception as e:
            success = False
            error = str(e)
        return score_result.ScoreResult(
            name=self.name,
            value=1.0 if success else 0.0,
            reason=error if error else "Network access succeeded")
"""
        result = executor.run_scoring(code, {"output": "", "reference": ""})
        
        # The execution should succeed but the network request should fail
        assert "scores" in result, "Result should contain scores"
        scores = result["scores"]
        assert len(scores) == 1, "Should have one score result"
        assert scores[0]["value"] == 0.0, "Network request should fail"
        assert "urlopen error" in scores[0]["reason"], "Error should indicate network failure"
        
    finally:
        executor.cleanup()


def test_filesystem_access_blocked():
    """Test that filesystem access is blocked in Docker containers."""
    executor = DockerExecutor()
    try:
        # Code that attempts to read a file
        code = """
from typing import Any
from opik.evaluation.metrics import base_metric, score_result
import os

class FileSystemAccessTest(base_metric.BaseMetric):
    def __init__(self, name: str = "filesystem_access_test"):
        super().__init__(name=name, track=False)

    def score(self, output: str, reference: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        try:
            # Try to read a file from the host system
            with open('/host/etc/passwd', 'r') as f:
                content = f.read()
            success = True
            error = None
        except Exception as e:
            success = False
            error = str(e)
        return score_result.ScoreResult(
            name=self.name,
            value=1.0 if success else 0.0,
            reason=error if error else "File system access succeeded")
"""
        result = executor.run_scoring(code, {"output": "", "reference": ""})
        
        # The execution should succeed but the file access should fail
        assert "scores" in result, "Result should contain scores"
        scores = result["scores"]
        assert len(scores) == 1, "Should have one score result"
        assert scores[0]["value"] == 0.0, "File system access should fail"
        assert "Permission denied" in scores[0]["reason"] or "No such file" in scores[0]["reason"], "Error should indicate filesystem access failure"
        
    finally:
        executor.cleanup()


def test_library_install_blocked():
    """Test that installing new libraries is blocked in Docker containers."""
    executor = DockerExecutor()
    try:
        # Code that attempts to install a package
        code = """
from typing import Any
from opik.evaluation.metrics import base_metric, score_result
import subprocess

class LibraryInstallTest(base_metric.BaseMetric):
    def __init__(self, name: str = "library_install_test"):
        super().__init__(name=name, track=False)

    def score(self, output: str, reference: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        # Try to install a package using pip
        import subprocess
        output = subprocess.run(['pip', '--retries', '1', 'install', 'docker'], capture_output=True, text=True)
        success = output.returncode == 0
        error = output.stderr
        return score_result.ScoreResult(
            name=self.name,
            value=1.0 if success else 0.0,
            reason=error if error else "Library installation succeeded")
"""
        result = executor.run_scoring(code, {"output": "", "reference": ""})
        
        # The execution should succeed but the installation should fail
        assert "scores" in result, "Result should contain scores"
        scores = result["scores"]
        assert len(scores) == 1, "Should have one score result"
        assert scores[0]["value"] == 0.0, "Library installation should fail"
        assert "ModuleNotFoundError: No module named 'pip'" in scores[0]["reason"], "Error should indicate pip is not available in container"
        
    finally:
        executor.cleanup()


def test_execution_timeout():
    """Test that code execution is terminated after 3 seconds."""
    executor = DockerExecutor()
    try:
        # Code that sleeps for longer than the timeout
        code = """
from typing import Any
from opik.evaluation.metrics import base_metric, score_result
import time

class TimeoutTest(base_metric.BaseMetric):
    def __init__(self,name: str = "timeout_test"):
        super().__init__(name=name, track=False)

    def score(self, output: str, reference: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        # Sleep for 10 seconds (longer than the 3s timeout)
        time.sleep(10)
        return score_result.ScoreResult(
            name=self.name,
            value=1.0,
            reason="Should not reach here")
"""
        result = executor.run_scoring(code, {"output": "", "reference": ""})
        
        # The execution should timeout
        assert result["code"] == 504, "Should return timeout status code"
        assert "timeout" in result["error"].lower(), "Error should indicate timeout"
        
    finally:
        executor.cleanup()
