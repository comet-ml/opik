#!/usr/bin/env python3
"""
Optimization script that runs in Daytona sandbox.

This script:
1. Receives dataset_id and other parameters via command line arguments
2. Uses environment variables for authentication (OPIK_API_KEY, OPIK_WORKSPACE_ID, OPIK_URL_OVERRIDE)
3. Runs the optimization process
4. Reports results back to Opik

Usage:
    python optimization_script.py --dataset_name <name> --optimization_studio_run_id <uuid> --algorithm <algorithm> --metric <metric> --prompt [{"role": "user", "content": "Hello, how are you?"}]
"""

import sys
import os
import argparse
import opik
import json
import logging
import io
import re
import time
import threading
import requests
from datetime import datetime, timezone
from contextlib import redirect_stdout, redirect_stderr
from rich.console import Console
from rich import get_console as rich_get_console
from opik_optimizer import HierarchicalReflectiveOptimizer, ChatPrompt, datasets
from opik.evaluation.metrics.score_result import ScoreResult
from opik_optimizer.utils.core import get_optimization_run_url_by_id
from opik_optimizer import logging_config
from opik_optimizer import reporting_utils


class LogUploader:
    """Handles batching and uploading logs to backend every 2 seconds."""

    def __init__(self, run_id, api_key, url_override):
        self.run_id = run_id
        self.api_key = api_key
        self.url_override = url_override.rstrip('/')
        self.log_batch = []
        self.batch_lock = threading.Lock()
        self.upload_thread = None
        self.stop_event = threading.Event()
        self.ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
        self.log_sequence = 0  # Sequence counter to ensure ordering
        self.base_time = datetime.now(timezone.utc)  # Base time for all logs

    def start(self):
        """Start the background thread that uploads logs every 2 seconds."""
        self.upload_thread = threading.Thread(target=self._upload_loop, daemon=True)
        self.upload_thread.start()

    def stop(self):
        """Stop the background thread and flush remaining logs."""
        self.stop_event.set()
        if self.upload_thread:
            self.upload_thread.join(timeout=5)
        # Final flush
        self._flush_logs()

    def add_log(self, level, message):
        """Add a log entry to the batch with sequential timestamp."""
        with self.batch_lock:
            # Use base time + sequence in microseconds to ensure strict ordering
            from datetime import timedelta
            timestamp = self.base_time + timedelta(microseconds=self.log_sequence)
            self.log_sequence += 1

            self.log_batch.append({
                "level": level,
                "message": message,
                "timestamp": timestamp.isoformat()
            })

    def _upload_loop(self):
        """Background thread that uploads logs every 2 seconds."""
        while not self.stop_event.is_set():
            time.sleep(2)
            self._flush_logs()

    def _flush_logs(self):
        """Upload all batched logs to the backend."""
        with self.batch_lock:
            if not self.log_batch:
                return

            logs_to_upload = self.log_batch.copy()
            self.log_batch.clear()

        try:
            url = f"{self.url_override}/v1/private/optimization-studio/runs/{self.run_id}/logs"
            headers = {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json"
            }

            response = requests.post(url, json=logs_to_upload, headers=headers, timeout=10)
            response.raise_for_status()

            # Log to stderr so it doesn't interfere with captured output
            sys.__stderr__.write(f"[LogUploader] Uploaded {len(logs_to_upload)} logs\n")
            sys.__stderr__.flush()

        except Exception as e:
            # Log error to stderr, don't fail the optimization
            sys.__stderr__.write(f"[LogUploader] Failed to upload logs: {e}\n")
            sys.__stderr__.flush()

    def parse_and_add_line(self, line):
        """Parse a log line and add it to the batch."""
        if not line or not line.strip():
            return

        # Try to determine log level from the line
        level = "INFO"  # default
        if "ERROR" in line.upper() or "FAIL" in line.upper():
            level = "ERROR"
        elif "WARN" in line.upper():
            level = "WARN"
        elif "DEBUG" in line.upper():
            level = "DEBUG"

        # Skip DEBUG logs - don't upload them
        if level == "DEBUG":
            return

        # Keep the message as-is (with ANSI codes as requested)
        self.add_log(level, line)


class TeeBuffer(io.StringIO):
    """A buffer that writes to StringIO and also parses/uploads logs."""

    def __init__(self, log_uploader=None):
        super().__init__()
        self.log_uploader = log_uploader
        self.line_buffer = ""

    def write(self, s):
        # Write to the StringIO buffer
        result = super().write(s)

        # Also parse lines and send to log uploader
        if self.log_uploader:
            self.line_buffer += s
            # Split by newlines and process each line
            while '\n' in self.line_buffer:
                line, self.line_buffer = self.line_buffer.split('\n', 1)
                # Split multi-line logs by literal \n and upload each separately
                if line:
                    # Handle literal \n in strings (escaped newlines)
                    if '\\n' in line:
                        sub_lines = line.split('\\n')
                        for sub_line in sub_lines:
                            if sub_line.strip():  # Only process non-empty lines
                                self.log_uploader.parse_and_add_line(sub_line)
                    else:
                        self.log_uploader.parse_and_add_line(line)

        return result


class LogCapture:
    """Captures all logs including stdout, stderr, and Rich console output."""

    def __init__(self, log_uploader=None):
        # Use TeeBuffer that both captures and uploads
        self.buffer = TeeBuffer(log_uploader)
        self.stdout_redirect = None
        self.stderr_redirect = None
        self.original_handlers = {}
        self.original_get_console = None
        self.captured_console = None
        self.log_uploader = log_uploader

    def __enter__(self):
        # Set opik_optimizer logging level to WARNING to reduce noise
        os.environ['OPIK_LOG_LEVEL'] = 'WARNING'

        # Force reconfigure logging to capture warnings and errors
        logging_config.setup_logging(level=logging.WARNING, force=True)

        # Create a console that writes to our buffer with legacy_windows=False and force_terminal=True
        # This ensures hyperlinks are rendered as escape codes
        self.captured_console = Console(
            file=self.buffer,
            force_terminal=True,
            width=120,
            legacy_windows=False,
            force_interactive=False,
            no_color=False,
            markup=True
        )

        # Monkey-patch the get_console function to return our captured console
        self.original_get_console = reporting_utils.get_console
        reporting_utils.get_console = lambda *args, **kwargs: self.captured_console

        # Replace RichHandler's console in opik_optimizer logger
        opik_logger = logging.getLogger('opik_optimizer')
        self.original_handlers['opik_optimizer'] = opik_logger.handlers.copy()

        # Remove existing handlers and add one that writes to our buffer
        for handler in opik_logger.handlers[:]:
            opik_logger.removeHandler(handler)

        # Add a new RichHandler that writes to our buffer
        from rich.logging import RichHandler
        new_handler = RichHandler(
            console=self.captured_console,
            rich_tracebacks=True,
            markup=True,
            show_time=True,
            show_level=True,
            show_path=False,
        )
        new_handler.setLevel(logging.WARNING)
        opik_logger.addHandler(new_handler)

        # Also redirect stdout and stderr to capture print statements
        self.stdout_redirect = redirect_stdout(self.buffer)
        self.stderr_redirect = redirect_stderr(self.buffer)
        self.stdout_redirect.__enter__()
        self.stderr_redirect.__enter__()

        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        # Restore stdout/stderr
        if self.stdout_redirect:
            self.stdout_redirect.__exit__(exc_type, exc_val, exc_tb)
        if self.stderr_redirect:
            self.stderr_redirect.__exit__(exc_type, exc_val, exc_tb)

        # Restore original get_console function
        if self.original_get_console:
            reporting_utils.get_console = self.original_get_console

        # Restore original handlers
        if 'opik_optimizer' in self.original_handlers:
            opik_logger = logging.getLogger('opik_optimizer')
            for handler in opik_logger.handlers[:]:
                opik_logger.removeHandler(handler)
            for handler in self.original_handlers['opik_optimizer']:
                opik_logger.addHandler(handler)

    def get_logs(self):
        """Get all captured logs as a single string."""
        return self.buffer.getvalue()


def answer_quality_metric(dataset_item, llm_output):
    reference = dataset_item.get("answer", "")
    # Your scoring logic
    is_correct = reference.lower() in llm_output.lower()
    score = 1.0 if is_correct else 0.0
    # IMPORTANT: Provide detailed reasoning
    if is_correct:
        reason = f"Output contains the correct answer: '{reference}'"
    else:
        reason = f"Output does not contain expected answer '{reference}'. Output was too vague or incorrect."
    return ScoreResult(
        name="answer_quality",
        value=score,
        reason=reason  # Critical for root cause analysis!
    )


def main():
    parser = argparse.ArgumentParser(description='Run optimization on a dataset')
    parser.add_argument('--dataset_name', required=True, help='Dataset name to optimize on')
    parser.add_argument('--optimization_studio_run_id', required=True, help='Optimization Studio Run ID (for logs)')
    parser.add_argument('--optimization_id', required=False, default=None, help='Optimization ID (for optimize_prompt tracking)')
    parser.add_argument('--algorithm', required=True, choices=['evolutionary', 'parameter', 'hierarchical_reflective'],
                        help='Optimization algorithm to use')
    parser.add_argument('--metric', required=True, help='Metric to optimize for')
    parser.add_argument('--prompt', required=True, help='Prompt configuration as JSON string')

    args = parser.parse_args()

    # Get authentication from environment variables
    api_key = os.environ.get('OPIK_API_KEY')
    workspace_name = os.environ.get('OPIK_WORKSPACE')
    opik_url = os.environ.get('OPIK_URL_OVERRIDE', "https://www.comet.com/opik/api")

    if not api_key:
        print("ERROR: OPIK_API_KEY environment variable not set", file=sys.stderr)
        sys.exit(1)

    if not workspace_name:
        print("ERROR: OPIK_WORKSPACE environment variable not set", file=sys.stderr)
        sys.exit(1)

    # Parse prompt JSON
    try:
        prompt_messages = json.loads(args.prompt)
    except json.JSONDecodeError as e:
        print(f"ERROR: Invalid prompt JSON: {e}", file=sys.stderr)
        sys.exit(1)

    # Create log uploader and start background thread
    log_uploader = LogUploader(
        run_id=args.optimization_studio_run_id,
        api_key=api_key,
        url_override=opik_url
    )
    log_uploader.start()

    # Capture all logs - start BEFORE any opik operations
    log_capture = LogCapture(log_uploader=log_uploader)

    try:
        with log_capture:
            # Get dataset by name
            opik_client = opik.Opik()
            dataset = opik_client.get_dataset(args.dataset_name)

            # Create chat prompt
            prompt = ChatPrompt(
                project_name="Optimization Studio",
                messages=prompt_messages
            )

            # Run optimization
            optimizer = HierarchicalReflectiveOptimizer(
                reasoning_model="openai/gpt-4.1"
            )

            result = optimizer.optimize_prompt(
                prompt=prompt,
                dataset=dataset,
                metric=answer_quality_metric,
                #optimization_id=args.optimization_id
            )

    except Exception as e:
        print(f"[LOG] ERROR: Optimization failed: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
    finally:
        # Stop the log uploader and flush remaining logs
        log_uploader.stop()


if __name__ == "__main__":
    main()
