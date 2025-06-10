from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import EvolutionaryOptimizer, datasets
from opik_optimizer.optimization_config import chat_prompt


def test_evolutionary_optimizer():
    # Prepare dataset
    dataset = datasets.hotpot_300()

    # Define metric and task configuration (see docs for more options)
    def levenshtein_ratio(dataset_item, llm_output):
        return LevenshteinRatio().score(reference=dataset_item["answer"], output=llm_output)
    
    prompt = chat_prompt.ChatPrompt(
        system="Provide an answer to the question.",
        prompt="{question}"
    )

    optimizer = EvolutionaryOptimizer(
        model="openai/gpt-4o",
        temperature=0.1,
        max_tokens=500000,
        infer_output_style=True,
        population_size=5,
        num_generations=10
    )
    
    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=levenshtein_ratio,
        prompt=prompt,
        n_samples=10
    )

    # Access results
    assert len(results.history) > 0

if __name__ == "__main__":
    test_evolutionary_optimizer()
