from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import (
    MetaPromptOptimizer,
    datasets,
)
from opik_optimizer.optimization_config import chat_prompt


def test_metaprompt_optimizer():
    # Prepare dataset
    dataset = datasets.hotpot_300()

    # Define metric and task configuration (see docs for more options)
    def levenshtein_ratio(dataset_item, llm_output):
        return LevenshteinRatio().score(reference=dataset_item["answer"], output=llm_output)
    
    prompt = chat_prompt.ChatPrompt(
        system="Provide an answer to the question.",
        prompt="{question}"
    )
    
    # Initialize optimizer
    optimizer = MetaPromptOptimizer(
        model="openai/gpt-4",  # or "azure/gpt-4"
        temperature=0.1,
        max_tokens=10000,
        num_threads=8,
        rounds=2,
        num_prompts_per_round=4,
        seed=42
    )

    # Run optimization
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=metric,
        prompt=prompt,
        n_samples=50
    )

    # Access results
    assert len(results.details['rounds']) > 0

if __name__ == "__main__":
    test_metaprompt_optimizer()
