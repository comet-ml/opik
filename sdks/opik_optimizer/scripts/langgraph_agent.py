from typing import Any, List, Dict
from typing_extensions import TypedDict

from opik.integrations.langchain import OpikTracer

from opik_optimizer import (
    OptimizableAgent,
    AgentConfig,
)

from langgraph.graph import StateGraph
from langchain_openai import ChatOpenAI
from langchain.agents import Tool, create_react_agent, AgentExecutor
from langchain_core.prompts import PromptTemplate

# Tools:
import dspy


def search_wikipedia(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=3
    )
    return [item["text"] for item in results]


class InputState(TypedDict):
    input: str


# Define the schema for the output
class OutputState(TypedDict):
    output: str


# Define the overall schema, combining both input and output
class OverallState(InputState, OutputState):
    pass


def create_graph(project_name: str, prompt_template: str) -> Any:
    llm = ChatOpenAI(model="gpt-4o", temperature=0, stream_usage=True)

    agent_tools = [
        Tool(
            name="Search Wikipedia",
            func=search_wikipedia,
            description="""This agent is used to search wikipedia. It can retrieve additional details about a topic.""",
        )
    ]
    # We'll use the prompt in the chat-prompt:
    prompt = PromptTemplate.from_template(prompt_template)
    agent = create_react_agent(llm, tools=agent_tools, prompt=prompt)
    agent_executor = AgentExecutor(
        agent=agent,
        tools=agent_tools,
        handle_parsing_errors=True,
        verbose=False,
    )
    workflow = StateGraph(OverallState, input=InputState, output=OutputState)
    # We'll set this below:
    opik_tracer = None

    def run_agent_node(state: InputState) -> OutputState:
        # "input" is from the State
        user_input = state["input"]
        # "input" is from the State
        result = agent_executor.invoke(
            {"input": user_input}, config={"callbacks": [opik_tracer]}
        )
        # "input", "output" are from the State
        return {"output": result["output"]}

    workflow.add_node("agent", run_agent_node)
    workflow.set_entry_point("agent")
    workflow.set_finish_point("agent")
    graph = workflow.compile()

    # Setup the Opik tracker:
    opik_tracer = OpikTracer(
        project_name=project_name,
        graph=graph.get_graph(xray=True),
    )
    return graph


class LangGraphAgent(OptimizableAgent):
    project_name = "langgraph-agent"

    def init_agent(self, agent_config: AgentConfig) -> None:
        self.agent_config = agent_config
        self.graph = create_graph(
            self.project_name,
            self.agent_config.chat_prompt.get_messages()[0]["content"],
        )

    def invoke(self, messages: List[Dict[str, str]], seed: int | None = None) -> str:
        if len(messages) > 1:
            # Skip the system prompt
            messages = messages[1:]
        for message in messages:
            result = self.graph.invoke({"input": message["content"]})

        return result["output"] if result else "No result from agent"
