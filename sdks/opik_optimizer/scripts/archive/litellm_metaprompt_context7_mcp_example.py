from __future__ import annotations

import copy
import os
import textwrap
from pathlib import Path
from typing import Any
from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets import context7_eval
from opik_optimizer.mcp_utils.mcp import system_prompt_from_tool, MCPManifest
from opik_optimizer.mcp_utils import mcp_workflow as mcp_flow

# ---------------------------------------------------------------------------
# CONTEXT7-SPECIFIC CONFIGURATION
#
# All Context7-specific settings are grouped here for easy modification.
# To use with other MCP servers, replace these settings with your server's
# configuration and adjust the helper components accordingly.
# ---------------------------------------------------------------------------

# MCP Server Configuration
# Note: to get a CONTEXT7_API_KEY go to
# https://context7.com/, then Dashboard, and request key
# It's free!

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

# Context7-specific dataset
dataset = context7_eval()

# Context7-specific tool name
TOOL_NAME = "get-library-docs"

# Context7-specific metric
context7_metric = mcp_flow.make_similarity_metric("context7")

# Context7-specific argument adapter settings
ARGUMENT_ADAPTER_CONFIG: dict[str, Any] = {
    "target_field": "context7CompatibleLibraryID",
    "resolver_tool": "resolve-library-id",
    "query_fields": ("library_query", "context7LibraryQuery"),
}

# Context7-specific summary builder settings
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

# Context7-specific follow-up template
FOLLOW_UP_TEMPLATE = (
    "Using the documentation snippet above, answer the user's question and mention important identifiers. "
    "Summary: {summary} Question: {user_query}"
)

# Context7-specific tool invocation settings
TOOL_INVOCATION_CONFIG: dict[str, Any] = {
    "rate_limit_sleep": 5.0,
    "preview_label": "context7/get-library-docs",
    "summary_var_name": "context7_tool_summary",
}

# ---------------------------------------------------------------------------
# MCP SERVER SETUP (Generic - works with any MCP server)
# ---------------------------------------------------------------------------

# Create the MCP manifest from the configuration
MCP_MANIFEST = MCPManifest.from_dict(MCP_SERVER_CONFIG)

# ---------------------------------------------------------------------------
# CREATE HELPER COMPONENTS (Context7-specific)
# ---------------------------------------------------------------------------

# Create argument adapter
argument_adapter = mcp_flow.ensure_argument_via_resolver(
    target_field=str(ARGUMENT_ADAPTER_CONFIG["target_field"]),
    resolver_tool=str(ARGUMENT_ADAPTER_CONFIG["resolver_tool"]),
    query_fields=ARGUMENT_ADAPTER_CONFIG["query_fields"],
)

# Create summary builder
summary_builder = mcp_flow.make_argument_summary_builder(
    heading=str(SUMMARY_BUILDER_CONFIG["heading"]),
    instructions=str(SUMMARY_BUILDER_CONFIG["instructions"]),
    argument_labels=SUMMARY_BUILDER_CONFIG["argument_labels"],
    preview_chars=int(SUMMARY_BUILDER_CONFIG["preview_chars"]),
)

# Create second pass coordinator
second_pass = mcp_flow.create_second_pass_coordinator(
    TOOL_NAME,
    FOLLOW_UP_TEMPLATE,
    summary_var_name=str(TOOL_INVOCATION_CONFIG["summary_var_name"]),
)

# Create tool invocation
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
# LOAD TOOL SIGNATURE AND PREVIEW
# ---------------------------------------------------------------------------

# Load the tool signature and dump it to a file
mcp_flow.list_manifest_tools(MCP_MANIFEST)
signature = mcp_flow.load_manifest_tool_signature(MCP_MANIFEST, TOOL_NAME)
original_tool_entry = copy.deepcopy(signature.to_tool_entry())
artifacts_dir = Path("artifacts")
mcp_flow.dump_signature_artifact(signature, artifacts_dir, "original_signature.json")

# Execute a smoke-test call using the first dataset item
mcp_flow.preview_dataset_tool_invocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    dataset=dataset,
    argument_adapter=argument_adapter,
)

# ---------------------------------------------------------------------------
# BUILD PROMPTS AND OPTIMIZE
# ---------------------------------------------------------------------------

# Build the system prompt and chat prompt
system_prompt = textwrap.dedent(
    system_prompt_from_tool(signature, MCP_MANIFEST)
).strip()
prompt = ChatPrompt(
    system=system_prompt,
    user="{user_query}",
    tools=[signature.to_tool_entry()],
    function_map={TOOL_NAME: tool_invocation},
)

# Optimize the tool invocation
meta_optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",
    prompts_per_round=3,
    n_threads=1,
    model_parameters={"temperature": 0.2},
)
meta_result = meta_optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=context7_metric,
    tool_name=TOOL_NAME,
    second_pass=second_pass,
    max_trials=6,  # Number of total trials (max_rounds * num_prompts_per_round)
    fallback_invoker=lambda args: tool_invocation.invoke(args),
    n_samples=min(5, len(dataset.get_items())),
    tool_panel_style="bright_magenta",
    optimize_prompts=False,
    optimize_tools=True,
)

if not meta_result.prompt:
    raise RuntimeError(
        "MetaPromptOptimizer did not return an optimized prompt. Check MCP responses and optimizer logs."
    )

if meta_result.tool_prompts and TOOL_NAME in meta_result.tool_prompts:
    signature.description = meta_result.tool_prompts[TOOL_NAME]

final_tools = (
    meta_result.details.get("final_tools")
    if isinstance(meta_result.details, dict)
    else None
)
if final_tools:
    final_tool_entry = copy.deepcopy(final_tools[0])
    mcp_flow.update_signature_from_tool_entry(signature, final_tool_entry)
else:
    final_tool_entry = copy.deepcopy(original_tool_entry)
    final_tool_entry["function"]["description"] = signature.description

tuned_system_prompt = textwrap.dedent(
    system_prompt_from_tool(signature, MCP_MANIFEST)
).strip()

# ---------------------------------------------------------------------------
# SAVE OPTIMIZED RESULTS
# ---------------------------------------------------------------------------
optimized_prompt = ChatPrompt(
    system=tuned_system_prompt,
    user="{user_query}",
    tools=[final_tool_entry],
    function_map={TOOL_NAME: tool_invocation},
)
optimized_prompt.set_messages(meta_result.prompt)

final_signature_path = mcp_flow.dump_signature_artifact(
    signature,
    artifacts_dir,
    "tuned_signature.json",
)
print(f"Optimized signature written to {final_signature_path}")
