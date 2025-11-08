from collections import OrderedDict
from threading import Lock
from typing import Any, Dict, Optional, Tuple, Union
import pydantic

from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from opik.evaluation import models
from . import template, parser
from .presets import GEVAL_PRESETS


class GEvalScoreFormat(pydantic.BaseModel):
    score: int
    reason: str


def _freeze_for_cache(value: Any) -> Any:
    """Convert nested structures into hashable representations for caching."""

    if isinstance(value, dict):
        return tuple(
            sorted((key, _freeze_for_cache(val)) for key, val in value.items())
        )
    if isinstance(value, (list, tuple)):
        return tuple(_freeze_for_cache(item) for item in value)
    if isinstance(value, set):
        return tuple(sorted(_freeze_for_cache(item) for item in value))
    return value


class GEval(base_metric.BaseMetric):
    """
    Generalised evaluation metric that prompts an LLM to grade another LLM output.

    GEval builds a reusable chain-of-thought using the provided
    ``task_introduction`` and ``evaluation_criteria`` prompts, then requests a
    final score and rationale for each evaluated output.

    Args:
        task_introduction: Instruction describing the evaluator's persona/purpose.
        evaluation_criteria: Detailed rubric presented to the evaluator.
        model: Optional model identifier or ``OpikBaseModel`` for the judge.
        name: Display name for the metric result. Defaults to ``"g_eval_metric"``.
        track: Whether to automatically track metric results. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the judge model.
        seed: Optional seed for reproducible generation (if supported by the model).

    Example:
        >>> from opik.evaluation.metrics.llm_judges.g_eval.metric import GEval
        >>> metric = GEval(
        ...     task_introduction="You evaluate politeness of responses.",
        ...     evaluation_criteria="Score from 1 (rude) to 5 (very polite).",
        ...     model="gpt-4",
        ... )
        >>> result = metric.score(output="Thanks so much for your help!")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.9
    """

    _CHAIN_OF_THOUGHT_CACHE: "OrderedDict[Tuple[str, str, str, Any], str]" = (
        OrderedDict()
    )
    _CHAIN_OF_THOUGHT_LOCK: Lock = Lock()
    _MAX_CHAIN_OF_THOUGHT_CACHE = 128

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
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )
        self.task_introduction = task_introduction
        self.evaluation_criteria = evaluation_criteria
        self._seed = seed

        self._log_probs_supported = False

        self._init_model(model, temperature=temperature)

    def llm_chain_of_thought(self) -> str:
        cache_key = self._chain_of_thought_cache_key()
        cached = self._get_cached_chain_of_thought(cache_key)
        if cached is not None:
            return cached

        prompt = template.G_EVAL_COT_TEMPLATE.format(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
        )
        generated = self._model.generate_string(input=prompt)
        self._store_chain_of_thought(cache_key, generated)
        return generated

    async def allm_chain_of_thought(self) -> str:
        cache_key = self._chain_of_thought_cache_key()
        cached = self._get_cached_chain_of_thought(cache_key)
        if cached is not None:
            return cached

        prompt = template.G_EVAL_COT_TEMPLATE.format(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
        )
        generated = await self._model.agenerate_string(input=prompt)
        self._store_chain_of_thought(cache_key, generated)
        return generated

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

    @classmethod
    def _get_cached_chain_of_thought(
        cls, cache_key: Tuple[str, str, str, Any]
    ) -> Optional[str]:
        with cls._CHAIN_OF_THOUGHT_LOCK:
            value = cls._CHAIN_OF_THOUGHT_CACHE.get(cache_key)
            if value is not None:
                cls._CHAIN_OF_THOUGHT_CACHE.move_to_end(cache_key)
            return value

    @classmethod
    def _store_chain_of_thought(
        cls, cache_key: Tuple[str, str, str, Any], value: str
    ) -> None:
        with cls._CHAIN_OF_THOUGHT_LOCK:
            existing = cls._CHAIN_OF_THOUGHT_CACHE.get(cache_key)
            if existing is not None:
                cls._CHAIN_OF_THOUGHT_CACHE.move_to_end(cache_key)
                return
            cls._CHAIN_OF_THOUGHT_CACHE[cache_key] = value
            cls._CHAIN_OF_THOUGHT_CACHE.move_to_end(cache_key)
            while len(cls._CHAIN_OF_THOUGHT_CACHE) > cls._MAX_CHAIN_OF_THOUGHT_CACHE:
                cls._CHAIN_OF_THOUGHT_CACHE.popitem(last=False)

    def _chain_of_thought_cache_key(self) -> Tuple[str, str, str, Any]:
        model_name = getattr(self._model, "model_name", "unknown")
        return (
            self.task_introduction,
            self.evaluation_criteria,
            model_name,
            self._model_cache_fingerprint(),
        )

    def _model_cache_fingerprint(self) -> Any:
        fingerprint_candidate = getattr(self._model, "cache_fingerprint", None)
        if callable(fingerprint_candidate):
            try:
                fingerprint = fingerprint_candidate()
            except Exception:
                fingerprint = None
            else:
                return _freeze_for_cache(fingerprint)

        completion_kwargs = getattr(self._model, "_completion_kwargs", None)
        if isinstance(completion_kwargs, dict):
            return _freeze_for_cache(completion_kwargs)

        return id(self._model)

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
            provider_kwargs: Dict[str, Any] = {
                "response_format": GEvalScoreFormat,
            }
            if self._log_probs_supported:
                provider_kwargs["logprobs"] = True
                provider_kwargs["top_logprobs"] = 20

            with base_model.get_provider_response(
                model_provider=self._model,
                messages=request,
                **provider_kwargs,
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
        self,
        output: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Async variant of :meth:`score`, evaluating the provided LLM output using
        the configured judge model and returning a ``ScoreResult``.
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
            provider_kwargs: Dict[str, Any] = {
                "response_format": GEvalScoreFormat,
            }
            if self._log_probs_supported:
                provider_kwargs["logprobs"] = True
                provider_kwargs["top_logprobs"] = 20

            async with base_model.aget_provider_response(
                model_provider=self._model,
                messages=request,
                **provider_kwargs,
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


class GEvalPreset(GEval):
    """
    Pre-configured GEval variant with author-provided prompt templates.

    Args:
        preset: Key name from ``GEVAL_PRESETS`` describing the evaluation rubric.
        model: Optional model identifier or ``OpikBaseModel`` instance.
        track: Whether to automatically track metric results. Defaults to ``True``.
        project_name: Optional tracking project name.
        temperature: Sampling temperature forwarded to the judge model.
        name: Optional override for the metric name (defaults to preset name).

    Example:
        >>> from opik.evaluation.metrics.llm_judges.g_eval.metric import GEvalPreset
        >>> metric = GEvalPreset(preset="qa_relevance", model="gpt-4")
        >>> result = metric.score(output="Answer addresses the user's question.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.85
    """

    def __init__(
        self,
        preset: str,
        model: Optional[Union[str, models.base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
        name: Optional[str] = None,
    ):
        try:
            definition = GEVAL_PRESETS[preset]
        except KeyError as error:
            raise ValueError(
                f"Unknown GEval preset '{preset}'. Available presets: {list(GEVAL_PRESETS)}"
            ) from error

        super().__init__(
            task_introduction=definition.task_introduction,
            evaluation_criteria=definition.evaluation_criteria,
            model=model,
            name=name or definition.name,
            track=track,
            project_name=project_name,
            temperature=temperature,
        )
