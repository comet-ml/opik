from typing import Union, Optional, List, Any
import logging

from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics import score_result, base_metric
from opik import exceptions
from . import template, parser
from .schema import (
    FewShotExampleStructuredOutputCompliance,
    StructuredOutputComplianceResponseFormat,
)

LOGGER = logging.getLogger(__name__)


class StructuredOutputCompliance(base_metric.BaseMetric):
    """
    Metric to evaluate whether an LLM's output complies with a specified structured format.
    This includes checking for valid JSON, JSON-LD compatibility, or adherence to a provided
    Pydantic/JSON schema.

    Score Range:
    - Minimum score: 0.0 (complete non-compliance)
    - Maximum score: 1.0 (complete compliance)

    Score Meaning:
    - 0.0: Output does not comply with the expected structure at all (e.g., invalid JSON, missing required fields)
    - 0.5: Partial compliance (e.g., valid JSON but missing some required fields)
    - 1.0: Complete compliance with the expected structure (valid JSON and all required fields present)

    Args:
        model: LLM to use for evaluation. Can be a string or an OpikBaseModel instance.
        name: Metric name.
        few_shot_examples: Optional few-shot examples to guide the LLM's judgment.
        track: Whether to track metric execution for observability.
        project_name: Optional name for tracking in an observability tool.
        seed: Optional seed value for reproducible model generation. If provided, this seed will be passed to the model for deterministic outputs.
        temperature: Optional temperature value for model generation. If provided, this temperature will be passed to the model. If not provided, the model's default temperature will be used.
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "structured_output_compliance",
        few_shot_examples: Optional[
            List[FewShotExampleStructuredOutputCompliance]
        ] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        seed: Optional[int] = None,
        temperature: Optional[float] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self._seed = seed
        self._init_model(model, temperature=temperature)
        self.few_shot_examples = few_shot_examples

    def _init_model(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]],
        temperature: Optional[float],
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            model_kwargs = {}
            if temperature is not None:
                model_kwargs["temperature"] = temperature
            if self._seed is not None:
                model_kwargs["seed"] = self._seed

            self._model = models_factory.get(model_name=model, **model_kwargs)

    def score(
        self,
        output: str,
        schema: Optional[str] = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Synchronously compute the structured output compliance score.
        Args:
            output: The LLM's output to validate.
            schema: Optional JSON or Pydantic schema to validate against.
        Returns:
            score_result.ScoreResult: An object containing the compliance score and reasons.
        """
        try:
            llm_query = template.generate_query(
                output=output,
                schema=schema,
                few_shot_examples=self.few_shot_examples,
            )

            model_output = self._model.generate_string(
                input=llm_query,
                response_format=StructuredOutputComplianceResponseFormat,
            )

            return parser.parse_model_output(content=model_output, name=self.name)

        except Exception as e:
            LOGGER.error(
                f"Structured output compliance evaluation failed: {e}", exc_info=True
            )
            raise exceptions.MetricComputationError(
                f"Structured output compliance evaluation failed: {str(e)}"
            ) from e

    async def ascore(
        self,
        output: str,
        schema: Optional[str] = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Asynchronously compute the structured output compliance score.
        Args:
            output: The LLM's output to validate.
            schema: Optional JSON or Pydantic schema to validate against.
        Returns:
            score_result.ScoreResult: An object containing the compliance score and reasons.
        """
        try:
            llm_query = template.generate_query(
                output=output,
                schema=schema,
                few_shot_examples=self.few_shot_examples,
            )

            model_output = await self._model.agenerate_string(
                input=llm_query,
                response_format=StructuredOutputComplianceResponseFormat,
            )

            return parser.parse_model_output(content=model_output, name=self.name)

        except Exception as e:
            LOGGER.error(
                f"Structured output compliance evaluation failed: {e}", exc_info=True
            )
            raise exceptions.MetricComputationError(
                f"Structured output compliance evaluation failed: {str(e)}"
            ) from e
