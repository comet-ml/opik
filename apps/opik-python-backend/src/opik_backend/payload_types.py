"""
Payload type definitions for the opik-python-backend.
"""

from enum import Enum


class PayloadType(Enum):
    """Payload type constants for evaluator handling"""
    TRACE_THREAD = "trace_thread"
