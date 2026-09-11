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

import logging
import os
from collections.abc import Callable
from typing import Any, cast

import opik
from opik import opik_context
from pydantic import BaseModel

from opik_optimizer import ChatPrompt, OptimizableAgent, constants
from opik_optimizer.core.llm_calls import call_model
from opik_optimizer.utils.tools.wikipedia import search_wikipedia

logger = logging.getLogger(__name__)


class SummaryObject(BaseModel):
    """Structured output for summarization steps."""

    summary: str
    gaps: list[str]


class SummaryUpdate(BaseModel):
    """Structured output for summary update step."""

    summary: str


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
        return results[:n] if len(results) >= n else results + [""] * (n - len(results))

    except Exception as e:
        logger.warning(f"BM25 search failed (will fallback to API): {e}")
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
        raise NotImplementedError(
            "invoke() is not implemented for HotpotMultiHopAgent. "
            "Use invoke_agent() with prompts dict instead."
        )

    def create_agent_graph(self) -> dict[str, str]:
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
        opik_context.update_current_trace(
            metadata={"_opik_graph_definition": self.create_agent_graph()}
        )
        model_name = self.model or constants.DEFAULT_MODEL or "openai/gpt-4.1-mini"
        call_metadata = {
            "opik": {
                "current_span_data": opik_context.get_current_span_data(),
                "tags": ["hotpot-multihop"],
                "suppress_call_log": True,
            }
        }

        messages = prompts["create_query_1"].get_messages(dataset_item)
        search_query_1 = cast(
            str,
            call_model(
                messages=messages,
                model=model_name,
                model_parameters=self.model_parameters,
                metadata=call_metadata,
                return_all=False,
            ),
        )
        search_query_1 = str(search_query_1 or "").strip()

        search_query_1_result = self.search_fn(search_query_1, self.num_passages)

        messages = prompts["summarize_1"].get_messages(
            {
                **dataset_item,
                "passages_1": "\n\n".join(search_query_1_result),
            }
        )
        summary_1 = cast(
            SummaryObject,
            call_model(
                messages=messages,
                model=model_name,
                model_parameters=self.model_parameters,
                response_model=SummaryObject,
                metadata=call_metadata,
                return_all=False,
            ),
        )

        messages = prompts["create_query_2"].get_messages(
            {
                **dataset_item,
                "summary_1": summary_1.summary,
                "gaps_1": "\n".join(summary_1.gaps),
            }
        )
        search_query_2 = cast(
            str,
            call_model(
                messages=messages,
                model=model_name,
                model_parameters=self.model_parameters,
                metadata=call_metadata,
                return_all=False,
            ),
        )
        search_query_2 = str(search_query_2 or "").strip()

        search_query_2_result = self.search_fn(search_query_2, self.num_passages)

        messages = prompts["summarize_2"].get_messages(
            {
                **dataset_item,
                "summary_1": summary_1.summary,
                "passages_2": "\n\n".join(search_query_2_result),
            }
        )
        summary_2 = cast(
            SummaryUpdate,
            call_model(
                messages=messages,
                model=model_name,
                model_parameters=self.model_parameters,
                response_model=SummaryUpdate,
                metadata=call_metadata,
                return_all=False,
            ),
        )

        messages = prompts["final_answer"].get_messages(
            {
                **dataset_item,
                "summary_2": summary_2.summary,
            }
        )
        answer = cast(
            str,
            call_model(
                messages=messages,
                model=model_name,
                model_parameters=self.model_parameters,
                metadata=call_metadata,
                return_all=False,
            ),
        )
        return str(answer or "").strip()


def build_hotpot_agent(
    model_name: str,
    model_parameters: dict[str, Any] | None,
) -> HotpotMultiHopAgent:
    return HotpotMultiHopAgent(
        model=model_name,
        model_parameters=model_parameters,
    )
