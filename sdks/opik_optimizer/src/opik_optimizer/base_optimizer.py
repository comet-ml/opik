from typing import Optional, Union, List, Dict, Any
import opik
from opik.evaluation import metrics
from pydantic import BaseModel


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
        self.model_kwargs = model_kwargs
        self.project_name = project_name
        self._history = []

    def optimize_prompt(
        self,
        dataset: Union[str, opik.Dataset],
        metric: metrics.BaseMetric,
        prompt: str,
        **kwargs
    ):
        """
        Optimize a prompt.

        Args:
           dataset: Opik dataset name, or Opik dataset
           metric: instance of an Opik metric
           prompt: the prompt to optimize
        """
        self.dataset = dataset
        self.metric = metric
        self.prompt = prompt

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
