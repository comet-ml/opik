from typing import Any, Dict
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
        query, k=1
    )
    return results[0]["text"]
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


class LangGraphAgent(OptimizableAgent):
    model = "gpt-4o"
    project_name = "langgraph-agent-wikipedia"

    def init_agent(self, agent_config: AgentConfig) -> None:
        self.llm = ChatOpenAI(model=self.model, temperature=0, stream_usage=True)
        self.agent_config = agent_config
        prompt_template = self.agent_config.get_system_prompt()
        prompt = PromptTemplate.from_template(prompt_template)

        agent_tools = []
        for key in self.agent_config.tools:
            item = self.agent_config.tools[key]
            agent_tools.append(
                Tool(
                    name=key,
                    func=item["function"],
                    description=item["description"],
                )
            )
        self.agent = create_react_agent(self.llm, tools=agent_tools, prompt=prompt)
        self.agent_executor = AgentExecutor(
            agent=self.agent,
            tools=agent_tools,
            handle_parsing_errors=True,
            verbose=False,
        )
        self.workflow = StateGraph(OverallState, input=InputState, output=OutputState)

        def run_agent_node(state: InputState) -> OutputState:
            # "input" is from the State
            user_input = state["input"]
            # "input" is from the State
            result = self.agent_executor.invoke(
                {"input": user_input}, config={"callbacks": [self.opik_tracer]}
            )
            # "input", "output" are from the State
            return {"output": result["output"]}

        self.workflow.add_node("agent", run_agent_node)
        self.workflow.set_entry_point("agent")
        self.workflow.set_finish_point("agent")
        self.graph = self.workflow.compile()

        # Setup the Opik tracker:
        self.opik_tracer = OpikTracer(
            project_name=self.project_name,
            graph=self.graph.get_graph(xray=True),
        )

    def invoke_dataset_item(
        self, dataset_item: Dict[str, Any], seed: int | None = None
    ) -> str:
        # First, get the agent messages, replacing parts with dataset item
        all_messages = self.agent_config.chat_prompt.get_messages(dataset_item)
        # Skip first message, as prompt is already part of agent:
        messages = all_messages[1:]
        result = None
        for message in messages:
            result = self.graph.invoke({"input": message["content"]})
        return result["output"] if result else "No result from agent"
