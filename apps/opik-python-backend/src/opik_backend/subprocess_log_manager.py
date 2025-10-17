"""Context manager for subprocess logging lifecycle."""

import logging
from typing import Optional
from opik_backend.subprocess_log_config import SubprocessLogConfig

logger = logging.getLogger(__name__)


class SubprocessLogManager:
    """
    Context manager for subprocess logging that ensures proper cleanup.
    
    Handles the lifecycle of BatchLogCollector with guaranteed shutdown
    on success, error, or explicit teardown.
    
    Usage:
        with SubprocessLogManager(...) as log_manager:
            log_manager.process_output(stdout, stderr)
        # Automatically closes on exit
        
    Or for explicit management:
        log_manager = SubprocessLogManager(...)
        try:
            log_manager.process_output(stdout, stderr)
        finally:
            log_manager.close()
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
        Initialize the subprocess log manager.
        
        Args:
            backend_url: HTTP endpoint to POST logs to
            optimization_id: Optimization identifier for correlation
            job_id: Job identifier for correlation
            api_key: Optional API key for authentication
            workspace: Optional workspace identifier
            
        Raises:
            ValueError: If backend_url is not provided or requests library unavailable
        """
        self.backend_url = backend_url
        self.optimization_id = optimization_id
        self.job_id = job_id
        self.api_key = api_key
        self.workspace = workspace
        self._batch_log_collector = None
        self._initialized = False
        
    def initialize(self) -> None:
        """
        Lazily initialize the BatchLogCollector.
        
        This is separated from __init__ to allow creation without requiring
        the requests library to be immediately available.
        """
        if self._initialized:
            return
            
        try:
            from opik_backend.subprocess_logger import BatchLogCollector
            
            self._batch_log_collector = BatchLogCollector(
                backend_url=self.backend_url,
                optimization_id=self.optimization_id,
                job_id=self.job_id,
                api_key=self.api_key,
                workspace=self.workspace,
            )
            self._initialized = True
        except (ValueError, ImportError) as e:
            logger.error(f"Failed to initialize subprocess logging: {e}")
            raise
        except Exception as e:
            logger.error(f"Unexpected error initializing subprocess logger: {e}")
            raise
    
    def process_output(self, stdout: str, stderr: str) -> None:
        """
        Process subprocess output and emit as logs.
        
        Args:
            stdout: Standard output from subprocess
            stderr: Standard error from subprocess
            
        Raises:
            RuntimeError: If not initialized
        """
        if not self._initialized:
            self.initialize()
            
        if self._batch_log_collector:
            try:
                self._batch_log_collector.process_subprocess_output(stdout, stderr)
            except Exception as e:
                logger.error(f"Error processing subprocess output: {e}")
                raise
    
    def close(self) -> None:
        """
        Close the log manager and ensure all logs are flushed.
        
        This is safe to call multiple times.
        """
        if self._batch_log_collector and self._initialized:
            try:
                self._batch_log_collector.close()
            except Exception as e:
                logger.warning(f"Error closing subprocess logger: {e}")
            finally:
                self._batch_log_collector = None
    
    def __enter__(self):
        """Context manager entry."""
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit with guaranteed cleanup."""
        self.close()
        return False  # Don't suppress exceptions
