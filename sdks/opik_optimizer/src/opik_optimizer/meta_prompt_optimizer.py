from typing import List, Dict, Any, Optional, Union
import opik
from opik.evaluation.metrics import BaseMetric
from .base_optimizer import BaseOptimizer, OptimizationRound
from .optimization_result import OptimizationResult
import openai
import os
import json


class MetaPromptOptimizer(BaseOptimizer):
    def __init__(
        self,
        model: str,
        reasoning_model: str = None,
        max_rounds: int = 3,
        num_prompts_per_round: int = 4,
        improvement_threshold: float = 0.05,
        temperature: float = 0.1,
        max_completion_tokens: int = 5000,
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
            temperature: Temperature for model generation
            max_completion_tokens: Maximum number of tokens for model completion
            **model_kwargs: Additional model parameters
        """
        super().__init__(model, **model_kwargs)
        self.reasoning_model = reasoning_model if reasoning_model is not None else model
        self.max_rounds = max_rounds
        self.num_prompts_per_round = num_prompts_per_round
        self.improvement_threshold = improvement_threshold
        self.temperature = temperature
        self.max_completion_tokens = max_completion_tokens
        self._openai_client = openai.OpenAI(api_key=os.environ["OPENAI_API_KEY"])

    def optimize_prompt(
        self,
        dataset: Union[str, opik.Dataset],
        metric: BaseMetric,
        prompt: str,
        **kwargs,
    ) -> OptimizationResult:
        """
        Optimize a prompt using meta-reasoning.

        Args:
            dataset: Opik dataset name or dataset
            metric: Instance of an Opik metric
            prompt: The prompt to optimize
            **kwargs: Additional arguments for evaluation

        Returns:
            OptimizationResult: Structured result containing optimization details
        """
        # Load the dataset if it's a string
        if isinstance(dataset, str):
            opik_client = opik.Opik(project_name=self.project_name)
            self.dataset = opik_client.get_dataset(dataset)
        else:
            self.dataset = dataset

        # Get the dataset items
        self.dataset_items = self.dataset.get_items()
        if not self.dataset_items:
            raise ValueError("No data found in dataset")

        self.metric = metric
        self.prompt = prompt

        current_prompt = prompt
        current_score = self._evaluate_prompt(prompt, **kwargs)
        best_prompt = current_prompt
        best_score = current_score
        rounds = []
        stopped_early = False

        for round_num in range(self.max_rounds):
            print(f"\nOptimization Round {round_num + 1}/{self.max_rounds}")
            print(f"Current best score: {best_score:.4f}")

            # Generate new prompts using reasoning
            new_prompts = self._generate_prompts(current_prompt, current_score)

            # Evaluate new prompts
            prompt_scores = []
            for new_prompt in new_prompts:
                score = self._evaluate_prompt(new_prompt, **kwargs)
                improvement = (
                    (score - current_score) / current_score if current_score > 0 else 0
                )
                prompt_scores.append(
                    {"prompt": new_prompt, "score": score, "improvement": improvement}
                )
                print(f"Generated prompt score: {score:.4f}")

            # Find best prompt from this round
            best_round_prompt = max(prompt_scores, key=lambda x: x["score"])

            # Check if we've improved enough
            improvement = (
                (best_round_prompt["score"] - best_score) / best_score
                if best_score > 0
                else 0
            )
            if improvement < self.improvement_threshold:
                print(
                    f"Improvement ({improvement:.2%}) below threshold ({self.improvement_threshold:.2%}). Stopping."
                )
                stopped_early = True
                break

            # Update best prompt if improved
            if best_round_prompt["score"] > best_score:
                best_prompt = best_round_prompt["prompt"]
                best_score = best_round_prompt["score"]
                current_prompt = best_round_prompt["prompt"]
                current_score = best_round_prompt["score"]
                print(f"New best prompt found with score: {best_score:.4f}")

            # Record round results
            round_data = OptimizationRound(
                round_number=round_num + 1,
                current_prompt=current_prompt,
                current_score=current_score,
                generated_prompts=[
                    {
                        "prompt": p["prompt"],
                        "score": p["score"],
                        "improvement": p["improvement"],
                    }
                    for p in prompt_scores
                ],
                best_prompt=best_round_prompt["prompt"],
                best_score=best_round_prompt["score"],
                improvement=improvement,
            )
            rounds.append(round_data)
            self._add_to_history(round_data.dict())

        # If no rounds were completed, add the initial round
        if not rounds:
            round_data = OptimizationRound(
                round_number=1,
                current_prompt=current_prompt,
                current_score=current_score,
                generated_prompts=[
                    {
                        "prompt": current_prompt,
                        "score": current_score,
                        "improvement": 0.0,
                    }
                ],
                best_prompt=best_prompt,
                best_score=best_score,
                improvement=0.0,
            )
            rounds.append(round_data)
            self._add_to_history(round_data.dict())

        # Create the result object with additional details
        result = OptimizationResult(
            prompt=best_prompt,
            score=best_score,
            metric_name=metric.name,
            details={
                "initial_prompt": prompt,
                "initial_score": self._evaluate_prompt(prompt, **kwargs),
                "final_prompt": best_prompt,
                "final_score": best_score,
                "rounds": rounds,
                "total_rounds": len(rounds),
                "stopped_early": stopped_early,
            },
        )

        return result

    def _evaluate_prompt(self, prompt: str, **kwargs) -> float:
        """Evaluate a prompt using the dataset and metric."""
        # Get a sample from the dataset
        data = self.dataset.get_items()
        if not data:
            raise ValueError("No data found in dataset")

        # Create the prompt with the sample
        sample = data[0]
        input_field = kwargs.get(
            "input_key", "text"
        )  # Default to "text" for input field
        output_field = kwargs.get(
            "output_key", "label"
        )  # Default to "label" for output field

        # Create the prompt with the sample
        full_prompt = (
            f"{prompt}\n\n{input_field}: {sample[input_field]}\n{output_field}:"
        )

        # Prepare API parameters based on model type
        api_params = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": full_prompt},
            ],
        }

        # Add model-specific parameters if not using o3-mini
        if not self.model.startswith("o3-"):
            api_params.update(
                {
                    "temperature": self.temperature,
                    "max_completion_tokens": self.max_completion_tokens,
                }
            )

        # Get the model's response
        response = self._openai_client.chat.completions.create(**api_params)

        # Get the model's response
        model_output = response.choices[0].message.content.strip()

        # Get the expected output
        expected_output = sample[output_field]

        # Compute the score using the metric
        score_result = self.metric.score(model_output, expected_output)

        # Extract the score value from the ScoreResult object
        return score_result.value

    def _generate_prompts(self, current_prompt: str, current_score: float) -> List[str]:
        """Generate new prompts using the reasoning model."""
        system_prompt = """You are an expert prompt engineer. Your task is to improve the given prompt based on its current performance.
        Analyze the prompt and suggest improvements that could make it more effective.
        Consider:
        1. Clarity and specificity
        2. Task alignment
        3. Potential biases or limitations
        4. Opportunities for better instruction
        Generate diverse variations that explore different aspects of improvement.
        
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

        user_prompt = f"""Current prompt: {current_prompt}
        Current performance score: {current_score}
        
        Generate {self.num_prompts_per_round} improved versions of this prompt.
        Each version should explore a different aspect of improvement.
        Return a valid JSON array as specified."""

        # Prepare API parameters based on model type
        api_params = {
            "model": self.reasoning_model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "response_format": {"type": "json_object"},
        }

        # Add model-specific parameters if not using o3-mini
        if not self.reasoning_model.startswith("o3-"):
            api_params.update({"temperature": 0.7, "max_completion_tokens": 1000})

        response = self._openai_client.chat.completions.create(**api_params)

        # Parse the JSON response
        try:
            result = json.loads(response.choices[0].message.content)
            return [p["prompt"] for p in result["prompts"]]
        except (json.JSONDecodeError, KeyError) as e:
            print(f"Error parsing response: {e}")
            return []
