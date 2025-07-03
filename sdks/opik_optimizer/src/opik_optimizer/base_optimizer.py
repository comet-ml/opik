from typing import Any, Callable, Dict, List, Optional, Type

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
from .utils import create_litellm_agent_class
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
        prompt: "chat_prompt.ChatPrompt",
        dataset: Dataset,
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

    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        n_threads: int,
        verbose: int = 1,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
        seed: Optional[int] = None,
        agent_class: Optional[Type[OptimizableAgent]] = None,
    ) -> float:
        random.seed(seed)

        if prompt.model is None:
            prompt.model = self.model
        if prompt.model_kwargs is None:
            prompt.model_kwargs = self.model_kwargs

        self.agent_class: Type[OptimizableAgent]

        if agent_class is None:
            self.agent_class = create_litellm_agent_class(prompt)
        else:
            self.agent_class = agent_class

        agent = self.agent_class(prompt)

        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, str]:
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
