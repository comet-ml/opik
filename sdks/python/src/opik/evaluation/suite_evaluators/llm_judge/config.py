"""
Configuration models for LLMJudge evaluator.

These Pydantic models represent the JSON structure used by the Opik backend
for online evaluations. The configuration is stored in the
`automation_rule_evaluators.code` column and used to execute LLM-as-a-judge
evaluations on the server side.

The schema aligns with the Java backend's LlmAsJudgeCode structure:
- model: LlmAsJudgeModelParameters (name, temperature, seed, custom_parameters)
- messages: List[LlmAsJudgeMessage] (role, content)
- variables: Map<String, String>
- schema: List[LlmAsJudgeOutputSchema] (name, type, description)
"""

from typing import Any, List, Literal, Optional

import pydantic


DEFAULT_MODEL_NAME = "gpt-5-nano"


class LLMJudgeModelConfig(pydantic.BaseModel):
    """
    Model configuration for LLMJudge.

    Matches backend's LlmAsJudgeModelParameters structure.
    """

    name: Optional[str] = None
    """The model name (e.g., 'gpt-4o', 'claude-3-opus'). Optional."""

    temperature: Optional[float] = None
    """Temperature for model generation."""

    seed: Optional[int] = None
    """Seed for reproducible generation."""

    custom_parameters: Optional[dict[str, Any]] = pydantic.Field(
        default=None, alias="customParameters"
    )
    """Optional custom parameters for the model."""

    model_config = pydantic.ConfigDict(populate_by_name=True)


class LLMJudgeMessage(pydantic.BaseModel):
    """A message in the LLMJudge prompt template.

    Matches backend's LlmAsJudgeMessage structure.
    """

    role: Literal["USER", "SYSTEM", "ASSISTANT"]
    """The role of the message sender."""

    content: str
    """The content of the message."""


class LLMJudgeSchemaItem(pydantic.BaseModel):
    """Schema definition for an assertion output.

    Matches backend's LlmAsJudgeOutputSchema structure.
    """

    name: str
    """The name of the assertion/score."""

    type: Literal["INTEGER", "DOUBLE", "BOOLEAN"]
    """The type of the result (matches backend's LlmAsJudgeOutputSchemaType)."""

    description: str
    """Description of the expected behavior to check."""

    metadata: Optional[dict[str, Any]] = None
    """Optional metadata dict for additional information."""


CONFIG_VERSION = "1"


class LLMJudgeConfig(pydantic.BaseModel):
    """
    Configuration for LLMJudge evaluator.

    This structure represents the JSON format used by the Opik backend
    for online evaluations stored in `automation_rule_evaluators.code`.

    Note: The evaluator 'name' is stored separately in the automation_rule_evaluators
    table, not inside the code JSON. It's included here for SDK convenience.
    """

    version: str = CONFIG_VERSION
    """Schema version for forward-compatible deserialization."""

    name: str = "llm_judge"
    """The name of the evaluator (SDK convenience, not part of backend code JSON)."""

    model: LLMJudgeModelConfig
    """Model configuration with name, temperature, seed."""

    messages: List[LLMJudgeMessage]
    """Prompt template messages."""

    variables: dict[str, str]
    """Variable mappings from trace fields to template variables."""

    schema_: List[LLMJudgeSchemaItem] = pydantic.Field(alias="schema")
    """Output schema definitions for each assertion."""

    model_config = pydantic.ConfigDict(populate_by_name=True)
