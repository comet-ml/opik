from pydantic_ai import Agent, RunContext

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
from opik_optimizer.agent_optimizer import OpikAgentOptimizer, OpikAgent


# Tools:
import dspy

def search_wikipedia(ctx, query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    print(ctx)
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=1
    )
    return results[0]["text"]

project_name = "langchain-agent"
dataset = get_or_create_dataset("hotpot-300")

prompt_template = """Use the `search_wikipedia` function to find details 
on a topic. Respond with a short, concise answer without 
explanation."""

metric_config = MetricConfig(
    metric=LevenshteinRatio(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

task_config = TaskConfig(
    instruction_prompt=prompt_template,
    input_dataset_fields=["question"],
    output_dataset_field="answer",
)

class PydanticAIAgent(OpikAgent):
    def reconfig(self, agent_config):
        self.agent = Agent(  
            'openai:gpt-4o',
            output_type=str,
            system_prompt=agent_config["system-prompt"]["value"],
        )
        self.agent.tool(search_wikipedia)
        

    def invoke(self, dataset_item, input_dataset_field):
        result = self.agent.run_sync(dataset_item[input_dataset_field])
        return {"output": result.output}

agent_config = {
    "chat-prompt": {"type": "chat", "value": []},
    "Wikipedia Search": {
        "type": "tool",
        "value": "Search wikipedia for abstracts. Gives a brief paragraph about a topic.",
        "function": search_wikipedia,
    },
    "system-prompt": {"type": "prompt", "value": prompt_template, "template": True},
}

agent = PydanticAIAgent(agent_config, project_name)

result = agent.invoke(
    {"question": "Which is heavier: a newborn elephant, or a motor boat?"},
    "question",
)
print(result)

optimizer = OpikAgentOptimizer(
    task_config=task_config,
)

metaprompt = """Refine this prompt template to make it better.
Just give me the better prompt, nothing else.
Here is the prompt:

%r
"""

optimizer.optimize_agent(
    agent=agent,
    dataset=dataset,
    metric_config=metric_config,
    n_samples=10,
    num_threads=16,
    metaprompt=metaprompt,
)
