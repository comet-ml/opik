from typing import Optional, Union, List, Dict, Any
import opik
import logging
import time

import litellm
from opik.evaluation import metrics
from opik.opik_context import get_current_span_data
from opik.rest_api.core import ApiError

from pydantic import BaseModel
from ._throttle import RateLimiter, rate_limited
from .cache_config import initialize_cache
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor
from .optimization_config.configs import TaskConfig, MetricConfig

limiter = RateLimiter(max_calls_per_second=15)

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
    def __init__(self, model: str, project_name: Optional[str] = None, **model_kwargs):
        """
        Base class for optimizers.

        Args:
           model: LiteLLM model name
           project_name: Opik project name
           model_kwargs: additional args for model (eg, temperature)
        """
        self.model = model
        self.reasoning_model = model
        self.model_kwargs = model_kwargs
        self.project_name = project_name
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

    @rate_limited(limiter)
    def _call_model(
        self,
        prompt: str,
        system_prompt: Optional[str] = None,
        is_reasoning: bool = False,
    ) -> str:
        """Call the model to get suggestions based on the meta-prompt."""
        model = self.reasoning_model if is_reasoning else self.model
        messages = []

        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
            logger.debug(f"Using custom system prompt: {system_prompt[:100]}...")
        else:
            messages.append(
                {"role": "system", "content": "You are a helpful assistant."}
            )

        messages.append({"role": "user", "content": prompt})
        logger.debug(f"Calling model {model} with prompt: {prompt[:100]}...")

        api_params = self.model_kwargs.copy()
        api_params.update(
            {
                "model": model,
                "messages": messages,
                # Ensure required params like 'temperature', 'max_tokens' are present
                # Defaults added here for safety, though usually set in __init__ kwargs
                "temperature": api_params.get("temperature", 0.3),
                "max_tokens": api_params.get("max_tokens", 1000),
            }
        )

        # Attempt to add Opik monitoring if available
        try:
            # Assuming opik_litellm_monitor is imported and configured elsewhere
            api_params = opik_litellm_monitor.try_add_opik_monitoring_to_params(
                api_params
            )
            logger.debug("Opik monitoring hooks added to LiteLLM params.")
        except Exception as e:
            logger.warning(f"Could not add Opik monitoring to LiteLLM params: {e}")

        logger.debug(
            f"Final API params (excluding messages): { {k:v for k,v in api_params.items() if k != 'messages'} }"
        )

        # Increment Counter
        self.llm_call_counter += 1
        logger.debug(f"LLM Call Count: {self.llm_call_counter}")

        try:
            response = litellm.completion(**api_params)
            model_output = response.choices[0].message.content.strip()
            logger.debug(f"Model response from {model_to_use}: {model_output[:100]}...")
            return model_output
        except litellm.exceptions.RateLimitError as e:
            logger.error(f"LiteLLM Rate Limit Error for model {model_to_use}: {e}")
            # Consider adding retry logic here with tenacity
            raise
        except litellm.exceptions.APIConnectionError as e:
            logger.error(f"LiteLLM API Connection Error for model {model_to_use}: {e}")
            # Consider adding retry logic here
            raise
        except litellm.exceptions.ContextWindowExceededError as e:
            logger.error(
                f"LiteLLM Context Window Exceeded Error for model {model_to_use}. Prompt length: {len(prompt)}. Details: {e}"
            )
            raise
        except litellm.exceptions.APIError as e:  # Catch broader API errors
            logger.error(f"LiteLLM API Error for model {model_to_use}: {e}")
            raise
        except Exception as e:
            # Catch any other unexpected errors
            logger.error(
                f"Unexpected error during model call to {model_to_use}: {type(e).__name__} - {e}"
            )
            raise

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
