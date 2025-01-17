import opik
from opik.evaluation import evaluate_prompt

# Create a dataset that contains the samples you want to evaluate
opik_client = opik.Opik()
dataset = opik_client.get_or_create_dataset("my_dataset")
dataset.insert(
    [
        {"question": "Hello, world!", "expected_output": "Hello, world!"},
        {"question": "What is the capital of France?", "expected_output": "Paris"},
    ]
)

# Run the evaluation
evaluate_prompt(
    dataset=dataset,
    messages=[
        {
            "role": "user",
            "content": "Translate the following text to French: {{question}}",
        },
    ],
    model="gpt-3.5-turbo",
)
