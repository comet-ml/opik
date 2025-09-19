from __future__ import annotations

from typing import Any, Dict

from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import ChatPrompt, FewShotBayesianOptimizer
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.utils.mcp import (
    MCPManifest,
    call_tool_from_manifest,
    list_tools_from_manifest,
    response_to_text,
)


# Update the manifest to point at your context7 MCP server implementation.
MCP_MANIFEST = MCPManifest.from_dict(
    {
        "name": "context7-docs",
        "command": "python",
        "args": ["/path/to/context7_server.py"],
        "env": {},
    }
)

TOOL_NAME = "doc_lookup"


def doc_lookup(query: str, **_: Any) -> str:
    """Invoke the MCP tool and normalise the response to text."""

    response = call_tool_from_manifest(MCP_MANIFEST, TOOL_NAME, {"query": query})
    return response_to_text(response)


def context7_metric(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    expected = dataset_item.get("expected_answer_contains", "").lower()
    score = 1.0 if expected and expected in llm_output.lower() else 0.0
    return ScoreResult(score=score)


dataset = load_context7_dataset()

# Pull the baseline tool metadata from the MCP server.
tool = next(tool for tool in list_tools_from_manifest(MCP_MANIFEST) if tool.name == TOOL_NAME)
tool_description = getattr(tool, "description", "") or ""
tool_parameters = getattr(tool, "input_schema", {}) or {}

system_prompt = """
You answer questions about the context7 SDK. Always consider whether the
MCP tool `doc_lookup` can help and call it when you need concrete
documentation snippets to answer the question.
"""

prompt = ChatPrompt(
    system=system_prompt,
    user="{user_query}",
    tools=[
        {
            "type": "function",
            "function": {
                "name": TOOL_NAME,
                "description": tool_description,
                "parameters": tool_parameters,
            },
        }
    ],
    function_map={TOOL_NAME: doc_lookup},
)

optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o-mini",
    min_examples=1,
    max_examples=4,
    n_threads=2,
    seed=42,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=context7_metric,
    n_trials=5,
    n_samples=len(dataset.get_items()),
)

result.display()
