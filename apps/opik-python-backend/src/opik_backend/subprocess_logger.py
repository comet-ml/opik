"""
Log streaming for isolated subprocess execution.

This module provides log collectors for streaming logs from subprocesses:
- BatchLogCollector: Streams to HTTP endpoint (legacy)
- RedisBatchLogCollector: Streams to Redis for S3 sync by Java backend

Configuration: All settings passed as parameters - no environment variables.
"""

import json
import logging
import os
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Optional, List, Dict, Any, IO
import subprocess
from opik_backend.subprocess_log_config import SubprocessLogConfig
import requests

logger = logging.getLogger(__name__)

# Redis key patterns for optimization logs
def _redis_log_key(workspace_id: str, optimization_id: str) -> str:
    """Redis LIST key for log entries."""
    return f"opik:logs:{workspace_id}:{optimization_id}"


def _redis_meta_key(workspace_id: str, optimization_id: str) -> str:
    """Redis HASH key for metadata (timestamps)."""
    return f"opik:logs:{workspace_id}:{optimization_id}:meta"


# Default TTL for Redis keys (configurable, default 24 hours as safety net)
REDIS_KEY_TTL_SECONDS = int(os.environ.get("OPTSTUDIO_LOG_REDIS_TTL", "86400"))

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
        self.flush_executor: Optional[ThreadPoolExecutor] = None
        self.should_stop = False
        self._resource_lock = threading.Lock()  # Protects reader executor and futures
        self._reader_executor: Optional[ThreadPoolExecutor] = None
        self._reader_futures: List = []

        # Set formatter
        self.setFormatter(logging.Formatter("%(levelname)s - %(name)s - %(message)s"))

        # Start background flush thread pool
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
        self.flush_executor = ThreadPoolExecutor(max_workers=1)
        self.flush_executor.submit(self._periodic_flush)

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

    def close(self) -> None:
        """
        Flush any remaining logs and stop the flush thread and reader threads.
        
        Should be called when done collecting logs to ensure all data is sent
        and all threads are properly cleaned up.
        """
        from concurrent.futures import TimeoutError as FuturesTimeoutError
        
        # Signal periodic flush to stop
        self.should_stop = True
        
        # Shutdown executor and wait for it to finish its current/pending work
        if self.flush_executor:
            try:
                # shutdown(wait=True) will wait for any pending tasks to complete
                self.flush_executor.shutdown(wait=True)
            except Exception as e:
                logger.error(f"Error shutting down flush executor: {e}")
        
        # Do a final flush to ensure all remaining logs are sent
        self.flush()
        
        # Wait for reader threads to finish (with timeout to avoid hanging)
        with self._resource_lock:
            if self._reader_executor:
                try:
                    # Wait for all reader futures to complete
                    for future in self._reader_futures:
                        if not future.done():
                            try:
                                future.result(timeout=2.0)
                            except FuturesTimeoutError:
                                logger.error("Reader thread timed out after 2.0s")
                            except Exception as e:
                                logger.warning(f"Error in reader thread: {e}")
                except Exception as e:
                    logger.warning(f"Error iterating reader futures: {e}")
                
                # Shutdown the reader executor
                try:
                    self._reader_executor.shutdown(wait=False)
                except Exception as e:
                    logger.warning(f"Error shutting down reader executor: {e}")
                finally:
                    # Clear futures list and set executor to None
                    self._reader_futures.clear()
                    self._reader_executor = None

    def start_stream_from_process(self, process: subprocess.Popen) -> Dict[str, str]:
        """
        Start real-time log streaming from subprocess pipes in background threads.
        
        Spawns threads to read stdout and stderr line-by-line, parsing logs,
        and emitting them. Logs are automatically batched and flushed based on
        time intervals or accumulated size.
        
        This method returns immediately - streaming continues in background threads.
        Call close() or teardown to ensure all logs are sent before process cleanup.
        
        Args:
            process: subprocess.Popen object with stdout/stderr pipes configured
        
        Returns:
            Dict with 'last_lines' dict for the caller to get output after threads finish
        """
        threads = {}
        
        # Store only the last line from each stream (for result parsing)
        last_lines = {'stdout': '', 'stderr': ''}
        
        with self._resource_lock:
            # Create executor once if not already created
            if self._reader_executor is None:
                self._reader_executor = ThreadPoolExecutor(max_workers=2)  # Concurrent stdout and stderr reading
            
            # Clear previous futures before starting new reader threads
            # (ensures futures list doesn't accumulate if close() wasn't called)
            self._reader_futures.clear()
            
            # Spawn reader tasks for stdout and stderr concurrently in single executor
            if process.stdout:
                future = self._reader_executor.submit(self._read_stream, process.stdout, "stdout", last_lines)
                self._reader_futures.append(future)
            
            if process.stderr:
                future = self._reader_executor.submit(self._read_stream, process.stderr, "stderr", last_lines)
                self._reader_futures.append(future)
        
        # Store last lines for later retrieval
        self._last_lines = last_lines
        
        # Return dict with last_lines reference
        threads['last_lines'] = last_lines
        return threads
    
    def get_last_lines(self) -> Dict[str, str]:
        """
        Get the last line collected from each stream.
        
        Should be called after threads have finished to get the final output.
        """
        return getattr(self, '_last_lines', {'stdout': '', 'stderr': ''})
    
    def wait_for_reader_threads(self, timeout: float = 5.0) -> None:
        """
        Wait for reader threads to finish reading from process pipes.
        
        This should be called after the process has finished to ensure all output
        has been read before retrieving last_lines.
        
        Args:
            timeout: Maximum time to wait for threads in seconds
        """
        from concurrent.futures import TimeoutError as FuturesTimeoutError
        
        with self._resource_lock:
            if self._reader_executor and self._reader_futures:
                try:
                    # Wait for all reader futures to complete
                    for future in self._reader_futures:
                        if not future.done():
                            try:
                                future.result(timeout=timeout)
                            except FuturesTimeoutError:
                                logger.error(f"Reader thread timed out after {timeout}s")
                            except Exception as e:
                                logger.warning(f"Error in reader thread: {e}")
                except Exception as e:
                    logger.warning(f"Error iterating reader futures: {e}")

    def _read_stream(self, pipe: Optional[IO], stream_name: str, last_lines: Dict[str, str]) -> None:
        """
        Read from a stream line-by-line, emit logs, and keep only the last line.
        
        Private method used by start_stream_from_process to handle individual streams.
        Parses JSON logs to extract structured fields, falls back to plain text.
        
        Args:
            pipe: Input stream to read from (stdout or stderr)
            stream_name: Name of stream ("stdout" or "stderr") for logging
            last_lines: Dict to store the last line from this stream
        """
        if pipe is None:
            return
        try:
            for line in pipe:
                # Only process non-empty lines
                if line.strip():
                    # Keep only the last line (for result parsing)
                    last_lines[stream_name] = line
                    try:
                        # Try to parse as JSON to preserve log structure
                        log_data = json.loads(line.strip())
                        # If it has log structure (level and logger_name), emit as-is
                        if 'level' in log_data and 'logger_name' in log_data:
                            self.emit(log_data)
                        else:
                            # Plain JSON result - treat as log message
                            log_data = {
                                'timestamp': int(time.time() * 1000),
                                'level': 'INFO',
                                'logger_name': f'subprocess.{stream_name}',
                                'message': line.strip(),
                                'attributes': {}
                            }
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
            logger.warning(f"Error reading {stream_name}: {e}")
        finally:
            try:
                if pipe:
                    pipe.close()
            except Exception as e:
                logger.warning(f"Error closing {stream_name}: {e}")


class RedisBatchLogCollector(BatchLogCollector):
    """
    Log collector that writes raw log lines to Redis.
    
    Used for Optimization Studio to stream logs to Redis, where they are
    periodically synced to S3 by the Java backend.
    
    Stores raw text lines (preserving Rich formatting, colors, etc.) rather
    than structured JSON. This keeps the logs human-readable.
    
    Inherits stream reading capabilities from BatchLogCollector but overrides
    the flush mechanism to write to Redis.
    """
    
    def __init__(
        self,
        workspace_id: str,
        optimization_id: str,
        redis_client=None,
    ):
        """
        Initialize the Redis batch log collector.
        
        Args:
            workspace_id: Workspace identifier for Redis key
            optimization_id: Optimization identifier for Redis key
            redis_client: Optional Redis client (will get from redis_utils if not provided)
        """
        # Don't call super().__init__() as it requires backend_url
        # Instead, initialize the shared attributes directly
        logging.Handler.__init__(self)
        
        self.workspace_id = workspace_id
        self.optimization_id = optimization_id
        
        # Get Redis client
        if redis_client is None:
            from opik_backend.utils.redis_utils import get_redis_client
            self.redis_client = get_redis_client()
        else:
            self.redis_client = redis_client
        
        # Redis keys
        self._log_key = _redis_log_key(workspace_id, optimization_id)
        self._meta_key = _redis_meta_key(workspace_id, optimization_id)
        
        # Configuration from SubprocessLogConfig
        self.flush_interval_ms = SubprocessLogConfig.get_flush_interval_ms()
        self.max_size_bytes = SubprocessLogConfig.get_max_size_bytes()
        
        # Buffer for raw log lines (strings, not SubprocessLogRecord)
        self.log_buffer: List[str] = []
        self.buffer_size_bytes: int = 0
        self.buffer_lock = threading.Lock()
        self.last_flush_time = time.time()
        self.flush_executor: Optional[ThreadPoolExecutor] = None
        self.should_stop = False
        self._resource_lock = threading.Lock()
        self._reader_executor: Optional[ThreadPoolExecutor] = None
        self._reader_futures: List = []
        
        # Set formatter (not used for raw capture, but required by Handler)
        self.setFormatter(logging.Formatter("%(message)s"))
        
        # Track last line for dedup across flushes
        self._last_line_for_dedup: Optional[str] = None
        
        # Start background flush thread
        self._start_flush_thread()
        
        logger.info(
            f"RedisBatchLogCollector initialized for optimization '{optimization_id}' "
            f"in workspace '{workspace_id}'"
        )
    
    # Regex to strip ALL ANSI escape codes for comparison
    # Matches: CSI sequences (\x1b[...X), OSC sequences (\x1b]...\x07 or \x1b]...\x1b\\)
    ANSI_ESCAPE = re.compile(r'\x1b\[[0-9;]*[A-Za-z]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)?')
    
    def _strip_for_compare(self, text: str) -> str:
        """Strip ANSI codes and first visible char for duplicate detection."""
        stripped = self.ANSI_ESCAPE.sub('', text)
        return stripped[1:] if len(stripped) > 1 else stripped
    
    def emit(self, record) -> None:
        """
        Handle a log record by adding raw line to the buffer.
        
        Overrides parent to store raw strings instead of structured records.
        Skips duplicate consecutive lines that only differ by spinner character.
        
        Args:
            record: Either a dict with 'message' key or a logging.LogRecord
        """
        try:
            # Extract the raw message
            if isinstance(record, dict):
                message = record.get('message', str(record))
            else:
                message = self.format(record)
            
            # Skip empty messages
            if not message or not message.strip():
                return
            
            message = message.strip()
            
            with self.buffer_lock:
                # Skip if identical after stripping ANSI codes and first char
                # Use _last_line_for_dedup to track across buffer flushes
                if self._last_line_for_dedup:
                    last_cmp = self._strip_for_compare(self._last_line_for_dedup)
                    curr_cmp = self._strip_for_compare(message)
                    if last_cmp == curr_cmp:
                        return
                
                self._last_line_for_dedup = message
                self.log_buffer.append(message)
                self.buffer_size_bytes += len(message.encode('utf-8'))
                
                # Check if we should flush based on buffer size
                if self.buffer_size_bytes >= self.max_size_bytes:
                    self._flush_logs()
        
        except Exception as e:
            logger.warning(f"Error in RedisBatchLogCollector emit: {e}")
    
    def _read_stream(self, pipe: Optional[IO], stream_name: str, last_lines: Dict[str, str]) -> None:
        """
        Read from a stream line-by-line, emit raw logs without JSON parsing.
        
        Overrides parent to emit raw lines for Redis storage, preserving
        Rich formatting and ANSI codes.
        
        Args:
            pipe: Input stream to read from (stdout or stderr)
            stream_name: Name of stream ("stdout" or "stderr") for logging
            last_lines: Dict to store the last line from this stream
        """
        if pipe is None:
            return
        try:
            for line in pipe:
                # Only process non-empty lines
                if line.strip():
                    # Keep only the last line (for result parsing)
                    last_lines[stream_name] = line
                    # Emit raw line - Redis collector stores as-is
                    self.emit({'message': line.strip()})
        except Exception as e:
            logger.warning(f"Error reading {stream_name}: {e}")
        finally:
            try:
                if pipe:
                    pipe.close()
            except Exception as e:
                logger.warning(f"Error closing {stream_name}: {e}")
    
    def _flush_logs(self) -> None:
        """
        Flush buffered logs to Redis as raw strings.
        
        Note: Must be called with buffer_lock held.
        """
        if not self.log_buffer:
            return
        
        try:
            # Use pipeline for atomic operations
            pipe = self.redis_client.pipeline()
            
            # Append all raw log lines to the LIST
            pipe.rpush(self._log_key, *self.log_buffer)
            
            # Update last_append_ts in metadata
            current_ts = int(time.time() * 1000)
            pipe.hset(self._meta_key, "last_append_ts", str(current_ts))
            
            # Set/refresh TTL on both keys (safety net)
            pipe.expire(self._log_key, REDIS_KEY_TTL_SECONDS)
            pipe.expire(self._meta_key, REDIS_KEY_TTL_SECONDS)
            
            # Execute pipeline
            pipe.execute()
            
            log_count = len(self.log_buffer)
            self.log_buffer.clear()
            self.buffer_size_bytes = 0
            self.last_flush_time = time.time()
            
            logger.debug(
                f"Flushed {log_count} logs to Redis for optimization '{self.optimization_id}'"
            )
        
        except Exception as e:
            logger.error(f"Failed to flush logs to Redis: {e}")
            # Keep logs in buffer for retry on next flush


def create_optimization_log_collector(
    workspace_id: str,
    optimization_id: str,
) -> RedisBatchLogCollector:
    """
    Factory function to create a log collector for Optimization Studio.
    
    Args:
        workspace_id: Workspace identifier
        optimization_id: Optimization identifier
        
    Returns:
        RedisBatchLogCollector instance
    """
    return RedisBatchLogCollector(
        workspace_id=workspace_id,
        optimization_id=optimization_id,
    )
