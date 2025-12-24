from __future__ import annotations

from typing import Any, TYPE_CHECKING
from typing_extensions import TypedDict

import litellm

from opik import track
from opik_optimizer import OptimizableAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia

from langgraph.graph import StateGraph

if TYPE_CHECKING:
    from opik_optimizer.api_objects import chat_prompt


class AgentState(TypedDict):
    system_prompt: str
    question: str
    context: str
    answer: str


@track(type="tool")
def search_wikipedia_tool(query: str) -> list[str]:
    """Search Wikipedia for the given query."""
    return search_wikipedia(query, search_type="api")


class LangGraphAgent(OptimizableAgent):
    project_name = "langgraph-agent"

    def invoke_agent(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        if len(prompts) > 1:
            raise ValueError("LangGraphAgent only supports single-prompt optimization.")

        prompt = list(prompts.values())[0]
        messages = prompt.get_messages(dataset_item)
        if not messages:
            return "No messages generated from prompt."

        # Extract system prompt and user question
        system_prompt = next(
            (m["content"] for m in messages if m["role"] == "system"), ""
        )
        question = next(
            (m["content"] for m in reversed(messages) if m["role"] == "user"), ""
        )
        model_name = prompt.model or "gpt-4o-mini"

        # Build LangGraph workflow
        def fetch_context(state: AgentState) -> dict[str, str]:
            results = search_wikipedia_tool(state["question"])
            return {"context": "\n\n".join(results) if results else ""}

        def generate_answer(state: AgentState) -> dict[str, str]:
            context = state.get("context") or "No additional information."
            prompt_text = f"Context:\n{context}\n\nQuestion:\n{state['question']}"
            response = litellm.completion(
                model=model_name,
                messages=[
                    {"role": "system", "content": state["system_prompt"]},
                    {"role": "user", "content": prompt_text},
                ],
            )
            return {"answer": response.choices[0].message.content or "No response."}

        workflow = StateGraph(AgentState)
        workflow.add_node("fetch_context", fetch_context)
        workflow.add_node("generate_answer", generate_answer)
        workflow.set_entry_point("fetch_context")
        workflow.add_edge("fetch_context", "generate_answer")
        workflow.set_finish_point("generate_answer")
        graph = workflow.compile()

        state: AgentState = {
            "system_prompt": system_prompt,
            "question": question,
            "context": "",
            "answer": "",
        }

        result = graph.invoke(state)
        return result.get("answer", "No result from agent")
