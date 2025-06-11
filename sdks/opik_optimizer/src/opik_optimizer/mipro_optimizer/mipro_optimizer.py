import os
import random
from datetime import datetime
from typing import Callable, Dict, List, Literal, Optional, Union

import dspy
import litellm
import opik
from litellm.caching import Cache
from opik import Dataset
from opik.evaluation import evaluate
from opik.integrations.dspy.callback import OpikCallback
from opik.opik_context import get_current_span_data

from ..optimization_result import OptimizationResult
from ..utils import optimization_context
from ..base_optimizer import BaseOptimizer
from ..optimization_config.configs import TaskConfig
from ..optimization_result import OptimizationResult
from ._lm import LM
from ._mipro_optimizer_v2 import MIPROv2
from .utils import (
    create_dspy_signature,
    create_dspy_training_set,
    get_tool_prompts,
    opik_metric_to_dspy,
)

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type="disk", disk_cache_dir=disk_cache_dir)

# Set up logging
import logging

logger = logging.getLogger(__name__)  # Inherits config from setup_logging


class MiproOptimizer(BaseOptimizer):
    def __init__(self, model, project_name: Optional[str] = None, verbose: int = 1, **model_kwargs):
        super().__init__(model, project_name, verbose=verbose, **model_kwargs)
        self.tools = []
        self.num_threads = self.model_kwargs.pop("num_threads", 6)
        self.model_kwargs["model"] = self.model
        # FIXME: add mipro_optimizer=True - It does not count the LLM calls made internally by DSPy during MiproOptimizer.optimizer.compile().
        self.lm = LM(**self.model_kwargs)
        opik_callback = OpikCallback(project_name=self.project_name, log_graph=True)
        dspy.configure(lm=self.lm, callbacks=[opik_callback])
        logger.debug(f"Initialized MiproOptimizer with model: {model}")

    def evaluate_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: Callable,
        task_config: TaskConfig,
        prompt: Optional[Union[str, dspy.Module, OptimizationResult]] = None,
        n_samples: int = 10,
        dataset_item_ids: Optional[List[str]] = None,
        experiment_config: Optional[Dict] = None,
        verbose: int = 1,
        **kwargs,
    ) -> float:
        """
        Compute the score of a prompt on dataset (or part thereof)

        Args:
            dataset: Opik dataset name or dataset
            metric: Metric function to optimize
            task_config: A TaskConfig instance
            prompt: The prompt to evaluate
            n_samples: number of items to test in the dataset
            dataset_item_ids: Optional list of dataset item IDs to evaluate
            experiment_config: Optional configuration for the experiment
            verbose: Verbosity level
            **kwargs: Additional arguments for evaluation

        Returns:
            Evaluation score
        """
        # FIMXE: call super when it is ready
        # FIXME: Intermediate values:
        self.llm_call_counter += 1
        input_key = task_config.input_dataset_fields[0]  # FIXME: allow all inputs
        output_key = task_config.output_dataset_field

        # Kwargs might contain n_samples, passed from run_benchmark.py
        n_samples = kwargs.pop("n_samples", None) # Get n_samples from kwargs if present

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

        # Robust n_samples handling for selecting dataset_item_ids
        dataset_items_for_eval = dataset.get_items()
        num_total_items = len(dataset_items_for_eval)
        dataset_item_ids_to_use = dataset_item_ids # Use provided IDs if any

        if n_samples is not None: # If n_samples is specified by the caller (run_benchmark.py)
            if dataset_item_ids is not None:
                # This case should ideally be an error or a clear precedence rule.
                # For now, let's assume if dataset_item_ids is provided, it takes precedence over n_samples.
                logger.warning("MiproOptimizer.evaluate_prompt: Both n_samples and dataset_item_ids provided. Using provided dataset_item_ids.")
                # dataset_item_ids_to_use is already dataset_item_ids
            elif n_samples > num_total_items:
                logger.warning(f"MiproOptimizer.evaluate_prompt: n_samples ({n_samples}) > total items ({num_total_items}). Using all {num_total_items} items.")
                dataset_item_ids_to_use = None # opik.evaluation.evaluate handles None as all items
            elif n_samples <= 0:
                logger.warning(f"MiproOptimizer.evaluate_prompt: n_samples ({n_samples}) is <= 0. Using all {num_total_items} items.")
                dataset_item_ids_to_use = None
            else:
                # n_samples is valid and dataset_item_ids was not provided, so sample now.
                all_ids = [item["id"] for item in dataset_items_for_eval]
                dataset_item_ids_to_use = random.sample(all_ids, n_samples)
                logger.info(f"MiproOptimizer.evaluate_prompt: Sampled {n_samples} items for evaluation.")
        else: # n_samples is None
            if dataset_item_ids is None:
                logger.info(f"MiproOptimizer.evaluate_prompt: n_samples is None and dataset_item_ids is None. Using all {num_total_items} items.")
            # dataset_item_ids_to_use is already dataset_item_ids (which could be None)

        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "tools": (
                    [f.__name__ for f in task_config.tools] if task_config.tools else []
                ),
                "metric": metric.__name__,
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
            dataset_item_ids=dataset_item_ids_to_use,
            project_name=self.project_name,
            experiment_config=experiment_config,
            verbose=verbose,
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
        metric: Callable,
        task_config: TaskConfig,
        num_candidates: int = 10,
        experiment_config: Optional[Dict] = None,
        num_trials: Optional[int] = 3,
        n_samples: Optional[int] = 10,
        auto: Optional[Literal["light", "medium", "heavy"]] = "light",
        **kwargs,
    ) -> OptimizationResult:
        self._opik_client = opik.Opik()
        with optimization_context(
                client=self._opik_client,
                dataset_name=dataset.name,
                objective_name=metric.__name__,
                metadata={"optimizer": self.__class__.__name__},
        ) as optimization:
            result = self._optimize_prompt(
                dataset=dataset,
                metric=metric,
                task_config=task_config,
                num_candidates=num_candidates,
                experiment_config=experiment_config,
                optimization_id=optimization.id if optimization is not None else None,
                num_trials=num_trials,
                n_samples=n_samples,
                auto=auto,
                **kwargs,
            )
            return result

    def _optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric: Callable,
        task_config: TaskConfig,
        num_candidates: int = 10,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        num_trials: Optional[int] = 3,
        n_samples: Optional[int] = 10,
        auto: Optional[Literal["light", "medium", "heavy"]] = "light",
        **kwargs,
    ) -> OptimizationResult:
        logger.info("Preparing MIPRO optimization...")
        self.prepare_optimize_prompt(
            dataset=dataset,
            metric=metric,
            task_config=task_config,
            num_candidates=num_candidates,
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            num_trials=num_trials,
            n_samples=n_samples,
            auto=auto,
            **kwargs,
        )
        logger.info("Starting MIPRO compilation...")
        result = self.continue_optimize_prompt()
        logger.info("MIPRO optimization complete.")
        return result

    def prepare_optimize_prompt(
        self,
        dataset,
        metric,
        task_config,
        num_candidates: int = 10,
        experiment_config: Optional[Dict] = None,
        optimization_id: Optional[str] = None,
        num_trials: Optional[int] = 3,
        n_samples: Optional[int] = 10,
        auto: Optional[Literal["light", "medium", "heavy"]] = "light",
        **kwargs,
    ) -> None:
        # FIXME: Intermediate values:
        self.llm_call_counter = 0
        prompt = task_config.instruction_prompt
        input_key = task_config.input_dataset_fields[0]  # FIXME: allow all
        output_key = task_config.output_dataset_field
        self.tools = task_config.tools
        self.num_candidates = num_candidates
        self.seed = 42
        self.input_key = input_key
        self.output_key = output_key
        self.prompt = prompt
        self.num_trials = num_trials
        self.n_samples = n_samples
        self.auto = auto

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

        self.trainset = create_dspy_training_set(self.dataset, self.input_key, self.n_samples)
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
                "metric": metric.__name__,
                "num_threads": self.num_threads,
                "num_candidates": self.num_candidates,
                "num_trials": self.num_trials,
                "dataset": dataset.name,
            },
        }

        # Initialize the optimizer:
        self.optimizer = MIPROv2(
            metric=self.metric_function,
            auto=self.auto,
            num_threads=self.num_threads,
            verbose=(self.verbose == 1),
            num_candidates=self.num_candidates,
            seed=self.seed,
            opik_prompt_task_config=task_config,
            opik_dataset=dataset,
            opik_project_name=self.project_name,
            opik_metric=metric,
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
        if not hasattr(self, 'optimizer') or not self.optimizer:
            raise RuntimeError("MiproOptimizer not prepared. Call prepare_optimize_prompt first.")

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

        mipro_history_processed = []
        # self.num_candidates is set in prepare_optimize_prompt, defaults to 10
        # If self.num_candidates is 0 or None, this logic might break or be odd.
        # Add a safeguard for num_candidates_per_round if self.num_candidates is not usable.
        num_candidates_per_round = self.num_candidates if hasattr(self, 'num_candidates') and self.num_candidates and self.num_candidates > 0 else 1

        for i, candidate_data in enumerate(self.results.candidate_programs):
            program_module = candidate_data.get("program")
            instruction = "N/A"
            if hasattr(program_module, 'signature') and hasattr(program_module.signature, 'instructions'):
                instruction = program_module.signature.instructions
            elif hasattr(program_module, 'extended_signature') and hasattr(program_module.extended_signature, 'instructions'):
                instruction = program_module.extended_signature.instructions
            elif hasattr(program_module, 'predictor') and hasattr(program_module.predictor, 'signature') and hasattr(program_module.predictor.signature, 'instructions'):
                instruction = program_module.predictor.signature.instructions

            # Remove R and C calculation for Mipro as its history is flat
            # current_round_number = (i // num_candidates_per_round) + 1
            # current_candidate_in_round = (i % num_candidates_per_round) + 1

            iter_detail = {
                "iteration": i + 1,
                # "round_number": current_round_number, # Remove round_number
                # "candidate_in_round": current_candidate_in_round, # Remove candidate_in_round
                "timestamp": datetime.now().isoformat(),
                "prompt_candidate": instruction,
                "parameters_used": {
                    "program_summary": str(program_module)[:500]
                },
                "scores": [], # Initialize scores list
                "tokens_used": None, # TODO: add tokens_used
                "cost": None, # TODO: add cost
                "duration_seconds": None, # TODO: add duration_seconds
            }

            current_score = candidate_data.get("score")
            metric_name_for_history = self.opik_metric.__name__

            # Unscale if it's a known 0-1 metric that MIPRO might scale to 0-100
            # For now, specifically targeting Levenshtein-like metrics
            if isinstance(current_score, (float, int)) and \
               ("levenshtein" in metric_name_for_history.lower() or "similarity" in metric_name_for_history.lower()):
                # Assuming scores like 32.4 are 0-1 scores scaled by 100
                if abs(current_score) > 1.0: # A simple check to see if it looks scaled
                    logger.debug(f"Mipro history: Unscaling score {current_score} for metric {metric_name_for_history} by dividing by 100.")
                    current_score /= 100.0
            
            iter_detail["scores"].append({
                "metric_name": metric_name_for_history,
                "score": current_score,
                "opik_evaluation_id": None # TODO: add opik_evaluation_id
            })
            mipro_history_processed.append(iter_detail)

        if not self.best_programs:
            logger.warning("MIPRO compile returned no candidate programs.")
            return OptimizationResult(
                optimizer="MiproOptimizer",
                prompt=[{"role": "user", "content": getattr(self, 'prompt', "Error: Initial prompt not found")}], 
                score=0.0,
                metric_name=self.opik_metric.__name__ if hasattr(self, 'opik_metric') else "unknown_metric",
                details={"error": "No candidate programs generated by MIPRO"},
                history=mipro_history_processed,
                llm_calls=self.lm.llm_call_counter
            )

        self.module = self.get_best().details["program"]
        best_program_details = self.get_best()
        
        # Unscale the main score if necessary, similar to history scores
        final_best_score = best_program_details.score
        final_metric_name = best_program_details.metric_name
        if isinstance(final_best_score, (float, int)) and \
           final_metric_name and \
           ("levenshtein" in final_metric_name.lower() or "similarity" in final_metric_name.lower()):
            if abs(final_best_score) > 1.0: # A simple check to see if it looks scaled
                logger.debug(f"Mipro main result: Unscaling score {final_best_score} for metric {final_metric_name} by dividing by 100.")
                final_best_score /= 100.0

        return OptimizationResult(
            optimizer="MiproOptimizer",
            prompt=best_program_details.prompt,
            tool_prompts=best_program_details.tool_prompts,
            score=final_best_score, # Use the potentially unscaled score
            metric_name=final_metric_name,
            demonstrations=best_program_details.demonstrations,
            details=best_program_details.details,
            history=mipro_history_processed,
            llm_calls=self.lm.llm_call_counter
        )

    def get_best(self, position: int = 0) -> OptimizationResult:
        if not hasattr(self, 'best_programs') or not self.best_programs:
            logger.error("get_best() called but no best_programs found. MIPRO compile might have failed or yielded no results.")
            return OptimizationResult(
                optimizer="MiproOptimizer",
                prompt=[{"role": "user", "content": getattr(self, 'prompt', "Error: Initial prompt not found")}], 
                score=0.0, 
                metric_name=getattr(self, 'opik_metric', None).name if hasattr(self, 'opik_metric') and self.opik_metric else "unknown_metric",
                details={"error": "No programs generated or compile failed"},
                history=[],
                llm_calls=self.lm.llm_call_counter
            )
            
        score = self.best_programs[position]["score"]
        program_module = self.best_programs[position]["program"]
        state = program_module.dump_state()
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

        print(best_prompt)
        return OptimizationResult(
            optimizer="MiproOptimizer",
            prompt=[{"role": "user", "content": best_prompt}],
            tool_prompts=tool_prompts,
            score=score,
            metric_name=self.opik_metric.__name__,
            demonstrations=demos,
            details={"program": program_module},
            llm_calls=self.lm.llm_call_counter
        )
