"""
HotpotQA Multi-Hop Benchmark (GEPA-style).

This example demonstrates a multi-hop retrieval pipeline with multiple
optimizable prompts:
1) Query → 2) Summarize → 3) Refine query → 4) Summarize → 5) Answer
"""

from __future__ import annotations

from collections.abc import Callable
import json
import logging
import os
import random
import re
from typing import Any

from pydantic import BaseModel

import litellm
import opik
from opik import opik_context
from opik.integrations.litellm import track_completion

from benchmarks.metrics.hotpot import hotpot_f1
from opik_optimizer import ChatPrompt, HierarchicalReflectiveOptimizer, OptimizableAgent
from opik_optimizer.datasets import hotpot
from opik_optimizer.utils.logging import setup_logging
from opik_optimizer.utils.tools.wikipedia import search_wikipedia

PROJECT_HEADER = "Hotpot QA Multihop Optimization"
SEED = 42
MODEL_NAME = "openai/gpt-4.1-mini"
MODEL_PARAMS = {"temperature": 1.0}
NUM_PASSAGES = 5

# Disable tqdm progress bars (used by bm25s)
os.environ["TQDM_DISABLE"] = "1"

# Configure logging and environment
setup_logging()
logger = logging.getLogger(__name__)
tracked_completion = track_completion()(litellm.completion)
random.seed(SEED)


# ---------------------------------------------------------------------------
# Search helpers
# ---------------------------------------------------------------------------


def wikipedia_search(query: str, n: int = 5) -> list[str]:
    """
    Search Wikipedia and return top n passage texts.

    Args:
        query: Search query
        n: Number of passages to return

    Returns:
        List of passage texts
    """
    disable_flag = os.getenv("OPIK_DISABLE_WIKIPEDIA", "").strip().lower()
    if disable_flag in ("1", "true", "yes", "on"):
        return []

    if len(query) > 256:
        query = query[:256] + "..."

    try:
        results = search_wikipedia(
            query,
            search_type="bm25",
            k=n,
            bm25_hf_repo="Comet/wikipedia-2017-bm25",
        )
    except Exception:
        logger.exception("BM25 search failed, falling back to Wikipedia API")
        results = search_wikipedia(query, search_type="api", k=n)

    return results[:n] if len(results) >= n else results + ["" for _ in range(n)]


def bm25_wikipedia_search(query: str, n: int = 5) -> list[str]:
    """
    BM25-based Wikipedia search for fair comparison.

    Uses the production Comet/wikipedia-2017-bm25 index.
    Falls back to API search if BM25 index is unavailable.
    """
    disable_flag = os.getenv("OPIK_DISABLE_WIKIPEDIA", "").strip().lower()
    if disable_flag in ("1", "true", "yes", "on"):
        return []

    try:
        results = search_wikipedia(
            query,
            search_type="bm25",
            k=n,
            bm25_hf_repo="Comet/wikipedia-2017-bm25",
        )
        return results
    except Exception as exc:
        logger.warning("BM25 search failed (fallback to API): %s", exc)
        return wikipedia_search(query, n)


def get_search_fn() -> Callable[[str, int], list[str]]:
    # Use BM25 by default for fair comparison with GEPA paper.
    return bm25_wikipedia_search


# ---------------------------------------------------------------------------
# Structured output models
# ---------------------------------------------------------------------------


class SummaryObject(BaseModel):
    summary: str
    gaps: list[str]


class SummaryUpdate(BaseModel):
    summary: str


def _parse_summary_response(content: str) -> SummaryObject:
    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", content, re.DOTALL)
        if not match:
            raise
        data = json.loads(match.group(0))
    return SummaryObject(**data)


def _parse_summary_update(content: str) -> SummaryUpdate:
    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", content, re.DOTALL)
        if not match:
            raise
        data = json.loads(match.group(0))
    return SummaryUpdate(**data)


# ---------------------------------------------------------------------------
# Agent implementation
# ---------------------------------------------------------------------------


class HotpotMultiHopAgent(OptimizableAgent):
    def __init__(
        self,
        search_fn: Callable[[str, int], list[str]],
        model: str = MODEL_NAME,
        model_parameters: dict | None = None,
        num_passages_per_hop: int = NUM_PASSAGES,
    ):
        self.search_fn = opik.track(name="wikipedia_search", type="tool")(search_fn)
        self.model = model
        self.model_parameters = model_parameters or {}
        self.num_passages = num_passages_per_hop
        self._tools_for_display = [
            {
                "type": "function",
                "function": {
                    "name": "search_wikipedia",
                    "description": (
                        "Search Wikipedia for information about a topic. "
                        "Returns relevant article abstracts."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": (
                                    "The search query - a topic, person, place, "
                                    "or concept to look up."
                                ),
                            },
                            "n": {
                                "type": "integer",
                                "description": "Number of passages to return.",
                            },
                        },
                        "required": ["query"],
                    },
                },
            }
        ]

    def invoke(
        self,
        messages: list[dict[str, str]] | None = None,
        seed: int | None = None,
        allow_tool_use: bool = True,
    ) -> str:
        raise NotImplementedError(
            "invoke_agent is not implemented for HotpotMultiHopAgent"
        )

    def create_agent_graph(self):
        return {
            "format": "mermaid",
            "data": "graph TD; Q1[create_query_1]-->S1[summarize_1]; S1-->Q2[create_query_2]; Q2-->S2[summarize_2]; S2-->FA[final_answer];",
        }

    def get_tools_for_display(self) -> list[dict[str, Any]] | None:
        return self._tools_for_display

    @opik.track(name="agent invocation")
    def invoke_agent(
        self,
        prompts: dict[str, ChatPrompt],
        dataset_item: dict[str, Any],
        seed: int | None = None,
        allow_tool_use: bool = True,
    ) -> str:
        del seed, allow_tool_use
        opik_context.update_current_trace(
            metadata={"_opik_graph_definition": self.create_agent_graph()}
        )

        messages = prompts["create_query_1"].get_messages(dataset_item)
        search_query_1 = tracked_completion(
            model=self.model,
            messages=messages,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["streaming-test"],
                },
            },
            **self.model_parameters,
        )
        search_query_1 = str(search_query_1.choices[0].message.content or "").strip()

        search_query_1_result = self.search_fn(search_query_1, self.num_passages)

        messages = prompts["summarize_1"].get_messages(
            {
                "question": dataset_item["question"],
                "passages_1": "\n\n".join(search_query_1_result),
            }
        )
        response = tracked_completion(
            model=self.model,
            messages=messages,
            response_format=SummaryObject,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["streaming-test"],
                },
            },
            **self.model_parameters,
        )
        response_msg = response.choices[0].message
        parsed = getattr(response_msg, "parsed", None)
        if parsed is None:
            parsed = _parse_summary_response(response_msg.content)
        search_query_1_summary = parsed.summary
        gaps_1 = parsed.gaps

        messages = prompts["create_query_2"].get_messages(
            {
                "question": dataset_item["question"],
                "summary_1": search_query_1_summary,
                "gaps_1": "\n\n".join(gaps_1),
            }
        )
        search_query_2 = tracked_completion(
            model=self.model,
            messages=messages,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["streaming-test"],
                },
            },
            **self.model_parameters,
        )
        search_query_prompt = str(
            search_query_2.choices[0].message.content or ""
        ).strip()
        search_query_2_result = self.search_fn(search_query_prompt, self.num_passages)

        messages = prompts["summarize_2"].get_messages(
            {
                "question": dataset_item["question"],
                "summary_1": search_query_1_summary,
                "passages_2": "\n\n".join(search_query_2_result),
            }
        )
        search_query_2_summary = tracked_completion(
            model=self.model,
            messages=messages,
            response_format=SummaryUpdate,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["streaming-test"],
                },
            },
            **self.model_parameters,
        )
        response_msg = search_query_2_summary.choices[0].message
        parsed = getattr(response_msg, "parsed", None)
        if parsed is None:
            parsed = _parse_summary_update(response_msg.content)
        search_query_2_summary_text = parsed.summary

        messages = prompts["final_answer"].get_messages(
            {
                "question": dataset_item["question"],
                "summary_2": search_query_2_summary_text,
            }
        )
        final_answer = tracked_completion(
            model=self.model,
            messages=messages,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["streaming-test"],
                },
            },
            **self.model_parameters,
        )
        return str(final_answer.choices[0].message.content or "").strip()


# ---------------------------------------------------------------------------
# Prompt definitions + metric
# ---------------------------------------------------------------------------


def build_initial_prompts() -> dict[str, ChatPrompt]:
    return {
        "create_query_1": ChatPrompt(
            system=(
                "Generate a Wikipedia search query to answer the question. "
                "Identify key entities, relations, and disambiguating details."
            ),
            user="{question}",
        ),
        "summarize_1": ChatPrompt(
            system=(
                "Summarize the retrieved passages focusing on facts relevant to the question. "
                "Identify what information is still missing or unclear."
            ),
            user=(
                "Question: {question}\n\n"
                "Retrieved passages from first search:\n{passages_1}\n\n"
                "Provide:\n"
                "1. Summary: Key facts from passages\n"
                "2. Gaps: What's still missing to answer the question"
            ),
        ),
        "create_query_2": ChatPrompt(
            system=(
                "Generate a refined Wikipedia search query targeting the identified gaps. "
                "Use different terms/angles than the first query."
            ),
            user=(
                "Question: {question}\n\n"
                "First summary: {summary_1}\n\n"
                "Identified gaps: {gaps_1}\n\n"
                "Generate a second search query to fill these gaps."
            ),
        ),
        "summarize_2": ChatPrompt(
            system=(
                "Update the summary with new information from the second search. "
                "Synthesize information from both searches."
            ),
            user=(
                "Question: {question}\n\n"
                "First summary: {summary_1}\n\n"
                "New passages from second search:\n{passages_2}\n\n"
                "Provide an updated comprehensive summary."
            ),
        ),
        "final_answer": ChatPrompt(
            system=(
                "Answer the question based on the accumulated evidence. "
                "Be concise and factual. Keep answers as short as possible, ideally a single word or phrase."
            ),
            user=(
                "Question: {question}\n\n"
                "Evidence from searches:\n{summary_2}\n\n"
                "Provide a direct answer to the question."
            ),
        ),
    }


def hotpot_multihop_metric(dataset_item: dict, llm_output: str) -> float:
    return hotpot_f1(dataset_item, llm_output)


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------


def print_header() -> None:
    print("=" * 80)
    print(PROJECT_HEADER)
    print("=" * 80)
    print()
    print(
        "This benchmark implements the compound AI system approach used in GEPA paper:"
    )
    print("- Multi-hop retrieval pipeline")
    print("- Multiple optimizable prompts")
    print("- Wikipedia search integration")
    print()


def load_datasets() -> tuple[Any, Any, Any]:
    print("Loading datasets...")
    train = hotpot(count=150, split="train", dataset_name="hotpot_train")
    val = hotpot(count=300, split="validation", dataset_name="hotpot_validation")
    test = hotpot(count=300, split="test", dataset_name="hotpot_test")
    print(f"  - Train: {len(train.get_items())} samples")
    print(f"  - Validation: {len(val.get_items())} samples")
    print(f"  - Test: {len(test.get_items())} samples")
    print()
    return train, val, test


def run_optimization(
    *,
    agent: HotpotMultiHopAgent,
    initial_prompts: dict[str, ChatPrompt],
    train_dataset: Any,
    validation_dataset: Any,
) -> Any:
    optimizer = HierarchicalReflectiveOptimizer(
        model=MODEL_NAME,
        model_parameters=MODEL_PARAMS,
        seed=SEED,
    )
    print(f"Running multi-prompt optimization ({optimizer.__class__.__name__})...")
    return optimizer.optimize_prompt(
        prompt=initial_prompts,
        dataset=train_dataset,
        validation_dataset=validation_dataset,
        metric=hotpot_multihop_metric,
        agent=agent,
        max_trials=50,
    )


def main() -> None:
    print_header()
    train_dataset, validation_dataset, test_dataset = load_datasets()

    search_fn = get_search_fn()
    print(f"Search function: {search_fn.__name__}")
    print()
    try:
        search_fn("warmup", 1)
    except Exception:
        pass

    agent = HotpotMultiHopAgent(
        search_fn=search_fn,
        model=MODEL_NAME,
        model_parameters=MODEL_PARAMS,
        num_passages_per_hop=NUM_PASSAGES,
    )
    initial_prompts = build_initial_prompts()

    print("=" * 80)
    print("OPTIMIZATION")
    print("=" * 80)
    print("This will optimize ALL prompts in the multi-hop pipeline:")
    for name in initial_prompts:
        print(f"  - {name}")
    print()
    print("Training on hotpot_train (150 samples)...")
    print("Validation on hotpot_validation (300 samples)...")
    print()

    opt_result = run_optimization(
        agent=agent,
        initial_prompts=initial_prompts,
        train_dataset=train_dataset,
        validation_dataset=validation_dataset,
    )
    print(f"Optimization best score: {opt_result.score:.4f}")

    print("=" * 80)
    print("TESTING")
    print("=" * 80)
    print("Evaluating optimized prompts on test set...")
    print()

    _ = test_dataset


if __name__ == "__main__":
    main()
