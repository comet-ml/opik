"""
LLMJudge evaluator for evaluation suites.

This module provides an LLM-as-a-judge evaluator that can be stored
in the backend and used with evaluation suites. The evaluator can
evaluate one or more assertions/criteria against the agent's output.
"""

from typing import Any, Dict, List, Optional

from opik.evaluation.models import models_factory
from opik.evaluation.metrics import score_result

from opik.evaluation.suite_evaluators import base
from . import config as llm_judge_config
from . import parsers


LLM_JUDGE_SYSTEM_PROMPT = """You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.

For each assertion, provide:
- score: true if the assertion passes, false if it fails
- reason: A brief explanation of your judgment
- confidence: A float between 0.0 and 1.0 indicating how confident you are in your judgment
"""

LLM_JUDGE_USER_TEMPLATE = """## Input
The INPUT section contains all data that the agent received. This may include the actual user query, conversation history, context, metadata, or other structured information. Identify the core user request within this data.

{input}

## Output
The OUTPUT section contains all data produced by the agent. This may include the agent's response text, tool calls, intermediate results, metadata, or other structured information. Focus on the substantive response when evaluating assertions.

{output}

## Assertions
Evaluate each of the following assertions against the agent's output:

{assertions}
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
    """Generate the LLM query for evaluating assertions.

    Combines the system prompt and user template into a single string
    because the model's generate_string API accepts only one input string.
    """
    assertions_str = "\n".join(f"- {assertion}" for assertion in assertions)

    user_content = LLM_JUDGE_USER_TEMPLATE.format(
        input=_format_value(input),
        output=_format_value(output),
        assertions=assertions_str,
    )
    return LLM_JUDGE_SYSTEM_PROMPT + "\n" + user_content


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
        name: The name of the evaluator (used as prefix for score names).
            Defaults to "llm_judge".
        model: The model name to use for evaluation. If not provided,
            uses the default model (gpt-5-nano).
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
        name: str = "llm_judge",
        model: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        seed: Optional[int] = None,
        temperature: Optional[float] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)

        self._assertions: List[str] = list(assertions)

        self._seed = seed
        self._temperature = temperature
        self._model_name: str = model or llm_judge_config.DEFAULT_MODEL_NAME
        self._init_model(temperature=temperature)

    @property
    def assertions(self) -> List[str]:
        """The assertions this evaluator checks (read-only copy)."""
        return list(self._assertions)

    def _init_model(
        self,
        temperature: Optional[float],
    ) -> None:
        model_kwargs = {}
        if temperature is not None:
            model_kwargs["temperature"] = temperature
        if self._seed is not None:
            model_kwargs["seed"] = self._seed

        self._model = models_factory.get(
            model_name=self._model_name, track=self.track, **model_kwargs
        )

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
                - name: The assertion text
                - value: True if passed, False if failed
                - reason: Explanation from the judge
        """
        llm_query = _generate_prompt(
            input=input,
            output=output,
            assertions=self._assertions,
        )
        response_format = parsers.build_response_format_model(self._assertions)
        model_output = self._model.generate_string(
            input=llm_query, response_format=response_format
        )

        return parsers.parse_model_output(
            content=model_output,
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
        response_format = parsers.build_response_format_model(self._assertions)
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=response_format
        )

        return parsers.parse_model_output(
            content=model_output,
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
            ...     temperature=0.0,
            ... )
            >>> config = evaluator.to_config()
        """
        model_config = llm_judge_config.LLMJudgeModelConfig(
            name=None,
            temperature=self._temperature,
            seed=self._seed,
        )

        messages = [
            llm_judge_config.LLMJudgeMessage(
                role="SYSTEM",
                content=LLM_JUDGE_SYSTEM_PROMPT,
            ),
            llm_judge_config.LLMJudgeMessage(
                role="USER",
                content=LLM_JUDGE_USER_TEMPLATE,
            ),
        ]

        variables = {
            "input": "input",
            "output": "output",
        }

        schema_items = [
            llm_judge_config.LLMJudgeSchemaItem(
                name=assertion,
                type="BOOLEAN",
                description=assertion,
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
        track: bool = True,
        project_name: Optional[str] = None,
        init_kwargs: Optional[Dict[str, Any]] = None,
    ) -> "LLMJudge":
        """
        Create an LLMJudge instance from a configuration.

        This method allows reconstructing an LLMJudge from a serialized config,
        typically retrieved from the backend's online evaluation system.

        Args:
            config: LLMJudgeConfig with model, messages, variables, and schema.
            track: Whether to track the evaluator. Defaults to True.
            project_name: Optional project name for tracking.
            init_kwargs: Optional dictionary to override __init__ parameters.
                Supported: 'model' to specify the model name.

        Returns:
            LLMJudge: A new instance configured according to the provided config.

        Example:
            >>> config = llm_judge_config.LLMJudgeConfig(
            ...     model=llm_judge_config.LLMJudgeModelConfig(temperature=0.0),
            ...     messages=[llm_judge_config.LLMJudgeMessage(role="USER", content="...")],
            ...     variables={"input": "input", "output": "output"},
            ...     schema=[llm_judge_config.LLMJudgeSchemaItem(name="accurate", type="BOOLEAN", description="Response is accurate")],
            ... )
            >>> evaluator = LLMJudge.from_config(config, init_kwargs={"model": "gpt-4o"})
        """
        assertion_texts = [item.description for item in config.schema_]

        init_kwargs = init_kwargs or {}
        model = init_kwargs.get("model")

        return cls(
            assertions=assertion_texts,
            name=config.name,
            model=model,
            track=track,
            project_name=project_name,
            seed=config.model.seed,
            temperature=config.model.temperature,
        )
