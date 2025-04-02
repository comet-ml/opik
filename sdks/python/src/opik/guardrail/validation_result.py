from typing import Any, Dict, Optional

class ValidationResult:
    """A class that holds the results of a validation process.
    
    Attributes:
        passed: Whether all validations passed
        scores: Dictionary mapping metric names to their scores
        reasons: Dictionary mapping metric names to their reasoning
        transformed_output: The output after transformations
        raw_output: The original output before transformations
        metadata: Additional metadata about the validation
    """
    
    def __init__(
        self,
        passed: bool,
        scores: Dict[str, float],
        reasons: Dict[str, str],
        transformed_output: Any,
        raw_output: Any,
        metadata: Optional[Dict[str, Any]] = None
    ):
        self.passed = passed
        self.scores = scores
        self.reasons = reasons
        self.transformed_output = transformed_output
        self.raw_output = raw_output
        self.metadata = metadata or {} 