from typing import Optional
import opik
from opik.evaluation import metrics


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

    def optimize_prompt(
        self, dataset: str | opik.Dataset, metric: metrics.BaseMetric, prompt: str, **kwargs
    ):
        """
        Optimizer a prompt.

        Args:
           dataset: Opik dataset name, or Opik dataset
           metric: instance of an Opik metric
           prompt: the prompt to optimize
        """
        self.dataset = dataset
        self.metric = metric
        self.prompt = prompt