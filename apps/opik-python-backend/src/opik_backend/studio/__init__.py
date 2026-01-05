"""Optimization Studio module for Opik Python Backend.

This module contains all the logic specific to Optimization Studio:
- Configuration (config.py)
- Data types (types.py)
- Exceptions (exceptions.py)
- Status management (status_manager.py)
- Helper functions (helpers.py)
- Metric factory (metrics.py)
- Optimizer factory (optimizers.py)
"""

from .config import (
    OPIK_URL,
    LLM_API_KEYS,
    OPTIMIZER_RUNTIME_PARAMS,
    DEFAULT_REFERENCE_KEY,
    DEFAULT_CASE_SENSITIVE,
    OPTIMIZATION_TIMEOUT_SECS,
)
from .types import OptimizationJobContext, OptimizationConfig, OptimizationResult
from .exceptions import (
    OptimizationError,
    DatasetNotFoundError,
    EmptyDatasetError,
    InvalidMetricError,
    InvalidOptimizerError,
    InvalidConfigError,
    JobMessageParseError,
)
from .status_manager import OptimizationStatusManager, optimization_lifecycle
from .metrics import MetricFactory
from .optimizers import OptimizerFactory
from .helpers import (
    initialize_opik_client,
    load_and_validate_dataset,
    run_optimization,
)
from .cancellation import CancellationHandle, CancellationMonitor, get_cancellation_monitor

__all__ = [
    # Config
    "OPIK_URL",
    "LLM_API_KEYS",
    "OPTIMIZER_RUNTIME_PARAMS",
    "DEFAULT_REFERENCE_KEY",
    "DEFAULT_CASE_SENSITIVE",
    "OPTIMIZATION_TIMEOUT_SECS",
    # Types
    "OptimizationJobContext",
    "OptimizationConfig",
    "OptimizationResult",
    # Exceptions
    "OptimizationError",
    "DatasetNotFoundError",
    "EmptyDatasetError",
    "InvalidMetricError",
    "InvalidOptimizerError",
    "InvalidConfigError",
    "JobMessageParseError",
    # Status Management
    "OptimizationStatusManager",
    "optimization_lifecycle",
    # Factories
    "MetricFactory",
    "OptimizerFactory",
    # Helpers
    "initialize_opik_client",
    "load_and_validate_dataset",
    "run_optimization",
    # Cancellation
    "CancellationHandle",
    "CancellationMonitor",
    "get_cancellation_monitor",
]

