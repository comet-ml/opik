"""
Centralized configuration for subprocess logging.

All environment variable reading happens here.
This is the single source of truth for logging configuration.
"""

import os
from typing import Optional


class SubprocessLogConfig:
    """Centralized configuration for subprocess logging."""
    
    # Environment variable names
    BACKEND_URL_ENV = "OPIK_SUBPROCESS_LOG_BACKEND_URL"
    ENABLED_ENV = "SUBPROCESS_LOG_ENABLED"
    FLUSH_INTERVAL_ENV = "SUBPROCESS_LOG_FLUSH_INTERVAL"
    MAX_SIZE_ENV = "SUBPROCESS_LOG_MAX_SIZE"
    REQUEST_TIMEOUT_ENV = "SUBPROCESS_LOG_REQUEST_TIMEOUT"
    LOG_READER_TIMEOUT_ENV = "SUBPROCESS_LOG_READER_TIMEOUT"
    
    # Defaults
    DEFAULT_FLUSH_INTERVAL_MS = 1000  # 1 second
    DEFAULT_MAX_SIZE_BYTES = 10 * 1024 * 1024  # 10MB
    DEFAULT_REQUEST_TIMEOUT_SECS = 60
    DEFAULT_LOG_READER_TIMEOUT_SECS = 5  # 5 seconds
    
    @classmethod
    def get_backend_url(cls) -> Optional[str]:
        """Get logging backend URL from environment."""
        return os.getenv(cls.BACKEND_URL_ENV)

    @classmethod
    def is_enabled(cls) -> bool:
        """Check if logging is enabled."""
        enabled_str = os.getenv(cls.ENABLED_ENV, "false").lower()
        return enabled_str == "true"
    
    @classmethod
    def get_flush_interval_ms(cls) -> int:
        """Get flush interval in milliseconds."""
        try:
            return int(os.getenv(cls.FLUSH_INTERVAL_ENV, str(cls.DEFAULT_FLUSH_INTERVAL_MS)))
        except (ValueError, TypeError):
            return cls.DEFAULT_FLUSH_INTERVAL_MS
    
    @classmethod
    def get_max_size_bytes(cls) -> int:
        """Get maximum buffer size in bytes."""
        try:
            return int(os.getenv(cls.MAX_SIZE_ENV, str(cls.DEFAULT_MAX_SIZE_BYTES)))
        except (ValueError, TypeError):
            return cls.DEFAULT_MAX_SIZE_BYTES
    
    @classmethod
    def get_request_timeout_secs(cls) -> int:
        """
        Get HTTP request timeout in seconds for posting logs to backend.
        
        Returns:
            int: Timeout in seconds (default: 60)
        """
        try:
            return int(os.getenv(cls.REQUEST_TIMEOUT_ENV, str(cls.DEFAULT_REQUEST_TIMEOUT_SECS)))
        except (ValueError, TypeError):
            return cls.DEFAULT_REQUEST_TIMEOUT_SECS
    
    @classmethod
    def get_log_reader_timeout_secs(cls) -> int:
        """
        Get log reader thread timeout in seconds.
        
        Returns:
            int: Timeout in seconds (default: 5)
        """
        try:
            return int(os.getenv(cls.LOG_READER_TIMEOUT_ENV, str(cls.DEFAULT_LOG_READER_TIMEOUT_SECS)))
        except (ValueError, TypeError):
            return cls.DEFAULT_LOG_READER_TIMEOUT_SECS
    
    @classmethod
    def is_fully_configured(cls) -> bool:
        """Check if all required configuration is present."""
        return bool(cls.get_backend_url() and cls.is_enabled())
