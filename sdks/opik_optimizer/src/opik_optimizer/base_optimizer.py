import logging
import time
from abc import abstractmethod
from typing import Any, Callable, Dict, List, Optional

import litellm
import opik
from opik.rest_api.core import ApiError
from opik.api_objects import optimization
from pydantic import BaseModel

from . import _throttle, optimization_result
from .cache_config import initialize_cache
from .optimization_config import chat_prompt

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
        **model_kwargs: Any,
    ) -> None:
        """
        Base class for optimizers.

        Args:
           model: LiteLLM model name
           verbose: Controls internal logging/progress bars (0=off, 1=on).
           model_kwargs: additional args for model (eg, temperature)
        """
        self.model = model
        self.reasoning_model = model
        self.model_kwargs = model_kwargs
        self.verbose = verbose
        self._history: List[OptimizationRound] = []
        self.experiment_config = None
        self.llm_call_counter = 0

        # Initialize shared cache
        initialize_cache()

    @abstractmethod
    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        experiment_config: Optional[Dict] = None,
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

    @abstractmethod
    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        n_samples: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        **kwargs: Any,
    ) -> float:
        """
        Evaluate a prompt.

        Args:
           prompt: the prompt to evaluate
           dataset: Opik dataset name, or Opik dataset
           metrics: A list of metric functions, these functions should have two arguments:
               dataset_item and llm_output
           n_samples: number of items to test in the dataset
           dataset_item_ids: Optional list of dataset item IDs to evaluate
           experiment_config: Optional configuration for the experiment
           **kwargs: Additional arguments for evaluation

        Returns:
            float: The evaluation score
        """
        pass

    def get_history(self) -> List[OptimizationRound]:
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
