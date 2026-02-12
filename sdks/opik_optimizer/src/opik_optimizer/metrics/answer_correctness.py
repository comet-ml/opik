from typing import Any
from pydantic import BaseModel
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation import models
import json
from .. import constants


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
        model: str = constants.DEFAULT_MODEL,
    ):
        super().__init__(name=name)
        self.model_name = model
        self.llm_client = models.LiteLLMChatModel(model_name=model)

    def score(
        self, output: str, reference: str, **_ignored_kwargs: Any
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
