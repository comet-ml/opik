"""
Integration tests for subprocess logging functionality via IsolatedSubprocessExecutor.

Tests the full flow:
1. IsolatedSubprocessExecutor runs code with logging enabled
2. Subprocess logs via print() AND logging module (with and without sys.stderr)
3. BatchLogCollector captures logs from stderr
4. HTTP POST sent to backend with proper payload
5. Validate complete log body structure (level, message, attributes, timestamp)
"""

import json
import logging
import os
import socket
import sys
import threading
import time
import tempfile
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import List, Dict, Any, Optional
from unittest.mock import patch, MagicMock

import pytest


# ============================================================================
# Utility Functions
# ============================================================================

def find_free_port() -> int:
    """Find a free port to avoid conflicts."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('localhost', 0))
        s.listen(1)
        port = s.getsockname()[1]
    return port


def assert_log_field(log: Dict, field: str, expected_value: Any = None, between: tuple = None):
    """Assert a log field with clear, simple checks.
    
    Args:
        log: Log entry dict
        field: Field name to check
        expected_value: Expected exact value (for == checks)
        between: Tuple of (min, max) for range checks
    """
    assert field in log, f"Field '{field}' missing in log"
    
    value = log[field]
    
    if between is not None:
        min_val, max_val = between
        assert min_val <= value <= max_val, f"{field}={value} not between {min_val} and {max_val}"
    elif expected_value is not None:
        assert value == expected_value, f"{field}={value}, expected {expected_value}"
    else:
        assert value is not None, f"{field} is None"


# ============================================================================
# Mock HTTP Server
# ============================================================================

class LogCapturingHandler(BaseHTTPRequestHandler):
    """HTTP handler that captures POST requests with logs."""
    
    captured_requests: List[Dict[str, Any]] = []
    
    def do_POST(self):
        """Handle POST request with logs."""
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length)
        
        try:
            log_batch = json.loads(body.decode('utf-8'))
            LogCapturingHandler.captured_requests.append({
                'headers': dict(self.headers),
                'body': log_batch,
                'received_at': time.time(),
            })
            
            # Send success response
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'status': 'ok'}).encode())
        except Exception as e:
            self.send_response(500)
            self.end_headers()
    
    def log_message(self, format, *args):
        """Suppress default logging."""
        pass


@pytest.fixture
def mock_backend():
    """Start a mock HTTP server to capture log POST requests."""
    port = find_free_port()
    server = HTTPServer(('localhost', port), LogCapturingHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    time.sleep(0.3)
    
    # Clear captured requests
    LogCapturingHandler.captured_requests = []
    
    yield port
    
    try:
        server.shutdown()
    except Exception as e:
        logger.warning(f"Error shutting down test HTTP server: {e}")


@pytest.fixture(autouse=True)
def clear_captured_requests():
    """Clear captured requests before each test to prevent test pollution."""
    LogCapturingHandler.captured_requests = []
    yield
    LogCapturingHandler.captured_requests = []


# ============================================================================
# Tests via IsolatedSubprocessExecutor
# ============================================================================

def test_executor_with_print_to_stderr(mock_backend):
    """Test IsolatedSubprocessExecutor with print() to sys.stderr."""
    pytest.importorskip("requests")
    
    from opik_backend.executor_isolated import IsolatedSubprocessExecutor
    
    port = mock_backend
    backend_url = f'http://localhost:{port}/logs'
    
    # Subprocess code that uses print() to sys.stderr
    code = '''
import sys
import json
print("Step 1: Starting", file=sys.stderr)
print("Step 2: Processing", file=sys.stderr)
print("Step 3: Complete", file=sys.stderr)
result = {"status": "ok"}
print(json.dumps(result))
'''
    
    with patch('opik_backend.executor_isolated.SubprocessLogConfig') as mock_config:
            mock_config.is_fully_configured.return_value = True
            mock_config.get_backend_url.return_value = backend_url
            mock_config.is_enabled.return_value = True
            mock_config.get_flush_interval_ms.return_value = 50
            mock_config.get_max_size_bytes.return_value = 10 * 1024 * 1024
            mock_config.get_request_timeout_secs.return_value = 60
            mock_config.should_fail_on_missing_backend.return_value = False
            
            executor = IsolatedSubprocessExecutor()
            
            before_time = int(time.time() * 1000)
            tmp = tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False)
            try:
                tmp.write(code)
                tmp.flush()
                result = executor.execute(
                    file_path=tmp.name,
                    data={},
                    env_vars={"OPIK_API_KEY": "test_key", "OPIK_WORKSPACE": "test_ws"},
                    optimization_id='opt_stderr',
                    job_id='job_stderr',
                )
            finally:
                try:
                    os.unlink(tmp.name)
                except Exception:
                    pass
            after_time = int(time.time() * 1000)
            
            assert result['status'] == 'ok'
            
            time.sleep(0.5)
            
            # Extract and validate payload
            captured_requests = LogCapturingHandler.captured_requests
            assert len(captured_requests) == 1
            
            captured_request = captured_requests[0]
            payload = captured_request['body']
            logs = payload.get('logs', [])
            
            # Validate payload structure
            assert payload['optimization_id'] == 'opt_stderr'
            assert payload['job_id'] == 'job_stderr'
            
            expected_logs = 4
            assert len(logs) == expected_logs
            
            expected_levels = ['INFO', 'INFO', 'INFO', 'INFO']
            expected_messages = ['Step 1: Starting', 'Step 2: Processing', 'Step 3: Complete', '{"status": "ok"}']
            expected_logger_names = ['subprocess.stderr', 'subprocess.stderr', 'subprocess.stderr', 'subprocess.stdout']
            expected_attrs_list = [{}, {}, {}, {}]
            
            # Validate each log
            for i, log in enumerate(logs):
                assert_log_field(log, 'timestamp', between=(before_time, after_time + 5000))
                assert_log_field(log, 'level', expected_value=expected_levels[i])
                assert_log_field(log, 'message', expected_value=expected_messages[i])
                assert_log_field(log, 'logger_name', expected_value=expected_logger_names[i])
                assert_log_field(log, 'attributes', expected_value=expected_attrs_list[i])
            
            # Validate auth headers
            headers = captured_request['headers']
            assert headers.get('Authorization') == 'test_key'
            assert headers.get('Comet-Workspace') == 'test_ws'


def test_executor_with_print_to_stdout(mock_backend):
    """Test IsolatedSubprocessExecutor with print() to stdout (no sys.stderr)."""
    pytest.importorskip("requests")
    
    from opik_backend.executor_isolated import IsolatedSubprocessExecutor
    
    port = mock_backend
    backend_url = f'http://localhost:{port}/logs'
    
    # Subprocess code using print() to stdout (default)
    code = '''
print("Log line 1")
print("Log line 2")
print("Log line 3")
result = {"status": "ok"}
import json
print(json.dumps(result))
'''
    
    with patch('opik_backend.executor_isolated.SubprocessLogConfig') as mock_config:
            mock_config.is_fully_configured.return_value = True
            mock_config.get_backend_url.return_value = backend_url
            mock_config.is_enabled.return_value = True
            mock_config.get_flush_interval_ms.return_value = 50
            mock_config.get_max_size_bytes.return_value = 10 * 1024 * 1024
            mock_config.get_request_timeout_secs.return_value = 60
            mock_config.should_fail_on_missing_backend.return_value = False
            
            executor = IsolatedSubprocessExecutor()
            
            before_time = int(time.time() * 1000)
            tmp = tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False)
            try:
                tmp.write(code)
                tmp.flush()
                result = executor.execute(
                    file_path=tmp.name,
                    data={},
                    env_vars={"OPIK_API_KEY": "stdout_key", "OPIK_WORKSPACE": "stdout_ws"},
                    optimization_id='opt_stdout',
                    job_id='job_stdout',
                )
            finally:
                try:
                    os.unlink(tmp.name)
                except Exception:
                    pass
            after_time = int(time.time() * 1000)
            
            assert result['status'] == 'ok'
            
            time.sleep(0.5)
            
            # Extract and validate payload
            captured_requests = LogCapturingHandler.captured_requests
            assert len(captured_requests) == 1
            
            captured_request = captured_requests[0]
            payload = captured_request['body']
            logs = payload.get('logs', [])
            
            # Validate structure
            assert payload['optimization_id'] == 'opt_stdout'
            assert payload['job_id'] == 'job_stdout'
            
            expected_logs = 4
            assert len(logs) == expected_logs
            
            expected_levels = ['INFO', 'INFO', 'INFO', 'INFO']
            expected_messages = ['Log line 1', 'Log line 2', 'Log line 3', '{"status": "ok"}']
            expected_logger_names = ['subprocess.stdout', 'subprocess.stdout', 'subprocess.stdout', 'subprocess.stdout']
            expected_attrs_list = [{}, {}, {}, {}]
            
            # Validate each log
            for i, log in enumerate(logs):
                assert_log_field(log, 'timestamp', between=(before_time, after_time + 5000))
                assert_log_field(log, 'level', expected_value=expected_levels[i])
                assert_log_field(log, 'message', expected_value=expected_messages[i])
                assert_log_field(log, 'logger_name', expected_value=expected_logger_names[i])
                assert_log_field(log, 'attributes', expected_value=expected_attrs_list[i])


def test_executor_with_logging_module(mock_backend):
    """Test IsolatedSubprocessExecutor with logging module."""
    pytest.importorskip("requests")
    
    from opik_backend.executor_isolated import IsolatedSubprocessExecutor
    
    port = mock_backend
    backend_url = f'http://localhost:{port}/logs'
    
    code = '''
import logging
import json
import sys
import time

# Configure logging to output JSON to stderr
class JSONFormatter(logging.Formatter):
    def format(self, record):
        log_obj = {
            "timestamp": int(time.time() * 1000),
            "level": record.levelname,
            "logger_name": record.name,
            "message": record.getMessage(),
            "attributes": {}
        }
        return json.dumps(log_obj)

logger = logging.getLogger("task")
logger.setLevel(logging.DEBUG)
handler = logging.StreamHandler(sys.stderr)
handler.setFormatter(JSONFormatter())
logger.addHandler(handler)

logger.info("Task started")
logger.warning("Memory usage high")
logger.error("Connection timeout")

print("Also printing to stderr", file=sys.stderr)

result = {"task_id": 42}
print(json.dumps(result))
'''
    
    with patch('opik_backend.executor_isolated.SubprocessLogConfig') as mock_config:
            mock_config.is_fully_configured.return_value = True
            mock_config.get_backend_url.return_value = backend_url
            mock_config.is_enabled.return_value = True
            mock_config.get_flush_interval_ms.return_value = 50
            mock_config.get_max_size_bytes.return_value = 10 * 1024 * 1024
            mock_config.get_request_timeout_secs.return_value = 60
            mock_config.should_fail_on_missing_backend.return_value = False
            
            executor = IsolatedSubprocessExecutor()
            
            before_time = int(time.time() * 1000)
            tmp = tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False)
            try:
                tmp.write(code)
                tmp.flush()
                result = executor.execute(
                    file_path=tmp.name,
                    data={},
                    env_vars={"OPIK_API_KEY": "log_key", "OPIK_WORKSPACE": "log_ws"},
                    optimization_id='opt_logging',
                    job_id='job_logging',
                )
            finally:
                try:
                    os.unlink(tmp.name)
                except Exception:
                    pass
            after_time = int(time.time() * 1000)
            
            assert result['task_id'] == 42
            
            time.sleep(0.5)
            
            captured_requests = LogCapturingHandler.captured_requests
            assert len(captured_requests) == 1
            
            captured_request = captured_requests[0]
            payload = captured_request['body']
            logs = payload.get('logs', [])
            
            # Validate structure
            assert payload['optimization_id'] == 'opt_logging'
            assert payload['job_id'] == 'job_logging'
            
            expected_logs = 5
            assert len(logs) == expected_logs
            
            expected_levels = ['INFO', 'WARNING', 'ERROR', 'INFO', 'INFO']
            expected_messages = ['Task started', 'Memory usage high', 'Connection timeout', 'Also printing to stderr', '{"task_id": 42}']
            expected_logger_names = ['task', 'task', 'task', 'subprocess.stderr', 'subprocess.stdout']
            expected_attrs_list = [{}, {}, {}, {}, {}]
            
            # Validate each log
            for i, log in enumerate(logs):
                assert_log_field(log, 'timestamp', between=(before_time, after_time + 5000))
                assert_log_field(log, 'level', expected_value=expected_levels[i])
                assert_log_field(log, 'message', expected_value=expected_messages[i])
                assert_log_field(log, 'logger_name', expected_value=expected_logger_names[i])
                assert_log_field(log, 'attributes', expected_value=expected_attrs_list[i])


def test_executor_with_json_logs(mock_backend):
    """Test IsolatedSubprocessExecutor with JSON-formatted logs."""
    pytest.importorskip("requests")
    
    from opik_backend.executor_isolated import IsolatedSubprocessExecutor
    
    port = mock_backend
    backend_url = f'http://localhost:{port}/logs'
    
    code = '''
import json
import sys
import time

for i in range(3):
    log_entry = {
        "timestamp": int(time.time() * 1000),
        "level": ["INFO", "WARNING", "ERROR"][i],
        "logger_name": f"task.step_{i}",
        "message": f"Processing step {i}",
        "attributes": {"step_number": i, "status": "running"}
    }
    print(json.dumps(log_entry), file=sys.stderr)

result = {"processed": 3}
print(json.dumps(result))
'''
    
    with patch('opik_backend.executor_isolated.SubprocessLogConfig') as mock_config:
            mock_config.is_fully_configured.return_value = True
            mock_config.get_backend_url.return_value = backend_url
            mock_config.is_enabled.return_value = True
            mock_config.get_flush_interval_ms.return_value = 50
            mock_config.get_max_size_bytes.return_value = 10 * 1024 * 1024
            mock_config.get_request_timeout_secs.return_value = 60
            mock_config.should_fail_on_missing_backend.return_value = False
            
            executor = IsolatedSubprocessExecutor()
            
            before_time = int(time.time() * 1000)
            tmp = tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False)
            try:
                tmp.write(code)
                tmp.flush()
                result = executor.execute(
                    file_path=tmp.name,
                    data={},
                    env_vars={"OPIK_API_KEY": "json_key", "OPIK_WORKSPACE": "json_ws"},
                    optimization_id='opt_json',
                    job_id='job_json',
                )
            finally:
                try:
                    os.unlink(tmp.name)
                except Exception:
                    pass
            after_time = int(time.time() * 1000)
            
            assert result['processed'] == 3
            
            time.sleep(0.5)
            
            captured_requests = LogCapturingHandler.captured_requests
            assert len(captured_requests) == 1
            
            captured_request = captured_requests[0]
            payload = captured_request['body']
            logs = payload.get('logs', [])
            
            # Validate payload exists
            expected_logs = 4
            assert len(logs) == expected_logs
            
            expected_levels = ['INFO', 'WARNING', 'ERROR', 'INFO']
            expected_messages = ['Processing step 0', 'Processing step 1', 'Processing step 2', '{"processed": 3}']
            expected_logger_names = ['task.step_0', 'task.step_1', 'task.step_2', 'subprocess.stdout']
            expected_attrs_list = [
                {"step_number": 0, "status": "running"},
                {"step_number": 1, "status": "running"},
                {"step_number": 2, "status": "running"},
                {}
            ]
            
            # Validate each log
            for i, log in enumerate(logs):
                assert_log_field(log, 'timestamp', between=(before_time, after_time + 5000))
                assert_log_field(log, 'level', expected_value=expected_levels[i])
                assert_log_field(log, 'message', expected_value=expected_messages[i])
                assert_log_field(log, 'logger_name', expected_value=expected_logger_names[i])
                assert_log_field(log, 'attributes', expected_value=expected_attrs_list[i])
