"""
HotpotQA Multi-Hop Benchmark

Implements the compound AI system approach for apples-to-apples comparison with academia:
- Multi-hop retrieval with Wikipedia search
- Multiple optimizable prompts in pipeline
- Matches the complexity of the GEPA paper's HotpotQA setup

Usage:
    python scripts/benchmarks/hotpot_multihop_benchmark.py
"""

import os
import random
import logging
from pydantic import BaseModel
import json

import opik
from opik import opik_context
from opik_optimizer.datasets import hotpot
from benchmarks.metrics.hotpot import hotpot_f1

from opik_optimizer.utils.tools.wikipedia import search_wikipedia
from opik_optimizer.logging_config import setup_logging
from opik_optimizer import HierarchicalReflectiveOptimizer, OptimizableAgent, ChatPrompt
from typing import Any
from collections.abc import Callable

from opik.integrations.litellm import track_completion
import litellm

# Disable tqdm progress bars (used by bm25s)
os.environ["TQDM_DISABLE"] = "1"

# Configure logging
setup_logging()
logger = logging.getLogger(__name__)

tracked_completion = track_completion()(litellm.completion)

# Set seed for reproducibility
SEED = 42
random.seed(SEED)

print("=" * 80)
print("HOTPOTQA MULTI-HOP BENCHMARK (GEPA-STYLE)")
print("=" * 80)
print()
print("This benchmark implements the compound AI system approach used in GEPA paper:")
print("- Multi-hop retrieval pipeline")
print("- Multiple optimizable prompts")
print("- Wikipedia search integration")
print()

# ============================================================================
# CONFIGURATION
# ============================================================================

# Dataset splits (matching GEPA paper: 150 train, 300 val, 300 test)
print("Loading datasets...")
train_dataset = hotpot(count=150, split="train", dataset_name="hotpot_train")
validation_dataset = hotpot(
    count=300, split="validation", dataset_name="hotpot_validation"
)
test_dataset = hotpot(count=300, split="test", dataset_name="hotpot_test")

print(f"  - Train: {len(train_dataset.get_items())} samples")
print(f"  - Validation: {len(validation_dataset.get_items())} samples")
print(f"  - Test: {len(test_dataset.get_items())} samples")
print()

# ============================================================================
# SEARCH FUNCTION SETUP
# ============================================================================


# Wrapper for Wikipedia search compatible with agent
def wikipedia_search(query: str, n: int = 5) -> list[str]:
    """
    Search Wikipedia and return top n passage texts.

    Args:
        query: Search query
        n: Number of passages to return

    Returns:
        List of passage texts
    """
    # If Wikipedia is disabled, return empty list without padding
    # Check environment variable at call time (not at module load time)
    disable_flag = os.getenv("OPIK_DISABLE_WIKIPEDIA", "").strip().lower()
    if disable_flag in ("1", "true", "yes", "on"):
        return []

    # Cap query length to avoid model overrun
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

    return results[:n] if len(results) >= n else results + [""] * (n - len(results))


# BM25 search (for like-for-like comparison)
def bm25_wikipedia_search(query: str, n: int = 5) -> list[str]:
    """
    BM25-based Wikipedia search for fair comparison.

    Uses the production Comet/wikipedia-2017-bm25 index:
    - Same Wikipedia 2017 corpus as benchmarks
    - Optimized Parquet format (1.61 GB, downloads on first run)
    - BM25 parameters: k1=0.9, b=0.4
    - ~100ms query time after initial load

    Falls back to API search if BM25 index is unavailable.
    """
    # If Wikipedia is disabled, return empty list
    # Check environment variable at call time (not at module load time)
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

    except Exception as e:
        logger.warning(f"BM25 search failed (will fallback to API): {e}")
        return wikipedia_search(query, n)


# Choose search function
# FIXME: Before merging, switch back to bm25_wikipedia_search for fair comparison with GEPA paper
# Currently using API mode for testing (no BM25 index download required)
# Use bm25_wikipedia_search for fair comparison with GEPA paper
SEARCH_FN = bm25_wikipedia_search
print(f"Search function: {SEARCH_FN.__name__}")
print()

# Optional one-time BM25 warmup to avoid lazy load during eval
try:
    SEARCH_FN("warmup", 1)
except Exception:
    pass

# ============================================================================
# AGENT SETUP
# ============================================================================

initial_prompts = {
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
# - create_query_1: Generate initial search query
# - search_1: Retrieve Wikipedia passages (external function)
# - summarize_1: Summarize findings + identify gaps
# - create_query_2: Generate refined query targeting gaps
# - search_2: Retrieve more passages
# - summarize_2: Update summary with new information
# - final_answer: Generate answer from accumulated evidence


class SummaryObject(BaseModel):
    summary: str
    gaps: list[str]


class HotpotMultiHopAgent(OptimizableAgent):
    def __init__(
        self,
        search_fn: Callable[[str, int], list[str]],
        model: str = "openai/gpt-4.1-mini",
        model_parameters: dict | None = None,
        num_passages_per_hop: int = 5,
    ):
        self.search_fn = opik.track(name="wikipedia_search", type="tool")(search_fn)
        self.model = model
        self.model_parameters = model_parameters or {}
        self.num_passages = num_passages_per_hop

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

    @opik.track(name="agent invocation")
    def invoke_agent(
        self,
        prompts: dict[str, ChatPrompt],
        dataset_item: dict[str, Any],
        seed: int | None = None,
        allow_tool_use: bool = True,
    ):
        opik_context.update_current_trace(
            metadata={"_opik_graph_definition": self.create_agent_graph()}
        )

        # Run first search query:
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
        search_query_1 = search_query_1.choices[0].message.content

        # Do the first external search
        search_query_1_result = self.search_fn(search_query_1, self.num_passages)

        # Do the first summarization
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
        response_content = json.loads(response.choices[0].message.content)
        search_query_1_summary = response_content["summary"]
        gaps_1 = response_content["gaps"]

        # Do the second search query
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
        search_query_prompt = search_query_2.choices[0].message.content
        search_query_2_result = self.search_fn(search_query_prompt, self.num_passages)

        # Do the second summarization
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
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["streaming-test"],
                },
            },
            **self.model_parameters,
        )
        search_query_2_summary = search_query_2_summary.choices[0].message.content

        # Do the final answer
        messages = prompts["final_answer"].get_messages(
            {
                "question": dataset_item["question"],
                "summary_2": search_query_2_summary,
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
        final_answer = final_answer.choices[0].message.content
        return final_answer


MODEL_NAME = "openai/gpt-4.1-mini"
MODEL_PARAMS = {"temperature": 1.0}
NUM_PASSAGES = 5

agent = HotpotMultiHopAgent(
    search_fn=SEARCH_FN,
    model=MODEL_NAME,
    model_parameters=MODEL_PARAMS,
    num_passages_per_hop=NUM_PASSAGES,
)

# ============================================================================
# OPTIMIZATION
# ============================================================================

print("=" * 80)
print("OPTIMIZATION")
print("=" * 80)
print("This will optimize ALL prompts in the multi-hop pipeline:")
print("  - create_query_1")
print("  - summarize_1")
print("  - create_query_2")
print("  - summarize_2")
print("  - final_answer")
print()
print("Training on hotpot_train (150 samples)...")
print("Validation on hotpot_validation (300 samples)...")
print()


def hotpot_multihop_metric(dataset_item: dict, llm_output: str) -> float:
    return hotpot_f1(dataset_item, llm_output)


optimizer = HierarchicalReflectiveOptimizer(
    model=MODEL_NAME,
    model_parameters=MODEL_PARAMS,
    seed=SEED,
)

print(f"Running multi-prompt optimization ({optimizer.__class__.__name__})...")
opt_result = optimizer.optimize_prompt(
    prompt=initial_prompts,
    dataset=train_dataset,
    validation_dataset=validation_dataset,
    metric=hotpot_multihop_metric,
    agent=agent,
    max_trials=50,
)
print(f"Optimization best score: {opt_result.score:.4f}")

prompts = opt_result.prompt

# ============================================================================
# TESTING
# ============================================================================

print("=" * 80)
print("TESTING")
print("=" * 80)
print()
test_score = optimizer.evaluate_prompt(
    prompt=prompts,
    agent=agent,
    dataset=test_dataset,
    metric=hotpot_multihop_metric,
    n_threads=1,
)
print(f"Test score: {test_score:.4f}")
