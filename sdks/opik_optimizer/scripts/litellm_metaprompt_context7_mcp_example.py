from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict

from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.utils.mcp import (
    MCPManifest,
    call_tool_from_manifest,
    dump_mcp_signature,
    extract_description_from_system,
    list_tools_from_manifest,
    load_tool_signature_from_manifest,
    response_to_text,
    system_prompt_from_tool,
)


# Update the manifest to point at your context7 MCP server implementation.
# If you set CONTEXT7_API_KEY in your environment it will be passed to the
# server; otherwise you can call the script without credentials for public data
# or craft your own `args` array.
context7_args = ["-y", "@upstash/context7-mcp"]
context7_api_key = os.getenv("CONTEXT7_API_KEY")
if context7_api_key:
    context7_args += ["--api-key", context7_api_key]

MCP_MANIFEST = MCPManifest.from_dict(
    {
        "name": "context7-docs",
        "command": "npx",
        "args": context7_args,
        "env": {},
    }
)

# Pick the tool name from the list logged at runtime (context7 server exposes
# "resolve-library-id" and "get-library-docs").
TOOL_NAME = "get-library-docs"


def doc_lookup(**arguments: Any) -> str:
    """Invoke the MCP tool with the provided arguments and return plain text."""

    response = call_tool_from_manifest(MCP_MANIFEST, TOOL_NAME, dict(arguments))
    text = response_to_text(response)
    print(f"[context7:{TOOL_NAME}] arguments={arguments} -> preview={text[:160]!r}")
    return text


def context7_metric(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    expected = dataset_item.get("expected_answer_contains", "").lower()
    score = 1.0 if expected and expected in llm_output.lower() else 0.0
    return ScoreResult(score=score)


dataset = load_context7_dataset()

all_tools = list_tools_from_manifest(MCP_MANIFEST)
available_names = [getattr(tool, "name", None) for tool in all_tools]
print("MCP tools available:", available_names)

signature = load_tool_signature_from_manifest(MCP_MANIFEST, TOOL_NAME)

original_signature_path = Path("artifacts/context7_original_signature.json")
original_signature_path.parent.mkdir(parents=True, exist_ok=True)
dump_mcp_signature([signature], original_signature_path)
print(f"Original signature written to {original_signature_path}")

sample_item = dataset.get_items(nb_samples=1)[0]
sample_args = sample_item.get("arguments", {})
sample_preview = response_to_text(
    call_tool_from_manifest(MCP_MANIFEST, TOOL_NAME, dict(sample_args))
)
print("Sample tool output preview:", sample_preview[:200].replace("\n", " "))
system_prompt = system_prompt_from_tool(signature)

prompt = ChatPrompt(
    system=system_prompt,
    user="{user_query}",
    tools=[signature.to_tool_entry()],
    function_map={TOOL_NAME: doc_lookup},
)

meta_optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",
    max_rounds=2,
    num_prompts_per_round=3,
    improvement_threshold=0.01,
    temperature=0.2,
    n_threads=1,
    subsample_size=min(5, len(dataset.get_items())),
)

meta_result = meta_optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=context7_metric,
    n_samples=len(dataset.get_items()),
)

if meta_result.best_prompt is None:
    raise RuntimeError(
        "MetaPromptOptimizer did not return an optimized prompt. Check MCP responses and optimizer logs."
    )

optimized_prompt = meta_result.best_prompt

meta_result.display()

maybe_description = extract_description_from_system(optimized_prompt.system or "")
if maybe_description:
    signature.description = maybe_description
    optimized_prompt.tools = [signature.to_tool_entry()]

output_signature_path = Path("artifacts/context7_tuned_signature.json")
output_signature_path.parent.mkdir(parents=True, exist_ok=True)
dump_mcp_signature([signature], output_signature_path)
cache_dir = Path("artifacts/.litellm_cache").resolve()
os.environ.setdefault("LITELLM_CACHE_PATH", str(cache_dir))
cache_dir.mkdir(parents=True, exist_ok=True)
