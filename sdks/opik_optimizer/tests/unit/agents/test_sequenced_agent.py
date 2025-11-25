from typing import Any

from benchmarks.agents.sequenced_agent import SequencedOptimizableAgent
from opik_optimizer import ChatPrompt


def test_sequenced_agent_propagates_step_outputs() -> None:
    """Ensure sequenced agent runs prompts in order and surfaces outputs."""

    # Prompt 1 echoes the question
    p1 = ChatPrompt(name="create_query_1", user="{question}")
    p1.invoke = lambda messages: "first-query"  # type: ignore[assignment]

    # Prompt 2 consumes previous step output
    p2 = ChatPrompt(name="summarize_1", user="{question} {create_query_1}")
    p2.invoke = lambda messages: "summary-using-" + messages[-1]["content"]  # type: ignore[assignment]

    calls: dict[str, Any] = {}

    def handle_search(item: dict[str, Any], query: str) -> dict[str, Any]:
        calls["search"] = query
        item = dict(item)
        item["passages_1"] = "doc1"
        return item

    agent = SequencedOptimizableAgent(
        prompts={"create_query_1": p1, "summarize_1": p2},
        plan=["create_query_1", "summarize_1"],
        step_handlers={"create_query_1": handle_search},
    )

    result = agent.run({"question": "who"})

    assert result["final_output"].startswith("summary-using-")
    assert calls["search"] == "first-query"
    steps = result["trace"]["steps"]
    assert [s["agent"] for s in steps] == ["create_query_1", "summarize_1"]
    # Ensure the second step saw the first output populated
    assert any("first-query" in s["messages"][-1]["content"] for s in steps)
