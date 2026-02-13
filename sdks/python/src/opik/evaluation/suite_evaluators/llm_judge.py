"""
LLMJudge evaluator for evaluation suites.

This module provides an LLM-as-a-judge evaluator that can be stored
in the backend and used with evaluation suites. The evaluator can
evaluate one or more assertions/criteria against the agent's output.
"""

from typing import Any, Optional, List

import pydantic

from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics import score_result

from . import base
from . import opik_llm_judge_config as llm_judge_config


def _build_response_format_example(assertions: List[str]) -> str:
    """
    Build a dynamic JSON response format example based on the assertions.
    """
    results_items = [
        f'        {{"name": "{assertion}", "value": <true or false>, "reason": "<brief explanation>", "confidence": <0.0 to 1.0>}}'
        for assertion in assertions
    ]
    return '{\n    "results": [\n' + ",\n".join(results_items) + "\n    ]\n}"


LLM_JUDGE_TEMPLATE = """You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.

## Input
The INPUT section contains all data that the agent received. This may include the actual user query, conversation history, context, metadata, or other structured information. Identify the core user request within this data.

{input}

## Output
The OUTPUT section contains all data produced by the agent. This may include the agent's response text, tool calls, intermediate results, metadata, or other structured information. Focus on the substantive response when evaluating assertions.

{output}

## Assertions
Evaluate each of the following assertions against the agent's output:

{assertions}

For each assertion, determine if it passes (true) or fails (false).
Also provide a confidence score between 0.0 and 1.0 indicating how confident you are in your judgment.

Provide your answer in the following JSON format:
{response_format}

Output must be JSON format only. Evaluate ALL assertions provided.
"""


def _format_value(value: Any) -> str:
    """Format a value for inclusion in the prompt."""
    if isinstance(value, str):
        return value
    import json

    return json.dumps(value, indent=2, default=str)


def _generate_prompt(
    input: Any,
    output: Any,
    assertions: List[str],
) -> str:
    """Generate the LLM query for evaluating assertions."""
    assertions_str = "\n".join(f"- {assertion}" for assertion in assertions)

    return LLM_JUDGE_TEMPLATE.format(
        input=_format_value(input),
        output=_format_value(output),
        assertions=assertions_str,
        response_format=_build_response_format_example(assertions),
    )


def _parse_model_output(
    content: str,
    name: str,
    assertions: List[str],
) -> List[score_result.ScoreResult]:
    """Parse the model output into ScoreResults."""
    import json

    results: List[score_result.ScoreResult] = []

    try:
        parsed = json.loads(content)
        validated = llm_judge_config.LLMJudgeResultFormat(**parsed)

        for item in validated.results:
            results.append(
                score_result.ScoreResult(
                    name=item.name,
                    value=item.value,
                    reason=item.reason,
                    metadata={"confidence": item.confidence},
                )
            )

    except (json.JSONDecodeError, pydantic.ValidationError) as e:
        for assertion in assertions:
            results.append(
                score_result.ScoreResult(
                    name=assertion,
                    value=False,
                    reason=f"Failed to parse model output: {e}",
                    metadata={
                        "raw_output": content,
                        "parsing_error": True,
                    },
                )
            )

    return results


class LLMJudge(base.BaseSuiteEvaluator):
    """
    LLM-as-a-judge evaluator for evaluation suites.

    This evaluator uses an LLM to judge whether an agent's output satisfies
    one or more assertions/criteria. It returns a ScoreResult for each assertion.

    The evaluator configuration can be serialized via `to_config()` and
    `from_config()` for storage in the backend's online evaluation system.

    Args:
        assertions: A list of assertion strings describing expected behaviors.
            Each string should describe what the output should satisfy.
        model: The LLM to use for evaluation. Can be a string (model name) or
            an `opik.evaluation.models.OpikBaseModel` subclass instance.
            `opik.evaluation.models.LiteLLMChatModel` is used by default.
        name: The name of the evaluator (used as prefix for score names).
            Defaults to "llm_judge".
        track: Whether to track the evaluator. Defaults to True.
        project_name: Optional project name for tracking.
        seed: Optional seed value for reproducible model generation.
        temperature: Optional temperature value for model generation.

    Example:
        >>> from opik.evaluation.suite_evaluators import LLMJudge
        >>>
        >>> evaluator = LLMJudge(
        ...     assertions=[
        ...         "Response does not contain hallucinated information",
        ...         "Response is helpful to the user",
        ...     ]
        ... )
        >>>
        >>> # Score an input/output pair
        >>> results = evaluator.score(
        ...     input="What is the capital of France?",
        ...     output="The capital of France is Paris."
        ... )
        >>> for result in results:
        ...     print(f"{result.name}: {result.value}")
    """

    def __init__(
        self,
        assertions: List[str],
        model: Optional[str | base_model.OpikBaseModel] = None,
        name: str = "llm_judge",
        track: bool = True,
        project_name: Optional[str] = None,
        seed: Optional[int] = None,
        temperature: Optional[float] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)

        self._assertions: List[str] = list(assertions)

        self._seed = seed
        self._temperature = temperature
        self._model_name = model if isinstance(model, str) else None
        self._init_model(model, temperature=temperature)

    @property
    def assertions(self) -> List[str]:
        """The assertions this evaluator checks (read-only copy)."""
        return list(self._assertions)

    def _init_model(
        self,
        model: Optional[str | base_model.OpikBaseModel],
        temperature: Optional[float],
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
            self._model_name = getattr(model, "model_name", None)
        else:
            model_kwargs = {}
            if temperature is not None:
                model_kwargs["temperature"] = temperature
            if self._seed is not None:
                model_kwargs["seed"] = self._seed

            self._model = models_factory.get(
                model_name=model, track=self.track, **model_kwargs
            )
            self._model_name = model

    def score(
        self,
        input: Any,
        output: Any,
        **ignored_kwargs: Any,
    ) -> List[score_result.ScoreResult]:
        """
        Evaluate the output against all assertions.

        Args:
            input: All inputs that the agent received. Can be a string, dict, list,
                or any JSON-serializable structure containing the user query and metadata.
            output: All outputs from the agent. Can be a string, dict, list,
                or any JSON-serializable structure containing the response and metadata.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            List[ScoreResult]: A list of ScoreResult objects, one per assertion.
                Each result has:
                - name: "{evaluator_name}_{assertion_name}"
                - value: 1.0 if passed, 0.0 if failed
                - reason: Explanation from the judge
                - metadata: {"confidence": float, "expected_behavior": str}
        """
        llm_query = _generate_prompt(
            input=input,
            output=output,
            assertions=self._assertions,
        )
        model_output = self._model.generate_string(
            input=llm_query, response_format=llm_judge_config.LLMJudgeResultFormat
        )

        return _parse_model_output(
            content=model_output,
            name=self.name,
            assertions=self._assertions,
        )

    async def ascore(
        self,
        input: Any,
        output: Any,
        **ignored_kwargs: Any,
    ) -> List[score_result.ScoreResult]:
        """
        Asynchronously evaluate the output against all assertions.

        Args:
            input: All inputs that the agent received. Can be a string, dict, list,
                or any JSON-serializable structure containing the user query and metadata.
            output: All outputs from the agent. Can be a string, dict, list,
                or any JSON-serializable structure containing the response and metadata.
            **ignored_kwargs: Additional keyword arguments that are ignored.

        Returns:
            List[ScoreResult]: A list of ScoreResult objects, one per assertion.
        """
        llm_query = _generate_prompt(
            input=input,
            output=output,
            assertions=self._assertions,
        )
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=llm_judge_config.LLMJudgeResultFormat
        )

        return _parse_model_output(
            content=model_output,
            name=self.name,
            assertions=self._assertions,
        )

    def to_config(self) -> llm_judge_config.LLMJudgeConfig:
        """
        Serialize the evaluator configuration to a Pydantic model compatible
        with the backend's online evaluation system.

        The returned structure mirrors the `LlmAsJudgeCode` JSON format used
        in the `automation_rule_evaluators.code` column.

        Returns:
            LLMJudgeConfig: A Pydantic model containing:
                - model: Model configuration (name, temperature, seed)
                - messages: Prompt template messages
                - variables: Variable mappings for trace field extraction
                - schema: Output schema definitions for each assertion

        Example:
            >>> evaluator = LLMJudge(
            ...     assertions=["Response is accurate"],
            ...     model="gpt-4o",
            ...     temperature=0.0,
            ... )
            >>> config = evaluator.to_config()
            >>> config.model.name
            'gpt-4o'
        """
        model_config = llm_judge_config.LLMJudgeModelConfig(
            name=self._model_name or "gpt-4o",
            temperature=self._temperature,
            seed=self._seed,
        )

        assertions_text = "\n".join(f"- {assertion}" for assertion in self._assertions)
        response_format = _build_response_format_example(self._assertions)

        prompt_content = LLM_JUDGE_TEMPLATE.replace(
            "{assertions}", assertions_text
        ).replace("{response_format}", response_format)

        messages = [
            llm_judge_config.LLMJudgeMessage(
                role="USER",
                content=prompt_content,
            )
        ]

        variables = {
            "input": "input",
            "output": "output",
        }

        schema_items = [
            llm_judge_config.LLMJudgeSchemaItem(
                name=assertion,
                type="BOOLEAN",
                expected_behavior=assertion,
            )
            for assertion in self._assertions
        ]

        return llm_judge_config.LLMJudgeConfig(
            name=self.name,
            model=model_config,
            messages=messages,
            variables=variables,
            schema=schema_items,
        )

    @classmethod
    def from_config(
        cls,
        config: llm_judge_config.LLMJudgeConfig,
        name: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> "LLMJudge":
        """
        Create an LLMJudge instance from a configuration.

        This method allows reconstructing an LLMJudge from a serialized config,
        typically retrieved from the backend's online evaluation system.

        Args:
            config: LLMJudgeConfig with model, messages, variables, and schema.
            name: The name of the evaluator. If not provided, uses the name from config.
            track: Whether to track the evaluator. Defaults to True.
            project_name: Optional project name for tracking.

        Returns:
            LLMJudge: A new instance configured according to the provided config.

        Example:
            >>> config = llm_judge_config.LLMJudgeConfig(
            ...     model=llm_judge_config.LLMJudgeModelConfig(name="gpt-4o", temperature=0.0),
            ...     messages=[llm_judge_config.LLMJudgeMessage(role="USER", content="...")],
            ...     variables={"input": "input", "output": "output"},
            ...     schema=[llm_judge_config.LLMJudgeSchemaItem(name="accurate", type="BOOLEAN", expected_behavior="Response is accurate")],
            ... )
            >>> evaluator = LLMJudge.from_config(config)
        """
        # Extract assertion texts from config schema
        assertion_texts = [item.expected_behavior for item in config.schema_]

        return cls(
            assertions=assertion_texts,
            model=config.model.name,
            name=name if name is not None else config.name,
            track=track,
            project_name=project_name,
            seed=config.model.seed,
            temperature=config.model.temperature,
        )
