"""RevisEval grounded judge implementation."""

from __future__ import annotations

from typing import Any, List, Optional, Union

import pydantic

from opik.evaluation.metrics.base_metric import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from opik.evaluation.metrics.llm_judges.reviseval import templates, parser
from opik.evaluation.models import base_model, models_factory


class RevisEvalResponseFormat(pydantic.BaseModel):
    score: float
    reason: str


class RevisEvalJudge(BaseMetric):
    """
    LLM judge that revises answers using grounded evidence (RevisEval).

    Args:
        model: Optional model identifier or ``OpikBaseModel`` instance. When ``None``
            the default judge model is fetched from ``models_factory``.
        name: Display name for the metric result. Defaults to ``"reviseval_judge"``.
        track: Whether to automatically track judge outputs. Defaults to ``True``.
        project_name: Optional tracking project name.

    Example:
        >>> from opik.evaluation.metrics import RevisEvalJudge
        >>> judge = RevisEvalJudge(model="gpt-4")
        >>> result = judge.score(
        ...     question="What is the capital of France?",
        ...     answer="It's Berlin.",
        ...     context=["France's capital city is Paris."],
        ... )
        >>> result.value  # doctest: +SKIP
        0.0
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "reviseval_judge",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)
        self._init_model(model)

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]]
    ) -> None:
        if model is None or isinstance(model, str):
            self._model = models_factory.get(model_name=model)
            return

        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
            return

        if not hasattr(model, "generate_string"):
            raise ValueError("Provided model must expose a 'generate_string' method.")

        self._model = model  # type: ignore[assignment]

    def score(
        self,
        question: str,
        answer: str,
        context: Optional[List[str]] = None,
        **ignored_kwargs: Any,
    ) -> ScoreResult:
        prompt = templates.build_prompt(
            question=question, answer=answer, context=context
        )
        model_output = self._model.generate_string(
            input=prompt,
            response_format=RevisEvalResponseFormat,
        )
        score, reason = parser.parse_model_output(model_output)
        return ScoreResult(value=score, name=self.name, reason=reason)

    async def ascore(
        self,
        question: str,
        answer: str,
        context: Optional[List[str]] = None,
        **ignored_kwargs: Any,
    ) -> ScoreResult:
        prompt = templates.build_prompt(
            question=question, answer=answer, context=context
        )
        model_output = await self._model.agenerate_string(
            input=prompt,
            response_format=RevisEvalResponseFormat,
        )
        score, reason = parser.parse_model_output(model_output)
        return ScoreResult(value=score, name=self.name, reason=reason)
