from __future__ import annotations

from typing import Any, cast
from collections.abc import Callable, Sequence

import copy
import inspect
import logging
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass
import random
import importlib.metadata


import litellm
from opik.rest_api.core import ApiError
from opik.api_objects import optimization
from opik import Dataset, opik_context
from pydantic import BaseModel

from . import _throttle, optimization_result
from .optimization_config import chat_prompt, mappers
from .optimizable_agent import OptimizableAgent
from .utils import DatasetSplitResult, ValidationSplit, create_litellm_agent_class
from . import task_evaluator

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

# Don't use unsupported params:
litellm.drop_params = True

# Set up logging:
logger = logging.getLogger(__name__)


try:
    _OPTIMIZER_VERSION = importlib.metadata.version("opik_optimizer")
except importlib.metadata.PackageNotFoundError:  # pragma: no cover - dev installs
    _OPTIMIZER_VERSION = "unknown"


class OptimizationRound(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    round_number: int
    current_prompt: chat_prompt.ChatPrompt
    current_score: float
    generated_prompts: Any
    best_prompt: chat_prompt.ChatPrompt
    best_score: float
    improvement: float


class BaseOptimizer(ABC):
    def __init__(
        self,
        model: str,
        verbose: int = 1,
        seed: int = 42,
        model_parameters: dict[str, Any] | None = None,
    ) -> None:
        """
        Base class for optimizers.

        Args:
           model: LiteLLM model name for optimizer's internal reasoning/generation calls
           verbose: Controls internal logging/progress bars (0=off, 1=on)
           seed: Random seed for reproducibility
           model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
               Common params: temperature, max_tokens, max_completion_tokens, top_p,
               presence_penalty, frequency_penalty.
               See: https://docs.litellm.ai/docs/completion/input
               Note: These params control the optimizer's reasoning model, NOT the prompt evaluation.
        """
        self.model = model
        self.reasoning_model = model
        self.model_parameters = model_parameters or {}
        self.verbose = verbose
        self.seed = seed
        self._history: list[OptimizationRound] = []
        self.experiment_config = None
        self.llm_call_counter = 0
        self.tool_call_counter = 0
        self._opik_client = None  # Lazy initialization
        self.current_optimization_id: str | None = None  # Track current optimization
        self.project_name: str = "Optimization"  # Default project name

    def _reset_counters(self) -> None:
        """Reset all call counters for a new optimization run."""
        self.llm_call_counter = 0
        self.tool_call_counter = 0

    def _increment_llm_counter(self) -> None:
        """Increment the LLM call counter."""
        self.llm_call_counter += 1

    def _increment_tool_counter(self) -> None:
        """Increment the tool call counter."""
        self.tool_call_counter += 1

    def cleanup(self) -> None:
        """
        Clean up resources and perform memory management.
        Should be called when the optimizer is no longer needed.
        """
        # Reset counters
        self._reset_counters()

        # Clear history to free memory
        self._history.clear()

        # Clear Opik client if it exists
        if self._opik_client is not None:
            # Note: Opik client doesn't have explicit cleanup, but we can clear the reference
            self._opik_client = None

        logger.debug(f"Cleaned up resources for {self.__class__.__name__}")

    def __del__(self) -> None:
        """Destructor to ensure cleanup is called."""
        try:
            self.cleanup()
        except Exception:
            # Ignore exceptions during cleanup in destructor
            pass

    @property
    def opik_client(self) -> Any:
        """Lazy initialization of Opik client."""
        if self._opik_client is None:
            import opik

            self._opik_client = opik.Opik()
        return self._opik_client

    def _validate_optimization_inputs(
        self, prompt: chat_prompt.ChatPrompt, dataset: Dataset, metric: Callable
    ) -> None:
        """
        Validate common optimization inputs.

        Args:
            prompt: The chat prompt to validate
            dataset: The dataset to validate
            metric: The metric function to validate

        Raises:
            ValueError: If any input is invalid
        """
        if not isinstance(prompt, chat_prompt.ChatPrompt):
            raise ValueError("Prompt must be a ChatPrompt object")

        if not isinstance(dataset, Dataset):
            raise ValueError("Dataset must be a Dataset object")

        if not callable(metric):
            raise ValueError(
                "Metric must be a function that takes `dataset_item` and `llm_output` as arguments."
            )

    def _setup_agent_class(
        self, prompt: chat_prompt.ChatPrompt, agent_class: Any = None
    ) -> Any:
        """
        Setup agent class for optimization.

        Args:
            prompt: The chat prompt
            agent_class: Optional custom agent class

        Returns:
            The agent class to use
        """
        if agent_class is None:
            return create_litellm_agent_class(prompt, optimizer_ref=self)
        else:
            return agent_class

    def _prepare_dataset_split(
        self,
        dataset: Dataset,
        *,
        n_samples: int | None = None,
        validation: ValidationSplit | None = None,
    ) -> DatasetSplitResult:
        """
        Combine training and optional validation splits for downstream optimizers.

        Args:
            dataset: Primary dataset used for optimization.
            n_samples: Optional limit applied when sampling examples for the run.
            validation: Optional :class:`ValidationSplit` describing how to build
                a validation subset.

        Returns:
            Tuple containing train item dictionaries and validation item dictionaries.
        """
        if validation is None or not validation.is_configured():
            items = list(dataset.get_items())
            if n_samples is not None and n_samples < len(items):
                rng = random.Random(self.seed)
                items = rng.sample(items, n_samples)
            return DatasetSplitResult(dataset, None, items, [])

        split = validation.build(
            dataset,
            n_samples=n_samples,
            default_seed=self.seed,
        )
        if n_samples is not None and n_samples < len(split.train_items):
            rng = random.Random(self.seed)
            sampled_train = rng.sample(split.train_items, n_samples)
            split = DatasetSplitResult(
                split.train_dataset,
                split.validation_dataset,
                sampled_train,
                split.validation_items,
            )
        return split

    def _extract_tool_prompts(
        self, tools: list[dict[str, Any]] | None
    ) -> dict[str, str] | None:
        """
        Extract tool names and descriptions from tools list.

        Args:
            tools: List of tool definitions in OpenAI/LiteLLM format

        Returns:
            Dictionary mapping tool names to descriptions, or None if no tools
        """
        if not tools:
            return None

        return {
            (tool.get("function", {}).get("name") or f"tool_{idx}"): tool.get(
                "function", {}
            ).get("description", "")
            for idx, tool in enumerate(tools)
        }

    def _pop_validation_split(self, kwargs: dict[str, Any]) -> ValidationSplit | None:
        """Extract a validation split from keyword arguments, enforcing type checks."""
        if "validation" not in kwargs:
            return None

        candidate = kwargs.pop("validation")
        if candidate is None:
            return None
        if not isinstance(candidate, ValidationSplit):
            raise TypeError("validation must be a ValidationSplit or None")
        return candidate

    def _build_eval_spec(
        self, dataset: Dataset, ids: list[str], n_samples: int | None
    ) -> EvaluationSpec:
        if ids:
            rng = random.Random(self.seed)
            population = list(ids)
            if n_samples is not None and 0 < n_samples < len(population):
                sampled_ids = tuple(rng.sample(population, n_samples))
            else:
                sampled_ids = tuple(population)
            return EvaluationSpec(dataset, sampled_ids, None)
        return EvaluationSpec(dataset, None, n_samples)

    def _build_evaluation_plan(
        self, split: DatasetSplitResult, n_samples: int | None
    ) -> EvaluationPlan:
        train_spec = self._build_eval_spec(
            split.train_dataset, split.train_ids(), n_samples
        )
        validation_spec: EvaluationSpec | None = None
        if split.validation_items:
            validation_dataset = split.validation_dataset or split.train_dataset
            validation_spec = self._build_eval_spec(
                validation_dataset, split.validation_ids(), None
            )
        return EvaluationPlan(split=split, train=train_spec, validation=validation_spec)

    def _evaluate_with_spec(
        self,
        prompt: chat_prompt.ChatPrompt,
        metric: Callable,
        spec: EvaluationSpec,
        **kwargs: Any,
    ) -> Any:
        payload = {**spec.kwargs(), **kwargs}
        return self._evaluate_prompt(
            prompt,
            dataset=spec.dataset,
            metric=metric,
            **payload,
        )

    def _select_items_for_spec(
        self,
        spec: EvaluationSpec,
        items: Sequence[dict[str, Any]],
    ) -> SpecSelection:
        available = list(items)
        if spec.item_ids is not None:
            lookup = {
                item.get("id"): item
                for item in available
                if isinstance(item, dict) and item.get("id")
            }
            ordered_items: list[dict[str, Any]] = []
            resolved_ids: list[str] = []
            for item_id in spec.item_ids:
                candidate = lookup.get(item_id)
                if candidate is not None:
                    ordered_items.append(candidate)
                    resolved_ids.append(item_id)
            return SpecSelection(
                items=ordered_items,
                dataset_item_ids=resolved_ids if resolved_ids else None,
                sample_count=len(resolved_ids),
            )

        if not available:
            return SpecSelection([], None, spec.sample_count)

        if spec.sample_count is not None and spec.sample_count < len(available):
            rng = random.Random(self.seed)
            chosen = rng.sample(available, spec.sample_count)
        else:
            chosen = available

        resolved_ids = [
            item["id"] for item in chosen if isinstance(item, dict) and item.get("id")
        ]
        effective_count = len(resolved_ids) if resolved_ids else spec.sample_count
        return SpecSelection(
            items=chosen,
            dataset_item_ids=resolved_ids if resolved_ids else None,
            sample_count=effective_count,
        )

    def _evaluate_prompt(self, *args: Any, **kwargs: Any) -> Any:  # pragma: no cover
        """Subclasses must implement evaluation logic used by `_evaluate_with_spec`."""
        raise NotImplementedError(
            f"{self.__class__.__name__} must implement _evaluate_prompt()"
        )

    # ------------------------------------------------------------------
    # LLM call methods
    # ------------------------------------------------------------------

    def _prepare_model_params(
        self,
        call_time_params: dict[str, Any],
        response_model: type[BaseModel] | None = None,
        is_reasoning: bool = False,
    ) -> dict[str, Any]:
        """
        Prepare parameters for LiteLLM call by merging and adding monitoring.

        Args:
            call_time_params: Dict of LiteLLM params from call-time overrides
            response_model: Optional Pydantic model for structured output
            is_reasoning: Flag for metadata tagging

        Returns:
            Dictionary ready for litellm.completion/acompletion
        """
        from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

        # Merge optimizer's model_parameters with call-time overrides
        merged_params = {**self.model_parameters, **call_time_params}

        # Add Opik monitoring wrapper
        final_params = opik_litellm_monitor.try_add_opik_monitoring_to_params(
            merged_params
        )

        # Add reasoning metadata if applicable
        if is_reasoning and "metadata" in final_params:
            if "opik_call_type" not in final_params["metadata"]:
                final_params["metadata"]["opik_call_type"] = "reasoning"

        # Configure project_name and tags for Opik tracing
        if "metadata" not in final_params:
            final_params["metadata"] = {}
        if "opik" not in final_params["metadata"]:
            final_params["metadata"]["opik"] = {}

        # Set project name for optimizer reasoning calls
        final_params["metadata"]["opik"]["project_name"] = self.project_name

        # Add tags if optimization_id is available
        if self.current_optimization_id:
            final_params["metadata"]["opik"]["tags"] = [
                self.current_optimization_id,
                "Prompt Optimization",
            ]

        # Add structured output support
        if response_model is not None:
            final_params["response_format"] = response_model

        return final_params

    def _parse_response(
        self,
        response: Any,
        response_model: type[BaseModel] | None = None,
    ) -> BaseModel | str:
        """
        Parse LiteLLM response, with optional structured output parsing.

        Args:
            response: The response from litellm.completion/acompletion
            response_model: Optional Pydantic model for structured output

        Returns:
            If response_model is provided, returns an instance of that model.
            Otherwise, returns the raw string response.
        """
        content = response.choices[0].message.content

        # When using structured outputs with Pydantic models, LiteLLM automatically
        # parses the response. Parse the JSON string into the Pydantic model
        if response_model is not None:
            return response_model.model_validate_json(content)

        return content

    def _build_call_time_params(
        self,
        temperature: float | None = None,
        max_tokens: int | None = None,
        max_completion_tokens: int | None = None,
        top_p: float | None = None,
        presence_penalty: float | None = None,
        frequency_penalty: float | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """
        Build dictionary of call-time LiteLLM parameter overrides.

        Args:
            temperature: Sampling temperature (0-2)
            max_tokens: Maximum tokens to generate
            max_completion_tokens: Upper bound for generated tokens
            top_p: Nucleus sampling probability mass
            presence_penalty: Penalty for new tokens based on presence
            frequency_penalty: Penalty for new tokens based on frequency
            metadata: Optional metadata dict for monitoring

        Returns:
            Dictionary of non-None parameters for LiteLLM
        """
        call_time_params: dict[str, Any] = {}
        if temperature is not None:
            call_time_params["temperature"] = temperature
        if max_tokens is not None:
            call_time_params["max_tokens"] = max_tokens
        if max_completion_tokens is not None:
            call_time_params["max_completion_tokens"] = max_completion_tokens
        if top_p is not None:
            call_time_params["top_p"] = top_p
        if presence_penalty is not None:
            call_time_params["presence_penalty"] = presence_penalty
        if frequency_penalty is not None:
            call_time_params["frequency_penalty"] = frequency_penalty
        if metadata is not None:
            call_time_params["metadata"] = metadata
        return call_time_params

    @_throttle.rate_limited(_limiter)
    def _call_model(
        self,
        messages: list[dict[str, str]],
        model: str | None = None,
        seed: int | None = None,
        response_model: type[BaseModel] | None = None,
        is_reasoning: bool = False,
        # Explicit call-time overrides for LiteLLM params
        temperature: float | None = None,
        max_tokens: int | None = None,
        max_completion_tokens: int | None = None,
        top_p: float | None = None,
        presence_penalty: float | None = None,
        frequency_penalty: float | None = None,
        # Optimizer-specific metadata (not passed to LiteLLM)
        optimization_id: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> BaseModel | str:
        """
        Call the LLM model with optional structured output.

        Args:
            messages: List of message dictionaries with 'role' and 'content' keys
            model: The model to use (defaults to self.model)
            seed: Random seed for reproducibility (defaults to self.seed)
            response_model: Optional Pydantic model for structured output
            is_reasoning: Flag for metadata tagging (not passed to LiteLLM)
            temperature: Sampling temperature (0-2)
            max_tokens: Maximum tokens to generate
            max_completion_tokens: Upper bound for generated tokens
            top_p: Nucleus sampling probability mass
            presence_penalty: Penalty for new tokens based on presence
            frequency_penalty: Penalty for new tokens based on frequency
            optimization_id: Optional ID for optimization tracking (metadata only)
            metadata: Optional metadata dict for monitoring

        Returns:
            If response_model is provided, returns an instance of that model.
            Otherwise, returns the raw string response.
        """
        self._increment_llm_counter()

        # Build dict of call-time LiteLLM parameter overrides (non-None only)
        call_time_params = self._build_call_time_params(
            temperature=temperature,
            max_tokens=max_tokens,
            max_completion_tokens=max_completion_tokens,
            top_p=top_p,
            presence_penalty=presence_penalty,
            frequency_penalty=frequency_penalty,
            metadata=metadata,
        )

        final_params_for_litellm = self._prepare_model_params(
            call_time_params, response_model, is_reasoning
        )

        response = litellm.completion(
            model=model or self.model,
            messages=messages,
            seed=seed if seed is not None else self.seed,
            num_retries=6,
            **final_params_for_litellm,
        )

        return self._parse_response(response, response_model)

    @_throttle.rate_limited(_limiter)
    async def _call_model_async(
        self,
        messages: list[dict[str, str]],
        model: str | None = None,
        seed: int | None = None,
        response_model: type[BaseModel] | None = None,
        is_reasoning: bool = False,
        # Explicit call-time overrides for LiteLLM params
        temperature: float | None = None,
        max_tokens: int | None = None,
        max_completion_tokens: int | None = None,
        top_p: float | None = None,
        presence_penalty: float | None = None,
        frequency_penalty: float | None = None,
        # Optimizer-specific metadata (not passed to LiteLLM)
        optimization_id: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> BaseModel | str:
        """
        Async version of _call_model using litellm.acompletion.

        Args:
            messages: List of message dictionaries with 'role' and 'content' keys
            model: The model to use (defaults to self.model)
            seed: Random seed for reproducibility (defaults to self.seed)
            response_model: Optional Pydantic model for structured output
            is_reasoning: Flag for metadata tagging (not passed to LiteLLM)
            temperature: Sampling temperature (0-2)
            max_tokens: Maximum tokens to generate
            max_completion_tokens: Upper bound for generated tokens
            top_p: Nucleus sampling probability mass
            presence_penalty: Penalty for new tokens based on presence
            frequency_penalty: Penalty for new tokens based on frequency
            optimization_id: Optional ID for optimization tracking (metadata only)
            metadata: Optional metadata dict for monitoring

        Returns:
            If response_model is provided, returns an instance of that model.
            Otherwise, returns the raw string response.
        """
        self._increment_llm_counter()

        # Build dict of call-time LiteLLM parameter overrides (non-None only)
        call_time_params = self._build_call_time_params(
            temperature=temperature,
            max_tokens=max_tokens,
            max_completion_tokens=max_completion_tokens,
            top_p=top_p,
            presence_penalty=presence_penalty,
            frequency_penalty=frequency_penalty,
            metadata=metadata,
        )

        final_params_for_litellm = self._prepare_model_params(
            call_time_params, response_model, is_reasoning
        )

        response = await litellm.acompletion(
            model=model or self.model,
            messages=messages,
            seed=seed if seed is not None else self.seed,
            num_retries=6,
            **final_params_for_litellm,
        )

        return self._parse_response(response, response_model)

    # ------------------------------------------------------------------
    # Experiment metadata helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _drop_none(metadata: dict[str, Any]) -> dict[str, Any]:
        return {k: v for k, v in metadata.items() if v is not None}

    @staticmethod
    def _deep_merge_dicts(
        base: dict[str, Any], overrides: dict[str, Any]
    ) -> dict[str, Any]:
        result = copy.deepcopy(base)
        for key, value in overrides.items():
            if (
                key in result
                and isinstance(result[key], dict)
                and isinstance(value, dict)
            ):
                result[key] = BaseOptimizer._deep_merge_dicts(result[key], value)
            else:
                result[key] = value
        return result

    @staticmethod
    def _serialize_tools(prompt: chat_prompt.ChatPrompt) -> list[dict[str, Any]]:
        tools_obj = getattr(prompt, "tools", None)
        if not isinstance(tools_obj, list):
            return []

        try:
            return copy.deepcopy(cast(list[dict[str, Any]], tools_obj))
        except Exception:  # pragma: no cover - defensive
            serialized_tools: list[dict[str, Any]] = []
            for tool in tools_obj:
                if isinstance(tool, dict):
                    serialized_tools.append({k: v for k, v in tool.items() if k})
            return serialized_tools

    @staticmethod
    def _describe_annotation(annotation: Any) -> str | None:
        if annotation is inspect._empty:
            return None
        if isinstance(annotation, type):
            return annotation.__name__
        return str(annotation)

    def _summarize_tool_signatures(
        self, prompt: chat_prompt.ChatPrompt
    ) -> list[dict[str, Any]]:
        signatures: list[dict[str, Any]] = []
        for name, func in getattr(prompt, "function_map", {}).items():
            callable_obj = getattr(func, "__wrapped__", func)
            try:
                sig = inspect.signature(callable_obj)
            except (TypeError, ValueError):  # pragma: no cover - defensive
                signatures.append({"name": name, "signature": "unavailable"})
                continue

            params: list[dict[str, Any]] = []
            for parameter in sig.parameters.values():
                params.append(
                    self._drop_none(
                        {
                            "name": parameter.name,
                            "kind": parameter.kind.name,
                            "annotation": self._describe_annotation(
                                parameter.annotation
                            ),
                            "default": (
                                None
                                if parameter.default is inspect._empty
                                else parameter.default
                            ),
                        }
                    )
                )

            signatures.append(
                self._drop_none(
                    {
                        "name": name,
                        "parameters": params,
                        "docstring": inspect.getdoc(callable_obj),
                    }
                )
            )
        return signatures

    def _build_agent_config(self, prompt: chat_prompt.ChatPrompt) -> dict[str, Any]:
        agent_config: dict[str, Any] = dict(prompt.to_dict())
        agent_config["project_name"] = getattr(prompt, "project_name", None)
        agent_config["model"] = getattr(prompt, "model", None) or self.model
        agent_config["tools"] = self._serialize_tools(prompt)
        agent_config["optimizer"] = self.__class__.__name__
        return self._drop_none(agent_config)

    def get_optimizer_metadata(self) -> dict[str, Any]:
        """Override in subclasses to expose optimizer-specific parameters."""
        return {}

    def _build_optimizer_metadata(self) -> dict[str, Any]:
        metadata = {
            "name": self.__class__.__name__,
            "version": _OPTIMIZER_VERSION,
            "model": self.model,
            "model_parameters": self.model_parameters or None,
            "seed": getattr(self, "seed", None),
            "num_threads": getattr(self, "num_threads", None),
        }

        # n_threads is used by some optimizers instead of num_threads
        if metadata["num_threads"] is None and hasattr(self, "n_threads"):
            metadata["num_threads"] = getattr(self, "n_threads")

        if hasattr(self, "reasoning_model"):
            metadata["reasoning_model"] = getattr(self, "reasoning_model")

        extra_parameters = self.get_optimizer_metadata()
        if extra_parameters:
            metadata["parameters"] = extra_parameters

        return self._drop_none(metadata)

    def _prepare_experiment_config(
        self,
        *,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict[str, Any] | None = None,
        configuration_updates: dict[str, Any] | None = None,
        additional_metadata: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        dataset_id = getattr(dataset, "id", None)
        project_name = (
            getattr(self.agent_class, "project_name", None)
            if hasattr(self, "agent_class")
            else None
        )
        if not project_name:
            project_name = getattr(prompt, "project_name", None)
        if not project_name:
            project_name = self.__class__.__name__

        base_config: dict[str, Any] = {
            "project_name": project_name,
            "agent_class": (
                getattr(self.agent_class, "__name__", None)
                if hasattr(self, "agent_class")
                else None
            ),
            "agent_config": self._build_agent_config(prompt),
            "metric": getattr(metric, "__name__", str(metric)),
            "dataset": getattr(dataset, "name", None),
            "dataset_id": dataset_id,
            "optimizer": self.__class__.__name__,
            "optimizer_metadata": self._build_optimizer_metadata(),
            "tool_signatures": self._summarize_tool_signatures(prompt),
            "configuration": {
                "prompt": prompt.get_messages(),
                "prompt_name": getattr(prompt, "name", None),
                "tools": self._serialize_tools(prompt),
                "prompt_project_name": getattr(prompt, "project_name", None),
            },
        }

        if configuration_updates:
            base_config["configuration"] = self._deep_merge_dicts(
                base_config["configuration"], configuration_updates
            )

        if additional_metadata:
            base_config = self._deep_merge_dicts(base_config, additional_metadata)

        if experiment_config:
            base_config = self._deep_merge_dicts(base_config, experiment_config)

        return self._drop_none(base_config)

    @abstractmethod
    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        *args: Any,
        **kwargs: Any,
    ) -> optimization_result.OptimizationResult:
        """
        Optimize a prompt.

        Args:
           dataset: Opik dataset name, or Opik dataset
           metric: A metric function, this function should have two arguments:
               dataset_item and llm_output
           prompt: the prompt to optimize
           input_key: input field of dataset
           output_key: output field of dataset
           experiment_config: Optional configuration for the experiment
           project_name: Opik project name for logging traces (default: "Optimization")
           **kwargs: Additional arguments for optimization
        """
        pass

    def get_history(self) -> list[OptimizationRound]:
        """
        Get the optimization history.

        Returns:
            List[Dict[str, Any]]: List of optimization rounds with their details
        """
        return self._history

    def _add_to_history(self, round_data: OptimizationRound) -> None:
        """
        Add a round to the optimization history.

        Args:
            round_data: Dictionary containing round details
        """
        self._history.append(round_data)

    def _update_optimization(
        self, optimization: optimization.Optimization, status: str
    ) -> None:
        """
        Update the optimization status
        """
        # FIXME: remove when a solution is added to opik's optimization.update method
        count = 0
        while count < 3:
            try:
                optimization.update(status="completed")
                break
            except ApiError:
                count += 1
                time.sleep(5)
        if count == 3:
            logger.warning("Unable to update optimization status; continuing...")

    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        n_threads: int,
        verbose: int = 1,
        dataset_item_ids: list[str] | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        seed: int | None = None,
        agent_class: type[OptimizableAgent] | None = None,
    ) -> float:
        random.seed(seed)

        self.agent_class: type[OptimizableAgent]

        if agent_class is None:
            self.agent_class = create_litellm_agent_class(prompt, optimizer_ref=self)
        else:
            self.agent_class = agent_class

        agent = self.agent_class(prompt)

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
            messages = prompt.get_messages(dataset_item)
            raw_model_output = agent.invoke(messages)
            cleaned_model_output = raw_model_output.strip()

            # Add tags to trace for optimization tracking
            if self.current_optimization_id:
                opik_context.update_current_trace(
                    tags=[self.current_optimization_id, "Evaluation"]
                )

            result = {
                mappers.EVALUATED_LLM_TASK_OUTPUT: cleaned_model_output,
            }
            return result

        experiment_config = self._prepare_experiment_config(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            experiment_config=experiment_config,
        )

        if n_samples is not None:
            if dataset_item_ids is not None:
                raise Exception("Can't use n_samples and dataset_item_ids")

            all_ids = [dataset_item["id"] for dataset_item in dataset.get_items()]
            n_samples = min(n_samples, len(all_ids))
            dataset_item_ids = random.sample(all_ids, n_samples)

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=n_threads,
            project_name=self.project_name,
            experiment_config=experiment_config,
            optimization_id=self.current_optimization_id,
            verbose=verbose,
        )
        return score


@dataclass(frozen=True, slots=True)
class EvaluationSpec:
    dataset: Dataset
    item_ids: tuple[str, ...] | None
    sample_count: int | None

    def kwargs(self) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        if self.item_ids is not None:
            payload["dataset_item_ids"] = list(self.item_ids)
        if self.sample_count is not None:
            payload["n_samples"] = self.sample_count
        return payload


@dataclass(frozen=True, slots=True)
class EvaluationPlan:
    split: DatasetSplitResult
    train: EvaluationSpec
    validation: EvaluationSpec | None

    @property
    def has_validation(self) -> bool:
        return self.validation is not None


@dataclass(frozen=True, slots=True)
class SpecSelection:
    items: list[dict[str, Any]]
    dataset_item_ids: list[str] | None
    sample_count: int | None
