from typing import Any
from collections.abc import Callable

import logging
import time
from abc import abstractmethod
import random


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


class OptimizationRound(BaseModel):
    model_config = {"arbitrary_types_allowed": True}

    round_number: int
    current_prompt: "chat_prompt.ChatPrompt"
    current_score: float
    generated_prompts: Any
    best_prompt: "chat_prompt.ChatPrompt"
    best_score: float
    improvement: float


class BaseOptimizer:
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
    def opik_client(self):
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
            return create_litellm_agent_class(prompt, optimizer=self)
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

    def create_optimization_context(
        self, dataset: "Dataset", metric: Callable, metadata: dict | None = None
    ):
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
        agent_class: str | None = None,
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
        agent_class: str | None = None,
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
            self.agent_class = create_litellm_agent_class(prompt, optimizer=self)
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

        experiment_config = experiment_config or {}
        experiment_config["project_name"] = self.__class__.__name__
        experiment_config = {
            **experiment_config,
            **{
                "agent_class": self.agent_class.__name__,
                "agent_config": prompt.to_dict(),
                "metric": metric.__name__,
                "dataset": dataset.name,
                "configuration": {"prompt": (prompt.get_messages() if prompt else [])},
            },
        }

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
            project_name=self.agent_class.project_name,
            experiment_config=experiment_config,
            optimization_id=None,
            verbose=verbose,
        )
        return score
