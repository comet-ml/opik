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
from opik import Dataset
from pydantic import BaseModel

from . import _throttle, optimization_result
from .cache_config import initialize_cache
from .optimization_config import chat_prompt, mappers
from .optimizable_agent import OptimizableAgent
from .utils import create_litellm_agent_class, optimization_context
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
        **model_kwargs: Any,
    ) -> None:
        """
        Base class for optimizers.

        Args:
           model: LiteLLM model name
           verbose: Controls internal logging/progress bars (0=off, 1=on).
           seed: Random seed for reproducibility (default: 42)
           model_kwargs: additional args for model (eg, temperature)
        """
        self.model = model
        self.reasoning_model = model
        self.model_kwargs = model_kwargs
        self.verbose = verbose
        self.seed = seed
        self._history: list[OptimizationRound] = []
        self.experiment_config = None
        self.llm_call_counter = 0
        self.tool_call_counter = 0
        self._opik_client = None  # Lazy initialization

        # Initialize shared cache
        initialize_cache()

    def reset_counters(self) -> None:
        """Reset all call counters for a new optimization run."""
        self.llm_call_counter = 0
        self.tool_call_counter = 0

    def increment_llm_counter(self) -> None:
        """Increment the LLM call counter."""
        self.llm_call_counter += 1

    def increment_tool_counter(self) -> None:
        """Increment the tool call counter."""
        self.tool_call_counter += 1

    def cleanup(self) -> None:
        """
        Clean up resources and perform memory management.
        Should be called when the optimizer is no longer needed.
        """
        # Reset counters
        self.reset_counters()

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

    def validate_optimization_inputs(
        self, prompt: "chat_prompt.ChatPrompt", dataset: "Dataset", metric: Callable
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

    def setup_agent_class(
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

    def configure_prompt_model(self, prompt: "chat_prompt.ChatPrompt") -> None:
        """
        Configure prompt model and model_kwargs if not set.

        Args:
            prompt: The chat prompt to configure
        """
        # Only configure if prompt is a valid ChatPrompt object
        if hasattr(prompt, "model") and hasattr(prompt, "model_kwargs"):
            if prompt.model is None:
                prompt.model = self.model
            if prompt.model_kwargs is None:
                prompt.model_kwargs = self.model_kwargs

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

    def _build_agent_config(self, prompt: "chat_prompt.ChatPrompt") -> dict[str, Any]:
        agent_config: dict[str, Any] = dict(prompt.to_dict())
        agent_config["project_name"] = getattr(prompt, "project_name", None)
        agent_config["model"] = getattr(prompt, "model", None) or self.model
        agent_config["tools"] = self._serialize_tools(prompt)
        return self._drop_none(agent_config)

    def get_optimizer_metadata(self) -> dict[str, Any]:
        """Override in subclasses to expose optimizer-specific parameters."""
        return {}

    def _build_optimizer_metadata(self) -> dict[str, Any]:
        metadata = {
            "name": self.__class__.__name__,
            "version": _OPTIMIZER_VERSION,
            "model": self.model,
            "model_kwargs": self.model_kwargs or None,
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

    def create_optimization_context(
        self, dataset: "Dataset", metric: Callable, metadata: dict | None = None
    ) -> Any:
        """
        Create optimization context for tracking.

        Args:
            dataset: The dataset being optimized
            metric: The metric function
            metadata: Additional metadata

        Returns:
            Optimization context manager
        """
        context_metadata = {
            "optimizer": self.__class__.__name__,
            "model": self.model,
            "seed": self.seed,
        }
        if metadata:
            context_metadata.update(metadata)

        return optimization_context(
            client=self.opik_client,
            dataset_name=dataset.name,
            objective_name=metric.__name__,
            metadata=context_metadata,
        )

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
           **kwargs: Additional arguments for optimization
        """
        pass

    def optimize_mcp(
        self,
        prompt: "chat_prompt.ChatPrompt",
        dataset: Dataset,
        metric: Callable,
        *,
        tool_name: str,
        second_pass: Any,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        fallback_invoker: Callable[[dict[str, Any]], str] | None = None,
        fallback_arguments: Callable[[Any], dict[str, Any]] | None = None,
        allow_tool_use_on_second_pass: bool = False,
        **kwargs: Any,
    ) -> optimization_result.OptimizationResult:
        """
        Optimize prompts that rely on MCP (Model Context Protocol) tooling.

        This method provides a standardized interface for optimizing prompts that use
        external tools through the MCP protocol. It handles tool invocation, second-pass
        coordination, and fallback mechanisms.

        Args:
            prompt: The chat prompt to optimize, must include tools
            dataset: Opik dataset containing evaluation data
            metric: Evaluation function that takes (dataset_item, llm_output) and returns a score
            tool_name: Name of the MCP tool to use for optimization
            second_pass: MCPSecondPassCoordinator for handling second-pass tool calls
            experiment_config: Optional configuration for the experiment
            n_samples: Number of samples to use for optimization (default: None)
            auto_continue: Whether to auto-continue optimization (default: False)
            agent_class: Custom agent class to use (default: None)
            fallback_invoker: Fallback function for tool invocation (default: None)
            fallback_arguments: Function to extract tool arguments (default: None)
            allow_tool_use_on_second_pass: Whether to allow tool use on second pass (default: False)
            **kwargs: Additional arguments for optimization

        Returns:
            OptimizationResult: The optimization result containing the optimized prompt and metrics

        Raises:
            NotImplementedError: If the optimizer doesn't implement MCP optimization
            ValueError: If the prompt doesn't include required tools
        """
        raise NotImplementedError(
            f"{self.__class__.__name__} does not implement optimize_mcp yet."
        )

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

    def update_optimization(
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

        if prompt.model is None:
            prompt.model = self.model
        if prompt.model_kwargs is None:
            prompt.model_kwargs = self.model_kwargs

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
            dataset_item_ids = random.sample(all_ids, n_samples)

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=n_threads,
            project_name=experiment_config.get("project_name"),
            experiment_config=experiment_config,
            optimization_id=None,
            verbose=verbose,
        )
        return score
