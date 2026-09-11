"""Example of running EvolutionaryOptimizer with MCP-enabled tooling (Context7)."""

from __future__ import annotations

import copy
import os
import textwrap
from pathlib import Path
from typing import Any

from opik_optimizer import ChatPrompt, EvolutionaryOptimizer
from opik_optimizer.datasets import context7_eval
from opik_optimizer.mcp_utils.mcp import system_prompt_from_tool, MCPManifest
from opik_optimizer.mcp_utils import mcp_workflow as mcp_flow
from opik_optimizer.mcp_utils.mcp_second_pass import MCPSecondPassCoordinator

# ---------------------------------------------------------------------------
# CONTEXT7 CONFIGURATION
# ---------------------------------------------------------------------------

MCP_SERVER_CONFIG: dict[str, Any] = {
    "name": "context7-docs",
    "command": "npx",
    "args": [
        "-y",
        "@upstash/context7-mcp",
        *(
            ["--api-key", os.environ["CONTEXT7_API_KEY"]]
            if os.getenv("CONTEXT7_API_KEY")
            else []
        ),
    ],
    "env": {},
}

dataset = context7_eval()
TOOL_NAME = "get-library-docs"
context7_metric = mcp_flow.make_similarity_metric("context7")

ARGUMENT_ADAPTER_CONFIG: dict[str, Any] = {
    "target_field": "context7CompatibleLibraryID",
    "resolver_tool": "resolve-library-id",
    "query_fields": ("library_query", "context7LibraryQuery"),
}

SUMMARY_BUILDER_CONFIG: dict[str, Any] = {
    "heading": "CONTEXT7_DOC_RESULT",
    "instructions": (
        "In your final reply, explicitly mention the library ID and key terms from the documentation snippet."
    ),
    "argument_labels": {
        "context7CompatibleLibraryID": "Library ID",
        "topic": "Topic",
    },
    "preview_chars": 800,
}

FOLLOW_UP_TEMPLATE = (
    "Using the documentation snippet above, answer the user's question and mention important identifiers. "
    "Summary: {summary} Question: {user_query}"
)

TOOL_INVOCATION_CONFIG: dict[str, Any] = {
    "rate_limit_sleep": 5.0,
    "preview_label": "context7/get-library-docs",
    "summary_var_name": "context7_tool_summary",
}

# ---------------------------------------------------------------------------
# MCP SERVER + HELPERS
# ---------------------------------------------------------------------------

MCP_MANIFEST = MCPManifest.from_dict(MCP_SERVER_CONFIG)

argument_adapter = mcp_flow.ensure_argument_via_resolver(
    target_field=str(ARGUMENT_ADAPTER_CONFIG["target_field"]),
    resolver_tool=str(ARGUMENT_ADAPTER_CONFIG["resolver_tool"]),
    query_fields=ARGUMENT_ADAPTER_CONFIG["query_fields"],
)

summary_builder = mcp_flow.make_argument_summary_builder(
    heading=str(SUMMARY_BUILDER_CONFIG["heading"]),
    instructions=str(SUMMARY_BUILDER_CONFIG["instructions"]),
    argument_labels=SUMMARY_BUILDER_CONFIG["argument_labels"],
    preview_chars=int(SUMMARY_BUILDER_CONFIG["preview_chars"]),
)

second_pass: MCPSecondPassCoordinator = mcp_flow.create_second_pass_coordinator(
    TOOL_NAME,
    FOLLOW_UP_TEMPLATE,
    summary_var_name=str(TOOL_INVOCATION_CONFIG["summary_var_name"]),
)

tool_invocation = mcp_flow.MCPToolInvocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    summary_handler=second_pass,
    summary_builder=summary_builder,
    argument_adapter=argument_adapter,
    rate_limit_sleep=float(TOOL_INVOCATION_CONFIG["rate_limit_sleep"]),
    preview_label=str(TOOL_INVOCATION_CONFIG["preview_label"]),
)

# ---------------------------------------------------------------------------
# LOAD TOOL SIGNATURE + PREVIEW
# ---------------------------------------------------------------------------

mcp_flow.list_manifest_tools(MCP_MANIFEST)
signature = mcp_flow.load_manifest_tool_signature(MCP_MANIFEST, TOOL_NAME)
original_tool_entry = copy.deepcopy(signature.to_tool_entry())
artifacts_dir = Path("artifacts")
artifacts_dir.mkdir(exist_ok=True)
mcp_flow.dump_signature_artifact(signature, artifacts_dir, "original_signature.json")

mcp_flow.preview_dataset_tool_invocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    dataset=dataset,
    argument_adapter=argument_adapter,
)

# ---------------------------------------------------------------------------
# BUILD PROMPTS + OPTIMIZE
# ---------------------------------------------------------------------------

system_prompt = textwrap.dedent(
    system_prompt_from_tool(signature, MCP_MANIFEST)
).strip()
prompt = ChatPrompt(
    system=system_prompt,
    user="{user_query}",
    tools=[signature.to_tool_entry()],
    function_map={TOOL_NAME: tool_invocation},
)

evo_optimizer = EvolutionaryOptimizer(
    model="openai/gpt-4o-mini",
    mutation_rate=0.25,
    crossover_rate=0.6,
    elitism_size=1,
    enable_moo=False,
    enable_llm_crossover=False,
    n_threads=1,
    verbose=1,
)

result = evo_optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=context7_metric,
    tool_name=TOOL_NAME,
    second_pass=second_pass,
    population_size=4,
    num_generations=2,
    fallback_invoker=lambda args: tool_invocation.invoke(args),
    n_samples=min(5, len(dataset.get_items())),
    tool_panel_style="bright_cyan",
    optimize_tools=True,
)

if not result.prompt:
    raise RuntimeError("EvolutionaryOptimizer did not return an optimized prompt.")

if result.tool_prompts and TOOL_NAME in result.tool_prompts:
    signature.description = result.tool_prompts[TOOL_NAME]

final_tools = (
    result.details.get("final_tools") if isinstance(result.details, dict) else None
)
if final_tools:
    final_tool_entry = copy.deepcopy(final_tools[0])
    mcp_flow.update_signature_from_tool_entry(signature, final_tool_entry)
else:
    final_tool_entry = copy.deepcopy(original_tool_entry)
    final_tool_entry["function"]["description"] = signature.description

mcp_flow.dump_signature_artifact(
    signature,
    artifacts_dir,
    "optimized_signature.json",
)

print("Optimization complete! Final tool description:")
print(signature.description)
