from __future__ import annotations

from opik_optimizer import OptimizableAgent, ChatPrompt
from opik_optimizer.utils import search_wikipedia
from opik_optimizer.utils.llm_logger import LLMLogger
from opik import track
from crewai import Agent, Crew, Task
from crewai.tools import tool as crewai_tool
from langchain_openai import ChatOpenAI

# Setup logger
logger = LLMLogger("crewai", agent_name="CrewAI", suppress=["crewai"])

logger.info("[bold green]═══ CrewAI loaded ═══[/bold green]")


# Define tool using CrewAI's @tool decorator
@crewai_tool("Search Wikipedia")
@track(type="tool")
def search_wikipedia_tool(query: str) -> list[str]:
    """
    Search Wikipedia for information about a topic.
    Use this to find factual information about people, places, events, and concepts.

    Args:
        query: The search query - a topic, person, place, or concept to look up.

    Returns:
        A list of relevant Wikipedia article excerpts.
    """
    with logger.log_tool("search_wikipedia", query):
        return search_wikipedia(query, use_api=True)


class CrewAIAgent(OptimizableAgent):
    """Agent using CrewAI for optimization."""

    project_name: str = "crewai-agent"
    default_model: str = "gpt-4o-mini"

    def __init__(
        self, prompt: ChatPrompt | None = None, project_name: str | None = None
    ) -> None:
        try:
            if prompt is not None:
                super().__init__(prompt, project_name)
            else:
                pass  # Will be initialized later
        except Exception as e:
            logger.agent_error(e, include_traceback=True)
            raise

    def _resolve_system_text(self, prompt: ChatPrompt) -> str:
        """Extract system message from prompt."""
        for message in prompt.get_messages():
            if message.get("role") == "system":
                return message.get("content", "")
        return "You are a helpful research assistant."

    def _extract_latest_user_message(self, messages: list[dict[str, str]]) -> str:
        """Extract the latest user message from the messages list."""
        for message in reversed(messages):
            if message.get("role") == "user":
                return message.get("content", "")
        return ""

    def init_agent(self, prompt: ChatPrompt) -> None:
        """Initialize the CrewAI agent with the provided configuration."""
        self.prompt = prompt
        system_text = self._resolve_system_text(prompt)
        model_name = prompt.model or self.default_model

        logger.agent_init(model=model_name, tools=["search_wikipedia"])

        try:
            # Initialize LLM
            self.llm = ChatOpenAI(model=model_name, temperature=0)

            # Create CrewAI agent with tools
            self.agent = Agent(
                role="Research Assistant",
                goal=system_text,
                backstory="An AI assistant specialized in finding accurate information.",
                tools=[search_wikipedia_tool],
                llm=self.llm,
                verbose=False,
            )
        except Exception as e:
            logger.agent_error(e, include_traceback=True)
            raise

    def invoke(self, messages: list[dict[str, str]], seed: int | None = None) -> str:
        """Execute the agent with the given messages."""
        question = self._extract_latest_user_message(messages)

        with logger.log_invoke(question) as ctx:
            # Create a task for this specific question
            task = Task(
                description=question,
                expected_output="A concise, factual answer based on the information found.",
                agent=self.agent,
            )

            # Create a crew with the agent and task
            crew = Crew(
                agents=[self.agent],
                tasks=[task],
                verbose=False,
                tracing=False,
            )

            # Execute the crew
            result = crew.kickoff()

            # Extract response
            response = str(result)
            ctx["response"] = response
            return response
