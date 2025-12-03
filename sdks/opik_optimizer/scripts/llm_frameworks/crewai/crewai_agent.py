from __future__ import annotations

from typing import Any, TYPE_CHECKING

from opik_optimizer import OptimizableAgent
from opik_optimizer.utils.tools.wikipedia import search_wikipedia
from opik import track
from crewai import Agent, Crew, Task
from crewai.tools import tool as crewai_tool
from langchain_openai import ChatOpenAI

if TYPE_CHECKING:
    from opik_optimizer.api_objects import chat_prompt


@crewai_tool("Search Wikipedia")
@track(type="tool")
def search_wikipedia_tool(query: str) -> list[str]:
    """Search Wikipedia for information about a topic."""
    return search_wikipedia(query, search_type="api")


class CrewAIAgent(OptimizableAgent):
    project_name = "crewai-agent"

    def invoke_agent(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        dataset_item: dict[str, Any],
        allow_tool_use: bool = False,
        seed: int | None = None,
    ) -> str:
        if len(prompts) > 1:
            raise ValueError("CrewAIAgent only supports single-prompt optimization.")

        prompt = list(prompts.values())[0]
        messages = prompt.get_messages(dataset_item)
        if not messages:
            return "No messages generated from prompt."

        # Extract system prompt and user question
        system_text = next(
            (m["content"] for m in messages if m["role"] == "system"), ""
        )
        question = next(
            (m["content"] for m in reversed(messages) if m["role"] == "user"), ""
        )

        # Create CrewAI agent
        model_name = prompt.model or "gpt-4o-mini"
        llm = ChatOpenAI(model=model_name, temperature=0)
        agent = Agent(
            role="Research Assistant",
            goal=system_text,
            backstory="An AI assistant specialized in finding accurate information.",
            tools=[search_wikipedia_tool],
            llm=llm,
            verbose=False,
        )

        task = Task(
            description=question,
            expected_output="A concise, factual answer based on the information found.",
            agent=agent,
        )
        crew = Crew(agents=[agent], tasks=[task], verbose=False, tracing=False)
        result = crew.kickoff()
        return str(result)
