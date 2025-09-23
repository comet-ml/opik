from __future__ import annotations

import copy
import logging
import os
import textwrap
from pathlib import Path
from typing import Any, Dict

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.utils import (
    MCPManifest,
    MCPToolInvocation,
    apply_tool_entry_from_prompt,
    call_tool_from_manifest,
    create_second_pass_coordinator,
    dump_signature_artifact,
    ensure_argument_via_resolver,
    extract_tool_arguments,
    list_manifest_tools,
    load_manifest_tool_signature,
    make_similarity_metric,
    preview_tool_output,
    system_prompt_from_tool,
    extract_description_from_system,
)

logger = logging.getLogger("opik_optimizer.examples.context7")

# Configure the Context7 MCP server. Include the API key when available.
MCP_MANIFEST = MCPManifest.from_dict(
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

argument_adapter = ensure_argument_via_resolver(
    target_field="context7CompatibleLibraryID",
    resolver_tool="resolve-library-id",
    query_fields=("library_query", "context7LibraryQuery"),
)

def summary_builder(tool_output: str, arguments: Dict[str, Any]) -> str:
    scoped_args = dict(arguments)
    library_id = scoped_args.get("context7CompatibleLibraryID", "unknown")
    topic = scoped_args.get("topic", "unspecified")
    snippet = tool_output[:800]
    return textwrap.dedent(
        f"""
        CONTEXT7_DOC_RESULT
        Library ID: {library_id}
        Topic: {topic}
        Instructions: In your final reply, explicitly mention the library ID and key terms from the documentation snippet.
        Documentation Snippet:
        {snippet}
        """
    ).strip()


FOLLOW_UP_TEMPLATE = (
    "Using the documentation snippet above, answer the user's question and mention important identifiers. "
    "Summary: {summary} Question: {user_query}"
)

second_pass = create_second_pass_coordinator(
    TOOL_NAME,
    FOLLOW_UP_TEMPLATE,
    summary_var_name="context7_tool_summary",
)

tool_invocation = MCPToolInvocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    summary_handler=second_pass,
    summary_builder=summary_builder,
    argument_adapter=argument_adapter,
    rate_limit_sleep=1.0,
    preview_label="context7/get-library-docs",
)

# Discover the tools exposed by the manifest so it’s easy to swap providers.
_, available_tool_names = list_manifest_tools(MCP_MANIFEST)
logger.info("MCP tools available: %s", available_tool_names)

signature = load_manifest_tool_signature(MCP_MANIFEST, TOOL_NAME)
original_tool_entry = copy.deepcopy(signature.to_tool_entry())
artifacts_dir = Path("artifacts")
dump_signature_artifact(signature, artifacts_dir, "context7_original_signature.json")

# Preview a single tool invocation to validate wiring.
dataset = load_context7_dataset()
try:
    sample_item = dataset.get_items(nb_samples=1)[0]
    sample_args = extract_tool_arguments(sample_item)
    if sample_args:
        sample_args = argument_adapter(
            sample_args,
            lambda name, payload: call_tool_from_manifest(MCP_MANIFEST, name, payload),
        )
        preview_tool_output(MCP_MANIFEST, TOOL_NAME, sample_args, logger=logger)
    else:
        logger.warning("No sample arguments available for preview.")
except Exception as exc:  # pragma: no cover - best-effort logging
    logger.warning("Failed to fetch sample tool output: %s", exc)

raw_system_prompt = textwrap.dedent(system_prompt_from_tool(signature, MCP_MANIFEST)).strip()
resolve_preamble = textwrap.dedent(
    """
    Before using get-library-docs, call resolve-library-id with the user's textual query to retrieve a UID.
    Then call get-library-docs with that UID and the topic. Always use the tool outputs in your final answer.
    """
).strip()
system_prompt = "\n\n".join(filter(None, [resolve_preamble, raw_system_prompt])).strip()

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

context7_metric = make_similarity_metric("context7")

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
    "context7_tuned_signature.json",
)
logger.info("Optimized signature written to %s", final_signature_path)
