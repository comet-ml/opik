from typing import List, Dict, Any, Optional, Union
import opik
from opik import Dataset
import litellm
from litellm.caching import Cache
import logging
import json
import os
from string import Template

from .optimization_config import mappers
from .optimization_config.configs import MetricConfig, TaskConfig
from .base_optimizer import BaseOptimizer, OptimizationRound
from .optimization_result import OptimizationResult
from opik_optimizer import task_evaluator
from opik.api_objects import opik_client
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor
from opik.environment import get_tqdm_for_current_environment
from . import _throttle

tqdm = get_tqdm_for_current_environment()

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type="disk", disk_cache_dir=disk_cache_dir)

# Set up logging
logger = logging.getLogger(__name__)  # Gets logger configured by setup_logging

_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class MetaPromptOptimizer(BaseOptimizer):
    """Optimizer that uses meta-prompting to improve prompts based on examples and performance."""

    # --- Constants for Default Configuration ---
    DEFAULT_MAX_ROUNDS = 3
    DEFAULT_PROMPTS_PER_ROUND = 4
    DEFAULT_IMPROVEMENT_THRESHOLD = 0.05
    DEFAULT_INITIAL_TRIALS = 3
    DEFAULT_MAX_TRIALS = 6
    DEFAULT_ADAPTIVE_THRESHOLD = 0.8  # Set to None to disable adaptive trials

    # --- Reasoning System Prompt ---
    _REASONING_SYSTEM_PROMPT = """You are an expert prompt engineer. Your task is to improve prompts for any type of task.
        Focus on making the prompt more effective by:
        1. Being clear and specific about what is expected
        2. Providing necessary context and constraints
        3. Guiding the model to produce the desired output format
        4. Removing ambiguity and unnecessary elements
        5. Maintaining conciseness while being complete

        Return a JSON array of prompts with the following structure:
        {
            "prompts": [
                {
                    "prompt": "the improved prompt text",
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                }
            ]
        }"""

    # --- Constants for Default Configuration ---
    DEFAULT_MAX_ROUNDS = 3
    DEFAULT_PROMPTS_PER_ROUND = 4
    DEFAULT_IMPROVEMENT_THRESHOLD = 0.05
    DEFAULT_INITIAL_TRIALS = 3
    DEFAULT_MAX_TRIALS = 6
    DEFAULT_ADAPTIVE_THRESHOLD = 0.8  # Set to None to disable adaptive trials

    # --- Reasoning System Prompt ---
    _REASONING_SYSTEM_PROMPT = """You are an expert prompt engineer. Your task is to improve prompts for any type of task.
        Focus on making the prompt more effective by:
        1. Being clear and specific about what is expected
        2. Providing necessary context and constraints
        3. Guiding the model to produce the desired output format
        4. Removing ambiguity and unnecessary elements
        5. Maintaining conciseness while being complete

        Return a JSON array of prompts with the following structure:
        {
            "prompts": [
                {
                    "prompt": "the improved prompt text",
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                }
            ]
        }"""

    def __init__(
        self,
        model: str,
        reasoning_model: str = None,
        max_rounds: int = DEFAULT_MAX_ROUNDS,
        num_prompts_per_round: int = DEFAULT_PROMPTS_PER_ROUND,
        improvement_threshold: float = DEFAULT_IMPROVEMENT_THRESHOLD,
        initial_trials_per_candidate: int = DEFAULT_INITIAL_TRIALS,
        max_trials_per_candidate: int = DEFAULT_MAX_TRIALS,
        adaptive_trial_threshold: Optional[float] = DEFAULT_ADAPTIVE_THRESHOLD,
        num_threads: int = 12,
        project_name: Optional[str] = None,
        verbose: int = 1,
        enable_context: bool = True,
        **model_kwargs,
    ):
        """
        Initialize the MetaPromptOptimizer.

        Args:
            model: The model to use for evaluation
            reasoning_model: The model to use for reasoning and prompt generation
            max_rounds: Maximum number of optimization rounds
            num_prompts_per_round: Number of prompts to generate per round
            improvement_threshold: Minimum improvement required to continue
            initial_trials_per_candidate: Number of initial evaluation trials for each candidate prompt.
            max_trials_per_candidate: Maximum number of evaluation trials if adaptive trials are enabled and score is promising.
            adaptive_trial_threshold: If not None, prompts scoring below `best_score * adaptive_trial_threshold` after initial trials won't get max trials.
            num_threads: Number of threads for parallel evaluation
            project_name: Optional project name for tracking
            verbose: Controls internal logging/progress bars (0=off, 1=on).
            enable_context: Whether to include task-specific context (metrics, examples) in the reasoning prompt.
            **model_kwargs: Additional model parameters
        """
        super().__init__(model=model, project_name=project_name, **model_kwargs)
        self.reasoning_model = reasoning_model if reasoning_model is not None else model
        self.max_rounds = max_rounds
        self.num_prompts_per_round = num_prompts_per_round
        self.improvement_threshold = improvement_threshold
        self.initial_trials = initial_trials_per_candidate
        self.max_trials = max_trials_per_candidate
        self.adaptive_threshold = adaptive_trial_threshold
        self.num_threads = num_threads
        self.verbose = verbose
        self.dataset = None
        self.task_config = None
        self._opik_client = opik_client.get_client_cached()
        self.llm_call_counter = 0
        self.enable_context = enable_context
        logger.debug(
            f"Initialized MetaPromptOptimizer with model={model}, reasoning_model={self.reasoning_model}"
        )
        logger.debug(
            f"Optimization rounds: {max_rounds}, Prompts/round: {num_prompts_per_round}"
        )
        logger.debug(
            f"Trials config: Initial={self.initial_trials}, Max={self.max_trials}, Adaptive Threshold={self.adaptive_threshold}"
        )

    def evaluate_prompt(
        self,
        dataset: opik.Dataset,
        metric_config: MetricConfig,
        task_config: TaskConfig,
        prompt: str,
        use_full_dataset: bool = False,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
        optimization_id: Optional[str] = None,
        verbose: int = 1,
    ) -> float:
        """
        Evaluate a prompt using the given dataset and metric configuration.

        Args:
            dataset: The dataset to evaluate against
            metric_config: The metric configuration to use for evaluation
            task_config: The task configuration containing input/output fields
            prompt: The prompt to evaluate
            use_full_dataset: Whether to use the full dataset or a subset for evaluation
            experiment_config: A dictionary to log with the experiments
            n_samples: The number of dataset items to use for evaluation
            optimization_id: Optional ID for tracking the optimization run

        Returns:
            float: The evaluation score
        """
        return self._evaluate_prompt(
            dataset=dataset,
            metric_config=metric_config,
            task_config=task_config,
            prompt=prompt,
            use_full_dataset=use_full_dataset,
            experiment_config=experiment_config,
            n_samples=n_samples,
            optimization_id=optimization_id,
            verbose=self.verbose,
        )

    @_throttle.rate_limited(_rate_limiter)
    def _call_model(
        self,
        prompt: str,
        system_prompt: Optional[str] = None,
        is_reasoning: bool = False,
        optimization_id: Optional[str] = None,
    ) -> str:
        """Call the model with the given prompt and return the response."""
        self.llm_call_counter += 1
        # Note: Basic retry logic could be added here using tenacity
        try:
            # Basic LLM parameters (e.g., temperature, max_tokens)
            base_temperature = getattr(self, "temperature", 0.3)
            base_max_tokens = getattr(self, "max_tokens", 1000)

            # Use potentially different settings for reasoning calls
            reasoning_temperature = base_temperature # Keep same temp unless specified otherwise
            # Increase max_tokens for reasoning to ensure JSON fits, unless already high
            reasoning_max_tokens = max(base_max_tokens, 3000) if is_reasoning else base_max_tokens 

            llm_config_params = {
                "temperature": reasoning_temperature if is_reasoning else base_temperature,
                "max_tokens": reasoning_max_tokens,
                "top_p": getattr(self, "top_p", 1.0),
                "frequency_penalty": getattr(self, "frequency_penalty", 0.0),
                "presence_penalty": getattr(self, "presence_penalty", 0.0),
            }

            # Prepare metadata that we want to be part of the LLM call context.
            metadata_for_opik = {}
            if self.project_name:
                metadata_for_opik["project_name"] = (
                    self.project_name
                )  # Top-level for general use
                metadata_for_opik["opik"] = {"project_name": self.project_name}

            if optimization_id:
                # Also add to opik-specific structure if project_name was added
                if "opik" in metadata_for_opik:
                    metadata_for_opik["opik"]["optimization_id"] = optimization_id

            metadata_for_opik["optimizer_name"] = self.__class__.__name__
            metadata_for_opik["opik_call_type"] = (
                "reasoning" if is_reasoning else "evaluation_llm_task_direct"
            )

            if metadata_for_opik:
                llm_config_params["metadata"] = metadata_for_opik

            messages = []
            if system_prompt and (
                is_reasoning or getattr(self.task_config, "use_chat_prompt", False)
            ):
                messages.append({"role": "system", "content": system_prompt})
            messages.append({"role": "user", "content": prompt})

            model_to_use = self.reasoning_model if is_reasoning else self.model

            # Pass llm_config_params (which now includes our metadata) to the Opik monitor.
            # The monitor is expected to return a dictionary suitable for spreading into litellm.completion,
            # having handled our metadata and added any Opik-specific configurations.
            final_call_params = opik_litellm_monitor.try_add_opik_monitoring_to_params(
                llm_config_params.copy()
            )

            logger.debug(
                f"Calling model '{model_to_use}' with messages: {messages}, "
                f"final params for litellm (from monitor): {final_call_params}"
            )

            response = litellm.completion(
                model=model_to_use, 
                messages=messages, 
                num_retries=6,
                **final_call_params
            )
            return response.choices[0].message.content
        except litellm.exceptions.RateLimitError as e:
            logger.error(f"LiteLLM Rate Limit Error: {e}")
            raise
        except litellm.exceptions.APIConnectionError as e:
            logger.error(f"LiteLLM API Connection Error: {e}")
            raise
        except litellm.exceptions.ContextWindowExceededError as e:
            logger.error(f"LiteLLM Context Window Exceeded Error: {e}")
            # Log prompt length if possible? Needs access to prompt_for_llm here.
            raise
        except Exception as e:
            logger.error(
                f"Error calling model '{model_to_use}': {type(e).__name__} - {e}"
            )
            raise

    def _evaluate_prompt(
        self,
        dataset: opik.Dataset,
        metric_config: MetricConfig,
        task_config: TaskConfig,
        prompt: str,
        use_full_dataset: bool,
        experiment_config: Optional[Dict],
        n_samples: Optional[int],
        optimization_id: Optional[str] = None,
        verbose: int = 1,
    ) -> float:
        # Calculate subset size for trials
        if not use_full_dataset:
            total_items = len(dataset.get_items())
            if n_samples is not None:
                if n_samples > total_items:
                    logger.warning(
                        f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset."
                    )
                    subset_size = None
                else:
                    subset_size = n_samples
                    logger.debug(f"Using specified n_samples: {subset_size} items")
            else:
                # Calculate 20% of total, but no more than 20 items and no more than total items
                subset_size = min(total_items, min(20, max(10, int(total_items * 0.2))))
                logger.debug(
                    f"Using automatic subset size calculation: {subset_size} items (20% of {total_items} total items)"
                )
        else:
            subset_size = None  # Use all items for final checks
            logger.debug("Using full dataset for evaluation")
        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": metric_config.metric.name,
                "dataset": dataset.name,
                "configuration": {
                    "prompt": prompt,
                    "n_samples": subset_size,
                    "use_full_dataset": use_full_dataset,
                },
            },
        }

        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, str]:
            # Convert DatasetItem to dict if needed
            if hasattr(dataset_item, "to_dict"):
                dataset_item = dataset_item.to_dict()

            # Validate that input and output fields are in the dataset_item
            for input_key in task_config.input_dataset_fields:
                if input_key not in dataset_item:
                    logger.error(
                        f"Input field '{input_key}' not found in dataset sample: {dataset_item}"
                    )
                    raise ValueError(
                        f"Input field '{input_key}' not found in dataset sample"
                    )
            if task_config.output_dataset_field not in dataset_item:
                logger.error(
                    f"Output field '{task_config.output_dataset_field}' not found in dataset sample: {dataset_item}"
                )
                raise ValueError(
                    f"Output field '{task_config.output_dataset_field}' not found in dataset sample"
                )

            # --- Step 1: Prepare the prompt for the LLM ---
            prompt_for_llm: str
            field_mapping = {
                field: dataset_item[field]
                for field in task_config.input_dataset_fields
                if field in dataset_item
            }

            if getattr(task_config, "use_chat_prompt", False):
                # For chat prompts, the candidate prompt `prompt` is expected to be a template for the user message.
                # We assume it contains placeholders like {question} or {text}.
                candidate_template = Template(prompt)
                prompt_for_llm = candidate_template.safe_substitute(field_mapping)
            else:
                # For non-chat prompts, `prompt` (the candidate/initial prompt) is the base instruction.
                # Append the actual data fields to it.
                input_clauses = []
                for field_name in task_config.input_dataset_fields:
                    if field_name in dataset_item:
                        input_clauses.append(
                            f"{field_name.capitalize()}: {dataset_item[field_name]}"
                        )
                item_specific_inputs_str = "\n".join(input_clauses)
                prompt_for_llm = f"{prompt}\n\n{item_specific_inputs_str}"

            logger.debug(f"Evaluating with inputs: {field_mapping}")
            logger.debug(f"Prompt for LLM: {prompt_for_llm}")

            # --- Step 2: Call the model ---
            try:
                logger.debug(f"Calling LLM with prompt length: {len(prompt_for_llm)}")
                raw_model_output = self._call_model(
                    prompt=prompt_for_llm,
                    system_prompt=None,
                    is_reasoning=False,
                    optimization_id=optimization_id,
                )
                logger.debug(f"LLM raw response length: {len(raw_model_output)}")
                logger.debug(f"LLM raw output: {raw_model_output}")
            except Exception as e:
                logger.error(f"Error calling model with prompt: {e}")
                logger.error(f"Failed prompt: {prompt_for_llm}")
                logger.error(f"Prompt length: {len(prompt_for_llm)}")
                raise

            # --- Step 3: Clean the model's output before metric evaluation ---
            cleaned_model_output = raw_model_output.strip()
            original_cleaned_output = cleaned_model_output  # For logging if changed

            # Dynamically generate prefixes based on the output field name
            output_field = task_config.output_dataset_field  # e.g., "answer" or "label"
            dynamic_prefixes = [
                f"{output_field.capitalize()}:",
                f"{output_field.capitalize()} :",
                f"{output_field}:",  # Also check lowercase field name
                f"{output_field} :",
            ]

            # Add common generic prefixes
            generic_prefixes = ["Answer:", "Answer :", "A:"]

            # Combine and remove duplicates (if any)
            prefixes_to_strip = list(set(dynamic_prefixes + generic_prefixes))
            logger.debug(f"Prefixes to strip: {prefixes_to_strip}")

            for prefix_to_check in prefixes_to_strip:
                # Perform case-insensitive check for robustness
                if cleaned_model_output.lower().startswith(prefix_to_check.lower()):
                    # Strip based on the actual length of the found prefix
                    cleaned_model_output = cleaned_model_output[
                        len(prefix_to_check) :
                    ].strip()
                    logger.debug(
                        f"Stripped prefix '{prefix_to_check}', new output for metric: {cleaned_model_output}"
                    )
                    break  # Stop after stripping the first found prefix

            if original_cleaned_output != cleaned_model_output:
                logger.debug(
                    f"Raw model output: '{original_cleaned_output}' -> Cleaned for metric: '{cleaned_model_output}'"
                )
            result = {
                mappers.EVALUATED_LLM_TASK_OUTPUT: cleaned_model_output,
            }
            return result

        # Use dataset's get_items with limit for sampling
        logger.info(
            f"Starting evaluation with {subset_size if subset_size else 'all'} samples for metric: {metric_config.metric.name}"
        )
        score = task_evaluator.evaluate(
            dataset=dataset,
            metric_config=metric_config,
            evaluated_task=llm_task,
            num_threads=self.num_threads,
            project_name=self.project_name,
            n_samples=subset_size,  # Use subset_size for trials, None for full dataset
            experiment_config=experiment_config,
            optimization_id=optimization_id,
            verbose=self.verbose,
        )
        logger.debug(f"Evaluation score: {score:.4f}")
        return score

    def optimize_prompt(
        self,
        dataset: Union[str, Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        experiment_config: Optional[Dict] = None,
        n_samples: int = None,
        auto_continue: bool = False,
        **kwargs,
    ) -> OptimizationResult:
        """
        Optimize a prompt using meta-reasoning.

        Args:
            dataset: The dataset to evaluate against
            metric_config: The metric configuration to use for evaluation
            task_config: The task configuration containing input/output fields
            experiment_config: A dictionary to log with the experiments
            n_samples: The number of dataset items to use for evaluation
            auto_continue: If True, the algorithm may continue if goal not met
            **kwargs: Additional arguments for evaluation

        Returns:
            OptimizationResult: Structured result containing optimization details
        """
        total_items = len(dataset.get_items())
        if n_samples is not None and n_samples > total_items:
            logger.warning(
                f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset."
            )
            n_samples = None

        logger.info(
            f"Starting optimization with n_samples={n_samples}, auto_continue={auto_continue}"
        )
        logger.info(f"Dataset size: {total_items} items")
        logger.info(f"Initial prompt: {task_config.instruction_prompt}")

        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric_config.metric.name,
                metadata={"optimizer": self.__class__.__name__},
            )
            logger.info(f"Created optimization with ID: {optimization.id}")
        except Exception as e:
            logger.warning(
                f"Opik server does not support optimizations: {e}. Please upgrade opik."
            )
            optimization = None

        try:
            result = self._optimize_prompt(
                optimization_id=optimization.id if optimization is not None else None,
                dataset=dataset,
                metric_config=metric_config,
                task_config=task_config,
                experiment_config=experiment_config,
                n_samples=n_samples,
                auto_continue=auto_continue,
                **kwargs,
            )
            if optimization:
                self.update_optimization(optimization, status="completed")
                logger.info("Optimization completed successfully")
            return result
        except Exception as e:
            logger.error(f"Optimization failed: {e}")
            if optimization:
                self.update_optimization(optimization, status="cancelled")
                logger.info("Optimization marked as cancelled")
            raise e

    def _optimize_prompt(
        self,
        optimization_id: str,
        dataset: Union[str, Dataset],
        metric_config: MetricConfig,
        task_config: TaskConfig,
        experiment_config: Optional[Dict],
        n_samples: int,
        auto_continue: bool,
        **kwargs,
    ) -> OptimizationResult:
        self.auto_continue = auto_continue
        self.dataset = dataset
        self.task_config = task_config
        self.llm_call_counter = 0 # Reset counter for run

        current_prompt = task_config.instruction_prompt
        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": metric_config.metric.name,
                "dataset": self.dataset.name,
                "configuration": {
                    "prompt": current_prompt,
                    "max_rounds": self.max_rounds,
                    "num_prompts_per_round": self.num_prompts_per_round,
                    "improvement_threshold": self.improvement_threshold,
                    "initial_trials": self.initial_trials,
                    "max_trials": self.max_trials,
                    "adaptive_threshold": self.adaptive_threshold,
                },
            },
        }

        logger.info("Evaluating initial prompt")
        initial_score = self.evaluate_prompt(
            optimization_id=optimization_id,
            dataset=dataset,
            metric_config=metric_config,
            task_config=task_config,
            prompt=current_prompt,
            n_samples=n_samples,
            experiment_config=experiment_config,
            use_full_dataset=n_samples is None,
            verbose=self.verbose,
        )
        best_score = initial_score
        best_prompt = current_prompt
        rounds = []
        stopped_early = False

        logger.info(f"Initial score: {initial_score:.4f}")

        # Initialize TQDM with postfix placeholder
        pbar = tqdm(
            total=self.max_rounds,
            desc="Optimizing Prompt",
            unit="round",
            bar_format="{l_bar}{bar:20}{r_bar} | {n_fmt}/{total_fmt} [{elapsed}<{remaining}, {rate_fmt}{postfix}]",
            position=0,
            leave=True,
            postfix={
                "best_score": f"{initial_score:.4f}",
                "llm_calls": self.llm_call_counter,
            },
        )

        for round_num in range(self.max_rounds):
            logger.info(f"\n{'='*50}")
            logger.info(f"Starting Round {round_num + 1}/{self.max_rounds}")
            logger.info(f"Current best score: {best_score:.4f}")
            logger.info(f"Current best prompt: {best_prompt}")

            previous_best_score = best_score
            try:
                logger.info("Generating candidate prompts")
                candidate_prompts = self._generate_candidate_prompts(
                    current_prompt=best_prompt,
                    best_score=best_score,
                    round_num=round_num,
                    previous_rounds=rounds,
                    metric_config=metric_config,
                    optimization_id=optimization_id,
                )
                logger.info(f"Generated {len(candidate_prompts)} candidate prompts")
            except Exception as e:
                logger.error(f"Error generating candidate prompts: {e}")
                break

            prompt_scores = []
            for candidate_count, prompt in enumerate(candidate_prompts):
                logger.info(
                    f"\nEvaluating candidate {candidate_count + 1}/{len(candidate_prompts)}"
                )
                logger.info(f"Prompt: {prompt}")

                scores = []
                should_run_max_trials = True

                # Initial trials
                logger.debug(f"Running initial {self.initial_trials} trials...")
                for trial in range(self.initial_trials):
                    try:
                        logger.debug(f"Trial {trial + 1}/{self.initial_trials}")
                        score = self.evaluate_prompt(
                            dataset=dataset,
                            metric_config=metric_config,
                            task_config=task_config,
                            prompt=prompt,
                            n_samples=n_samples,
                            use_full_dataset=False,
                            experiment_config=experiment_config,
                            verbose=self.verbose,
                        )
                        scores.append(score)
                        logger.debug(f"Trial {trial+1} score: {score:.4f}")
                    except Exception as e:
                        logger.error(f"Error in trial {trial + 1}: {e}")
                        continue

                if not scores:
                    logger.warning(
                        "All initial trials failed for this prompt, skipping"
                    )
                    continue

                # Adaptive trials logic
                avg_score_initial = sum(scores) / len(scores)
                if (
                    self.adaptive_threshold is not None
                    and self.max_trials > self.initial_trials
                    and avg_score_initial < best_score * self.adaptive_threshold
                ):
                    should_run_max_trials = False
                    logger.debug("Skipping additional trials...")

                # Run additional trials
                if should_run_max_trials and self.max_trials > self.initial_trials:
                    num_additional_trials = self.max_trials - self.initial_trials
                    logger.debug(
                        f"Running {num_additional_trials} additional trials..."
                    )
                    for trial in range(self.initial_trials, self.max_trials):
                        try:
                            logger.debug(
                                f"Additional trial {trial + 1}/{self.max_trials}"
                            )
                            score = self.evaluate_prompt(
                                dataset=dataset,
                                metric_config=metric_config,
                                task_config=task_config,
                                prompt=prompt,
                                n_samples=n_samples,
                                use_full_dataset=False,
                                experiment_config=experiment_config,
                                verbose=self.verbose,
                            )
                            scores.append(score)
                            logger.debug(
                                f"Additional trial {trial+1} score: {score:.4f}"
                            )
                        except Exception as e:
                            logger.error(f"Error in additional trial {trial + 1}: {e}")
                            continue

                # Calculate final average score
                if scores:
                    final_avg_score = sum(scores) / len(scores)
                    prompt_scores.append((prompt, final_avg_score, scores))
                    logger.info(f"Completed {len(scores)} trials for prompt.")
                    logger.info(f"Final average score: {final_avg_score:.4f}")
                    logger.debug(
                        f"Individual trial scores: {[f'{s:.4f}' for s in scores]}"
                    )
                else:
                    # This case should be rare now due to the initial check, but good practice
                    logger.warning("No successful trials completed for this prompt.")

            if not prompt_scores:
                logger.warning("No prompts were successfully evaluated in this round")
                break

            # Sort by float score
            prompt_scores.sort(key=lambda x: x[1], reverse=True)
            best_candidate_this_round, best_cand_score_avg, best_cand_trials = (
                prompt_scores[0]
            )

            logger.info(
                f"\nBest candidate from this round (avg score {metric_config.metric.name}): {best_cand_score_avg:.4f}"
            )
            logger.info(f"Prompt: {best_candidate_this_round}")

            # Re-evaluate the best candidate from the round using the full dataset (if n_samples is None)
            # or the specified n_samples subset for a more stable score comparison.
            # This uses use_full_dataset flag appropriately.
            if best_cand_score_avg > best_score:
                logger.info("Running final evaluation on best candidate...")
                final_score_best_cand = self.evaluate_prompt(
                    optimization_id=optimization_id,
                    dataset=dataset,
                    metric_config=metric_config,
                    task_config=task_config,
                    prompt=best_candidate_this_round,
                    experiment_config=experiment_config,
                    n_samples=n_samples,
                    use_full_dataset=n_samples is None,
                    verbose=self.verbose,
                )
                logger.info(
                    f"Final evaluation score for best candidate: {final_score_best_cand:.4f}"
                )

                if final_score_best_cand > best_score:
                    logger.info(f"New best prompt found!")
                    best_score = final_score_best_cand
                    best_prompt = best_candidate_this_round
                    logger.info(f"New Best Prompt: {best_prompt}")
                    logger.info(
                        f"New Best Score ({metric_config.metric.name}): {best_score:.4f}"
                    )
                else:
                    logger.info(
                        "Best candidate score did not improve upon final evaluation."
                    )
            # Decide what prompt to carry to the next round's generation step.
            # Option 1: Carry the best scoring prompt overall (best_prompt)
            # Option 2: Carry the best candidate from this round (best_candidate_this_round) even if it didn't beat the overall best after final eval.
            # Let's stick with Option 1 for now - always generate from the overall best.
            # current_prompt = best_prompt # Implicitly done as best_prompt is updated

            improvement = self._calculate_improvement(best_score, previous_best_score)
            logger.info(
                f"Improvement in score ({metric_config.metric.name}) this round: {improvement:.2%}"
            )

            # Create round data
            round_data = self._create_round_data(
                round_num,
                best_prompt,
                best_score,
                best_prompt,
                prompt_scores,
                previous_best_score,
                improvement,
            )
            rounds.append(round_data)
            self._add_to_history(round_data.model_dump())

            if (
                improvement < self.improvement_threshold and round_num > 0
            ):  # Avoid stopping after first round if threshold is low
                logger.info(
                    f"Improvement below threshold ({improvement:.2%} < {self.improvement_threshold:.2%}), stopping early"
                )
                stopped_early = True
                break

            # Update TQDM postfix
            pbar.set_postfix(
                {
                    "best_score": f"{best_score:.4f}",
                    "improvement": f"{improvement:.2%}",
                    "llm_calls": self.llm_call_counter,
                }
            )
            pbar.update(1)

        pbar.close()

        logger.info("\n" + "=" * 80)
        logger.info("OPTIMIZATION COMPLETE")
        logger.info("=" * 80)
        logger.info(f"Initial score: {initial_score:.4f}")
        logger.info(f"Final best score: {best_score:.4f}")
        if initial_score != 0:  # Avoid division by zero if initial score was 0
            total_improvement_pct = (best_score - initial_score) / abs(
                initial_score
            )  # Use abs for safety
            logger.info(f"Total improvement: {total_improvement_pct:.2%}")
        elif best_score > 0:
            logger.info("Total improvement: infinite (initial score was 0)")
        else:
            logger.info("Total improvement: 0.00% (scores did not improve from 0)")
        logger.info("\nFINAL OPTIMIZED PROMPT:")
        logger.info("-" * 80)
        logger.info(best_prompt)
        logger.info("-" * 80)
        logger.info("=" * 80)

        return self._create_result(
            metric_config,
            task_config,
            best_prompt,
            best_score,
            initial_score,
            rounds,
            stopped_early,
        )

    def _calculate_improvement(
        self, current_score: float, previous_score: float
    ) -> float:
        """Calculate the improvement percentage between scores."""
        return (
            (current_score - previous_score) / previous_score
            if previous_score > 0
            else 0
        )

    def _create_round_data(
        self,
        round_num: int,
        current_best_prompt: str,
        current_best_score: float,
        best_prompt_overall: str,
        evaluated_candidates: List[tuple[str, float, List[float]]],
        previous_best_score: float,
        improvement_this_round: float,
    ) -> OptimizationRound:
        """Create an OptimizationRound object with the current round's data."""
        generated_prompts_log = []
        for prompt, avg_score, trial_scores in evaluated_candidates:
            improvement_vs_prev = self._calculate_improvement(
                avg_score, previous_best_score
            )
            generated_prompts_log.append(
                {
                    "prompt": prompt,
                    "score": avg_score,
                    "trial_scores": trial_scores,
                    "improvement": improvement_vs_prev,
                }
            )

        return OptimizationRound(
            round_number=round_num + 1,
            current_prompt=current_best_prompt,
            current_score=current_best_score,
            generated_prompts=generated_prompts_log,
            best_prompt=best_prompt_overall,
            best_score=current_best_score,
            improvement=improvement_this_round,
        )

    def _create_result(
        self,
        metric_config: MetricConfig,
        task_config: TaskConfig,
        best_prompt: str,
        best_score: float,
        initial_score: float,
        rounds: List[OptimizationRound],
        stopped_early: bool,
    ) -> OptimizationResult:
        """Create the final OptimizationResult object."""
        details = {
            "prompt_type": "chat" if task_config.use_chat_prompt else "non-chat",
            "initial_prompt": task_config.instruction_prompt,
            "initial_score": initial_score,
            "final_prompt": best_prompt,
            "final_score": best_score,
            "rounds": rounds,
            "total_rounds": len(rounds),
            "stopped_early": stopped_early,
            "metric_config": metric_config.model_dump(),
            "task_config": task_config.model_dump(),
            "model": self.model,
            "temperature": self.model_kwargs.get("temperature"),
        }

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=best_prompt,
            score=best_score,
            metric_name=metric_config.metric.name,
            details=details,
            llm_calls=self.llm_call_counter
        )

    def _get_task_context(self, metric_config: MetricConfig) -> str:
        """Get task-specific context from the dataset and metric configuration."""
        if self.dataset is None or self.task_config is None:
            return ""

        input_fields = self.task_config.input_dataset_fields
        output_field = self.task_config.output_dataset_field

        # Describe Single Metric
        metric_name = metric_config.metric.name
        description = getattr(
            metric_config.metric, "description", "No description available."
        )
        goal = (
            "higher is better"
            if getattr(metric_config.metric, "higher_is_better", True)
            else "lower is better"
        )
        metrics_str = f"- {metric_name}: {description} ({goal})"

        context = "\nTask Context:\n"
        context += f"Input fields: {', '.join(input_fields)}\n"
        context += f"Output field: {output_field}\n"
        context += f"Evaluation Metric:\n{metrics_str}\n"

        try:
            # Try get_items() first as it's the preferred method
            items = self.dataset.get_items()
            if items:
                sample = items[0]  # Get first sample
            else:
                # Fallback to other methods if get_items() fails or returns empty
                if hasattr(self.dataset, "samples") and self.dataset.samples:
                    sample = self.dataset.samples[0]  # Get first sample
                elif hasattr(self.dataset, "__iter__"):
                    sample = next(iter(self.dataset))
                else:
                    logger.warning(
                        "Dataset does not have a samples attribute or is not iterable"
                    )
                    return context

            if sample is not None:
                context += "\nExample:\n"
                for field in input_fields:
                    if field in sample:
                        context += f"Input '{field}': {sample[field]}\n"
                if output_field in sample:
                    context += f"Output '{output_field}': {sample[output_field]}\n"
        except Exception as e:
            logger.warning(f"Could not get sample from dataset: {e}")

        return context

    def _generate_candidate_prompts(
        self,
        current_prompt: str,
        best_score: float,
        round_num: int,
        previous_rounds: List[OptimizationRound],
        metric_config: MetricConfig,
        optimization_id: Optional[str] = None,
    ) -> List[str]:
        """Generate candidate prompts using meta-prompting."""

        logger.debug(f"\nGenerating candidate prompts for round {round_num + 1}")
        logger.debug(f"Generating from prompt: {current_prompt}")
        logger.debug(f"Current best score: {best_score:.4f}")

        # Pass single metric_config
        history_context = self._build_history_context(previous_rounds)
        task_context_str = ""
        analysis_instruction = ""
        metric_focus_instruction = ""
        improvement_point_1 = ""

        if self.enable_context:
            task_context_str = self._get_task_context(metric_config=metric_config)
            analysis_instruction = "Analyze the example provided (if any), the metric description (if any), and the history of scores."
            metric_focus_instruction = f"Focus on improving the score for the metric: {metric_config.metric.name}."
            improvement_point_1 = "1. Be more specific and clear about expectations based on the metric and task."
            logger.debug("Task context and metric-specific instructions enabled for reasoning prompt.")
        else:
            analysis_instruction = "Analyze the history of scores and the current prompt\'s performance."
            metric_focus_instruction = "Focus on generating diverse and effective prompt variations based on the history."
            improvement_point_1 = "1. Be more specific and clear about expectations based on the task."
            logger.debug("Task context and metric-specific instructions disabled for reasoning prompt.")

        user_prompt = f"""Current prompt: {current_prompt}
        Current score: {best_score}
        {history_context}
        {task_context_str}

        {analysis_instruction}
        Generate {self.num_prompts_per_round} improved versions of this prompt.
        {metric_focus_instruction}
        Each version should aim to:
        {improvement_point_1}
        2. Provide necessary context and constraints (if applicable, without relying on disabled external context).
        3. Guide the model to produce the desired output format suitable for the task.
        4. Remove ambiguity and unnecessary elements.
        5. Maintain conciseness while being complete.

        Return a valid JSON array as specified."""

        try:
            # Use _call_model which handles selecting reasoning_model
            content = self._call_model(
                prompt=user_prompt,
                system_prompt=self._REASONING_SYSTEM_PROMPT,
                is_reasoning=True,
                optimization_id=optimization_id,
            )
            logger.debug(f"Raw response from reasoning model: {content}")

            # --- Robust JSON Parsing and Validation ---
            json_result = None
            try:
                # Try direct JSON parsing
                json_result = json.loads(content)
            except json.JSONDecodeError:
                # If direct fails, try regex extraction
                logger.warning(
                    "Direct JSON parsing failed, attempting regex extraction."
                )
                import re

                json_match = re.search(r"\{.*\}", content, re.DOTALL)
                if json_match:
                    try:
                        json_result = json.loads(json_match.group())
                    except json.JSONDecodeError as e:
                        logger.error(f"Could not parse JSON extracted via regex: {e}")
                        return [current_prompt]  # Fallback
                else:
                    logger.error("No JSON object found in response via regex.")
                    return [current_prompt]  # Fallback

            # Validate the parsed JSON structure
            if not isinstance(json_result, dict) or "prompts" not in json_result:
                logger.error(
                    "Parsed JSON is not a dictionary or missing 'prompts' key."
                )
                logger.debug(f"Parsed JSON content: {json_result}")
                return [current_prompt]  # Fallback

            if not isinstance(json_result["prompts"], list):
                logger.error("'prompts' key does not contain a list.")
                logger.debug(f"Content of 'prompts': {json_result.get('prompts')}")
                return [current_prompt]  # Fallback

            # Extract and log valid prompts
            valid_prompts = []
            for item in json_result["prompts"]:
                if (
                    isinstance(item, dict)
                    and "prompt" in item
                    and isinstance(item["prompt"], str)
                ):
                    prompt_text = item["prompt"]
                    valid_prompts.append(prompt_text)
                    # Log details
                    focus = item.get("improvement_focus", "N/A")
                    reasoning = item.get("reasoning", "N/A")
                    logger.info(f"Generated prompt: {prompt_text}")
                    logger.info(f"  Improvement focus: {focus}")
                    logger.info(f"  Reasoning: {reasoning}")
                else:
                    logger.warning(
                        f"Skipping invalid prompt item structure in JSON response: {item}"
                    )

            if not valid_prompts:
                logger.warning(
                    "No valid prompts found in the parsed JSON response after validation."
                )
                return [current_prompt]  # Fallback

            return valid_prompts
            # --- End Robust Parsing ---

        except Exception as e:
            # Catch other errors during model call or processing
            logger.error(f"Unexpected error during candidate prompt generation: {e}")
            logger.error("Falling back to current prompt.")
            return [current_prompt]

    def _build_history_context(self, previous_rounds: List[OptimizationRound]) -> str:
        """Build context from previous optimization rounds."""
        if not previous_rounds:
            return ""

        context = "\nPrevious rounds (latest first):\n"
        for round_data in reversed(previous_rounds[-3:]):
            context += f"\nRound {round_data.round_number}:\n"
            context += f"Best score this round: {round_data.best_score:.4f}\n"
            context += "Generated prompts this round (best first):\n"

            sorted_generated = sorted(
                round_data.generated_prompts,
                key=lambda p: p.get("score", -float("inf")),
                reverse=True,
            )

            for p in sorted_generated[:3]:
                prompt_text = p.get("prompt", "N/A")
                score = p.get("score", float("nan"))
                context += f"- Prompt: {prompt_text[:150]}...\n"
                context += f"  Avg Score: {score:.4f}\n"
        return context

    def _get_evaluation_subset(
        self, dataset: opik.Dataset, min_size: int = 20, max_size: int = 100
    ) -> List[Dict[str, Any]]:
        """Get a random subset of the dataset for evaluation.

        Returns:
            List[Dict[str, Any]]: A list of dataset items to evaluate against
        """
        try:
            # Get all items from the dataset
            all_items = dataset.get_items()
            if not all_items:
                return all_items

            # Calculate subset size
            total_size = len(all_items)
            subset_size = min(max(min_size, int(total_size * 0.2)), max_size)

            # Get random subset of items
            import random

            return random.sample(all_items, subset_size)

        except Exception as e:
            logger.warning(f"Could not create evaluation subset: {e}")
            return all_items
