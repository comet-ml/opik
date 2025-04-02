from typing import Any, Dict, Optional, List
from opik.evaluation.metrics.score_result import ScoreResult

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
        results: List[ScoreResult],
        transformed_output: Any,
        raw_output: Any,
    ):
        self.passed = passed
        self.results = results
        self.transformed_output = transformed_output
        self.raw_output = raw_output
