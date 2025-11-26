"""
Multi-hop retrieval agent for HotpotQA using a sequenced OptimizableAgent.

Pipeline:
- create_query_1: Generate initial search query
- search_1: Retrieve Wikipedia passages (external function)
- summarize_1: Summarize findings + identify gaps
- create_query_2: Generate refined query targeting gaps
- search_2: Retrieve more passages
- summarize_2: Update summary with new information
- final_answer: Generate answer from accumulated evidence
"""

from typing import Any
from collections.abc import Callable

from opik_optimizer import ChatPrompt
from benchmarks.agents.sequenced_agent import SequencedOptimizableAgent
from opik_optimizer.utils.llm_logger import LLMLogger


class HotpotMultiHopAgent(SequencedOptimizableAgent):
    def __init__(
        self,
        search_fn: Callable[[str, int], list[str]],
        model: str = "openai/gpt-4.1-mini",
        model_parameters: dict | None = None,
        num_passages_per_hop: int = 5,
        prompts: dict[str, ChatPrompt] | None = None,
        plan: list[str] | None = None,
        project_name: str | None = None,
    ):
        self.search_fn = search_fn
        self.model = model
        self.model_parameters = model_parameters or {}
        self.num_passages = num_passages_per_hop
        self.prompts = prompts or self._create_initial_prompts()
        # plan controls prompt order; default to dict order
        self.plan = plan or list(self.prompts.keys())

        step_handlers = {
            "create_query_1": self._handle_search_1,
            "create_query_2": self._handle_search_2,
            "summarize_1": self._handle_summarize_1,
            "summarize_2": self._handle_summarize_2,
        }

        super().__init__(
            prompts=self.prompts,
            plan=self.plan,
            project_name=project_name or "Hotpot Multi-Hop",
            step_handlers=step_handlers,
            logger=LLMLogger("hotpot_multihop_agent", agent_name="Hotpot Multi-Hop"),
        )

    def get_optimizable_prompts(self) -> dict[str, ChatPrompt]:
        """Expose prompts for optimization/display."""
        return self.prompts

    def _create_initial_prompts(self) -> dict[str, ChatPrompt]:
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
                    "Be concise and factual. If information is insufficient, say so."
                ),
                user=(
                    "Question: {question}\n\n"
                    "Evidence from searches:\n{summary_2}\n\n"
                    "Provide a direct answer to the question."
                ),
            ),
        }

    def _handle_search_1(self, item: dict[str, Any], query: str) -> dict[str, Any]:
        passages_1 = self.search_fn(query, self.num_passages)
        item = dict(item)
        item["passages_1"] = "\n\n".join(passages_1)
        return item

    def _handle_search_2(self, item: dict[str, Any], query: str) -> dict[str, Any]:
        passages_2 = self.search_fn(query, self.num_passages)
        item = dict(item)
        item["passages_2"] = "\n\n".join(passages_2)
        return item
