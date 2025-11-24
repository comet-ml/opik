from __future__ import annotations

from typing import Any
from typing_extensions import TypedDict

import litellm

from opik import track
from opik.integrations.langchain import OpikTracer
from opik_optimizer import ChatPrompt, OptimizableAgent
from opik_optimizer.utils import search_wikipedia
from opik_optimizer.utils.llm_logger import LLMLogger

from langgraph.graph import StateGraph

# Setup logger
logger = LLMLogger("langgraph", agent_name="LangGraph")

logger.info("[bold green]═══ LangGraph loaded ═══[/bold green]")


PROMPT_TEMPLATE = """You are a fact-finding assistant. Use the provided context when answering.

Context:
{context}

Question:
{question}

Respond with a concise, factual answer."""


class AgentState(TypedDict):
    system_prompt: str
    question: str
    context: str
    answer: str


def search_wikipedia_tool(query: str) -> list[str]:
    """Wrapper for the shared Wikipedia search helper."""
    with logger.log_tool("search_wikipedia", query):
        return search_wikipedia(query, use_api=True)


search_wikipedia_tool = track(type="tool")(search_wikipedia_tool)


def create_graph(
    project_name: str, system_prompt: str, model_name: str
) -> tuple[Any, OpikTracer]:
    """
    Build a simple LangGraph workflow that (1) fetches context via the shared
    Wikipedia tool and (2) calls an LLM to produce the final answer.
    """
    workflow = StateGraph(AgentState)

    def fetch_context(state: AgentState) -> dict[str, str]:
        query = state["question"]
        results = search_wikipedia_tool(query)
        context = "\n\n".join(results) if results else ""
        return {"context": context}

    def generate_answer(state: AgentState) -> dict[str, str]:
        context = state.get("context", "")
        prompt_text = PROMPT_TEMPLATE.format(
            context=context or "No additional information.",
            question=state["question"],
        )
        messages = [
            {"role": "system", "content": state.get("system_prompt", system_prompt)},
            {"role": "user", "content": prompt_text},
        ]
        response = litellm.completion(model=model_name, messages=messages)
        content = response.choices[0].message.content
        return {"answer": content or "No response from model."}

    workflow.add_node("fetch_context", fetch_context)
    workflow.add_node("generate_answer", generate_answer)
    workflow.set_entry_point("fetch_context")
    workflow.add_edge("fetch_context", "generate_answer")
    workflow.set_finish_point("generate_answer")

    graph = workflow.compile()
    tracer = OpikTracer(project_name=project_name, graph=graph.get_graph(xray=True))
    return graph, tracer


class LangGraphAgent(OptimizableAgent):
    project_name = "langgraph-agent"

    def _resolve_system_text(self, prompt: ChatPrompt) -> str:
        """Extract system message from prompt."""
        messages = prompt.get_messages()
        if messages and messages[0].get("role") == "system":
            return messages[0].get("content", "")
        return "You are a helpful assistant."

    def _extract_latest_user_message(self, messages: list[dict[str, str]]) -> str:
        """Extract the latest user message from the messages list."""
        for message in reversed(messages):
            if message.get("role") == "user":
                return message.get("content", "")
        return ""

    def init_agent(self, prompt: ChatPrompt) -> None:
        self.prompt = prompt
        system_prompt = self._resolve_system_text(prompt)
        model_name = prompt.model or "gpt-4o-mini"

        logger.agent_init(model=model_name, tools=["search_wikipedia"])

        self.graph, self._tracer = create_graph(
            self.project_name, system_prompt, model_name
        )
        self._system_prompt = system_prompt
        self._model_name = model_name

    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
        question = self._extract_latest_user_message(messages)

        state: AgentState = {
            "system_prompt": self._system_prompt,
            "question": question,
            "context": "",
            "answer": "",
        }

        with logger.log_invoke(question) as ctx:
            result = self.graph.invoke(state)
            answer = result.get("answer", "No result from agent")
            ctx["response"] = answer
            return answer
