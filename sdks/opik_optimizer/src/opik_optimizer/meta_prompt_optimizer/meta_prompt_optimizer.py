import json
import copy
import logging
import os
from typing import Any, Callable, Dict, List, Optional, overload

import litellm
import opik
from litellm.caching import Cache
from litellm.types.caching import LiteLLMCacheType
from opik import Dataset
from opik.api_objects import opik_client
from opik.environment import get_tqdm_for_current_environment
from opik.evaluation.models.litellm import opik_monitor as opik_litellm_monitor

from opik_optimizer import task_evaluator

from .. import _throttle
from ..base_optimizer import BaseOptimizer, OptimizationRound
from ..optimization_config import chat_prompt, mappers
from ..optimization_result import OptimizationResult
from . import reporting

tqdm = get_tqdm_for_current_environment()

# Using disk cache for LLM calls
disk_cache_dir = os.path.expanduser("~/.litellm_cache")
litellm.cache = Cache(type=LiteLLMCacheType.DISK, disk_cache_dir=disk_cache_dir)

# Set up logging
logger = logging.getLogger(__name__)  # Gets logger configured by setup_logging

_rate_limiter = _throttle.get_rate_limiter_for_current_opik_installation()


class MetaPromptOptimizer(BaseOptimizer):
    """
    The Meta-Prompt Optimizer uses meta-prompting to improve prompts based on examples and performance.
    
    This algorithm is best used when you have a prompt and would like to make sure it follows best
    practices.
    """
    # --- Constants for Default Configuration ---
    DEFAULT_ROUNDS = 3
    DEFAULT_PROMPTS_PER_ROUND = 4

    # --- Reasoning System Prompt ---
    _REASONING_SYSTEM_PROMPT = """You are an expert prompt engineer. Your task is to improve prompts for any type of task.
        
        Focus on making the prompt more effective by:
        1. Being clear and specific about what is expected
        2. Providing necessary context and constraints
        3. Guiding the model to produce the desired output format
        4. Removing ambiguity and unnecessary elements
        5. Maintaining conciseness while being complete

        Instructions:
        1. If there is a system prompt, prioritize adding instructions there if and only if it makes sense.
        2. DO NOT add any variables or parameters to the prompt you are editing.
        3. You can reuse variables that already exist in the prompt.

        Return a JSON array of prompts with the following structure. Make sure to return a valid
        JSON object with correct use of double quotes and single quotes. JSON keys should be
        double-quoted:
        {
            "prompts": [
                {
                    "prompt": [{"role": "<role>", "content": "<content>"}],
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                },
                {
                    "prompt": [{"role": "<role>", "content": "<content>"}],
                    "improvement_focus": "what aspect this prompt improves",
                    "reasoning": "why this improvement should help"
                }
            ]
        }"""

    def __init__(
        self,
        model: str,
        reasoning_model: Optional[str] = None,
        rounds: int = DEFAULT_ROUNDS,
        num_prompts_per_round: int = DEFAULT_PROMPTS_PER_ROUND,
        num_threads: int = 12,
        project_name: str = "Optimization",
        verbose: int = 1,
        enable_context: bool = True,
        **model_kwargs,
    ):
        """
        Args:
            model: The model to use for evaluation
            reasoning_model: The model to use for reasoning and prompt generation
            rounds: Number of optimization rounds
            num_prompts_per_round: Number of prompts to generate per round
            num_threads: Number of threads for parallel evaluation
            project_name: Optional project name for tracking
            verbose: Controls internal logging/progress bars (0=off, 1=on).
            enable_context: Whether to include task-specific context (metrics, examples) in the reasoning prompt.
            **model_kwargs: Additional model parameters
        """
        super().__init__(model=model, project_name=project_name, **model_kwargs)
        self.reasoning_model = reasoning_model if reasoning_model is not None else model
        self.rounds = rounds
        self.num_prompts_per_round = num_prompts_per_round
        self.num_threads = num_threads
        self.verbose = verbose
        self.dataset = None
        self._opik_client = opik_client.get_client_cached()
        self.llm_call_counter = 0
        self.enable_context = enable_context
        logger.debug(
            f"Initialized MetaPromptOptimizer with model={model}, reasoning_model={self.reasoning_model}"
        )
        logger.debug(
            f"Optimization rounds: {rounds}, Prompts/round: {num_prompts_per_round}"
        )

    @_throttle.rate_limited(_rate_limiter)
    def _call_model(
        self,
        messages: List[Dict[str, str]],
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

     # type: ignore
    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        use_full_dataset: bool = True,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
        optimization_id: Optional[str] = None,
        verbose: int = 1,
    ) -> float:
        """
        Args:
            prompt: The prompt to evaluate
            dataset: Opik Dataset to evaluate the prompt on
            metric: Metric functions
            use_full_dataset: Whether to use the full dataset or a subset
            experiment_config: Optional configuration for the experiment, useful to log additional metadata
            n_samples: Optional number of items to test in the dataset
            optimization_id: Optional ID of the optimization
            verbose: Controls internal logging/progress bars (0=off, 1=on).
        Returns:
            float: The evaluation score
        """
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
                "metric": getattr(metric, '__name__', str(metric)),
                "dataset": dataset.name,
                "configuration": {
                    "prompt": prompt.formatted_messages,
                    "n_samples": subset_size,
                    "use_full_dataset": use_full_dataset,
                },
            },
        }
        if optimization_id:
            experiment_config["optimization_id"] = optimization_id

        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, str]:
            # --- Step 1: Prepare the prompt for the LLM ---
            messages = [{
                "role": item["role"],
                "content": item["content"].format(**dataset_item)
            } for item in prompt.formatted_messages]

            # --- Step 2: Call the model ---
            try:
                logger.debug(f"Calling LLM with prompt length: {sum(len(msg['content']) for msg in messages)}")
                raw_model_output = self._call_model(
                    messages=messages,
                    is_reasoning=False,
                    optimization_id=optimization_id,
                )
                logger.debug(f"LLM raw response length: {len(raw_model_output)}")
                logger.debug(f"LLM raw output: {raw_model_output}")
            except Exception as e:
                logger.error(f"Error calling model with prompt: {e}")
                logger.error(f"Failed prompt: {messages}")
                logger.error(f"Prompt length: {sum(len(msg['content']) for msg in messages)}")
                raise

            # --- Step 3: Clean the model's output before metric evaluation ---
            cleaned_model_output = raw_model_output.strip()

            result = {
                mappers.EVALUATED_LLM_TASK_OUTPUT: cleaned_model_output,
            }
            return result

        # Use dataset's get_items with limit for sampling
        logger.debug(
            f"Starting evaluation with {subset_size if subset_size else 'all'} samples for metric: {getattr(metric, '__name__', str(metric))}"
        )
        score = task_evaluator.evaluate(
            dataset=dataset,
            metric=metric,
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

    def optimize_prompt( # type: ignore[override]
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        experiment_config: Optional[Dict] = None,
        n_samples: Optional[int] = None,
        auto_continue: bool = False,
        **kwargs,
    ) -> OptimizationResult:
        """
        Optimize a prompt using meta-reasoning.

        Args:
            prompt: The prompt to optimize
            dataset: The dataset to evaluate against
            metric: The metric to use for evaluation
            experiment_config: A dictionary to log with the experiments
            n_samples: The number of dataset items to use for evaluation
            auto_continue: If True, the algorithm may continue if goal not met
            **kwargs: Additional arguments for evaluation

        Returns:
            OptimizationResult: Structured result containing optimization details
        """
        if not isinstance(prompt, chat_prompt.ChatPrompt):
            raise ValueError("Prompt must be a ChatPrompt object")
        
        if not isinstance(dataset, Dataset):
            raise ValueError("Dataset must be a Dataset object")
        
        if not isinstance(metric, Callable):
            raise ValueError("Metric must be a function that takes `dataset_item` and `llm_output` as arguments.")

        total_items = len(dataset.get_items())
        if n_samples is not None and n_samples > total_items:
            logger.warning(
                f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset."
            )
            n_samples = None

        
        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=getattr(metric, '__name__', str(metric)),
                metadata={"optimizer": self.__class__.__name__},
            )
            logger.debug(f"Created optimization with ID: {optimization.id}")
        except Exception as e:
            logger.warning(
                f"Opik server does not support optimizations: {e}. Please upgrade opik."
            )
            optimization = None

        reporting.display_header(
            algorithm=self.__class__.__name__,
            optimization_id=optimization.id if optimization is not None else None,
            dataset_id=dataset.id,
            verbose=self.verbose
        )
        reporting.display_configuration(
            messages=prompt.formatted_messages,
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "n_samples": n_samples,
                "auto_continue": auto_continue
            },
            verbose=self.verbose
        )

        try:
            result = self._optimize_prompt(
                optimization_id=optimization.id if optimization is not None else None,
                prompt=prompt,
                dataset=dataset,
                metric=metric,
                experiment_config=experiment_config,
                n_samples=n_samples,
                auto_continue=auto_continue,
                **kwargs,
            )
            if optimization:
                self.update_optimization(optimization, status="completed")
                logger.debug("Optimization completed successfully")
            return result
        except Exception as e:
            logger.error(f"Optimization failed: {e}")
            if optimization:
                self.update_optimization(optimization, status="cancelled")
                logger.debug("Optimization marked as cancelled")
            raise e

    def _optimize_prompt(
        self,
        optimization_id: str,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        experiment_config: Optional[Dict],
        n_samples: int,
        auto_continue: bool,
        **kwargs,
    ) -> OptimizationResult:
        self.auto_continue = auto_continue
        self.dataset = dataset
        self.prompt = prompt
        self.llm_call_counter = 0 # Reset counter for run
        initial_prompt: List[Dict[str, str]] = prompt.formatted_messages

        current_prompt = prompt.formatted_messages
        experiment_config = experiment_config or {}
        experiment_config = {
            **experiment_config,
            **{
                "optimizer": self.__class__.__name__,
                "metric": getattr(metric, '__name__', str(metric)),
                "dataset": self.dataset.name,
                "configuration": {
                    "prompt": current_prompt,
                    "rounds": self.rounds,
                    "num_prompts_per_round": self.num_prompts_per_round,
                },
            },
        }

        with reporting.display_evaluation(verbose=self.verbose) as baseline_reporter:
            initial_score = self.evaluate_prompt(
                prompt=prompt,
                optimization_id=optimization_id,
                dataset=dataset,
                metric=metric,
                n_samples=n_samples,
                experiment_config=experiment_config,
                use_full_dataset=n_samples is None,
                verbose=self.verbose,
            )
            best_score = initial_score
            best_prompt = current_prompt
            rounds = []

            baseline_reporter.set_score(initial_score)

        reporting.display_optimization_start_message(verbose=self.verbose)
        with reporting.display_round_progress(self.rounds, verbose=self.verbose) as round_reporter:
            for round_num in range(self.rounds):
                
                round_reporter.round_start(round_num)
                previous_best_score = best_score
                
                # Step 1. Create a set of candidate prompts
                try:
                    candidate_prompts = self._generate_candidate_prompts(
                        current_prompt=best_prompt,
                        best_score=best_score,
                        round_num=round_num,
                        previous_rounds=rounds,
                        metric=metric,
                        optimization_id=optimization_id,
                    )
                except Exception as e:
                    round_reporter.failed_to_generate(self.num_prompts_per_round, e)
                    continue

                # Step 2. Score each candidate prompt
                prompt_scores = []
                for candidate_count, prompt in enumerate(candidate_prompts):
                    with reporting.display_prompt_candidate_scoring_report(candidate_count, prompt, verbose=self.verbose) as eval_report:
                        eval_report.set_generated_prompts(candidate_count, prompt)

                        try:
                            prompt_score = self.evaluate_prompt(
                                prompt=chat_prompt.ChatPrompt(messages=prompt),
                                optimization_id=optimization_id,
                                dataset=dataset,
                                metric=metric,
                                n_samples=n_samples,
                                use_full_dataset=False,
                                experiment_config=experiment_config,
                                verbose=self.verbose,
                            )

                            eval_report.set_final_score(best_score, prompt_score)
                        except Exception as e:
                            raise ValueError(f"Error evaluating candidate prompt: {e}")

                    prompt_scores.append((prompt, prompt_score))
                
                # Step 3. Identify potential improvements
                if not prompt_scores:
                    logger.warning("No prompts were successfully evaluated in this round")
                    break

                prompt_scores.sort(key=lambda x: x[1], reverse=True)
                best_candidate_this_round, best_cand_score_avg = (
                    prompt_scores[0]
                )
                improvement = self._calculate_improvement(best_cand_score_avg, best_score)
                round_reporter.round_end(round_num, best_cand_score_avg, best_score, best_prompt)
                
                round_data = self._create_round_data(
                    round_num=round_num,
                    current_best_prompt=chat_prompt.ChatPrompt(messages=best_candidate_this_round),
                    current_best_score=best_cand_score_avg,
                    best_prompt_overall=chat_prompt.ChatPrompt(messages=best_prompt),
                    evaluated_candidates=prompt_scores,
                    previous_best_score=previous_best_score,
                    improvement_this_round=improvement,
                )
                rounds.append(round_data)
                self._add_to_history(round_data.model_dump())

                if improvement > 0:
                    best_score = best_cand_score_avg
                    best_prompt = best_candidate_this_round

        reporting.display_result(
            initial_score,
            best_score,
            best_prompt,
            verbose=self.verbose
        )

        return self._create_result(
            metric,
            initial_prompt=initial_prompt,
            best_prompt=best_prompt,
            best_score=best_score,
            initial_score=initial_score,
            rounds=rounds,
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
        current_best_prompt: chat_prompt.ChatPrompt,
        current_best_score: float,
        best_prompt_overall: chat_prompt.ChatPrompt,
        evaluated_candidates: List[tuple[str, float, List[float]]],
        previous_best_score: float,
        improvement_this_round: float,
    ) -> OptimizationRound:
        """Create an OptimizationRound object with the current round's data."""
        generated_prompts_log = []
        for prompt, score in evaluated_candidates:
            improvement_vs_prev = self._calculate_improvement(
                score, previous_best_score
            )
            generated_prompts_log.append(
                {
                    "prompt": prompt,
                    "score": score,
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
        metric: Callable,
        initial_prompt: List[Dict[str, str]],
        best_prompt: List[Dict[str, str]],
        best_score: float,
        initial_score: float,
        rounds: List[OptimizationRound],
    ) -> OptimizationResult:
        """Create the final OptimizationResult object."""
        details = {
            "final_prompt": best_prompt,
            "final_score": best_score,
            "rounds": rounds,
            "total_rounds": len(rounds),
            "metric_name": getattr(metric, '__name__', str(metric)),
            "model": self.model,
            "temperature": self.model_kwargs.get("temperature"),
        }

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=best_prompt,
            score=best_score,
            initial_prompt=initial_prompt,
            initial_score=initial_score,
            metric_name=getattr(metric, '__name__', str(metric)),
            details=details,
            llm_calls=self.llm_call_counter
        )

    def _get_task_context(self, metric: Callable) -> str:
        """Get task-specific context from the dataset and metric configuration."""
        if self.dataset is None:
            return ""

        try:
            # Try get_items() first as it's the preferred method
            items = self.dataset.get_items()
            sample = items[0]  # Get first sample
        except Exception as e:
            logger.warning(f"Could not get sample from dataset: {e}")

        # Describe Single Metric
        if sample is not None:
            metric_name = metric.__name__
            description = metric.__doc__ or "No description available."

            metrics_str = f"- {metric_name}: {description}"

            context = "\nTask Context:\n"
            context += f"Dataset fields (includes both input and optionally the expected output): {', '.join([x for x in sample.keys() if x != 'id'])}\n"
            context += f"Evaluation Metric:\n{metrics_str}\n"
            context += f"\nExample:\n{json.dumps(sample)}\n"
        
        return context

    def _generate_candidate_prompts(
        self,
        current_prompt: chat_prompt.ChatPrompt,
        best_score: float,
        round_num: int,
        previous_rounds: List[OptimizationRound],
        metric: Callable,
        optimization_id: Optional[str] = None,
    ) -> List[str]:
        """Generate candidate prompts using meta-prompting."""
        with reporting.display_candidate_generation_report(
            self.num_prompts_per_round,
            verbose=self.verbose
        ) as candidate_generation_report: 
            logger.debug(f"\nGenerating candidate prompts for round {round_num + 1}")
            logger.debug(f"Generating from prompt: {current_prompt}")
            logger.debug(f"Current best score: {best_score:.4f}")

            history_context = self._build_history_context(previous_rounds)
            task_context_str = ""
            analysis_instruction = ""
            metric_focus_instruction = ""
            improvement_point_1 = ""

            if self.enable_context:
                task_context_str = self._get_task_context(metric=metric)
                analysis_instruction = "Analyze the example provided (if any), the metric description (if any), and the history of scores."
                metric_focus_instruction = f"Focus on improving the score for the metric: {metric.__name__}."
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
                    messages=[
                        {"role": "system", "content": self._REASONING_SYSTEM_PROMPT},
                        {"role": "user", "content": user_prompt}
                    ],
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
                    import re

                    json_match = re.search(r"\{.*\}", content, re.DOTALL)
                    if json_match:
                        try:
                            json_result = json.loads(json_match.group())
                        except json.JSONDecodeError as e:
                            raise ValueError(f"Could not parse JSON extracted via regex: {e} - received: {json_match.group()}")
                    else:
                        raise ValueError(f"No JSON object found in response via regex. - received: {content}")

                # Validate the parsed JSON structure
                if isinstance(json_result, list) and len(json_result) == 1:
                    json_result = json_result[0]

                if not isinstance(json_result, dict) or "prompts" not in json_result:
                    logger.debug(f"Parsed JSON content: {json_result}")
                    raise ValueError(f"Parsed JSON is not a dictionary or missing 'prompts' key. - received: {json_result}")

                if not isinstance(json_result["prompts"], list):
                    logger.debug(f"Content of 'prompts': {json_result.get('prompts')}")
                    raise ValueError(f"'prompts' key does not contain a list. - received: {json_result.get('prompts')}")

                # Extract and log valid prompts
                valid_prompts = []
                for item in json_result["prompts"]:
                    if (
                        isinstance(item, dict)
                        and "prompt" in item
                        and isinstance(item["prompt"], list)
                    ):
                        prompt_text = item["prompt"]
                        valid_prompts.append(prompt_text)
                        
                        # Log details
                        focus = item.get("improvement_focus", "N/A")
                        reasoning = item.get("reasoning", "N/A")
                        logger.debug(f"Generated prompt: {prompt_text}")
                        logger.debug(f"  Improvement focus: {focus}")
                        logger.debug(f"  Reasoning: {reasoning}")
                    else:
                        logger.warning(
                            f"Skipping invalid prompt item structure in JSON response: {item}"
                        )

                if not valid_prompts:
                    raise ValueError("No valid prompts found in the parsed JSON response after validation.")
                
                candidate_generation_report.set_generated_prompts(
                    self.num_prompts_per_round
                )
                
                return valid_prompts
                # --- End Robust Parsing ---

            except Exception as e:
                raise ValueError(f"Unexpected error during candidate prompt generation: {e}")

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
