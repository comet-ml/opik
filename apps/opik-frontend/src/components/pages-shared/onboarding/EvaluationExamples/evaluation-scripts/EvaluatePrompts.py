import opik
from opik.evaluation import evaluate_prompt
from opik.evaluation.metrics import Hallucination
import os

# INJECT_OPIK_CONFIGURATION

# Create a dataset that contains the samples you want to evaluate
opik_client = opik.Opik()
dataset = opik_client.get_or_create_dataset("Evaluation test dataset")
dataset.insert([
    {"input": "Hello, world!", "expected_output": "Hello, world!"},
    {"input": "What is the capital of France?", "expected_output": "Paris"},
])

# Run the evaluation
result = evaluate_prompt(
    dataset=dataset,
    messages=[{"role": "user", "content": "Translate the following text to French: {{input}}"}],
    model="gpt-3.5-turbo",  # or your preferred model
    scoring_metrics=[Hallucination()]
)
