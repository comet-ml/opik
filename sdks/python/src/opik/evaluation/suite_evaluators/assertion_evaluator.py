from typing import Any, Union, Optional, List, Literal
import pydantic

from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics import score_result

from . import template, parser
from .base import BaseSuiteEvaluator


class AssertionResultFormat(pydantic.BaseModel):
    """Pydantic model for structured output parsing from LLM."""

    class AssertionScoreResultMetadata(pydantic.BaseModel):
        pass_score: float

    class AssertionScoreResult(pydantic.BaseModel):
        name: str
        value: int
        reason: str
        metadata: "AssertionResultFormat.AssertionScoreResultMetadata"

    results: List[AssertionScoreResult]


class AssertionEvaluatorModelConfig(pydantic.BaseModel):
    """Model configuration for AssertionEvaluator."""

    name: str
    """The model name (e.g., 'gpt-4o', 'claude-3-opus')."""

    temperature: Optional[float] = None
    """Temperature for model generation."""

    seed: Optional[int] = None
    """Seed for reproducible generation."""


class AssertionEvaluatorMessage(pydantic.BaseModel):
    """A message in the AssertionEvaluator prompt template."""

    role: Literal["USER", "SYSTEM", "ASSISTANT"]
    """The role of the message sender."""

    content: str
    """The content of the message."""


class AssertionEvaluatorSchemaItem(pydantic.BaseModel):
    """Schema definition for an assertion output."""

    name: str
    """The name of the assertion."""

    type: Literal["INTEGER", "FLOAT", "STRING", "BOOLEAN"]
    """The type of the assertion result."""

    description: str
    """Description of what the assertion checks."""


class AssertionEvaluatorConfig(pydantic.BaseModel):
    """
    Configuration for AssertionEvaluator.

    This structure mirrors the backend's LLM-as-Judge code format
    for online evaluations stored in `automation_rule_evaluators.code`.
    """

    model: AssertionEvaluatorModelConfig
    """Model configuration with name, temperature, seed."""

    messages: List[AssertionEvaluatorMessage]
    """Prompt template messages."""

    variables: dict[str, str]
    """Variable mappings from trace fields to template variables."""

    schema_: List[AssertionEvaluatorSchemaItem] = pydantic.Field(alias="schema")
    """Output schema definitions for each assertion."""

    model_config = pydantic.ConfigDict(populate_by_name=True)


# Re-export Assertion for convenience
Assertion = template.Assertion


class AssertionEvaluator(BaseSuiteEvaluator):
    """
    Evaluates whether an agent's output satisfies a set of user-defined assertions.

    This class is designed for use with evaluation suites, not the raw evaluate function.
    It uses an LLM to judge if the output passes or fails each assertion,
    returning multiple ScoreResult objects (one per assertion).

    The class provides `to_config()` and `from_config()` methods for serialization
    that are compatible with the backend's online evaluation system.

    Args:
        assertions: A list of assertions to evaluate. Each assertion should have
            a 'name' and 'description' key.
        model: The LLM to use for evaluation. Can be a string (model name) or
            an `opik.evaluation.models.OpikBaseModel` subclass instance.
            `opik.evaluation.models.LiteLLMChatModel` is used by default.
        name: The name of the evaluator (used as prefix for score names).
        track: Whether to track the evaluator. Defaults to True.
        project_name: Optional project name to track the evaluator in.
        seed: Optional seed value for reproducible model generation.
        temperature: Optional temperature value for model generation.

    Example:
        >>> from opik.evaluation.suite_asserters import AssertionEvaluator
        >>> evaluator = AssertionEvaluator(
        ...     assertions=[
        ...         {"name": "factual", "description": "The response is factually correct"},
        ...         {"name": "helpful", "description": "The response is helpful to the user"},
        ...     ]
        ... )
        >>> results = evaluator.score(
        ...     input="What is the capital of France?",
        ...     output="The capital of France is Paris."
        ... )
        >>> for result in results:
        ...     print(f"{result.name}: {result.value}")
        assertion_evaluator_factual: 1.0
        assertion_evaluator_helpful: 1.0
    """

    def __init__(
        self,
        assertions: List[template.Assertion],
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "assertion_evaluator",
        track: bool = True,
        project_name: Optional[str] = None,
        seed: Optional[int] = None,
        temperature: Optional[float] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self._assertions = assertions
        self._seed = seed
        self._temperature = temperature
        self._model_name = model if isinstance(model, str) else None
        self._init_model(model, temperature=temperature)

    def _init_model(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]],
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
                - name: "{asserter_name}_{assertion_name}"
                - value: 1.0 if passed, 0.0 if failed
                - reason: Explanation from the judge
                - metadata: {"pass_score": float, "assertion_text": str}
        """
        llm_query = template.generate_query(
            input=input,
            output=output,
            assertions=self._assertions,
        )
        model_output = self._model.generate_string(
            input=llm_query, response_format=AssertionResultFormat
        )

        return parser.parse_model_output(
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
        llm_query = template.generate_query(
            input=input,
            output=output,
            assertions=self._assertions,
        )
        model_output = await self._model.agenerate_string(
            input=llm_query, response_format=AssertionResultFormat
        )

        return parser.parse_model_output(
            content=model_output,
            name=self.name,
            assertions=self._assertions,
        )

    def to_config(self) -> AssertionEvaluatorConfig:
        """
        Serialize the evaluator configuration to a Pydantic model compatible
        with the backend's online evaluation system.

        The returned structure mirrors the `LlmAsJudgeCode` JSON format used
        in the `automation_rule_evaluators.code` column.

        Returns:
            AssertionEvaluatorConfig: A Pydantic model containing:
                - model: Model configuration (name, temperature, seed)
                - messages: Prompt template messages
                - variables: Variable mappings for trace field extraction
                - schema: Output schema definitions for each assertion

        Example:
            >>> evaluator = AssertionEvaluator(
            ...     assertions=[{"name": "accurate", "description": "Response is accurate"}],
            ...     model="gpt-4o",
            ...     temperature=0.0,
            ... )
            >>> config = evaluator.to_config()
            >>> config.model.name
            'gpt-4o'
        """
        model_config = AssertionEvaluatorModelConfig(
            name=self._model_name or "gpt-4o",
            temperature=self._temperature,
            seed=self._seed,
        )

        assertions_text = "\n".join(
            f"- **{assertion['name']}**: {assertion['description']}"
            for assertion in self._assertions
        )

        messages = [
            AssertionEvaluatorMessage(
                role="USER",
                content=template.LLM_JUDGE_TEMPLATE.replace(
                    "{assertions}", assertions_text
                ),
            )
        ]

        variables = {
            "input": "input",
            "output": "output",
        }

        schema_items = [
            AssertionEvaluatorSchemaItem(
                name=assertion["name"],
                type="INTEGER",
                description=assertion["description"],
            )
            for assertion in self._assertions
        ]

        return AssertionEvaluatorConfig(
            model=model_config,
            messages=messages,
            variables=variables,
            schema=schema_items,
        )

    @classmethod
    def from_config(
        cls,
        config: AssertionEvaluatorConfig,
        name: str = "assertion_evaluator",
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> "AssertionEvaluator":
        """
        Create an AssertionEvaluator instance from a configuration.

        This method allows reconstructing an AssertionEvaluator from a serialized config,
        typically retrieved from the backend's online evaluation system.

        Args:
            config: AssertionEvaluatorConfig with model, messages, variables, and schema.
            name: The name of the evaluator. Defaults to "assertion_evaluator".
            track: Whether to track the evaluator. Defaults to True.
            project_name: Optional project name for tracking.

        Returns:
            AssertionEvaluator: A new instance configured according to the provided config.

        Example:
            >>> config = AssertionEvaluatorConfig(
            ...     model=AssertionEvaluatorModelConfig(name="gpt-4o", temperature=0.0),
            ...     messages=[AssertionEvaluatorMessage(role="USER", content="...")],
            ...     variables={"input": "input", "output": "output"},
            ...     schema=[AssertionEvaluatorSchemaItem(name="accurate", type="INTEGER", description="Response is accurate")],
            ... )
            >>> evaluator = AssertionEvaluator.from_config(config)
        """
        assertions: List[template.Assertion] = [
            template.Assertion(
                name=item.name,
                description=item.description,
            )
            for item in config.schema_
        ]

        return cls(
            assertions=assertions,
            model=config.model.name,
            name=name,
            track=track,
            project_name=project_name,
            seed=config.model.seed,
            temperature=config.model.temperature,
        )
