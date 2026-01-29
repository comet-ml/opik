from typing import Any
from pydantic import BaseModel, ConfigDict, create_model
from ...api_objects import types


class FewShotPromptMessagesBase(BaseModel):
    """Base model for few-shot prompt messages containing only the template field."""

    model_config = ConfigDict(extra="forbid")
    template: str


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


def create_few_shot_response_model(prompt_names: list[str]) -> type[BaseModel]:
    """
    Create a dynamic Pydantic model for few-shot prompt responses.

    Generates a model with explicit fields for each prompt name, with a
    custom JSON schema that satisfies OpenAI's structured output requirements.
    """
    fields = {name: (list[types.Message], ...) for name in prompt_names}
    DynamicModel = create_model(  # type: ignore[call-overload]
        "FewShotPromptMessages",
        __base__=FewShotPromptMessagesBase,
        **fields,
    )
    DynamicModel.model_config = ConfigDict(extra="forbid")

    # Wrap schema generation to fix OpenAI compatibility issues
    original_schema = DynamicModel.model_json_schema

    def fixed_schema(cls: type[BaseModel], **kwargs: Any) -> dict[str, Any]:
        schema = original_schema(**kwargs)
        _fix_schema_for_openai(schema)
        return schema

    DynamicModel.model_json_schema = classmethod(fixed_schema)  # type: ignore[assignment]
    return DynamicModel
