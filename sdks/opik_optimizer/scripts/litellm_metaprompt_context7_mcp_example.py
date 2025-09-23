from __future__ import annotations

import os
import random
from pathlib import Path
from typing import Any, Dict, List, Optional
from difflib import SequenceMatcher
import textwrap
from contextvars import ContextVar

from opik.evaluation.metrics.score_result import ScoreResult

from opik_optimizer import ChatPrompt, MetaPromptOptimizer
from opik_optimizer.datasets.context7_eval import load_context7_dataset
from opik_optimizer.optimization_config import mappers
from opik_optimizer import task_evaluator
from opik_optimizer.utils import create_litellm_agent_class
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
from opik_optimizer.utils.mcp_second_pass import (
    MCPSecondPassCoordinator,
    extract_user_query,
)

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
LAST_TOOL_SUMMARY: ContextVar[str | None] = ContextVar("context7_tool_summary", default=None)


def _extract_arguments(item: Any) -> Dict[str, Any]:
    if isinstance(item, dict):
        if "arguments" in item:
            return item["arguments"]
        if "input" in item and isinstance(item["input"], dict):
            return item["input"].get("arguments", {})
    for attr in ("input_values", "input", "data"):
        value = getattr(item, attr, None)
        if isinstance(value, dict) and "arguments" in value:
            return value["arguments"]
    return {}


def _context7_follow_up(dataset_item: Dict[str, Any], _summary: str) -> str:
    follow_up = (
        "Using the documentation snippet above, answer the user's question explicitly "
        "mentioning any important identifiers or implementation details."
    )
    user_query = extract_user_query(dataset_item)
    if user_query:
        follow_up += f" Question: {user_query}"
    return follow_up


CONTEXT7_SECOND_PASS = MCPSecondPassCoordinator(
    tool_name=TOOL_NAME,
    summary_var=LAST_TOOL_SUMMARY,
    follow_up_builder=_context7_follow_up,
)


# TODO(opik): create a dedicated MCP-aware prompt class instead of mutating
# ChatPrompt in-place. For now we rely on this script-level glue.

def doc_lookup(**arguments: Any) -> str:
    args = dict(arguments)
    library_id = args.get("context7CompatibleLibraryID")
    if not library_id:
        query = args.get("library_query") or args.get("context7LibraryQuery")
        if query:
            resolve = call_tool_from_manifest(
                MCP_MANIFEST,
                "resolve-library-id",
                {"query": query},
            )
            library_id = response_to_text(resolve).strip()
            args["context7CompatibleLibraryID"] = library_id

    response = call_tool_from_manifest(MCP_MANIFEST, TOOL_NAME, args)
    text = response_to_text(response)
    preview = text[:160].replace("\n", " ")
    print(f"[context7:{TOOL_NAME}] arguments={args} -> preview={preview!r}")

    summary = textwrap.dedent(
        f"""
        CONTEXT7_DOC_RESULT
        Library ID: {library_id or 'unknown'}
        Topic: {args.get('topic', 'unspecified')}
        Instructions: In your final reply, explicitly mention the library ID and important terms from the documentation snippet (for example words like "Next.js", "Supabase", "aggregation" when present). Summarize the guidance in your own words.
        Documentation Snippet:
        {text[:800]}
        """
    ).strip()
    CONTEXT7_SECOND_PASS.record_summary(summary)
    return summary


def context7_metric(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    reference = (dataset_item.get("reference_answer") or "").strip()
    if not reference:
        return ScoreResult(name="context7_similarity", value=0.0, reason="Missing reference answer.")

    def _normalize(text: str) -> str:
        return " ".join(text.lower().split())

    ratio = SequenceMatcher(None, _normalize(reference), _normalize(llm_output)).ratio()
    reason = f"Levenshtein ratio {ratio:.2f} against reference."
    return ScoreResult(
        name="context7_similarity",
        value=ratio,
        reason=reason,
        metadata={"reference": reference},
    )


cache_dir = Path("artifacts/.litellm_cache").resolve()
os.environ.setdefault("LITELLM_CACHE_PATH", str(cache_dir))
cache_dir.mkdir(parents=True, exist_ok=True)

dataset = load_context7_dataset()

all_tools = list_tools_from_manifest(MCP_MANIFEST)
available_names = [getattr(tool, "name", None) for tool in all_tools]
print("MCP tools available:", available_names)

signature = load_tool_signature_from_manifest(MCP_MANIFEST, TOOL_NAME)

original_signature_path = Path("artifacts/context7_original_signature.json")
original_signature_path.parent.mkdir(parents=True, exist_ok=True)
dump_mcp_signature([signature], original_signature_path)
print(f"Original signature written to {original_signature_path}")


class MCPMetaPromptOptimizer(MetaPromptOptimizer):
    """MetaPromptOptimizer variant with second-pass MCP summarisation."""

    def evaluate_prompt(
        self,
        prompt: ChatPrompt,
        dataset,
        metric,
        n_threads: int,
        verbose: int = 1,
        dataset_item_ids=None,
        experiment_config=None,
        n_samples=None,
        seed=None,
        agent_class=None,
    ) -> float:
        random.seed(seed)

        if prompt.model is None:
            prompt.model = self.model
        if prompt.model_kwargs is None:
            prompt.model_kwargs = self.model_kwargs

        if agent_class is None:
            self.agent_class = create_litellm_agent_class(prompt)
        else:
            self.agent_class = agent_class

        agent = self.agent_class(prompt)

        def llm_task(dataset_item: Dict[str, Any]) -> Dict[str, str]:
            CONTEXT7_SECOND_PASS.reset()
            print("[context7-second-pass] building base messages", flush=True)
            base_messages = prompt.get_messages(dataset_item)
            raw_model_output = agent.llm_invoke(messages=base_messages, seed=seed, allow_tool_use=True)
            second_pass_messages = CONTEXT7_SECOND_PASS.build_second_pass_messages(
                base_messages=base_messages,
                dataset_item=dataset_item,
            )
            summary_used = CONTEXT7_SECOND_PASS.get_last_summary()
            if second_pass_messages is None:
                print("[context7-second-pass] second pass missing, invoking fallback", flush=True)
                fallback_args = _extract_arguments(dataset_item)
                if fallback_args:
                    synthetic_summary = doc_lookup(**fallback_args)
                    second_pass_messages = CONTEXT7_SECOND_PASS.build_second_pass_messages(
                        base_messages=base_messages,
                        dataset_item=dataset_item,
                        summary_override=synthetic_summary,
                    )
                    summary_used = CONTEXT7_SECOND_PASS.get_last_summary() or synthetic_summary
            if second_pass_messages:
                print("[context7-second-pass] invoking second pass LLM", flush=True)
                final_response = agent.llm_invoke(messages=second_pass_messages, seed=seed, allow_tool_use=False)
                cleaned_model_output = final_response.strip()
            else:
                cleaned_model_output = raw_model_output.strip()
                print(
                    "[context7-second-pass] no follow-up used for",
                    dataset_item.get("id"),
                    flush=True,
                )
            print(
                "[context7-second-pass] final response",
                dataset_item.get("id"),
                cleaned_model_output[:200].replace("\n", " "),
                flush=True,
            )
            print(
                "[context7-second-pass] summary used",
                dataset_item.get("id"),
                (summary_used[:160].replace("\n", " ") if summary_used else "<none>"),
                flush=True,
            )
            return {mappers.EVALUATED_LLM_TASK_OUTPUT: cleaned_model_output.strip()}

        experiment_config_local = experiment_config or {}
        experiment_config_local["project_name"] = self.__class__.__name__
        experiment_config_local = {
            **experiment_config_local,
            **{
                "agent_class": self.agent_class.__name__,
                "agent_config": prompt.to_dict(),
                "metric": metric.__name__,
                "dataset": dataset.name,
                "configuration": {"prompt": (prompt.get_messages() if prompt else [])},
            },
        }

        if n_samples is not None:
            if dataset_item_ids is not None:
                raise Exception("Can't use n_samples and dataset_item_ids")
            all_ids = [dataset_item["id"] for dataset_item in dataset.get_items()]
            dataset_item_ids = random.sample(all_ids, n_samples)

        score = task_evaluator.evaluate(
            dataset=dataset,
            dataset_item_ids=dataset_item_ids,
            metric=metric,
            evaluated_task=llm_task,
            num_threads=n_threads,
            project_name=self.agent_class.project_name,
            experiment_config=experiment_config_local,
            optimization_id=None,
            verbose=verbose,
        )
        return score

try:
    sample_item = dataset.get_items(nb_samples=1)[0]
    print("Sample dataset item:", sample_item)
    sample_args = _extract_arguments(sample_item)
    if sample_args:
        sample_preview = response_to_text(
            call_tool_from_manifest(MCP_MANIFEST, TOOL_NAME, dict(sample_args))
        )
        print("Sample tool output preview:", sample_preview[:200].replace("\n", " "))
    else:
        print("No sample arguments available for preview.")
except Exception as exc:
    print(f"Failed to fetch sample tool output: {exc}")

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
    function_map={TOOL_NAME: doc_lookup},
)

if prompt.model is None:
    prompt.model = "openai/gpt-4o-mini"
if not prompt.model_kwargs:
    prompt.model_kwargs = {"temperature": 0.2}


def debug_second_pass_flow(prompt: ChatPrompt, dataset_item: Dict[str, Any]) -> None:
    print("\n--- DEBUG: running explicit second-pass flow ---", flush=True)
    agent_class = create_litellm_agent_class(prompt)
    agent = agent_class(prompt)

    CONTEXT7_SECOND_PASS.reset()
    base_messages = prompt.get_messages(dataset_item)
    print("[debug] base messages:", base_messages, flush=True)

    raw_output = agent.llm_invoke(messages=base_messages, seed=42, allow_tool_use=True)
    print("[debug] raw model output:", raw_output, flush=True)

    summary = CONTEXT7_SECOND_PASS.get_last_summary()
    print(
        "[debug] summary captured:",
        (summary[:200].replace("\n", " ") if summary else "<none>"),
        flush=True,
    )

    second_pass_messages = CONTEXT7_SECOND_PASS.build_second_pass_messages(
        base_messages=base_messages,
        dataset_item=dataset_item,
    )
    if second_pass_messages is None:
        print("[debug] second pass messages missing; attempting fallback", flush=True)
        synthetic_summary = doc_lookup(**_extract_arguments(dataset_item))
        summary = synthetic_summary
        second_pass_messages = CONTEXT7_SECOND_PASS.build_second_pass_messages(
            base_messages=base_messages,
            dataset_item=dataset_item,
            summary_override=synthetic_summary,
        )

    if second_pass_messages:
        print("[debug] second-pass message transcript:", second_pass_messages, flush=True)
        # Use the optimizer's low-level invoker to avoid OpenAI tool-call validation
        final_output = agent.llm_invoke(
            messages=second_pass_messages,
            seed=101,
            allow_tool_use=True,
        )
    else:
        print("[debug] still no second-pass messages; using raw output", flush=True)
        final_output = raw_output

    coerced = final_output
    print("[debug] coerced final output:", coerced, flush=True)
    print("--- END DEBUG ---\n", flush=True)


# Run a single debug pass before the optimizer to surface tracing info
debug_dataset_item = dataset.get_items(nb_samples=1)[0]
debug_second_pass_flow(prompt, debug_dataset_item)

meta_optimizer = MCPMetaPromptOptimizer(
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
