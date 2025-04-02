import asyncio
import logging
from typing import Any, Callable, Dict, List, Optional

from opik.evaluation.metrics import BaseMetric
from opik.utils.naming_utils import random_id
from opik.tracing import trace

from .transformation import Transformation
from .validation_result import ValidationResult

LOGGER = logging.getLogger(__name__)

class Guardrail:
    """A class that orchestrates validation and transformation of LLM outputs.
    
    This class provides a framework for:
    1. Transforming LLM outputs
    2. Validating outputs using Opik metrics
    3. Handling retries
    4. Executing success/failure hooks
    5. Tracking validation history
    """
    
    def __init__(
        self,
        metrics: List[BaseMetric],
        transformations: Optional[List[Transformation]] = None,
        on_success: Optional[Callable] = None,
        on_failure: Optional[Callable] = None,
        max_retries: int = 0,
        retry_delay: float = 1.0,
        retry_backoff: float = 2.0,
        name: Optional[str] = None,
        description: Optional[str] = None
    ):
        self.metrics = metrics
        self.transformations = transformations or []
        self.on_success = on_success
        self.on_failure = on_failure
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        self.retry_backoff = retry_backoff
        self.name = name or f"guardrail-{random_id()}"
        self.description = description
        self._history = []

    @trace(name="/guardrail_call")
    async def __call__(
        self,
        llm_output: str,
        input_data: Optional[Dict[str, Any]] = None,
        metadata: Optional[Dict[str, Any]] = None
    ) -> ValidationResult:
        """Execute the guardrail validation process.
        
        Args:
            llm_output: The output from the LLM to validate
            input_data: Optional input data needed for validation
            metadata: Optional metadata for the validation process
            
        Returns:
            ValidationResult containing validation outcomes and transformed output
        """
        # Apply transformations
        transformed_output = llm_output
        for transform in self.transformations:
            transformed_output = transform.apply(transformed_output)

        # Run validation metrics
        scores = {}
        reasons = {}
        passed = True
        
        for metric in self.metrics:
            score_result = await metric.score(
                output=transformed_output,
                **(input_data or {})
            )
            scores[metric.name] = score_result.value
            reasons[metric.name] = score_result.reason
            if not score_result.passed:
                passed = False

        # Create validation result
        result = ValidationResult(
            passed=passed,
            scores=scores,
            reasons=reasons,
            transformed_output=transformed_output,
            raw_output=llm_output,
            metadata=metadata or {}
        )

        # Execute hooks
        if passed and self.on_success:
            await self.on_success(result)
        elif not passed and self.on_failure:
            await self.on_failure(result)

        # Add to history
        self._history.append(result)

        return result

    async def _execute_with_retry(
        self,
        llm_output: str,
        input_data: Optional[Dict[str, Any]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        attempt: int = 0
    ) -> ValidationResult:
        """Execute validation with retry logic.
        
        Args:
            llm_output: The output from the LLM to validate
            input_data: Optional input data needed for validation
            metadata: Optional metadata for the validation process
            attempt: Current retry attempt number
            
        Returns:
            ValidationResult containing validation outcomes
            
        Raises:
            Exception: If all retry attempts fail
        """
        try:
            result = await self(llm_output, input_data, metadata)
            return result
        except Exception as e:
            if attempt < self.max_retries:
                delay = self.retry_delay * (self.retry_backoff ** attempt)
                LOGGER.info(f"Retry attempt {attempt + 1} after {delay}s delay")
                await asyncio.sleep(delay)
                return await self._execute_with_retry(
                    llm_output, input_data, metadata, attempt + 1
                )
            raise e

    @property
    def history(self) -> List[ValidationResult]:
        """Get the validation history.
        
        Returns:
            List of ValidationResult objects from previous validations
        """
        return self._history 