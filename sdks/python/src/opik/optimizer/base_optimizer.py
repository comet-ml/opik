from opik.api_objects.dataset import Dataset
from opik.evaluation.metrics import BaseMetric


class BaseOptimizer:
    def __init__(self, model: str, project_name: str = None, **model_kwargs):
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
        self, dataset: str | Dataset, metric: BaseMetric, prompt: str, **kwargs
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

    def display_best_prompts(self):
        """
        Display the best prompts found.
        """

    def get_best_prompt(self, from_top=0):
        """
        Get the best prompt from top
        """

    def evaluate_prompt_on_dataset(
        self,
        dataset: str | Dataset,
        metric: BaseMetric,
        prompt: str,
        verbose: bool = False,
        count: int = None,
        **kwargs
    ):
        """
        Evaluate the prompt on a dataset with given metric.
        """
