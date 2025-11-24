"""
Multi-hop retrieval agent for HotpotQA.

Implements the compound AI system approach used in GEPA paper:
1. Generate initial query
2. Search Wikipedia
3. Summarize findings and identify gaps
4. Generate refined query
5. Search again
6. Final answer synthesis

This matches the Arize/GEPA benchmark setup for apples-to-apples comparison.
"""

from typing import Any
from collections.abc import Callable
import logging

from opik_optimizer import ChatPrompt
from opik_optimizer._llm_calls import call_model
from opik_optimizer.utils.llm_logger import LLMLogger

logger = logging.getLogger(__name__)
agent_logger = LLMLogger("hotpot_multihop_agent", agent_name="Hotpot Multi-Hop")


class HotpotMultiHopAgent:
    """
    Multi-hop retrieval agent for HotpotQA following GEPA/Arize approach.

    Pipeline:
    - create_query_1: Generate initial search query
    - search_1: Retrieve Wikipedia passages (external function)
    - summarize_1: Summarize findings + identify gaps
    - create_query_2: Generate refined query targeting gaps
    - search_2: Retrieve more passages
    - summarize_2: Update summary with new information
    - final_answer: Generate answer from accumulated evidence
    """

    def __init__(
        self,
        search_fn: Callable[[str, int], list[str]],
        model: str = "openai/gpt-4.1-mini",
        model_parameters: dict | None = None,
        num_passages_per_hop: int = 5,
        prompts: dict[str, ChatPrompt] | None = None,
    ):
        """
        Initialize the multi-hop agent.

        Args:
            search_fn: A callable that takes a query string and count, returning a list of passage texts.
            model: LLM model to use
            model_parameters: Model parameters (temperature, etc.)
            num_passages_per_hop: Number of passages to retrieve per search
        """
        self.search_fn = search_fn
        self.model = model
        self.model_parameters = model_parameters or {}
        self.num_passages = num_passages_per_hop

        # Define prompts for each step (to be optimized)
        self.prompts = prompts or self._create_initial_prompts()

    def _create_initial_prompts(self) -> dict[str, ChatPrompt]:
        """Create initial (unoptimized) prompts for each pipeline step."""
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
                    "Retrieved passages:\n{passages}\n\n"
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
                    "New passages from second search:\n{passages}\n\n"
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

    def execute(self, question: str, verbose: bool = False) -> dict[str, Any]:
        """
        Execute the multi-hop retrieval pipeline.

        Args:
            question: The question to answer
            verbose: Whether to log intermediate steps

        Returns:
            Dict with 'answer' and intermediate steps for debugging
        """
        return self._execute_with_prompts(self.prompts, question, verbose)

    def _execute_with_prompts(
        self, prompts: dict[str, ChatPrompt], question: str, verbose: bool = False
    ) -> dict[str, Any]:
        context = {"question": question}

        # === HOP 1: Initial search ===
        if verbose:
            logger.info(f"Question: {question}")

        # Generate first query
        query_1_response = self._invoke_prompt(
            prompts, "create_query_1", {"question": question}
        )
        context["query_1"] = self._extract_text(query_1_response)

        if verbose:
            logger.info(f"Query 1: {context['query_1']}")

        # Search
        passages_1 = self._log_search(context["query_1"], self.num_passages)
        context["passages_1"] = "\n\n".join(passages_1)

        if verbose:
            logger.info(f"Retrieved {len(passages_1)} passages from search 1")

        # Summarize and identify gaps
        summary_1_response = self._invoke_prompt(
            prompts,
            "summarize_1",
            {"question": question, "passages": context["passages_1"]},
        )
        summary_1_text = self._extract_text(summary_1_response)

        # Parse summary and gaps (simple split on "Gaps:" or similar)
        context["summary_1"], context["gaps_1"] = self._parse_summary_and_gaps(
            summary_1_text
        )

        if verbose:
            logger.info(f"Summary 1: {context['summary_1'][:200]}...")
            logger.info(f"Gaps: {context['gaps_1']}")

        # === HOP 2: Refined search ===

        # Generate refined query
        query_2_response = self._invoke_prompt(
            prompts,
            "create_query_2",
            {
                "question": question,
                "summary_1": context["summary_1"],
                "gaps_1": context["gaps_1"],
            },
        )
        context["query_2"] = self._extract_text(query_2_response)

        if verbose:
            logger.info(f"Query 2: {context['query_2']}")

        # Second search
        passages_2 = self._log_search(context["query_2"], self.num_passages)
        context["passages_2"] = "\n\n".join(passages_2)

        if verbose:
            logger.info(f"Retrieved {len(passages_2)} passages from search 2")

        # Update summary
        summary_2_response = self._invoke_prompt(
            prompts,
            "summarize_2",
            {
                "question": question,
                "summary_1": context["summary_1"],
                "passages": context["passages_2"],
            },
        )
        context["summary_2"] = self._extract_text(summary_2_response)

        if verbose:
            logger.info(f"Summary 2: {context['summary_2'][:200]}...")

        # === FINAL ANSWER ===

        answer_response = self._invoke_prompt(
            prompts,
            "final_answer",
            {"question": question, "summary_2": context["summary_2"]},
        )
        context["answer"] = self._extract_text(answer_response)

        if verbose:
            logger.info(f"Final answer: {context['answer']}")
        # Note: agent_response is already logged by _invoke_prompt's log_invoke context manager

        return context

    def _invoke_prompt(
        self, prompts: dict[str, ChatPrompt], prompt_name: str, inputs: dict[str, str]
    ) -> Any:
        """Invoke a prompt with given inputs."""
        prompt = prompts[prompt_name]
        # Get formatted messages from prompt
        messages = prompt.get_messages(dataset_item=inputs)
        input_preview = inputs.get("question") or str(inputs)
        with agent_logger.log_invoke(f"{prompt_name}: {input_preview}") as ctx:
            response = call_model(
                messages=messages,
                model=self.model,
                model_parameters=self.model_parameters,
            )
            ctx["response"] = self._extract_text(response)
            return response

    def _extract_text(self, response: Any) -> str:
        """Extract text from LLM response."""
        if isinstance(response, str):
            return response
        elif hasattr(response, "content"):
            return response.content
        elif hasattr(response, "text"):
            return response.text
        else:
            return str(response)

    def _parse_summary_and_gaps(self, text: str) -> tuple[str, str]:
        """
        Parse summary text into summary and gaps sections.

        Looks for markers like "Gaps:", "Missing:", etc.
        If not found, treats entire text as summary with empty gaps.
        """
        text_lower = text.lower()

        # Try to find gap indicators
        gap_markers = ["gaps:", "missing:", "still needed:", "unclear:"]

        for marker in gap_markers:
            if marker in text_lower:
                idx = text_lower.index(marker)
                summary = text[:idx].strip()
                gaps = text[idx:].strip()
                return summary, gaps

        # No gaps section found
        return text.strip(), "No specific gaps identified."

    def get_optimizable_prompts(self) -> dict[str, ChatPrompt]:
        """Return prompts that should be optimized."""
        return self.prompts

    def update_prompt(self, prompt_name: str, new_prompt: ChatPrompt) -> None:
        """Update a specific prompt (used during optimization)."""
        if prompt_name in self.prompts:
            self.prompts[prompt_name] = new_prompt
        else:
            raise ValueError(f"Unknown prompt: {prompt_name}")

    def _log_search(self, query: str, n: int) -> list[str]:
        """Call the configured search_fn with tool-style logging."""
        tool_name = getattr(
            self.search_fn, "__name__", self.search_fn.__class__.__name__
        )
        with agent_logger.log_tool(tool_name, query):
            return self.search_fn(query, n)
