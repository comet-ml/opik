import dspy
from opik.evaluation.metrics import Equals

from opik_optimizer import MiproOptimizer, TaskConfig
from opik_optimizer.datasets import hotpot_300
from opik_optimizer.demo import get_litellm_cache

project_name = "optimize-mipro-hotpot"
opik_dataset = hotpot_300()
get_litellm_cache("test")

optimizer = MiproOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM or OpenAI name
    temperature=0.1,
    project_name=project_name,
    num_threads=16,
)

# Tools:
def search_wikipedia(query: str) -> list[str]:
    """
    This agent is used to search wikipedia. It can retrieve additional details
    about a topic.
    """
    results = dspy.ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")(
        query, k=3
    )
    return [x["text"] for x in results]

def equals(dataset_item, llm_output):
    metric = Equals()
    return metric.score(reference=dataset_item["answer"], output=llm_output)


task_config = TaskConfig(
    instruction_prompt="Answer the question",
    input_dataset_fields=["question"],
    output_dataset_field="answer",
    tools=[search_wikipedia],
)

result = optimizer.optimize_prompt(
    task_config=task_config,
    metric=equals,
    dataset=opik_dataset,
    n_samples=50,
    auto=None,
)

result.display()
