"""
Configuration models for LLMJudge evaluator.

These Pydantic models represent the JSON structure used by the Opik backend
for online evaluations. The configuration is stored in the
`automation_rule_evaluators.code` column and used to execute LLM-as-a-judge
evaluations on the server side.
"""

from typing import List, Literal, Optional

import pydantic


class LLMJudgeResultFormat(pydantic.BaseModel):
    """Pydantic model for structured output parsing from LLM."""

    class AssertionResult(pydantic.BaseModel):
        name: str
        value: bool
        reason: str
        confidence: float

    results: List[AssertionResult]


class LLMJudgeModelConfig(pydantic.BaseModel):
    """
    Model configuration for LLMJudge.

    This structure represents the JSON format used by the Opik backend
    for online evaluations to configure the LLM model.
    """

    name: str
    """The model name (e.g., 'gpt-4o', 'claude-3-opus')."""

    temperature: Optional[float] = None
    """Temperature for model generation."""

    seed: Optional[int] = None
    """Seed for reproducible generation."""


class LLMJudgeMessage(pydantic.BaseModel):
    """A message in the LLMJudge prompt template."""

    role: Literal["USER", "SYSTEM", "ASSISTANT"]
    """The role of the message sender."""

    content: str
    """The content of the message."""


class LLMJudgeSchemaItem(pydantic.BaseModel):
    """Schema definition for an assertion output."""

    name: str
    """The name of the assertion."""

    type: Literal["INTEGER", "FLOAT", "STRING", "BOOLEAN"]
    """The type of the assertion result."""

    expected_behavior: str
    """Description of the expected behavior to check."""


class LLMJudgeConfig(pydantic.BaseModel):
    """
    Configuration for LLMJudge evaluator.

    This structure represents the JSON format used by the Opik backend
    for online evaluations stored in `automation_rule_evaluators.code`.
    """

    name: str = "llm_judge"
    """The name of the evaluator (used as prefix for score names)."""

    model: LLMJudgeModelConfig
    """Model configuration with name, temperature, seed."""

    messages: List[LLMJudgeMessage]
    """Prompt template messages."""

    variables: dict[str, str]
    """Variable mappings from trace fields to template variables."""

    schema_: List[LLMJudgeSchemaItem] = pydantic.Field(alias="schema")
    """Output schema definitions for each assertion."""

    model_config = pydantic.ConfigDict(populate_by_name=True)
