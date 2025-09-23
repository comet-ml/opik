from __future__ import annotations

import copy
import os
import textwrap
from pathlib import Path
from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.utils import mcp as mcp_utils
from opik_optimizer.utils import mcp_workflow as mcp_flow

# ---------------------------------------------------------------------------
# 1. Configure the Context7 MCP server manifest and tool name
#
# The manifest tells the SDK how to launch the MCP server that exposes the
# documentation tools.  If `CONTEXT7_API_KEY` exists we pass it to the CLI,
# otherwise the server runs in a public/demo mode.
#
# The tool name is the name of the tool that will be used to call the MCP server.
# Some MCP servers may expose multiple tools, so we need to specify the tool name.
# ---------------------------------------------------------------------------
MCP_MANIFEST = mcp_flow.MCPManifest.from_dict(
    {
        "name": "context7-docs",
        "command": "npx",
        "args": [
            "-y",
            "@upstash/context7-mcp",
            *(["--api-key", os.environ["CONTEXT7_API_KEY"]] if os.getenv("CONTEXT7_API_KEY") else []),
        ],
        "env": {},
    }
)
TOOL_NAME = "get-library-docs"

# ---------------------------------------------------------------------------
# 2. Create helper components used by the tool invocation
#
# `ensure_argument_via_resolver` adds a pre-processing step to every tool call.
# If the dataset item does not already specify `context7CompatibleLibraryID`
# we automatically invoke the resolver MCP tool using the available natural-
# language query fields and patch the arguments before hitting the main tool.
# ---------------------------------------------------------------------------
argument_adapter = mcp_flow.ensure_argument_via_resolver(
    target_field="context7CompatibleLibraryID",
    resolver_tool="resolve-library-id",
    query_fields=("library_query", "context7LibraryQuery"),
)

# ---------------------------------------------------------------------------
# 3. Create a summary builder for the tool output
#
# There are multiple passes between tools and llms
# Dataset question → LLM (with MCP tool) → MCP call → LLM follow‑up → metric
# This summary builder is used to capture the tool output and the arguments
# used to call the tool. Summaries are aggregated between the first and
# second model pass so the assistant can reference the snippet in its final
# answer while keeping the prompt structured for evaluation.
# ---------------------------------------------------------------------------
summary_builder = mcp_flow.make_argument_summary_builder(
    heading="CONTEXT7_DOC_RESULT",
    instructions=(
        "In your final reply, explicitly mention the library ID and key terms from the documentation snippet."
    ),
    argument_labels={
        "context7CompatibleLibraryID": "Library ID",
        "topic": "Topic",
    },
    preview_chars=800,
)

# ---------------------------------------------------------------------------
# 4. Create a second pass coordinator for the tool output
#
# The second pass instructs the model to answer the user question with the
# captured snippet, ensuring the evaluation reflects tool usage instead of the
# raw response returned by the MCP server.
# ---------------------------------------------------------------------------
FOLLOW_UP_TEMPLATE = (
    "Using the documentation snippet above, answer the user's question and mention important identifiers. "
    "Summary: {summary} Question: {user_query}"
)

second_pass = mcp_flow.create_second_pass_coordinator(
    TOOL_NAME,
    FOLLOW_UP_TEMPLATE,
    summary_var_name="context7_tool_summary",
)

# Wrap the MCP call in a helper that automatically tracks spans in Opik, applies
# the resolver adapter, records summaries for the second pass, and sleeps 5s to
# avoid rate limits when sampling the dataset.
tool_invocation = mcp_flow.MCPToolInvocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    summary_handler=second_pass,
    summary_builder=summary_builder,
    argument_adapter=argument_adapter,
    rate_limit_sleep=5.0,
    preview_label="context7/get-library-docs",
)

# ---------------------------------------------------------------------------
# 5. Load the tool signature and dump it to a file
#
# The tool signature is used to build the system prompt and the tool entry is
# used to build the chat prompt.
# ---------------------------------------------------------------------------
mcp_flow.list_manifest_tools(MCP_MANIFEST)
signature = mcp_flow.load_manifest_tool_signature(MCP_MANIFEST, TOOL_NAME)
original_tool_entry = copy.deepcopy(signature.to_tool_entry())
artifacts_dir = Path("artifacts")
mcp_flow.dump_signature_artifact(signature, artifacts_dir, "context7_original_signature.json")

# ---------------------------------------------------------------------------
# 6. Execute a smoke-test call using the first dataset item so the operator can
# confirm the resolver and MCP invocation succeed before running the optimizer.
# ---------------------------------------------------------------------------
dataset = load_context7_dataset()
mcp_flow.preview_dataset_tool_invocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    dataset=dataset,
    argument_adapter=argument_adapter,
)

# ---------------------------------------------------------------------------
# 7. Build the system prompt and chat prompt
#
# The system prompt is used to build the chat prompt. The chat prompt is used to
# optimize the tool invocation.
# ---------------------------------------------------------------------------
system_prompt = textwrap.dedent(mcp_utils.system_prompt_from_tool(signature, MCP_MANIFEST)).strip()
prompt = ChatPrompt(
    system=system_prompt,
    user="{user_query}",
    tools=[signature.to_tool_entry()],
    function_map={TOOL_NAME: tool_invocation},
)

# ---------------------------------------------------------------------------
# 8. Optimize the tool invocation
#
# The optimizer is used to optimize the tool invocation.
# ---------------------------------------------------------------------------
context7_metric = mcp_flow.make_similarity_metric("context7")
meta_optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",
    max_rounds=2,
    num_prompts_per_round=3,
    improvement_threshold=0.01,
    temperature=0.2,
    n_threads=1,
    subsample_size=min(5, len(dataset.get_items())),
)
meta_result = meta_optimizer.optimize_mcp(
    prompt=prompt,
    dataset=dataset,
    metric=context7_metric,
    tool_name=TOOL_NAME,
    second_pass=second_pass,
    fallback_invoker=tool_invocation,
    n_samples=len(dataset.get_items()),
    tool_panel_style="bright_magenta",
)

if not meta_result.prompt:
    raise RuntimeError(
        "MetaPromptOptimizer did not return an optimized prompt. Check MCP responses and optimizer logs."
    )

if meta_result.tool_prompts and TOOL_NAME in meta_result.tool_prompts:
    signature.description = meta_result.tool_prompts[TOOL_NAME]

final_tools = meta_result.details.get("final_tools") if isinstance(meta_result.details, dict) else None
if final_tools:
    final_tool_entry = copy.deepcopy(final_tools[0])
    mcp_flow.update_signature_from_tool_entry(signature, final_tool_entry)
else:
    final_tool_entry = copy.deepcopy(original_tool_entry)
    final_tool_entry["function"]["description"] = signature.description

tuned_system_prompt = textwrap.dedent(
    mcp_utils.system_prompt_from_tool(signature, MCP_MANIFEST)
).strip()

# ---------------------------------------------------------------------------
# 9. Rebuild a `ChatPrompt` that contains the tuned system message, the updated
# tool entry, and the optimized chat messages so users can re-run the winning
# prompt directly or export it elsewhere.
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
    "context7_tuned_signature.json",
)
print(f"Optimized signature written to {final_signature_path}")
