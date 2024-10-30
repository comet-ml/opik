import math
from functools import cached_property
from typing import Any, Optional, Union
import pydantic
import json

from litellm.types.utils import ModelResponse

from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from opik.logging_messages import GEVAL_SCORE_CALC_FAILED
from .template import G_EVAL_COT_TEMPLATE, G_EVAL_QUERY_TEMPLATE
from ... import exceptions


class GEvalScoreFormat(pydantic.BaseModel):
    score: int
    reason: str


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

    @cached_property
    def llm_chain_of_thought(self) -> str:
        prompt = G_EVAL_COT_TEMPLATE.format(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
        )
        return self._model.generate_string(input=prompt)

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            self._model = models_factory.get(
                model_name=model,
                must_support_arguments=["logprobs", "top_logprobs"],
            )

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

        request = [
            {
                "content": llm_query,
                "role": "user",
            },
        ]

        model_output = self._model.generate_provider_response(
            messages=request,
            logprobs=True,
            top_logprobs=20,
            response_format=GEvalScoreFormat,
        )

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

        request = [
            {
                "content": llm_query,
                "role": "user",
            },
        ]

        model_output = await self._model.agenerate_provider_response(
            messages=request,
            logprobs=True,
            top_logprobs=20,
            response_format=GEvalScoreFormat,
        )

        return self._parse_model_output(model_output)

    def _parse_model_output(self, content: ModelResponse) -> score_result.ScoreResult:
        """
        This method computes the final score based on the model's response. The model's response is a dictionary
        with a `score` key and a `reason` key. The prompt template also specifies that the score should be an integer
        between 0 and 10.

        In order to make the score computation more robust, we look at the top logprobs of the score token and compute
        a weighted average of the scores. Since we try to enforce the format of the model's response, we can assume that
        the score token is always the fourth token in the response (first token is `{"`, followed by `score` and `":`).
        """
        try:
            # Compute score using top logprobs
            score_token_position = 3
            top_score_logprobs = content.choices[0].model_extra["logprobs"]["content"][
                score_token_position
            ]["top_logprobs"]

            linear_probs_sum = 0.0
            weighted_score_sum = 0.0

            for token_info in top_score_logprobs:
                # if not a number
                if not token_info["token"].isdecimal():
                    continue

                score = int(token_info["token"])

                # if score value not in scale
                if not 0 <= score <= 10:
                    continue

                log_prob = token_info["logprob"]
                linear_prob = math.exp(log_prob)

                linear_probs_sum += linear_prob
                weighted_score_sum += linear_prob * score

            final_score: float = weighted_score_sum / linear_probs_sum / 10

            if not (0.0 <= final_score <= 1.0):
                raise ValueError

            # Get the reason
            reason = json.loads(content.choices[0].message.content)["reason"]

            # Return the score and the reason
            return score_result.ScoreResult(
                name=self.name, value=final_score, reason=reason
            )
        except Exception:
            raise exceptions.MetricComputationError(GEVAL_SCORE_CALC_FAILED)
