"""Type definitions for the Reflective Optimizer."""

from dataclasses import dataclass
from typing import Any, Literal
from pydantic import BaseModel, create_model, ConfigDict

from ...api_objects import types
from ...utils.toolcalling.ops.toolcalling import (
    ToolDescriptionUpdate,
    ToolParameterUpdate,
)


@dataclass
class MessageDiffItem:
    """Represents a single message's diff information."""

    role: str
    change_type: Literal["added", "removed", "unchanged", "changed"]
    initial_content: str | None
    optimized_content: str | None


class FailureMode(BaseModel):
    """Model for a single failure mode identified in evaluation."""

    name: str
    description: str
    root_cause: str


class RootCauseAnalysis(BaseModel):
    """Model for root cause analysis response."""

    failure_modes: list[FailureMode]


class BatchAnalysis(BaseModel):
    """Model for a single batch analysis result."""

    batch_number: int
    start_index: int
    end_index: int
    failure_modes: list[FailureMode]


class HierarchicalRootCauseAnalysis(BaseModel):
    """Model for the final hierarchical root cause analysis."""

    total_test_cases: int
    num_batches: int
    unified_failure_modes: list[FailureMode]
    synthesis_notes: str


class ImprovedPrompt(BaseModel):
    """Model for improved prompt response."""

    reasoning: str
    messages: list[types.Message]
    tool_descriptions: list[ToolDescriptionUpdate] | None = None
    parameter_descriptions: list[ToolParameterUpdate] | None = None


def _fix_schema_for_openai(schema: dict[str, Any]) -> None:
    """
    Recursively fix JSON schema for OpenAI's structured output requirements:
    - All objects must have additionalProperties: false
    - All schemas in anyOf/oneOf/allOf must have explicit 'type' fields
    """
    if not isinstance(schema, dict):
        return

    # Add type: object for schemas with properties but no type
    if "properties" in schema and "type" not in schema:
        schema["type"] = "object"

    # Set additionalProperties: false for all object types
    if schema.get("type") == "object":
        schema["additionalProperties"] = False

    # Process all nested schemas
    for key in ["properties", "$defs", "definitions"]:
        if key in schema and isinstance(schema[key], dict):
            for nested in schema[key].values():
                _fix_schema_for_openai(nested)

    # Process array items
    if "items" in schema and isinstance(schema["items"], dict):
        _fix_schema_for_openai(schema["items"])

    # Process anyOf/oneOf/allOf - ensure each variant has a type
    for key in ["anyOf", "oneOf", "allOf"]:
        if key in schema and isinstance(schema[key], list):
            for sub in schema[key]:
                if isinstance(sub, dict):
                    # Empty schema {} needs a type - default to string
                    if "type" not in sub and "$ref" not in sub:
                        if "properties" in sub:
                            sub["type"] = "object"
                        elif not sub or ("const" not in sub and "enum" not in sub):
                            sub["type"] = "string"
                    _fix_schema_for_openai(sub)


def create_improved_prompts_response_model(prompt_names: list[str]) -> type[BaseModel]:
    """
    Create a dynamic Pydantic model for improved prompts response.

    Generates a model with explicit fields for each prompt name, where each field
    contains the improved prompt (reasoning + messages) for that specific prompt.

    Args:
        prompt_names: List of prompt names to create fields for

    Returns:
        A dynamic Pydantic model class with fields for each prompt name
    """
    # Create fields mapping prompt_name -> ImprovedPrompt
    fields = {name: (ImprovedPrompt, ...) for name in prompt_names}

    DynamicModel = create_model(  # type: ignore[call-overload]
        "ImprovedPromptsResponse",
        **fields,
    )
    DynamicModel.model_config = ConfigDict(extra="forbid")

    # Apply schema fixes for OpenAI compatibility
    original_schema = DynamicModel.model_json_schema

    def fixed_schema(cls: type[BaseModel], **kwargs: Any) -> dict[str, Any]:
        schema = original_schema(**kwargs)
        _fix_schema_for_openai(schema)
        return schema

    DynamicModel.model_json_schema = classmethod(fixed_schema)  # type: ignore[assignment]
    return DynamicModel
