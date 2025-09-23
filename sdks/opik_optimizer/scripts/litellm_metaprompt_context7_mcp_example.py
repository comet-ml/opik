from __future__ import annotations

import logging
import os
import textwrap
from pathlib import Path
from typing import Any, Dict

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.utils import (
    MCPManifest,
    MCPSecondPassCoordinator,
    MCPToolInvocation,
    call_tool_from_manifest,
    create_summary_var,
    dump_mcp_signature,
    extract_description_from_system,
    extract_tool_arguments,
    list_tools_from_manifest,
    load_tool_signature_from_manifest,
    make_follow_up_builder,
    make_similarity_metric,
    response_to_text,
    system_prompt_from_tool,
)

logger = logging.getLogger("opik_optimizer.examples.context7")

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

TOOL_NAME = "get-library-docs"


def _context7_argument_adapter(arguments: Dict[str, Any], call_tool) -> Dict[str, Any]:
    prepared = dict(arguments)
    library_id = prepared.get("context7CompatibleLibraryID")
    if not library_id:
        query = prepared.get("library_query") or prepared.get("context7LibraryQuery")
        if query:
            logger.debug("Resolving context7 library id for query=%r", query)
            response = call_tool("resolve-library-id", {"query": query})
            library_id = response_to_text(response).strip()
            prepared["context7CompatibleLibraryID"] = library_id
    return prepared


def _context7_summary_builder(tool_output: str, arguments: Dict[str, Any]) -> str:
    snippet = tool_output[:800]
    args = dict(arguments)
    args.setdefault("context7CompatibleLibraryID", "unknown")
    args.setdefault("topic", "unspecified")
    return textwrap.dedent(
        """
        CONTEXT7_DOC_RESULT
        Library ID: {library_id}
        Topic: {topic}
        Instructions: In your final reply, explicitly mention the library ID and key terms from the documentation snippet.
        Documentation Snippet:
        {snippet}
        """
    ).strip().format(
        library_id=args.get("context7CompatibleLibraryID", "unknown"),
        topic=args.get("topic", "unspecified"),
        snippet=snippet,
    )


FOLLOW_UP_TEMPLATE = (
    "Using the documentation snippet above, answer the user's question and mention important identifiers. "
    "Summary: {summary} Question: {user_query}"
)

CONTEXT7_SECOND_PASS = MCPSecondPassCoordinator(
    tool_name=TOOL_NAME,
    summary_var=create_summary_var("context7_tool_summary"),
    follow_up_builder=make_follow_up_builder(FOLLOW_UP_TEMPLATE),
)

tool_invocation = MCPToolInvocation(
    manifest=MCP_MANIFEST,
    tool_name=TOOL_NAME,
    summary_handler=CONTEXT7_SECOND_PASS,
    summary_builder=_context7_summary_builder,
    argument_adapter=_context7_argument_adapter,
    rate_limit_sleep=1.0,
    preview_label="context7/get-library-docs",
)

dataset = load_context7_dataset()

all_tools = list_tools_from_manifest(MCP_MANIFEST)
available_names = [getattr(tool, "name", None) for tool in all_tools]
logger.info("MCP tools available: %s", available_names)

signature = load_tool_signature_from_manifest(MCP_MANIFEST, TOOL_NAME)

artifacts_dir = Path("artifacts")
artifacts_dir.mkdir(parents=True, exist_ok=True)

original_signature_path = artifacts_dir / "context7_original_signature.json"
dump_mcp_signature([signature], original_signature_path)
logger.info("Original signature written to %s", original_signature_path)


def _preview_tool(dataset_item: Dict[str, Any]) -> None:
    sample_args = extract_tool_arguments(dataset_item)
    if not sample_args:
        logger.warning("No sample arguments available for preview.")
        return
    response = call_tool_from_manifest(MCP_MANIFEST, TOOL_NAME, dict(sample_args))
    preview = response_to_text(response)[:200].replace("\n", " ")
    logger.info("Sample tool output preview: %s", preview)


try:
    sample_item = dataset.get_items(nb_samples=1)[0]
    logger.debug("Sample dataset item: %s", sample_item)
    _preview_tool(sample_item)
except Exception as exc:  # pragma: no cover - best-effort logging
    logger.warning("Failed to fetch sample tool output: %s", exc)


system_prompt = system_prompt_from_tool(signature, MCP_MANIFEST)
resolve_preamble = (
    "Before using get-library-docs, call resolve-library-id with the user's textual query "
    "to retrieve a UID. Then call get-library-docs with that UID and the topic. Always use "
    "the tool outputs in your final answer."
)
system_prompt = "\n\n".join(filter(None, [resolve_preamble, system_prompt])).strip()

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
    second_pass=CONTEXT7_SECOND_PASS,
    fallback_invoker=tool_invocation,
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

output_signature_path = artifacts_dir / "context7_tuned_signature.json"
dump_mcp_signature([signature], output_signature_path)
logger.info("Optimized signature written to %s", output_signature_path)
