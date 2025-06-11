from opik.evaluation.metrics import LevenshteinRatio

from opik_optimizer import FewShotBayesianOptimizer, datasets
from opik_optimizer.optimization_config import chat_prompt


def test_few_shot_optimizer():
    # Initialize optimizer
    optimizer = FewShotBayesianOptimizer(
        model="openai/gpt-4",
        temperature=0.1,
        max_tokens=5000,
    )

    # Prepare dataset
    dataset = datasets.hotpot_300()

    # Define metric
    def levenshtein_ratio(dataset_item, llm_output):
        return LevenshteinRatio().score(reference=dataset_item["answer"], output=llm_output)
    

    prompt = chat_prompt.ChatPrompt(
        messages=[
            {"role": "system", "content": "Provide an answer to the question."},
            {"role": "user", "content": "{question}"}
        ],
    )

    results = optimizer.optimize_prompt(
        dataset=dataset,
        metric=levenshtein_ratio,
        prompt=prompt,
        n_trials=2
    )

    # Access results
    assert len(results.history) > 0

if __name__ == "__main__":
    test_few_shot_optimizer()
