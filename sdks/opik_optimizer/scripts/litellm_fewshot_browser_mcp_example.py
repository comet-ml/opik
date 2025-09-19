from __future__ import annotations

from typing import Any, Dict

from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import ChatPrompt, FewShotBayesianOptimizer
from opik_optimizer.datasets.browser_eval import load_browser_dataset
from opik_optimizer.utils.mcp import (
    MCPManifest,
    call_tool_from_manifest,
    list_tools_from_manifest,
    response_to_text,
)


# Update the manifest to point at your browser MCP server implementation.
MCP_MANIFEST = MCPManifest.from_dict(
    {
        "name": "browser-info",
        "command": "python",
        "args": ["/path/to/browser_server.py"],
        "env": {},
    }
)

TOOL_NAME = "browser_open"


def browser_open(url: str, **_: Any) -> str:
    response = call_tool_from_manifest(MCP_MANIFEST, TOOL_NAME, {"url": url})
    return response_to_text(response)


def browser_metric(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    expected = dataset_item.get("expected_answer_contains", "").lower()
    score = 1.0 if expected and expected in llm_output.lower() else 0.0
    return ScoreResult(score=score)


dataset = load_browser_dataset()

tool = next(tool for tool in list_tools_from_manifest(MCP_MANIFEST) if tool.name == TOOL_NAME)
tool_description = getattr(tool, "description", "") or ""
tool_parameters = getattr(tool, "input_schema", {}) or {}

system_prompt = """
You browse the web to answer factual developer questions. When URLs are
provided or implied, call the MCP tool `browser_open` to fetch the page
and incorporate the result into your answer.
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
    function_map={TOOL_NAME: browser_open},
)

optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o-mini",
    min_examples=1,
    max_examples=4,
    n_threads=2,
    seed=7,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=browser_metric,
    n_trials=5,
    n_samples=len(dataset.get_items()),
)

result.display()
