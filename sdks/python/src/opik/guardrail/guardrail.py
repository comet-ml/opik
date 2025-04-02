import asyncio
import logging
from typing import Any, Callable, Dict, List, Optional, Awaitable
import uuid
from opik import track

from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from .transformation import Transformation
from .validation_result import ValidationResult

logger = logging.getLogger(__name__)

class Guardrail:
    """A class that orchestrates validation and transformation of LLM outputs.
    
    This class provides a flexible framework for validating and transforming LLM outputs
    using Opik metrics. It supports transformations, retries, and success/failure hooks.
    """
    
    def __init__(
        self,
        metrics: List[BaseMetric],
        calculate_success: Callable[[List[ScoreResult]], bool],
        transformations: Optional[List[Transformation]] = None,
        on_success: Optional[Callable[[ValidationResult], None]] = None, # TODO: Think through async
        on_failure: Optional[Callable[[ValidationResult], None]] = None,
        max_retries: int = 0,
        retry_delay: float = 1.0,
        retry_backoff: float = 2.0,
        name: Optional[str] = None,
        description: Optional[str] = None
    ):
        """Initialize a Guardrail instance.
        
        Args:
            metrics: List of Opik metrics to use for validation
            calculate_success: Callback function that determines if validation results constitute success
            transformations: Optional list of transformations to apply before validation
            on_success: Optional callback for successful validation
            on_failure: Optional callback for failed validation
            max_retries: Maximum number of retry attempts
            retry_delay: Initial delay between retries in seconds
            retry_backoff: Multiplier for retry delay after each attempt
            name: Optional name for the guardrail
            description: Optional description of the guardrail
        """
        self.metrics = metrics
        self.calculate_success = calculate_success
        self.transformations = transformations or []
        self.on_success = on_success
        self.on_failure = on_failure
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        self.retry_backoff = retry_backoff
        self.name = name or f"guardrail_{uuid.uuid4()}"
        self.description = description
        self._history: List[ValidationResult] = []
    
    @track
    async def __call__(
        self,
        llm_output: Any,
        input_data: Optional[Dict[str, Any]] = None,
        **kwargs
    ) -> ValidationResult:
        """Execute the validation process.
        
        Args:
            llm_output: The output from the LLM to validate
            input_data: Optional input data for context
            **kwargs: Additional arguments passed to metric scoring
            
        Returns:
            ValidationResult containing validation outcomes and transformed output
        """
        return await self._execute_with_retry(llm_output, input_data, **kwargs)
    
    async def _execute_with_retry(
        self,
        llm_output: Any,
        input_data: Optional[Dict[str, Any]] = None,
        **kwargs
    ) -> ValidationResult:
        """Execute validation with retry logic.
        
        Args:
            llm_output: The output from the LLM to validate
            input_data: Optional input data for context
            **kwargs: Additional arguments passed to metric scoring
            
        Returns:
            ValidationResult containing the validation outcome
        """
        attempt = 0
        delay = self.retry_delay
        
        while True:
            try:
                # Apply transformations
                transformed_output = llm_output
                for transform in self.transformations:
                    transformed_output = transform.apply(transformed_output)
                
                # Run validation metrics
                results: List[ScoreResult] = []
                
                for metric in self.metrics:
                    result = await metric.score(
                        output=transformed_output,
                        input_data=input_data,
                        **kwargs
                    )
                    results.append(result)
                
                # Calculate overall success
                passed = self.calculate_success(results)
                
                # Create validation result
                result = ValidationResult(
                    passed=passed,
                    results=results,
                    transformed_output=transformed_output,
                    raw_output=llm_output
                )
                
                # Execute hooks
                if passed and self.on_success is not None:
                    self.on_success(result)
                elif not passed and self.on_failure is not None:
                    self.on_failure(result)
                
                # Add to history
                self._history.append(result)
                
                return result
                
            except Exception as e:
                if attempt >= self.max_retries:
                    raise
                
                logger.warning(
                    f"Validation attempt {attempt + 1} failed: {str(e)}. "
                    f"Retrying in {delay} seconds..."
                )
                
                await asyncio.sleep(delay)
                attempt += 1
                delay *= self.retry_backoff
    
    @property
    def history(self) -> List[ValidationResult]:
        """Get the validation history.
        
        Returns:
            List of ValidationResult objects from previous validations
        """
        return self._history.copy()