"""
Multimodal LLM-as-a-Judge metric for evaluating outputs with image context.

This metric uses vision-capable LLMs to evaluate model outputs by comparing them
to expected outputs while considering image inputs. It supports both structured
content (OpenAI format with text and image_url parts) and placeholder formats.
"""

from typing import Any, Dict, List, Optional, Union
import json
import pydantic

# Import from Opik SDK
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory, model_capabilities


class MultimodalJudgeResponse(pydantic.BaseModel):
    """Response format for multimodal judge evaluation."""
    match_score: float = pydantic.Field(
        description="Match score between 0.0 and 1.0, where 1.0 means perfect match"
    )
    reasoning: str = pydantic.Field(
        description="Detailed explanation for the score"
    )


# Type alias for multimodal content
MessageContent = Union[str, List[Dict[str, Any]]]


class MultimodalLLMJudge(base_metric.BaseMetric):
    """
    LLM-as-a-Judge metric with multimodal support for image-based evaluation.

    This metric uses vision-capable language models (like GPT-4o, Claude 3, Gemini)
    to evaluate model outputs by comparing them to expected outputs while considering
    image inputs. It returns a match score between 0.0 and 1.0.

    Args:
        model: The vision-capable model to use for evaluation. Can be a string
            (model name like "gpt-4o-mini") or an OpikBaseModel instance.
            Must support vision/image inputs.
        name: The name of the metric. Defaults to "multimodal_llm_judge".
        track: Whether to track the metric with Opik. Defaults to True.
        project_name: Optional project name for tracking.
        evaluation_criteria: Custom criteria for evaluation. If not provided,
            uses default criteria focused on semantic match and accuracy.

    Example:
        >>> from opik_optimizer.metrics import MultimodalLLMJudge
        >>>
        >>> judge = MultimodalLLMJudge(model="gpt-4o-mini")
        >>>
        >>> # Structured content with image
        >>> image_content = [
        ...     {"type": "text", "text": "What hazards do you see?"},
        ...     {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
        ... ]
        >>>
        >>> result = judge.score(
        ...     input=image_content,
        ...     output="I see a pedestrian crossing ahead",
        ...     expected_output="Pedestrian in crosswalk"
        ... )
        >>> print(result.value)  # 0.85
        >>> print(result.reason)  # "The output correctly identifies..."
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "multimodal_llm_judge",
        track: bool = True,
        project_name: Optional[str] = None,
        evaluation_criteria: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )

        self._init_model(model)
        self._verify_vision_support()
        self._evaluation_criteria = evaluation_criteria or self._default_criteria()

    def _init_model(self, model: Optional[Union[str, base_model.OpikBaseModel]]) -> None:
        """Initialize the evaluation model."""
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            # Default to gpt-4o-mini if no model specified
            model_name = model or "gpt-4o-mini"
            self._model = models_factory.get(model_name=model_name)

    def _verify_vision_support(self) -> None:
        """Verify that the model supports vision capabilities."""
        if not model_capabilities.supports_vision(self._model.model_name):
            raise ValueError(
                f"Model '{self._model.model_name}' does not support vision. "
                f"Please use a vision-capable model like 'gpt-4o', 'gpt-4o-mini', "
                f"'claude-3-opus', 'claude-3-sonnet', 'gemini-1.5-pro', etc."
            )

    def _default_criteria(self) -> str:
        """Default evaluation criteria for output matching."""
        return """Evaluate how well the output matches the expected output based on:
1. Semantic accuracy: Does the output convey the same meaning?
2. Completeness: Does the output include all key information?
3. Visual understanding: Does the output correctly interpret the image?
4. Precision: Is the output specific and accurate?

Consider minor wording differences acceptable if the meaning is preserved."""

    def score(
        self,
        input: MessageContent,
        output: str,
        expected_output: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Evaluate the output against the expected output with image context.

        Args:
            input: The input content, either a string or structured content with images.
                For structured content (OpenAI format):
                [
                    {"type": "text", "text": "Question text"},
                    {"type": "image_url", "image_url": {"url": "data:image/..."}}
                ]
            output: The actual output from the model being evaluated.
            expected_output: The expected/reference output to compare against.
            **ignored_kwargs: Additional kwargs that are ignored.

        Returns:
            ScoreResult with value (0.0-1.0 match score) and reason.
        """
        # Build the evaluation prompt
        evaluation_messages = self._build_evaluation_messages(
            input=input,
            output=output,
            expected_output=expected_output,
        )

        # Generate evaluation using the vision model
        try:
            response = self._model.generate_provider_response(
                messages=evaluation_messages,
                response_format=MultimodalJudgeResponse,
            )

            # Parse the response
            content = response.choices[0].message.content

            # Try to parse as JSON first
            try:
                if isinstance(content, str):
                    result_dict = json.loads(content)
                else:
                    result_dict = content

                match_score = float(result_dict.get("match_score", 0.0))
                reasoning = result_dict.get("reasoning", "No reasoning provided")
            except (json.JSONDecodeError, ValueError, TypeError):
                # Fallback: try to parse as pydantic model
                if isinstance(content, str):
                    response_obj = MultimodalJudgeResponse.model_validate_json(content)
                else:
                    response_obj = MultimodalJudgeResponse.model_validate(content)
                match_score = response_obj.match_score
                reasoning = response_obj.reasoning

            # Ensure score is in valid range
            match_score = max(0.0, min(1.0, match_score))

            return score_result.ScoreResult(
                name=self.name,
                value=match_score,
                reason=reasoning,
            )

        except Exception as e:
            # If evaluation fails, return a low score with error reason
            return score_result.ScoreResult(
                name=self.name,
                value=0.0,
                reason=f"Evaluation failed: {str(e)}",
            )

    async def ascore(
        self,
        input: MessageContent,
        output: str,
        expected_output: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        Async version of score method.

        Args:
            input: The input content with optional images.
            output: The actual output from the model.
            expected_output: The expected output to compare against.
            **ignored_kwargs: Additional kwargs that are ignored.

        Returns:
            ScoreResult with match score and reasoning.
        """
        evaluation_messages = self._build_evaluation_messages(
            input=input,
            output=output,
            expected_output=expected_output,
        )

        try:
            response = await self._model.agenerate_provider_response(
                messages=evaluation_messages,
                response_format=MultimodalJudgeResponse,
            )

            content = response.choices[0].message.content

            try:
                if isinstance(content, str):
                    result_dict = json.loads(content)
                else:
                    result_dict = content

                match_score = float(result_dict.get("match_score", 0.0))
                reasoning = result_dict.get("reasoning", "No reasoning provided")
            except (json.JSONDecodeError, ValueError, TypeError):
                if isinstance(content, str):
                    response_obj = MultimodalJudgeResponse.model_validate_json(content)
                else:
                    response_obj = MultimodalJudgeResponse.model_validate(content)
                match_score = response_obj.match_score
                reasoning = response_obj.reasoning

            match_score = max(0.0, min(1.0, match_score))

            return score_result.ScoreResult(
                name=self.name,
                value=match_score,
                reason=reasoning,
            )

        except Exception as e:
            return score_result.ScoreResult(
                name=self.name,
                value=0.0,
                reason=f"Evaluation failed: {str(e)}",
            )

    def _build_evaluation_messages(
        self,
        input: MessageContent,
        output: str,
        expected_output: str,
    ) -> List[Dict[str, Any]]:
        """
        Build the messages for the evaluation prompt.

        This creates a conversation that includes:
        1. System message with evaluation criteria
        2. User message with the original input (including any images)
        3. User message with actual and expected outputs for comparison
        """
        messages = []

        # System message with evaluation criteria
        system_prompt = f"""You are an expert evaluator assessing the quality of AI model outputs.

Your task is to compare the actual output with the expected output, considering the input context (including any images).

{self._evaluation_criteria}

Provide your evaluation as JSON with:
- match_score: A number between 0.0 and 1.0 (where 1.0 is perfect match)
- reasoning: Detailed explanation for your score

Be fair and objective in your evaluation."""

        messages.append({
            "role": "system",
            "content": system_prompt,
        })

        # User message with original input (may include images)
        if isinstance(input, str):
            input_content = input
        elif isinstance(input, list):
            # Structured content - preserve as-is for vision model
            input_content = input
        else:
            input_content = str(input)

        messages.append({
            "role": "user",
            "content": self._format_input_message(input_content),
        })

        # User message with comparison request
        comparison_text = f"""Now evaluate this output:

**Expected Output:**
{expected_output}

**Actual Output:**
{output}

Provide your evaluation as JSON with match_score (0.0-1.0) and reasoning."""

        messages.append({
            "role": "user",
            "content": comparison_text,
        })

        return messages

    def _format_input_message(self, input_content: MessageContent) -> MessageContent:
        """
        Format the input message for the evaluation prompt.

        Handles both string and structured content formats.
        """
        if isinstance(input_content, str):
            return f"**Original Input:**\n{input_content}"

        # Structured content - add text wrapper
        if isinstance(input_content, list):
            formatted_parts = [
                {"type": "text", "text": "**Original Input (with image):**"}
            ]
            formatted_parts.extend(input_content)
            return formatted_parts

        return str(input_content)
