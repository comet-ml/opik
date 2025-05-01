from opik.evaluation.metrics import LevenshteinRatio
from opik_optimizer import MiproOptimizer
from opik_optimizer.demo import get_or_create_dataset

from opik_optimizer import (
    OptimizationConfig,
    MetricConfig,
    PromptTaskConfig,
    from_dataset_field,
    from_llm_response_text,
)

opik_dataset = get_or_create_dataset("hotpot-300")
project_name = "optimize-mipro-hotpot-0001"

initial_prompt = "Answer the question"

optimizer = MiproOptimizer(
    model="openai/gpt-4o-mini",  # LiteLLM or OpenAI name
    project_name=project_name,
    temperature=0.1,
    max_tokens=5000,
    num_threads=1,
)

optimization_config = OptimizationConfig(
    dataset=opik_dataset,
    objective=MetricConfig(
        metric=LevenshteinRatio(project_name=project_name),
        inputs={
            "output": from_llm_response_text(),
            "reference": from_dataset_field(name="answer"),
        },
    ),
    task=PromptTaskConfig(
        instruction_prompt=initial_prompt,
        input_dataset_fields=["question"],
        output_dataset_field="answer",
    ),
)

initial_score = optimizer.evaluate_prompt(
    dataset=opik_dataset,
    config=optimization_config,
    prompt=initial_prompt,
)

print("Initial prompt:", initial_prompt)
print("Score:", initial_score)

result = optimizer.optimize_prompt(optimization_config)

print(result)

final_score = optimizer.evaluate_prompt(
    dataset=opik_dataset,
    config=optimization_config,
    prompt=result.prompt,
)

print("Initial prompt:", initial_prompt)
print("Score:", initial_score)

print("Final prompt:", result.prompt)
print("Final score:", final_score)
