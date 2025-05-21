import dspy

from opik.evaluation.metrics import Equals
from opik_optimizer import MiproOptimizer
from opik_optimizer.demo import get_or_create_dataset, get_litellm_cache
from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)

project_name = "optimize-mipro-hotpot"
opik_dataset = get_or_create_dataset("hotpot-300")
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


metric_config = MetricConfig(
    metric=Equals(project_name=project_name),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="answer"),
    },
)

task_config = TaskConfig(
    instruction_prompt="Answer the question",
    input_dataset_fields=["question"],
    output_dataset_field="answer",
    tools=[search_wikipedia],
)

result = optimizer.optimize_prompt(
    dataset=opik_dataset,
    metric_config=metric_config,
    task_config=task_config,
    n_samples=50,
    auto=None,
)

result.display()
