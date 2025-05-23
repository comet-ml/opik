from typing import Optional, Union, List, Dict, Any
import opik
import logging
import time

import litellm
from . import _throttle
from opik.rest_api.core import ApiError

from pydantic import BaseModel
from .cache_config import initialize_cache
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor
from .optimization_config.configs import TaskConfig, MetricConfig

_limiter = _throttle.get_rate_limiter_for_current_opik_installation()

# Don't use unsupported params:
litellm.drop_params = True

# Set up logging:
logger = logging.getLogger(__name__)


class OptimizationRound(BaseModel):
    round_number: int
    current_prompt: str
    current_score: float
    generated_prompts: List[Dict[str, Any]]
    best_prompt: str
    best_score: float
    improvement: float


class BaseOptimizer:
    def __init__(self, model: str, project_name: Optional[str] = None, verbose: int = 1, **model_kwargs):
        """
        Base class for optimizers.

        Args:
           model: LiteLLM model name
           project_name: Opik project name
           verbose: Controls internal logging/progress bars (0=off, 1=on).
           model_kwargs: additional args for model (eg, temperature)
        """
        self.model = model
        self.reasoning_model = model
        self.model_kwargs = model_kwargs
        self.project_name = project_name
        self.verbose = verbose
        self._history = []
        self.experiment_config = None
        self.llm_call_counter = 0

        # Initialize shared cache
        initialize_cache()

    def optimize_prompt(
        self,
        dataset: Union[str, opik.Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        prompt: str,
        input_key: str,
        output_key: str,
        experiment_config: Optional[Dict] = None,
        **kwargs,
    ):
        """
        Optimize a prompt.

        Args:
           dataset: Opik dataset name, or Opik dataset
           metric_config: instance of a MetricConfig
           task_config: instance of a TaskConfig
           prompt: the prompt to optimize
           input_key: input field of dataset
           output_key: output field of dataset
           experiment_config: Optional configuration for the experiment
           **kwargs: Additional arguments for optimization
        """
        self.dataset = dataset
        self.metric = metric
        self.prompt = prompt
        self.input_key = input_key
        self.output_key = output_key
        self.experiment_config = experiment_config

    def evaluate_prompt(
        self,
        dataset: Union[str, opik.Dataset],
        metric_config: MetricConfig,
        prompt: str,
        input_key: str,
        output_key: str,
        n_samples: int = 10,
        task_config: Optional[TaskConfig] = None,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        **kwargs,
    ) -> float:
        """
        Evaluate a prompt.

        Args:
           dataset: Opik dataset name, or Opik dataset
           metric_config: instance of a MetricConfig
           task_config: instance of a TaskConfig
           prompt: the prompt to evaluate
           input_key: input field of dataset
           output_key: output field of dataset
           n_samples: number of items to test in the dataset
           dataset_item_ids: Optional list of dataset item IDs to evaluate
           experiment_config: Optional configuration for the experiment
           **kwargs: Additional arguments for evaluation

        Returns:
            float: The evaluation score
        """
        self.dataset = dataset
        self.metric_config = metric_config
        self.task_config = task_config
        self.prompt = prompt
        self.input_key = input_key
        self.output_key = output_key
        self.experiment_config = experiment_config
        return 0.0  # Base implementation returns 0

    def get_history(self) -> List[Dict[str, Any]]:
        """
        Get the optimization history.

        Returns:
            List[Dict[str, Any]]: List of optimization rounds with their details
        """
        return self._history

    def _add_to_history(self, round_data: Dict[str, Any]):
        """
        Add a round to the optimization history.

        Args:
            round_data: Dictionary containing round details
        """
        self._history.append(round_data)

            
    def update_optimization(self, optimization, status: str) -> None:
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
