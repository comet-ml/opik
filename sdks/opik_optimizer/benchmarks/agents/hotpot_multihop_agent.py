"""
Multi-hop retrieval agent for HotpotQA.

Pipeline:
- create_query_1: Generate initial search query
- search_1: Retrieve Wikipedia passages (external function)
- summarize_1: Summarize findings + identify gaps
- create_query_2: Generate refined query targeting gaps
- search_2: Retrieve more passages
- summarize_2: Update summary with new information
- final_answer: Generate answer from accumulated evidence
"""

from __future__ import annotations

import json
import logging
import os
from collections.abc import Callable
from typing import Any

import litellm
import opik
from opik import opik_context
from opik.integrations.litellm import track_completion
from pydantic import BaseModel

from opik_optimizer import ChatPrompt, OptimizableAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia

logger = logging.getLogger(__name__)

tracked_completion = track_completion()(litellm.completion)


class SummaryObject(BaseModel):
    """Structured output for summarization steps."""

    summary: str
    gaps: list[str]


def get_initial_prompts() -> dict[str, ChatPrompt]:
    """Return the initial prompts for the HotpotQA multi-hop pipeline."""
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
    disable_flag = os.getenv("OPIK_DISABLE_WIKIPEDIA", "").strip().lower()
    if disable_flag in ("1", "true", "yes", "on"):
        return []

    # Cap query length for logging; avoid model overrun
    if len(query) > 256:
        query = query[:256] + "..."

    try:
        results = search_wikipedia(
            query,
            search_type="bm25",
            k=n,
            bm25_hf_repo="Comet/wikipedia-2017-bm25",
        )
        return results[:n] if len(results) >= n else results + [""] * (n - len(results))

    except Exception as e:
        logger.warning(f"BM25 search failed (will fallback to API): {e}")
        # Fallback to API search
        try:
            results = search_wikipedia(query, search_type="api", k=n)
            return (
                results[:n]
                if len(results) >= n
                else results + [""] * (n - len(results))
            )
        except Exception:
            logger.exception("Wikipedia API search also failed")
            return [""] * n


class HotpotMultiHopAgent(OptimizableAgent):
    """
    Multi-hop retrieval agent for HotpotQA using Wikipedia search.

    This agent implements a two-hop retrieval pipeline:
    1. Generate initial search query from question
    2. Search Wikipedia and summarize findings
    3. Identify gaps and generate refined query
    4. Search again and synthesize information
    5. Generate final answer

    Args:
        search_fn: Function to search Wikipedia (query, n) -> list[str]
        model: LiteLLM model name for LLM calls
        model_parameters: Additional parameters for LLM calls
        num_passages_per_hop: Number of passages to retrieve per search
    """

    def __init__(
        self,
        search_fn: Callable[[str, int], list[str]] | None = None,
        model: str = "openai/gpt-4.1-mini",
        model_parameters: dict[str, Any] | None = None,
        num_passages_per_hop: int = 5,
    ):
        super().__init__()
        self.search_fn = opik.track(name="wikipedia_search", type="tool")(
            search_fn or bm25_wikipedia_search
        )
        self.model = model
        self.model_parameters = model_parameters or {}
        self.num_passages = num_passages_per_hop

    def invoke(
        self,
        messages: list[dict[str, str]] | None = None,
        seed: int | None = None,
        allow_tool_use: bool = True,
    ) -> str:
        """Not implemented for multi-prompt agents."""
        raise NotImplementedError(
            "invoke() is not implemented for HotpotMultiHopAgent. "
            "Use invoke_agent() with prompts dict instead."
        )

    def create_agent_graph(self) -> dict[str, str]:
        """Return a Mermaid graph definition for visualization."""
        return {
            "format": "mermaid",
            "data": (
                "graph TD; "
                "Q1[create_query_1]-->S1[summarize_1]; "
                "S1-->Q2[create_query_2]; "
                "Q2-->S2[summarize_2]; "
                "S2-->FA[final_answer];"
            ),
        }

    @opik.track(name="agent invocation")
    def invoke_agent(
        self,
        prompts: dict[str, ChatPrompt],
        dataset_item: dict[str, Any],
        seed: int | None = None,
        allow_tool_use: bool = True,
    ) -> str:
        """
        Execute the multi-hop retrieval pipeline.

        Args:
            prompts: Dict of ChatPrompt objects for each pipeline step
            dataset_item: Dataset item containing at least 'question' field
            seed: Optional random seed (unused)
            allow_tool_use: Whether to allow tool use (unused, always uses search)

        Returns:
            Final answer string
        """
        opik_context.update_current_trace(
            metadata={"_opik_graph_definition": self.create_agent_graph()}
        )

        # Step 1: Generate first search query
        messages = prompts["create_query_1"].get_messages(dataset_item)
        search_query_1_response = tracked_completion(
            model=self.model,
            messages=messages,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["hotpot-multihop"],
                },
            },
            **self.model_parameters,
        )
        search_query_1 = search_query_1_response.choices[0].message.content

        # Step 2: Execute first Wikipedia search
        search_query_1_result = self.search_fn(search_query_1, self.num_passages)

        # Step 3: Summarize first search results
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
                    "tags": ["hotpot-multihop"],
                },
            },
            **self.model_parameters,
        )
        response_content = json.loads(response.choices[0].message.content)
        summary_1 = response_content["summary"]
        gaps_1 = response_content["gaps"]

        # Step 4: Generate second search query targeting gaps
        messages = prompts["create_query_2"].get_messages(
            {
                "question": dataset_item["question"],
                "summary_1": summary_1,
                "gaps_1": "\n\n".join(gaps_1),
            }
        )
        search_query_2_response = tracked_completion(
            model=self.model,
            messages=messages,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["hotpot-multihop"],
                },
            },
            **self.model_parameters,
        )
        search_query_2 = search_query_2_response.choices[0].message.content

        # Step 5: Execute second Wikipedia search
        search_query_2_result = self.search_fn(search_query_2, self.num_passages)

        # Step 6: Synthesize information from both searches
        messages = prompts["summarize_2"].get_messages(
            {
                "question": dataset_item["question"],
                "summary_1": summary_1,
                "passages_2": "\n\n".join(search_query_2_result),
            }
        )
        summary_2_response = tracked_completion(
            model=self.model,
            messages=messages,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["hotpot-multihop"],
                },
            },
            **self.model_parameters,
        )
        summary_2 = summary_2_response.choices[0].message.content

        # Step 7: Generate final answer
        messages = prompts["final_answer"].get_messages(
            {
                "question": dataset_item["question"],
                "summary_2": summary_2,
            }
        )
        final_answer_response = tracked_completion(
            model=self.model,
            messages=messages,
            metadata={
                "opik": {
                    "current_span_data": opik_context.get_current_span_data(),
                    "tags": ["hotpot-multihop"],
                },
            },
            **self.model_parameters,
        )
        return final_answer_response.choices[0].message.content
