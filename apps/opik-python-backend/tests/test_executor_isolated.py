"""Tests for IsolatedSubprocessExecutor"""
import concurrent.futures
import json
import os
import tempfile
from pathlib import Path
from typing import Any

import pytest

from opik_backend.executor_isolated import IsolatedSubprocessExecutor

# ============================================================================
# Test Code Constants
# ============================================================================

METRIC_CODE = '''
import json
import sys
import os
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())
data = input_data.get("data", {})
payload_type = input_data.get("payload_type")

# Get environment variable
tenant_id = os.getenv("TENANT_ID", "unknown")

try:
    # Simple metric execution
    input_text = data.get("input_text", "")
    value = len(str(input_text)) / 100.0
    score = min(value, 1.0)  # Cap at 1.0
    
    result = {
        "scores": [{
            "value": score,
            "name": "test_metric",
            "reason": f"Scored for tenant {tenant_id}"
        }]
    }
    print(json.dumps(result))
except Exception as e:
    result = {"code": 400, "error": str(e)}
    print(json.dumps(result))
'''

SLOW_CODE = '''
import time
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())

# Sleep longer than timeout
time.sleep(15)

result = {"scores": [{"value": 1.0, "name": "test"}]}
print(json.dumps(result))
'''

ERROR_CODE = '''
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())

# This will raise an exception
x = 1 / 0
'''

CODE_USING_PAYLOAD = '''
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())
payload_type = input_data.get("payload_type")

# Access payload_type variable
result = {
    "scores": [{
        "value": 0.5,
        "name": "test",
        "reason": f"Payload type: {payload_type}"
    }]
}
print(json.dumps(result))
'''

SIMPLE_CODE = '''
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())

result = {
    "scores": [{
        "value": 0.75,
        "name": "empty_test",
        "reason": "Executed with empty data"
    }]
}
print(json.dumps(result))
'''

CODE_WITH_ENV = '''
import os
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())

tenant = os.getenv("TENANT_ID", "none")
result = {
    "scores": [{
        "value": 1.0,
        "name": "concurrent_test",
        "reason": f"Tenant: {tenant}"
    }]
}
print(json.dumps(result))
'''

QUALITY_METRIC_CODE = '''
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())

result = {
    "scores": [{
        "value": 0.85,
        "name": "quality_metric",
        "reason": "Excellent quality"
    }]
}
print(json.dumps(result))
'''

MULTIPLE_SCORES_CODE = '''
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())

result = {
    "scores": [
        {
            "value": 0.9,
            "name": "accuracy",
            "reason": "High accuracy"
        },
        {
            "value": 0.8,
            "name": "relevance",
            "reason": "Good relevance"
        }
    ]
}
print(json.dumps(result))
'''

ENV_TEST_CODE = '''
import json
import os
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())

tenant = os.getenv("TENANT_ID", "unknown")
api_key = os.getenv("API_KEY", "not_set")

result = {
    "scores": [{
        "value": 0.95,
        "name": "env_test",
        "reason": f"Tenant: {tenant}, Has API Key: {api_key != 'not_set'}"
    }]
}
print(json.dumps(result))
'''

COMPLEX_DATA_CODE = '''
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())
data = input_data.get("data", {})

# data is provided by input
input_keys = list(data.keys())
input_values = list(str(v) for v in data.values())

result = {
    "scores": [{
        "value": len(input_keys) * 0.1,
        "name": "data_complexity",
        "reason": f"Input has {len(input_keys)} keys: {', '.join(input_keys)}"
    }]
}
print(json.dumps(result))
'''

LONG_RUNNING_CODE = '''
import time
import json
import sys
from opik.evaluation.metrics import base_metric, score_result

# Read input from stdin
input_data = json.loads(sys.stdin.read())

time.sleep(30)  # Simulate long-running task
result = {"scores": [{"value": 0.5, "name": "test", "reason": "done"}]}
print(json.dumps(result))
'''

# ============================================================================
# Test Class
# ============================================================================

class TestIsolatedSubprocessExecutor:
    """Test suite for IsolatedSubprocessExecutor"""

    @pytest.fixture
    def executor(self):
        """Create executor instance"""
        return IsolatedSubprocessExecutor(timeout_secs=10)

    @pytest.fixture
    def temp_metric_file(self):
        """Create a temporary Python file with metric code"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(METRIC_CODE)
            f.flush()
            temp_file = f.name
        
        yield temp_file
        
        # Cleanup
        Path(temp_file).unlink()

    def test_execute_with_inline_code(self, executor, temp_metric_file):
        """Test executing Python file by path"""
        result = executor.execute(
            file_path=temp_metric_file,
            data={"input_text": "hello world"},
        )
        
        assert result == {
            "scores": [{
                "value": 0.11,
                "name": "test_metric",
                "reason": "Scored for tenant unknown"
            }]
        }

    def test_execute_with_env_vars(self, executor, temp_metric_file):
        """Test executing with scoped environment variables"""
        result = executor.execute(
            file_path=temp_metric_file,
            data={"input_text": "test"},
            env_vars={"TENANT_ID": "tenant_123"},
        )
        
        assert result == {
            "scores": [{
                "value": 0.04,
                "name": "test_metric",
                "reason": "Scored for tenant tenant_123"
            }]
        }

    def test_execute_with_data_passing(self, executor, temp_metric_file):
        """Test that data is correctly passed to subprocess"""
        result = executor.execute(
            file_path=temp_metric_file,
            data={"input_text": "this is a longer test string"},
        )
        
        assert result == {
            "scores": [{
                "value": 0.28,
                "name": "test_metric",
                "reason": "Scored for tenant unknown"
            }]
        }

    def test_execute_timeout(self, executor):
        """Test execution timeout"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(SLOW_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(
                file_path=temp_file,
                data={},
                timeout_secs=1,
            )
            
            assert result.get("error") is not None
            assert "timed out" in result["error"].lower()
        finally:
            Path(temp_file).unlink()

    def test_execute_with_error_handling(self, executor):
        """Test error handling in user code"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(ERROR_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(
                file_path=temp_file,
                data={},
            )
            
            assert result.get("code") == 500
            assert result.get("error") is not None
        finally:
            Path(temp_file).unlink()

    def test_execute_with_payload_type(self, executor):
        """Test that payload_type is passed correctly"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(CODE_USING_PAYLOAD)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(
                file_path=temp_file,
                data={},
                payload_type="trace_thread",
            )
            
            assert result == {
                "scores": [{
                    "value": 0.5,
                    "name": "test",
                    "reason": "Payload type: trace_thread"
                }]
            }
        finally:
            Path(temp_file).unlink()

    def test_execute_with_empty_data(self, executor):
        """Test execution with empty data"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(SIMPLE_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(
                file_path=temp_file,
                data={},
            )
            
            assert result == {
                "scores": [{
                    "value": 0.75,
                    "name": "empty_test",
                    "reason": "Executed with empty data"
                }]
            }
        finally:
            Path(temp_file).unlink()

    def test_concurrent_execution(self, executor):
        """Test that multiple executions don't interfere with each other"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(CODE_WITH_ENV)
            f.flush()
            temp_file = f.name
        
        try:
            def run_with_tenant(tenant_id):
                return executor.execute(
                    file_path=temp_file,
                    data={},
                    env_vars={"TENANT_ID": tenant_id},
                )
            
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                results = list(pool.map(run_with_tenant, ["tenant_1", "tenant_2", "tenant_3"]))
            
            assert len(results) == 3
            for i, result in enumerate(results):
                tenant_id = f"tenant_{i+1}"
                assert result == {
                    "scores": [{
                        "value": 1.0,
                        "name": "concurrent_test",
                        "reason": f"Tenant: {tenant_id}"
                    }]
                }
        finally:
            Path(temp_file).unlink()

    def test_complete_output_structure(self, executor):
        """Test that complete output structure matches expected format"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(QUALITY_METRIC_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(
                file_path=temp_file,
                data={"test": "data"},
            )
            
            assert result == {
                "scores": [{
                    "value": 0.85,
                    "name": "quality_metric",
                    "reason": "Excellent quality"
                }]
            }
        finally:
            Path(temp_file).unlink()

    def test_multiple_scores_in_output(self, executor):
        """Test that multiple scores can be returned"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(MULTIPLE_SCORES_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(
                file_path=temp_file,
                data={},
            )
            
            assert result == {
                "scores": [
                    {
                        "value": 0.9,
                        "name": "accuracy",
                        "reason": "High accuracy"
                    },
                    {
                        "value": 0.8,
                        "name": "relevance",
                        "reason": "Good relevance"
                    }
                ]
            }
        finally:
            Path(temp_file).unlink()

    def test_env_vars_in_output(self, executor):
        """Test that environment variables are accessible in the output"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(ENV_TEST_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(
                file_path=temp_file,
                data={},
                env_vars={
                    "TENANT_ID": "acme_corp",
                    "API_KEY": "secret_123"
                }
            )
            
            assert result == {
                "scores": [{
                    "value": 0.95,
                    "name": "env_test",
                    "reason": "Tenant: acme_corp, Has API Key: True"
                }]
            }
        finally:
            Path(temp_file).unlink()

    def test_output_with_complex_data(self, executor):
        """Test output when input data is complex"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(COMPLEX_DATA_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(
                file_path=temp_file,
                data={
                    "user_id": "123",
                    "query": "hello world",
                    "context": "qa"
                }
            )
            
            score = result["scores"][0]
            assert score["name"] == "data_complexity"
            assert abs(score["value"] - 0.3) < 0.01
            assert score["reason"] == "Input has 3 keys: user_id, query, context"
        finally:
            Path(temp_file).unlink()

    def test_teardown_callback_is_called(self, executor):
        """Test that registered teardown callbacks are called"""
        callback_called = []
        
        def cleanup_callback():
            callback_called.append(True)
        
        executor.register_teardown_callback(cleanup_callback)
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(SIMPLE_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            # Execute something
            result = executor.execute(
                file_path=temp_file,
                data={},
            )
            
            assert result is not None
            assert len(callback_called) == 0  # Not called yet
            
            # Call teardown
            executor.teardown()
            
            # Verify callback was called
            assert len(callback_called) == 1
        finally:
            Path(temp_file).unlink()

    def test_multiple_teardown_callbacks(self, executor):
        """Test that multiple teardown callbacks are all called"""
        callback_order = []
        
        def callback1():
            callback_order.append(1)
        
        def callback2():
            callback_order.append(2)
        
        def callback3():
            callback_order.append(3)
        
        executor.register_teardown_callback(callback1)
        executor.register_teardown_callback(callback2)
        executor.register_teardown_callback(callback3)
        
        executor.teardown()
        
        assert callback_order == [1, 2, 3]

    def test_context_manager_calls_teardown(self, executor):
        """Test that context manager automatically calls teardown"""
        callback_called = []
        
        def cleanup_callback():
            callback_called.append(True)
        
        executor.register_teardown_callback(cleanup_callback)
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(SIMPLE_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            # Use as context manager
            with executor:
                result = executor.execute(
                    file_path=temp_file,
                    data={},
                )
                assert result is not None
                assert len(callback_called) == 0
            
            # After exiting context, teardown should have been called
            assert len(callback_called) == 1
        finally:
            Path(temp_file).unlink()

    def test_process_cleanup_after_execution(self, executor):
        """Test that processes are cleaned up automatically after execution"""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(SIMPLE_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            result = executor.execute(file_path=temp_file, data={})
            assert result is not None
            
            # After execution, process should be automatically cleaned up
            assert len(executor._active_processes) == 0
            
            # Calling teardown should be safe and cleanup is idempotent
            executor.teardown()
            assert len(executor._active_processes) == 0
        finally:
            Path(temp_file).unlink()

    def test_context_manager_with_error(self, executor):
        """Test that context manager calls teardown even on error"""
        callback_called = []
        
        def cleanup_callback():
            callback_called.append(True)
        
        executor.register_teardown_callback(cleanup_callback)
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(ERROR_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            try:
                with executor:
                    executor.execute(
                        file_path=temp_file,
                        data={},
                    )
                    raise ValueError("Test error")
            except ValueError:
                pass
            
            # Teardown should still have been called despite the error
            assert len(callback_called) == 1
        finally:
            Path(temp_file).unlink()

    def test_teardown_callback_exception_handling(self, executor):
        """Test that exceptions in teardown callbacks don't crash teardown"""
        callback_results = []
        
        def failing_callback():
            callback_results.append("failing")
            raise RuntimeError("Callback error")
        
        def normal_callback():
            callback_results.append("normal")
        
        executor.register_teardown_callback(failing_callback)
        executor.register_teardown_callback(normal_callback)
        
        # Should not raise despite failing callback
        executor.teardown()
        
        # Both callbacks should have been attempted
        assert "failing" in callback_results
        assert "normal" in callback_results

    def test_teardown_with_long_running_process(self, executor):
        """Test that teardown can kill long-running processes"""
        import threading
        import time as time_module
        
        callback_called = []
        
        def cleanup():
            callback_called.append(True)
        
        executor.register_teardown_callback(cleanup)
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False) as f:
            f.write(LONG_RUNNING_CODE)
            f.flush()
            temp_file = f.name
        
        try:
            # Start execution in a thread (non-blocking)
            def run_execution():
                executor.execute(file_path=temp_file, data={}, timeout_secs=60)
            
            thread = threading.Thread(target=run_execution)
            thread.daemon = True
            thread.start()
            
            # Give it a moment to start
            time_module.sleep(0.5)
            
            # Now call teardown while process is still running
            executor.teardown()
            
            # Verify callback was called
            assert len(callback_called) == 1
            # Process should be cleaned up
            assert len(executor._active_processes) == 0
            
            # Give thread a moment to wrap up
            time_module.sleep(0.5)
        finally:
            Path(temp_file).unlink()
