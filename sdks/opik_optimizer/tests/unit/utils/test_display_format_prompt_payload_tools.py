from opik_optimizer.utils.display import format as display_format


def test_format_prompt_payload_includes_tool_summaries() -> None:
    payload = {
        "messages": [{"role": "system", "content": "sys"}],
        "tools": [
            {
                "type": "function",
                "function": {"name": "search", "description": "Search docs"},
                "mcp": {"server_label": "context7", "tool": {"name": "query-docs"}},
            }
        ],
    }
    rendered = display_format.format_prompt_payload(payload, pretty=True)
    assert "TOOLS" in rendered
    assert "context7.query-docs" in rendered
