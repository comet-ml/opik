from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    MetaPromptOptimizer,
    MetricConfig,
    datasets,
    from_dataset_field,
    from_llm_response_text,
)
from opik_optimizer.optimization_config import chat_prompt


def test_metaprompt_optimizer():
    # Initialize optimizer
    optimizer = MetaPromptOptimizer(
        model="openai/gpt-4",  # or "azure/gpt-4"
        temperature=0.1,
        max_tokens=5000,
        num_threads=8,
        max_rounds=2,
        num_prompts_per_round=1,
        seed=42
    )

    # Prepare dataset
    dataset = datasets.hotpot_300()

    # Define metric and task configuration (see docs for more options)
    metric_config = MetricConfig(
        metric=LevenshteinRatio(),
        inputs={
            "output": from_llm_response_text(),  # Model's output
            "reference": from_dataset_field(name="answer"),  # Ground truth
        }
    )
    prompt = chat_prompt.ChatPrompt(
        system="Provide an answer to the question.",
        prompt="{question}"
    )
    
    # Run optimization
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric_config=metric_config,
        prompt=prompt,
        n_samples=50
    )

    # Access results
    assert len(results.details['rounds']) > 0

if __name__ == "__main__":
    test_metaprompt_optimizer()
