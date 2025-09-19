from __future__ import annotations

from pathlib import Path
from typing import Any, Dict

from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.browser_eval import load_browser_dataset
from opik_optimizer.utils.mcp import (
    MCPManifest,
    call_tool_from_manifest,
    dump_mcp_signature,
    extract_description_from_system,
    load_tool_signature_from_manifest,
    response_to_text,
    system_prompt_from_tool,
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

signature = load_tool_signature_from_manifest(MCP_MANIFEST, TOOL_NAME)
system_prompt = system_prompt_from_tool(signature)

prompt = ChatPrompt(
    system=system_prompt,
    user="{user_query}",
    tools=[signature.to_tool_entry()],
    function_map={TOOL_NAME: browser_open},
)

meta_optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",
    max_rounds=2,
    num_prompts_per_round=3,
    improvement_threshold=0.01,
    temperature=0.3,
    n_threads=1,
    subsample_size=min(5, len(dataset.get_items())),
)

meta_result = meta_optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=browser_metric,
    n_samples=len(dataset.get_items()),
)

optimized_prompt = meta_result.best_prompt or prompt

maybe_description = extract_description_from_system(optimized_prompt.system or "")
if maybe_description:
    signature.description = maybe_description
    optimized_prompt.tools = [signature.to_tool_entry()]

meta_result.display()

output_signature_path = Path("artifacts/browser_tuned_signature.json")
output_signature_path.parent.mkdir(parents=True, exist_ok=True)
dump_mcp_signature([signature], output_signature_path)
