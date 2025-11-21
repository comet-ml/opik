from __future__ import annotations

from typing import Any
from typing_extensions import TypedDict

import litellm

from opik import track
from opik.integrations.langchain import OpikTracer
from opik_optimizer import ChatPrompt, OptimizableAgent
from opik_optimizer.utils import search_wikipedia

from langgraph.graph import StateGraph


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
    return search_wikipedia(query, use_api=False)


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

    def init_agent(self, prompt: ChatPrompt) -> None:
        self.prompt = prompt
        system_prompt = (
            prompt.get_messages()[0]["content"]
            if prompt.get_messages()
            else "You are a helpful assistant."
        )
        model_name = prompt.model or "gpt-4o-mini"
        self.graph, self._tracer = create_graph(
            self.project_name, system_prompt, model_name
        )
        self._system_prompt = system_prompt
        self._model_name = model_name

    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
        question = messages[-1]["content"] if messages else ""
        state: AgentState = {
            "system_prompt": self._system_prompt,
            "question": question,
            "context": "",
            "answer": "",
        }
        result = self.graph.invoke(state)
        return result.get("answer", "No result from agent")
