from typing import List, Dict, Any, Optional
import opik
from opik.evaluation import metrics
import litellm
from litellm.caching import Cache
import logging
import json
import os

from opik_optimizer.optimization_config import mappers
from .base_optimizer import BaseOptimizer, OptimizationRound
from .optimization_result import OptimizationResult
from .utils import get_tqdm
from opik_optimizer.optimization_dsl import (
    MetricConfig,
    OptimizationConfig,
    PromptTaskConfig,
)
from opik_optimizer import task_evaluator

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
        logger.info(f"Initialized MetaPromptOptimizer with model={model}, reasoning_model={reasoning_model}")

    def evaluate_prompt(
        self,
        dataset: opik.Dataset,
        metric_config: MetricConfig,
        task_config: PromptTaskConfig,
        prompt: str,
        use_full_dataset: bool = False,
        experiment_config: Optional[Dict] = None,
    ) -> float:
        """
        Evaluate a prompt using the given dataset and metric configuration.

        Args:
            dataset: The dataset to evaluate against
            metric_config: The metric configuration to use for evaluation
            task_config: The task configuration containing input/output fields
            prompt: The prompt to evaluate
            use_full_dataset: Whether to use the full dataset or a subset for evaluation
            experiment_config: Optional configuration for the experiment

        Returns:
            float: The evaluation score
        """
        # Calculate subset size for trials
        if not use_full_dataset:
            # Get total size of dataset
            total_items = len(dataset.get_items())
            # Calculate 20% of total, but no more than 20 items
            subset_size = min(20, max(10, int(total_items * 0.2)))
        else:
            subset_size = None  # Use all items for final checks

        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, str]:
            # Convert DatasetItem to dict if needed
            if hasattr(dataset_item, 'to_dict'):
                dataset_item = dataset_item.to_dict()
            
            for input_key in task_config.input_dataset_fields:
                if input_key not in dataset_item:
                    raise ValueError(f"Input field '{input_key}' not found in dataset sample")
            if task_config.output_dataset_field not in dataset_item:
                raise ValueError(f"Output field '{task_config.output_dataset_field}' not found in dataset sample")

            from string import Template
            template = Template(prompt)
            field_mapping = {field: dataset_item[field] for field in task_config.input_dataset_fields}
            full_prompt = template.safe_substitute(field_mapping)
            
            # If the prompt doesn't contain any of the input fields, format it properly
            if not any(field in full_prompt for field in task_config.input_dataset_fields):
                context = dataset_item.get('metadata', {}).get('context', '')
                question = dataset_item[task_config.input_dataset_fields[0]]
                full_prompt = f"{full_prompt}\n\nContext: {context}\nQuestion: {question}"
            
            logger.debug(f"Evaluating prompt with input: {field_mapping}")
            logger.debug(f"Full prompt: {full_prompt}")

            model_output = self._call_model(full_prompt)
            result = {
                mappers.EVALUATED_LLM_TASK_OUTPUT: model_output,
            }
            return result

        # Use dataset's get_items with limit for sampling
        return task_evaluator.evaluate(
            dataset=dataset,
            metric_config=metric_config,
            evaluated_task=llm_task,
            num_threads=self.num_threads,
            project_name=self.project_name,
            num_test=subset_size,  # Use subset_size for trials, None for full dataset
            experiment_config=experiment_config,
        )

    def optimize_prompt(
        self,
        config: OptimizationConfig,
        **kwargs
    ) -> OptimizationResult:
        """
        Optimize a prompt using meta-reasoning.

        Args:
            config: Configuration for the optimization task
            **kwargs: Additional arguments for evaluation

        Returns:
            OptimizationResult: Structured result containing optimization details
        """
        self.dataset = config.dataset
        self.task_config = config.task

        current_prompt = config.task.instruction_prompt
        best_score = self.evaluate_prompt(
            dataset=config.dataset,
            metric_config=config.objective,
            task_config=config.task,
            prompt=current_prompt,
            use_full_dataset=True,  # Use full dataset for initial evaluation
        )
        initial_score = best_score
        best_prompt = current_prompt
        rounds = []
        stopped_early = False

        # Initialize progress tracking with custom format
        pbar = tqdm(
            total=self.max_rounds,
            desc="Optimizing Prompt",
            bar_format='{l_bar}{bar:20}{r_bar}',
            position=0,
            leave=True
        )

        for round_num in range(self.max_rounds):
            logger.info(f"\nRound {round_num + 1}/{self.max_rounds}")
            logger.info(f"Current best score: {best_score:.4f}")

            previous_best_score = best_score
            try:
                candidate_prompts = self._generate_candidate_prompts(
                    current_prompt=current_prompt,
                    best_score=best_score,
                    round_num=round_num,
                    previous_rounds=rounds,
                )
            except Exception as e:
                logger.error(f"Error generating candidate prompts: {e}")
                break

            # Evaluate each candidate with multiple trials
            prompt_scores = []
            for prompt in candidate_prompts:
                trial_scores = []
                should_continue = True
                
                # First round of trials (always complete)
                for trial in range(3):
                    try:
                        score = self.evaluate_prompt(
                            dataset=config.dataset,
                            metric_config=config.objective,
                            task_config=config.task,
                            prompt=prompt,
                            use_full_dataset=False,  # Use subset for trials
                        )
                        trial_scores.append(score)
                        logger.debug(f"Candidate prompt trial {trial + 1} score: {score:.4f}")
                    except Exception as e:
                        logger.error(f"Error in trial {trial + 1}: {e}")
                        trial_scores.append(0)  # Use 0 as fallback score
                
                # Check if we should continue with additional trials
                avg_score = sum(trial_scores) / len(trial_scores)
                if avg_score < best_score * 0.8:  # If significantly worse, skip additional trials
                    should_continue = False
                
                # Additional trials if needed
                if should_continue:
                    for trial in range(3, 6):  # Up to 6 total trials
                        try:
                            score = self.evaluate_prompt(
                                dataset=config.dataset,
                                metric_config=config.objective,
                                task_config=config.task,
                                prompt=prompt,
                                use_full_dataset=False,  # Use subset for trials
                            )
                            trial_scores.append(score)
                            logger.debug(f"Candidate prompt trial {trial + 1} score: {score:.4f}")
                        except Exception as e:
                            logger.error(f"Error in trial {trial + 1}: {e}")
                            trial_scores.append(0)  # Use 0 as fallback score
                
                # Calculate average score for this prompt
                avg_score = sum(trial_scores) / len(trial_scores)
                prompt_scores.append((prompt, avg_score, trial_scores))
                logger.info(f"Average score for prompt: {avg_score:.4f}")

            # Sort prompts by score and get the best one
            prompt_scores.sort(key=lambda x: x[1], reverse=True)
            if prompt_scores:
                best_candidate, best_candidate_score, trial_scores = prompt_scores[0]

                # Final evaluation with full dataset for the best candidate
                if best_candidate_score > best_score:
                    final_score = self.evaluate_prompt(
                        dataset=config.dataset,
                        metric_config=config.objective,
                        task_config=config.task,
                        prompt=best_candidate,
                        use_full_dataset=True,  # Use full dataset for final check
                    )
                    if final_score > best_score:
                        best_score = final_score
                        best_prompt = best_candidate
                        logger.info(f"New best prompt found with score: {best_score:.4f}")
                        logger.debug(f"Individual trial scores: {[f'{s:.4f}' for s in trial_scores]}")
                        logger.debug(f"Best prompt: {best_prompt}")
                
                # Update current prompt for next round
                current_prompt = best_candidate

            improvement = self._calculate_improvement(best_score, previous_best_score)
            if improvement < self.improvement_threshold:
                logger.info(f"Improvement below threshold ({improvement:.2%} < {self.improvement_threshold:.2%}), stopping early")
                stopped_early = True
                break

            round_data = self._create_round_data(
                round_num, current_prompt, best_score, best_prompt, 
                prompt_scores, previous_best_score, improvement
            )
            rounds.append(round_data)
            self._add_to_history(round_data.dict())
            
            # Update progress bar
            pbar.update(1)
            pbar.set_postfix({
                'best_score': f'{best_score:.4f}',
                'improvement': f'{improvement:.2%}'
            })

        # Close progress bar
        pbar.close()

        # Final logging of the best prompt
        logger.info("\n" + "="*80)
        logger.info("OPTIMIZATION COMPLETE")
        logger.info("="*80)
        logger.info(f"Initial score: {initial_score:.4f}")
        logger.info(f"Final best score: {best_score:.4f}")
        logger.info(f"Improvement: {(best_score - initial_score) / initial_score:.2%}")
        logger.info("\nFINAL OPTIMIZED PROMPT:")
        logger.info("-"*80)
        logger.info(best_prompt)
        logger.info("-"*80)
        logger.info("="*80)

        return self._create_result(config, best_prompt, best_score, initial_score, rounds, stopped_early)

    def _calculate_improvement(self, current_score: float, previous_score: float) -> float:
        """Calculate the improvement percentage between scores."""
        return (current_score - previous_score) / previous_score if previous_score > 0 else 0

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
                    "improvement": (avg_score - previous_best_score) / previous_best_score if previous_best_score > 0 else 0,
                }
                for prompt, avg_score, trial_scores in candidate_prompts
            ],
            best_prompt=best_prompt,
            best_score=best_score,
            improvement=improvement,
        )

    def _create_result(
        self,
        config: OptimizationConfig,
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
            metric_name=config.objective.metric.name,
            details={
                "initial_prompt": config.task.instruction_prompt,
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
                if hasattr(self.dataset, 'samples') and self.dataset.samples:
                    sample = self.dataset.samples[0]  # Get first sample
                elif hasattr(self.dataset, '__iter__'):
                    sample = next(iter(self.dataset))
                else:
                    logger.warning("Dataset does not have a samples attribute or is not iterable")
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
        previous_rounds: List[OptimizationRound]
    ) -> List[str]:
        """Generate candidate prompts using meta-prompting."""
        logger.debug(f"\nGenerating candidate prompts for round {round_num + 1}")
        logger.debug(f"Current prompt: {current_prompt}")
        logger.debug(f"Current score: {best_score}")

        system_prompt = """You are an expert prompt engineer. Your task is to improve the given prompt for a question-answering task.
        The goal is to optimize the prompt to get concise, direct answers to questions.
        Consider:
        1. The task is to answer questions directly and accurately
        2. The evaluation metric favors concise, precise answers
        3. The prompt should guide the model to give short, factual responses
        4. Avoid unnecessary explanations or structured responses
        5. Focus on getting the core answer right

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

        Analyze the examples provided and observe:
        1. The typical length and format of successful answers
        2. The language style and tone used
        3. The level of detail in responses
        4. The context and domain-specific terminology

        Based on this analysis, generate {self.num_prompts_per_round} improved versions of this prompt.
        Each version should:
        1. Guide the model to give direct, factual answers
        2. Keep responses concise and to the point
        3. Avoid unnecessary explanations or structure
        4. Focus on accuracy and precision

        Return a valid JSON array as specified."""

        response = self._call_model(
            prompt=user_prompt,
            system_prompt=system_prompt,
            is_reasoning=True,
        )

        return self._parse_candidate_prompts(response, current_prompt)

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

    def _parse_candidate_prompts(self, response: str, fallback_prompt: str) -> List[str]:
        """Parse the model's response to extract candidate prompts."""
        try:
            result = json.loads(response)
            if "prompts" in result:
                prompts = [p["prompt"] for p in result["prompts"]]
                for p in result["prompts"]:
                    logger.debug(f"Generated prompt: {p['prompt']}")
                    logger.debug(f"Improvement focus: {p['improvement_focus']}")
                    logger.debug(f"Reasoning: {p['reasoning']}")
                return prompts
            else:
                logger.warning("Invalid response format")
                return [fallback_prompt]
        except (json.JSONDecodeError, KeyError) as e:
            logger.error(f"Error parsing response: {e}")
            logger.error(f"Raw response: {response}")
            return [fallback_prompt]

    def _get_evaluation_subset(self, dataset: opik.Dataset, min_size: int = 20, max_size: int = 100) -> List[Dict[str, Any]]:
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
