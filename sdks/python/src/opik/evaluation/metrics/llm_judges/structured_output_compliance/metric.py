import json
from typing import Any, List, Optional, Union

from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from pydantic import BaseModel

from . import parser, template


class StructuredOutputComplianceResponseFormat(BaseModel):
    score: float
    reason: List[str]


class StructuredOutputCompliance(base_metric.BaseMetric):
    """
    Check if the output is a valid JSON and/or JSON-LD compatible.
    Ideal solution would have Pydantic schema support.
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "structured_output_compliance_metric",
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self._init_model(model)

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def score(  # type: ignore
        self,
        output: str,
        pydantic_schema: Optional[Any] = None,
    ) -> score_result.ScoreResult:
        """
        Scores the structured output compliance of the output.
        :param output: The output to score.
        :param pydantic_schema: The pydantic schema to validate against.
        """
        json_schema_str = "No schema provided. Validate for JSON format only."
        if (
            pydantic_schema
            and isinstance(pydantic_schema, type)
            and issubclass(pydantic_schema, BaseModel)
        ):
            json_schema_str = json.dumps(pydantic_schema.model_json_schema(), indent=2)

        llm_query = template.generate_query(
            output=output,
            json_schema=json_schema_str,
        )

        model_output = self._model.generate_string(
            input=llm_query, response_format=StructuredOutputComplianceResponseFormat
        )

        return parser.parse_model_output(content=model_output, name=self.name)

    async def ascore(  # type: ignore
        self,
        output: str,
        pydantic_schema: Optional[Any] = None,
    ) -> score_result.ScoreResult:
        """
        Scores the structured output compliance of the output asynchronously.
        :param output: The output to score.
        :param pydantic_schema: The pydantic schema to validate against.
        """
        json_schema_str = "No schema provided. Validate for JSON format only."
        if (
            pydantic_schema
            and isinstance(pydantic_schema, type)
            and issubclass(pydantic_schema, BaseModel)
        ):
            json_schema_str = json.dumps(pydantic_schema.model_json_schema(), indent=2)

        llm_query = template.generate_query(
            output=output,
            json_schema=json_schema_str,
        )

        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=StructuredOutputComplianceResponseFormat
        )

        return parser.parse_model_output(content=model_output, name=self.name)