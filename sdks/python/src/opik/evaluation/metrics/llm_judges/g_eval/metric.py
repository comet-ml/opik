from typing import Any, Optional, Union
import pydantic

from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from opik.evaluation import models
from . import template, parser


class GEvalScoreFormat(pydantic.BaseModel):
    score: int
    reason: str


class GEval(base_metric.BaseMetric):
    def __init__(
        self,
        task_introduction: str,
        evaluation_criteria: str,
        model: Optional[Union[str, models.base_model.OpikBaseModel]] = None,
        name: str = "g_eval_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
        seed: Optional[int] = None,
    ):
        """
        A metric that evaluates an LLM output based on chain-of-thought built with the evaluation criteria provided
        by the user.

        For more details see the original paper: https://arxiv.org/pdf/2303.16634

        Args:
            task_introduction: An instruction for LLM used to generate an evaluation chain-of-thought and in evaluation call itself.
                `opik.evaluation.models.LiteLLMChatModel` is used by default.
            evaluation_criteria: The main task for G-Eval metric written in human language.
            model: The LLM to use for evaluation. Can be a string (model name) or an `opik.evaluation.models.OpikBaseModel` subclass instance.
            name: The name of the metric.
            track: Whether to track the metric. Defaults to True.
            project_name: Optional project name to track the metric in for the cases when
                there is no parent span/trace to inherit project name from.
            temperature: The temperature to use for the model. Defaults to 0.0.
            seed: Optional seed value for reproducible model generation. If provided, this seed will be passed to the model for deterministic outputs.
        """
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )
        self.task_introduction = task_introduction
        self.evaluation_criteria = evaluation_criteria
        self._seed = seed

        self._log_probs_supported = False
        self._chain_of_thought_response: Optional[str] = None

        self._init_model(model, temperature=temperature)

    def llm_chain_of_thought(self) -> str:
        if self._chain_of_thought_response is None:
            prompt = template.G_EVAL_COT_TEMPLATE.format(
                task_introduction=self.task_introduction,
                evaluation_criteria=self.evaluation_criteria,
            )
            self._chain_of_thought_response = self._model.generate_string(input=prompt)

        return self._chain_of_thought_response

    async def allm_chain_of_thought(self) -> str:
        if self._chain_of_thought_response is None:
            prompt = template.G_EVAL_COT_TEMPLATE.format(
                task_introduction=self.task_introduction,
                evaluation_criteria=self.evaluation_criteria,
            )
            self._chain_of_thought_response = await self._model.agenerate_string(
                input=prompt
            )

        return self._chain_of_thought_response

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]], temperature: float
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            model_kwargs = {"temperature": temperature}
            if self._seed is not None:
                model_kwargs["seed"] = self._seed

            self._model = models_factory.get(model_name=model, **model_kwargs)

        if (
            hasattr(self._model, "supported_params")
            and "logprobs" in self._model.supported_params
            and "top_logprobs" in self._model.supported_params
        ):
            self._log_probs_supported = True

    def score(
        self,
        output: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Calculate the G-Eval score for the given LLM's output.

        Args:
            output: The LLM's output to evaluate.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the G-Eval score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.G_EVAL_QUERY_TEMPLATE.format(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
            chain_of_thought=self.llm_chain_of_thought(),
            input=output,
        )

        request = [
            {
                "content": llm_query,
                "role": "user",
            },
        ]

        if isinstance(self._model, models.LiteLLMChatModel):
            with base_model.get_provider_response(
                model_provider=self._model,
                messages=request,
                logprobs=self._log_probs_supported,
                top_logprobs=20 if self._log_probs_supported else None,
                response_format=GEvalScoreFormat,
            ) as model_output:
                return parser.parse_litellm_model_output(
                    content=model_output,
                    name=self.name,
                    log_probs_supported=self._log_probs_supported,
                )

        model_output_string = self._model.generate_string(
            input=llm_query, response_format=GEvalScoreFormat
        )

        return parser.parse_model_output_string(model_output_string, self.name)

    async def ascore(
        self, output: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Calculate the G-Eval score for the given LLM's output.

        Args:
            output: The LLM's output to evaluate.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            score_result.ScoreResult: A ScoreResult object containing the G-Eval score
            (between 0.0 and 1.0) and a reason for the score.
        """
        llm_query = template.G_EVAL_QUERY_TEMPLATE.format(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
            chain_of_thought=await self.allm_chain_of_thought(),
            input=output,
        )

        request = [
            {
                "content": llm_query,
                "role": "user",
            },
        ]

        if isinstance(self._model, models.LiteLLMChatModel):
            async with base_model.aget_provider_response(
                model_provider=self._model,
                messages=request,
                logprobs=self._log_probs_supported,
                top_logprobs=20 if self._log_probs_supported else None,
                response_format=GEvalScoreFormat,
            ) as model_output:
                return parser.parse_litellm_model_output(
                    content=model_output,
                    name=self.name,
                    log_probs_supported=self._log_probs_supported,
                )

        model_output_string = await self._model.agenerate_string(
            input=llm_query, response_format=GEvalScoreFormat
        )

        return parser.parse_model_output_string(model_output_string, self.name)
