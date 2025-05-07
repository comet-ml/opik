from typing import List, Dict, Any, Optional, Union
import opik
from opik.evaluation import metrics
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
from .utils import get_tqdm
from opik_optimizer import task_evaluator
from opik.api_objects import opik_client

tqdm = get_tqdm()

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type="disk", disk_cache_dir=disk_cache_dir)

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Configure LiteLLM logging
litellm_logger = logging.getLogger("LiteLLM")
litellm_logger.setLevel(logging.WARNING)  # Only show warnings and errors from LiteLLM

# Configure HTTPX logging
httpx_logger = logging.getLogger("httpx")
httpx_logger.setLevel(logging.WARNING)  # Only show warnings and errors from HTTPX


class MetaPromptOptimizer(BaseOptimizer):
    """Optimizer that uses meta-prompting to improve prompts based on examples and performance."""

    def __init__(
        self,
        model: str,
        reasoning_model: str = None,
        max_rounds: int = 3,
        num_prompts_per_round: int = 4,
        improvement_threshold: float = 0.05,
        num_threads: int = 12,
        project_name: Optional[str] = None,
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
            num_threads: Number of threads for parallel evaluation
            project_name: Optional project name for tracking
            **model_kwargs: Additional model parameters
        """
        super().__init__(model=model, project_name=project_name, **model_kwargs)
        self.reasoning_model = reasoning_model if reasoning_model is not None else model
        self.max_rounds = max_rounds
        self.num_prompts_per_round = num_prompts_per_round
        self.improvement_threshold = improvement_threshold
        self.num_threads = num_threads
        self.dataset = None
        self.task_config = None
        self._opik_client = opik_client.get_client_cached()
        logger.info(
            f"Initialized MetaPromptOptimizer with model={model}, reasoning_model={reasoning_model}"
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
        )

    def _call_model(
        self,
        prompt: str,
        system_prompt: Optional[str] = None,
        is_reasoning: bool = False,
    ) -> str:
        """Call the model with the given prompt and return the response."""
        try:
            # Filter out non-model parameters
            model_params = {
                "temperature": getattr(self, "temperature", 0.7),
                "max_tokens": getattr(self, "max_tokens", 1000),
                "top_p": getattr(self, "top_p", 1.0),
                "frequency_penalty": getattr(self, "frequency_penalty", 0.0),
                "presence_penalty": getattr(self, "presence_penalty", 0.0),
            }

            # Handle chat prompts
            if hasattr(self, 'task_config') and getattr(self.task_config, 'use_chat_prompt', False):
                messages = []
                if system_prompt:
                    messages.append({"role": "system", "content": system_prompt})
                messages.append({"role": "user", "content": prompt})
            else:
                # For non-chat prompts, just use the prompt directly
                messages = [{"role": "user", "content": prompt}]

            model = self.reasoning_model if is_reasoning else self.model
            response = litellm.completion(
                model=model,
                messages=messages,
                **model_params
            )
            return response.choices[0].message.content
        except Exception as e:
            logger.error(f"Error calling model: {e}")
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
    ) -> float:
        # Calculate subset size for trials
        if not use_full_dataset:
            total_items = len(dataset.get_items())
            if n_samples is not None:
                if n_samples > total_items:
                    logger.warning(f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset.")
                    subset_size = None
                else:
                    subset_size = n_samples
                    logger.info(f"Using specified n_samples: {subset_size} items")
            else:
                # Calculate 20% of total, but no more than 20 items and no more than total items
                subset_size = min(total_items, min(20, max(10, int(total_items * 0.2))))
                logger.info(f"Using automatic subset size calculation: {subset_size} items (20% of {total_items} total items)")
        else:
            subset_size = None  # Use all items for final checks
            logger.info("Using full dataset for evaluation")

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
                    logger.error(f"Input field '{input_key}' not found in dataset sample: {dataset_item}")
                    raise ValueError(
                        f"Input field '{input_key}' not found in dataset sample"
                    )
            if task_config.output_dataset_field not in dataset_item:
                logger.error(f"Output field '{task_config.output_dataset_field}' not found in dataset sample: {dataset_item}")
                raise ValueError(
                    f"Output field '{task_config.output_dataset_field}' not found in dataset sample"
                )

            # --- Step 1: Prepare the prompt for the LLM ---
            prompt_for_llm: str
            # For logging purposes, show the raw input values that were combined.
            field_mapping = {
                field: dataset_item[field] for field in task_config.input_dataset_fields if field in dataset_item
            }

            if getattr(task_config, 'use_chat_prompt', False):
                # For chat prompts, the candidate prompt `prompt` is expected to be a template for the user message.
                # We assume it contains placeholders like {question} or {text}.
                candidate_template = Template(prompt) # `prompt` is the candidate prompt text from optimizer/initial
                prompt_for_llm = candidate_template.safe_substitute(field_mapping)
            else:
                # For non-chat prompts, `prompt` (the candidate/initial prompt) is the base instruction.
                # Append the actual data fields to it.
                input_clauses = []
                for field_name in task_config.input_dataset_fields: # e.g., ["question"] or ["text"]
                    if field_name in dataset_item: # This should always be true due to earlier validation
                        # Capitalizes field name, e.g., "Question: ..." or "Text: ..."
                        input_clauses.append(f"{field_name.capitalize()}: {dataset_item[field_name]}")
                item_specific_inputs_str = "\n".join(input_clauses)
                
                # `prompt` here is the candidate prompt text from the optimizer, or the initial_prompt.
                prompt_for_llm = f"{prompt}\n\n{item_specific_inputs_str}"

            logger.debug(f"Evaluating with inputs: {field_mapping}")
            logger.debug(f"Prompt for LLM: {prompt_for_llm}")

            # --- Step 2: Call the model ---
            try:
                logger.info(f"Calling LLM with prompt length: {len(prompt_for_llm)}")
                # _call_model handles system prompt and chat/non-chat based on task_config.use_chat_prompt
                # `is_reasoning` is False here as this is for evaluation, not prompt generation.
                raw_model_output = self._call_model(prompt=prompt_for_llm, system_prompt=None, is_reasoning=False)
                logger.info(f"LLM raw response length: {len(raw_model_output)}")
                logger.debug(f"LLM raw output: {raw_model_output}")
            except Exception as e:
                logger.error(f"Error calling model with prompt: {e}")
                logger.error(f"Failed prompt: {prompt_for_llm}")
                logger.error(f"Prompt length: {len(prompt_for_llm)}")
                raise

            # --- Step 3: Clean the model's output before metric evaluation ---
            cleaned_model_output = raw_model_output.strip()
            original_cleaned_output = cleaned_model_output # For logging if changed

            # Dynamically generate prefixes based on the output field name
            output_field = task_config.output_dataset_field # e.g., "answer" or "label"
            dynamic_prefixes = [
                f"{output_field.capitalize()}:", 
                f"{output_field.capitalize()} :",
                f"{output_field}:", # Also check lowercase field name
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
                    cleaned_model_output = cleaned_model_output[len(prefix_to_check):].strip()
                    logger.debug(f"Stripped prefix '{prefix_to_check}', new output for metric: {cleaned_model_output}")
                    break # Stop after stripping the first found prefix
            
            if original_cleaned_output != cleaned_model_output:
                 logger.info(f"Raw model output: '{original_cleaned_output}' -> Cleaned for metric: '{cleaned_model_output}'")

            result = {
                mappers.EVALUATED_LLM_TASK_OUTPUT: cleaned_model_output,
            }
            return result

        # Use dataset's get_items with limit for sampling
        logger.info(f"Starting evaluation with {subset_size if subset_size else 'all'} samples")
        return task_evaluator.evaluate(
            dataset=dataset,
            metric_config=metric_config,
            evaluated_task=llm_task,
            num_threads=self.num_threads,
            project_name=self.project_name,
            n_samples=subset_size,  # Use subset_size for trials, None for full dataset
            experiment_config=experiment_config,
            optimization_id=optimization_id,
        )

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
            logger.warning(f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset.")
            n_samples = None

        logger.info(f"Starting optimization with n_samples={n_samples}, auto_continue={auto_continue}")
        logger.info(f"Dataset size: {total_items} items")
        logger.info(f"Initial prompt: {task_config.instruction_prompt}")

        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric_config.metric.name,
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
                optimization.update(status="completed")
                logger.info("Optimization completed successfully")
            return result
        except Exception as e:
            logger.error(f"Optimization failed: {e}")
            if optimization:
                optimization.update(status="cancelled")
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
                },
            },
        }

        logger.info("Evaluating initial prompt")
        best_score = self.evaluate_prompt(
            optimization_id=optimization_id,
            dataset=dataset,
            metric_config=metric_config,
            task_config=task_config,
            prompt=current_prompt,
            n_samples=n_samples,  # Use n_samples if provided
            experiment_config=experiment_config,
            use_full_dataset=n_samples is None,  # Only use full dataset if n_samples is None
        )
        initial_score = best_score
        best_prompt = current_prompt
        rounds = []
        stopped_early = False

        logger.info(f"Initial score: {initial_score:.4f}")

        # Initialize progress tracking with custom format
        pbar = tqdm(
            total=self.max_rounds,
            desc="Optimizing Prompt",
            bar_format="{l_bar}{bar:20}{r_bar}",
            position=0,
            leave=True,
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
                    current_prompt=current_prompt,
                    best_score=best_score,
                    round_num=round_num,
                    previous_rounds=rounds,
                )
                logger.info(f"Generated {len(candidate_prompts)} candidate prompts")
            except Exception as e:
                logger.error(f"Error generating candidate prompts: {e}")
                break

            # Evaluate each candidate with multiple trials
            prompt_scores = []
            for candidate_count, prompt in enumerate(candidate_prompts):
                logger.info(f"\nEvaluating candidate {candidate_count + 1}/{len(candidate_prompts)}")
                logger.info(f"Prompt: {prompt}")
                
                scores = []
                should_continue = True

                # First round of trials (always complete)
                for trial in range(3):
                    try:
                        logger.info(f"Trial {trial + 1}/3")
                        score = self.evaluate_prompt(
                            dataset=dataset,
                            metric_config=metric_config,
                            task_config=task_config,
                            prompt=prompt,
                            n_samples=n_samples,
                            use_full_dataset=False,
                            experiment_config=experiment_config,
                        )
                        scores.append(score)
                    except Exception as e:
                        logger.error(f"Error in trial {trial + 1}: {e}")
                        continue

                # If all trials failed, skip this prompt
                if not scores:
                    logger.warning("All trials failed for this prompt, skipping")
                    continue

                # Check if we should continue with additional trials
                avg_score = sum(scores) / len(scores)
                logger.info(f"Average score after first 3 trials: {avg_score:.4f}")
                
                if (
                    not self.auto_continue or avg_score < best_score * 0.8
                ):  # If significantly worse, skip additional trials
                    should_continue = False
                    logger.info("Skipping additional trials - score too low")

                # Additional trials if needed
                if should_continue:
                    logger.info("Running additional trials")
                    for trial in range(3, 6):  # Up to 6 total trials
                        try:
                            logger.info(f"Additional trial {trial + 1}/6")
                            score = self.evaluate_prompt(
                                dataset=dataset,
                                metric_config=metric_config,
                                task_config=task_config,
                                prompt=prompt,
                                n_samples=n_samples,
                                use_full_dataset=False,
                                experiment_config=experiment_config,
                            )
                            scores.append(score)
                        except Exception as e:
                            logger.error(f"Error in additional trial {trial + 1}: {e}")
                            continue

                # Calculate average score for this prompt
                if scores:  # Only calculate average if we have scores
                    avg_score = sum(scores) / len(scores)
                    prompt_scores.append((prompt, avg_score, scores))
                    logger.info(f"Final average score for prompt: {avg_score:.4f}")
                    logger.info(f"Individual trial scores: {[f'{s:.4f}' for s in scores]}")

            # If no prompts were successfully evaluated, break
            if not prompt_scores:
                logger.warning("No prompts were successfully evaluated in this round")
                break

            # Sort prompts by score and get the best one
            prompt_scores.sort(key=lambda x: x[1], reverse=True)
            if prompt_scores:
                best_candidate, best_candidate_score, trial_scores = prompt_scores[0]
                logger.info(f"\nBest candidate from this round:")
                logger.info(f"Score: {best_candidate_score:.4f}")
                logger.info(f"Prompt: {best_candidate}")

                # Final evaluation with full dataset for the best candidate
                if best_candidate_score > best_score:
                    logger.info("Running final evaluation")
                    final_score = self.evaluate_prompt(
                        optimization_id=optimization_id,
                        dataset=dataset,
                        metric_config=metric_config,
                        task_config=task_config,
                        prompt=best_candidate,
                        experiment_config=experiment_config,
                        n_samples=n_samples,  # Use n_samples if provided
                        use_full_dataset=n_samples is None,  # Only use full dataset if n_samples is None
                    )
                    if final_score > best_score:
                        best_score = final_score
                        best_prompt = best_candidate
                        logger.info(f"New best prompt found!")
                        logger.info(f"Score: {best_score:.4f}")
                        logger.info(f"Prompt: {best_prompt}")

                # Update current prompt for next round
                current_prompt = best_candidate

            improvement = self._calculate_improvement(best_score, previous_best_score)
            logger.info(f"Improvement in this round: {improvement:.2%}")
            
            if improvement < self.improvement_threshold:
                logger.info(
                    f"Improvement below threshold ({improvement:.2%} < {self.improvement_threshold:.2%}), stopping early"
                )
                stopped_early = True
                break

            round_data = self._create_round_data(
                round_num,
                current_prompt,
                best_score,
                best_prompt,
                prompt_scores,
                previous_best_score,
                improvement,
            )
            rounds.append(round_data)
            self._add_to_history(round_data.dict())

            # Update progress bar
            pbar.update(1)
            pbar.set_postfix(
                {"best_score": f"{best_score:.4f}", "improvement": f"{improvement:.2%}"}
            )

        # Close progress bar
        pbar.close()

        # Final logging of the best prompt
        logger.info("\n" + "=" * 80)
        logger.info("OPTIMIZATION COMPLETE")
        logger.info("=" * 80)
        logger.info(f"Initial score: {initial_score:.4f}")
        logger.info(f"Final best score: {best_score:.4f}")
        if initial_score != 0:
            logger.info(
                f"Total improvement: {(best_score - initial_score) / initial_score:.2%}"
            )
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
        current_prompt: str,
        best_score: float,
        best_prompt: str,
        candidate_prompts: List[tuple[str, float, List[float]]],
        previous_best_score: float,
        improvement: float,
    ) -> OptimizationRound:
        """Create an OptimizationRound object with the current round's data."""
        return OptimizationRound(
            round_number=round_num + 1,
            current_prompt=current_prompt,
            current_score=best_score,
            generated_prompts=[
                {
                    "prompt": prompt,
                    "score": avg_score,
                    "trial_scores": trial_scores,
                    "improvement": (
                        (avg_score - previous_best_score) / previous_best_score
                        if previous_best_score > 0
                        else 0
                    ),
                }
                for prompt, avg_score, trial_scores in candidate_prompts
            ],
            best_prompt=best_prompt,
            best_score=best_score,
            improvement=improvement,
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
        return OptimizationResult(
            prompt=best_prompt,
            score=best_score,
            metric_name=metric_config.metric.name,
            details={
                "initial_prompt": task_config.instruction_prompt,
                "initial_score": initial_score,
                "final_prompt": best_prompt,
                "final_score": best_score,
                "rounds": rounds,
                "total_rounds": len(rounds),
                "stopped_early": stopped_early,
            },
        )

    def _get_task_context(self) -> str:
        """Get task-specific context from the dataset."""
        if self.dataset is None or self.task_config is None:
            return ""

        input_fields = self.task_config.input_dataset_fields
        output_field = self.task_config.output_dataset_field

        context = "\nTask Context:\n"
        context += f"Input fields: {', '.join(input_fields)}\n"
        context += f"Output field: {output_field}\n"

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
    ) -> List[str]:
        """Generate candidate prompts using meta-prompting."""
        logger.info(f"\nGenerating candidate prompts for round {round_num + 1}")
        logger.info(f"Current prompt: {current_prompt}")
        logger.info(f"Current score: {best_score}")

        system_prompt = """You are an expert prompt engineer. Your task is to improve prompts for any type of task.
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

        history_context = self._build_history_context(previous_rounds)
        task_context = self._get_task_context()

        user_prompt = f"""Current prompt: {current_prompt}
        Current score: {best_score}
        {history_context}
        {task_context}

        Generate {self.num_prompts_per_round} improved versions of this prompt.
        Each version should:
        1. Be more specific and clear about expectations
        2. Provide necessary context and constraints
        3. Guide the model to produce the desired output format
        4. Remove ambiguity and unnecessary elements
        5. Maintain conciseness while being complete

        Return a valid JSON array as specified."""

        try:
            # Filter out non-model parameters
            model_params = {
                "temperature": 0.7,
                "max_tokens": 1000,
                "top_p": 1.0,
                "frequency_penalty": 0.0,
                "presence_penalty": 0.0,
            }

            messages = [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ]

            response = litellm.completion(
                model=self.reasoning_model,
                messages=messages,
                **model_params
            )
            
            content = response.choices[0].message.content
            logger.debug(f"Raw response from model: {content}")
            
            # Try to extract JSON from the response
            try:
                # First try direct JSON parsing
                result = json.loads(content)
            except json.JSONDecodeError:
                # If that fails, try to extract JSON from the text
                import re
                json_match = re.search(r'\{.*\}', content, re.DOTALL)
                if json_match:
                    try:
                        result = json.loads(json_match.group())
                    except json.JSONDecodeError:
                        logger.error("Could not parse JSON from response")
                        return [current_prompt]
                else:
                    logger.error("No JSON found in response")
                    return [current_prompt]

            if "prompts" in result:
                prompts = [p["prompt"] for p in result["prompts"]]
                for p in result["prompts"]:
                    logger.info(f"Generated prompt: {p['prompt']}")
                    logger.info(f"Improvement focus: {p['improvement_focus']}")
                    logger.info(f"Reasoning: {p['reasoning']}")
                return prompts
            else:
                logger.warning("Invalid response format - missing 'prompts' key")
                return [current_prompt]
        except Exception as e:
            logger.error(f"Error generating candidate prompts: {e}")
            logger.error("Falling back to current prompt")
            return [current_prompt]

    def _build_history_context(self, previous_rounds: List[OptimizationRound]) -> str:
        """Build context from previous optimization rounds."""
        if not previous_rounds:
            return ""

        context = "\nPrevious rounds:\n"
        for round_data in previous_rounds[-3:]:
            context += f"\nRound {round_data.round_number}:\n"
            context += f"Best score: {round_data.best_score:.4f}\n"
            context += "Generated prompts:\n"
            for p in round_data.generated_prompts:
                context += f"- Score: {p['score']:.4f}\n"
                context += f"  Prompt: {p['prompt']}\n"
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
