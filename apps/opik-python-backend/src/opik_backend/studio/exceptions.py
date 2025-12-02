"""Custom exceptions for Optimization Studio."""


class OptimizationError(Exception):
    """Base exception for all optimization-related errors.
    
    All Studio-specific exceptions inherit from this, making it easy
    to catch any optimization error with a single except clause.
    """
    pass


class DatasetNotFoundError(OptimizationError):
    """Dataset not found or inaccessible.
    
    Raised when:
    - Dataset doesn't exist in the workspace
    - User doesn't have permission to access dataset
    - Dataset name is invalid
    """
    
    def __init__(self, dataset_name: str, original_error: Exception = None):
        self.dataset_name = dataset_name
        self.original_error = original_error
        message = (
            f"Dataset '{dataset_name}' not found or inaccessible. "
            "Please create the dataset before running optimization."
        )
        if original_error:
            message += f" Original error: {str(original_error)}"
        super().__init__(message)


class EmptyDatasetError(OptimizationError):
    """Dataset exists but has no items.
    
    Raised when a dataset is loaded successfully but contains zero items.
    Optimization requires at least one dataset item to evaluate against.
    """
    
    def __init__(self, dataset_name: str):
        self.dataset_name = dataset_name
        message = (
            f"Dataset '{dataset_name}' is empty. "
            "Please add items to the dataset before running optimization."
        )
        super().__init__(message)


class InvalidMetricError(OptimizationError):
    """Invalid metric type or configuration.
    
    Raised when:
    - Metric type is not registered in MetricFactory
    - Required metric parameters are missing
    - Metric parameters are invalid
    """
    
    def __init__(self, metric_type: str, reason: str = None):
        self.metric_type = metric_type
        message = f"Invalid metric: '{metric_type}'"
        if reason:
            message += f". {reason}"
        super().__init__(message)


class InvalidOptimizerError(OptimizationError):
    """Invalid optimizer type or configuration.
    
    Raised when:
    - Optimizer type is not registered in OptimizerFactory
    - Required optimizer parameters are missing
    - Optimizer parameters are invalid
    """
    
    def __init__(self, optimizer_type: str, reason: str = None):
        self.optimizer_type = optimizer_type
        message = f"Invalid optimizer: '{optimizer_type}'"
        if reason:
            message += f". {reason}"
        super().__init__(message)


class InvalidConfigError(OptimizationError):
    """Invalid job configuration.
    
    Raised when:
    - Required configuration fields are missing
    - Configuration values are invalid
    - Configuration structure is malformed
    """
    
    def __init__(self, field: str, reason: str):
        self.field = field
        message = f"Invalid configuration for '{field}': {reason}"
        super().__init__(message)


class JobMessageParseError(OptimizationError):
    """Failed to parse job message.
    
    Raised when:
    - Job message is not a dictionary
    - Job message format is unexpected
    - Required top-level fields are missing
    """
    
    def __init__(self, reason: str):
        message = f"Failed to parse job message: {reason}"
        super().__init__(message)

