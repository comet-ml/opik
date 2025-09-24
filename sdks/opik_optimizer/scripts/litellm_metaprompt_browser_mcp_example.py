from __future__ import annotations

import copy
import logging
import textwrap
from pathlib import Path
from typing import Any, Dict

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.browser_eval import load_browser_dataset
from opik_optimizer.utils import (
    MCPManifest,
    MCPToolInvocation,
    apply_tool_entry_from_prompt,
    create_second_pass_coordinator,
    dump_signature_artifact,
    extract_description_from_system,
    list_manifest_tools,
    load_manifest_tool_signature,
    make_similarity_metric,
    preview_dataset_tool_invocation,
    summarise_with_template,
    system_prompt_from_tool,
)

logger = logging.getLogger("opik_optimizer.examples.browser")

MCP_MANIFEST = MCPManifest.from_dict(
    {
        "name": "browser-info",
        "command": "npx",
        "args": ["@browsermcp/mcp@latest"],
        "env": {},
    }
)

TOOL_NAME = "browser_navigate"

summary_template = textwrap.dedent(
    """
    BROWSER_ACTION_RESULT
    Arguments: {arguments}
    Instructions: In your final reply, explain what the browser action revealed and include key phrases from the preview.
    Response Preview:
    {response}
    """
).strip()
summary_builder_base = summarise_with_template(summary_template)


def summary_builder(tool_output: str, arguments: Dict[str, Any]) -> str:
    return summary_builder_base(tool_output[:800], dict(arguments))


FOLLOW_UP_TEMPLATE = (
    "Using the browser result above, describe what happened, referencing key phrases from the preview. "
    "Summary: {summary} Question: {user_query}"
)

second_pass = create_second_pass_coordinator(
    TOOL_NAME,
    FOLLOW_UP_TEMPLATE,
    summary_var_name="browser_tool_summary",
)

tool_invocation = MCPToolInvocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    summary_handler=second_pass,
    summary_builder=summary_builder,
    preview_label="browser/browser_navigate",
)

# Discover the tools exposed by the manifest so it’s easy to swap providers.
_, available_tool_names = list_manifest_tools(MCP_MANIFEST)
logger.info("MCP tools available: %s", available_tool_names)

signature = load_manifest_tool_signature(MCP_MANIFEST, TOOL_NAME)
original_tool_entry = copy.deepcopy(signature.to_tool_entry())
artifacts_dir = Path("artifacts")
dump_signature_artifact(signature, artifacts_dir, "browser_original_signature.json")

dataset = load_browser_dataset()
preview_dataset_tool_invocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    dataset=dataset,
    logger=logger,
)

system_prompt = textwrap.dedent(system_prompt_from_tool(signature, MCP_MANIFEST)).strip()

prompt = ChatPrompt(
    system=system_prompt,
    user="{user_query}",
    tools=[signature.to_tool_entry()],
    function_map={TOOL_NAME: tool_invocation},
)

browser_metric = make_similarity_metric("browser")

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
    metric=browser_metric,
    tool_name=TOOL_NAME,
    second_pass=second_pass,
    fallback_invoker=tool_invocation,
    n_samples=len(dataset.get_items()),
    tool_panel_style="bright_cyan",
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
    signature.description = final_tool_entry.get("function", {}).get("description", signature.description)
    signature.parameters = final_tool_entry.get("function", {}).get("parameters", signature.parameters)
    signature.examples = final_tool_entry.get("function", {}).get("examples", signature.examples)
else:
    final_tool_entry = apply_tool_entry_from_prompt(
        signature,
        ChatPrompt(system=system_prompt, user="{user_query}", tools=[signature.to_tool_entry()], function_map={TOOL_NAME: tool_invocation}),
        original_tool_entry,
    )

tuned_system_prompt = textwrap.dedent(
    system_prompt_from_tool(signature, MCP_MANIFEST)
).strip()

optimized_prompt = ChatPrompt(
    system=tuned_system_prompt,
    user="{user_query}",
    tools=[final_tool_entry],
    function_map={TOOL_NAME: tool_invocation},
)
optimized_prompt.set_messages(meta_result.prompt)

final_signature_path = dump_signature_artifact(
    signature,
    artifacts_dir,
    "browser_tuned_signature.json",
)
logger.info("Optimized signature written to %s", final_signature_path)
