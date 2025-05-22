from typing import Any, Callable, Dict, List, Literal, Optional, Tuple
from typing_extensions import TypedDict

from opik_optimizer.demo import get_or_create_dataset
from opik.integrations.langchain import OpikTracer
from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import (
    TaskConfig,
    MetricConfig,
    from_dataset_field,
    from_llm_response_text,
)
from opik_optimizer.langgraph_optimizer import LangGraphOptimizer

from langgraph.graph import StateGraph, START, END
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


class InputState(TypedDict):
    input: str


# Define the schema for the output
class OutputState(TypedDict):
    output: str


# Define the overall schema, combining both input and output
class OverallState(InputState, OutputState):
    pass


project_name = "langchain-agent"
llm = ChatOpenAI(model="gpt-4o", temperature=0, stream_usage=True)
dataset = get_or_create_dataset("hotpot-300")

prompt_template = """Answer the following questions as best you can. You have access to the following tools:

{tools}

Use the following format:

Question: "the input question you must answer"
Thought: "you should always think about what to do"
Action: "the action to take" --- should be one of [{tool_names}]
Action Input: "the input to the action"
Observation: "the result of the action"
... (this Thought/Action/Action Input/Observation can repeat N times)
Thought: "I now know the final answer"
Final Answer: "the final answer to the original input question"

Begin!

Question: {input}
Thought: {agent_scratchpad}"""

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

tools = [
    {
        "name": "Wikipedia Search",
        "func": search_wikipedia,
        "description": "Search wikipedia for abstracts. Gives a brief paragraph about a topic.",
    },
]

task_config = TaskConfig(
    instruction_prompt=prompt_template,
    input_dataset_fields=["question"],
    output_dataset_field="answer",
    tools=tools,
)


class MyLangGraphOptimizer(LangGraphOptimizer):
    def build_agent_invoke(self, agent_config):
        prompt_template = agent_config["prompts"][0]
        prompt = PromptTemplate.from_template(prompt_template)
        tools = agent_config["tools"]

        agent_tools = []
        for tool in tools:
            tool_instance = Tool(
                name=tool["name"], func=tool["func"], description=tool["description"]
            )
            agent_tools.append(tool_instance)

        agent = create_react_agent(llm=self.llm, tools=agent_tools, prompt=prompt)
        agent_executor = AgentExecutor(
            agent=agent,
            tools=agent_tools,
            handle_parsing_errors=True,
            verbose=False,
        )
        workflow = StateGraph(OverallState, input=InputState, output=OutputState)

        def run_agent_node(state: InputState):
            # "input" is from the State
            user_input = state["input"]
            # "input" is from the State
            result = agent_executor.invoke(
                {"input": user_input}, config={"callbacks": [self.opik_tracer]}
            )
            # "input", "output" are from the State
            return {"input": user_input, "output": result["output"]}

        workflow.add_node("agent", run_agent_node)
        workflow.set_entry_point("agent")
        workflow.set_finish_point("agent")
        graph = workflow.compile()

        # Setup the Opik tracker:
        self.opik_tracer = OpikTracer(
            project_name=self.project_name,
            tags=self.tags,
            graph=graph.get_graph(xray=True),
        )

        def agent_invoke(item: Dict[str, Any]) -> Dict[str, Any]:
            # "input" and "output" are Agent State fields
            # FIXME: need to map input_dataset_fields to correct state fields:
            state = {"input": item[key] for key in task_config.input_dataset_fields}
            result = graph.invoke(state)
            return {"output": result["output"]}

        return agent_invoke


optimizer = MyLangGraphOptimizer(
    project_name=project_name,
    tags=["langchain-agent"],
    llm=llm,
    num_threads=16,
)

optimizer.optimize_prompt(
    dataset=dataset,
    metric_config=metric_config,
    task_config=task_config,
    n_samples=10,
)
