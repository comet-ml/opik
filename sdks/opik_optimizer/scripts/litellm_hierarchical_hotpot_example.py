"""
Hierarchical Reflective Optimizer example on Hotpot dataset using LiteLLM.

This script demonstrates:
- Using HierarchicalReflectiveOptimizer to systematically improve prompts
- Creating a custom LLM-as-judge metric for semantic similarity
- The importance of metrics that provide reasoning for root cause analysis
- Optimizing prompts with tool calling support

Note: The HierarchicalReflectiveOptimizer requires metrics that return
ScoreResult with detailed 'reason' fields for effective root cause analysis.
"""

from typing import Any
import json

import opik  # noqa: E402
from opik_optimizer import ChatPrompt  # noqa: E402
from opik_optimizer import HierarchicalReflectiveOptimizer  # noqa: E402
from opik_optimizer.datasets import hotpot_300  # noqa: E402
from opik_optimizer.utils import search_wikipedia  # noqa: E402

from opik.evaluation.metrics import base_metric, score_result  # noqa: E402
from opik.evaluation import models  # noqa: E402
from pydantic import BaseModel  # noqa: E402


# Define structured output for LLM judge
class AnswerCorrectnessResult(BaseModel):
    """Structured output for answer correctness evaluation."""

    is_correct: bool  # True if answer is correct, False otherwise
    reason: str  # Detailed explanation of the judgment


class AnswerCorrectnessMetric(base_metric.BaseMetric):
    """
    LLM-as-judge metric for evaluating answer correctness.

    This metric uses an LLM to judge whether the model's output is
    semantically correct compared to the reference answer. It returns
    a binary score (1.0 for correct, 0.0 for incorrect) along with
    detailed reasoning which is critical for the Hierarchical Reflective
    Optimizer's root cause analysis.
    """

    def __init__(
        self,
        name: str = "answer_correctness",
        model: str = "openai/gpt-4o-mini",
    ):
        super().__init__(name=name)
        self.model_name = model
        self.llm_client = models.LiteLLMChatModel(model_name=model)

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        Evaluate whether the answer is correct.

        Args:
            output: The model's generated answer
            reference: The expected/reference answer
            **ignored_kwargs: Additional kwargs (ignored)

        Returns:
            ScoreResult with binary score (1.0 or 0.0) and detailed reasoning
        """
        if not reference:
            return score_result.ScoreResult(
                name=self.name,
                value=0.0,
                reason="No reference answer provided for comparison",
            )

        prompt = f"""You are evaluating whether a model's answer is correct compared to a reference answer.

REFERENCE ANSWER (ground truth):
{reference}

MODEL OUTPUT:
{output}

Determine if the model's output is CORRECT:
- CORRECT (true): The output contains the key information from the reference answer, even if worded differently
- INCORRECT (false): The output is missing key information, contains wrong information, or is irrelevant

Provide:
1. is_correct: boolean (true or false)
2. reason: A detailed explanation including:
   - What specific information is present or missing
   - Whether key facts match the reference
   - Any critical errors or inaccuracies
   - Be specific and actionable - explain exactly why it's correct or what's wrong

IMPORTANT: Your reason should be detailed enough to help improve the prompt that generated this answer.

Return your evaluation as JSON with 'is_correct' and 'reason' fields."""

        try:
            response: str = self.llm_client.generate_string(
                input=prompt,
                response_format=AnswerCorrectnessResult,
            )

            formatted_response = json.loads(response)

            # Convert boolean to float score (1.0 or 0.0)
            score_value = 1.0 if formatted_response["is_correct"] else 0.0

            return score_result.ScoreResult(
                name=self.name,
                value=score_value,
                reason=formatted_response[
                    "reason"
                ],  # Critical for root cause analysis!
            )
        except Exception as e:
            # Fallback in case of LLM errors
            return score_result.ScoreResult(
                name=self.name,
                value=0.0,
                reason=f"Error during evaluation: {str(e)}",
            )


# Create metric instance
def answer_correctness_score(
    dataset_item: dict[str, Any], llm_output: str
) -> score_result.ScoreResult:
    """
    Wrapper function for the answer correctness metric.

    This function extracts the reference answer from the dataset item
    and calls the metric's score method.
    """
    correctness_metric = AnswerCorrectnessMetric(
        model="openai/gpt-4o-mini"  # Fast model for judging
    )

    reference_answer = dataset_item.get("answer", "")
    return correctness_metric.score(output=llm_output, reference=reference_answer)


# Load dataset
dataset = hotpot_300()

# Define initial prompt
system_prompt = """Answer the question with a direct, accurate response.
You have access to a Wikipedia search tool - use it to find relevant information before answering.
Provide concise answers based on the search results."""

prompt = ChatPrompt(
    project_name="HierarchicalReflective-Hotpot",
    system=system_prompt,
    user="{question}",
    tools=[
        {
            "type": "function",
            "function": {
                "name": "search_wikipedia",
                "description": "Search Wikipedia for information about a topic. Returns relevant article abstracts.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "The search query - a topic, person, place, or concept to look up.",
                        },
                    },
                    "required": ["query"],
                },
            },
        },
    ],
    function_map={
        "search_wikipedia": opik.track(type="tool")(
            lambda query: search_wikipedia(query, use_api=True)
        )
    },
)


# Initialize the Hierarchical Reflective Optimizer
optimizer = HierarchicalReflectiveOptimizer(
    model="openai/gpt-4o",  # Model for analysis and improvement
    n_threads=4,  # Parallel evaluation threads
    max_parallel_batches=3,  # Batches analyzed concurrently
    model_parameters={"temperature": 0.7, "max_tokens": 4096},
    seed=42,
    verbose=1,  # Show progress
)

# Run optimization
optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=answer_correctness_score,
    n_samples=50,  # Use 50 samples for evaluation
    max_retries=2,  # Retry improvements up to 2 times if they don't help
)

optimization_result.display()
