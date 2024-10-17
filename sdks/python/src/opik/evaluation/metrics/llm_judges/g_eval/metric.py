from typing import Any, Optional, Union

from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from .template import G_EVAL_COT_TEMPLATE, G_EVAL_QUERY_TEMPLATE
from ... import exceptions


class GEval(base_metric.BaseMetric):
    def __init__(
        self,
        task_introduction: str,
        evaluation_criteria: str,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "g_eval_metric",
    ):
        super().__init__(
            name=name,
        )
        self._init_model(model)

        self.task_introduction = task_introduction
        self.evaluation_criteria = evaluation_criteria

        prompt = G_EVAL_COT_TEMPLATE.format(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
        )

        self.llm_chain_of_thought: str = self._model.generate(input=prompt)

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(model_name=model)

    def score(
        self,
        input: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        llm_query = G_EVAL_QUERY_TEMPLATE.format(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
            chain_of_thought=self.llm_chain_of_thought,
            input=input,
        )
        model_output = self._model.generate(input=llm_query)

        return self._parse_model_output(model_output)

    async def ascore(
        self, input: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        llm_query = G_EVAL_QUERY_TEMPLATE.format(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
            chain_of_thought=self.llm_chain_of_thought,
            input=input,
        )
        model_output = self._model.generate(input=llm_query)

        return self._parse_model_output(model_output)

    def _parse_model_output(self, content: str) -> score_result.ScoreResult:
        try:
            score: float = float(content)

            if score > 1.0:
                score /= 10

            if not (0.0 <= score <= 1.0):
                raise ValueError(
                    f"Unable to compute the score as the current value is {score}"
                )

            return score_result.ScoreResult(name=self.name, value=score)
        except Exception as e:
            raise exceptions.MetricComputationError(str(e))
