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
    return [x["text"] for x in results]


from opik.evaluation.metrics import Equals
from opik_optimizer import MiproOptimizer
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    OptimizationConfig,
    MetricConfig,
    PromptTaskConfig,
    from_dataset_field,
    from_llm_response_text,
)

optimizer = MiproOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM or OpenAI name
    temperature=0.0,
    project_name="optimize-mipro-hotpot-benchmark-001",
    num_threads=16,
)

opik_dataset = get_or_create_dataset("hotpot-500")

optimization_config = OptimizationConfig(
    dataset=opik_dataset,
    objective=MetricConfig(
        metric=Equals(),
        inputs={
            "output": from_llm_response_text(),
            "reference": from_dataset_field(name="answer"),
        },
    ),
    task=PromptTaskConfig(
        instruction_prompt="",
        input_dataset_fields=["question"],
        output_dataset_field="answer",
        tools=[search_wikipedia],
    ),
)

result = optimizer.optimize_prompt(optimization_config)
