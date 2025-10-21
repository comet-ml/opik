"""Tests for IsolatedSubprocessExecutor"""
import concurrent.futures
import json
import os
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

# The wrapper makes these available:
# - data: dict with input data
# - payload_type: Optional[str]

# Get environment variable
tenant_id = os.getenv("TENANT_ID", "unknown")

# Simple metric execution
try:
    # Create a simple metric that scores based on data
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
from opik.evaluation.metrics import base_metric, score_result

# Sleep longer than timeout
time.sleep(15)

result = {"scores": [{"value": 1.0, "name": "test"}]}
print(json.dumps(result))
'''

ERROR_CODE = '''
from opik.evaluation.metrics import base_metric, score_result

# This will raise an exception
x = 1 / 0
'''

CODE_USING_PAYLOAD = '''
import json
from opik.evaluation.metrics import base_metric, score_result

# Access payload_type variable provided by wrapper
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
from opik.evaluation.metrics import base_metric, score_result

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
from opik.evaluation.metrics import base_metric, score_result

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
from opik.evaluation.metrics import base_metric, score_result

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
from opik.evaluation.metrics import base_metric, score_result

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
from opik.evaluation.metrics import base_metric, score_result

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
from opik.evaluation.metrics import base_metric, score_result

# data is provided by wrapper
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

WORKING_CODE = '''
import json
import time
from opik.evaluation.metrics import base_metric, score_result

# Simulate some work
result = {"scores": [{"value": 0.5, "name": "test", "reason": "working"}]}
print(json.dumps(result))
'''

LONG_RUNNING_CODE = '''
import time
import json
from opik.evaluation.metrics import base_metric, score_result

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

    def test_execute_with_inline_code(self, executor):
        """Test executing inline code"""
        result = executor.execute(
            code=METRIC_CODE,
            data={"input_text": "hello world"},
        )
        
        assert result == {
            "scores": [{
                "value": 0.11,
                "name": "test_metric",
                "reason": "Scored for tenant unknown"
            }]
        }

    def test_execute_with_env_vars(self, executor):
        """Test executing with scoped environment variables"""
        result = executor.execute(
            code=METRIC_CODE,
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

    def test_execute_with_data_passing(self, executor):
        """Test that data is correctly passed to subprocess"""
        result = executor.execute(
            code=METRIC_CODE,
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
        result = executor.execute(
            code=SLOW_CODE,
            data={},
            timeout_secs=1,
        )
        
        assert result.get("error") is not None
        assert "timed out" in result["error"].lower()

    def test_execute_with_error_handling(self, executor):
        """Test error handling in user code"""
        result = executor.execute(
            code=ERROR_CODE,
            data={},
        )
        
        assert result.get("code") == 500
        assert result.get("error") is not None

    def test_execute_with_payload_type(self, executor):
        """Test that payload_type is passed correctly"""
        result = executor.execute(
            code=CODE_USING_PAYLOAD,
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

    def test_execute_with_empty_data(self, executor):
        """Test execution with empty data"""
        result = executor.execute(
            code=SIMPLE_CODE,
            data={},
        )
        
        assert result == {
            "scores": [{
                "value": 0.75,
                "name": "empty_test",
                "reason": "Executed with empty data"
            }]
        }

    def test_concurrent_execution(self, executor):
        """Test that multiple executions don't interfere with each other"""
        def run_with_tenant(tenant_id):
            return executor.execute(
                code=CODE_WITH_ENV,
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

    def test_complete_output_structure(self, executor):
        """Test that complete output structure matches expected format"""
        result = executor.execute(
            code=QUALITY_METRIC_CODE,
            data={"test": "data"},
        )
        
        assert result == {
            "scores": [{
                "value": 0.85,
                "name": "quality_metric",
                "reason": "Excellent quality"
            }]
        }

    def test_multiple_scores_in_output(self, executor):
        """Test that multiple scores can be returned"""
        result = executor.execute(
            code=MULTIPLE_SCORES_CODE,
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

    def test_env_vars_in_output(self, executor):
        """Test that environment variables are accessible in the output"""
        result = executor.execute(
            code=ENV_TEST_CODE,
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

    def test_output_with_complex_data(self, executor):
        """Test output when input data is complex"""
        result = executor.execute(
            code=COMPLEX_DATA_CODE,
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

    def test_teardown_callback_is_called(self, executor):
        """Test that registered teardown callbacks are called"""
        callback_called = []
        
        def cleanup_callback():
            callback_called.append(True)
        
        executor.register_teardown_callback(cleanup_callback)
        
        # Execute something
        result = executor.execute(
            code=SIMPLE_CODE,
            data={},
        )
        
        assert result is not None
        assert len(callback_called) == 0  # Not called yet
        
        # Call teardown
        executor.teardown()
        
        # Verify callback was called
        assert len(callback_called) == 1

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
        
        # Use as context manager
        with executor:
            result = executor.execute(
                code=SIMPLE_CODE,
                data={},
            )
            assert result is not None
            assert len(callback_called) == 0
        
        # After exiting context, teardown should have been called
        assert len(callback_called) == 1

    def test_process_cleanup_after_execution(self, executor):
        """Test that processes are cleaned up automatically after execution"""
        result = executor.execute(WORKING_CODE, {})
        assert result is not None
        
        # After execution, process should be automatically cleaned up
        assert len(executor._active_processes) == 0
        
        # Calling teardown should be safe and cleanup is idempotent
        executor.teardown()
        assert len(executor._active_processes) == 0

    def test_context_manager_with_error(self, executor):
        """Test that context manager calls teardown even on error"""
        callback_called = []
        
        def cleanup_callback():
            callback_called.append(True)
        
        executor.register_teardown_callback(cleanup_callback)
        
        try:
            with executor:
                executor.execute(
                    code=ERROR_CODE,
                    data={},
                )
                raise ValueError("Test error")
        except ValueError:
            pass
        
        # Teardown should still have been called despite the error
        assert len(callback_called) == 1

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
        
        # Start execution in a thread (non-blocking)
        def run_execution():
            executor.execute(LONG_RUNNING_CODE, {}, timeout_secs=60)
        
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
