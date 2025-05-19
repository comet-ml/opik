from typing import Any, Dict, List, Tuple, Union, Optional
import os
import random

import opik

from opik.integrations.dspy.callback import OpikCallback
from opik.opik_context import get_current_span_data
from opik.evaluation import evaluate
from opik import Dataset

import dspy

import litellm
from litellm.caching import Cache

from ..optimization_result import OptimizationResult
from ..base_optimizer import BaseOptimizer
from ._mipro_optimizer_v2 import MIPROv2
from ._lm import LM
from ..optimization_config.configs import MetricConfig, TaskConfig
from .utils import (
    create_dspy_signature,
    opik_metric_to_dspy,
    create_dspy_training_set,
    get_tool_prompts,
)

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type="disk", disk_cache_dir=disk_cache_dir)

# Set up logging
import logging

logger = logging.getLogger(__name__)  # Inherits config from setup_logging


class MiproOptimizer(BaseOptimizer):
    def __init__(self, model, project_name: Optional[str] = None, **model_kwargs):
        super().__init__(model, project_name, **model_kwargs)
        self.tools = []
        self.num_threads = self.model_kwargs.pop("num_threads", 6)
        self.model_kwargs["model"] = self.model
        lm = LM(**self.model_kwargs)
        opik_callback = OpikCallback(project_name=self.project_name, log_graph=True)
        dspy.configure(lm=lm, callbacks=[opik_callback])
        logger.debug(f"Initialized MiproOptimizer with model: {model}")

    def evaluate_prompt(
        self,
        dataset: Union[str, Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        prompt: Union[str, dspy.Module, OptimizationResult] = None,
        n_samples: int = 10,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        **kwargs,
    ) -> float:
        """
        Compute the score of a prompt on dataset (or part thereof)

        Args:
            dataset: Opik dataset name or dataset
            metric_config: A MetricConfig instance
            task_config: A TaskConfig instance
            prompt: The prompt to evaluate
            n_samples: number of items to test in the dataset
            dataset_item_ids: Optional list of dataset item IDs to evaluate
            experiment_config: Optional configuration for the experiment
            **kwargs: Additional arguments for evaluation

        Returns:
            Evaluation score
        """
        # FIMXE: call super when it is ready
        # FIXME: Intermediate values:
        metric = metric_config.metric
        input_key = task_config.input_dataset_fields[0]  # FIXME: allow all inputs
        output_key = task_config.output_dataset_field

        if isinstance(dataset, str):
            opik_client = opik.Opik(project_name=self.project_name)
            dataset = opik_client.get_dataset(dataset)

        def LLM(input: str) -> str:
            if isinstance(prompt, str):
                response = litellm.completion(
                    messages=[
                        {"role": "system", "content": prompt},
                        {"role": "user", "content": input},
                    ],
                    metadata={
                        "opik": {
                            "current_span_data": get_current_span_data(),
                            "tags": ["optimizer"],
                        },
                    },
                    **self.model_kwargs,
                )
                return response.choices[0].message.content
            elif isinstance(prompt, OptimizationResult):
                if prompt.optimizer == "MiproOptimizer" and getattr(prompt, "details"):
                    program = prompt.details["program"]
                    result = program(**{input_key: input})
                    return getattr(result, output_key)
                else:
                    response = litellm.completion(
                        messages=[
                            {"role": "system", "content": prompt.prompt},
                            # FIXME: insert demonstrations here
                            {"role": "user", "content": input},
                        ],
                        metadata={
                            "opik": {
                                "current_span_data": get_current_span_data(),
                                "tags": ["optimizer"],
                            },
                        },
                        **self.model_kwargs,
                    )
                    return response.choices[0].message.content
            elif isinstance(prompt, dspy.Module):
                result = prompt(**{input_key: input})
                return getattr(result, output_key)
            else:
                raise Exception("I don't know how to evaluate this prompt: %r" % prompt)

        def evaluation_task(dataset_item):
            # Get the model output
            model_output = LLM(dataset_item[input_key])

            # Prepare the result with all required fields
            result = {
                "input": dataset_item[input_key],
                "output": model_output,
                "expected_output": dataset_item[output_key],
                "reference": dataset_item[output_key],
            }

            # Add context if available, otherwise use input as context
            result["context"] = dataset_item.get("context", dataset_item[input_key])

            return result

        if n_samples is not None:
            if dataset_item_ids is not None:
                raise Exception("Can't use n_samples and dataset_item_ids")

            all_ids = [dataset_item["id"] for dataset_item in dataset.get_items()]
            dataset_item_ids = random.sample(all_ids, n_samples)

        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "tools": (
                    [f.__name__ for f in task_config.tools] if task_config.tools else []
                ),
                "metric": metric_config.metric.name,
                "dataset": dataset.name,
            },
        }
        # Run evaluation with all metrics at once
        evaluation = evaluate(
            dataset=dataset,
            task=evaluation_task,
            scoring_metrics=[metric],
            # "reference" needs to match metric
            scoring_key_mapping={"reference": output_key},
            task_threads=self.num_threads,
            dataset_item_ids=dataset_item_ids,
            project_name=self.project_name,
            experiment_config=experiment_config,
        )

        # Calculate average score across all metrics
        total_score = 0
        count = len(evaluation.test_results)
        for i in range(count):
            total_score += evaluation.test_results[i].score_results[0].value
        score = total_score / count if count > 0 else 0.0

        logger.debug(
            f"Starting Mipro evaluation for prompt type: {type(prompt).__name__}"
        )
        logger.debug(f"Evaluation score: {score:.4f}")
        return score

    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        num_candidates: int = 10,
        experiment_config: Optional[Dict] = None,
        num_trials: Optional[int] = 3,
        **kwargs,
    ) -> OptimizationResult:
        self._opik_client = opik.Opik()
        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric_config.metric.name,
            )
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            optimization = None

        if not optimization:
            logger.warning("Continuing without Opik optimization tracking.")

        try:
            result = self._optimize_prompt(
                dataset=dataset,
                metric_config=metric_config,
                task_config=task_config,
                num_candidates=num_candidates,
                experiment_config=experiment_config,
                optimization_id=optimization.id if optimization is not None else None,
                num_trials=num_trials,
                **kwargs,
            )
            if optimization:
                self.update_optimization(optimization, status="completed")
            return result
        except Exception as e:
            logger.error(f"Mipro optimization failed: {e}", exc_info=True)
            if optimization:
                self.update_optimization(optimization, status="cancelled")
            raise e

    def _optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        num_candidates: int = 10,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        num_trials: Optional[int] = 3,
        **kwargs,
    ) -> OptimizationResult:
        logger.info("Preparing MIPRO optimization...")
        self.prepare_optimize_prompt(
            dataset=dataset,
            metric_config=metric_config,
            task_config=task_config,
            num_candidates=num_candidates,
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            num_trials=num_trials,
            **kwargs,
        )
        logger.info("Starting MIPRO compilation...")
        result = self.continue_optimize_prompt()
        logger.info("MIPRO optimization complete.")
        return result

    def prepare_optimize_prompt(
        self,
        dataset,
        metric_config,
        task_config,
        num_candidates: int = 10,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        num_trials: Optional[int] = 3,
        **kwargs,
    ) -> None:
        # FIXME: Intermediate values:
        metric = metric_config.metric
        prompt = task_config.instruction_prompt
        input_key = task_config.input_dataset_fields[0]  # FIXME: allow all
        output_key = task_config.output_dataset_field
        self.tools = task_config.tools
        self.num_candidates = num_candidates
        self.seed = 9
        self.input_key = input_key
        self.output_key = output_key
        self.prompt = prompt
        self.num_trials = num_trials

        # Convert to values for MIPRO:
        if isinstance(dataset, str):
            opik_client = opik.Opik(project_name=self.project_name)
            self.dataset = opik_client.get_dataset(dataset).get_items()
        else:
            self.dataset = dataset.get_items()

        # Validate dataset:
        for row in self.dataset:
            if self.input_key not in row:
                raise Exception("row does not contain input_key: %r" % self.input_key)
            if self.output_key not in row:
                raise Exception("row does not contain output_key: %r" % self.output_key)

        self.trainset = create_dspy_training_set(self.dataset, self.input_key)
        self.data_signature = create_dspy_signature(
            self.input_key, self.output_key, self.prompt
        )

        if self.tools:
            self.module = dspy.ReAct(self.data_signature, tools=self.tools)
        else:
            self.module = dspy.Predict(self.data_signature)

        # Convert the metric to a DSPy-compatible function
        self.metric_function = opik_metric_to_dspy(metric, self.output_key)
        self.opik_metric = metric
        log_dir = os.path.expanduser("~/.opik-optimizer-checkpoints")
        os.makedirs(log_dir, exist_ok=True)

        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "tools": [f.__name__ for f in self.tools],
                "metric": metric.name,
                "num_threads": self.num_threads,
                "num_candidates": self.num_candidates,
                "num_trails": self.num_trails,
                "dataset": dataset.name,
            },
        }

        # Initialize the optimizer:
        self.optimizer = MIPROv2(
            metric=self.metric_function,
            auto="light",
            num_threads=self.num_threads,
            verbose=False,
            num_candidates=self.num_candidates,
            seed=self.seed,
            opik_prompt_task_config=task_config,
            opik_dataset=dataset,
            opik_project_name=self.project_name,
            opik_metric_config=metric_config,
            opik_optimization_id=optimization_id,
            log_dir=log_dir,
            experiment_config=experiment_config,
        )

        logger.debug("Created DSPy training set.")
        logger.debug(f"Using DSPy module: {type(self.module).__name__}")
        logger.debug(f"Using metric function: {self.metric_function.__name__}")

    def load_from_checkpoint(self, filename):
        """
        Load the module from a checkpoint.
        """
        self.module.load(os.path.expanduser(filename))

    def continue_optimize_prompt(self):
        """
        Continue to look for optimizations
        """
        self.results = self.optimizer.compile(
            student=self.module,
            trainset=self.trainset,
            provide_traceback=True,
            requires_permission_to_run=False,
            num_trials=self.num_trials,
        )
        self.best_programs = sorted(
            self.results.candidate_programs,
            key=lambda item: item["score"],
            reverse=True,
        )
        self.module = self.get_best().details["program"]
        return self.get_best()

    def get_best(self, position: int = 0) -> OptimizationResult:
        score = self.best_programs[position]["score"]
        state = self.best_programs[position]["program"].dump_state()
        if self.tools:
            tool_names = [tool.__name__ for tool in self.tools]
            tool_prompts = get_tool_prompts(
                tool_names, state["react"]["signature"]["instructions"]
            )
            best_prompt = state["react"]["signature"]["instructions"]
            demos = [x.toDict() for x in state["react"]["demos"]]
        else:
            tool_prompts = None
            best_prompt = state["signature"]["instructions"]
            demos = [x.toDict() for x in state["demos"]]

        return OptimizationResult(
            optimizer="MiproOptimizer",
            prompt=best_prompt,
            tool_prompts=tool_prompts,
            score=score,
            metric_name=self.opik_metric.name,
            demonstrations=demos,
            details={"program": self.best_programs[position]["program"]},
        )
