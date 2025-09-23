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
    extract_tool_arguments,
    list_manifest_tools,
    load_manifest_tool_signature,
    make_similarity_metric,
    preview_tool_output,
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
try:
    sample_item = dataset.get_items(nb_samples=1)[0]
    sample_args = extract_tool_arguments(sample_item)
    if sample_args:
        preview_tool_output(MCP_MANIFEST, TOOL_NAME, sample_args, logger=logger)
    else:
        logger.warning("No sample arguments available for preview.")
except Exception as exc:  # pragma: no cover - best-effort logging
    logger.warning("Failed to fetch sample tool output: %s", exc)

system_prompt = textwrap.dedent(system_prompt_from_tool(signature, MCP_MANIFEST)).strip()

prompt = ChatPrompt(
    system=system_prompt,
    user="{user_query}",
    tools=[signature.to_tool_entry()],
    function_map={TOOL_NAME: tool_invocation},
)

if prompt.model is None:
    prompt.model = "openai/gpt-4o-mini"
if not prompt.model_kwargs:
    prompt.model_kwargs = {"temperature": 0.2}

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

if meta_result.best_prompt is None:
    raise RuntimeError(
        "MetaPromptOptimizer did not return an optimized prompt. Check MCP responses and optimizer logs."
    )

optimized_prompt = meta_result.best_prompt
final_tool_entry = apply_tool_entry_from_prompt(signature, optimized_prompt, original_tool_entry)

maybe_description = extract_description_from_system(optimized_prompt.system or "")
if maybe_description:
    signature.description = maybe_description
    final_tool_entry["function"]["description"] = signature.description
    optimized_prompt.tools = [final_tool_entry]

final_signature_path = dump_signature_artifact(
    signature,
    artifacts_dir,
    "browser_tuned_signature.json",
)
logger.info("Optimized signature written to %s", final_signature_path)
