from typing import Any, cast
from collections.abc import Callable

import copy
import inspect
import logging
import time
from abc import ABC, abstractmethod
import random
import importlib.metadata


import litellm
from opik.rest_api.core import ApiError
from opik.api_objects import optimization
from opik import Dataset, opik_context
from pydantic import BaseModel

from . import optimization_result
from .api_objects import chat_prompt
from .optimizable_agent import OptimizableAgent
from .utils import create_litellm_agent_class
from . import task_evaluator, helpers

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
    current_prompt: "chat_prompt.ChatPrompt"
    current_score: float
    generated_prompts: Any
    best_prompt: "chat_prompt.ChatPrompt"
    best_score: float
    improvement: float


class BaseOptimizer(ABC):
    def __init__(
        self,
        model: str,
        verbose: int = 1,
        seed: int = 42,
        model_parameters: dict[str, Any] | None = None,
        name: str | None = None,
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
           name: Optional name for the optimizer instance. This will be used when creating optimizations.
        """
        self.model = model
        self.reasoning_model = model
        self.model_parameters = model_parameters or {}
        self.verbose = verbose
        self.seed = seed
        self.name = name
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
        self,
        prompt: "chat_prompt.ChatPrompt",
        dataset: "Dataset",
        metric: Callable,
        support_content_parts: bool = False,
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

        if prompt._has_content_parts() and not support_content_parts:
            raise ValueError(
                "Prompt has content parts, which are not supported by this optimizer - You can use the Hierarchical Reflective Optimizer instead."
            )

    def _setup_agent_class(
        self, prompt: "chat_prompt.ChatPrompt", agent_class: Any = None
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

    # ------------------------------------------------------------------
    # Experiment metadata helpers
    # ------------------------------------------------------------------

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
    def _serialize_tools(prompt: "chat_prompt.ChatPrompt") -> list[dict[str, Any]]:
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
        self, prompt: "chat_prompt.ChatPrompt"
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
                    helpers.drop_none(
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
                helpers.drop_none(
                    {
                        "name": name,
                        "parameters": params,
                        "docstring": inspect.getdoc(callable_obj),
                    }
                )
            )
        return signatures

    def _build_agent_config(self, prompt: "chat_prompt.ChatPrompt") -> dict[str, Any]:
        agent_config: dict[str, Any] = dict(prompt.to_dict())
        agent_config["project_name"] = getattr(prompt, "project_name", None)
        agent_config["model"] = getattr(prompt, "model", None) or self.model
        agent_config["tools"] = self._serialize_tools(prompt)
        agent_config["optimizer"] = self.__class__.__name__
        return helpers.drop_none(agent_config)

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

        return helpers.drop_none(metadata)

    def _build_optimization_config(self) -> dict[str, Any]:
        """
        Build metadata dictionary for optimization creation to be used when
        creating Opik optimizations.

        Returns:
            Dictionary with 'optimizer' and optionally 'name' keys.
        """
        metadata: dict[str, Any] = {"optimizer": self.__class__.__name__}
        if self.name:
            metadata["name"] = self.name
        return metadata

    def _prepare_experiment_config(
        self,
        *,
        prompt: "chat_prompt.ChatPrompt",
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

        return helpers.drop_none(base_config)

    @abstractmethod
    def optimize_prompt(
        self,
        prompt: "chat_prompt.ChatPrompt",
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
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
           optimization_id: Optional ID to use when creating the Opik optimization run;
               when provided it must be a valid UUIDv7 string.
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
                "llm_output": cleaned_model_output,
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
