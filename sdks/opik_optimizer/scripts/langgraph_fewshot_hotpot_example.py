from typing import Any, Dict
from typing_extensions import TypedDict

from opik.evaluation.metrics.score_result import ScoreResult
from opik.integrations.langchain import OpikTracer
from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import OptimizableAgent, ChatPrompt, FewShotBayesianOptimizer
from opik_optimizer.datasets import hotpot_300

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
        query, k=1
    )
    return results[0]["text"]


def levenshtein_ratio(dataset_item: Dict[str, Any], llm_output: str) -> ScoreResult:
    """
    Calculate the Levenshtein ratio score between dataset answer and LLM output.
    """
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)
    metric = LevenshteinRatio()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


dataset = hotpot_300()


class InputState(TypedDict):
    input: str


# Define the schema for the output
class OutputState(TypedDict):
    output: str


# Define the overall schema, combining both input and output
class OverallState(InputState, OutputState):
    pass


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


class LangGraphAgent(OptimizableAgent):
    model = "gpt-4o"
    project_name = "langgraph-agent-wikipedia"
    input_dataset_field = "question"

    def init_agent(self, agent_config: dict) -> None:
        self.llm = ChatOpenAI(model=self.model, temperature=0, stream_usage=True)
        self.agent_config = agent_config
        prompt_template = self.agent_config["chat_prompt"].get_system_prompt()
        prompt = PromptTemplate.from_template(prompt_template)

        agent_tools = []
        for key in self.agent_config["tools"]:
            item = self.agent_config["tools"][key]
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

    def invoke_dataset_item(self, item: Dict[str, Any], seed: int | None = None) -> str:
        # "input" and "output" are Agent State fields
        messages = self.agent_config["chat_prompt"].messages or []
        state = {"input": item[self.input_dataset_field]}
        messages.append(state)
        result = self.graph.invoke(state)
        return result["output"]


agent_config = {
    "chat_prompt": ChatPrompt(system=prompt_template),
    "tools": {
        "Wikipedia Search": {
            "type": "tool",
            "description": "Search wikipedia for abstracts. Gives a brief paragraph about a topic.",
            "function": search_wikipedia,
        },
    },
}

# Test it:
agent = LangGraphAgent(agent_config)
result = agent.invoke_dataset_item(
    {"question": "Which is heavier: a newborn elephant, or a motor boat?"}
)
print(result)

# Optimize it:
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o",
    min_examples=3,
    max_examples=8,
    n_threads=16,
    seed=42,
)
result = optimizer.optimize_agent(
    agent_class=LangGraphAgent,
    agent_config=agent_config,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=50,
)
