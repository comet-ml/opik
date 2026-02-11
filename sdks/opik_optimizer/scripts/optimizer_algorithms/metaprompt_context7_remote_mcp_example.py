from __future__ import annotations

import os

from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.utils.toolcalling.normalize.tool_factory import (
    ToolCallingFactory,
    cursor_mcp_config_to_tools,
)
from opik_optimizer.utils.toolcalling.runtime import mcp_remote
from opik_optimizer.utils.toolcalling.runtime.mcp import ToolCallingDependencyError


CONTEXT7_URL = "https://mcp.context7.com/mcp"
ALLOWED_TOOLS = ["resolve-library-id", "query-docs"]

# Remote MCP works without API key for listing tools. Add key if available.
context7_api_key = os.getenv("CONTEXT7_API_KEY", "").strip()
headers = {"CONTEXT7_API_KEY": context7_api_key} if context7_api_key else {}

try:
    tools_live = mcp_remote.list_tools_from_remote(url=CONTEXT7_URL, headers={})
    print(
        f"Discovered remote MCP tools: {[getattr(t, 'name', '') for t in tools_live[:10]]}"
    )
except ToolCallingDependencyError as exc:
    raise RuntimeError(
        "MCP SDK is not installed. Install optional dependency `mcp` first."
    ) from exc


def extract_tool_descriptions(prompt: ChatPrompt) -> dict[str, str]:
    """Return function-name -> description for tool entries."""
    resolved_prompt = ToolCallingFactory().resolve_prompt(prompt)
    descriptions: dict[str, str] = {}
    for tool in resolved_prompt.tools or []:
        function = tool.get("function", {})
        name = function.get("name")
        if isinstance(name, str):
            descriptions[name] = str(function.get("description", ""))
    return descriptions


# ---------------------------------------------------------------------------
# Cursor-style MCP config (active path)
# ---------------------------------------------------------------------------
cursor_config = {
    "mcpServers": {
        "context7": {
            "url": CONTEXT7_URL,
            "headers": headers,
        }
    }
}
tools = cursor_mcp_config_to_tools(cursor_config)
# Default behavior: when `allowed_tools` is omitted, the SDK resolves all tools
# exposed by the MCP server.
# for tool in tools:
#     tool["allowed_tools"] = ALLOWED_TOOLS

# ---------------------------------------------------------------------------
# OpenAI-style MCP tool entry (alternative)
# ---------------------------------------------------------------------------
# tools = [
#     {
#         "type": "mcp",
#         "server_label": "context7",
#         "server_url": CONTEXT7_URL,
#         "headers": headers,
#         "allowed_tools": ALLOWED_TOOLS,
#     }
# ]

dataset = load_context7_dataset(test_mode=False)
scorer = LevenshteinRatio()


def context7_metric(dataset_item: dict, llm_output: str):  # type: ignore[no-untyped-def]
    return scorer.score(
        reference=str(dataset_item.get("reference_answer", "")),
        output=llm_output,
    )


prompt = ChatPrompt(
    system=(
        "You are a documentation assistant. Use MCP tools to resolve a library id "
        "and fetch docs before answering. Keep responses concise and grounded."
    ),
    user="{user_query}",
    tools=tools,
)
before_descriptions = extract_tool_descriptions(prompt)
print(f"Dataset size: {len(dataset.get_items())}")
print("Initial tool descriptions:")
for name, description in sorted(before_descriptions.items()):
    print(f"- {name}: {description[:140]}")

optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",
    prompts_per_round=4,
    n_threads=1,
    verbose=1,
    model_parameters={
        "temperature": 0.2,
    },
)

optimization_result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=context7_metric,
    max_trials=6,
    n_samples=3,
    allow_tool_use=True,
    optimize_tools=True,
)
optimization_result.display()

optimized_prompt = optimization_result.prompt
if isinstance(optimized_prompt, dict):
    optimized_prompt = next(iter(optimized_prompt.values()))

if optimized_prompt is None:
    raise RuntimeError("No optimized prompt returned.")

after_descriptions = extract_tool_descriptions(optimized_prompt)
print("\nTool description changes:")
changed = 0
for name in sorted(after_descriptions):
    before = before_descriptions.get(name, "")
    after = after_descriptions[name]
    if before != after:
        changed += 1
        print(f"- CHANGED {name}")
        print(f"  before: {before[:180]}")
        print(f"  after:  {after[:180]}")
if changed == 0:
    print("- No signature description changes detected.")
