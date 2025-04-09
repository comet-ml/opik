"""
Type annotations for botocore.retries.throttling module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, NamedTuple

class CubicParams(NamedTuple):
    w_max: Any
    k: Any
    last_fail: Any

class CubicCalculator:
    def __init__(
        self,
        starting_max_rate: Any,
        start_time: Any,
        scale_constant: Any = ...,
        beta: Any = ...,
    ) -> None: ...
    def success_received(self, timestamp: Any) -> Any: ...
    def error_received(self, current_rate: Any, timestamp: Any) -> Any: ...
    def get_params_snapshot(self) -> Any: ...
