from opik_optimizer import ChatPrompt
from opik_optimizer.core.results import OptimizationHistoryState


def test_history_payload_captures_full_tools_and_model_kwargs() -> None:
    tools = [
        {
            "type": "function",
            "function": {
                "name": "search",
                "description": "Search tool",
                "parameters": {"type": "object", "properties": {}},
            },
        }
    ]
    prompt = ChatPrompt(
        name="prompt",
        system="sys",
        user="hi",
        model_kwargs={"allow_tool_use": True, "temperature": 0.2},
    )
    prompt.tools = tools
    payload = OptimizationHistoryState._normalize_candidate_payload(prompt)
    assert "prompt" in payload
    prompt_payload = payload["prompt"]
    assert prompt_payload["messages"][0]["role"] == "system"
    assert prompt_payload["tools"] == tools
    assert prompt_payload["model_kwargs"]["temperature"] == 0.2
