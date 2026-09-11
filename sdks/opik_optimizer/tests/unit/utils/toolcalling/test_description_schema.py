from opik_optimizer.utils.toolcalling.ops.toolcalling import (
    ToolDescriptionCandidatesResponse,
)


def test_tool_description_schema_includes_param_descriptions() -> None:
    schema = ToolDescriptionCandidatesResponse.__get_pydantic_json_schema__(None, None)
    item_props = (
        schema.get("properties", {})
        .get("prompts", {})
        .get("items", {})
        .get("properties", {})
    )
    assert "parameter_descriptions" in item_props
    assert item_props["parameter_descriptions"].get("type") == ["array", "null"]
