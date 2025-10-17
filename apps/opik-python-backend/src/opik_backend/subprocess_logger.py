
"""
HTTP log streaming for isolated subprocess execution.

This module provides BatchLogCollector for streaming logs to a backend HTTP endpoint
with automatic batching, gzip compression, and authentication headers.

Configuration: All settings passed as parameters - no environment variables.
"""

import json
import logging
import sys
import threading
import time
from datetime import datetime
from typing import Optional, List, Dict, Any, IO
import subprocess
from opik_backend.subprocess_log_config import SubprocessLogConfig
import os

try:
    import requests
except ImportError:
    requests = None

logger = logging.getLogger(__name__)

class SubprocessLogRecord:
    """Represents a single log record from subprocess."""

    def __init__(
        self,
        timestamp: int,
        level: str,
        logger_name: str,
        message: str,
        attributes: Optional[Dict[str, Any]] = None,
    ):
        self.timestamp = timestamp
        self.level = level
        self.logger_name = logger_name
        self.message = message
        self.attributes = attributes or {}

    def to_dict(self) -> Dict[str, Any]:
        """Convert log record to dictionary for JSON serialization."""
        return {
            "timestamp": self.timestamp,
            "level": self.level,
            "logger_name": self.logger_name,
            "message": self.message,
            "attributes": self.attributes,
        }

    def size_bytes(self) -> int:
        """Estimate the size of the log record in bytes when serialized to JSON."""
        return len(json.dumps(self.to_dict()).encode('utf-8'))


class BatchLogCollector(logging.Handler):
    """
    Logging handler that collects logs in memory and batches them for HTTP posting.

    Features:
    - Collects logs in memory buffer
    - Batches by size or time interval
    - Sends via HTTP POST with gzip compression
    - Supports authentication headers
    - Thread-safe batch flushing
    """

    def __init__(
        self,
        backend_url: str,
        optimization_id: str,
        job_id: str,
        api_key: Optional[str] = None,
        workspace: Optional[str] = None,
    ):
        """
        Initialize the batch log collector.

        Args:
            backend_url: HTTP endpoint to POST logs to
            optimization_id: Optimization identifier for correlation
            job_id: Job identifier for correlation
            api_key: Optional API key for Bearer token authentication
            workspace: Optional workspace identifier for Comet-Workspace header
        
        Raises:
            ValueError: If backend_url is not provided or if requests library is not available
        """
        super().__init__()
        
        # Validate backend URL
        if not backend_url:
            raise ValueError("backend_url is required for BatchLogCollector")
        
        # Check requests library availability
        if requests is None:
            raise ImportError("requests library is required for subprocess logging. Install it with: pip install requests")
        
        self.backend_url = backend_url
        self.optimization_id = optimization_id
        self.job_id = job_id
        self.api_key = api_key
        self.workspace = workspace
        self.flush_interval_ms = SubprocessLogConfig.get_flush_interval_ms()
        self.max_size_bytes = SubprocessLogConfig.get_max_size_bytes()

        self.log_buffer: List[SubprocessLogRecord] = []
        self.buffer_size_bytes: int = 0
        self.buffer_lock = threading.Lock()
        self.last_flush_time = time.time()
        self.flush_thread: Optional[threading.Thread] = None
        self.should_stop = False

        # Set formatter
        self.setFormatter(logging.Formatter("%(levelname)s - %(name)s - %(message)s"))

        # Start background flush thread
        self._start_flush_thread()

    def emit(self, record: dict) -> None:
        """
        Handle a log record by adding it to the buffer.

        Args:
            record: Dictionary with log data from subprocess (timestamp, level, message, attributes)
        """
        try:
            # Parse the record dictionary
            timestamp_ms = int(record.get('timestamp', time.time() * 1000))
            level_name = record.get('level', 'INFO')
            logger_name = record.get('logger_name', 'subprocess')
            message = record.get('message', '')
            attributes = record.get('attributes', {})

            log_record = SubprocessLogRecord(
                timestamp=timestamp_ms,
                level=level_name,
                logger_name=logger_name,
                message=message,
                attributes=attributes,
            )

            with self.buffer_lock:
                self.log_buffer.append(log_record)
                self.buffer_size_bytes += log_record.size_bytes()

                # Check if we should flush based on buffer size
                if self.buffer_size_bytes >= self.max_size_bytes:
                    self._flush_logs()

        except Exception as e:
            logger.warning(f"Error in subprocess logger emit: {e}")

    def _start_flush_thread(self) -> None:
        """Start background thread for periodic log flushing."""
        self.flush_thread = threading.Thread(target=self._periodic_flush, daemon=True)
        self.flush_thread.start()

    def _periodic_flush(self) -> None:
        """Periodically flush logs based on time interval."""
        while not self.should_stop:
            time.sleep(self.flush_interval_ms / 1000.0)

            with self.buffer_lock:
                if self.log_buffer and (time.time() - self.last_flush_time) * 1000 >= self.flush_interval_ms:
                    self._flush_logs()

    def _flush_logs(self) -> None:
        """
        Flush buffered logs to the HTTP backend.

        Note: Must be called with buffer_lock held.
        """
        if not self.log_buffer:
            return

        if requests is None:
            logger.warning("requests library not available for log posting")
            self.log_buffer.clear()
            self.buffer_size_bytes = 0
            return

        # Prepare the log batch
        payload = {
            "optimization_id": self.optimization_id,
            "job_id": self.job_id,
            "logs": [log.to_dict() for log in self.log_buffer],
        }

        # Send via HTTP POST with optional authentication headers
        try:
            headers = {
                "Content-Type": "application/json",
            }
            # Add authorization if API key provided
            if self.api_key:
                headers["Authorization"] = self.api_key
            # Add workspace header if provided
            if self.workspace:
                headers["Comet-Workspace"] = self.workspace

            # Get configurable timeout
            timeout_secs = SubprocessLogConfig.get_request_timeout_secs()

            response = requests.post(
                self.backend_url,
                json=payload,
                headers=headers,
                timeout=timeout_secs,
            )
            response.raise_for_status()

            # Clear buffer on successful post
            self.log_buffer.clear()
            self.buffer_size_bytes = 0
            self.last_flush_time = time.time()

        except requests.exceptions.RequestException as e:
            # Log errors but don't crash
            logger.warning(f"Failed to post logs to {self.backend_url}: {e}")
        except Exception as e:
            logger.warning(f"Unexpected error flushing logs: {e}")

    def flush(self) -> None:
        """Flush any remaining logs (e.g., on shutdown)."""
        with self.buffer_lock:
            if self.log_buffer:
                self._flush_logs()

    def stream_from_process(self, process: subprocess.Popen) -> None:
        """
        Stream logs from a subprocess's stdout/stderr.
        
        Expects subprocess to output JSON-formatted log lines on stderr:
        {"timestamp": ms, "level": "INFO", "message": "...", "attributes": {...}}
        
        This method spawns threads to read from the process's pipes,
        automatically batch and flush logs based on time/size.
        The method returns immediately - logging continues in background threads.
        
        Args:
            process: subprocess.Popen object with stdout/stderr pipes
        """

        def read_stream(stream: IO, stream_name: str = "stdout") -> None:
            """Read from a stream line-by-line and emit logs."""
            try:
                if stream is None:
                    return
                
                for line in stream:
                    if line.strip():
                        try:
                            # Try to parse as JSON to preserve log level and details
                            log_data = json.loads(line.strip())
                            self.emit(log_data)
                        except json.JSONDecodeError:
                            # Fallback: treat as plain text message
                            log_data = {
                                'timestamp': int(time.time() * 1000),
                                'level': 'INFO',
                                'logger_name': f'subprocess.{stream_name}',
                                'message': line.strip(),
                                'attributes': {}
                            }
                            self.emit(log_data)

            except Exception as e:
                # Log errors silently to stderr, don't crash
                logger.warning(f"Error reading {stream_name}: {e}")
            finally:
                try:
                    if stream:
                        stream.close()
                except Exception as e:
                    logger.warning(f"Error closing {stream_name}: {e}")
        
        # Spawn reader threads for stdout and stderr
        if process.stdout:
            stdout_thread = threading.Thread(
                target=read_stream,
                args=(process.stdout, "stdout"),
                daemon=True,
            )
            stdout_thread.start()
        
        if process.stderr:
            stderr_thread = threading.Thread(
                target=read_stream,
                args=(process.stderr, "stderr"),
                daemon=True,
            )
            stderr_thread.start()

    def process_subprocess_output(self, stdout: str, stderr: str) -> None:
        """
        Process subprocess stdout/stderr output and emit as logs.
        
        Args:
            stdout: Standard output from subprocess
            stderr: Standard error from subprocess
        """
        # Process stderr lines as logs
        if stderr:
            for line in stderr.split('\n'):
                if line.strip():
                    try:
                        log_data = json.loads(line.strip())
                        self.emit(log_data)
                    except json.JSONDecodeError:
                        # Fallback: treat as plain text message
                        log_data = {
                            'timestamp': int(time.time() * 1000),
                            'level': 'INFO',
                            'logger_name': 'subprocess.stderr',
                            'message': line.strip(),
                            'attributes': {}
                        }
                        self.emit(log_data)
        
        # Process stdout lines as logs
        if stdout:
            lines = [l.strip() for l in stdout.split('\n') if l.strip()]
            for line in lines:
                try:
                    # Try to parse as JSON
                    log_data = json.loads(line)
                    # If it has log structure, treat as log
                    if 'message' in log_data and 'level' in log_data:
                        self.emit(log_data)
                    else:
                        # Plain JSON - treat as log message
                        log_data_log = {
                            'timestamp': int(time.time() * 1000),
                            'level': 'INFO',
                            'logger_name': 'subprocess.stdout',
                            'message': line,
                            'attributes': {}
                        }
                        self.emit(log_data_log)
                except json.JSONDecodeError:
                    # Fallback: treat as plain text
                    log_data = {
                        'timestamp': int(time.time() * 1000),
                        'level': 'INFO',
                        'logger_name': 'subprocess.stdout',
                        'message': line,
                        'attributes': {}
                    }
                    self.emit(log_data)
        
        # Flush remaining logs
        self._flush_logs()

    def close(self) -> None:
        """Close the handler and flush remaining logs."""
        self.should_stop = True

        # Final flush
        self.flush()

        # Wait for flush thread to complete
        if self.flush_thread and self.flush_thread.is_alive():
            self.flush_thread.join(timeout=2)

        super().close()
